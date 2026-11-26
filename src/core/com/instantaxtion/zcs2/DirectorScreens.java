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

    // ------------------------------------------------------------------ the replay's controls

    float rbX0, rbX1;

    /** The height of the replay's bar (a clock line, the scrubber, a row of buttons). */
    float replayBarH(boolean land) {
        return g0.ui.dp(land ? 92 : 104);
    }

    /**
     * Under the map while a replay plays: the clock and the end, a scrubber (snapshots as ticks, the camera's
     * keyframes as diamonds; tap anywhere to go there), and back an hour, play or pause, step, speed, a keyframe, the
     * camera path on or off, and save as a director's cut.
     */
    void replayBar(Gfx g, float x0, float y0, float bw, float bh) {
        Ui ui = g0.ui;
        Replay r = g0.replay;
        World w = g0.w;
        if (r == null || w == null) return;
        g.color(Ui.BAR);
        g.fillRect(x0, y0, bw, bh);
        g.color(Ui.LINE);
        g.fillRect(x0, y0, bw, ui.dp(1));
        ui.block(x0, y0, bw, bh);
        float m = ui.dp(12);
        float ts = ui.dp(Ui.S + 0.5f);
        float ly = y0 + ui.dp(17);
        long end = Math.max(1, r.endTick);
        g.color(Ui.TEXT);
        g.text(Game.clockAt(w.time), x0 + m, ly, ts, Gfx.ALIGN_LEFT, true);
        g.color(Ui.DIM);
        g.text("of " + Game.clockAt(r.endTick / 20.0), x0 + bw - m, ly, ts, Gfx.ALIGN_RIGHT, false);
        // in the middle: going somewhere, or the camera path
        float prog = g0.seekProgress();
        String mid = prog >= 0 ? (prog <= 0 ? "Going back..." : "Going there... " + (int) (prog * 100) + "%")
                : r.path.size() == 0 ? "Move the camera, then +Key" : r.path.size() + (r.path.size() == 1 ? " keyframe" : " keyframes") + (g0.pathOn ? ", following" : "");
        g.color(prog >= 0 ? Ui.WARN : Ui.DIM);
        float midW = bw - ui.dp(230);
        if (midW > ui.dp(60)) g.text(Ui.fit(g, mid, midW, ts, false), x0 + bw / 2, ly, ts, Gfx.ALIGN_CENTER, false);
        // the track
        float tx0 = x0 + m + ui.dp(4), tx1 = x0 + bw - m - ui.dp(4), cy = y0 + ui.dp(38), tw = tx1 - tx0;
        rbX0 = tx0;
        rbX1 = tx1;
        g.color(0xFF2A323C);
        g.fillRoundRect(tx0, cy - ui.dp(4), tw, ui.dp(8), ui.dp(4));
        float done = Math.min(1, w.tick / (float) end);
        g.color(Ui.ACCENT);
        g.fillRoundRect(tx0, cy - ui.dp(4), Math.max(ui.dp(8), tw * done), ui.dp(8), ui.dp(4));
        // day marks
        double endMin = end / 20.0;
        for (int day = 1; day < 400; day++) {
            double tm = (day * 24 - World.START_HOUR) * 60;
            if (tm >= endMin) break;
            float x = tx0 + (float) (tw * tm / endMin);
            g.color(0x90FFFFFF);
            g.fillRect(x, cy - ui.dp(8), Math.max(1, ui.dp(1)), ui.dp(16));
        }
        // the snapshots kept (where a seek back starts from)
        g.color(0xB0A0C4FF);
        for (Replay.Snap s : r.snaps) {
            float x = tx0 + tw * Math.min(1, s.tick / (float) end);
            g.fillRect(x - ui.dp(0.75f), cy + ui.dp(6), ui.dp(1.5f), ui.dp(5));
        }
        // the camera's keyframes
        for (int k = 0; k < r.path.size(); k++) {
            float x = tx0 + tw * Math.min(1, r.path.ticks.get(k) / (float) end), d = ui.dp(5);
            g.color(0xFF000000);
            g.fillCircle(x, cy - ui.dp(11), d + ui.dp(1.5f));
            g.color(0xFFFFD040);
            g.fillCircle(x, cy - ui.dp(11), d);
        }
        if (g0.seekTarget >= 0) {
            float gx = tx0 + tw * Math.min(1, g0.seekTarget / (float) end);
            g.color(0x80FFFFFF);
            g.fillCircle(gx, cy, ui.dp(8));
        }
        float kx = tx0 + tw * done;
        g.color(0xFFFFFFFF);
        g.fillCircle(kx, cy, ui.dp(9));
        g.color(Ui.ACCENT);
        g.fillCircle(kx, cy, ui.dp(5));
        ui.area("rb:scrub", tx0 - ui.dp(10), cy - ui.dp(18), tw + ui.dp(20), ui.dp(34));
        // the buttons
        boolean onKey = keyAt(r, w.tick) >= 0;
        String[][] b = {{"rb:back", "-1h"}, {"rb:play", g0.paused ? "Play" : "Pause"}, {"rb:step", "Step"},
                {"rb:speed", Game.SPEED_NAMES[g0.speedIdx]}, {"rb:key", onKey ? "-Key" : "+Key"}, {"rb:path", g0.pathOn && r.path.size() > 0 ? "Path on" : "Path"},
                {"rb:cut", "Cut"}};
        float gap = ui.dp(4), by = cy + ui.dp(18), bh2 = y0 + bh - by - ui.dp(8);
        float each = (bw - m * 2 + gap) / b.length;
        for (int k = 0; k < b.length; k++) {
            boolean on = b[k][0].equals("rb:path") && g0.pathOn && r.path.size() > 0 || b[k][0].equals("rb:play") && !g0.paused;
            ui.button(b[k][0], x0 + m + k * each, by, each - gap, bh2, b[k][1], on, 12);
        }
    }

    /** The keyframe at this tick (within a game minute), or -1. */
    private static int keyAt(Replay r, long tick) {
        for (int k = 0; k < r.path.size(); k++) if (Math.abs(r.path.ticks.get(k) - tick) < 20) return k;
        return -1;
    }

    /** The replay again with its camera path, as a new replay in the list: a director's cut. */
    void saveCut() {
        Replay r = g0.replay;
        if (r == null) return;
        String name = "cut_" + Math.abs(System.currentTimeMillis() % 1000000000L) + ".zcr";
        try {
            OutputStream out = g0.pf.openWrite(name);
            r.saveCut(out);
            out.close();
            String[] old = replayNames();
            StringBuilder sb = new StringBuilder(name);
            for (int i = 0; i < old.length; i++) {
                if (i >= 9) {
                    g0.pf.delete(old[i]);
                    continue;
                }
                sb.append(',').append(old[i]);
            }
            g0.pf.setPref("replays", sb.toString());
            g0.pf.setPref("replay.title." + name, "Director's cut: " + r.title + ". " + r.path.size() + " camera keyframes");
            listed = null;
            g0.toast("Director's cut saved, with " + r.path.size() + " camera keyframes");
        } catch (Exception e) {
            g0.toast("Couldn't save it: " + e.getMessage());
        }
    }

    private boolean replayButton(String id) {
        Replay r = g0.replay;
        World w = g0.w;
        if (r == null || w == null) return true;
        switch (id) {
            case "rb:scrub": {
                float f = Math.max(0, Math.min(1, (g0.upX - rbX0) / Math.max(1, rbX1 - rbX0)));
                g0.seekTo((long) (f * r.endTick));
                break;
            }
            case "rb:back": g0.seekTo(w.tick - 1200); break;
            case "rb:play": g0.paused = !g0.paused; break;
            case "rb:step":
                // a game minute on (a fast-forward step where the replay is fast-forwarding)
                g0.paused = true;
                g0.seekTo(w.tick + (w.strategic ? 200 : 20));
                break;
            case "rb:speed": g0.speedIdx = (g0.speedIdx + 1) % 5; break;
            case "rb:key": {
                int k = keyAt(r, w.tick);
                if (k >= 0) {
                    r.path.remove(k);
                    g0.toast("Keyframe removed");
                } else {
                    r.path.add(w.tick, g0.cam.cx, g0.cam.cy, g0.cam.zoom);
                    // (the camera stays yours while the path is made: Path plays it back)
                    g0.pathOn = false;
                    g0.toast("Keyframe " + r.path.size() + " at " + Game.clockAt(w.time) + ". Path plays the camera along them");
                }
                break;
            }
            case "rb:path":
                if (r.path.size() == 0) g0.toast("No camera path yet: move the camera where you want it, then +Key");
                else g0.pathOn = !g0.pathOn;
                break;
            case "rb:cut": saveCut(); break;
            default:
        }
        return true;
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
    private static final String[] GOAL_NAMES = {"No campaign goal", "Hold the capital", "Evacuate the coast", "Retake a state", "Protect the capital", "Survive"};
    private static final int[] DAYS = {2, 3, 7, 14, 30};
    private static final int[] SEASONS = {80, 172, 243, 355};
    private static final String[] SEASON_NAMES = {"Spring", "Summer", "Autumn", "Winter"};
    static final String[] STRAIN_NAMES = {"The original", "Runners", "Armoured", "Stalkers"};
    static final String[] TABS = {"Start", "Places", "Rules", "Goals", "Triggers"};
    private static final String[] PLACE_KINDS = {"Outbreak point", "Squad", "Defence"};
    private static final int[] HOLD_DAYS = {0, 1, 3, 7, 14, 30};
    private static final int[] KEEP_PCT = {25, 50, 75, 90};
    private static final int[] LOSSES = {0, 1000, 10000, 100000, 1000000};
    private static final String[] WHEN_NAMES = {"At a time", "Dead in a town", "A town falls", "A facility lost"};
    private static final int[] WHEN_MIN = {30, 60, 180, 360, 720, 1440, 2880, 7200};
    private static final int[] WHEN_DEAD = {100, 1000, 10000, 50000};
    private static final String[] DO_NAMES = {"A horde", "An air strike", "Send a formation", "The weather", "A message"};
    private static final int[] DO_SIZE = {500, 2000, 10000, 50000};
    /** What a tap on the map does: where it starts, put something down, the town to keep, a trigger's town, facility or place. */
    static final int P_ORIGIN = 0, P_PLACE = 1, P_TOWN = 2, P_TTOWN = 3, P_TFAC = 4, P_TPLACE = 5, P_NONE = 6;

    int edTab;
    float edX = -1, edY = -1;
    private int edZ = 2, edH, edHS = 1, edGoal, edDays = 2, edSeason = 2, edRole, edStrain;
    /** A named origin (content.ini's [origin], by index), or -1 for the place tapped on the map. */
    private int edOrigin = -1;
    private int plKind, plSquad, plDef, plCount = 1;
    final java.util.List<float[]> edPoints = new java.util.ArrayList<float[]>();
    final java.util.List<String> edPlace = new java.util.ArrayList<String>();
    /** What was put down, in order (0 a point, 1 a placing), for Undo. */
    private final java.util.List<Integer> edUndo = new java.util.ArrayList<Integer>();
    final int[] edRules = Rules.DEFAULTS.clone();
    private int gDays, gTown = -1, gPct = 1, gLoss;
    private boolean gClear;
    final java.util.List<String> edTrig = new java.util.ArrayList<String>();
    private int tWhen, tTime = 2, tDead = 1, tTown = -1, tFac = -1, tDo, tSize = 1, tWeather = 4;
    private String tMsg = "Reports of the dead in the hills";
    private float tX = -1, tY = -1;
    private int pick = -1;
    float mapX, mapY, mapS;

    void openEditor() {
        g0.screen = Game.S_EDITOR;
        edX = edY = -1;
        edTab = 0;
        pick = -1;
    }

    /** What a map tap does on this tab (unless a Pick button has asked for something else). */
    private int pickMode() {
        if (pick >= 0) return pick;
        switch (edTab) {
            case 1: return P_PLACE;
            case 2: return P_NONE;
            case 3: return P_TOWN;
            case 4: return P_TPLACE;
            default: return P_ORIGIN;
        }
    }

    private java.util.List<Region.Settlement> towns() {
        return g0.previewSettlements();
    }

    private String townName(int id) {
        java.util.List<Region.Settlement> l = towns();
        return l != null && id >= 0 && id < l.size() ? l.get(id).name : "(tap the map)";
    }

    private String facilityName(int id) {
        java.util.List<Region.Facility> l = g0.previewFacilities();
        return l != null && id >= 0 && id < l.size() ? l.get(id).name : "(tap the map)";
    }

    private static String minutes(int m) {
        return m < 60 ? m + " min" : m < 1440 ? m / 60 + (m == 60 ? " hour" : " hours") : m / 1440 + (m == 1440 ? " day" : " days");
    }

    /** The rows of a tab: {button id, label, value}; a value of null is a plain button across the row. */
    private String[][] rows() {
        switch (edTab) {
            case 1: {
                String what = plKind == 0 ? WorldView.people(ZOMBIES[plCount]) + " of the dead"
                        : plKind == 1 ? (g0.content.squads.isEmpty() ? "-" : g0.content.squads.get(plSquad % g0.content.squads.size()).name)
                        : Defences.NAMES[plDef % Defences.KINDS];
                return new String[][]{
                        {"ed:plk", "Tap the map to put down", PLACE_KINDS[plKind]},
                        {"ed:plw", plKind == 0 ? "How many" : plKind == 1 ? "Which squad" : "Which defence", what},
                        {"ed:undo", edPoints.size() + (edPoints.size() == 1 ? " point, " : " points, ") + edPlace.size() + " forces/defences", "Undo"},
                        {"ed:clear", "", "Clear all"}};
            }
            case 2: {
                String[][] r = new String[Rules.KEYS][];
                for (int k = 0; k < Rules.KEYS; k++) r[k] = new String[]{"ed:rule:" + k, Rules.KEY_NAMES[k], Rules.CHOICES[k][edRules[k]]};
                return r;
            }
            case 3:
                return new String[][]{
                        {"ed:goal", "Campaign goal", GOAL_NAMES[edGoal]},
                        {"ed:days", "Its days", edGoal == 0 ? "-" : DAYS[edDays] + ""},
                        {"ed:gdays", "Win by surviving", HOLD_DAYS[gDays] == 0 ? "Off" : HOLD_DAYS[gDays] + (HOLD_DAYS[gDays] == 1 ? " day" : " days")},
                        {"ed:gtown", "Keep a town (tap the map)", gTown < 0 ? "Off" : townName(gTown)},
                        {"ed:gpct", "...with at least", KEEP_PCT[gPct] + "% of its people"},
                        {"ed:gclear", "Win: kill all the dead", gClear ? "On" : "Off"},
                        {"ed:gloss", "Lose if more are lost than", LOSSES[gLoss] == 0 ? "Off" : WorldView.people(LOSSES[gLoss])}};
            case 4: {
                String whenV = tWhen == 0 ? "after " + minutes(WHEN_MIN[tTime]) : tWhen == 1 ? "more than " + WorldView.people(WHEN_DEAD[tDead]) : tWhen == 2 ? townName(tTown) : facilityName(tFac);
                String doV = tDo == 0 ? WorldView.people(DO_SIZE[tSize]) + " of the dead" : tDo == 3 ? Rules.CHOICES[Rules.WEATHER][tWeather] : tDo == 4 ? "\"" + tMsg + "\"" : tX < 0 ? "(tap the map)" : "there";
                java.util.List<String[]> r = new java.util.ArrayList<String[]>();
                r.add(new String[]{"ed:tw", "When", WHEN_NAMES[tWhen]});
                r.add(new String[]{tWhen == 0 ? "ed:tt" : tWhen == 1 ? "ed:td" : tWhen == 2 ? "ed:ttown" : "ed:tfac", tWhen == 1 ? "...in " + townName(tTown) : tWhen >= 2 ? "Which (pick, then tap the map)" : "Time", whenV});
                if (tWhen == 1) r.add(new String[]{"ed:ttown", "The town (pick, then tap the map)", townName(tTown)});
                r.add(new String[]{"ed:tdo", "Do", DO_NAMES[tDo]});
                r.add(new String[]{tDo == 0 ? "ed:ts" : tDo == 3 ? "ed:twx" : tDo == 4 ? "ed:tmsg" : "ed:tplace", tDo <= 2 ? "Where (tap the map)" : tDo == 3 ? "Turns to" : "Says", doV});
                if (tDo == 0) r.add(new String[]{"ed:tplace", "Where (tap the map)", tX < 0 ? "(tap the map)" : "there"});
                r.add(new String[]{"ed:tadd", "Triggers: " + edTrig.size(), "Add trigger"});
                return r.toArray(new String[0][]);
            }
            default: {
                java.util.List<Data.Origin> og = Data.origins();
                return new String[][]{
                        {"ed:org", "Where it starts", edOrigin < 0 ? (edX < 0 ? "Somewhere random" : "Where you tapped") : og.get(edOrigin % og.size()).name},
                        {"ed:z", "The dead at the start", WorldView.people(ZOMBIES[edZ])},
                        {"ed:h", "Hordes at the edges", HORDES[edH] == 0 ? "None" : HORDES[edH] + ""},
                        {"ed:hs", "Horde size", WorldView.people(HORDE_SIZE[edHS])},
                        {"ed:strain", "Strain", STRAIN_NAMES[edStrain]},
                        {"ed:season", "Season", SEASON_NAMES[edSeason]},
                        {"ed:role", "Play as", Roles.NAMES[edRole]}};
            }
        }
    }

    void editor(Gfx g) {
        Ui ui = g0.ui;
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(12);
        ui.button("ed:back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("Make your own", m + ui.dp(84), ui.dp(34));
        boolean land = W > H * 1.15f;
        float startH = ui.dp(48);
        float colW = land ? Math.min(ui.dp(380), (W - m * 3) * 0.5f) : W - m * 2;
        // the tabs
        float y = ui.dp(52), tabH = ui.dp(34), tg = ui.dp(4), tw = (colW + tg) / TABS.length;
        for (int k = 0; k < TABS.length; k++) ui.button("ed:tab:" + k, m + k * tw, y, tw - tg, tabH, TABS[k], k == edTab, 12);
        y += tabH + ui.dp(8);
        String[][] rows = rows();
        float rh = ui.dp(land ? 34 : 38), bwid = Math.min(ui.dp(170), colW * 0.46f);
        for (String[] r : rows) {
            g.color(Ui.TEXT);
            if (r[1].length() > 0)
                g.text(Ui.fit(g, r[1], colW - bwid - ui.dp(10), ui.dp(12.5f), false), m, y + rh / 2 + ui.dp(5), ui.dp(12.5f), Gfx.ALIGN_LEFT, false);
            boolean on = r[0].equals("ed:" + pickName());
            ui.button(r[0], m + colW - bwid, y + ui.dp(3), bwid, rh - ui.dp(6), r[2], on, 12);
            y += rh;
        }
        if (edTab == 4 && !edTrig.isEmpty()) {
            // the triggers made so far, each with a way to take it out
            float lh = ui.dp(26);
            for (int k = 0; k < edTrig.size(); k++) {
                g.color(0xFFFFE08A);
                g.text(Ui.fit(g, (k + 1) + ". " + triggerText(edTrig.get(k)), colW - ui.dp(44), ui.dp(Ui.S), false), m, y + lh / 2 + ui.dp(4), ui.dp(Ui.S), Gfx.ALIGN_LEFT, false);
                ui.button("ed:tdel:" + k, m + colW - ui.dp(36), y + ui.dp(2), ui.dp(36), lh - ui.dp(4), "x", false, 12);
                y += lh;
            }
        }
        String hint = hint();
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
            marks(g, ui, ps);
        }
        float sy = Math.max(H - startH - ui.dp(12), land ? y + ui.dp(8) : 0);
        ui.bigButton("ed:save", m, sy, colW, startH, "Save it", Ui.ACCENT);
    }

    private String pickName() {
        switch (pick) {
            case P_TTOWN: return "ttown";
            case P_TFAC: return "tfac";
            case P_TOWN: return "gtown";
            default: return "";
        }
    }

    private String hint() {
        switch (pickMode()) {
            case P_PLACE: return "Tap the map to put down " + PLACE_KINDS[plKind].toLowerCase();
            case P_TOWN: return "Tap the map near the town to keep";
            case P_TTOWN: return "Tap the map near the trigger's town";
            case P_TFAC: return "Tap the map near the facility (power, a hospital, the base...)";
            case P_TPLACE: return "Tap the map where the trigger's action happens";
            case P_NONE: return "The rules apply from the first minute";
            default:
                return edOrigin >= 0 ? "It starts at " + Data.origins().get(edOrigin % Data.origins().size()).name.toLowerCase() + " (tap the map for a place instead)"
                        : edX < 0 ? "Tap the map where it starts (or it starts somewhere random)" : "It starts where the red ring is. Tap to move it.";
        }
    }

    /** A trigger's text as words, for the list. */
    String triggerText(String t) {
        try {
            Script.Trigger tr = Script.parseTrigger(t);
            String when;
            switch (tr.cond) {
                case Script.C_TIME: when = "After " + minutes((int) tr.n); break;
                case Script.C_DEAD: when = "More than " + WorldView.people((long) tr.n) + " dead in " + townName(tr.a); break;
                case Script.C_FALLS: when = townName(tr.a) + " falls"; break;
                default: when = facilityName(tr.a) + " is lost";
            }
            String act;
            switch (tr.act) {
                case Script.A_HORDE: act = "a horde of " + WorldView.people(tr.m); break;
                case Script.A_STRIKE: act = "an air strike"; break;
                case Script.A_SEND: act = "a formation is sent"; break;
                case Script.A_WEATHER: act = "the weather turns " + Rules.CHOICES[Rules.WEATHER][Math.max(0, Math.min(Rules.CHOICES[Rules.WEATHER].length - 1, tr.m))].toLowerCase(); break;
                default: act = "\"" + tr.text + "\"";
            }
            return when + ": " + act;
        } catch (java.io.IOException e) {
            return t;
        }
    }

    /** Everything put on the map: the start, the points, forces and defences, the town to keep, the triggers. */
    private void marks(Gfx g, Ui ui, float ps) {
        java.util.List<Region.Settlement> setts = towns();
        int side = g0.previewSide();
        if (edX >= 0 && edOrigin < 0) {
            g.color(0xFFFF3030);
            g.strokeCircle(mapX + edX * ps, mapY + edY * ps, ui.dp(9), ui.dp(2.5f));
            g.fillCircle(mapX + edX * ps, mapY + edY * ps, ui.dp(3));
        }
        for (float[] p : edPoints) {
            float x = mapX + p[0] * ps, y = mapY + p[1] * ps;
            g.color(0xFF000000);
            g.fillCircle(x, y, ui.dp(6.5f));
            g.color(0xFFFF4A3A);
            g.fillCircle(x, y, ui.dp(5));
            g.color(0xFFFFFFFF);
            g.textHalo(WorldView.people((long) p[2]), x, y - ui.dp(9), ui.dp(10), Gfx.ALIGN_CENTER, true, 0xD0000000, ui.dp(1.2f));
        }
        for (String s : edPlace) {
            try {
                for (String[] p : Script.place(s)) {
                    float x = mapX + Float.parseFloat(p[2]) * ps, y = mapY + Float.parseFloat(p[3]) * ps, d = ui.dp(5);
                    g.color(0xFF000000);
                    g.fillRect(x - d - ui.dp(1.5f), y - d - ui.dp(1.5f), d * 2 + ui.dp(3), d * 2 + ui.dp(3));
                    g.color(p[0].equals("squad") ? 0xFF4A90FF : 0xFFC8B070);
                    g.fillRect(x - d, y - d, d * 2, d * 2);
                }
            } catch (java.io.IOException ignored) {
            }
        }
        if (gTown >= 0 && setts != null && gTown < setts.size() && side > 0) {
            Region.Settlement s = setts.get(gTown);
            float x = mapX + s.cx * ps / side, y = mapY + s.cy * ps / side;
            g.color(0xFF60E080);
            g.strokeCircle(x, y, ui.dp(14), ui.dp(2.5f));
            g.textHalo("Keep " + KEEP_PCT[gPct] + "%", x, y + ui.dp(26), ui.dp(10.5f), Gfx.ALIGN_CENTER, true, 0xD0000000, ui.dp(1.2f));
        }
        for (int k = 0; k < edTrig.size(); k++) {
            try {
                Script.Trigger t = Script.parseTrigger(edTrig.get(k));
                if (t.act <= Script.A_SEND) diamond(g, ui, mapX + t.x * ps, mapY + t.y * ps, (k + 1) + "");
            } catch (java.io.IOException ignored) {
            }
        }
        if (edTab == 4 && tX >= 0) diamond(g, ui, mapX + tX * ps, mapY + tY * ps, "+");
        if (edTab == 4 && side > 0) {
            if ((tWhen == 1 || tWhen == 2) && setts != null && tTown >= 0 && tTown < setts.size()) {
                Region.Settlement s = setts.get(tTown);
                g.color(0xFFFFD040);
                g.strokeCircle(mapX + s.cx * ps / side, mapY + s.cy * ps / side, ui.dp(12), ui.dp(2));
            }
            java.util.List<Region.Facility> fs = g0.previewFacilities();
            if (tWhen == 3 && fs != null && tFac >= 0 && tFac < fs.size()) {
                Region.Facility f = fs.get(tFac);
                g.color(0xFFFFD040);
                g.strokeCircle(mapX + f.x / 16f * ps / side, mapY + f.y / 16f * ps / side, ui.dp(10), ui.dp(2));
            }
        }
    }

    private void diamond(Gfx g, Ui ui, float x, float y, String label) {
        float d = ui.dp(7);
        g.color(0xFF000000);
        g.fillCircle(x, y, d + ui.dp(2));
        g.color(0xFFFFD040);
        g.fillCircle(x, y, d);
        g.color(0xFF000000);
        g.text(label, x, y + ui.dp(3.5f), ui.dp(10), Gfx.ALIGN_CENTER, true);
    }

    /** A tap on the editor's map, at (fx, fy) as fractions of the map's side. */
    private void mapTap(float fx, float fy) {
        int mode = pickMode();
        pick = -1;
        switch (mode) {
            case P_PLACE:
                if (plKind == 0) {
                    edPoints.add(new float[]{fx, fy, ZOMBIES[plCount]});
                    edUndo.add(0);
                } else {
                    String what = plKind == 1 ? "squad:" + g0.content.squads.get(plSquad % g0.content.squads.size()).id : "defence:" + plDef % Defences.KINDS;
                    edPlace.add(what + "@" + Script.frac(fx) + "," + Script.frac(fy));
                    edUndo.add(1);
                }
                break;
            case P_TOWN: gTown = nearestTown(fx, fy); break;
            case P_TTOWN: tTown = nearestTown(fx, fy); break;
            case P_TFAC: tFac = nearestFacility(fx, fy); break;
            case P_TPLACE:
                tX = fx;
                tY = fy;
                break;
            case P_NONE: break;
            default:
                edX = fx;
                edY = fy;
                edOrigin = -1;
        }
    }

    private int nearestTown(float fx, float fy) {
        java.util.List<Region.Settlement> l = towns();
        int side = g0.previewSide();
        if (l == null || side <= 0) return -1;
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (Region.Settlement s : l) {
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN && s.kind != Region.S_VILLAGE) continue;
            float dx = s.cx - fx * side, dy = s.cy - fy * side, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = s.id;
            }
        }
        return best;
    }

    private int nearestFacility(float fx, float fy) {
        java.util.List<Region.Facility> l = g0.previewFacilities();
        int side = g0.previewSide();
        if (l == null || side <= 0) return -1;
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (Region.Facility f : l) {
            if (f.kind == Region.F_HELIPAD || f.kind == Region.F_PARK) continue;
            float dx = f.x / 16f - fx * side, dy = f.y / 16f - fy * side, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f.id;
            }
        }
        return best;
    }

    /** The trigger the Triggers tab describes, as Script's text (null, with a toast, if something is missing). */
    private String builtTrigger() {
        String when;
        switch (tWhen) {
            case 0: when = "time:" + WHEN_MIN[tTime]; break;
            case 1:
                if (tTown < 0) return missing("the town");
                when = "dead:" + tTown + ":" + WHEN_DEAD[tDead];
                break;
            case 2:
                if (tTown < 0) return missing("the town");
                when = "falls:" + tTown;
                break;
            default:
                if (tFac < 0) return missing("the facility");
                when = "lost:" + tFac;
        }
        String act;
        switch (tDo) {
            case 0:
                if (tX < 0) return missing("where");
                act = "horde:" + Script.frac(tX) + "," + Script.frac(tY) + "," + DO_SIZE[tSize];
                break;
            case 1:
                if (tX < 0) return missing("where");
                act = "strike:" + Script.frac(tX) + "," + Script.frac(tY);
                break;
            case 2:
                if (tX < 0) return missing("where");
                act = "send:" + Script.frac(tX) + "," + Script.frac(tY);
                break;
            case 3: act = "weather:" + tWeather; break;
            default: act = "message:" + tMsg.replace(';', ',');
        }
        return when + " -> " + act;
    }

    private String missing(String what) {
        g0.toast("Pick " + what + " first");
        return null;
    }

    /** Builds the scenario the editor describes. */
    Content.Scenario editorScenario(int number) {
        Content.Scenario sc = new Content.Scenario();
        sc.id = "custom" + number;
        sc.name = "My scenario " + number;
        String goal = GOALS[edGoal];
        sc.mode = edRole == Roles.SURVIVOR || edRole == Roles.OFFICER ? "survivor" : goal.length() > 0 && !goal.equals("survive") ? "commander" : "sandbox";
        java.util.List<Data.Origin> og = Data.origins();
        sc.origin = edOrigin >= 0 ? og.get(edOrigin % og.size()).id : edX < 0 ? "random" : "xy:" + Script.frac(edX) + "," + Script.frac(edY);
        sc.goal = goal;
        sc.zombies = ZOMBIES[edZ];
        sc.hordes = HORDES[edH];
        sc.hordeSize = HORDE_SIZE[edHS];
        sc.days = goal.length() > 0 ? DAYS[edDays] : 0;
        sc.season = SEASONS[edSeason];
        sc.role = Roles.IDS[edRole];
        StringBuilder b = new StringBuilder();
        for (float[] p : edPoints) b.append(b.length() > 0 ? "; " : "").append(Script.frac(p[0])).append(',').append(Script.frac(p[1])).append(',').append((int) p[2]);
        sc.points = b.toString();
        b.setLength(0);
        for (String p : edPlace) b.append(b.length() > 0 ? "; " : "").append(p);
        sc.place = b.toString();
        b.setLength(0);
        for (int k = 0; k < Rules.KEYS; k++) if (edRules[k] != Rules.DEFAULTS[k]) b.append(b.length() > 0 ? ", " : "").append(k).append(':').append(edRules[k]);
        sc.rules = b.toString();
        sc.strain = edStrain;
        b.setLength(0);
        if (HOLD_DAYS[gDays] > 0) b.append("days:").append(HOLD_DAYS[gDays]);
        if (gTown >= 0) b.append(b.length() > 0 ? "; " : "").append("keep:").append(gTown).append(':').append(KEEP_PCT[gPct]);
        if (gClear) b.append(b.length() > 0 ? "; " : "").append("cleared");
        if (LOSSES[gLoss] > 0) b.append(b.length() > 0 ? "; " : "").append("losses:").append(LOSSES[gLoss]);
        sc.goals = b.toString();
        sc.triggers.addAll(edTrig);
        int extra = edPoints.size() + edPlace.size();
        sc.desc = WorldView.people(sc.zombies) + " of the dead" + (sc.hordes > 0 ? ", " + sc.hordes + (sc.hordes == 1 ? " horde of " : " hordes of ") + WorldView.people(sc.hordeSize) : "")
                + (edPoints.isEmpty() ? "" : ", " + edPoints.size() + " more outbreak " + (edPoints.size() == 1 ? "point" : "points"))
                + (edStrain > 0 ? ", " + STRAIN_NAMES[edStrain].toLowerCase() : "")
                + ", " + SEASON_NAMES[edSeason].toLowerCase() + (goal.length() > 0 ? ". Goal: " + GOAL_NAMES[edGoal].toLowerCase() + " for " + sc.days + " days" : "")
                + (sc.goals.length() > 0 ? ". " + sc.goals.split(";").length + " goals of its own" : "")
                + (edTrig.isEmpty() ? "" : ". " + edTrig.size() + (edTrig.size() == 1 ? " trigger" : " triggers"))
                + (extra - edPoints.size() > 0 ? ". " + (extra - edPoints.size()) + " forces and defences placed" : "")
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
            String bad = Script.check(g0.content, sc);
            if (bad != null) {
                g0.toast("Can't save it: " + bad);
                return;
            }
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

    private boolean editorButton(String id) {
        if (id.startsWith("ed:tab:")) {
            edTab = Integer.parseInt(id.substring(7));
            pick = -1;
            return true;
        }
        if (id.startsWith("ed:rule:")) {
            int k = Integer.parseInt(id.substring(8));
            edRules[k] = (edRules[k] + 1) % Rules.CHOICES[k].length;
            return true;
        }
        if (id.startsWith("ed:tdel:")) {
            int k = Integer.parseInt(id.substring(8));
            if (k >= 0 && k < edTrig.size()) edTrig.remove(k);
            return true;
        }
        int nsq = Math.max(1, g0.content.squads.size());
        switch (id) {
            case "ed:back": g0.screen = Game.S_NEW; break;
            case "ed:org": edOrigin = edOrigin + 1 >= Data.origins().size() ? -1 : edOrigin + 1; break;
            case "ed:z": edZ = (edZ + 1) % ZOMBIES.length; break;
            case "ed:h": edH = (edH + 1) % HORDES.length; break;
            case "ed:hs": edHS = (edHS + 1) % HORDE_SIZE.length; break;
            case "ed:strain": edStrain = (edStrain + 1) % STRAIN_NAMES.length; break;
            case "ed:goal": edGoal = (edGoal + 1) % GOALS.length; break;
            case "ed:days": edDays = (edDays + 1) % DAYS.length; break;
            case "ed:season": edSeason = (edSeason + 1) % SEASONS.length; break;
            case "ed:role": edRole = (edRole + 1) % Roles.NAMES.length; break;
            case "ed:plk": plKind = (plKind + 1) % PLACE_KINDS.length; break;
            case "ed:plw":
                if (plKind == 0) plCount = (plCount + 1) % ZOMBIES.length;
                else if (plKind == 1) plSquad = (plSquad + 1) % nsq;
                else plDef = (plDef + 1) % Defences.KINDS;
                break;
            case "ed:undo":
                if (!edUndo.isEmpty()) {
                    int what = edUndo.remove(edUndo.size() - 1);
                    if (what == 0 && !edPoints.isEmpty()) edPoints.remove(edPoints.size() - 1);
                    else if (what == 1 && !edPlace.isEmpty()) edPlace.remove(edPlace.size() - 1);
                }
                break;
            case "ed:clear":
                edPoints.clear();
                edPlace.clear();
                edUndo.clear();
                break;
            case "ed:gdays": gDays = (gDays + 1) % HOLD_DAYS.length; break;
            case "ed:gtown":
                if (gTown >= 0 && pick != P_TOWN) gTown = -1;
                else pick = P_TOWN;
                break;
            case "ed:gpct": gPct = (gPct + 1) % KEEP_PCT.length; break;
            case "ed:gclear": gClear = !gClear; break;
            case "ed:gloss": gLoss = (gLoss + 1) % LOSSES.length; break;
            case "ed:tw": tWhen = (tWhen + 1) % WHEN_NAMES.length; break;
            case "ed:tt": tTime = (tTime + 1) % WHEN_MIN.length; break;
            case "ed:td": tDead = (tDead + 1) % WHEN_DEAD.length; break;
            case "ed:ttown": pick = P_TTOWN; break;
            case "ed:tfac": pick = P_TFAC; break;
            case "ed:tdo": tDo = (tDo + 1) % DO_NAMES.length; break;
            case "ed:ts": tSize = (tSize + 1) % DO_SIZE.length; break;
            case "ed:twx": tWeather = tWeather % (Rules.CHOICES[Rules.WEATHER].length - 1) + 1; break;
            case "ed:tplace": pick = P_TPLACE; break;
            case "ed:tmsg":
                g0.ask("What the message says", tMsg, new Platform.TextAnswer() {
                    public void answer(String text) {
                        if (text != null && text.trim().length() > 0) tMsg = text.trim().replace('\n', ' ');
                    }
                });
                break;
            case "ed:tadd": {
                String t = builtTrigger();
                if (t != null) {
                    edTrig.add(t);
                    g0.toast("Trigger " + edTrig.size() + ": " + triggerText(t));
                }
                break;
            }
            case "ed:map":
                mapTap(Math.max(0, Math.min(1, (g0.upX - mapX) / mapS)), Math.max(0, Math.min(1, (g0.upY - mapY) / mapS)));
                break;
            case "ed:save": saveEditor(); break;
            default:
        }
        return true;
    }
    // ------------------------------------------------------------------ buttons

    /** Handles this class's buttons; false if the id isn't one of them. */
    boolean onButton(String id) {
        if (id.startsWith("rb:")) return replayButton(id);
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
        return editorButton(id);
    }
}
