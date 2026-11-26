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
            S_RADIO = 7, S_HELP = 8, S_NOTES = 9, S_SLOTS = 10, S_EDITOR = 11, S_TIMELAPSE = 12, S_REPLAYS = 13,
            S_PLAYERS = 14, S_HANDOFF = 15;
    /** Game seconds per real second. From 60 up it's strategic fast-forward: an hour, six hours, a day a second. */
    static final int[] SPEEDS = {1, 2, 4, 8, 16, 60, 360, 1440};
    static final String[] SPEED_NAMES = {"1x", "2x", "4x", "8x", "16x", "1h/s", "6h/s", "1d/s"};

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
    /** The role picked on the New Game screen (-1 = the scenario's own). */
    int ngRole = -1;
    /** A replay being watched (the world is fed its recorded inputs), or null. */
    Replay replay;
    final DirectorScreens dir = new DirectorScreens(this);
    /** The scenario editor's custom scenarios file. */
    static final String CUSTOM = "custom_scenarios.ini";
    boolean customContent;
    long ngSeed;
    private volatile Region preview;
    private GImage previewImg;
    private volatile boolean previewBusy;
    private long previewKey = -1;

    // loading
    private volatile World loaded;
    private volatile String loadingText = "";
    private volatile Throwable loadError;
    /** Which load is current: a load cancelled with back (or superseded) finds it changed and throws its result away. */
    private volatile int loadGen;
    /** The screen to go back to if a load is cancelled. */
    private int loadingFrom = S_MENU;
    /** The preview failed for this key: don't try again every frame. */
    private volatile long previewFailed = -1;
    private volatile Thread previewThread;

    // things handed to the game's thread (answers typed into dialogs arrive on the UI thread)
    private final java.util.ArrayDeque<Runnable> inbox = new java.util.ArrayDeque<Runnable>();
    /** True while the world is stepping: an exception then may leave it half-changed, so it isn't autosaved. */
    private boolean stepping;
    /** Real seconds since the last autosave while playing. */
    private float sinceAutosave;
    static final float AUTOSAVE_EVERY = 180;
    /** The fast-forward the device actually reached, in game seconds per real second (smoothed). */
    private float achieved;

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
    Forces.Formation inspFormation;
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
            // a content.ini in the app's own files replaces the built-in one (modding)
            InputStream in;
            if (pf.exists("content.ini")) {
                in = pf.openRead("content.ini");
                customContent = true;
            } else in = pf.openAsset("content.ini");
            content = Content.load(in);
            in.close();
        } catch (Exception e) {
            error = "Couldn't read the game's content" + (customContent ? " (your content.ini)" : "") + ": " + e;
        }
        loadCustomScenarios();
        ngSeed = System.currentTimeMillis() % 1000000;
        pf.setMusic(0, settings.music / 100f);
    }

    void loadCustomScenarios() {
        if (content == null || !pf.exists(CUSTOM)) return;
        try {
            InputStream in = pf.openRead(CUSTOM);
            content.addScenarios(in, true);
            in.close();
        } catch (Exception e) {
            toast("Couldn't read your scenarios");
        }
    }

    float d() {
        float[] scale = {0.85f, 1f, 1.2f};
        return pf.density() * scale[Math.max(0, Math.min(2, settings.uiSize))];
    }

    // ------------------------------------------------------------------ frame

    /** Runs r on the game's thread before the next frame. Safe to call from any thread. */
    public void post(Runnable r) {
        synchronized (inbox) {
            inbox.add(r);
        }
    }

    private void runPosted() {
        for (int n = 0; n < 64; n++) {
            Runnable r;
            synchronized (inbox) {
                r = inbox.poll();
            }
            if (r == null) return;
            r.run();
        }
    }

    /** Asks for text; the answer is handed to the game's thread (the dialog's answer may come on the UI thread). */
    void ask(String title, String current, final Platform.TextAnswer answer) {
        pf.askText(title, current, answer == null ? null : new Platform.TextAnswer() {
            public void answer(final String text) {
                post(new Runnable() {
                    public void run() {
                        answer.answer(text);
                    }
                });
            }
        });
    }

    public void frame(Gfx g, float dt) {
        try {
            frameInner(g, dt);
        } catch (Throwable t) {
            crashed(t);
        }
    }

    /**
     * Something threw during a frame. The game keeps running: it says what went wrong and offers the menu. The
     * world is autosaved first unless it was in the middle of a step (then the last good autosave is kept).
     */
    public void crashed(Throwable t) {
        t.printStackTrace();
        boolean wasStepping = stepping;
        stepping = false;
        if (error != null) return;
        String where = "";
        StackTraceElement[] st = t.getStackTrace();
        if (st.length > 0) where = " (" + st[0].getFileName() + ":" + st[0].getLineNumber() + ")";
        String saved = "";
        if (w != null && !wasStepping && screen != S_LOADING && replay == null) {
            try {
                saveTo("autosave");
                saved = " The game was saved: Continue picks it up.";
            } catch (Throwable ignored) {
            }
        } else if (w != null && pf.exists("autosave.sav")) saved = " Continue goes back to the last autosave.";
        error = "Something went wrong: " + t + where + "." + saved;
        crashedWorld = true;
    }

    /** The error screen came from a crash in a running game (the world may not be trustworthy). */
    private boolean crashedWorld;

    /** Leaves the error screen for the menu. */
    private void clearError() {
        error = null;
        loadError = null;
        loaded = null;
        if (crashedWorld) closeWorld();
        crashedWorld = false;
        screen = S_MENU;
        scroll = 0;
    }

    private void errorScreen(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(0xFF101318);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(16);
        g.color(Ui.ACCENT);
        g.text(content == null ? "The game can't start" : "Something went wrong", m, ui.dp(48), ui.dp(18), Gfx.ALIGN_LEFT, true);
        ui.wrap(error, m, ui.dp(80), W - m * 2, ui.dp(14), Ui.TEXT, 20);
        float bw = Math.min(W - m * 2, ui.dp(320)), bh = ui.dp(50);
        if (content == null) ui.bigButton("quit", (W - bw) / 2, H - bh - ui.dp(40), bw, bh, "Quit", Ui.ACCENT);
        else ui.bigButton("errmenu", (W - bw) / 2, H - bh - ui.dp(40), bw, bh, "Back to the menu", Ui.ACCENT);
    }

    private void frameInner(Gfx g, float dt) {
        runPosted();
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
            errorScreen(g);
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
            case S_EDITOR: dir.editor(g); break;
            case S_TIMELAPSE: dir.timelapse(g, dt); break;
            case S_REPLAYS: dir.replays(g); break;
            case S_PLAYERS: play(g, 0); players(g); break;
            case S_HANDOFF: handoff(g); break;
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
        g.text("The outbreak goes national", cx, y + ui.dp(70), ui.dp(14), Gfx.ALIGN_CENTER, false);
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
        float half = (bw - ui.dp(10)) / 2;
        ui.button("load", bx, y, half, bh * 0.85f, "Load game", false);
        ui.button("replays", bx + half + ui.dp(10), y, half, bh * 0.85f, "Replays", false);
        y += bh * 0.85f + ui.dp(10);
        ui.button("settings", bx, y, half, bh * 0.8f, "Settings", false);
        ui.button("help", bx + half + ui.dp(10), y, half, bh * 0.8f, "How to play", false);
        y += bh * 0.8f + ui.dp(10);
        ui.button("notes", bx, y, half, bh * 0.8f, "What's new", false);
        ui.button("quit", bx + half + ui.dp(10), y, half, bh * 0.8f, "Quit", false);
        g.color(0x80FFFFFF);
        g.text("Version " + Texts.VERSION, cx, H - ui.dp(16), ui.dp(11), Gfx.ALIGN_CENTER, false);
    }

    // ------------------------------------------------------------------ new game

    static final String[] TABS = {"Sandbox", "Campaigns", "Survive", "Yours"};

    /** The scenarios under a New Game tab: sandbox, the commander campaigns, survival, the player's own. */
    List<Content.Scenario> scenariosFor(int tab) {
        List<Content.Scenario> out = new java.util.ArrayList<Content.Scenario>();
        String m = tab == 0 ? "sandbox" : tab == 1 ? "commander" : "survivor";
        for (Content.Scenario s : content.scenarios) {
            if (tab == 3 ? s.custom : !s.custom && s.mode.equals(m)) out.add(s);
        }
        return out;
    }

    Content.Scenario chosenScenario() {
        List<Content.Scenario> list = scenariosFor(ngMode);
        return list.isEmpty() ? null : list.get(Math.min(ngScenario, list.size() - 1));
    }

    /** The roles offered for the chosen scenario. */
    int[] rolesFor(Content.Scenario sc) {
        if (sc != null && sc.mode.equals("survivor")) return new int[]{Roles.SURVIVOR, Roles.OFFICER};
        return new int[]{Roles.GOD, Roles.PRESIDENT, Roles.MILITARY, Roles.INCIDENT, Roles.PRECINCT, Roles.SQUAD, Roles.OFFICER, Roles.SURVIVOR};
    }

    int chosenRole() {
        return ngRole >= 0 ? ngRole : Setup.defaultRole(chosenScenario());
    }

    String regionCode() {
        return RegionGen.SIZES[ngSize] + "-" + ngPreset + "-" + ngSeed;
    }

    /** A challenge: the map, the scenario and the role, as a code to share. */
    String challengeCode() {
        Content.Scenario sc = chosenScenario();
        return "ZC2-" + RegionGen.SIZES[ngSize] + "-" + ngPreset + "-" + ngSeed + "-" + (sc == null ? "sandbox" : sc.id) + "-" + Roles.IDS[chosenRole()];
    }

    static String challengeCodeOf(World w) {
        Director.Header h = w.director.header;
        return "ZC2-" + h.size + "-" + h.preset + "-" + h.seed + "-" + (w.scenario == null ? "sandbox" : w.scenario.id) + "-" + Roles.IDS[h.role];
    }

    /** Takes a typed challenge code; false if it doesn't make sense. */
    boolean applyCode(String code) {
        String[] p = code.trim().split("-");
        if (p.length < 4 || !p[0].equalsIgnoreCase("ZC2")) return false;
        try {
            int size = Integer.parseInt(p[1]), preset = Integer.parseInt(p[2]);
            long seed = Long.parseLong(p[3]);
            int si = -1;
            for (int i = 0; i < RegionGen.SIZES.length; i++) if (RegionGen.SIZES[i] == size) si = i;
            if (si < 0 || preset < 0 || preset >= RegionGen.PRESETS.length) return false;
            ngSize = si;
            ngPreset = preset;
            ngSeed = seed;
            if (p.length > 4) {
                for (int tab = 0; tab < TABS.length; tab++) {
                    List<Content.Scenario> l = scenariosFor(tab);
                    for (int i = 0; i < l.size(); i++)
                        if (l.get(i).id.equals(p[4])) {
                            ngMode = tab;
                            ngScenario = i;
                        }
                }
            }
            ngRole = p.length > 5 ? Roles.byId(p[5]) : -1;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void newGame(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(14), y;
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("New game", m + ui.dp(84), ui.dp(34));
        y = ui.dp(54);
        float cw = (W - m * 2 - ui.dp(8) * (TABS.length - 1)) / TABS.length;
        for (int i = 0; i < TABS.length; i++) ui.button("mode:" + i, m + i * (cw + ui.dp(8)), y, cw, ui.dp(38), TABS[i], ngMode == i, 13);
        y += ui.dp(46);
        List<Content.Scenario> list = scenariosFor(ngMode);
        if (ngScenario >= list.size()) ngScenario = 0;
        float ch = ui.dp(64);
        boolean twoCol = W > ui.dp(560);
        float colW = twoCol ? (W - m * 2 - ui.dp(8)) / 2 : W - m * 2;
        // the list scrolls if it's long; at most four rows show
        int rows = twoCol ? (list.size() + 1) / 2 : list.size();
        int shown = Math.min(rows, 4);
        float listH = shown * (ch + ui.dp(6));
        maxScroll = Math.max(0, rows - shown) * (ch + ui.dp(6));
        float off = Math.min(scroll, maxScroll);
        g.save();
        g.clip(0, y, W, listH);
        for (int i = 0; i < list.size(); i++) {
            Content.Scenario s = list.get(i);
            float cx = m + (twoCol ? (i % 2) * (colW + ui.dp(8)) : 0);
            float cy = y + (twoCol ? i / 2 : i) * (ch + ui.dp(6)) - off;
            if (cy + ch < y || cy > y + listH) continue;
            ui.card("scen:" + i, cx, cy, colW, ch, s.name, s.desc, i == ngScenario);
        }
        g.restore();
        if (list.isEmpty()) {
            g.color(Ui.DIM);
            g.text("Nothing here yet: make one with \"Make your own\".", W / 2f, y + ui.dp(30), ui.dp(13), Gfx.ALIGN_CENTER, false);
            listH = ui.dp(50);
        }
        if (rows > shown) {
            g.color(Ui.DIM);
            g.text("drag the list for more", W - m, y + listH + ui.dp(8), ui.dp(10), Gfx.ALIGN_RIGHT, false);
        }
        y += listH + ui.dp(12);
        // who you are
        Content.Scenario sc = chosenScenario();
        int role = chosenRole();
        float half = (W - m * 2 - ui.dp(8)) / 2;
        ui.button("role", m, y, W - m * 2, ui.dp(40), "Play as: " + Roles.NAMES[role], false, 14);
        y += ui.dp(44);
        y += ui.wrap(Roles.DESC[role], m, y + ui.dp(10), W - m * 2, ui.dp(11), Ui.DIM, 2) + ui.dp(4);
        // the map
        float bw = (W - m * 2 - ui.dp(16)) / 3;
        ui.button("preset", m, y, bw, ui.dp(38), RegionGen.PRESETS[ngPreset], false, 13);
        ui.button("size", m + bw + ui.dp(8), y, bw, ui.dp(38), RegionGen.SIZE_NAMES[ngSize], false, 13);
        ui.button("seed", m + (bw + ui.dp(8)) * 2, y, bw, ui.dp(38), "New seed", false, 13);
        y += ui.dp(44);
        ui.button("code", m, y, half, ui.dp(34), "Enter a code", false, 12);
        ui.button("editor", m + half + ui.dp(8), y, half, ui.dp(34), "Make your own", false, 12);
        y += ui.dp(38);
        g.color(Ui.DIM);
        g.text("Code " + challengeCode(), m, y + ui.dp(10), ui.dp(11), Gfx.ALIGN_LEFT, false);
        String best = bestText(challengeCode());
        if (best != null) {
            g.color(Ui.GOOD);
            g.text(best, W - m, y + ui.dp(24), ui.dp(11), Gfx.ALIGN_RIGHT, false);
            y += ui.dp(14);
        }
        y += ui.dp(16);
        // the preview
        float startH = ui.dp(52);
        float avail = H - y - startH - ui.dp(22);
        float ps = Math.min(W - m * 2, avail);
        if (ps > ui.dp(60)) drawPreview(g, (W - ps) / 2, y, ps);
        ui.bigButton("start", m, H - startH - ui.dp(12), W - m * 2, startH, sc == null ? "Start" : "Start: " + sc.name, Ui.ACCENT);
    }

    /** The preview of the chosen map in a square; returns false while it's still being drawn. */
    boolean drawPreview(Gfx g, float pxx, float y, float ps) {
        requestPreview();
        g.color(0xFF1A2028);
        g.fillRect(pxx, y, ps, ps);
        Region pr = preview;
        if (pr == null || previewKey != key()) {
            g.color(Ui.DIM);
            g.text(previewFailed == key() ? "Couldn't draw the map: try another seed" : "Drawing the map...", pxx + ps / 2, y + ps / 2, ui.dp(13), Gfx.ALIGN_CENTER, false);
            return false;
        }
        if (previewImg == null) {
            int st = pr.W > 1024 ? 4 : 2, iw = pr.W / st;
            previewImg = pf.newImage(iw, iw);
            int[] pix = new int[iw * iw];
            for (int yy = 0; yy < iw; yy++) for (int xx = 0; xx < iw; xx++) pix[yy * iw + xx] = MapPainter.tileColor(pr, xx * st, yy * st, false);
            previewImg.setPixels(pix, iw, iw);
        }
        g.image(previewImg, pxx, y, ps, ps);
        float k = ps / pr.W;
        for (Region.Settlement s : pr.settlements) {
            if (s.kind != Region.S_CITY && (s.kind != Region.S_TOWN || ps < ui.dp(260))) continue;
            float size = ui.dp(s.capital ? 13 : s.kind == Region.S_CITY ? 11 : 9);
            g.color(0xD0000000);
            g.text(s.name, pxx + s.cx * k + 1, y + s.cy * k + 1, size, Gfx.ALIGN_CENTER, true);
            g.color(0xFFFFFFFF);
            g.text(s.name, pxx + s.cx * k, y + s.cy * k, size, Gfx.ALIGN_CENTER, true);
        }
        long people = 0;
        for (Region.Settlement s : pr.settlements) people += s.residents;
        g.color(0xD0000000);
        g.fillRect(pxx, y + ps - ui.dp(22), ps, ui.dp(22));
        g.color(0xFFFFFFFF);
        String cap = pr.name + ": " + WorldView.people(people) + " people, " + pr.states.size() + " states";
        float cs = ui.dp(12);
        while (g.textWidth(cap, cs, false) > ps - ui.dp(12) && cs > ui.dp(7)) cs *= 0.9f;
        g.text(cap, pxx + ui.dp(6), y + ps - ui.dp(7), cs, Gfx.ALIGN_LEFT, false);
        return true;
    }

    Region previewRegion() {
        return previewKey == key() ? preview : null;
    }

    long key() {
        return ngSeed * 31 + ngPreset * 7 + ngSize;
    }

    private void requestPreview() {
        final long k = key();
        if (previewKey == k || previewBusy || previewFailed == k || screen == S_LOADING) return;
        previewBusy = true;
        final int size = RegionGen.SIZES[ngSize], preset = ngPreset;
        final long seed = ngSeed;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Region r = RegionGen.generate(size, preset, seed);
                    preview = r;
                    previewKey = k;
                } catch (Throwable e) {
                    previewFailed = k;
                } finally {
                    previewBusy = false;
                }
            }
        }, "preview");
        previewThread = t;
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
        final int role = chosenRole();
        final long k = key();
        // the preview already built this map (or is building it): use it rather than building it again
        final Thread pt = previewThread;
        final boolean usePreview = previewKey == k || (previewBusy && pt != null);
        closeWorld();
        loadingFrom = S_NEW;
        screen = S_LOADING;
        loadingText = "Building " + RegionGen.SIZE_NAMES[ngSize].toLowerCase() + " (" + RegionGen.PRESETS[preset].toLowerCase() + ")...";
        loaded = null;
        loadError = null;
        final int gen = ++loadGen;
        new Thread(new Runnable() {
            public void run() {
                try {
                    Region pre = null;
                    if (usePreview) {
                        if (pt != null) pt.join();
                        if (previewKey == k) pre = preview;
                    }
                    // the game changes its region (rubble, blown bridges): the preview mustn't show it, or be reused
                    preview = null;
                    previewKey = -1;
                    if (gen != loadGen) return;
                    World nw = Setup.newGame(content, size, preset, seed, sc, settings.simThreads(), role, settings.maxIndividuals, pre);
                    loadingText = "Putting everyone in place...";
                    startX = Setup.originX;
                    startY = Setup.originY;
                    outbreakX = Setup.outbreakX;
                    outbreakY = Setup.outbreakY;
                    publish(gen, nw);
                } catch (Throwable t) {
                    if (gen == loadGen) loadError = t;
                }
            }
        }, "build").start();
    }

    volatile float startX, startY, outbreakX, outbreakY;
    volatile Replay loadedReplay;

    /** Hands a world built in the background to the game, unless its load was cancelled (then it's shut down). */
    private boolean publish(int gen, World nw) {
        synchronized (inbox) {
            if (gen != loadGen) {
                nw.shutdown();
                return false;
            }
            loaded = nw;
            return true;
        }
    }

    /** Back during loading: the background load carries on but its result is thrown away. */
    private void cancelLoading() {
        synchronized (inbox) {
            loadGen++;
            World nw = loaded;
            loaded = null;
            if (nw != null) nw.shutdown();
        }
        loadedReplay = null;
        loadError = null;
        screen = loadingFrom;
        toast("Cancelled");
    }

    /** Builds a replay in the background, then plays it. */
    void playReplay(final String file) {
        closeWorld();
        loadingFrom = screen == S_LOADING ? S_MENU : screen;
        screen = S_LOADING;
        loadingText = "Rebuilding the outbreak from its start...";
        loaded = null;
        loadError = null;
        final int gen = ++loadGen;
        new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream in = pf.openRead(file);
                    Replay r = Replay.load(content, in, settings.simThreads());
                    in.close();
                    startX = Setup.originX;
                    startY = Setup.originY;
                    loadedReplay = r;
                    if (!publish(gen, r.w)) loadedReplay = null;
                } catch (Throwable t) {
                    if (gen == loadGen) loadError = t;
                }
            }
        }, "replay").start();
    }

    /** The tool a role starts with. */
    String defaultTool() {
        switch (w.roles.role) {
            case Roles.GOD: return "dead";
            case Roles.PRECINCT: return "send";
            case Roles.OFFICER:
            case Roles.SURVIVOR: return "look";
            default: return "orders";
        }
    }

    private void loadFrom(final String name) {
        closeWorld();
        loadingFrom = screen == S_LOADING ? S_MENU : screen;
        screen = S_LOADING;
        loadingText = "Loading...";
        loaded = null;
        loadError = null;
        final int gen = ++loadGen;
        new Thread(new Runnable() {
            public void run() {
                try {
                    World nw;
                    InputStream in = pf.openRead(name);
                    try {
                        nw = SaveIO.load(content, in, settings.simThreads());
                    } catch (Throwable t) {
                        in.close();
                        if (gen == loadGen) quarantine(name, t);
                        return;
                    }
                    in.close();
                    nw.maxIndividuals = settings.maxIndividuals;
                    startX = (nw.camX0 + nw.camX1) / 2;
                    startY = (nw.camY0 + nw.camY1) / 2;
                    if (startX <= 0) {
                        startX = nw.r.city().cx * 16f;
                        startY = nw.r.city().cy * 16f;
                    }
                    publish(gen, nw);
                } catch (Throwable t) {
                    if (gen == loadGen) loadError = t;
                }
            }
        }, "load").start();
    }

    /**
     * A save that can't be read is put aside (renamed to .bad, so Continue and the slots stop offering it) and
     * the player is told, with a way back to the menu.
     */
    private void quarantine(String name, Throwable t) {
        String base = name.endsWith(".sav") ? name.substring(0, name.length() - 4) : name;
        boolean moved = pf.rename(name, base + ".bad");
        if (moved) pf.setPref("info." + base, "");
        loadError = new java.io.IOException("Couldn't read the save " + (base.equals("autosave") ? "\"Autosave\"" : "\"" + base + "\"")
                + ": it may be damaged (" + t.getMessage() + ")." + (moved ? " It has been put aside as " + base + ".bad." : ""));
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
            Throwable le = loadError;
            error = le instanceof java.io.IOException && le.getMessage() != null && le.getMessage().startsWith("Couldn't read the save")
                    ? le.getMessage() : "Something went wrong: " + le;
            loadError = null;
            crashedWorld = false;
            return;
        }
        World nw = loaded;
        if (nw != null) {
            loaded = null;
            w = nw;
            replay = loadedReplay;
            loadedReplay = null;
            bestNoted = w.roles.containedAt >= 0;
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
            tool = replay != null ? "look" : defaultTool();
            ended = w.ended;
            soundsSeen = w.fx.sHead;
            closeInspector();
            sinceAutosave = 0;
            achieved = 0;
            if (w.scenario != null && w.tick < 5) toast(w.scenario.name);
            if (w.tick < 5 && w.roles.role == Roles.SURVIVOR && w.playerEnt >= 0) {
                // where the outbreak is, from here
                float dx = outbreakX - w.e.x[w.playerEnt], dy = outbreakY - w.e.y[w.playerEnt];
                float km = (float) Math.sqrt(dx * dx + dy * dy) / 16f * 0.025f;
                if (km > 0.2f) toast("The outbreak is " + (km >= 10 ? "" + Math.round(km) : String.format("%.1f", km)) + " km " + compass(dx, dy) + ". Find somewhere safe.");
            }
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
        if (w != null && replay == null) Actions.maxIndividuals(w, settings.maxIndividuals);
        pf.setMusic(0, settings.music / 100f);
    }

    private void closeWorld() {
        if (w != null) w.shutdown();
        if (view != null) view.map.clear();
        w = null;
        view = null;
        replay = null;
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
        if (replay == null) {
            // the camera is an input to the simulation: what's in view is simulated person by person
            w.camX0 = cam.x0();
            w.camY0 = cam.y0();
            w.camX1 = cam.x1();
            w.camY1 = cam.y1();
            w.camDetail = cam.zoom >= 0.3f && !w.strategic;
            // fast-forward speeds fold the simulation into numbers; normal speeds bring the detail back
            if (speedIdx > maxSpeedIdx()) {
                speedIdx = maxSpeedIdx();
                toast("Can't fast-forward while you're playing a person");
            }
            boolean wantStrategic = SPEEDS[speedIdx] >= 60;
            if (wantStrategic != w.strategic) {
                Actions.strategic(w, wantStrategic);
                if (wantStrategic && !w.strategic) speedIdx = maxSpeedIdx();
            }
        } else if (replay.done() && !paused) {
            // the end of the recording: from here on, it's yours
            paused = true;
            toast("End of the recording: press play to take over");
        }
        // step the simulation, within a time budget
        if (!paused && screen == S_PLAY && dt > 0) {
            if (replay != null && replay.done()) {
                replay = null;
                w.director.replaying = false;
            }
            int speed = SPEEDS[speedIdx];
            if (replay != null && w.strategic) speed = Math.max(speed, 360);
            acc += dt * speed;
            float stepDt = w.strategic ? World.STRATEGIC_DT : World.DT;
            long t0 = System.nanoTime();
            int steps = 0;
            double simulated = 0;
            stepping = true;
            while (acc >= stepDt) {
                if (replay != null) {
                    if (replay.done()) break;
                    replay.beforeStep();
                    stepDt = w.strategic ? World.STRATEGIC_DT : World.DT;
                }
                w.step();
                acc -= stepDt;
                simulated += stepDt;
                steps++;
                if (System.nanoTime() - t0 > 28_000_000L) {
                    // the device can't keep up: drop the rest rather than fall further behind (the label says so)
                    acc = 0;
                    break;
                }
            }
            stepping = false;
            float rate = (float) (simulated / dt);
            achieved = achieved <= 0 ? rate : achieved + (rate - achieved) * Math.min(1, dt * 1.5f);
            // a save every few minutes, so a crash or a killed app loses little
            sinceAutosave += dt;
            if (sinceAutosave >= AUTOSAVE_EVERY && replay == null && !w.ended) {
                sinceAutosave = 0;
                autosave();
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
            recordBest();
        }
        if (!bestNoted && w.roles.containedAt >= 0) {
            bestNoted = true;
            recordBest();
        }
        sounds();
        view.selected = inspSquad;
        view.selectedFormation = inspFormation;
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
    private boolean bestNoted;

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
        g.color(Ui.DIM);
        float wx = m + bh + ui.dp(10) + g.textWidth(Gov.LEVELS[w.gov.level] + "  ", ui.dp(11), true);
        String wt = w.weather.status();
        float room = W - m - ui.dp(44) * 2 - ui.dp(14) - wx;
        while (wt.length() > 4 && g.textWidth(wt, ui.dp(11), false) > room) wt = wt.substring(0, wt.lastIndexOf(' ') > 0 ? wt.lastIndexOf(' ') : wt.length() - 1);
        g.text(wt, wx, m + ui.dp(33), ui.dp(11), Gfx.ALIGN_LEFT, false);
        int lvl = w.gov.level;
        int lc = lvl <= 0 ? Ui.GOOD : lvl == 1 ? Ui.WARN : lvl == 2 ? 0xFFE07030 : 0xFFE0453A;
        g.color(lc);
        g.text(Gov.LEVELS[lvl], m + bh + ui.dp(10), m + ui.dp(33), ui.dp(11), Gfx.ALIGN_LEFT, true);
        // speed
        float sw = ui.dp(44);
        float sx = W - m - sw * 2 - ui.dp(6);
        ui.button("playpause", sx, m, sw, bh, paused ? "▶" : "II", paused, 15);
        ui.button("speed", sx + sw + ui.dp(6), m, sw, bh, speedLabel(), w.strategic, 13);
        // the numbers
        int people = w.livingCivilians();
        String line = "People " + WorldView.people(people) + "   Dead " + WorldView.people(w.totalZombies()) + "   Police " + fmt(w.sq.count(Ents.COP) + w.forces.total(Ents.COP))
                + "   Army " + fmt(w.sq.count(Ents.SOLDIER) + w.forces.total(Ents.SOLDIER));
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
        // who you are (or that this is a replay)
        String who = replay != null ? "REPLAY: " + replay.title : w.roles.label();
        float ww = Math.min(W - ui.dp(140), g.textWidth(who, ui.dp(11), true) + ui.dp(14));
        g.color(replay != null ? 0xC0602080 : 0x90000000);
        g.fillRoundRect(m, y + m, ww, ui.dp(20), ui.dp(6));
        g.color(0xFFFFFFFF);
        String whoCut = who;
        while (g.textWidth(whoCut, ui.dp(11), true) > ww - ui.dp(12) && whoCut.length() > 6) whoCut = whoCut.substring(0, whoCut.length() - 2);
        g.text(whoCut, m + ui.dp(7), y + m + ui.dp(14), ui.dp(11), Gfx.ALIGN_LEFT, true);
        if (w.roles.players > 1 && replay == null) {
            // pass-and-play: whose turn it is, and the hand-over
            String pl = "P" + (w.roles.current + 1) + " ▸";
            ui.button("handover", m + ww + ui.dp(6), y + m - ui.dp(2), ui.dp(54), ui.dp(24), pl, false, 11);
        }
        if (view.overlay > WorldView.OV_INFECTION) view.legend(g, m, y + m + ui.dp(26), ui.dp(1));
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
        if (w.mode == World.MODE_SURVIVOR && w.playerEnt >= 0 && replay == null) joystick(g);
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

    private float mmX, mmY, mmS;
    float upX, upY;

    private float toolbarH;

    /** Each role's tools. */
    private String[] tools() {
        if (replay != null) return new String[]{"look", "map"};
        switch (w.roles.role) {
            case Roles.PRESIDENT: return new String[]{"look", "orders", "strike", "town", "map"};
            case Roles.MILITARY: return new String[]{"look", "orders", "strike", "map"};
            case Roles.INCIDENT: return new String[]{"look", "orders", "town", "map"};
            case Roles.PRECINCT: return new String[]{"look", "send", "orders", "map"};
            case Roles.SQUAD: return new String[]{"look", "orders", "map"};
            case Roles.OFFICER:
            case Roles.SURVIVOR: return new String[]{"look", "fire", "map"};
            default: return new String[]{"look", "dead", "police", "army", "strike", "orders", "town", "erase", "map"};
        }
    }

    /** What a tap does with the Towns tool. */
    String townMode = "info";

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
            case "send": return "Send";
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
                return new String[][]{{"s:air", "Air strike" + (w.mil.strikesLeft >= 0 ? " (" + w.mil.strikesLeft + ")" : ""), "s:art", "Artillery", "s:bridge", "Blow a bridge"}};
            case "town": {
                if (w.roles.role == Roles.INCIDENT)
                    return new String[][]{{"tm:info", "Look", "tm:evac", "Evacuate", "tm:army", "Ask for the army"}};
                String[] row1 = {"tm:info", "Look", "tm:evac", "Evacuate", "tm:quar", "Quarantine", "tm:army", "Send army"};
                String[] row2 = {"curfew", w.gov.curfew ? "Lift curfew" : "Curfew", "n:alert", "Raise alert", "n:martial", "Martial law", "n:mobilise", "Mobilise"};
                return new String[][]{row1, row2};
            }
            case "send": {
                if (w.roles.target < 0) return null;
                Region.Facility f = w.r.facilities.get(w.roles.target);
                java.util.List<String> row = new java.util.ArrayList<String>();
                for (Content.SquadT t : content.squads) {
                    if (!t.faction.equals("police")) continue;
                    boolean home = false;
                    for (String h : t.homes) if (h.equals("police")) home = true;
                    if (!home || f.pool == null) continue;
                    int have = f.pool[t.units[0].index] / Math.max(1, t.units.length);
                    row.add("send:" + t.id);
                    row.add(t.name + " (" + have + ")");
                }
                row.add("o:hint");
                row.add("Pick, then tap where");
                return new String[][]{row.toArray(new String[0])};
            }
            case "map": {
                String[] row1 = {"m:jump", cam.zoom > cam.minZoom * 1.5f ? "Whole country" : "Back", "ov:0", "Plain", "ov:1", "Infection", "ov:2", "Utilities"};
                String[] row2 = {"ov:3", "Supplies", "ov:4", "Trust", "ov:5", "Forces", "ov:6", "Deaths", "ov:7", "Peak"};
                return new String[][]{row1, row2};
            }
            case "orders":
                if (inspFormation != null && inspFormation.alive)
                    return new String[][]{{"o:hint", Forces.shortName(inspFormation) + " (" + inspFormation.strength() + "): tap where to hold", "o:frelease", "Release", "o:fdone", "Done"}};
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
    String sendTemplate = "patrol";

    private boolean paletteOn(String id) {
        if (id.startsWith("z:")) return id.substring(2).equals(zType < 0 ? "-1" : content.ztypes.get(zType).id);
        if (id.startsWith("b:")) return Integer.parseInt(id.substring(2)) == brush;
        if (id.startsWith("u:")) return placeVehicle == null && id.substring(2).equals(placeUnit);
        if (id.startsWith("v:")) return id.substring(2).equals(placeVehicle);
        if (id.startsWith("s:")) return id.substring(2).equals(strikeMode);
        if (id.startsWith("tm:")) return id.substring(3).equals(townMode);
        if (id.startsWith("send:")) return id.substring(5).equals(sendTemplate);
        if (id.startsWith("ov:")) return view != null && Integer.parseInt(id.substring(3)) == view.overlay;
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
        return inspEnt >= 0 || inspVeh >= 0 || inspFac >= 0 || inspSettle >= 0 || ((inspSquad != null || inspFormation != null) && !tool.equals("orders"));
    }

    void closeInspector() {
        inspEnt = inspVeh = inspFac = inspSettle = -1;
        if (!tool.equals("orders")) {
            inspSquad = null;
            inspFormation = null;
        }
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
                body.append("Ammunition in store: ").append((int) f.ammo).append(" rounds. Waiting here: ")
                        .append(w.forces.armedAt(f, Ents.COP) + w.forces.armedAt(f, Ents.SOLDIER));
            if (f.kind == Region.F_HOSPITAL) body.append(on ? "Taking in the bitten" : "The sick inside have turned");
            if (f.kind == Region.F_FUEL) body.append(on ? "Refuelling the town's vehicles" : "Vehicles here can't refuel");
            if (f.food > 0 || f.fuel > 0 || f.med > 0) {
                body.append(body.length() > 0 ? ". " : "").append("In store:");
                if (f.food > 0) body.append(" food for ").append(fmt((int) f.food)).append(" person-days");
                if (f.fuel > 0) body.append(f.food > 0 ? "," : "").append(" ").append((int) f.fuel).append(" t of fuel");
                if (f.med > 0) body.append(f.food > 0 || f.fuel > 0 ? "," : "").append(" ").append((int) f.med).append(" doses of medicine");
            }
            actions = new String[]{"i:close", "Close"};
        } else if (inspSettle >= 0) {
            Region.Settlement s = w.r.settlements.get(inspSettle);
            title = s.name;
            sub = (s.capital ? "Capital" : Region.SNAMES[s.kind]) + (s.state >= 0 && s.state < w.r.states.size() ? ", " + w.r.states.get(s.state).name : "")
                    + (w.gov.evacuating(s.id) ? ", evacuating" : "") + (w.roles.quarantined(s.id) ? ", QUARANTINED" : "")
                    + (w.disp.perimeterMode(s.id) ? ", under a police cordon" : "");
            body.append("Home to ").append(WorldView.people(s.residents)).append(". Still here: ").append(WorldView.people(w.stats.popBySettlement[s.id]))
                    .append(". The dead here: ").append(WorldView.people(w.stats.zBySettlement[s.id]));
            if (s.grid >= 0) body.append(w.strat.gridOn[s.grid] ? ". Power on" : ". No power");
            body.append(". The people are ").append(w.media.mood(s.id));
            if (w.society.militia[s.id] > 0) body.append(", ").append(w.society.militia[s.id]).append(" in militias");
            body.append(w.command.describe(s.id));
            boolean town = s.kind == Region.S_CITY || s.kind == Region.S_TOWN;
            int ro = w.roles.role;
            boolean mine = ro == Roles.GOD || ro == Roles.PRESIDENT || (ro == Roles.INCIDENT && w.roles.target == s.id);
            java.util.List<String> acts = new java.util.ArrayList<String>();
            if (town && mine && replay == null) {
                acts.add("i:evac");
                acts.add(w.gov.evacuating(s.id) ? "Stop evacuating" : "Evacuate");
                if (ro != Roles.INCIDENT) {
                    acts.add("i:quar");
                    acts.add(w.roles.quarantined(s.id) ? "Lift quarantine" : "Quarantine");
                }
                acts.add("i:army");
                acts.add("Send army");
            } else {
                acts.add("i:zoom");
                acts.add("Go there");
            }
            acts.add("i:close");
            acts.add("Close");
            actions = acts.toArray(new String[0]);
        } else if (inspFormation != null) {
            Forces.Formation f = inspFormation;
            if (!f.alive) {
                closeInspector();
                return;
            }
            title = f.name;
            sub = Forces.STATES[f.state] + (f.player ? ", under your command" : f.task >= 0 ? ", sent to " + w.r.settlements.get(f.task).name : "");
            body.append(f.strength()).append(f.faction == Ents.COP ? " officers" : " soldiers").append(" (").append(f.deployed).append(" out in squads)");
            Content.VType tank = w.c.vtype("tank");
            if (tank != null && f.hq.vpool[tank.index] + f.tanksOut > 0) body.append(", ").append(f.hq.vpool[tank.index] + f.tanksOut).append(" tanks");
            if (f.t.guns > 0) body.append(", ").append(f.t.guns).append(" guns");
            body.append(". ").append(fmt(f.kills)).append(" of the dead destroyed, ").append(f.losses).append(" lost. Ammunition ").append((int) f.hq.ammo);
            actions = new String[]{"i:fcommand", "Command it", "i:close", "Close"};
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
        ui.wrap(body.toString(), m * 2, y + ui.dp(62), textW, ui.dp(12), Ui.TEXT, 10);
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
        float pw = Math.min(W - ui.dp(40), ui.dp(380));
        float x = (W - pw) / 2, bh = ui.dp(44), gap = ui.dp(8);
        String[][] items = {{"resume", "Resume"}, {"report", "Report"}, {"save", "Save"}, {"loadp", "Load"}, {"radio", "Radio log"},
                {"timelapse", "Timelapse"}, {"savereplay", "Save replay"}, {"sharecode", "Challenge code"}, {"players", "Players"},
                {"settings", "Settings"}, {"shot", "Screenshot"}, {"hideui", "Hide buttons"}, {"tomenu", "Quit to the menu"}};
        int rows = (items.length + 1) / 2;
        float ph = rows * (bh + gap) + ui.dp(76);
        float y = (H - ph) / 2;
        ui.panel(x - ui.dp(12), y, pw + ui.dp(24), ph);
        g.color(Ui.TEXT);
        g.text("Paused", W / 2f, y + ui.dp(30), ui.dp(20), Gfx.ALIGN_CENTER, true);
        g.color(Ui.DIM);
        String line = w.r.name + (w.scenario != null ? ", " + w.scenario.name : "") + ". " + w.roles.label();
        float ts = ui.dp(11);
        while (g.textWidth(line, ts, false) > pw && ts > ui.dp(7)) ts *= 0.92f;
        g.text(line, W / 2f, y + ui.dp(52), ts, Gfx.ALIGN_CENTER, false);
        y += ui.dp(66);
        float half = (pw - gap) / 2;
        for (int k = 0; k < items.length; k++) {
            ui.button(items[k][0], x + (k % 2) * (half + gap), y + (k / 2) * (bh + gap), half, bh, items[k][1], false, 13);
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
                {"set:vibrate", "Vibration", s.vibrate ? "On" : "Off"},
                {"set:gpu", "Renderer (next start)", s.gpu ? "GPU (experimental)" : "Canvas"}};
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

    // ------------------------------------------------------------------ pass-and-play

    private static final int[] SHARED_ROLES = {Roles.PRESIDENT, Roles.MILITARY, Roles.INCIDENT, Roles.PRECINCT, Roles.SQUAD};
    private int handTo;

    /** Who plays what: the players sharing this game, and adding one. */
    private void players(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(0xD0000000);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(16), y = ui.dp(70), bw = W - m * 2;
        ui.block(0, 0, W, H);
        ui.button("back", m, ui.dp(14), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("Players", m + ui.dp(84), ui.dp(38));
        Roles ro = w.roles;
        for (int k = 0; k < ro.players; k++) {
            boolean now = k == ro.current;
            ui.card("pl:" + k, m, y, bw, ui.dp(58), "Player " + (k + 1) + (now ? " (playing now)" : ""), ro.labelOf(k), now);
            y += ui.dp(64);
        }
        y += ui.dp(8);
        if (ro.players < Roles.MAX_PLAYERS) {
            ui.wrap("Pass the phone round: each player takes a role and plays their part while the others wait. Add a player in a role; their city, station or squad is the one nearest the middle of the screen.",
                    m, y + ui.dp(4), bw, ui.dp(12), Ui.DIM, 4);
            y += ui.dp(66);
            float cw = (bw - ui.dp(8)) / 2;
            for (int i = 0; i < SHARED_ROLES.length; i++) {
                ui.button("addpl:" + SHARED_ROLES[i], m + (i % 2) * (cw + ui.dp(8)), y + (i / 2) * ui.dp(48), cw, ui.dp(40), "+ " + Roles.NAMES[SHARED_ROLES[i]], false, 12);
            }
        }
    }

    /** The hand-over: the screen goes dark so the next player can take the phone. */
    private void handoff(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, H);
        g.color(Ui.TEXT);
        g.text("Pass to Player " + (handTo + 1), W / 2f, H * 0.38f, ui.dp(26), Gfx.ALIGN_CENTER, true);
        g.color(Ui.DIM);
        g.text(w.roles.labelOf(handTo), W / 2f, H * 0.38f + ui.dp(32), ui.dp(14), Gfx.ALIGN_CENTER, false);
        float bw = Math.min(W - ui.dp(48), ui.dp(300));
        ui.bigButton("takeover", (W - bw) / 2, H * 0.55f, bw, ui.dp(54), "I'm Player " + (handTo + 1) + ": go", Ui.ON);
        ui.button("pl:back", (W - bw) / 2, H * 0.55f + ui.dp(66), bw, ui.dp(40), "Not yet", false);
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
        // the challenge, and your best at it
        String code = challengeCodeOf(w);
        g.color(Ui.DIM);
        g.text("Challenge " + code + "  (" + w.roles.label() + ")", m, y + ui.dp(14), ui.dp(11), Gfx.ALIGN_LEFT, false);
        y += ui.dp(16);
        String best = bestText(code);
        if (best != null) {
            g.color(Ui.GOOD);
            g.text(best, m, y + ui.dp(14), ui.dp(11.5f), Gfx.ALIGN_LEFT, true);
            y += ui.dp(16);
        }
        // friends' results for the same challenge
        if (replay == null) {
            float hb = (W - m * 2 - ui.dp(8)) / 2;
            ui.button("shareresult", m, y + ui.dp(4), hb, ui.dp(34), "Share my result", false, 12);
            ui.button("addresult", m + hb + ui.dp(8), y + ui.dp(4), hb, ui.dp(34), "Add a friend's result", false, 12);
            y += ui.dp(44);
            java.util.List<String[]> board = board(code);
            if (!board.isEmpty()) {
                g.color(Ui.ACCENT);
                g.text("RESULTS ON THIS CHALLENGE", m, y + ui.dp(12), ui.dp(12), Gfx.ALIGN_LEFT, true);
                y += ui.dp(18);
                int place = 1;
                for (String[] r : board) {
                    g.color(r[0].equals(playerName()) ? Ui.GOOD : Ui.TEXT);
                    g.text(place++ + ". " + r[0], m, y + ui.dp(12), ui.dp(12), Gfx.ALIGN_LEFT, false);
                    g.text(WorldView.people(Integer.parseInt(r[1])) + " lost, " + duration(Integer.parseInt(r[2]) * 60f)
                            + (r[3].equals("1") ? "" : " (not finished)"), W - m, y + ui.dp(12), ui.dp(12), Gfx.ALIGN_RIGHT, false);
                    y += ui.dp(17);
                }
            }
        }
        if (w.roles.containedAt >= 0) {
            g.color(Ui.TEXT);
            g.text("Contained after " + duration(w.roles.containedAt), m, y + ui.dp(14), ui.dp(11.5f), Gfx.ALIGN_LEFT, false);
            y += ui.dp(16);
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

    // ------------------------------------------------------------------ personal bests

    static String duration(float seconds) {
        int h = (int) (seconds / 60), mins = (int) (seconds % 60);
        if (h >= 24) return (h / 24) + "d " + (h % 24) + "h";
        return h + "h " + mins + "m";
    }

    int losses() {
        return w.stats.civDeaths + w.stats.armedDeaths[Ents.COP] + w.stats.armedDeaths[Ents.SOLDIER];
    }

    /** Keeps the best result for this challenge: fewest lives lost, fastest containment (only finished games count). */
    void recordBest() {
        if (w == null || replay != null) return;
        boolean finished = w.roles.containedAt >= 0 || (w.ended && w.won);
        if (!finished) return;
        String key = "best." + challengeCodeOf(w);
        String[] old = pf.pref(key, "").split(",");
        int bestLoss = Integer.MAX_VALUE;
        float bestTime = Float.MAX_VALUE;
        if (old.length == 2) {
            try {
                bestLoss = Integer.parseInt(old[0]);
                bestTime = Float.parseFloat(old[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        float t = w.roles.containedAt >= 0 ? w.roles.containedAt : (float) w.time;
        int loss = losses();
        if (loss < bestLoss || t < bestTime) {
            pf.setPref(key, Math.min(loss, bestLoss) + "," + Math.min(t, bestTime));
            if (loss < bestLoss || t < bestTime) toast("A personal best for this challenge");
        }
    }

    // ------------------------------------------------------------------ results to compare with friends

    String playerName() {
        return pf.pref("playerName", "You");
    }

    /** This game's result as a code: the challenge, who, the lives lost, how long, finished or not, and a check. */
    String resultCode() {
        String name = playerName().replace("~", "-");
        boolean done = w.roles.containedAt >= 0 || (w.ended && w.won);
        int minutes = (int) ((w.roles.containedAt >= 0 ? w.roles.containedAt : w.time) / 60);
        return resultCodeFor(challengeCodeOf(w), name, losses(), minutes, done);
    }

    static String resultCodeFor(String challenge, String name, int losses, int minutes, boolean done) {
        String body = challenge + "~" + name + "~" + losses + "~" + minutes + "~" + (done ? 1 : 0);
        return "ZC2R~" + body + "~" + check(body);
    }

    private static String check(String body) {
        long h = 1469598103934665603L;
        for (int i = 0; i < body.length(); i++) h = (h ^ body.charAt(i)) * 1099511628211L;
        return Long.toString(Math.abs(h % 1679616L), 36);
    }

    /** Adds a friend's result code; false if it's mistyped or for another challenge. */
    boolean addResult(String code) {
        String[] p = code.trim().split("~");
        if (p.length != 7 || !p[0].equals("ZC2R")) return false;
        String body = p[1] + "~" + p[2] + "~" + p[3] + "~" + p[4] + "~" + p[5];
        if (!check(body).equals(p[6])) return false;
        try {
            Integer.parseInt(p[3]);
            Integer.parseInt(p[4]);
        } catch (NumberFormatException e) {
            return false;
        }
        String key = "friends." + p[1];
        String list = pf.pref(key, "");
        String entry = p[2] + "," + p[3] + "," + p[4] + "," + p[5];
        // one entry per name: the newest
        StringBuilder sb = new StringBuilder();
        for (String e : list.split(";")) if (e.length() > 0 && !e.startsWith(p[2] + ",")) sb.append(e).append(';');
        sb.append(entry);
        pf.setPref(key, sb.toString());
        return true;
    }

    /** Everyone's results on a challenge, you included (your best), best first: finished, then fewest lost, then fastest. */
    java.util.List<String[]> board(String challenge) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        for (String e : pf.pref("friends." + challenge, "").split(";")) {
            String[] f = e.split(",");
            if (f.length == 4) out.add(f);
        }
        String[] b = pf.pref("best." + challenge, "").split(",");
        if (b.length == 2) {
            try {
                out.add(new String[]{playerName(), b[0], "" + (int) (Float.parseFloat(b[1]) / 60), "1"});
            } catch (NumberFormatException ignored) {
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<String[]>() {
            public int compare(String[] a, String[] c) {
                if (!a[3].equals(c[3])) return c[3].compareTo(a[3]);
                int la = Integer.parseInt(a[1]), lc = Integer.parseInt(c[1]);
                if (la != lc) return la < lc ? -1 : 1;
                return Integer.parseInt(a[2]) - Integer.parseInt(c[2]);
            }
        });
        return out;
    }

    String bestText(String code) {
        String[] v = pf.pref("best." + code, "").split(",");
        if (v.length != 2) return null;
        try {
            return "Your best: " + WorldView.people(Integer.parseInt(v[0])) + " lives lost, contained in " + duration(Float.parseFloat(v[1]));
        } catch (NumberFormatException e) {
            return null;
        }
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
            case "new": screen = S_NEW; scroll = 0; return;
            case "replays": back = screen; screen = S_REPLAYS; scroll = 0; return;
            case "editor": dir.openEditor(); return;
            case "role": {
                int[] rs = rolesFor(chosenScenario());
                int cur = chosenRole(), idx = 0;
                for (int i = 0; i < rs.length; i++) if (rs[i] == cur) idx = i;
                ngRole = rs[(idx + 1) % rs.length];
                return;
            }
            case "code":
                ask("A challenge code (map, seed, scenario, role)", challengeCode(), new Platform.TextAnswer() {
                    public void answer(String text) {
                        if (applyCode(text)) toast("Challenge loaded");
                        else toast("That code doesn't look right");
                    }
                });
                return;
            case "players": back = S_PAUSE; screen = S_PLAYERS; return;
            case "shareresult":
                if (pf.pref("playerName", null) == null) {
                    ask("Your name (shown to friends with your result)", "", new Platform.TextAnswer() {
                        public void answer(String text) {
                            String n = text.trim().replace(",", " ").replace(";", " ").replace("~", "-");
                            pf.setPref("playerName", n.length() == 0 ? "Player" : n.length() > 16 ? n.substring(0, 16) : n);
                            ask("Your result: send this code to a friend", resultCode(), null);
                        }
                    });
                } else ask("Your result: send this code to a friend", resultCode(), null);
                return;
            case "addresult":
                ask("A friend's result code", "", new Platform.TextAnswer() {
                    public void answer(String text) {
                        toast(addResult(text) ? "Result added" : "That code doesn't look right");
                    }
                });
                return;
            case "handover":
                paused = true;
                handTo = (w.roles.current + 1) % w.roles.players;
                screen = S_HANDOFF;
                return;
            case "takeover": {
                Actions.switchPlayer(w, handTo);
                float[] p = w.roles.focusOf(handTo);
                cam.follow = w.roles.current == 0 && w.playerEnt >= 0 ? w.playerEnt : -1;
                cam.flyTo(p[0], p[1], p[2]);
                inspSquad = null;
                inspFormation = null;
                closeInspector();
                tool = w.roles.role == Roles.GOD ? "look" : "orders";
                screen = S_PLAY;
                paused = false;
                return;
            }
            case "pl:back":
                // not yet: the game stays paused, in the pause menu, until someone takes over or resumes
                paused = true;
                screen = S_PAUSE;
                toast("Paused: Player " + (handTo + 1) + " hasn't taken over");
                return;
            case "errmenu": clearError(); return;
            case "sharecode":
                ask("This game's challenge code: share it", challengeCodeOf(w), new Platform.TextAnswer() {
                    public void answer(String text) {
                    }
                });
                return;
            case "savereplay": dir.saveReplay(); return;
            case "timelapse": back = screen; screen = S_TIMELAPSE; dir.openTimelapse(); return;
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
            case "speed": speedIdx = speedIdx >= maxSpeedIdx() ? 0 : speedIdx + 1; paused = false; achieved = 0; return;
            case "report": back = screen == S_PAUSE ? S_PAUSE : S_PLAY; screen = S_REPORT; scroll = 0; recordBest(); return;
            case "radio": back = S_PAUSE; screen = S_RADIO; scroll = 0; return;
            case "shot": screenshot(); return;
            case "hideui": hideUi = true; screen = S_PLAY; return;
            case "showui": hideUi = false; return;
            case "tomenu": autosave(); screen = S_MENU; return;
            case "watch": recordBest(); paused = false; ended = true; Actions.watch(w); return;
            case "minimap": {
                float k = w.r.px() / mmS;
                cam.follow = -1;
                cam.flyTo((upX - mmX) * k, (upY - mmY) * k, cam.zoom);
                return;
            }
            case "curfew": Actions.curfew(w, !w.gov.curfew); return;
            case "n:alert":
                if (w.gov.level >= Gov.LEVELS.length - 1) toast("Already at " + Gov.LEVELS[w.gov.level]);
                else Actions.level(w, w.gov.level + 1);
                return;
            case "n:martial": if (w.gov.level < 3) Actions.level(w, 3); else toast("Martial law is already in force"); return;
            case "n:mobilise": if (w.gov.level < 4) Actions.level(w, 4); else toast("The nation is already mobilised"); return;
            case "m:jump": mapToggle(); return;
            case "o:release": Actions.release(w, inspSquad); inspSquad = null; return;
            case "o:frelease": Actions.releaseFormation(w, inspFormation); inspFormation = null; return;
            case "o:fdone": inspFormation = null; return;
            case "o:home": Actions.home(w, inspSquad); return;
            case "i:quar": Actions.quarantine(w, inspSettle, !w.roles.quarantined(inspSettle)); return;
            case "i:army": Actions.requestArmy(w, inspSettle); return;
            case "i:close": closeInspector(); inspFormation = null; return;
            case "i:fcommand": tool = "orders"; return;
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
                Actions.evac(w, inspSettle, !w.gov.evacuating(inspSettle));
                return;
            case "i:zoom": {
                Region.Settlement s = w.r.settlements.get(inspSettle);
                cam.flyTo(s.cx * 16f, s.cy * 16f, 0.35f);
                closeInspector();
                return;
            }
            default:
        }
        if (dir.onButton(id)) return;
        if (id.startsWith("mode:")) {
            ngMode = Integer.parseInt(id.substring(5));
            ngScenario = 0;
            ngRole = -1;
            scroll = 0;
        } else if (id.startsWith("scen:")) {
            ngScenario = Integer.parseInt(id.substring(5));
            ngRole = -1;
        } else if (id.startsWith("tm:")) townMode = id.substring(3);
        else if (id.startsWith("send:")) sendTemplate = id.substring(5);
        else if (id.startsWith("ov:")) view.overlay = Integer.parseInt(id.substring(3));
        else if (id.startsWith("tool:")) {
            String t = id.substring(5);
            if (t.equals("fire")) {
                Actions.fire(w, !w.strat.playerFire);
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
        else if (id.startsWith("addpl:")) {
            int r = Integer.parseInt(id.substring(6));
            if (Actions.addPlayer(w, r, cam.cx, cam.cy)) toast("Player " + w.roles.players + ": " + Roles.NAMES[r]);
            else toast("Can't add that player");
        } else if (id.startsWith("pl:") && !id.equals("pl:back")) {
            int k = Integer.parseInt(id.substring(3));
            if (k != w.roles.current) {
                handTo = k;
                paused = true;
                screen = S_HANDOFF;
            }
        }
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
            case "gpu":
                s.gpu = !s.gpu;
                if (s.gpu) pf.setPref("gpuFailed", "false"); // let it try again
                toast("Takes effect when the game next starts");
                break;
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
            try {
                SaveIO.save(w, out);
            } finally {
                out.close();
            }
            // replace the old save only once the new one is complete
            if (!pf.rename(name + ".tmp", name + ".sav")) {
                InputStream in = pf.openRead(name + ".tmp");
                OutputStream o2 = pf.openWrite(name + ".sav");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) o2.write(buf, 0, n);
                in.close();
                o2.close();
                pf.delete(name + ".tmp");
            }
            pf.setPref("info." + name, w.r.name + ", " + w.clock() + (w.scenario != null ? ", " + w.scenario.name : "") + ". " + WorldView.fmt(w.totalZombies()) + " of the dead");
            if (!name.equals("autosave")) toast("Saved");
        } catch (Exception e) {
            toast("Couldn't save: " + e.getMessage());
        }
    }

    public void autosave() {
        if (w != null && screen != S_LOADING && error == null && replay == null) saveTo("autosave");
        sinceAutosave = 0;
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
            pointers = countDown();
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
            pointers = countDown();
            if (id == joyPtr) {
                joyPtr = -1;
                if (w != null && replay == null) Actions.joy(w, 0, 0);
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

    private int countDown() {
        int n = 0;
        for (boolean b : down) if (b) n++;
        return n;
    }

    /** The platform saw the first finger go down (or a cancel): forget any pointer whose UP was lost. */
    public void resetTouches() {
        for (int k = 0; k < down.length; k++) down[k] = false;
        pointers = 0;
        pinched = false;
        ui.pressed = null;
        if (joyPtr >= 0) {
            joyPtr = -1;
            if (w != null && replay == null && screen == S_PLAY) Actions.joy(w, 0, 0);
        }
    }

    /** For tests: how many fingers the game thinks are down. */
    int pointersDown() {
        return pointers;
    }

    /** The fastest speed the player may pick: no fast-forward while playing a person. */
    int maxSpeedIdx() {
        if (w != null && w.playerEnt >= 0 && replay == null) {
            int k = 0;
            while (k + 1 < SPEEDS.length && SPEEDS[k + 1] < 60) k++;
            return k;
        }
        return SPEEDS.length - 1;
    }

    /** The speed button's label: the speed chosen, or what the device really reaches when it falls short. */
    String speedLabel() {
        String name = SPEED_NAMES[speedIdx];
        int want = SPEEDS[speedIdx];
        if (paused || want < 60 || achieved <= 0 || achieved >= want * 0.8f) return name;
        float h = achieved / 60f;
        return "~" + (h >= 10 ? "" + Math.round(h) : String.format("%.1f", h)) + "h/s";
    }

    private static String compass(float dx, float dy) {
        String[] names = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        double a = Math.atan2(dy, dx);
        int k = (int) Math.round(a / (Math.PI / 4));
        return names[((k % 8) + 8) % 8];
    }

    private boolean isScrollScreen() {
        return screen == S_REPORT || screen == S_RADIO || screen == S_HELP || screen == S_NOTES || screen == S_NEW || screen == S_REPLAYS;
    }

    private void joy(float x, float y) {
        float r = ui.dp(54);
        float dx = (x - joyCX) / r, dy = (y - joyCY) / r;
        float l = (float) Math.sqrt(dx * dx + dy * dy);
        if (l > 1) {
            dx /= l;
            dy /= l;
        }
        if (replay == null) Actions.joy(w, dx, dy);
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
        if (error != null) {
            if (content == null) return false;
            clearError();
            return true;
        }
        switch (screen) {
            case S_PLAY:
                if (inspectorOpen()) closeInspector();
                else screen = S_PAUSE;
                return true;
            case S_PAUSE: screen = S_PLAY; return true;
            case S_MENU: return false;
            case S_LOADING: cancelLoading(); return true;
            case S_HANDOFF: onButton("pl:back"); return true;
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
            Actions.erase(w, x, y, brushRadius());
            return;
        }
        if (brush >= 2000) {
            if (!w.r.walkablePx(x, y)) {
                toast("The dead can't stand there");
                return;
            }
            if (cam.zoom < 0.3f) Actions.horde(w, x, y, brush);
            else Actions.paint(w, x, y, brush, brushRadius(), zType);
            toast("A horde of " + fmt(brush));
            return;
        }
        int made = Actions.paint(w, x, y, brush, brushRadius(), zType);
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
                    int v = Actions.vehicle(w, placeVehicle, x, y);
                    toast(v >= 0 ? w.c.vtype(placeVehicle).name + " placed" : "No road near there");
                } else {
                    Content.Unit u = w.c.unit(placeUnit);
                    int n = placeUnit.equals("sniper") || placeUnit.equals("marksman") ? 2 : placeUnit.equals("swat") || placeUnit.equals("riot") || placeUnit.equals("guard") || placeUnit.equals("rifleman") ? 8 : 4;
                    Squads.Squad s = Actions.squad(w, placeUnit, n, x, y);
                    toast(s != null ? n + " " + u.name.toLowerCase() + (n > 1 ? "s" : "") + " placed" : "They can't stand there");
                }
                return;
            }
            case "strike": {
                if (strikeMode.equals("bridge")) {
                    int e = Tools.bridgeAt(w, x, y);
                    if (e < 0) toast("Tap a bridge on a road");
                    else Actions.bridge(w, e);
                } else if (strikeMode.equals("art")) {
                    if (!Actions.artillery(w, x, y)) toast("No guns in range (they need a battery holding within reach)");
                } else if (!Actions.strike(w, x, y)) toast("No air strikes left");
                return;
            }
            case "send": {
                if (w.roles.target < 0) return;
                if (!w.r.walkablePx(x, y)) {
                    toast("They can't go there");
                    return;
                }
                Squads.Squad s = Actions.deploy(w, w.roles.target, sendTemplate, x, y);
                if (s == null) toast("Nobody left at the station for that");
                else {
                    inspSquad = s;
                    toast(s.label + " on the way");
                }
                return;
            }
            case "orders": {
                if (w.roles.commandsFormations()) {
                    Forces.Formation fm = w.forces.formationAt(x, y, Math.max(60, ui.dp(24) / cam.zoom));
                    if (fm != null && fm != inspFormation && cam.zoom < 0.6f) {
                        inspFormation = fm;
                        inspSquad = null;
                        return;
                    }
                    if (inspFormation != null && inspFormation.alive) {
                        if (Actions.formation(w, inspFormation, x, y)) toast(inspFormation.name + " moving");
                        else toast("No road there");
                        return;
                    }
                }
                Squads.Squad s = Tools.squadAt(w, x, y, Math.max(40, ui.dp(22) / cam.zoom));
                if (s != null && s != inspSquad) {
                    if (!w.roles.canOrder(s)) {
                        toast(s.name + " isn't yours to command");
                        return;
                    }
                    inspSquad = s;
                    return;
                }
                if (inspSquad == null && w.roles.role == Roles.SQUAD && w.roles.target >= 0 && w.roles.target < w.sq.list.size())
                    inspSquad = w.sq.get(w.roles.target);
                if (inspSquad != null && inspSquad.alive) {
                    if (!w.roles.canOrder(inspSquad)) {
                        toast(inspSquad.name + " isn't yours to command");
                        inspSquad = null;
                        return;
                    }
                    if (!w.r.walkablePx(x, y)) {
                        toast("They can't go there");
                        return;
                    }
                    Actions.order(w, inspSquad, x, y);
                    return;
                }
                toast(w.roles.commandsFormations() ? "Tap a formation or a squad first" : "Tap one of your squads first");
                return;
            }
            case "town": {
                int st = Tools.settlementAt(w, x, y);
                if (st < 0) {
                    toast("Tap a town or a city");
                    return;
                }
                Region.Settlement s = w.r.settlements.get(st);
                boolean town = s.kind == Region.S_CITY || s.kind == Region.S_TOWN;
                if (w.roles.role == Roles.INCIDENT && st != w.roles.target && !townMode.equals("info")) {
                    toast("Only " + w.r.settlements.get(w.roles.target).name + " is yours");
                    return;
                }
                if (townMode.equals("evac") && town) Actions.evac(w, st, !w.gov.evacuating(st));
                else if (townMode.equals("quar") && town) Actions.quarantine(w, st, !w.roles.quarantined(st));
                else if (townMode.equals("army") && town) Actions.requestArmy(w, st);
                else {
                    closeInspector();
                    inspSettle = st;
                }
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
        Logistics.Convoy cv = w.logistics.convoyAt(x, y, Math.max(ui.dp(16) / cam.zoom, 30));
        if (cv != null) {
            toast(w.logistics.describe(cv));
            return;
        }
        for (Region.Facility f : w.r.facilities) {
            if (f.kind == Region.F_HELIPAD || f.kind == Region.F_PARK) continue;
            if (dist(cam.toScreenX(f.x), cam.toScreenY(f.y), sx, sy) < ui.dp(16)) {
                inspFac = f.id;
                return;
            }
        }
        if (cam.zoom < 0.6f) {
            Forces.Formation fm = w.forces.formationAt(x, y, ui.dp(18) / cam.zoom);
            if (fm != null) {
                inspFormation = fm;
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
        else if (code >= '1' && code <= '8') speedIdx = Math.min(code - '1', maxSpeedIdx());
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
