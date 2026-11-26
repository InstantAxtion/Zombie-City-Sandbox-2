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

    private String[] replayNames() {
        String list = g0.pf.pref("replays", "");
        if (list.length() == 0) return new String[0];
        return list.split(",");
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
        String[] names = replayNames();
        if (names.length == 0) {
            ui.wrap("No replays yet. In a game, open the pause menu and choose Save replay: the whole outbreak, from its start, "
                    + "is kept in a few kilobytes and plays back exactly.", m, y + ui.dp(20), W - m * 2, ui.dp(13), Ui.DIM, 6);
            return;
        }
        for (String n : names) {
            if (!g0.pf.exists(n)) continue;
            ui.card("rp:" + n, m, y, W - m * 2, ui.dp(66), "Replay", g0.pf.pref("replay.title." + n, n), false);
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
        float size = Math.min(W - m * 2, H - ui.dp(220));
        float x0 = (W - size) / 2, y0 = ui.dp(60);
        int step = g0.view.map.step();
        g.image(g0.view.map.regionImage, x0, y0, size, size);
        g.color(0x60000000);
        g.fillRect(x0, y0, size, size);
        Sectors sec = w.sec;
        float cs = size / sec.sw;
        byte[] zd = d.lapseDead.get(frame), pp = d.lapsePeople.get(frame);
        for (int s = 0; s < sec.n; s++) {
            int pv = pp[s] & 255, zv = zd[s] & 255;
            float sx = x0 + (s % sec.sw) * cs, sy = y0 + (s / sec.sw) * cs;
            if (pv > 0) {
                g.color(((int) (pv * 0.6f) << 24) | 0xE8E0C8);
                g.fillRect(sx, sy, cs + 0.5f, cs + 0.5f);
            }
            if (zv > 0) {
                g.color((Math.min(230, 60 + zv) << 24) | 0xD01818);
                g.fillRect(sx, sy, cs + 0.5f, cs + 0.5f);
            }
        }
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_CITY) continue;
            g.color(0xFFFFFFFF);
            g.text(s.name, x0 + s.cx * size / w.r.W, y0 + s.cy * size / w.r.H, ui.dp(11), Gfx.ALIGN_CENTER, true);
        }
        float t = d.lapseTime.get(frame);
        int dead = 0, people = 0;
        for (int s = 0; s < sec.n; s++) {
            dead += Director.unlog(zd[s]);
            people += Director.unlog(pp[s]);
        }
        float y = y0 + size + ui.dp(18);
        g.color(Ui.TEXT);
        int day = 1 + (int) ((World.START_HOUR + t / 60) / 24);
        int hour = (int) ((World.START_HOUR + t / 60) % 24);
        g.text("Day " + day + ", " + String.format("%02d:00", hour), m, y, ui.dp(15), Gfx.ALIGN_LEFT, true);
        g.color(0xFFFF8080);
        g.text("about " + WorldView.people(dead) + " dead", W - m, y, ui.dp(13), Gfx.ALIGN_RIGHT, true);
        y += ui.dp(18);
        g.color(0xFFE8E0C8);
        g.text("about " + WorldView.people(people) + " people", W - m, y, ui.dp(12), Gfx.ALIGN_RIGHT, false);
        y += ui.dp(14);
        // the scrubber
        float bw = W - m * 2;
        g.color(0xFF2A323C);
        g.fillRect(m, y, bw, ui.dp(10));
        g.color(Ui.ACCENT);
        g.fillRect(m, y, bw * (frame + 1) / n, ui.dp(10));
        ui.area("tl:scrub", m, y - ui.dp(10), bw, ui.dp(30));
        scrubX = m;
        scrubW = bw;
        y += ui.dp(26);
        float hb = (W - m * 2 - ui.dp(16)) / 3;
        ui.button("tl:start", m, y, hb, ui.dp(40), "Start", false, 13);
        ui.button("tl:play", m + hb + ui.dp(8), y, hb, ui.dp(40), playing ? "Pause" : "Play", playing, 13);
        ui.button("tl:end", m + (hb + ui.dp(8)) * 2, y, hb, ui.dp(40), "Now", false, 13);
    }

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
        float rh = ui.dp(40);
        for (String[] r : rows) {
            g.color(Ui.TEXT);
            g.text(r[1], m, y + rh / 2 + ui.dp(5), ui.dp(13), Gfx.ALIGN_LEFT, false);
            ui.button(r[0], W - m - ui.dp(170), y + ui.dp(3), ui.dp(170), rh - ui.dp(6), r[2], false, 12);
            y += rh;
        }
        g.color(Ui.DIM);
        g.text(edX < 0 ? "Tap the map where it starts (or it starts somewhere random)" : "It starts where the red ring is. Tap to move it.", m, y + ui.dp(14), ui.dp(11), Gfx.ALIGN_LEFT, false);
        y += ui.dp(22);
        float startH = ui.dp(48);
        float ps = Math.min(W - m * 2, H - y - startH - ui.dp(20));
        mapX = (W - ps) / 2;
        mapY = y;
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
        ui.bigButton("ed:save", m, H - startH - ui.dp(12), W - m * 2, startH, "Save it", Ui.ACCENT);
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
