package com.instantaxtion.zcs2;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * The game as the player meets it: the menus, the new game screen, the running game with its tools and HUD, the
 * pause menu, saves, and the after-action report. Platform-free: Android (and the desktop) hand it frames,
 * touches and keys.
 */
public final class Game {
    static final int S_MENU = 0, S_NEW = 1, S_LOADING = 2, S_PLAY = 3, S_PAUSE = 4, S_SETTINGS = 5, S_REPORT = 6,
            S_RADIO = 7, S_HELP = 8, S_NOTES = 9, S_SLOTS = 10;
    static final int[] SPEEDS = {1, 2, 4, 8, 16};

    final Platform pf;
    final Settings settings = new Settings();
    final Ui ui = new Ui();
    final Camera cam = new Camera();
    Content content;
    World w;
    WorldView view;
    int screen = S_MENU, back = S_MENU;
    boolean paused;
    int speedIdx;
    private double acc;
    String error;

    // new game choices
    int ngMode, ngScenario, ngPreset, ngSize = 1;
    long ngSeed;
    private volatile Region preview;
    private GImage previewImg;
    private volatile boolean previewBusy;
    private long previewKey = -1;

    // loading
    private volatile World loaded;
    private volatile String loadingText = "";
    private volatile Throwable loadError;

    // tools
    String tool = "look";
    int zType = -1; // -1 = mixed
    int brush = 10;
    String placeUnit = "patrol", placeVehicle;
    boolean hideUi;
    float scroll;
    boolean saving; // the slots screen is for saving (else loading)

    // touch
    private final float[] px = new float[10], py = new float[10];
    private final boolean[] down = new boolean[10];
    private int pointers;
    private float downX, downY, lastPaintX, lastPaintY;
    private long downT;
    private boolean pinched, toUi, moved;
    private float pinchDist, pinchMidX, pinchMidY;

    // survivor joystick
    private int joyPtr = -1;
    private float joyCX, joyCY;

    // inspector
    int inspEnt = -1, inspVeh = -1, inspFac = -1, inspSettle = -1;
    Squads.Squad inspSquad;
    private float msgT;
    private String msg;
    private int soundsSeen;
    private float fps, frameAcc;
    private int frames;
    private boolean ended;
    private float prevZoom = 0.6f, prevX, prevY;

    public Game(Platform pf) {
        this.pf = pf;
        settings.load(pf);
        try {
            InputStream in = pf.openAsset("content.ini");
            content = Content.load(in);
            in.close();
        } catch (Exception e) {
            error = "Couldn't read the game's content: " + e;
        }
        ngSeed = System.currentTimeMillis() % 1000000;
        pf.setMusic(0, settings.music / 100f);
    }

    float d() {
        float[] scale = {0.85f, 1f, 1.2f};
        return pf.density() * scale[Math.max(0, Math.min(2, settings.uiSize))];
    }

    // ------------------------------------------------------------------ frame

    public void frame(Gfx g, float dt) {
        dt = Math.min(dt, 0.1f);
        frameAcc += dt;
        frames++;
        if (frameAcc >= 1) {
            fps = frames / frameAcc;
            frameAcc = 0;
            frames = 0;
        }
        ui.begin(g, d());
        if (error != null) {
            g.color(0xFF101318);
            g.fillRect(0, 0, g.width(), g.height());
            ui.wrap(error, ui.dp(16), ui.dp(60), g.width() - ui.dp(32), ui.dp(14), Ui.TEXT, 20);
            return;
        }
        switch (screen) {
            case S_MENU: menu(g); break;
            case S_NEW: newGame(g); break;
            case S_LOADING: loading(g, dt); break;
            case S_PLAY: play(g, dt); break;
            case S_PAUSE: play(g, 0); pause(g); break;
            case S_SETTINGS: settingsScreen(g); break;
            case S_REPORT: report(g); break;
            case S_RADIO: radio(g); break;
            case S_HELP: textScreen(g, "How to play", Texts.HELP); break;
            case S_NOTES: textScreen(g, "What's new", Texts.NOTES); break;
            case S_SLOTS: slots(g); break;
            default:
        }
        if (msg != null && msgT > 0) {
            msgT -= dt;
            float tw = g.textWidth(msg, ui.dp(14), true) + ui.dp(24);
            float x = (g.width() - tw) / 2, y = g.height() * 0.42f;
            g.color(0xE0000000);
            g.fillRoundRect(x, y, tw, ui.dp(36), ui.dp(10));
            g.color(0xFFFFFFFF);
            g.text(msg, g.width() / 2f, y + ui.dp(23), ui.dp(14), Gfx.ALIGN_CENTER, true);
        }
    }

    void toast(String s) {
        msg = s;
        msgT = 2.2f;
    }

    // ------------------------------------------------------------------ main menu

    private void menu(Gfx g) {
        int W = g.width(), H = g.height();
        if (w != null && view != null) {
            cam.setScreen(W, H, w.r.px());
            cam.zoom = cam.minZoom;
            cam.cx = cam.cy = w.r.px() / 2f;
            view.draw(g, cam, 0.016f);
            g.color(0xB0080A0E);
            g.fillRect(0, 0, W, H);
        } else {
            g.color(0xFF0E1116);
            g.fillRect(0, 0, W, H);
            // a stain spreading over a grid of streets
            g.color(0xFF161B22);
            for (int i = 0; i < 24; i++) {
                g.fillRect(0, i * H / 24f, W, ui.dp(2));
                g.fillRect(i * W / 12f, 0, ui.dp(2), H);
            }
            g.color(0x30A01818);
            g.fillCircle(W * 0.7f, H * 0.3f, W * 0.5f);
            g.color(0x30C02020);
            g.fillCircle(W * 0.62f, H * 0.36f, W * 0.3f);
        }
        float cx = W / 2f;
        float y = H * 0.18f;
        g.color(0xFFE0453A);
        g.text("ZOMBIE CITY", cx, y, ui.dp(38), Gfx.ALIGN_CENTER, true);
        g.color(0xFFFFFFFF);
        g.text("SANDBOX 2", cx, y + ui.dp(42), ui.dp(30), Gfx.ALIGN_CENTER, true);
        g.color(Ui.DIM);
        g.text("The outbreak goes regional", cx, y + ui.dp(70), ui.dp(14), Gfx.ALIGN_CENTER, false);
        float bw = Math.min(W - ui.dp(48), ui.dp(320)), bh = ui.dp(50), bx = cx - bw / 2;
        y = H * 0.4f;
        if (w != null) {
            ui.bigButton("resume", bx, y, bw, bh, "Back to the game", Ui.ON);
            y += bh + ui.dp(12);
        } else if (pf.exists("autosave.sav")) {
            ui.bigButton("continue", bx, y, bw, bh, "Continue", Ui.ON);
            y += bh + ui.dp(12);
        }
        ui.bigButton("new", bx, y, bw, bh, "New game", Ui.ACCENT);
        y += bh + ui.dp(12);
        ui.button("load", bx, y, bw, bh * 0.85f, "Load game", false);
        y += bh * 0.85f + ui.dp(10);
        float half = (bw - ui.dp(10)) / 2;
        ui.button("settings", bx, y, half, bh * 0.8f, "Settings", false);
        ui.button("help", bx + half + ui.dp(10), y, half, bh * 0.8f, "How to play", false);
        y += bh * 0.8f + ui.dp(10);
        ui.button("notes", bx, y, half, bh * 0.8f, "What's new", false);
        ui.button("quit", bx + half + ui.dp(10), y, half, bh * 0.8f, "Quit", false);
        g.color(0x80FFFFFF);
        g.text("Version " + Texts.VERSION, cx, H - ui.dp(16), ui.dp(11), Gfx.ALIGN_CENTER, false);
    }

    // ------------------------------------------------------------------ new game

    private List<Content.Scenario> scenariosFor(int mode) {
        List<Content.Scenario> out = new java.util.ArrayList<Content.Scenario>();
        String m = mode == 0 ? "sandbox" : mode == 1 ? "commander" : "survivor";
        for (Content.Scenario s : content.scenarios) if (s.mode.equals(m)) out.add(s);
        return out;
    }

    String regionCode() {
        return RegionGen.SIZES[ngSize] + "-" + ngPreset + "-" + ngSeed;
    }

    private void newGame(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(14), y = ui.dp(36);
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("New game", m + ui.dp(84), ui.dp(34));
        y = ui.dp(56);
        float cw = (W - m * 2 - ui.dp(16)) / 3;
        String[] modes = {"Sandbox", "Commander", "Survivor"};
        for (int i = 0; i < 3; i++) ui.button("mode:" + i, m + i * (cw + ui.dp(8)), y, cw, ui.dp(40), modes[i], ngMode == i);
        y += ui.dp(50);
        List<Content.Scenario> list = scenariosFor(ngMode);
        if (ngScenario >= list.size()) ngScenario = 0;
        float ch = ui.dp(70);
        boolean twoCol = W > ui.dp(560);
        float colW = twoCol ? (W - m * 2 - ui.dp(8)) / 2 : W - m * 2;
        for (int i = 0; i < list.size(); i++) {
            Content.Scenario s = list.get(i);
            float cx = m + (twoCol ? (i % 2) * (colW + ui.dp(8)) : 0);
            float cy = y + (twoCol ? i / 2 : i) * (ch + ui.dp(6));
            ui.card("scen:" + i, cx, cy, colW, ch, s.name, s.desc, i == ngScenario);
        }
        y += ((twoCol ? (list.size() + 1) / 2 : list.size())) * (ch + ui.dp(6)) + ui.dp(8);
        // the region
        ui.label("Region", m, y + ui.dp(14), 15, Ui.TEXT, true);
        y += ui.dp(22);
        float bw = (W - m * 2 - ui.dp(16)) / 3;
        ui.button("preset", m, y, bw, ui.dp(40), RegionGen.PRESETS[ngPreset], false, 13);
        ui.button("size", m + bw + ui.dp(8), y, bw, ui.dp(40), RegionGen.SIZE_NAMES[ngSize], false, 13);
        ui.button("seed", m + (bw + ui.dp(8)) * 2, y, bw, ui.dp(40), "New seed", false, 13);
        y += ui.dp(48);
        g.color(Ui.DIM);
        g.text("Region code " + regionCode(), m, y + ui.dp(10), ui.dp(12), Gfx.ALIGN_LEFT, false);
        y += ui.dp(18);
        // the preview
        float startH = ui.dp(54);
        float avail = H - y - startH - ui.dp(24);
        float ps = Math.min(W - m * 2, avail);
        requestPreview();
        if (ps > ui.dp(60)) {
            float pxx = (W - ps) / 2;
            g.color(0xFF1A2028);
            g.fillRect(pxx, y, ps, ps);
            Region pr = preview;
            if (pr != null && previewKey == key()) {
                if (previewImg == null) {
                    previewImg = pf.newImage(pr.W, pr.H);
                    int[] pix = new int[pr.W * pr.H];
                    for (int yy = 0; yy < pr.H; yy++) for (int xx = 0; xx < pr.W; xx++) pix[yy * pr.W + xx] = MapPainter.tileColor(pr, xx, yy, false);
                    previewImg.setPixels(pix, pr.W, pr.H);
                }
                g.image(previewImg, pxx, y, ps, ps);
                float k = ps / pr.W;
                for (Region.Settlement s : pr.settlements) {
                    if (s.kind == Region.S_VILLAGE) continue;
                    g.color(0xD0000000);
                    g.text(s.name, pxx + s.cx * k + 1, y + s.cy * k + 1, ui.dp(s.kind == Region.S_CITY ? 13 : 10), Gfx.ALIGN_CENTER, true);
                    g.color(0xFFFFFFFF);
                    g.text(s.name, pxx + s.cx * k, y + s.cy * k, ui.dp(s.kind == Region.S_CITY ? 13 : 10), Gfx.ALIGN_CENTER, true);
                }
                int people = 0;
                for (Region.Settlement s : pr.settlements) people += s.residents;
                g.color(0xD0000000);
                g.fillRect(pxx, y + ps - ui.dp(22), ps, ui.dp(22));
                g.color(0xFFFFFFFF);
                String cap = pr.name + ": " + WorldView.fmt(people) + " people";
                float cs = ui.dp(12);
                while (g.textWidth(cap, cs, false) > ps - ui.dp(12) && cs > ui.dp(7)) cs *= 0.9f;
                g.text(cap, pxx + ui.dp(6), y + ps - ui.dp(7), cs, Gfx.ALIGN_LEFT, false);
            } else {
                g.color(Ui.DIM);
                g.text("Drawing the region...", W / 2f, y + ps / 2, ui.dp(13), Gfx.ALIGN_CENTER, false);
            }
        }
        ui.bigButton("start", m, H - startH - ui.dp(14), W - m * 2, startH, "Start", Ui.ACCENT);
    }

    private long key() {
        return ngSeed * 31 + ngPreset * 7 + ngSize;
    }

    private void requestPreview() {
        final long k = key();
        if (previewKey == k || previewBusy) return;
        previewBusy = true;
        final int size = RegionGen.SIZES[ngSize], preset = ngPreset;
        final long seed = ngSeed;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Region r = RegionGen.generate(size, preset, seed);
                    preview = r;
                    previewKey = k;
                } finally {
                    previewBusy = false;
                }
            }
        }, "preview");
        t.start();
        if (previewImg != null) {
            previewImg.recycle();
            previewImg = null;
        }
    }

    private void start() {
        final List<Content.Scenario> list = scenariosFor(ngMode);
        final Content.Scenario sc = list.isEmpty() ? null : list.get(Math.min(ngScenario, list.size() - 1));
        final int size = RegionGen.SIZES[ngSize], preset = ngPreset;
        final long seed = ngSeed;
        closeWorld();
        screen = S_LOADING;
        loadingText = "Building " + RegionGen.SIZE_NAMES[ngSize].toLowerCase() + " " + RegionGen.PRESETS[preset].toLowerCase() + " region...";
        loaded = null;
        loadError = null;
        new Thread(new Runnable() {
            public void run() {
                try {
                    World nw = Setup.newGame(content, size, preset, seed, sc, settings.simThreads());
                    nw.maxIndividuals = settings.maxIndividuals;
                    loadingText = "Putting everyone in place...";
                    loaded = nw;
                    startX = Setup.originX;
                    startY = Setup.originY;
                } catch (Throwable t) {
                    loadError = t;
                }
            }
        }, "build").start();
    }

    private volatile float startX, startY;

    private void loadFrom(final String name) {
        closeWorld();
        screen = S_LOADING;
        loadingText = "Loading...";
        loaded = null;
        loadError = null;
        new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream in = pf.openRead(name);
                    World nw = SaveIO.load(content, in, settings.simThreads());
                    in.close();
                    nw.maxIndividuals = settings.maxIndividuals;
                    startX = (nw.camX0 + nw.camX1) / 2;
                    startY = (nw.camY0 + nw.camY1) / 2;
                    if (startX <= 0) {
                        startX = nw.r.city().cx * 16f;
                        startY = nw.r.city().cy * 16f;
                    }
                    loaded = nw;
                } catch (Throwable t) {
                    loadError = t;
                }
            }
        }, "load").start();
    }

    private float spin;

    private void loading(Gfx g, float dt) {
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        spin += dt;
        g.color(Ui.ACCENT);
        for (int i = 0; i < 8; i++) {
            float a = spin * 4 + i * 0.785f;
            g.color(((40 + i * 26) << 24) | 0xE0453A);
            g.fillCircle(W / 2f + (float) Math.cos(a) * ui.dp(22), H / 2f + (float) Math.sin(a) * ui.dp(22), ui.dp(5));
        }
        g.color(Ui.TEXT);
        g.text(loadingText, W / 2f, H / 2f + ui.dp(60), ui.dp(15), Gfx.ALIGN_CENTER, false);
        if (loadError != null) {
            error = "Something went wrong: " + loadError;
            return;
        }
        World nw = loaded;
        if (nw != null) {
            loaded = null;
            w = nw;
            view = new WorldView(w, pf);
            applySettings();
            cam.setScreen(W, H, w.r.px());
            cam.cx = startX;
            cam.cy = startY;
            cam.zoom = w.mode == World.MODE_SURVIVOR ? 1.4f : 0.75f;
            prevX = cam.cx;
            prevY = cam.cy;
            prevZoom = cam.zoom;
            if (w.playerEnt >= 0) cam.follow = w.playerEnt;
            screen = S_PLAY;
            paused = false;
            speedIdx = 0;
            tool = w.mode == World.MODE_SURVIVOR ? "look" : w.mode == World.MODE_COMMANDER ? "orders" : "dead";
            ended = w.ended;
            soundsSeen = w.fx.sHead;
            closeInspector();
            if (w.scenario != null && w.tick < 5) toast(w.scenario.name);
        }
    }

    void applySettings() {
        if (view != null) {
            view.lean3d = settings.lean3d;
            view.blood = settings.blood;
            view.healthBars = settings.healthBars;
            view.names = settings.names;
            view.map.realistic = settings.realistic;
        }
        if (w != null) w.maxIndividuals = settings.maxIndividuals;
        pf.setMusic(0, settings.music / 100f);
    }

    private void closeWorld() {
        if (w != null) w.shutdown();
        if (view != null) view.map.clear();
        w = null;
        view = null;
    }

    // ------------------------------------------------------------------ playing

    private void play(Gfx g, float dt) {
        int W = g.width(), H = g.height();
        cam.setScreen(W, H, w.r.px());
        // follow
        if (cam.follow >= 0) {
            int f = cam.follow;
            if (w.e.loc[f] == Ents.FREE || w.e.loc[f] == Ents.BODY) {
                cam.cx += (w.e.x[f] - cam.cx) * Math.min(1, dt * 5);
                cam.cy += (w.e.y[f] - cam.cy) * Math.min(1, dt * 5);
            } else if (w.e.loc[f] == Ents.ABOARD && w.e.vehicle[f] >= 0) {
                cam.cx = w.veh.x[w.e.vehicle[f]];
                cam.cy = w.veh.y[w.e.vehicle[f]];
            }
        }
        if (cam.followVehicle >= 0 && w.veh.state[cam.followVehicle] != Vehicles.V_NONE) {
            cam.cx += (w.veh.x[cam.followVehicle] - cam.cx) * Math.min(1, dt * 5);
            cam.cy += (w.veh.y[cam.followVehicle] - cam.cy) * Math.min(1, dt * 5);
        }
        cam.update(dt, w.r.px());
        // the camera is an input to the simulation: what's in view is simulated person by person
        w.camX0 = cam.x0();
        w.camY0 = cam.y0();
        w.camX1 = cam.x1();
        w.camY1 = cam.y1();
        w.camDetail = cam.zoom >= 0.3f;
        // step the simulation, within a time budget
        if (!paused && screen == S_PLAY && dt > 0) {
            acc += dt * SPEEDS[speedIdx];
            long t0 = System.nanoTime();
            int steps = 0;
            while (acc >= World.DT) {
                w.step();
                acc -= World.DT;
                steps++;
                if (System.nanoTime() - t0 > 28_000_000L) {
                    acc = 0;
                    break;
                }
            }
        }
        musicT -= dt;
        if (musicT <= 0) {
            musicT = 5;
            int track = w.gov.level >= 2 || w.sp.zCount > 200 ? 1 : 0;
            pf.setMusic(track, settings.music / 100f);
        }
        if (w.ended && !ended) {
            ended = true;
            paused = true;
        }
        sounds();
        view.selected = inspSquad;
        view.inspectEnt = inspEnt;
        view.inspectVehicle = inspVeh;
        view.draw(g, cam, dt);
        if (!hideUi) hud(g);
        else {
            ui.button("showui", W - ui.dp(46), ui.dp(10), ui.dp(36), ui.dp(30), "UI", false, 12);
        }
        if (w.ended && screen == S_PLAY && !hideUi) endPanel(g);
    }

    private float musicT;

    private void sounds() {
        Effects fx = w.fx;
        int n = (fx.sHead - soundsSeen + Effects.SOUNDS) % Effects.SOUNDS;
        if (n > Effects.SOUNDS / 2) n = Effects.SOUNDS / 2;
        soundsSeen = fx.sHead;
        if (paused) return;
        float vol = settings.sound / 100f;
        if (vol <= 0) return;
        int played = 0;
        for (int k = 0; k < n && played < 6; k++) {
            int i = (fx.sHead - 1 - k + Effects.SOUNDS) % Effects.SOUNDS;
            String name = fx.sName[i];
            if (name == null) continue;
            float x = fx.sx[i], y = fx.sy[i];
            float v, pan = 0;
            if (x < 0) v = 0.7f; // news chime: everywhere
            else {
                float dx = (x - cam.cx) * cam.zoom, dy = (y - cam.cy) * cam.zoom;
                float dist = (float) Math.sqrt(dx * dx + dy * dy) / Math.max(cam.sw, cam.sh);
                v = Math.max(0, 1 - dist * 1.2f) * Math.min(1, cam.zoom * 2.5f + 0.15f);
                if (name.contains("boom")) v = Math.max(v, 0.35f * Math.max(0, 1 - dist * 0.4f));
                pan = Math.max(-1, Math.min(1, dx / (cam.sw * 0.6f)));
            }
            if (v < 0.04f) continue;
            pf.playSound(name, v * vol, pan);
            played++;
        }
    }

    private String fmt(int n) {
        return WorldView.fmt(n);
    }

    private void hud(Gfx g) {
        int W = g.width(), H = g.height();
        float m = ui.dp(8), bh = ui.dp(38);
        // top bar
        g.color(0xC0101318);
        g.fillRect(0, 0, W, ui.dp(78));
        ui.block(0, 0, W, ui.dp(78));
        ui.button("pause", m, m, bh, bh, "≡", false, 18);
        g.color(Ui.TEXT);
        g.text(w.clock(), m + bh + ui.dp(10), m + ui.dp(16), ui.dp(15), Gfx.ALIGN_LEFT, true);
        int lvl = w.gov.level;
        int lc = lvl <= 0 ? Ui.GOOD : lvl == 1 ? Ui.WARN : lvl == 2 ? 0xFFE07030 : 0xFFE0453A;
        g.color(lc);
        g.text(Gov.LEVELS[lvl], m + bh + ui.dp(10), m + ui.dp(33), ui.dp(11), Gfx.ALIGN_LEFT, true);
        // speed
        float sw = ui.dp(44);
        float sx = W - m - sw * 2 - ui.dp(6);
        ui.button("playpause", sx, m, sw, bh, paused ? "▶" : "II", paused, 15);
        ui.button("speed", sx + sw + ui.dp(6), m, sw, bh, SPEEDS[speedIdx] + "x", false, 14);
        // the numbers
        int people = w.livingCivilians();
        String line = "People " + fmt(people) + "   Dead " + fmt(w.totalZombies()) + "   Police " + w.sq.count(Ents.COP)
                + "   Army " + w.sq.count(Ents.SOLDIER);
        g.color(Ui.TEXT);
        float ts = ui.dp(12.5f);
        while (g.textWidth(line, ts, false) > W - m * 2 && ts > ui.dp(8)) ts *= 0.92f;
        g.text(line, m, ui.dp(68), ts, Gfx.ALIGN_LEFT, false);
        // the latest headline
        News.Item hl = w.news.headline;
        float y = ui.dp(80);
        if (hl != null && w.time - hl.time < 14) {
            g.color(0xD0301010);
            float th = ui.dp(Ui.lines(g, hl.text, W - m * 4, ui.dp(12)) > 1 ? 38 : 24);
            g.fillRect(0, y, W, th);
            ui.wrap(hl.text, m * 2, y + ui.dp(16), W - m * 4, ui.dp(12), 0xFFFFE8E0, 2);
            ui.block(0, y, W, th);
            y += th;
        }
        // the minimap
        if (settings.minimap && cam.zoom > 0.08f) minimap(g, W - ui.dp(112) - m, y + m, ui.dp(112));
        // radio: the last few messages
        bottomBars(g);
        float ry = H - toolbarH - paletteH() - ui.dp(8);
        if (settings.radio) {
            int shown = 0;
            for (int i = w.news.log.size() - 1; i >= 0 && shown < 3; i--) {
                News.Item it = w.news.log.get(i);
                if (it.kind == News.HEADLINE) continue;
                if (w.time - it.time > 25) break;
                float a = (float) Math.max(0, Math.min(1, (25 - (w.time - it.time)) / 5));
                String t = it.text;
                float tw = Math.min(W - m * 2, g.textWidth(t, ui.dp(11.5f), false) + ui.dp(12));
                ry -= ui.dp(22);
                g.color(((int) (a * 170)) << 24);
                g.fillRoundRect(m, ry, tw, ui.dp(20), ui.dp(5));
                int col = it.kind == News.LOSS ? 0xFFFF9A8A : it.kind == News.COMMAND ? 0xFFFFE08A : it.kind == News.CALL ? 0xFFB0D0FF : 0xFFE0E0E0;
                g.color(((int) (a * 255) << 24) | (col & 0xFFFFFF));
                g.text(t, m + ui.dp(6), ry + ui.dp(14), ui.dp(11.5f), Gfx.ALIGN_LEFT, false);
                shown++;
            }
        }
        if (w.mode == World.MODE_SURVIVOR) joystick(g);
        // fps
        g.color(0x90FFFFFF);
        g.text((int) fps + " fps  " + fmt(w.e.live) + " simulated", W - m, ui.dp(94) + (hl != null && w.time - hl.time < 14 ? ui.dp(24) : 0) + (settings.minimap && cam.zoom > 0.08f ? ui.dp(118) : 0), ui.dp(10), Gfx.ALIGN_RIGHT, false);
    }

    private void minimap(Gfx g, float x, float y, float s) {
        g.color(0xC0000000);
        g.fillRect(x - 2, y - 2, s + 4, s + 4);
        g.image(view.map.regionImage, x, y, s, s);
        float k = s / w.r.px();
        float cs = Region.SECT * Tile.SIZE;
        for (int sec = 0; sec < w.sec.n; sec++) {
            int zz = w.sec.zIn[sec] + w.sp.secZ[sec];
            if (zz <= 0) continue;
            g.color(zz > 100 ? 0xD0E02020 : 0x90E02020);
            g.fillRect(x + (sec % w.sec.sw) * cs * k, y + (sec / w.sec.sw) * cs * k, cs * k + 0.5f, cs * k + 0.5f);
        }
        for (Strategic.Horde h : w.strat.hordes) {
            g.color(0xFFFF4040);
            g.fillCircle(x + h.x * k, y + h.y * k, 3);
        }
        g.color(0xFFFFFFFF);
        g.strokeRect(x + cam.x0() * k, y + cam.y0() * k, Math.max(3, (cam.x1() - cam.x0()) * k), Math.max(3, (cam.y1() - cam.y0()) * k), 1.5f);
        ui.area("minimap", x, y, s, s);
        mmX = x;
        mmY = y;
        mmS = s;
    }

    private float mmX, mmY, mmS, upX, upY;

    private float toolbarH;

    private String[] tools() {
        if (w.mode == World.MODE_COMMANDER) return new String[]{"look", "orders", "strike", "town", "map"};
        if (w.mode == World.MODE_SURVIVOR) return new String[]{"look", "fire", "map"};
        return new String[]{"look", "dead", "police", "army", "strike", "orders", "town", "erase", "map"};
    }

    private static String toolName(String t) {
        switch (t) {
            case "look": return "Look";
            case "dead": return "Dead";
            case "police": return "Police";
            case "army": return "Army";
            case "strike": return "Strike";
            case "orders": return "Orders";
            case "town": return "Towns";
            case "erase": return "Erase";
            case "map": return "Map";
            case "fire": return "Shoot";
            default: return t;
        }
    }

    private float paletteH() {
        if (inspectorOpen()) return inspectorH;
        String[][] p = palette();
        return p == null ? 0 : ui.dp(46) * p.length + ui.dp(6);
    }

    /** Rows of (id, label) pairs for the current tool's options. */
    private String[][] palette() {
        switch (tool) {
            case "dead": {
                String[] types = {"z:-1", "Mixed", "z:walker", "Walker", "z:runner", "Runner", "z:brute", "Brute", "z:armoured", "Armour", "z:giant", "Giant"};
                String[] sizes = {"b:1", "1", "b:10", "10", "b:50", "50", "b:250", "250", "b:2000", "Horde 2k"};
                return new String[][]{types, sizes};
            }
            case "police":
                return new String[][]{{"u:patrol", "Patrol", "u:swat", "SWAT", "u:riot", "Riot", "u:marksman", "Marksmen", "u:trooper", "Troopers"},
                        {"v:patrol_car", "Car", "v:swat_van", "SWAT van", "v:police_heli", "Helicopter"}};
            case "army":
                return new String[][]{{"u:rifleman", "Riflemen", "u:gunner", "Gunners", "u:grenadier", "Grenadiers", "u:sniper", "Snipers", "u:guard", "Guard"},
                        {"v:humvee", "Humvee", "v:apc", "APC", "v:tank", "Tank", "v:gunship", "Gunship"}};
            case "strike":
                return new String[][]{{"s:air", "Air strike" + (w.mil.strikesLeft >= 0 ? " (" + w.mil.strikesLeft + ")" : ""), "s:bridge", "Blow a bridge"}};
            case "town":
                return new String[][]{{"curfew", w.gov.curfew ? "Lift curfew" : "Curfew", "t:hint", "Tap a town to evacuate it"}};
            case "orders":
                if (inspSquad != null && inspSquad.alive)
                    return new String[][]{{"o:hint", inspSquad.name + ": tap where to go", "o:release", "Release", "o:home", "Home"}};
                return new String[][]{{"o:hint", "Tap a squad, then tap where it should go"}};
            case "erase":
                return new String[][]{{"b:10", "Small", "b:50", "Medium", "b:250", "Large"}};
            default:
                return null;
        }
    }

    private String strikeMode = "air";

    private boolean paletteOn(String id) {
        if (id.startsWith("z:")) return id.substring(2).equals(zType < 0 ? "-1" : content.ztypes.get(zType).id);
        if (id.startsWith("b:")) return Integer.parseInt(id.substring(2)) == brush;
        if (id.startsWith("u:")) return placeVehicle == null && id.substring(2).equals(placeUnit);
        if (id.startsWith("v:")) return id.substring(2).equals(placeVehicle);
        if (id.startsWith("s:")) return id.substring(2).equals(strikeMode);
        return false;
    }

    private void bottomBars(Gfx g) {
        int W = g.width(), H = g.height();
        String[] ts = tools();
        toolbarH = ui.dp(54);
        float y = H - toolbarH;
        g.color(0xE0101318);
        g.fillRect(0, y, W, toolbarH);
        ui.block(0, y, W, toolbarH);
        float bw = (W - ui.dp(8)) / ts.length;
        for (int i = 0; i < ts.length; i++)
            ui.button("tool:" + ts[i], ui.dp(4) + i * bw + ui.dp(2), y + ui.dp(6), bw - ui.dp(4), toolbarH - ui.dp(12), toolName(ts[i]),
                    ts[i].equals(tool) || (ts[i].equals("fire") && w.strat.playerFire), 12);
        if (inspectorOpen()) {
            inspector(g, y);
            return;
        }
        String[][] rows = palette();
        if (rows == null) return;
        float rh = ui.dp(46);
        float py = y - rh * rows.length - ui.dp(6);
        g.color(0xD0101318);
        g.fillRect(0, py, W, rh * rows.length + ui.dp(6));
        ui.block(0, py, W, rh * rows.length + ui.dp(6));
        for (int r = 0; r < rows.length; r++) {
            String[] row = rows[r];
            int n = row.length / 2;
            float cw = (W - ui.dp(8)) / n;
            for (int i = 0; i < n; i++) {
                String id = row[i * 2], label = row[i * 2 + 1];
                if (id.endsWith(":hint")) {
                    g.color(Ui.DIM);
                    g.text(label, ui.dp(8) + i * cw + cw / 2, py + ui.dp(6) + r * rh + rh / 2 + ui.dp(3), ui.dp(12), Gfx.ALIGN_CENTER, false);
                    continue;
                }
                ui.button(id, ui.dp(4) + i * cw + ui.dp(2), py + ui.dp(6) + r * rh, cw - ui.dp(4), rh - ui.dp(6), label, paletteOn(id), 12);
            }
        }
    }

    // ------------------------------------------------------------------ the inspector

    private float inspectorH;

    boolean inspectorOpen() {
        return inspEnt >= 0 || inspVeh >= 0 || inspFac >= 0 || inspSettle >= 0 || (inspSquad != null && !tool.equals("orders"));
    }

    void closeInspector() {
        inspEnt = inspVeh = inspFac = inspSettle = -1;
        if (!tool.equals("orders")) inspSquad = null;
    }

    private void inspector(Gfx g, float bottom) {
        int W = g.width();
        float m = ui.dp(10);
        String title = "", sub = "";
        StringBuilder body = new StringBuilder();
        String[] actions;
        Ents e = w.e;
        if (inspEnt >= 0) {
            int i = inspEnt;
            if (e.loc[i] == Ents.NONE) {
                closeInspector();
                return;
            }
            title = w.nameOf(i);
            if (e.kind[i] == Ents.ZOMBIE) {
                Content.ZType t = w.ztypeOf(i);
                sub = e.loc[i] == Ents.BODY ? "Destroyed" : "One of the dead";
                body.append("Health ").append((int) Math.max(0, e.hp[i])).append(" of ").append((int) e.maxHp[i]);
                if ((e.flags[i] & Ents.F_HELMET) != 0) body.append(". Wearing a helmet");
                if (t.smash) body.append(". Smashes through things");
            } else {
                Content.Unit u = w.unitOf(i);
                sub = u.name + (u.agency.length() > 0 ? ", " + u.agency : "");
                if (e.loc[i] == Ents.BODY) sub = "Killed. " + sub;
                body.append("Health ").append((int) Math.max(0, e.hp[i])).append(" of ").append((int) e.maxHp[i]);
                if (u.weapon != null) body.append(". ").append(u.weapon.name).append(", ").append(e.ammo[i]).append(" rounds");
                if (e.kills[i] > 0) body.append(". ").append(e.kills[i]).append(" kills");
                if ((e.flags[i] & Ents.F_INFECTED) != 0) body.append(". Bitten and infected");
                if (e.squad[i] >= 0) {
                    Squads.Squad s = w.sq.get(e.squad[i]);
                    body.append(". ").append(s.name).append(" (").append(s.label).append("): ").append(Squads.ORDER_NAMES[s.order]);
                }
                if (e.kind[i] == Ents.CIV && i != w.playerEnt) {
                    String[] st = {"Out and about", "Fleeing", "Heading for shelter", "Evacuating", "Going home", ""};
                    body.append(". ").append(st[Math.min(5, e.state[i])]);
                }
            }
            boolean squadable = e.squad[i] >= 0 && w.mode != World.MODE_SURVIVOR;
            actions = squadable ? new String[]{"i:follow", cam.follow == i ? "Stop following" : "Follow", "i:squad", "Give orders", "i:close", "Close"}
                    : new String[]{"i:follow", cam.follow == i ? "Stop following" : "Follow", "i:close", "Close"};
        } else if (inspVeh >= 0) {
            int v = inspVeh;
            Vehicles vs = w.veh;
            if (vs.state[v] == Vehicles.V_NONE) {
                closeInspector();
                return;
            }
            Content.VType t = vs.type(v);
            title = t.name + " " + Names.callsign(t.faction.equals("police") ? Ents.COP : Ents.SOLDIER, vs.uid[v]);
            sub = Vehicles.STATE_NAMES[vs.state[v]] + (vs.siren[v] ? ", lights and siren" : "");
            body.append("Condition ").append((int) Math.max(0, vs.hp[v] * 100 / t.hp)).append("%. Fuel ").append((int) (vs.fuel[v] * 100)).append("%");
            if (t.seats > 0) body.append(". Aboard ").append(vs.crewN[v]).append(" of ").append(t.seats);
            if (vs.kills[v] > 0) body.append(". ").append(vs.kills[v]).append(" kills");
            if (vs.squad[v] >= 0) {
                Squads.Squad s = w.sq.get(vs.squad[v]);
                body.append(". ").append(s.name).append(": ").append(Squads.ORDER_NAMES[s.order]);
            }
            actions = new String[]{"i:followv", cam.followVehicle == v ? "Stop following" : "Follow", "i:close", "Close"};
        } else if (inspFac >= 0) {
            Region.Facility f = w.r.facilities.get(inspFac);
            title = f.name;
            boolean on = w.strat.online[f.id];
            sub = on ? "Working" : "Overrun";
            if (f.kind == Region.F_POWER) body.append(on ? "Powering " : "Was powering ").append(w.strat.gridName(f.grid));
            if (f.kind == Region.F_POLICE || f.kind == Region.F_BASE || f.kind == Region.F_ARMORY)
                body.append("Ammunition in store: ").append(w.strat.ammo[f.id]).append(" rounds");
            if (f.kind == Region.F_HOSPITAL) body.append(on ? "Taking in the bitten" : "The sick inside have turned");
            if (f.kind == Region.F_FUEL) body.append(on ? "Refuelling the town's vehicles" : "Vehicles here can't refuel");
            actions = new String[]{"i:close", "Close"};
        } else if (inspSettle >= 0) {
            Region.Settlement s = w.r.settlements.get(inspSettle);
            title = s.name;
            String[] kinds = {"City", "Town", "Village", "Army base", "Prison"};
            sub = kinds[s.kind] + (w.gov.evacuating(s.id) ? ", evacuating" : "") + (w.disp.perimeterMode(s.id) ? ", under a police cordon" : "");
            body.append("Home to ").append(fmt(s.residents)).append(". Still here: ").append(fmt(w.stats.popBySettlement[s.id]))
                    .append(". The dead here: ").append(fmt(w.stats.zBySettlement[s.id]));
            if (s.grid >= 0) body.append(w.strat.gridOn[s.grid] ? ". Power on" : ". No power");
            boolean canEvac = (s.kind == Region.S_CITY || s.kind == Region.S_TOWN) && w.mode != World.MODE_SURVIVOR;
            actions = canEvac ? new String[]{"i:evac", w.gov.evacuating(s.id) ? "Stop evacuating" : "Evacuate", "i:zoom", "Go there", "i:close", "Close"}
                    : new String[]{"i:zoom", "Go there", "i:close", "Close"};
        } else {
            Squads.Squad s = inspSquad;
            if (s == null || !s.alive) {
                closeInspector();
                inspSquad = null;
                return;
            }
            title = s.name + " (" + s.label + ")";
            sub = Squads.ORDER_NAMES[s.order] + ", " + s.n + (s.faction == Ents.COP ? " officers" : " soldiers");
            body.append(s.kills).append(" kills, ").append(s.losses).append(" lost");
            actions = new String[]{"i:squad", "Give orders", "i:close", "Close"};
        }
        float textW = W - m * 4;
        int bl = Ui.lines(ui.g, body.toString(), textW, ui.dp(12));
        inspectorH = ui.dp(64) + bl * ui.dp(15.6f) + ui.dp(48);
        float y = bottom - inspectorH;
        ui.panel(m, y, W - m * 2, inspectorH - ui.dp(6));
        g.color(Ui.TEXT);
        g.text(title, m * 2, y + ui.dp(24), ui.dp(16), Gfx.ALIGN_LEFT, true);
        g.color(Ui.DIM);
        g.text(sub, m * 2, y + ui.dp(42), ui.dp(12), Gfx.ALIGN_LEFT, false);
        ui.wrap(body.toString(), m * 2, y + ui.dp(62), textW, ui.dp(12), Ui.TEXT, 6);
        int n = actions.length / 2;
        float aw = (W - m * 4 - ui.dp(8) * (n - 1)) / n;
        for (int k = 0; k < n; k++)
            ui.button(actions[k * 2], m * 2 + k * (aw + ui.dp(8)), y + inspectorH - ui.dp(50), aw, ui.dp(38), actions[k * 2 + 1], false, 13);
    }

    // ------------------------------------------------------------------ the end

    private void endPanel(Gfx g) {
        int W = g.width(), H = g.height();
        float pw = Math.min(W - ui.dp(32), ui.dp(360)), ph = ui.dp(200);
        float x = (W - pw) / 2, y = H * 0.3f;
        ui.panel(x, y, pw, ph);
        g.color(w.won ? Ui.GOOD : Ui.ACCENT);
        g.text(w.endTitle == null ? "It's over" : w.endTitle, x + pw / 2, y + ui.dp(36), ui.dp(20), Gfx.ALIGN_CENTER, true);
        ui.wrap(w.endText == null ? "" : w.endText, x + ui.dp(16), y + ui.dp(64), pw - ui.dp(32), ui.dp(13), Ui.TEXT, 3);
        float bw = (pw - ui.dp(48)) / 3;
        ui.button("report", x + ui.dp(16), y + ph - ui.dp(56), bw, ui.dp(40), "Report", false, 13);
        ui.button("watch", x + ui.dp(24) + bw, y + ph - ui.dp(56), bw, ui.dp(40), "Keep watching", false, 12);
        ui.button("tomenu", x + ui.dp(32) + bw * 2, y + ph - ui.dp(56), bw, ui.dp(40), "Menu", false, 13);
    }

    // ------------------------------------------------------------------ survivor stick

    private void joystick(Gfx g) {
        float r = ui.dp(54);
        float cx = ui.dp(24) + r, cy = g.height() - toolbarH - ui.dp(24) - r;
        joyCX = cx;
        joyCY = cy;
        g.color(0x50000000);
        g.fillCircle(cx, cy, r);
        g.color(0x80FFFFFF);
        g.strokeCircle(cx, cy, r, 2);
        float kx = cx + w.strat.playerJoyX * r, ky = cy + w.strat.playerJoyY * r;
        g.color(0xB0FFFFFF);
        g.fillCircle(kx, ky, r * 0.4f);
        ui.block(cx - r, cy - r, r * 2, r * 2);
    }

    // ------------------------------------------------------------------ pause and other screens

    private void pause(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(0xB0000000);
        g.fillRect(0, 0, W, H);
        float pw = Math.min(W - ui.dp(40), ui.dp(340));
        float x = (W - pw) / 2, bh = ui.dp(46), gap = ui.dp(9);
        String[][] items = {{"resume", "Resume"}, {"save", "Save"}, {"loadp", "Load"}, {"report", "Report"}, {"radio", "Radio log"},
                {"settings", "Settings"}, {"shot", "Screenshot"}, {"hideui", "Hide buttons"}, {"tomenu", "Quit to the menu"}};
        float ph = items.length * (bh + gap) + ui.dp(70);
        float y = (H - ph) / 2;
        ui.panel(x - ui.dp(12), y, pw + ui.dp(24), ph);
        g.color(Ui.TEXT);
        g.text("Paused", W / 2f, y + ui.dp(34), ui.dp(20), Gfx.ALIGN_CENTER, true);
        g.color(Ui.DIM);
        g.text(w.r.name + "  " + (w.scenario != null ? w.scenario.name : ""), W / 2f, y + ui.dp(54), ui.dp(11), Gfx.ALIGN_CENTER, false);
        y += ui.dp(66);
        for (String[] it : items) {
            ui.button(it[0], x, y, pw, bh, it[1], false);
            y += bh + gap;
        }
    }

    private void settingsScreen(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(14);
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("Settings", m + ui.dp(84), ui.dp(34));
        Settings s = settings;
        String[][] rows = {
                {"set:sound", "Sound", s.sound + "%"},
                {"set:music", "Music", s.music + "%"},
                {"set:lean3d", "Buildings", s.lean3d ? "3D" : "Flat"},
                {"set:realistic", "Ground", s.realistic ? "Textured" : "Plain"},
                {"set:blood", "Blood", s.blood ? "On" : "Off"},
                {"set:health", "Health bars", s.healthBars ? "On" : "Off"},
                {"set:names", "Place names", s.names ? "On" : "Off"},
                {"set:radio", "Radio messages", s.radio ? "On" : "Off"},
                {"set:minimap", "Minimap", s.minimap ? "On" : "Off"},
                {"set:max", "People simulated in detail", WorldView.fmt(s.maxIndividuals)},
                {"set:threads", "Simulation threads", s.threads == 0 ? "Auto (" + s.simThreads() + ")" : "" + s.threads},
                {"set:ui", "Button size", new String[]{"Small", "Normal", "Large"}[s.uiSize]},
                {"set:vibrate", "Vibration", s.vibrate ? "On" : "Off"}};
        float y = ui.dp(60), rh = ui.dp(48);
        for (String[] r : rows) {
            g.color(Ui.TEXT);
            g.text(r[1], m, y + rh / 2 + ui.dp(5), ui.dp(14), Gfx.ALIGN_LEFT, false);
            ui.button(r[0], W - m - ui.dp(130), y + ui.dp(5), ui.dp(130), rh - ui.dp(10), r[2], false, 13);
            y += rh;
        }
        g.color(Ui.DIM);
        ui.wrap("More people in detail look better and cost more on a slow phone. The rest are still simulated, as counts.", m, y + ui.dp(20), W - m * 2, ui.dp(12), Ui.DIM, 3);
    }

    private void textScreen(Gfx g, String title, String[] lines) {
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(16);
        g.save();
        g.clip(0, ui.dp(54), W, H - ui.dp(54));
        float y = ui.dp(76) - scroll;
        for (String l : lines) {
            boolean head = l.equals(l.toUpperCase()) || l.matches("^\\d+\\.\\d+.*");
            if (head) {
                y += ui.dp(10);
                g.color(Ui.ACCENT);
                g.text(l, m, y, ui.dp(14), Gfx.ALIGN_LEFT, true);
                y += ui.dp(10);
            } else y += ui.wrap(l, m, y + ui.dp(8), W - m * 2, ui.dp(13), Ui.TEXT, 30) + ui.dp(6);
            y += ui.dp(8);
        }
        g.restore();
        maxScroll = Math.max(0, y + scroll - H + ui.dp(30));
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, ui.dp(54));
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title(title, m + ui.dp(84), ui.dp(34));
    }

    private float maxScroll;

    private void radio(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(12);
        g.save();
        g.clip(0, ui.dp(54), W, H - ui.dp(54));
        float y = ui.dp(60) - scroll, rh = ui.dp(36);
        List<News.Item> log = w.news.log;
        for (int i = log.size() - 1; i >= 0; i--) {
            News.Item it = log.get(i);
            if (y > -rh && y < H) {
                int col = it.kind == News.HEADLINE ? 0xFFFFD0A0 : it.kind == News.LOSS ? 0xFFFF9A8A : it.kind == News.COMMAND ? 0xFFFFE08A : it.kind == News.CALL ? 0xFFB0D0FF : Ui.TEXT;
                int mins = (int) (it.time / 60);
                String when = "Day " + (1 + (int) ((World.START_HOUR + mins / 60.0) / 24)) + " " + String.format("%02d:%02d", (int) ((World.START_HOUR + it.time / 60.0) % 24), (int) (it.time % 60));
                g.color(Ui.DIM);
                g.text(when, m, y + ui.dp(13), ui.dp(10), Gfx.ALIGN_LEFT, false);
                g.color(col);
                String t = it.text;
                float ts = ui.dp(12.5f);
                while (g.textWidth(t, ts, false) > W - m * 2 && t.length() > 10) t = t.substring(0, t.length() - 4) + "...";
                g.text(t, m, y + ui.dp(29), ts, Gfx.ALIGN_LEFT, it.kind == News.HEADLINE);
                if (it.x >= 0) ui.button("goto:" + i, 0, y, W, rh, "", false);
                if (it.x >= 0) {
                    // the button paints a background: put the text back on top
                    g.color(Ui.DIM);
                    g.text(when + "   (tap to go there)", m, y + ui.dp(13), ui.dp(10), Gfx.ALIGN_LEFT, false);
                    g.color(col);
                    g.text(t, m, y + ui.dp(29), ts, Gfx.ALIGN_LEFT, it.kind == News.HEADLINE);
                }
            }
            y += rh + ui.dp(2);
        }
        g.restore();
        maxScroll = Math.max(0, y + scroll - H + ui.dp(20));
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, ui.dp(54));
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("Radio log", m + ui.dp(84), ui.dp(34));
    }

    private void slots(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(14);
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title(saving ? "Save game" : "Load game", m + ui.dp(84), ui.dp(34));
        float y = ui.dp(64);
        String[] names = saving ? new String[]{"slot1", "slot2", "slot3"} : new String[]{"autosave", "slot1", "slot2", "slot3"};
        for (String n : names) {
            boolean has = pf.exists(n + ".sav");
            String info = has ? pf.pref("info." + n, "A saved game") : "Empty";
            String title = n.equals("autosave") ? "Autosave" : "Slot " + n.substring(4);
            ui.card("slot:" + n, m, y, W - m * 2, ui.dp(66), title, info, false);
            y += ui.dp(74);
        }
    }

    // ------------------------------------------------------------------ the report

    private void report(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(14);
        g.save();
        g.clip(0, ui.dp(54), W, H - ui.dp(54));
        float y = ui.dp(70) - scroll;
        Stats s = w.stats;
        g.color(Ui.DIM);
        g.text(w.r.name + ", " + w.clock() + (w.scenario != null ? ", " + w.scenario.name : ""), m, y, ui.dp(12), Gfx.ALIGN_LEFT, false);
        y += ui.dp(14);
        if (w.ended) {
            g.color(w.won ? Ui.GOOD : Ui.ACCENT);
            g.text(w.endTitle, m, y + ui.dp(14), ui.dp(17), Gfx.ALIGN_LEFT, true);
            y += ui.dp(24);
        }
        // the timeline
        float gh = ui.dp(150), gw = W - m * 2;
        y += ui.dp(10);
        g.color(0xFF161C24);
        g.fillRect(m, y, gw, gh);
        int n = s.tN;
        if (n >= 2) {
            int maxZ = 1, maxA = 1, maxU = 1;
            for (int i = 0; i < n; i++) {
                maxZ = Math.max(maxZ, s.tZombies[i]);
                maxA = Math.max(maxA, s.tAlive[i]);
                maxU = Math.max(maxU, Math.max(s.tPolice[i], s.tArmy[i]));
            }
            series(g, s.tAlive, n, maxA, m, y, gw, gh, 0xFFE8E0C8);
            series(g, s.tPolice, n, maxU, m, y, gw, gh, 0xFF4F8CFF);
            series(g, s.tArmy, n, maxU, m, y, gw, gh, 0xFF9CC060);
            series(g, s.tZombies, n, maxZ, m, y, gw, gh, 0xFFE04040);
            int tp = s.turningPoint();
            if (tp >= 0) {
                float tx = m + gw * tp / (n - 1f);
                g.color(0xA0FFFFFF);
                g.line(tx, y, tx, y + gh, 1);
            }
        } else {
            g.color(Ui.DIM);
            g.text("The timeline fills in as the game goes on", m + gw / 2, y + gh / 2, ui.dp(12), Gfx.ALIGN_CENTER, false);
        }
        y += gh + ui.dp(16);
        g.color(0xFFE8E0C8); g.text("people", m, y, ui.dp(11), Gfx.ALIGN_LEFT, true);
        g.color(0xFFE04040); g.text("the dead", m + ui.dp(60), y, ui.dp(11), Gfx.ALIGN_LEFT, true);
        g.color(0xFF4F8CFF); g.text("police", m + ui.dp(130), y, ui.dp(11), Gfx.ALIGN_LEFT, true);
        g.color(0xFF9CC060); g.text("army", m + ui.dp(185), y, ui.dp(11), Gfx.ALIGN_LEFT, true);
        y += ui.dp(18);
        int tp = s.turningPoint();
        if (tp >= 0) {
            float t = s.tTime[tp];
            int hh = (int) ((World.START_HOUR + t / 60) % 24), mm = (int) (t % 60);
            y += ui.wrap("The turning point came on day " + (1 + (int) ((World.START_HOUR + t / 60) / 24)) + " at " + String.format("%02d:%02d", hh, mm)
                    + ", when the dead grew fastest.", m, y + ui.dp(10), W - m * 2, ui.dp(12.5f), Ui.TEXT, 3) + ui.dp(8);
        }
        String[][] nums = {
                {"People killed", fmt(s.civDeaths)}, {"Infected", fmt(s.infectedTotal)}, {"Most of the dead at once", fmt(s.peakZombies)},
                {"Dead destroyed", fmt(s.zombieKills)}, {"  by the police", fmt(s.killsBySide[1])}, {"  by the army", fmt(s.killsBySide[2])},
                {"Officers lost", "" + s.armedDeaths[Ents.COP]}, {"Soldiers lost", "" + s.armedDeaths[Ents.SOLDIER]},
                {"Help from outside", fmt(s.outsidePolice + s.outsideSoldiers)}, {"Evacuated", fmt(w.strat.evacuatedOut + w.strat.evacuatedArrived)},
                {"Air strikes", "" + s.airStrikes}, {"Bridges blown", "" + s.bridgesBlown}, {"Places overrun", "" + s.facilitiesLost},
                {"Buildings destroyed", "" + s.buildingsLost}, {"Vehicles lost", "" + s.vehiclesLost}, {"Shots fired", fmt(s.shots)},
                {"Public trust", (int) w.gov.trust + "%"}};
        for (String[] r : nums) {
            g.color(Ui.DIM);
            g.text(r[0], m, y + ui.dp(14), ui.dp(12.5f), Gfx.ALIGN_LEFT, false);
            g.color(Ui.TEXT);
            g.text(r[1], W - m, y + ui.dp(14), ui.dp(12.5f), Gfx.ALIGN_RIGHT, true);
            y += ui.dp(20);
        }
        y += ui.dp(12);
        g.color(Ui.ACCENT);
        g.text("BEST FIGHTERS", m, y + ui.dp(12), ui.dp(13), Gfx.ALIGN_LEFT, true);
        y += ui.dp(20);
        Integer[] order = new Integer[10];
        for (int i = 0; i < 10; i++) order[i] = i;
        final int[] bk = s.bestKills;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return bk[b] - bk[a];
            }
        });
        for (int k : order) {
            if (s.bestName[k] == null || s.bestKills[k] == 0) continue;
            g.color(Ui.TEXT);
            g.text(s.bestName[k], m, y + ui.dp(12), ui.dp(12), Gfx.ALIGN_LEFT, false);
            g.text(s.bestKills[k] + "", W - m, y + ui.dp(12), ui.dp(12), Gfx.ALIGN_RIGHT, true);
            y += ui.dp(18);
        }
        y += ui.dp(12);
        g.color(Ui.ACCENT);
        g.text("WHAT HAPPENED", m, y + ui.dp(12), ui.dp(13), Gfx.ALIGN_LEFT, true);
        y += ui.dp(18);
        for (Stats.Event ev : s.events) {
            int mins = (int) (ev.time / 60);
            String when = "Day " + (1 + (int) ((World.START_HOUR + ev.time / 60) / 24)) + " " + String.format("%02d:%02d", (int) ((World.START_HOUR + ev.time / 60) % 24), (int) (ev.time % 60));
            g.color(Ui.DIM);
            g.text(when, m, y + ui.dp(12), ui.dp(10.5f), Gfx.ALIGN_LEFT, false);
            y += ui.wrap(ev.text, m + ui.dp(78), y + ui.dp(12), W - m * 2 - ui.dp(78), ui.dp(11.5f), Ui.TEXT, 3) + ui.dp(4);
            if (mins < 0) break;
        }
        y += ui.dp(12);
        g.color(Ui.ACCENT);
        g.text("THE FALLEN (" + s.fallen.size() + ")", m, y + ui.dp(12), ui.dp(13), Gfx.ALIGN_LEFT, true);
        y += ui.dp(18);
        for (int i = s.fallen.size() - 1; i >= Math.max(0, s.fallen.size() - 40); i--) {
            y += ui.wrap(s.fallen.get(i), m, y + ui.dp(12), W - m * 2, ui.dp(11), Ui.DIM, 2) + ui.dp(3);
        }
        g.restore();
        maxScroll = Math.max(0, y + scroll - H + ui.dp(40));
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, ui.dp(54));
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("After-action report", m + ui.dp(84), ui.dp(34));
    }

    private void series(Gfx g, int[] v, int n, int max, float x, float y, float w2, float h, int color) {
        g.color(color);
        float px0 = x, py0 = y + h - (float) Math.sqrt(v[0] / (float) max) * h;
        for (int i = 1; i < n; i++) {
            float px1 = x + w2 * i / (n - 1f), py1 = y + h - (float) Math.sqrt(v[i] / (float) max) * h;
            g.line(px0, py0, px1, py1, ui.dp(1.6f));
            px0 = px1;
            py0 = py1;
        }
    }

    // ------------------------------------------------------------------ buttons

    private void onButton(String id) {
        pf.playSound("click", 0.4f * settings.sound / 100f, 0);
        if (id.length() == 0) return;
        switch (id) {
            case "new": screen = S_NEW; return;
            case "resume":
                if (w != null) screen = S_PLAY;
                paused = w != null && w.ended;
                return;
            case "continue": loadFrom("autosave.sav"); return;
            case "load":
            case "loadp": saving = false; back = screen; screen = S_SLOTS; return;
            case "save": saving = true; back = S_PAUSE; screen = S_SLOTS; return;
            case "settings": back = screen; screen = S_SETTINGS; return;
            case "help": back = screen; screen = S_HELP; scroll = 0; return;
            case "notes": back = screen; screen = S_NOTES; scroll = 0; return;
            case "quit": pf.exit(); return;
            case "back":
                if (screen == S_SETTINGS) {
                    settings.save(pf);
                    applySettings();
                }
                screen = screen == S_NEW ? S_MENU : back == S_PLAY ? S_PAUSE : back;
                scroll = 0;
                return;
            case "start": start(); return;
            case "preset": ngPreset = (ngPreset + 1) % RegionGen.PRESETS.length; return;
            case "size": ngSize = (ngSize + 1) % RegionGen.SIZES.length; return;
            case "seed": ngSeed = (ngSeed * 1103515245L + 12345L + System.nanoTime()) % 1000000L; if (ngSeed < 0) ngSeed = -ngSeed; return;
            case "pause": screen = S_PAUSE; return;
            case "playpause": paused = !paused; return;
            case "speed": speedIdx = (speedIdx + 1) % SPEEDS.length; paused = false; return;
            case "report": back = screen == S_PAUSE ? S_PAUSE : S_PLAY; screen = S_REPORT; scroll = 0; return;
            case "radio": back = S_PAUSE; screen = S_RADIO; scroll = 0; return;
            case "shot": screenshot(); return;
            case "hideui": hideUi = true; screen = S_PLAY; return;
            case "showui": hideUi = false; return;
            case "tomenu": autosave(); screen = S_MENU; return;
            case "watch": paused = false; w.ended = w.mode != World.MODE_SANDBOX; ended = true; if (w.mode != World.MODE_SANDBOX) { w.mode = World.MODE_SANDBOX; w.ended = false; } return;
            case "minimap": {
                float k = w.r.px() / mmS;
                cam.follow = -1;
                cam.flyTo((upX - mmX) * k, (upY - mmY) * k, cam.zoom);
                return;
            }
            case "curfew": w.gov.setCurfew(!w.gov.curfew); return;
            case "o:release": Tools.releaseSquad(w, inspSquad); inspSquad = null; return;
            case "o:home": if (inspSquad != null) { w.sq.goHome(inspSquad); inspSquad.player = true; } return;
            case "i:close": closeInspector(); return;
            case "i:follow":
                cam.follow = cam.follow == inspEnt ? -1 : inspEnt;
                cam.followVehicle = -1;
                return;
            case "i:followv":
                cam.followVehicle = cam.followVehicle == inspVeh ? -1 : inspVeh;
                cam.follow = -1;
                return;
            case "i:squad":
                if (inspEnt >= 0 && w.e.squad[inspEnt] >= 0) inspSquad = w.sq.get(w.e.squad[inspEnt]);
                inspEnt = inspVeh = inspFac = inspSettle = -1;
                tool = "orders";
                return;
            case "i:evac":
                w.gov.setEvac(inspSettle, !w.gov.evacuating(inspSettle));
                return;
            case "i:zoom": {
                Region.Settlement s = w.r.settlements.get(inspSettle);
                cam.flyTo(s.cx * 16f, s.cy * 16f, 0.35f);
                closeInspector();
                return;
            }
            default:
        }
        if (id.startsWith("mode:")) {
            ngMode = Integer.parseInt(id.substring(5));
            ngScenario = 0;
        } else if (id.startsWith("scen:")) ngScenario = Integer.parseInt(id.substring(5));
        else if (id.startsWith("tool:")) {
            String t = id.substring(5);
            if (t.equals("map")) {
                mapToggle();
                return;
            }
            if (t.equals("fire")) {
                w.strat.playerFire = !w.strat.playerFire;
                return;
            }
            tool = t;
            closeInspector();
            if (!t.equals("orders")) inspSquad = null;
            if (t.equals("police") && (placeUnit == null || w.c.unit(placeUnit) == null || w.c.unit(placeUnit).kind != Content.COP)) { placeUnit = "patrol"; placeVehicle = null; }
            if (t.equals("army") && (placeUnit == null || w.c.unit(placeUnit) == null || w.c.unit(placeUnit).kind != Content.SOLDIER)) { placeUnit = "rifleman"; placeVehicle = null; }
            if (t.equals("erase") && brush < 10) brush = 50;
        } else if (id.startsWith("z:")) {
            String z = id.substring(2);
            zType = z.equals("-1") ? -1 : content.ztype(z).index;
        } else if (id.startsWith("b:")) brush = Integer.parseInt(id.substring(2));
        else if (id.startsWith("u:")) {
            placeUnit = id.substring(2);
            placeVehicle = null;
        } else if (id.startsWith("v:")) placeVehicle = id.substring(2);
        else if (id.startsWith("s:")) strikeMode = id.substring(2);
        else if (id.startsWith("set:")) setting(id.substring(4));
        else if (id.startsWith("slot:")) {
            String n = id.substring(5);
            if (saving) {
                saveTo(n);
                screen = S_PAUSE;
            } else if (pf.exists(n + ".sav")) loadFrom(n + ".sav");
        } else if (id.startsWith("goto:")) {
            int i = Integer.parseInt(id.substring(5));
            if (i < w.news.log.size()) {
                News.Item it = w.news.log.get(i);
                cam.flyTo(it.x, it.y, Math.max(cam.zoom, 0.6f));
                cam.follow = -1;
                screen = S_PLAY;
            }
        }
    }

    private void setting(String k) {
        Settings s = settings;
        switch (k) {
            case "sound": s.sound = (s.sound + 20) % 120; break;
            case "music": s.music = (s.music + 25) % 125; break;
            case "lean3d": s.lean3d = !s.lean3d; break;
            case "realistic": s.realistic = !s.realistic; break;
            case "blood": s.blood = !s.blood; break;
            case "health": s.healthBars = !s.healthBars; break;
            case "names": s.names = !s.names; break;
            case "radio": s.radio = !s.radio; break;
            case "minimap": s.minimap = !s.minimap; break;
            case "vibrate": s.vibrate = !s.vibrate; break;
            case "ui": s.uiSize = (s.uiSize + 1) % 3; break;
            case "threads": s.threads = (s.threads + 1) % 5; break;
            case "max": {
                int idx = 0;
                for (int i = 0; i < Settings.MAX_OPTIONS.length; i++) if (Settings.MAX_OPTIONS[i] == s.maxIndividuals) idx = i;
                s.maxIndividuals = Settings.MAX_OPTIONS[(idx + 1) % Settings.MAX_OPTIONS.length];
                break;
            }
            default:
        }
        settings.save(pf);
        applySettings();
    }

    private void mapToggle() {
        if (cam.zoom > cam.minZoom * 1.5f) {
            prevX = cam.cx;
            prevY = cam.cy;
            prevZoom = cam.zoom;
            cam.follow = -1;
            cam.flyTo(w.r.px() / 2f, w.r.px() / 2f, cam.minZoom);
        } else cam.flyTo(prevX, prevY, Math.max(prevZoom, 0.3f));
    }

    // ------------------------------------------------------------------ saving

    void saveTo(String name) {
        try {
            OutputStream out = pf.openWrite(name + ".tmp");
            SaveIO.save(w, out);
            out.close();
            // replace the old save only once the new one is complete
            InputStream in = pf.openRead(name + ".tmp");
            OutputStream o2 = pf.openWrite(name + ".sav");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) o2.write(buf, 0, n);
            in.close();
            o2.close();
            pf.delete(name + ".tmp");
            pf.setPref("info." + name, w.r.name + ", " + w.clock() + (w.scenario != null ? ", " + w.scenario.name : "") + ". " + WorldView.fmt(w.totalZombies()) + " of the dead");
            if (!name.equals("autosave")) toast("Saved");
        } catch (Exception e) {
            toast("Couldn't save: " + e.getMessage());
        }
    }

    public void autosave() {
        if (w != null && screen != S_LOADING) saveTo("autosave");
    }

    private void screenshot() {
        GImage img = pf.newImage(cam.sw, cam.sh);
        Gfx g = img.gfx();
        view.draw(g, cam, 0);
        pf.shareImage(img);
        screen = S_PLAY;
    }

    // ------------------------------------------------------------------ input

    public static final int DOWN = 0, MOVE = 1, UP = 2, CANCEL = 3;

    /** A touch event: action, pointer id (0-9), screen position. */
    public void touch(int action, int id, float x, float y) {
        if (id < 0 || id >= 10) return;
        if (action == DOWN) {
            down[id] = true;
            px[id] = x;
            py[id] = y;
            pointers++;
            if (pointers == 1) {
                downX = x;
                downY = y;
                downT = System.currentTimeMillis();
                moved = false;
                pinched = false;
                String hit = ui.at(x, y);
                toUi = hit != null;
                ui.pressed = hit;
                if (screen == S_PLAY && w != null && w.mode == World.MODE_SURVIVOR && dist(x, y, joyCX, joyCY) < ui.dp(80)) {
                    joyPtr = id;
                    toUi = true;
                    ui.pressed = null;
                    joy(x, y);
                } else if (!toUi && screen == S_PLAY && brushTool()) {
                    lastPaintX = x;
                    lastPaintY = y;
                    paint(x, y);
                }
            } else if (pointers == 2 && !toUi) {
                beginPinch();
            } else if (joyPtr >= 0 && id != joyPtr && pointers == 2) {
                // the other thumb pans while the stick is held
            }
            return;
        }
        if (action == MOVE) {
            if (!down[id]) return;
            float ox = px[id], oy = py[id];
            px[id] = x;
            py[id] = y;
            if (id == joyPtr) {
                joy(x, y);
                return;
            }
            if (Math.abs(x - downX) + Math.abs(y - downY) > ui.dp(10)) moved = true;
            if (toUi) {
                if (isScrollScreen() && moved) {
                    ui.pressed = null;
                    scroll = Math.max(0, Math.min(maxScroll, scroll - (y - oy)));
                }
                return;
            }
            if (screen != S_PLAY || w == null) {
                if (isScrollScreen()) scroll = Math.max(0, Math.min(maxScroll, scroll - (y - oy)));
                return;
            }
            if (pointers >= 2) {
                pinch();
                return;
            }
            if (brushTool() && !pinched) {
                if (dist(x, y, lastPaintX, lastPaintY) > Math.max(ui.dp(14), brushRadius() * cam.zoom * 0.8f)) {
                    paint(x, y);
                    lastPaintX = x;
                    lastPaintY = y;
                }
                return;
            }
            if (!pinched) cam.pan(x - ox, y - oy);
            return;
        }
        if (action == UP || action == CANCEL) {
            if (!down[id]) return;
            down[id] = false;
            pointers = Math.max(0, pointers - 1);
            if (id == joyPtr) {
                joyPtr = -1;
                if (w != null) {
                    w.strat.playerJoyX = 0;
                    w.strat.playerJoyY = 0;
                }
                return;
            }
            if (pointers == 1 && pinched) {
                // one finger left after a pinch: carry on panning from it
                for (int k = 0; k < 10; k++) if (down[k]) { downX = px[k]; downY = py[k]; }
                return;
            }
            if (pointers > 0) return;
            if (action == CANCEL) {
                ui.pressed = null;
                return;
            }
            if (toUi) {
                upX = x;
                upY = y;
                String hit = ui.at(x, y);
                if (hit != null && hit.equals(ui.pressed) && !(moved && isScrollScreen())) onButton(hit);
                ui.pressed = null;
                return;
            }
            if (screen == S_PLAY && w != null && !moved && !pinched && System.currentTimeMillis() - downT < 600) tap(x, y);
        }
    }

    private boolean isScrollScreen() {
        return screen == S_REPORT || screen == S_RADIO || screen == S_HELP || screen == S_NOTES;
    }

    private void joy(float x, float y) {
        float r = ui.dp(54);
        float dx = (x - joyCX) / r, dy = (y - joyCY) / r;
        float l = (float) Math.sqrt(dx * dx + dy * dy);
        if (l > 1) {
            dx /= l;
            dy /= l;
        }
        w.strat.playerJoyX = dx;
        w.strat.playerJoyY = dy;
    }

    private void beginPinch() {
        int a = -1, b = -1;
        for (int k = 0; k < 10; k++)
            if (down[k]) {
                if (a < 0) a = k;
                else if (b < 0) b = k;
            }
        if (b < 0) return;
        pinched = true;
        pinchDist = dist(px[a], py[a], px[b], py[b]);
        pinchMidX = (px[a] + px[b]) / 2;
        pinchMidY = (py[a] + py[b]) / 2;
    }

    private void pinch() {
        int a = -1, b = -1;
        for (int k = 0; k < 10; k++)
            if (down[k]) {
                if (a < 0) a = k;
                else if (b < 0) b = k;
            }
        if (b < 0) return;
        if (!pinched) {
            beginPinch();
            return;
        }
        float d = dist(px[a], py[a], px[b], py[b]);
        float mx = (px[a] + px[b]) / 2, my = (py[a] + py[b]) / 2;
        if (pinchDist > 10 && d > 10) cam.zoomAt(d / pinchDist, mx, my);
        cam.pan(mx - pinchMidX, my - pinchMidY);
        pinchDist = d;
        pinchMidX = mx;
        pinchMidY = my;
    }

    /** Mouse wheel (desktop): positive = zoom in. */
    public void wheel(float notches, float x, float y) {
        if (screen == S_PLAY) cam.zoomAt((float) Math.pow(1.15, notches), x, y);
        else if (isScrollScreen()) scroll = Math.max(0, Math.min(maxScroll, scroll - notches * ui.dp(40)));
    }

    /** The phone's back button. Returns false to let the app close. */
    public boolean back() {
        switch (screen) {
            case S_PLAY:
                if (inspectorOpen()) closeInspector();
                else screen = S_PAUSE;
                return true;
            case S_PAUSE: screen = S_PLAY; return true;
            case S_MENU: return false;
            case S_LOADING: return true;
            default: onButton("back"); return true;
        }
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private boolean brushTool() {
        return tool.equals("dead") || tool.equals("erase");
    }

    private float brushRadius() {
        if (tool.equals("erase")) return brush * 3 + 30;
        return brush <= 1 ? 6 : 14 + (float) Math.sqrt(brush) * 12;
    }

    private void paint(float sx, float sy) {
        float x = cam.toWorldX(sx), y = cam.toWorldY(sy);
        if (x < 0 || y < 0 || x > w.r.px() || y > w.r.px()) return;
        if (tool.equals("erase")) {
            Tools.erase(w, x, y, brushRadius());
            return;
        }
        if (brush >= 2000) {
            if (!w.r.walkablePx(x, y)) {
                toast("The dead can't stand there");
                return;
            }
            if (cam.zoom < 0.3f) Tools.paintHorde(w, x, y, brush);
            else Tools.paintZombies(w, x, y, brush, brushRadius(), zType);
            toast("A horde of " + fmt(brush));
            return;
        }
        int made = Tools.paintZombies(w, x, y, brush, brushRadius(), zType);
        if (made == 0 && brush > 1) toast("Nowhere to stand there");
        view.brushX = x;
        view.brushY = y;
        view.brushR = brushRadius();
        view.brushColor = 0x90E04040;
    }

    /** A tap on the map: the current tool acts there, or it shows what's there. */
    private void tap(float sx, float sy) {
        float x = cam.toWorldX(sx), y = cam.toWorldY(sy);
        if (brushTool()) return; // painted on the way down
        switch (tool) {
            case "police":
            case "army": {
                if (placeVehicle != null) {
                    int v = Tools.placeVehicle(w, placeVehicle, x, y);
                    toast(v >= 0 ? w.c.vtype(placeVehicle).name + " placed" : "No road near there");
                } else {
                    Content.Unit u = w.c.unit(placeUnit);
                    int n = placeUnit.equals("sniper") || placeUnit.equals("marksman") ? 2 : placeUnit.equals("swat") || placeUnit.equals("riot") || placeUnit.equals("guard") || placeUnit.equals("rifleman") ? 8 : 4;
                    Squads.Squad s = Tools.placeSquad(w, placeUnit, n, x, y);
                    toast(s != null ? n + " " + u.name.toLowerCase() + (n > 1 ? "s" : "") + " placed" : "They can't stand there");
                }
                return;
            }
            case "strike": {
                if (strikeMode.equals("bridge")) {
                    int e = Tools.bridgeAt(w, x, y);
                    if (e < 0) toast("Tap a bridge on a road");
                    else w.strat.blowBridge(e, true);
                } else if (!w.mil.callStrike(x, y, true)) toast("No air strikes left");
                return;
            }
            case "orders": {
                Squads.Squad s = Tools.squadAt(w, x, y, Math.max(40, ui.dp(22) / cam.zoom));
                if (s != null && s != inspSquad) {
                    inspSquad = s;
                    return;
                }
                if (inspSquad != null && inspSquad.alive) {
                    if (!w.r.walkablePx(x, y)) {
                        toast("They can't go there");
                        return;
                    }
                    Tools.orderSquad(w, inspSquad, x, y);
                    return;
                }
                toast("Tap a squad first");
                return;
            }
            case "town": {
                int st = Tools.settlementAt(w, x, y);
                if (st >= 0) {
                    closeInspector();
                    inspSettle = st;
                } else toast("Tap a town or the city");
                return;
            }
            default:
                inspect(sx, sy, x, y);
        }
    }

    private void inspect(float sx, float sy, float x, float y) {
        closeInspector();
        float reach = Math.max(ui.dp(16) / cam.zoom, 8);
        Ents e = w.e;
        int best = -1;
        float bd = reach * reach;
        if (cam.zoom > 0.25f) {
            for (int i = 0; i < e.top; i++) {
                if (e.loc[i] != Ents.FREE && e.loc[i] != Ents.BODY) continue;
                float dx = e.x[i] - x, dy = e.y[i] - y, d = dx * dx + dy * dy;
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
        }
        if (best >= 0) {
            inspEnt = best;
            return;
        }
        int v = -1;
        bd = (reach * 1.6f) * (reach * 1.6f);
        for (int i = 0; i < w.veh.top; i++) {
            if (w.veh.state[i] == Vehicles.V_NONE) continue;
            float dx = w.veh.x[i] - x, dy = w.veh.y[i] - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                v = i;
            }
        }
        if (v >= 0) {
            inspVeh = v;
            return;
        }
        for (Region.Facility f : w.r.facilities) {
            if (f.kind == Region.F_HELIPAD || f.kind == Region.F_PARK) continue;
            if (dist(cam.toScreenX(f.x), cam.toScreenY(f.y), sx, sy) < ui.dp(16)) {
                inspFac = f.id;
                return;
            }
        }
        if (cam.zoom < 0.3f) {
            Squads.Squad s = Tools.squadAt(w, x, y, ui.dp(14) / cam.zoom);
            if (s != null) {
                inspSquad = s;
                return;
            }
        }
        int st = Tools.settlementAt(w, x, y);
        if (st >= 0 && cam.zoom < 0.3f) inspSettle = st;
    }

    public void key(int code) {
        if (screen != S_PLAY || w == null) return;
        if (code == ' ') paused = !paused;
        else if (code >= '1' && code <= '5') speedIdx = code - '1';
        else if (code == 'm') mapToggle();
    }

    /** For tests and tools: the hit area under a point. */
    public String hitAt(float x, float y) {
        return ui.at(x, y);
    }

    public World world() {
        return w;
    }

    public int screen() {
        return screen;
    }

    /** Pausing the app (phone call, home button): stop and keep a save. */
    public void onPause() {
        autosave();
    }
}
