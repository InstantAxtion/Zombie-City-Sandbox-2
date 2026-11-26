package com.instantaxtion.zcs2;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * The director's screens: saved replays, the timelapse of the national map, and the scenario editor.
 * Kept apart from Game so its screens stay readable; it uses Game's interface kit and state.
 */
final class DirectorScreens {
    private final Game g0;

    DirectorScreens(Game game) {
        g0 = game;
    }

    // ------------------------------------------------------------------ replays

    /** The replays saved from the game, newest first (from the pref), then any other .zcr file in the app's files. */
    private String[] replayNames() {
        String list = g0.pf.pref("replays", "");
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (list.length() > 0) for (String n : list.split(",")) if (n.length() > 0 && !out.contains(n)) out.add(n);
        return out.toArray(new String[0]);
    }

    private String[] listed;
    private long listedAt;

    /** Every replay to show: the saved list, plus .zcr files copied in or whose list entry was lost (checked once a second). */
    private String[] allReplays() {
        long now = System.currentTimeMillis();
        if (listed != null && now - listedAt < 1000) return listed;
        listedAt = now;
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String n : replayNames()) if (g0.pf.exists(n)) out.add(n);
        java.util.List<String> extra = new java.util.ArrayList<String>();
        try {
            for (String n : g0.pf.list()) if (n.endsWith(".zcr") && !out.contains(n)) extra.add(n);
        } catch (Throwable ignored) {
        }
        java.util.Collections.sort(extra);
        java.util.Collections.reverse(extra);
        out.addAll(extra);
        listed = out.toArray(new String[0]);
        return listed;
    }

    void saveReplay() {
        World w = g0.w;
        if (w == null) return;
        if (g0.replay != null) {
            g0.toast("This is already a replay");
            return;
        }
        String name = "replay_" + Math.abs(System.currentTimeMillis() % 1000000000L) + ".zcr";
        try {
            OutputStream out = g0.pf.openWrite(name);
            Replay.save(w, out);
            out.close();
            String[] old = replayNames();
            StringBuilder sb = new StringBuilder(name);
            // keep the ten newest
            for (int i = 0; i < old.length; i++) {
                if (i >= 9) {
                    g0.pf.delete(old[i]);
                    continue;
                }
                sb.append(',').append(old[i]);
            }
            g0.pf.setPref("replays", sb.toString());
            g0.pf.setPref("replay.title." + name, (w.scenario != null ? w.scenario.name : "Sandbox") + ", " + w.r.name + ", " + w.clock()
                    + ", as " + Roles.NAMES[w.roles.role] + ". " + w.director.events.size() + " inputs");
            listed = null;
            g0.toast("Replay saved");
        } catch (Exception e) {
            g0.toast("Couldn't save the replay: " + e.getMessage());
        }
    }

    void replays(Gfx g) {
        Ui ui = g0.ui;
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(14);
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("Replays", m + ui.dp(84), ui.dp(34));
        float y = ui.dp(64);
        String[] names = allReplays();
        if (names.length == 0) {
            ui.wrap("No replays yet. In a game, open the pause menu and choose Save replay: the whole outbreak, from its start, "
                    + "is kept in a few kilobytes and plays back exactly.", m, y + ui.dp(20), W - m * 2, ui.dp(13), Ui.DIM, 6);
            return;
        }
        for (String n : names) {
            if (!g0.pf.exists(n)) continue;
            String title = g0.pf.pref("replay.title." + n, "");
            ui.card("rp:" + n, m, y, W - m * 2, ui.dp(66), "Replay", title.length() > 0 ? title : n + " (copied in)", false);
            y += ui.dp(74);
        }
    }

    // ------------------------------------------------------------------ timelapse

    private int frame;
    private float frameT;
    private boolean playing = true;

    void openTimelapse() {
        frame = 0;
        frameT = 0;
        playing = true;
    }

    void timelapse(Gfx g, float dt) {
        Ui ui = g0.ui;
        World w = g0.w;
        int W = g.width(), H = g.height();
        g.color(0xFF0B0E12);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(12);
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("Timelapse", m + ui.dp(84), ui.dp(34));
        Director d = w.director;
        int n = d.lapseTime.size();
        if (n == 0) {
            ui.wrap("The timelapse fills in as the game goes on: a picture of the country every game hour.", m, ui.dp(80), W - m * 2, ui.dp(13), Ui.DIM, 4);
            return;
        }
        if (playing) {
            frameT += dt;
            float per = n > 200 ? 0.04f : 0.12f;
            while (frameT >= per) {
                frameT -= per;
                frame = (frame + 1) % n;
            }
        }
        frame = Math.max(0, Math.min(n - 1, frame));
        boolean land = W > H * 1.15f;
        float size = land ? Math.min(H - ui.dp(70), W * 0.55f) : Math.min(W - m * 2, H - ui.dp(260));
        // upright, the map and its controls sit in the middle of the screen, not at the top of an empty one
        float x0 = land ? m : (W - size) / 2, y0 = land ? ui.dp(60) : Math.max(ui.dp(60), (H - size - ui.dp(180)) / 2 + ui.dp(20));
        g.image(g0.view.map.regionImage, x0, y0, size, size);
        g.color(0x70000000);
        g.fillRect(x0, y0, size, size);
        Sectors sec = w.sec;
        byte[] zd = d.lapseDead.get(frame), pp = d.lapsePeople.get(frame);
        // the hour's picture: people as a warm haze, the dead as red, smoothed
        int ln = d.lapseN;
        if (lapseImg == null || lapsePx.length != ln) {
            lapsePx = new int[ln];
            lapseImg = g0.pf.newImage(d.lapseW, d.lapseH);
            lapseShown = -1;
        }
        if (lapseShown != frame) {
            for (int s = 0; s < ln; s++) {
                int pv = pp[s] & 255, zv = zd[s] & 255;
                int c = 0;
                if (zv > 0) c = (Math.min(235, 70 + zv) << 24) | 0xD81C1C;
                else if (pv > 0) c = ((int) Math.min(200, pv * 0.7f) << 24) | 0xF0E2C0;
                lapsePx[s] = c;
            }
            lapseImg.setPixels(lapsePx, d.lapseW, d.lapseH);
            lapseShown = frame;
        }
        int cellTiles = w.r.sect * (d.coarse ? SectorIndex.D : 1);
        g.image(lapseImg, x0, y0, size * d.lapseW * cellTiles / w.r.W, size * d.lapseH * cellTiles / w.r.H);
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_CITY) continue;
            g.color(0xFFFFFFFF);
            g.textHalo(s.name, x0 + s.cx * size / w.r.W, y0 + s.cy * size / w.r.H, ui.dp(11.5f), Gfx.ALIGN_CENTER, true, 0xD0000000, ui.dp(1.3f));
        }
        float t = d.lapseTime.get(frame);
        long dead = 0, people = 0;
        for (int s = 0; s < ln; s++) {
            dead += Director.unlog(zd[s]);
            people += Director.unlog(pp[s]);
        }
        float cx = land ? x0 + size + m * 2 : m, cw = land ? W - cx - m : W - m * 2;
        float y = land ? y0 + ui.dp(22) : y0 + size + ui.dp(26);
        g.color(Ui.TEXT);
        g.text(Game.clockAt(t), cx, y, ui.dp(Ui.L + 3), Gfx.ALIGN_LEFT, true);
        y += ui.dp(24);
        // the hours kept are rounded (a byte a sector); the latest is the count the game has now, as on the HUD
        boolean now = frame == n - 1;
        if (now) {
            dead = w.totalZombies();
            people = w.livingCivilians();
        }
        g.color(0xFFFF8080);
        g.text((now ? "" : "about ") + WorldView.people(dead) + " dead", cx, y, ui.dp(Ui.M), Gfx.ALIGN_LEFT, true);
        g.color(0xFFE8E0C8);
        g.text((now ? "" : "about ") + WorldView.people(people) + " people", cx + cw, y, ui.dp(Ui.M), Gfx.ALIGN_RIGHT, false);
        y += ui.dp(26);
        // the scrubber: a track with a mark at each day, and a knob
        float bw = cw, ty = y + ui.dp(8);
        g.color(0xFF2A323C);
        g.fillRoundRect(cx, ty - ui.dp(4), bw, ui.dp(8), ui.dp(4));
        g.color(Ui.ACCENT);
        g.fillRoundRect(cx, ty - ui.dp(4), Math.max(ui.dp(8), bw * (frame + 1) / n), ui.dp(8), ui.dp(4));
        float tFirst = d.lapseTime.get(0), tLast = Math.max(tFirst + 1, d.lapseTime.get(n - 1));
        for (int day = 1; day < 400; day++) {
            float tm = (day * 24 - World.START_HOUR) * 60;
            if (tm <= tFirst) continue;
            if (tm > tLast) break;
            float tx = cx + bw * (tm - tFirst) / (tLast - tFirst);
            g.color(0x90FFFFFF);
            g.fillRect(tx, ty - ui.dp(9), 1, ui.dp(18));
            if ((tLast - tFirst) / 60 / 24 < 12 || day % 5 == 0) {
                g.color(Ui.DIM);
                g.text("Day " + (day + 1), tx, ty + ui.dp(22), ui.dp(Ui.S), Gfx.ALIGN_CENTER, false);
            }
        }
        float kx = cx + bw * (frame + 0.5f) / n;
        g.color(0xFFFFFFFF);
        g.fillCircle(kx, ty, ui.dp(9));
        g.color(Ui.ACCENT);
        g.fillCircle(kx, ty, ui.dp(5));
        ui.area("tl:scrub", cx, ty - ui.dp(20), bw, ui.dp(40));
        scrubX = cx;
        scrubW = bw;
        y += ui.dp(46);
        float hb = (cw - ui.dp(16)) / 3;
        ui.button("tl:start", cx, y, hb, ui.dp(44), "Start", false, 13);
        ui.button("tl:play", cx + hb + ui.dp(8), y, hb, ui.dp(44), playing ? "Pause" : "Play", playing, 13);
        ui.button("tl:end", cx + (hb + ui.dp(8)) * 2, y, hb, ui.dp(44), "Now", false, 13);
    }

    private GImage lapseImg;
    private int[] lapsePx;
    private int lapseShown = -1;

    private float scrubX, scrubW;

    // ------------------------------------------------------------------ the scenario editor

    private static final int[] ZOMBIES = {25, 100, 500, 2000, 10000, 50000, 200000};
    private static final int[] HORDES = {0, 1, 2, 4, 6};
    private static final int[] HORDE_SIZE = {1000, 5000, 20000, 60000};
    private static final String[] GOALS = {"", "holdcity", "evaccoast", "retakestate", "protectcap", "survive"};
    private static final String[] GOAL_NAMES = {"No goal (sandbox)", "Hold the capital", "Evacuate the coast", "Retake a state", "Protect the capital", "Survive"};
    private static final int[] DAYS = {2, 3, 7, 14, 30};
    private static final int[] SEASONS = {80, 172, 243, 355};
    private static final String[] SEASON_NAMES = {"Spring", "Summer", "Autumn", "Winter"};

    private float edX = -1, edY = -1;
    private int edZ = 2, edH, edHS = 1, edGoal, edDays = 2, edSeason = 2, edRole;
    private float mapX, mapY, mapS;

    void openEditor() {
        g0.screen = Game.S_EDITOR;
        edX = edY = -1;
    }

    void editor(Gfx g) {
        Ui ui = g0.ui;
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(12);
        ui.button("ed:back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("Make your own", m + ui.dp(84), ui.dp(34));
        float y = ui.dp(56);
        String[][] rows = {
                {"ed:z", "The dead at the start", WorldView.people(ZOMBIES[edZ])},
                {"ed:h", "Hordes at the edges", HORDES[edH] == 0 ? "None" : HORDES[edH] + " of " + WorldView.people(HORDE_SIZE[edHS])},
                {"ed:hs", "Horde size", WorldView.people(HORDE_SIZE[edHS])},
                {"ed:goal", "Goal", GOAL_NAMES[edGoal]},
                {"ed:days", "Days", DAYS[edDays] + ""},
                {"ed:season", "Season", SEASON_NAMES[edSeason]},
                {"ed:role", "Play as", Roles.NAMES[edRole]}};
        // sideways: the settings and Save on the left, the map on the right; upright: one above the other
        boolean land = W > H * 1.15f;
        float startH = ui.dp(48);
        float rh = ui.dp(land ? 36 : 40);
        float colW = land ? Math.min(ui.dp(360), (W - m * 3) / 2) : W - m * 2;
        for (String[] r : rows) {
            g.color(Ui.TEXT);
            g.text(Ui.fit(g, r[1], colW - ui.dp(160), ui.dp(13), false), m, y + rh / 2 + ui.dp(5), ui.dp(13), Gfx.ALIGN_LEFT, false);
            ui.button(r[0], m + colW - ui.dp(150), y + ui.dp(3), ui.dp(150), rh - ui.dp(6), r[2], false, 12);
            y += rh;
        }
        String hint = edX < 0 ? "Tap the map where it starts (or it starts somewhere random)" : "It starts where the red ring is. Tap to move it.";
        float ps;
        if (land) {
            float mx = m * 2 + colW, top = ui.dp(10);
            float hw = W - mx - m;
            g.color(Ui.DIM);
            g.text(Ui.fit(g, hint, hw, ui.dp(11), false), mx, top + ui.dp(12), ui.dp(11), Gfx.ALIGN_LEFT, false);
            ps = Math.min(hw, H - top - ui.dp(22) - m);
            mapX = mx + (hw - ps) / 2;
            mapY = top + ui.dp(22);
        } else {
            g.color(Ui.DIM);
            g.text(Ui.fit(g, hint, W - m * 2, ui.dp(11), false), m, y + ui.dp(14), ui.dp(11), Gfx.ALIGN_LEFT, false);
            y += ui.dp(22);
            ps = Math.min(W - m * 2, H - y - startH - ui.dp(20));
            mapX = (W - ps) / 2;
            mapY = y;
        }
        mapS = ps;
        if (ps > ui.dp(60)) {
            g0.drawPreview(g, mapX, mapY, ps);
            ui.area("ed:map", mapX, mapY, ps, ps);
            if (edX >= 0) {
                g.color(0xFFFF3030);
                g.strokeCircle(mapX + edX * ps, mapY + edY * ps, ui.dp(9), ui.dp(2.5f));
                g.fillCircle(mapX + edX * ps, mapY + edY * ps, ui.dp(3));
            }
        }
        float sy = Math.max(H - startH - ui.dp(12), land ? y + ui.dp(8) : 0);
        ui.bigButton("ed:save", m, sy, colW, startH, "Save it", Ui.ACCENT);
    }

    /** Builds the scenario the editor describes. */
    Content.Scenario editorScenario(int number) {
        Content.Scenario sc = new Content.Scenario();
        sc.id = "custom" + number;
        sc.name = "My scenario " + number;
        String goal = GOALS[edGoal];
        sc.mode = edRole == Roles.SURVIVOR || edRole == Roles.OFFICER ? "survivor" : goal.length() > 0 && !goal.equals("survive") ? "commander" : "sandbox";
        sc.origin = edX < 0 ? "random" : "xy:" + edX + "," + edY;
        sc.goal = goal;
        sc.zombies = ZOMBIES[edZ];
        sc.hordes = HORDES[edH];
        sc.hordeSize = HORDE_SIZE[edHS];
        sc.days = goal.length() > 0 ? DAYS[edDays] : 0;
        sc.season = SEASONS[edSeason];
        sc.role = Roles.IDS[edRole];
        sc.desc = WorldView.people(sc.zombies) + " of the dead" + (sc.hordes > 0 ? ", " + sc.hordes + (sc.hordes == 1 ? " horde of " : " hordes of ") + WorldView.people(sc.hordeSize) : "")
                + ", " + SEASON_NAMES[edSeason].toLowerCase() + (goal.length() > 0 ? ". Goal: " + GOAL_NAMES[edGoal].toLowerCase() + " for " + sc.days + " days" : "")
                + ". As " + Roles.NAMES[edRole].toLowerCase() + ".";
        return sc;
    }

    private void saveEditor() {
        Platform pf = g0.pf;
        StringBuilder all = new StringBuilder();
        int number = 1;
        try {
            if (pf.exists(Game.CUSTOM)) {
                InputStream in = pf.openRead(Game.CUSTOM);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int k;
                while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
                in.close();
                all.append(bo.toString("UTF-8"));
                for (Content.Scenario s : g0.content.scenarios) if (s.custom) number++;
            }
            Content.Scenario sc = editorScenario(number);
            all.append('\n').append(sc.toIni());
            OutputStream out = pf.openWrite(Game.CUSTOM);
            out.write(all.toString().getBytes("UTF-8"));
            out.close();
            g0.content.addScenarios(new java.io.ByteArrayInputStream(sc.toIni().getBytes("UTF-8")), true);
            g0.ngMode = 3;
            java.util.List<Content.Scenario> mine = g0.scenariosFor(3);
            g0.ngScenario = Math.max(0, mine.size() - 1);
            g0.ngRole = -1;
            g0.screen = Game.S_NEW;
            g0.toast("Saved as " + sc.name);
        } catch (Exception e) {
            g0.toast("Couldn't save it: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ buttons

    /** Handles this class's buttons; false if the id isn't one of them. */
    boolean onButton(String id) {
        if (id.startsWith("rp:")) {
            g0.playReplay(id.substring(3));
            return true;
        }
        if (id.startsWith("tl:")) {
            int n = g0.w.director.lapseTime.size();
            switch (id) {
                case "tl:start": frame = 0; break;
                case "tl:end": frame = Math.max(0, n - 1); playing = false; break;
                case "tl:play": playing = !playing; break;
                case "tl:scrub":
                    frame = (int) Math.max(0, Math.min(n - 1, (g0.upX - scrubX) / scrubW * n));
                    playing = false;
                    break;
                default:
            }
            return true;
        }
        if (!id.startsWith("ed:")) return false;
        switch (id) {
            case "ed:back": g0.screen = Game.S_NEW; break;
            case "ed:z": edZ = (edZ + 1) % ZOMBIES.length; break;
            case "ed:h": edH = (edH + 1) % HORDES.length; break;
            case "ed:hs": edHS = (edHS + 1) % HORDE_SIZE.length; break;
            case "ed:goal": edGoal = (edGoal + 1) % GOALS.length; break;
            case "ed:days": edDays = (edDays + 1) % DAYS.length; break;
            case "ed:season": edSeason = (edSeason + 1) % SEASONS.length; break;
            case "ed:role": edRole = (edRole + 1) % Roles.NAMES.length; break;
            case "ed:map":
                edX = Math.max(0, Math.min(1, (g0.upX - mapX) / mapS));
                edY = Math.max(0, Math.min(1, (g0.upY - mapY) / mapS));
                break;
            case "ed:save": saveEditor(); break;
            default:
        }
        return true;
    }
}
