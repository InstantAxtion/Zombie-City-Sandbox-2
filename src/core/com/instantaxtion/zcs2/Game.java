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
    /** What's heard (and the captions, vibration and screen shake that go with it): reads the world, never changes it. */
    final AudioDirector audio;
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
    /** A real-scale map's preview: its coarse layer (no tiles are made for the preview). */
    private volatile Nation previewNation;
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
    private float fps, frameAcc;
    private int frames;
    private boolean ended;
    private float prevZoom = 0.6f, prevX, prevY;

    public Game(Platform pf) {
        this.pf = pf;
        settings.load(pf);
        audio = new AudioDirector(pf);
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
        audio.applySettings(settings);
        WorldView.palette(settings.colourBlind);
        statsOpen = Boolean.parseBoolean(pf.pref("statsOpen", "false"));
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

    /** The save the error screen is about when it's from an older version (offered for deleting), else null. */
    private String tooOld;

    private static String saveTitle(String file) {
        String base = file == null ? "" : file.endsWith(".sav") ? file.substring(0, file.length() - 4) : file;
        return base.equals("autosave") ? "\"Autosave\"" : base.startsWith("slot") ? "in slot " + base.substring(4) : "\"" + base + "\"";
    }

    /** The error screen came from a crash in a running game (the world may not be trustworthy). */
    private boolean crashedWorld;

    /** Leaves the error screen for the menu. */
    private void clearError() {
        error = null;
        tooOld = null;
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
        g.text(content == null ? "The game can't start" : tooOld != null ? "An older save" : "Something went wrong", m, ui.dp(48), ui.dp(18), Gfx.ALIGN_LEFT, true);
        ui.wrap(error, m, ui.dp(80), W - m * 2, ui.dp(14), Ui.TEXT, 20);
        float bw = Math.min(W - m * 2, ui.dp(320)), bh = ui.dp(50);
        if (content == null) ui.bigButton("quit", (W - bw) / 2, H - bh - ui.dp(40), bw, bh, "Quit", Ui.ACCENT);
        else if (tooOld != null) {
            ui.bigButton("olddelete", (W - bw) / 2, H - bh * 2 - ui.dp(52), bw, bh, "Delete it", Ui.ACCENT);
            ui.bigButton("errmenu", (W - bw) / 2, H - bh - ui.dp(40), bw, bh, "Keep it", Ui.LINE);
        }
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
        if (screen != S_PLAY && screen != S_PAUSE && screen != S_PLAYERS) audio.idle(dt);
        if (msg != null && msgT > 0) {
            msgT -= dt;
            toastBox(g);
        }
    }

    private final java.util.List<String> toastLines = new java.util.ArrayList<String>();

    private float toastX, toastY, toastW, toastH, toastL, toastR;

    /** Lays the toast out: wrapped to at most three lines, centred inside the part of the screen the bars leave free. */
    private void toastLayout(Gfx g) {
        boolean onMap = screen == S_PLAY && w != null && !hideUi;
        float l = onMap ? freeL : 0, r = onMap ? freeR : g.width(), t = onMap ? freeT : 0, b = onMap ? freeB : g.height();
        float ts = ui.dp(13.5f), maxW = Math.min(r - l - ui.dp(32), ui.dp(420));
        toastLines.clear();
        StringBuilder line = new StringBuilder();
        for (String word : msg.split(" ")) {
            String tryLine = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && g.textWidth(tryLine, ts, true) > maxW) {
                toastLines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(tryLine);
            }
        }
        if (line.length() > 0) toastLines.add(line.toString());
        while (toastLines.size() > 3) toastLines.remove(toastLines.size() - 1);
        float tw = 0;
        for (int k = 0; k < toastLines.size(); k++) {
            toastLines.set(k, Ui.fit(g, toastLines.get(k), maxW, ts, true));
            tw = Math.max(tw, g.textWidth(toastLines.get(k), ts, true));
        }
        float lh = ts * 1.35f, th = toastLines.size() * lh + ui.dp(16);
        tw += ui.dp(28);
        toastL = l;
        toastR = r;
        toastW = tw;
        toastH = th;
        toastX = (l + r - tw) / 2;
        toastY = Math.max(t + ui.dp(8), Math.min(b - th - ui.dp(8), t + (b - t) * 0.38f));
    }

    private void toastBox(Gfx g) {
        toastLayout(g);
        float ts = ui.dp(13.5f), lh = ts * 1.35f;
        g.color(0xE6000000);
        g.fillRoundRect(toastX, toastY, toastW, toastH, ui.dp(10));
        g.color(0xFFFFFFFF);
        for (int k = 0; k < toastLines.size(); k++)
            g.text(toastLines.get(k), (toastL + toastR) / 2, toastY + ui.dp(8) + lh * k + ts * 1.0f, ts, Gfx.ALIGN_CENTER, true);
    }

    void toast(String s) {
        msg = s;
        // long enough to read
        msgT = 2.2f + Math.min(4, s.length() / 30f);
    }

    // ------------------------------------------------------------------ main menu

    private float menuT, keptX, keptY, keptZ;
    private boolean menuCam;

    private void menu(Gfx g) {
        int W = g.width(), H = g.height();
        menuT += 0.016f;
        g.color(Ui.SCREEN);
        g.fillRect(0, 0, W, H);
        // streets, faintly, until a map is ready
        g.color(0xFF161B22);
        for (int i = 0; i < 24; i++) {
            g.fillRect(0, i * H / 24f, W, ui.dp(2));
            g.fillRect(i * W / 12f, 0, ui.dp(2), H);
        }
        // behind it all, a map drifting slowly by: the game's own, or the next one's
        if (w != null && view != null) {
            if (!menuCam) {
                // keep where the player was looking, for when they go back
                menuCam = true;
                keptX = cam.cx;
                keptY = cam.cy;
                keptZ = cam.zoom;
            }
            cam.setInsets(0, 0, 0, 0);
            cam.setScreen(W, H, w.r.px());
            cam.dp = ui.d;
            float z = Math.max(cam.minZoom * 2.2f, 0.035f);
            float rad = w.r.px() * 0.18f;
            cam.zoom = z;
            cam.cx = w.r.city().cx * 16f + (float) Math.cos(menuT * 0.05f) * rad;
            cam.cy = w.r.city().cy * 16f + (float) Math.sin(menuT * 0.04f) * rad;
            boolean nm = view.names;
            view.names = false;
            view.noLabels = true;
            view.draw(g, cam, 0.016f);
            view.noLabels = false;
            view.names = nm;
        } else {
            requestPreview();
            if (previewRegion() != null) {
                float ps = Math.max(W, H) * 1.9f;
                float px = -(ps - W) / 2 + (float) Math.sin(menuT * 0.05f) * (ps - W) * 0.4f;
                float py = -(ps - H) / 2 + (float) Math.cos(menuT * 0.037f) * (ps - H) * 0.4f;
                drawPreview(g, px, py, ps, false);
            }
        }
        // darken towards the bottom where the buttons are, and a little at the top behind the title
        g.color(0xB00A0D12);
        g.fillRect(0, 0, W, H);
        boolean land = W > H * 1.15f;
        float cx = land ? W * 0.3f : W / 2f;
        float y = land ? H * 0.38f : H * 0.2f;
        g.color(0xFFE0453A);
        g.textHalo("ZOMBIE CITY", cx, y, ui.dp(38), Gfx.ALIGN_CENTER, true, 0xC0000000, ui.dp(2));
        g.color(0xFFFFFFFF);
        g.textHalo("SANDBOX 2", cx, y + ui.dp(40), ui.dp(28), Gfx.ALIGN_CENTER, true, 0xC0000000, ui.dp(2));
        g.color(0xFFC8D0DA);
        g.textHalo("The outbreak goes national", cx, y + ui.dp(68), ui.dp(Ui.L), Gfx.ALIGN_CENTER, false, 0xC0000000, ui.dp(1.5f));
        // the buttons: in the bottom third (or the right half, sideways)
        float bw = Math.min((land ? W * 0.42f : W) - ui.dp(48), ui.dp(340)), bh = ui.dp(52), gap = ui.dp(10);
        float bx = land ? W * 0.75f - bw / 2 : W / 2f - bw / 2;
        boolean two = w != null || pf.exists("autosave.sav");
        float smallH = ui.dp(44);
        float total = (two ? bh + gap : 0) + bh + gap + (smallH + gap) * 3;
        y = land ? (H - total) / 2 : H - total - ui.dp(36);
        if (w != null) {
            ui.bigButton("resume", bx, y, bw, bh, "Back to the game", Ui.ON);
            y += bh + gap;
        } else if (pf.exists("autosave.sav")) {
            ui.bigButton("continue", bx, y, bw, bh, "Continue", Ui.ON);
            y += bh + gap;
        }
        ui.bigButton("new", bx, y, bw, bh, "New game", Ui.ACCENT);
        y += bh + gap;
        float half = (bw - gap) / 2;
        String[][] small = {{"load", "Load game"}, {"replays", "Replays"}, {"settings", "Settings"}, {"help", "How to play"}, {"notes", "What's new"}, {"quit", "Quit"}};
        for (int k = 0; k < small.length; k++)
            ui.button(small[k][0], bx + (k % 2) * (half + gap), y + (k / 2) * (smallH + gap), half, smallH, small[k][1], false, 13);
        g.color(0x90FFFFFF);
        g.text("Version " + Texts.VERSION, W / 2f, H - ui.dp(14), ui.dp(Ui.S), Gfx.ALIGN_CENTER, false);
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
    /** Set by applyCode when the code loaded with a caveat (shown instead of "Challenge loaded"). */
    String codeNote;

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
            codeNote = null;
            if (p.length > 4) {
                boolean found = false;
                for (int tab = 0; tab < TABS.length; tab++) {
                    List<Content.Scenario> l = scenariosFor(tab);
                    for (int i = 0; i < l.size(); i++)
                        if (l.get(i).id.equals(p[4])) {
                            ngMode = tab;
                            ngScenario = i;
                            found = true;
                        }
                }
                // a scenario made in someone else's editor isn't on this phone: say so, don't quietly swap it
                if (!found) codeNote = "This challenge uses a scenario made on another phone (" + p[4] + "). The map is loaded; pick a scenario, or ask for theirs.";
            }
            ngRole = -1;
            if (p.length > 5) {
                int role = Roles.byId(p[5]);
                if (role < 0) {
                    // a role this version doesn't know: say so, and play the scenario's own
                    codeNote = (codeNote != null ? codeNote + " " : "") + "The role \"" + p[5] + "\" isn't one this game knows: you'll play as "
                            + Roles.NAMES[chosenRole()] + ".";
                } else {
                    boolean offered = false;
                    for (int r : rolesFor(chosenScenario())) if (r == role) offered = true;
                    if (offered) ngRole = role;
                    else codeNote = (codeNote != null ? codeNote + " " : "") + "This scenario can't be played as " + Roles.NAMES[role]
                            + ": you'll play as " + Roles.NAMES[chosenRole()] + ".";
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The role list is open over the New Game screen. */
    boolean roleSheet;

    private static String roleIcon(int r) {
        switch (r) {
            case Roles.GOD: return "dead";
            case Roles.PRESIDENT: return "town";
            case Roles.MILITARY: return "army";
            case Roles.INCIDENT: return "orders";
            case Roles.PRECINCT: return "police";
            case Roles.SQUAD: return "send";
            case Roles.OFFICER: return "police";
            default: return "people";
        }
    }

    private void newGame(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(Ui.SCREEN);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(14), y;
        boolean land = W > H * 1.15f;
        // portrait: one column. Sideways: the list, then the choices, then the map
        float colW = land ? W * 0.38f - m * 1.5f : W - m * 2;
        float rightX = land ? W * 0.38f + m * 0.5f : m;
        float rightW = land ? W * 0.31f - m : W - m * 2;
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(36), "Back", false, 13);
        ui.title("New game", m + ui.dp(84), ui.dp(35));
        y = ui.dp(56);
        float cw = (colW - ui.dp(6) * (TABS.length - 1)) / TABS.length;
        for (int i = 0; i < TABS.length; i++) ui.button("mode:" + i, m + i * (cw + ui.dp(6)), y, cw, ui.dp(38), TABS[i], ngMode == i, 13);
        y += ui.dp(46);
        List<Content.Scenario> list = scenariosFor(ngMode);
        if (ngScenario >= list.size()) ngScenario = 0;
        // the list: a fixed area (so nothing below jumps between tabs), cards as tall as their text, scrolling
        float listH = land ? H - y - m : Math.max(ui.dp(150), H * 0.3f);
        float total = 0;
        for (Content.Scenario s : list) total += ui.cardH(s.desc, colW) + ui.dp(6);
        maxScroll = Math.max(0, total - listH);
        float off = Math.min(scroll, maxScroll);
        g.save();
        g.clip(0, y, land ? m + colW + ui.dp(8) : W, listH);
        float cy = y - off;
        for (int i = 0; i < list.size(); i++) {
            Content.Scenario s = list.get(i);
            float ch = ui.cardH(s.desc, colW);
            if (cy + ch >= y && cy <= y + listH) ui.card("scen:" + i, m, cy, colW, ch, s.name, s.desc, i == ngScenario);
            cy += ch + ui.dp(6);
        }
        g.restore();
        // a card half scrolled out of the list mustn't take taps outside it: the tabs above go on top again, and
        // below the list nothing of it can be tapped
        for (int i = 0; i < TABS.length; i++) ui.button("mode:" + i, m + i * (cw + ui.dp(6)), y - ui.dp(46), cw, ui.dp(38), TABS[i], ngMode == i, 13);
        ui.block(0, y + listH, land ? m + colW + ui.dp(8) : W, H - y - listH);
        if (list.isEmpty()) {
            g.color(Ui.DIM);
            g.text("Nothing here yet: make one with \"Make your own\".", m + colW / 2, y + ui.dp(30), ui.dp(Ui.M), Gfx.ALIGN_CENTER, false);
        }
        if (maxScroll > 0) {
            // a scroll bar
            float th = listH * listH / (listH + maxScroll);
            g.color(0x60FFFFFF);
            g.fillRoundRect(m + colW + ui.dp(3), y + (listH - th) * off / maxScroll, ui.dp(3), th, ui.dp(1.5f));
        }
        float ry = land ? ui.dp(10) : y + listH + ui.dp(10);
        // who you are: a row that opens the list of roles
        Content.Scenario sc = chosenScenario();
        int role = chosenRole();
        float rh = ui.dp(48);
        ui.button("rolesheet", rightX, ry, rightW, rh, "", false);
        g.color(0xFF2A3644);
        g.fillCircle(rightX + ui.dp(24), ry + rh / 2, ui.dp(15));
        Icons.draw(g, roleIcon(role), rightX + ui.dp(24), ry + rh / 2, ui.dp(16), Ui.TEXT);
        g.color(Ui.DIM);
        g.text("Play as", rightX + ui.dp(48), ry + ui.dp(18), ui.dp(Ui.S), Gfx.ALIGN_LEFT, false);
        g.color(Ui.TEXT);
        g.text(Ui.fit(g, Roles.NAMES[role], rightW - ui.dp(110), ui.dp(Ui.L), true), rightX + ui.dp(48), ry + ui.dp(37), ui.dp(Ui.L), Gfx.ALIGN_LEFT, true);
        g.color(0xFF8FB8FF);
        g.text("Change ▸", rightX + rightW - ui.dp(12), ry + rh / 2 + ui.dp(4), ui.dp(Ui.M), Gfx.ALIGN_RIGHT, true);
        ry += rh;
        float dh = Ui.lines(g, Roles.DESC[role], rightW, ui.dp(12)) * ui.dp(12) * 1.3f;
        ui.wrap(Roles.DESC[role], rightX, ry + ui.dp(16), rightW, ui.dp(12), Ui.DIM, 5);
        ry += Math.min(dh, ui.dp(12) * 1.3f * 5) + ui.dp(12);
        // the map
        float bw = (rightW - ui.dp(16)) / 3;
        ui.button("preset", rightX, ry, bw, ui.dp(40), RegionGen.PRESETS[ngPreset], false, 13);
        ui.button("size", rightX + bw + ui.dp(8), ry, bw, ui.dp(40), RegionGen.SIZE_NAMES[ngSize], false, 13);
        ui.button("seed", rightX + (bw + ui.dp(8)) * 2, ry, bw, ui.dp(40), "New map", false, 13);
        ry += ui.dp(46);
        // the start, and the challenge code tucked above it
        float startH = ui.dp(52);
        float startY = H - startH - ui.dp(12);
        float codeY = startY - ui.dp(42);
        float half = (rightW - ui.dp(8)) / 2;
        ui.button("code", rightX, codeY, half, ui.dp(34), "Enter a code", false, 12);
        ui.button("editor", rightX + half + ui.dp(8), codeY, half, ui.dp(34), "Make your own", false, 12);
        String best = bestText(challengeCode());
        g.color(best != null ? Ui.GOOD : 0xFF707A86);
        g.text(Ui.fit(g, best != null ? best : "Code " + challengeCode(), rightW, ui.dp(Ui.S), false), rightX, codeY - ui.dp(6), ui.dp(Ui.S), Gfx.ALIGN_LEFT, false);
        ui.bigButton("start", rightX, startY, rightW, startH, sc == null ? "Start" : Ui.fit(g, "Start: " + sc.name, rightW - ui.dp(20), ui.dp(17), true), Ui.ACCENT);
        // the preview: below the choices, or in its own column sideways
        if (land) {
            float px = rightX + rightW + m, pw = W - px - m;
            float ps = Math.min(pw, H - ui.dp(20));
            drawPreview(g, px + (pw - ps) / 2, (H - ps) / 2, ps);
        } else {
            float avail = codeY - ry - ui.dp(24);
            float ps = Math.min(rightW, avail);
            if (ps > ui.dp(60)) drawPreview(g, rightX + (rightW - ps) / 2, ry, ps);
        }
        if (roleSheet) roleSheet(g, sc);
    }

    /** The roles as a list over the screen: each with what it can do. */
    private void roleSheet(Gfx g, Content.Scenario sc) {
        int W = g.width(), H = g.height();
        g.color(0xC0000000);
        g.fillRect(0, 0, W, H);
        ui.area("rolesheet:close", 0, 0, W, H);
        int[] rs = rolesFor(sc);
        float m = ui.dp(12), pw = Math.min(W - m * 2, ui.dp(460));
        float tw = pw - ui.dp(70);
        float total = ui.dp(54);
        for (int r : rs) total += ui.dp(30) + Ui.lines(g, Roles.DESC[r], tw, ui.dp(12)) * ui.dp(12) * 1.3f + ui.dp(10);
        float ph = Math.min(H - m * 2, total);
        float x = (W - pw) / 2, y = (H - ph) / 2;
        ui.panel(x, y, pw, ph);
        g.color(Ui.TEXT);
        g.text("Play as", x + ui.dp(16), y + ui.dp(32), ui.dp(Ui.XL - 2), Gfx.ALIGN_LEFT, true);
        ui.button("rolesheet:close", x + pw - ui.dp(44), y + ui.dp(10), ui.dp(34), ui.dp(34), "✕", false, 13);
        float yy = y + ui.dp(50);
        int cur = chosenRole();
        for (int r : rs) {
            float h = ui.dp(30) + Ui.lines(g, Roles.DESC[r], tw, ui.dp(12)) * ui.dp(12) * 1.3f + ui.dp(6);
            if (yy + h > y + ph) break;
            boolean on = r == cur;
            g.color(on ? 0xFF24426E : ("rolepick:" + r).equals(ui.pressed) ? 0xFF2C3746 : 0xFF202832);
            g.fillRoundRect(x + ui.dp(8), yy, pw - ui.dp(16), h, ui.dp(8));
            g.color(0xFF2E3A48);
            g.fillCircle(x + ui.dp(32), yy + ui.dp(22), ui.dp(14));
            Icons.draw(g, roleIcon(r), x + ui.dp(32), yy + ui.dp(22), ui.dp(15), on ? 0xFFFFFFFF : 0xFFC8D0DA);
            g.color(Ui.TEXT);
            g.text(Roles.NAMES[r], x + ui.dp(56), yy + ui.dp(21), ui.dp(Ui.L - 1), Gfx.ALIGN_LEFT, true);
            ui.wrap(Roles.DESC[r], x + ui.dp(56), yy + ui.dp(38), tw, ui.dp(12), Ui.DIM, 6);
            ui.area("rolepick:" + r, x + ui.dp(8), yy, pw - ui.dp(16), h);
            yy += h + ui.dp(4);
        }
    }

    /** The preview of the chosen map in a square; returns false while it's still being drawn. */
    private final float[] prevBox = new float[32 * 4];

    boolean drawPreview(Gfx g, float pxx, float y, float ps) {
        return drawPreview(g, pxx, y, ps, true);
    }

    /** The preview, with or without its names and caption (the menu's backdrop has none). */
    boolean drawPreview(Gfx g, float pxx, float y, float ps, boolean captions) {
        requestPreview();
        if (captions) {
            g.color(0xFF1A2028);
            g.fillRoundRect(pxx, y, ps, ps, ui.dp(6));
        }
        Region pr = preview;
        Nation pn = previewNation;
        if (pr == null && pn == null || previewKey != key()) {
            if (captions) {
                g.color(Ui.DIM);
                g.text(previewFailed == key() ? "Couldn't draw the map: try another seed" : "Drawing the map...", pxx + ps / 2, y + ps / 2, ui.dp(Ui.M), Gfx.ALIGN_CENTER, false);
            }
            return false;
        }
        if (previewImg == null) {
            if (pr != null) {
                int st = pr.W > 1024 ? 4 : 2, iw = pr.W / st;
                previewImg = pf.newImage(iw, iw);
                int[] pix = new int[iw * iw];
                for (int yy = 0; yy < iw; yy++) for (int xx = 0; xx < iw; xx++) pix[yy * iw + xx] = MapPainter.tileColor(pr, xx * st, yy * st, false);
                previewImg.setPixels(pix, iw, iw);
            } else {
                // from the coarse layer only
                int iw = 768;
                previewImg = pf.newImage(iw, iw);
                previewImg.setPixels(NationPainter.preview(pn, iw), iw, iw);
            }
        }
        g.image(previewImg, pxx, y, ps, ps);
        if (!captions) return true;
        java.util.List<Region.Settlement> setts = pr != null ? pr.settlements : pn.settlements;
        String pname = pr != null ? pr.name : pn.name;
        int nstates = pr != null ? pr.states.size() : pn.states.size();
        float k = ps / (pr != null ? pr.W : pn.size);
        // the caption first: the names keep above it
        long people = 0;
        for (Region.Settlement s : setts) people += s.residents;
        // the country's name, and its people and states: on one line if it fits, else on two
        String cap = pname + ": " + WorldView.people(people) + " people, " + nstates + " states";
        String sub = WorldView.people(people) + " people, " + nstates + " states";
        boolean two = g.textWidth(cap, ui.dp(12), false) > ps - ui.dp(12);
        float bh = ui.dp(two ? 40 : 24);

        int placed = 0;
        for (int pass = 0; pass < 3; pass++)
            for (Region.Settlement s : setts) {
                int rank = s.capital ? 0 : s.kind == Region.S_CITY ? 1 : 2;
                if (rank != pass || (rank == 2 && (s.kind != Region.S_TOWN || ps < ui.dp(260)))) continue;
                float size = ui.dp(s.capital ? 13 : s.kind == Region.S_CITY ? 11.5f : 10);
                String name = (s.capital ? "★ " : "") + s.name;
                float tw = g.textWidth(name, size, true), lx = Math.max(pxx + tw / 2 + ui.dp(2), Math.min(pxx + ps - tw / 2 - ui.dp(2), pxx + s.cx * k));
                float ly = Math.min(y + ps - bh - ui.dp(3), Math.max(y + size + ui.dp(2), y + s.cy * k));
                boolean free = true;
                for (int q = 0; q < placed && free; q++)
                    free = lx + tw / 2 < prevBox[q * 4] || lx - tw / 2 > prevBox[q * 4 + 2] || ly < prevBox[q * 4 + 1] || ly - size > prevBox[q * 4 + 3];
                if (!free || placed >= 32) continue;
                prevBox[placed * 4] = lx - tw / 2;
                prevBox[placed * 4 + 1] = ly - size;
                prevBox[placed * 4 + 2] = lx + tw / 2;
                prevBox[placed * 4 + 3] = ly;
                placed++;
                g.color(0xFFFFFFFF);
                g.textHalo(name, lx, ly, size, Gfx.ALIGN_CENTER, true, 0xD0000000, ui.dp(1.3f));
            }
        g.color(0xD0000000);
        g.fillRect(pxx, y + ps - bh, ps, bh);
        g.color(0xFFFFFFFF);
        if (two) {
            g.text(Ui.fit(g, pname, ps - ui.dp(12), ui.dp(12), true), pxx + ui.dp(6), y + ps - ui.dp(24), ui.dp(12), Gfx.ALIGN_LEFT, true);
            g.text(Ui.fit(g, sub, ps - ui.dp(12), ui.dp(12), false), pxx + ui.dp(6), y + ps - ui.dp(8), ui.dp(12), Gfx.ALIGN_LEFT, false);
        } else g.text(cap, pxx + ui.dp(6), y + ps - ui.dp(8), ui.dp(12), Gfx.ALIGN_LEFT, false);
        return true;
    }

    Region previewRegion() {
        return previewKey == key() ? preview : null;
    }

    long key() {
        return ngSeed * 31 + ngPreset * 7 + ngSize;
    }

    /** A real-scale map size (built by NationGen). */
    static boolean national(int size) {
        for (int s : Nation.SIZES) if (s == size) return true;
        return false;
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
                    if (national(size)) {
                        // a real-scale map: the preview is its coarse layer
                        Nation n = NationGen.generate(size, preset, seed, settings.simThreads());
                        preview = null;
                        previewNation = n;
                    } else {
                        Region r = RegionGen.generate(size, preset, seed);
                        previewNation = null;
                        preview = r;
                    }
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
        waitForSave();
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
                        if (gen == loadGen) {
                            if (t instanceof SaveIO.TooOld) {
                                // not damaged, just too old: the player decides whether to keep it
                                tooOld = name;
                                loadError = t;
                            } else quarantine(name, t);
                        }
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
            if (le instanceof SaveIO.TooOld) error = "The save " + saveTitle(tooOld) + " was made with an older version of the game, which this one can't read. "
                    + "Delete it to start afresh, or keep it (an older version of the game can still play it).";
            else error = le instanceof java.io.IOException && le.getMessage() != null && le.getMessage().startsWith("Couldn't read the save")
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
            int ro = w.roles.role;
            if (w.tick < 5 && replay == null && ro != Roles.GOD && ro != Roles.PRESIDENT && ro != Roles.MILITARY) {
                // a new game starts on the player's own piece (a city, a station, a squad, the person); the way to
                // the outbreak is a button while it's out of view
                float[] p = w.roles.focusOf(w.roles.current);
                cam.cx = p[0];
                cam.cy = p[1];
                cam.zoom = Math.max(p[2], ro == Roles.INCIDENT ? 0.25f : 0.5f);
                if (w.playerEnt >= 0) cam.zoom = w.mode == World.MODE_SURVIVOR ? 1.4f : 1.0f;
            }
            prevX = cam.cx;
            prevY = cam.cy;
            prevZoom = cam.zoom;
            if (w.playerEnt >= 0) cam.follow = w.playerEnt;
            screen = S_PLAY;
            paused = false;
            speedIdx = 0;
            tool = replay != null ? "look" : defaultTool();
            ended = w.ended;
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
            view.unitNames = settings.unitNames;
            view.effects = settings.effects;
            view.map.realistic = settings.realistic;
        }
        if (w != null && replay == null) Actions.maxIndividuals(w, settings.maxIndividuals);
        audio.applySettings(settings);
        WorldView.palette(settings.colourBlind);
    }

    private void closeWorld() {
        menuCam = false;
        if (w != null) w.shutdown();
        if (view != null) view.map.clear();
        w = null;
        view = null;
        replay = null;
    }

    // ------------------------------------------------------------------ playing

    private void play(Gfx g, float dt) {
        int W = g.width(), H = g.height();
        cam.dp = ui.d;
        if (hideUi) {
            cam.setInsets(0, 0, 0, 0);
            freeL = freeT = 0;
            freeR = W;
            freeB = H;
        } else layout(W, H);
        if (landOf != w.r) {
            // where the land is, for framing the whole country on a tall screen
            landOf = w.r;
            int n = 64;
            boolean[] cells = new boolean[n * n];
            for (int cy = 0; cy < n; cy++)
                for (int cx = 0; cx < n; cx++) {
                    int land = 0, all = 0;
                    for (int k = 0; k < 16; k++) {
                        int tx = (int) ((cx + (k % 4 + 0.5f) / 4f) * w.r.W / n), ty = (int) ((cy + (k / 4 + 0.5f) / 4f) * w.r.H / n);
                        int t = w.r.t(tx, ty);
                        all++;
                        if (t != Tile.WATER && t != Tile.DEEP) land++;
                    }
                    cells[cy * n + cx] = land * 2 >= all;
                }
            cam.setLand(cells, n);
        }
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
        if (alertOf != w) takeAlerts();
        // step the simulation, within a time budget
        if (!paused && screen == S_PLAY && dt > 0) {
            if (replay != null && replay.done()) {
                replay = null;
                w.director.replaying = false;
            }
            if (skipping && (w.playerEnt < 0 || w.ended || replay != null || deadNear(SKIP_SAFE * 0.8f))) {
                skipping = false;
                if (w.playerEnt >= 0 && !w.ended) toast("The dead are close: back to 1x");
                speedIdx = 0;
            }
            // skipping ahead: everyone still in person, as fast as the device can step (what can't be done is dropped)
            int speed = skipping ? 3000 : SPEEDS[speedIdx];
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
                if (w.stats.rec.alertSeq != alertSeen && takeAlerts()) {
                    // something the player asked to be stopped for: the rest of this frame's time is dropped
                    acc = 0;
                    break;
                }
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
        if (w.ended && !ended) {
            ended = true;
            paused = true;
            recordBest();
        }
        if (!bestNoted && w.roles.containedAt >= 0) {
            bestNoted = true;
            recordBest();
        }
        audio.update(w, cam, settings, dt, paused || screen != S_PLAY, skipping ? 3000 : SPEEDS[speedIdx]);
        view.selected = inspSquad;
        view.selectedFormation = inspFormation;
        view.inspectEnt = inspEnt;
        view.inspectVehicle = inspVeh;
        if (!hideUi) {
            view.labelArea(freeL, freeT, freeR, freeB);
            for (int k = 0; k < hudKeepN; k++) view.keepOut(hudKeep[k * 4], hudKeep[k * 4 + 1], hudKeep[k * 4 + 2], hudKeep[k * 4 + 3]);
            // the notice in the middle of the map: no label under it
            if (msg != null && msgT > 0) {
                toastLayout(g);
                view.keepOut(toastX - ui.dp(4), toastY - ui.dp(4), toastX + toastW + ui.dp(4), toastY + toastH + ui.dp(4));
            }
        }
        hudKeepN = 0;
        // screen shake: only the picture moves (the camera the simulation sees is set above)
        float shX = settings.shake ? audio.shakeX * ui.d / cam.zoom : 0, shY = settings.shake ? audio.shakeY * ui.d / cam.zoom : 0;
        cam.cx += shX;
        cam.cy += shY;
        view.draw(g, cam, dt);
        cam.cx -= shX;
        cam.cy -= shY;
        if (!hideUi) {
            hud(g);
            audio.drawCaptions(g, ui.d, (freeL + freeR) / 2, freeB - ui.dp(40)); // above the radio line
        } else {
            ui.button("showui", W - ui.dp(52), ui.dp(10), ui.dp(44), ui.dp(34), "UI", false, 12);
        }
        if (w.ended && screen == S_PLAY && !hideUi) endPanel(g);
    }

    private boolean bestNoted;

    private String fmt(int n) {
        return WorldView.fmt(n);
    }

    // ------------------------------------------------------------------ the play screen's layout

    /** Is the screen wider than tall (the side layout: a tool rail on the left, sheets on the right)? */
    boolean landscape;
    /** The map area left free by the bars and panels, in pixels. */
    float freeL, freeT, freeR, freeB;
    private float topH, railW, paletteW, sheetW;
    private Region landOf;

    /** Works out where the bars go this frame and tells the camera what's covered. */
    private void layout(int W, int H) {
        landscape = W > H * 1.15f;
        topH = ui.dp(landscape ? 46 : 70) + (goalShown() ? ui.dp(GOAL_H) : 0);
        float bottom = 0, left = 0, right = 0;
        boolean pal = palette() != null && !inspectorOpen();
        if (landscape) {
            railW = ui.dp(60);
            paletteW = pal ? Math.min(ui.dp(210), W * 0.27f) : 0;
            sheetW = inspectorOpen() ? Math.min(ui.dp(340), W * 0.42f) : 0;
            left = railW + paletteW;
            right = sheetW;
        } else {
            railW = paletteW = sheetW = 0;
            toolbarH = ui.dp(60);
            bottom = toolbarH + (inspectorOpen() ? inspectorH : pal ? paletteFixedH() : 0);
        }
        freeL = left;
        freeT = topH;
        freeR = W - right;
        freeB = H - bottom;
        cam.setInsets(left, topH, right, bottom);
    }

    /** The palette's height: a caption and its rows (the same for a tool whichever option is picked). */
    private float paletteFixedH() {
        String[][] p = palette();
        int rows = 0;
        if (p != null) for (String[] r : p) if (r.length > 0) rows++;
        return ui.dp(rows == 0 ? 30 : 22) + ui.dp(46) * rows + ui.dp(rows == 0 ? 0 : 8);
    }

    // ------------------------------------------------------------------ the HUD

    private String hudKey;
    private String hudLine;
    private long headlineSeen = -1;
    private News.Item headlineItem;
    private float rippleX, rippleY, rippleT = 9;
    private float pingX, pingY, pingT = 9;
    private float detailCueT;
    private boolean wasDetail = true;

    /** A clock reading for a game time (game minutes since the start): "Day 2  14:05". One formatter for every screen. */
    static String clockAt(double minutes) {
        double h = World.START_HOUR + minutes / 60.0;
        int day = 1 + (int) (h / 24.0);
        int hh = (int) (h % 24.0), mm = (int) (minutes % 60.0);
        if (mm < 0) mm += 60;
        return "Day " + day + "  " + (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm;
    }

    private void hud(Gfx g) {
        int W = g.width(), H = g.height();
        float m = ui.dp(8);
        // what happens on the map under the bars first: the tap ripple, an order's flag
        rippleT += 0.016f;
        pingT += 0.016f;
        if (rippleT < 0.45f) {
            float k = rippleT / 0.45f;
            g.color(MapPainter.alpha(0xFFFFFFFF, (1 - k) * 0.8f));
            g.strokeCircle(rippleX, rippleY, ui.dp(8 + 26 * k), ui.dp(2));
        }
        if (pingT < 2.5f) view.flag(g, cam.toScreenX(pingX), cam.toScreenY(pingY), 0xFFFFE040);
        alertArrows(g);
        boolean goLive = outbreakMarker(g);
        topBar(g, W);
        float y = topH;
        // the latest headline: one line, for a few seconds
        News.Item hl = w.news.headline;
        if (hl != headlineItem) {
            headlineItem = hl;
            headlineSeen = System.currentTimeMillis();
        }
        if (hl != null && System.currentTimeMillis() - headlineSeen < 6000 && w.time - hl.time < 60) {
            float hh = ui.dp(28), x0 = freeL + m, x1 = freeR - m;
            float a = Math.min(1, (6000 - (System.currentTimeMillis() - headlineSeen)) / 600f);
            g.color(MapPainter.alpha(0xFF5A1414, 0.92f * a));
            g.fillRoundRect(x0, y + m * 0.5f, x1 - x0, hh, ui.dp(8));
            g.color(MapPainter.alpha(0xFFFF8A70, a));
            g.fillRoundRect(x0, y + m * 0.5f, ui.dp(4), hh, ui.dp(2));
            g.color(MapPainter.alpha(0xFFFFEDE6, a));
            float ts = ui.dp(12.5f);
            g.text(Ui.fit(g, hl.text, x1 - x0 - ui.dp(20), ts, true), x0 + ui.dp(12), y + m * 0.5f + hh / 2 + ts * 0.36f, ts, Gfx.ALIGN_LEFT, true);
            ui.area("radio", x0, y + m * 0.5f, x1 - x0, hh);
            keepHud(x0, y + m * 0.5f, x1, y + m * 0.5f + hh);
            y += hh + m * 0.5f;
        }
        y = alertBanner(g, y);
        // the map's key, under the bar on the left; the minimap on the right
        float ky = y + m;
        if (legendOpen || view.overlay > WorldView.OV_INFECTION) {
            float lw = Math.min(ui.dp(230), (freeR - freeL) - ui.dp(140));
            float lh = legendPanel(g, freeL + m, ky, lw);
            keepHud(freeL + m, ky, freeL + m + lw, ky + lh);
            ky += lh + m;
        } else {
            ui.iconButton("legend", freeL + m, ky, ui.dp(36), ui.dp(36), "help", "", false);
            keepHud(freeL + m - ui.dp(2), ky - ui.dp(2), freeL + m + ui.dp(38), ky + ui.dp(38));
        }
        boolean mm = settings.minimap && cam.zoom > 0.25f && !w.strategic;
        // the numbers: a panel that opens and closes, under the key's button (or the key)
        {
            float sy0 = legendOpen || view.overlay > WorldView.OV_INFECTION ? ky : ky + ui.dp(44);
            float room = freeR - freeL - m * 2 - (mm ? ui.dp(114) : 0);
            float sh = statsPanel(g, freeL + m, sy0, Math.min(ui.dp(250), room), freeB - ui.dp(90) - sy0);
            keepHud(freeL + m - ui.dp(2), sy0 - ui.dp(2), freeL + m + (statsOpen ? Math.min(ui.dp(250), room) : ui.dp(38)), sy0 + sh + ui.dp(2));
        }
        if (mm) {
            minimap(g, freeR - ui.dp(104) - m, y + m, ui.dp(104));
            keepHud(freeR - ui.dp(108) - m, y + m - ui.dp(4), freeR - m + ui.dp(4), y + m + ui.dp(108));
        }
        // pass-and-play: whose turn it is, and the hand-over
        if (w.roles.players > 1 && replay == null) {
            ui.button("handover", freeL + m + ui.dp(44), y + m, ui.dp(64), ui.dp(34), "P" + (w.roles.current + 1) + " ▸", false, 12);
        }
        bottomBars(g);
        if (w.mode == World.MODE_SURVIVOR && w.playerEnt >= 0 && replay == null) joystick(g);
        // the bottom of the map, stacked upwards: the radio, a note, the way to the outbreak
        float top = radioFeed(g, W);
        top = cues(g, top);
        if (w.playerEnt >= 0 && replay == null && !w.ended) {
            boolean can = skipping || !deadNear(SKIP_SAFE);
            float bw = ui.dp(150), bh = ui.dp(34);
            float bx = (stackX0 + stackX1 - bw) / 2, by = top - bh - ui.dp(8);
            if (can) {
                ui.bigButton("skip", bx, by, bw, bh, skipping ? "Stop skipping" : "Skip ahead ▸▸", skipping ? 0xE0B05010 : 0xE0304860);
                top = by;
            }
        }
        if (goLive) {
            float bw = ui.dp(170), bh = ui.dp(36);
            float bx = (freeL + freeR - bw) / 2, by = top - bh - ui.dp(8);
            ui.bigButton("golive", bx, by, bw, bh, "To the outbreak", 0xE0B03028);
            top = by;
        }
        if (top < freeB) keepHud(stackX0, top - ui.dp(2), stackX1, freeB);
        if (settings.debug) {
            g.color(0xC0FFFFFF);
            g.text((int) fps + " fps  " + fmt(w.e.live) + " simulated  zoom " + String.format("%.3f", cam.zoom), freeL + m, freeB - ui.dp(6), ui.dp(Ui.S), Gfx.ALIGN_LEFT, false);
        }
        if (view.aimX >= 0) aimPanel(g);
    }

    /** For the first hours: where it started, pulsing, and a way back there if it's out of view. */
    private boolean outbreakMarker(Gfx g) {
        if (replay != null || w.time > 120 || startX <= 0) return false;
        float x = cam.toScreenX(startX), y = cam.toScreenY(startY);
        float pulse = (view.time * 0.8f) % 1f;
        boolean in = x > freeL && x < freeR && y > freeT && y < freeB;
        if (in) {
            for (int k = 0; k < 2; k++) {
                float p = (pulse + k * 0.5f) % 1f;
                g.color(MapPainter.alpha(0xFFFF5040, (1 - p) * 0.9f));
                g.strokeCircle(x, y, ui.dp(14 + p * 46), ui.dp(2.5f));
            }
            if (cam.zoom > 0.1f) view.label(g, "It starts here", x, y - ui.dp(30), 12, 0xFFFFD0C8, Gfx.ALIGN_CENTER, true);
            return false;
        }
        int ro = w.roles.role;
        // the roles that start on their own piece keep the way to the outbreak for longer
        return w.time < (ro == Roles.GOD || ro == Roles.PRESIDENT || ro == Roles.MILITARY ? 60 : 120);
    }

    // screen areas the HUD took last frame, kept free of map labels this frame
    private final float[] hudKeep = new float[4 * 12];
    private int hudKeepN;
    // the radio's slot, left and right
    private float stackX0, stackX1;

    private void keepHud(float x0, float y0, float x1, float y1) {
        if (hudKeepN >= 12) return;
        int k = hudKeepN++ * 4;
        hudKeep[k] = x0;
        hudKeep[k + 1] = y0;
        hudKeep[k + 2] = x1;
        hudKeep[k + 3] = y1;
    }

    /** The height of the campaign's progress line under the top bar, in dp. */
    private static final float GOAL_H = 22;

    /** Is there a campaign goal to show progress towards? */
    private boolean goalShown() {
        if (w == null || w.scenario == null || w.scenario.goal == null) return false;
        String g = w.scenario.goal;
        // the country-scale goals Roles measures (surviving as one person has no line to show)
        return g.equals("holdcity") || g.equals("evaccoast") || g.equals("retakestate") || g.equals("protectcap")
                || w.roles.progressText != null && w.roles.progressText.length() > 0;
    }

    /** Where the campaign stands, in a line (until the first count is in, that it's coming). */
    private String goalText() {
        String t = w.roles.progressText;
        return t == null || t.length() == 0 ? w.scenario.name + ": the first count is coming in" : t;
    }

    /** The time left for the goal ("29 days left", "5 hours left"), or "" for a goal with no deadline. */
    private String goalDays() {
        int days = w.scenario.days;
        if (days <= 0) return "";
        double left = days * 1440.0 - w.time;
        if (left <= 0) return "Time's up";
        if (left >= 1440 * 2) return (int) Math.ceil(left / 1440) + " days left";
        int h = (int) Math.ceil(left / 60);
        return h + (h == 1 ? " hour left" : " hours left");
    }

    /** The goal's progress as a thin bar with what it means, across the bottom of the top bar. */
    private void goalLine(Gfx g, int W) {
        if (!goalShown()) return;
        float m = ui.dp(10), y0 = topH - ui.dp(GOAL_H);
        float ts = ui.dp(Ui.S), base = y0 + ui.dp(11);
        String days = goalDays();
        float dw = days.length() > 0 ? g.textWidth(days, ts, true) + ui.dp(10) : 0;
        g.color(0xFFD8E0EA);
        g.text(Ui.fit(g, goalText(), W - m * 2 - dw, ts, false), m, base, ts, Gfx.ALIGN_LEFT, false);
        if (dw > 0) {
            g.color(Ui.DIM);
            g.text(days, W - m, base, ts, Gfx.ALIGN_RIGHT, true);
        }
        if (w.roles.progressText == null || w.roles.progressText.length() == 0) return;
        float by = y0 + ui.dp(15), bw = W - m * 2, bh = ui.dp(3);
        float p = Math.max(0, Math.min(1, w.roles.progress));
        g.color(0xFF2A333F);
        g.fillRoundRect(m, by, bw, bh, bh / 2);
        g.color(w.ended ? (w.won ? Ui.GOOD : Ui.ACCENT) : 0xFF5A9CFF);
        if (p > 0) g.fillRoundRect(m, by, Math.max(bh, bw * p), bh, bh / 2);
    }

    /** The bar along the top: menu, the time and weather, the alert level, the numbers, who you are, the speed. */
    private void topBar(Gfx g, int W) {
        float m = ui.dp(8), bh = ui.dp(40);
        g.color(Ui.BAR);
        g.fillRect(0, 0, W, topH);
        g.color(0x20FFFFFF);
        g.fillRect(0, topH - 1, W, 1);
        ui.block(0, 0, W, topH);
        float rowH = ui.dp(46), by = rowH / 2 - bh / 2;
        ui.button("pause", m, by, bh, bh, "", false);
        Icons.draw(g, "menu", m + bh / 2, by + bh / 2, ui.dp(18), Ui.TEXT);
        // speed, fixed widths at the right
        float sw = ui.dp(44), spw = ui.dp(54);
        float sx = W - m - spw - ui.dp(6) - sw;
        ui.button("playpause", sx, by, sw, bh, "", paused);
        Icons.draw(g, paused ? "play" : "pause", sx + sw / 2, by + bh / 2, ui.dp(16), Ui.TEXT);
        ui.button("speed", sx + sw + ui.dp(6), by, spw, bh, speedLabel(), w.strategic, 13);
        // the time, the weather (named), the alert level
        float x = m + bh + ui.dp(10);
        float cy1 = by + ui.dp(17), cy2 = by + ui.dp(34);
        g.color(Ui.TEXT);
        g.text(w.clock(), x, cy1, ui.dp(Ui.L), Gfx.ALIGN_LEFT, true);
        Icons.draw(g, Icons.weather(w.weather.state), x + ui.dp(7), cy2 - ui.dp(4), ui.dp(13), 0xFFD8E0EA);
        String wt = Weather.NAMES[w.weather.state] + " " + Math.round(w.weather.temperature()) + "°";
        g.color(0xFFD8E0EA);
        g.text(wt, x + ui.dp(17), cy2, ui.dp(Ui.S), Gfx.ALIGN_LEFT, false);
        float lx = x + ui.dp(17) + g.textWidth(wt, ui.dp(Ui.S), false) + ui.dp(8);
        int lvl = w.gov.level;
        int lc = lvl <= 0 ? Ui.GOOD : lvl == 1 ? Ui.WARN : lvl == 2 ? 0xFFE07030 : 0xFFE0453A;
        float clockW = Math.max(g.textWidth(w.clock(), ui.dp(Ui.L), true), lx - x);
        float statsX;
        if (landscape) {
            ui.chip(x + clockW + ui.dp(10), (rowH - ui.dp(Ui.S) * 1.75f) / 2, Gov.LEVELS[lvl], Ui.S, MapPainter.alpha(lc, 0.25f), lc, true);
            statsX = x + clockW + ui.dp(18) + g.textWidth(Gov.LEVELS[lvl], ui.dp(Ui.S), true) + ui.dp(12);
            stats(g, statsX, rowH / 2, sx - ui.dp(10));
        } else {
            float room = sx - ui.dp(8) - lx;
            if (room > ui.dp(40)) {
                // short of room, the short name rather than a cut one
                String lv = Gov.LEVELS[lvl];
                if (g.textWidth(lv, ui.dp(Ui.S), true) > room - ui.dp(12)) lv = new String[]{"Normal", "Advisory", "Emergency", "Martial law", "Mobilised"}[lvl];
                lv = Ui.fit(g, lv, room - ui.dp(12), ui.dp(Ui.S), true);
                ui.chip(lx, cy2 - ui.dp(Ui.S) * 1.25f, lv, Ui.S, MapPainter.alpha(lc, 0.25f), lc, true);
            }
            stats(g, m, ui.dp(58), W - m);
        }
        goalLine(g, W);
    }

    /** The numbers as chips with icons: people, the dead, police, army; then who you are. */
    private void stats(Gfx g, float x, float cy, float maxX) {
        float ts = ui.dp(12), is = ui.dp(13);
        int[] nums = hudNumbers(w);
        String[] v = {WorldView.people(nums[0]), WorldView.people(nums[1]), fmt(nums[2]), fmt(nums[3])};
        String[] icon = {"people", "dead", "police", "army"};
        int[] col = {WorldView.C_CIV, 0xFFFF6A5A, 0xFF7FA8FF, WorldView.C_ARMY};
        for (int k = 0; k < 4; k++) {
            float tw = g.textWidth(v[k], ts, true);
            if (x + is + tw + ui.dp(6) > maxX) return;
            Icons.draw(g, icon[k], x + is / 2, cy, is, col[k]);
            g.color(Ui.TEXT);
            g.text(v[k], x + is + ui.dp(4), cy + ts * 0.36f, ts, Gfx.ALIGN_LEFT, true);
            x += is + ui.dp(4) + tw + ui.dp(12);
        }
        // who you are (or that this is a replay)
        String who = replay != null ? "REPLAY: " + replay.title : w.roles.label();
        float room = maxX - x;
        if (room > ui.dp(50)) {
            // the role and its piece; short of room, the role alone; then a shorter word for it
            if (replay == null && g.textWidth(who, ui.dp(Ui.S), true) > room - ui.dp(14)) who = Roles.NAMES[w.roles.role];
            if (g.textWidth(who, ui.dp(Ui.S), true) > room - ui.dp(14)) who = who.replace(" commander", "");
            String cut = Ui.fit(g, who, room - ui.dp(14), ui.dp(Ui.S), true);
            float cw = g.textWidth(cut, ui.dp(Ui.S), true) + ui.dp(12), ch = ui.dp(Ui.S) * 1.75f;
            g.color(replay != null ? 0xD0602080 : 0xFF263040);
            g.fillRoundRect(maxX - cw, cy - ch / 2, cw, ch, ch / 2);
            g.color(0xFFE8ECF0);
            g.text(cut, maxX - cw + ui.dp(6), cy + ui.dp(Ui.S) * 0.36f, ui.dp(Ui.S), Gfx.ALIGN_LEFT, true);
        }
    }

    /**
     * Short notes over the map (fast-forward, and when the people are counted rather than drawn), in their own slot
     * just above the radio's: returns the slot's top (or the bottom given, with no note).
     */
    private float cues(Gfx g, float bottom) {
        boolean detail = cam.zoom >= 0.3f;
        if (detail != wasDetail) {
            wasDetail = detail;
            detailCueT = 3.5f;
        }
        detailCueT -= 0.016f;
        String cue = null;
        int bg = 0xD0101318;
        if (w.strategic) {
            cue = "▶▶ Fast-forward " + speedLabel() + ": everyone counted, fights by the numbers";
            bg = 0xE0603010;
            // a warm edge round the map while time races
            g.color(0x50FF8A30);
            g.fillRect(freeL, freeT, freeR - freeL, ui.dp(3));
            g.fillRect(freeL, freeB - ui.dp(3), freeR - freeL, ui.dp(3));
        } else if (detailCueT > 0) {
            cue = detail ? "Close in: everyone in view is simulated in person" : "Far out: what's out of sight is counted, not drawn";
        }
        if (cue == null) return bottom;
        float ts = ui.dp(Ui.S);
        // on a narrow screen the short form, rather than a cut one
        if (w.strategic && g.textWidth(cue, ts, false) > stackX1 - stackX0 - ui.dp(16)) cue = "▶▶ Fast-forward " + speedLabel() + ": fights by the numbers";
        String c = Ui.fit(g, cue, stackX1 - stackX0 - ui.dp(16), ts, false);
        float tw = g.textWidth(c, ts, false) + ui.dp(16), th = ui.dp(24);
        float x = (stackX0 + stackX1 - tw) / 2, y = bottom - th - ui.dp(6);
        g.color(bg);
        g.fillRoundRect(x, y, tw, th, th / 2);
        g.color(0xFFFFFFFF);
        g.text(c, x + ui.dp(8), y + th / 2 + ts * 0.36f, ts, Gfx.ALIGN_LEFT, false);
        return y;
    }

    private boolean legendOpen;
    /** The numbers panel is open (remembered between games). */
    boolean statsOpen;
    /** The last alert the game has looked at, and the one shown (with where it was), for its seconds left. */
    long alertSeen = -1;
    String alertText;
    float alertX, alertY, alertShow;
    private int inspTrace = -1;

    /** The key to what's on the map: the overlay's colours, and what the marks mean. */
    private float legendPanel(Gfx g, float x, float y, float wdt) {
        float h = view.legend(g, x, y, ui.d, wdt);
        if (h <= 0) h = 0;
        float yy = y + h;
        if (legendOpen) {
            String[] marks = {"Pulsing ring: a 911 call", "Number in it: the dead there", "Blue bar: a police cordon", "Framed cross: a formation",
                    "Red bleed: where the dead are", "Striped bar: a holdout", "Flag: where you sent them"};
            float lh = ui.dp(16), ph = marks.length * lh + ui.dp(30);
            g.color(0xE0101318);
            g.fillRoundRect(x, yy + ui.dp(4), wdt, ph, ui.dp(8));
            g.color(0xFFFFFFFF);
            g.text("The marks", x + ui.dp(8), yy + ui.dp(20), ui.dp(12), Gfx.ALIGN_LEFT, true);
            for (int k = 0; k < marks.length; k++) {
                g.color(0xFFD0D0D0);
                g.text(Ui.fit(g, marks[k], wdt - ui.dp(16), ui.dp(Ui.S), false), x + ui.dp(8), yy + ui.dp(38) + k * lh, ui.dp(Ui.S), Gfx.ALIGN_LEFT, false);
            }
            yy += ph + ui.dp(4);
        }
        ui.area("legend", x, y, wdt, yy - y);
        return yy - y;
    }

    private final float[] arrowTip = new float[6];
    private final Dispatch.Incident[] alertPick = new Dispatch.Incident[3];

    /** Arrows at the edge of the map pointing to the biggest fights out of view; tap one to go there. */
    private void alertArrows(Gfx g) {
        if (cam.zoom < 0.15f || w.strategic) return;
        for (int k = 0; k < alertPick.length; k++) alertPick[k] = null;
        for (Dispatch.Incident in : w.disp.incidents) {
            if (!in.open || in.zombies < 10) continue;
            float sx = cam.toScreenX(in.x), sy = cam.toScreenY(in.y);
            if (sx > freeL && sx < freeR && sy > freeT && sy < freeB) continue;
            // keep the four biggest, biggest first
            for (int k = 0; k < alertPick.length; k++) {
                if (alertPick[k] == null || in.zombies > alertPick[k].zombies) {
                    for (int q = alertPick.length - 1; q > k; q--) alertPick[q] = alertPick[q - 1];
                    alertPick[k] = in;
                    break;
                }
            }
        }
        float cx = (freeL + freeR) / 2, cy = (freeT + freeB) / 2, pad = ui.dp(26);
        for (int k = 0; k < alertPick.length; k++) {
            Dispatch.Incident in = alertPick[k];
            if (in == null) continue;
            float dx = cam.toScreenX(in.x) - cx, dy = cam.toScreenY(in.y) - cy;
            float hw = (freeR - freeL) / 2 - pad, hh = (freeB - freeT) / 2 - pad - ui.dp(30);
            float t = Math.min(hw / Math.max(1e-3f, Math.abs(dx)), hh / Math.max(1e-3f, Math.abs(dy)));
            float ax = cx + dx * t, ay = cy + dy * t;
            float l = (float) Math.sqrt(dx * dx + dy * dy), ux = dx / l, uy = dy / l;
            float r = ui.dp(13);
            int col = in.army ? 0xFFE0A030 : 0xFFE84040;
            g.color(0xE0101318);
            g.fillCircle(ax, ay, r + ui.dp(2));
            g.color(col);
            g.strokeCircle(ax, ay, r, ui.dp(2));
            arrowTip[0] = ax + ux * (r + ui.dp(9));
            arrowTip[1] = ay + uy * (r + ui.dp(9));
            arrowTip[2] = ax + ux * r - uy * ui.dp(6);
            arrowTip[3] = ay + uy * r + ux * ui.dp(6);
            arrowTip[4] = ax + ux * r + uy * ui.dp(6);
            arrowTip[5] = ay + uy * r - ux * ui.dp(6);
            g.fillPoly(arrowTip, 3);
            g.color(0xFFFFFFFF);
            String c = in.zombies >= 1000 ? (in.zombies / 1000) + "k" : "" + in.zombies;
            g.text(c, ax, ay + ui.dp(4), ui.dp(Ui.S), Gfx.ALIGN_CENTER, true);
            ui.area("alert:" + in.id, ax - r * 1.5f, ay - r * 1.5f, r * 3, r * 3);
        }
    }

    /** The last few messages over the bottom of the map: what matters, and the latest routine call, dimmer. */
    private float radioFeed(Gfx g, int W) {
        float m = ui.dp(8);
        float x0 = freeL + m, x1 = freeR - m;
        // beside the survivor's stick, not over it
        if (w.mode == World.MODE_SURVIVOR && w.playerEnt >= 0 && replay == null) x0 = joyCX + ui.dp(64);
        stackX0 = x0;
        stackX1 = x1;
        float ry = freeB - ui.dp(6);
        if (view.aimX >= 0) ry -= ui.dp(60);
        if (!settings.radio || x1 - x0 < ui.dp(120)) return ry;
        int shown = 0;
        boolean routineShown = false;
        float ts = ui.dp(Ui.S + 0.5f), lh = ui.dp(22);
        for (int i = w.news.log.size() - 1; i >= 0 && shown < 3; i--) {
            News.Item it = w.news.log.get(i);
            if (it.kind == News.HEADLINE) continue;
            double age = w.time - it.time;
            boolean routine = it.kind == News.CALL || it.kind == News.UNIT;
            if (age > (routine ? 12 : 30)) {
                if (age > 30) break;
                continue;
            }
            if (routine && routineShown) continue;
            if (routine) routineShown = true;
            float a = (float) Math.max(0, Math.min(1, ((routine ? 12 : 30) - age) / 5)) * (routine ? 0.75f : 1);
            int col = it.kind == News.LOSS ? 0xFFFF8A7A : it.kind == News.COMMAND ? 0xFFFFD87A : it.kind == News.CALL ? 0xFF9EC4FF : 0xFFD8DCE0;
            String t = Ui.fit(g, it.text, x1 - x0 - ui.dp(18), ts, false);
            float tw = g.textWidth(t, ts, false) + ui.dp(16);
            ry -= lh;
            g.color(MapPainter.alpha(0xFF0C0F14, a * 0.8f));
            g.fillRoundRect(x0, ry, tw, lh - ui.dp(3), ui.dp(5));
            g.color(MapPainter.alpha(col, a));
            g.fillRect(x0, ry + ui.dp(3), ui.dp(3), lh - ui.dp(9));
            g.text(t, x0 + ui.dp(9), ry + (lh - ui.dp(3)) / 2 + ts * 0.36f, ts, Gfx.ALIGN_LEFT, false);
            if (it.x >= 0) ui.area("goto:" + i, x0, ry, tw, lh - ui.dp(3));
            shown++;
        }
        return ry;
    }

    /** An aimed strike: what it is, and fire or cancel. */
    private void aimPanel(Gfx g) {
        float m = ui.dp(8), h = ui.dp(52);
        float x0 = freeL + m, x1 = freeR - m, y = freeB - h - m;
        ui.panel(x0, y, x1 - x0, h);
        String what = strikeMode.equals("bridge") ? "Blow this bridge?" : strikeMode.equals("art") ? "Artillery here?" : "Air strike here?";
        g.color(Ui.TEXT);
        float bw = ui.dp(84);
        g.text(Ui.fit(g, what, x1 - x0 - bw * 2 - ui.dp(36), ui.dp(Ui.M), true), x0 + ui.dp(12), y + h / 2 + ui.dp(5), ui.dp(Ui.M), Gfx.ALIGN_LEFT, true);
        ui.bigButton("aim:go", x1 - bw * 2 - ui.dp(16), y + ui.dp(7), bw, h - ui.dp(14), "Fire", Ui.ACCENT);
        ui.button("aim:no", x1 - bw - ui.dp(8), y + ui.dp(7), bw, h - ui.dp(14), "Cancel", false, 13);
    }

    private void minimap(Gfx g, float x, float y, float s) {
        g.color(0xE0000000);
        g.fillRoundRect(x - ui.dp(3), y - ui.dp(3), s + ui.dp(6), s + ui.dp(6), ui.dp(6));
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
            if (!h.alive) continue;
            g.color(0xFFFF4040);
            g.fillCircle(x + h.x * k, y + h.y * k, ui.dp(2));
        }
        g.color(0xFFFFFFFF);
        g.strokeRect(x + cam.x0() * k, y + cam.y0() * k, Math.max(ui.dp(3), (cam.x1() - cam.x0()) * k), Math.max(ui.dp(3), (cam.y1() - cam.y0()) * k), ui.dp(1.2f));
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

    /** Can the player hand the whole army to the AI general (the President and the Military commander)? */
    private boolean generalToggle() {
        int r = w.roles.role;
        return replay == null && (r == Roles.PRESIDENT || r == Roles.MILITARY);
    }

    /** Is this formation the AI general's to move, rather than the player's? */
    private boolean underGeneral(Forces.Formation f) {
        return w.forces.aiMay(f);
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
            case "orders": return "Order";
            case "town": return "Town";
            case "erase": return "Erase";
            case "map": return "Map";
            case "fire": return "Shoot";
            case "send": return "Send";
            default: return t;
        }
    }

    private float paletteH() {
        if (landscape) return 0;
        if (inspectorOpen()) return inspectorH;
        return palette() == null ? 0 : paletteFixedH();
    }

    /** A line saying what the current tool does with a tap. */
    private String paletteCaption() {
        switch (tool) {
            case "dead": return "Drag on the map to paint the dead: pick a kind and how many";
            case "police": return placeVehicle != null ? "Tap a road to place the vehicle" : "Tap the map to place a squad";
            case "army": return placeVehicle != null ? "Tap a road to place the vehicle" : "Tap the map to place soldiers";
            case "strike": return "Tap where to aim, then confirm";
            case "town": return "Tap a town: " + (townMode.equals("info") ? "see how it's doing" : townMode.equals("evac") ? "evacuate it" : townMode.equals("quar") ? "quarantine it" : "send the army");
            case "send": return "Pick who to send, then tap where they go";
            case "map": return "Layers of the national map";
            case "erase": return "Drag to clear the dead away";
            case "orders":
                if (inspFormation != null && inspFormation.alive)
                    return Forces.shortName(inspFormation) + " (" + inspFormation.strength() + "): " + (underGeneral(inspFormation) ? "the general has it; tap where to send it to take it back" : "tap where to hold");
                if (inspSquad != null && inspSquad.alive) return inspSquad.name + ": tap where to go";
                if (generalToggle()) return w.forces.generalCommands ? "The general commands the army; tap a formation to take it back" : "Tap a formation or a squad, then where it goes";
                return w.roles.commandsFormations() ? "Tap a formation or a squad, then where it goes" : "Tap one of your squads, then where it goes";
            default: return null;
        }
    }

    /** Rows of (id, label) pairs for the current tool's options; an id starting with "x:" is shown greyed. */
    private String[][] palette() {
        switch (tool) {
            case "dead": {
                String[] types = {"z:-1", "Mixed", "z:walker", "Walker", "z:runner", "Runner", "z:dog", "Dogs", "z:bruiser", "Bruiser", "z:brute", "Brute", "z:armoured", "Armour", "z:giant", "Giant"};
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
                // the nation's alert: up, down, and martial law and mobilisation each declared or lifted
                int lv = w.gov.level;
                String[] row2 = {"curfew", w.gov.curfew ? "Lift curfew" : "Curfew", lv >= Gov.LEVELS.length - 1 ? "x:alert" : "n:alert", "Raise alert",
                        lv <= 0 ? "x:lower" : "n:lower", "Lower alert"};
                String[] row3 = {"n:martial", lv >= 3 ? "Lift martial law" : "Martial law", "n:mobilise", lv >= 4 ? "End mobilisation" : "Mobilise"};
                return new String[][]{row1, row2, row3};
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
                    // what the station hasn't got is shown greyed, not offered
                    row.add((have > 0 ? "send:" : "x:") + t.id);
                    row.add(t.name + " (" + have + ")");
                }
                // the precinct's own district: a police cordon on its edge, and getting its people out
                String[] district = {"pc:cordon", w.roles.cordon ? "Lift cordon" : "Cordon district", "pc:evac", w.roles.districtEvac ? "Stop evacuating" : "Evacuate district"};
                return new String[][]{row.toArray(new String[0]), district};
            }
            case "map": {
                String[] row1 = {"m:jump", cam.zoom > cam.countryZoom() * 1.3f ? "Whole country" : "Back to where I was", "ov:0", "Plain", "ov:1", "Infection", "ov:2", "Utilities"};
                String[] row2 = {"ov:3", "Supplies", "ov:4", "Trust", "ov:5", "Forces", "ov:6", "Deaths", "ov:7", "Peak"};
                return new String[][]{row1, row2};
            }
            case "orders":
                if (inspFormation != null && inspFormation.alive)
                    return new String[][]{{underGeneral(inspFormation) ? "o:ftake" : "o:frelease", underGeneral(inspFormation) ? "Take command" : "Release to the general", "o:fdone", "Done"}};
                if (inspSquad != null && inspSquad.alive)
                    return new String[][]{{"o:release", "Release", "o:home", "Send home"}};
                if (generalToggle()) return new String[][]{{"o:general", "Let the general command"}};
                return new String[][]{{}};
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
        if (id.equals("o:general")) return w.forces.generalCommands;
        return false;
    }

    /** The tools (a bar along the bottom, or a rail down the left side), and the current tool's options. */
    private void bottomBars(Gfx g) {
        int W = g.width(), H = g.height();
        String[] ts = tools();
        if (landscape) {
            g.color(Ui.BAR);
            g.fillRect(0, topH, railW, H - topH);
            g.color(0x20FFFFFF);
            g.fillRect(railW - 1, topH, 1, H - topH);
            ui.block(0, topH, railW, H - topH);
            float bh = Math.min(ui.dp(58), (H - topH - ui.dp(8)) / ts.length);
            for (int i = 0; i < ts.length; i++) toolButton(ts[i], ui.dp(4), topH + ui.dp(4) + i * bh, railW - ui.dp(8), bh - ui.dp(2));
            if (inspectorOpen()) {
                inspector(g, W - sheetW, topH, sheetW, H - topH);
                return;
            }
            String[][] rows = palette();
            if (rows == null) return;
            sidePalette(g, rows, railW, topH, paletteW, H - topH);
            return;
        }
        toolbarH = ui.dp(60);
        float y = H - toolbarH;
        g.color(Ui.BAR);
        g.fillRect(0, y, W, toolbarH);
        g.color(0x20FFFFFF);
        g.fillRect(0, y, W, 1);
        ui.block(0, y, W, toolbarH);
        float bw = (W - ui.dp(8)) / ts.length;
        for (int i = 0; i < ts.length; i++) toolButton(ts[i], ui.dp(4) + i * bw + ui.dp(1), y + ui.dp(4), bw - ui.dp(2), toolbarH - ui.dp(8));
        if (inspectorOpen()) {
            inspector(g, 0, 0, W, y);
            return;
        }
        String[][] rows = palette();
        if (rows == null) return;
        float ph = paletteFixedH();
        float py = y - ph;
        g.color(0xEC121820);
        g.fillRect(0, py, W, ph);
        g.color(0x18FFFFFF);
        g.fillRect(0, py, W, 1);
        ui.block(0, py, W, ph);
        String cap = paletteCaption();
        if (cap != null) {
            g.color(Ui.DIM);
            g.text(Ui.fit(g, cap, W - ui.dp(20), ui.dp(Ui.S + 0.5f), false), ui.dp(10), py + ui.dp(16), ui.dp(Ui.S + 0.5f), Gfx.ALIGN_LEFT, false);
        }
        float rh = ui.dp(46);
        for (int r = 0; r < rows.length; r++) paletteRow(g, rows[r], ui.dp(4), py + ui.dp(22) + r * rh, W - ui.dp(8), rh - ui.dp(6));
    }

    private void toolButton(String t, float x, float y, float w2, float h) {
        if (t.equals("fire")) {
            // not a tool but a switch: firing at will or holding fire
            boolean on = w.strat.playerFire;
            g0Fill(x, y, w2, h, on ? 0xFF2E5A34 : 0xFF3A2A2A);
            ui.iconButton("tool:fire", x, y, w2, h, "fire", on ? "Firing" : "Hold fire", false);
            return;
        }
        ui.iconButton("tool:" + t, x, y, w2, h, t, toolName(t), t.equals(tool));
    }

    private void g0Fill(float x, float y, float w2, float h, int c) {
        ui.g.color(c);
        ui.g.fillRoundRect(x, y, w2, h, ui.dp(10));
    }

    /** One row of options across a width: each as wide as its label wants, all filling the row. */
    private void paletteRow(Gfx g, String[] row, float x, float y, float wdt, float h) {
        int n = row.length / 2;
        if (n == 0) return;
        float ts = ui.dp(12.5f), gap = ui.dp(4), min = ui.dp(44);
        float want = 0;
        for (int i = 0; i < n; i++) want += Math.max(min, g.textWidth(row[i * 2 + 1], ts, true) + ui.dp(18));
        // each as wide as its label wants, in proportion: a long name gets the room it needs
        float k = (wdt - gap * (n - 1)) / want;
        float bx = x;
        for (int i = 0; i < n; i++) {
            float bw = Math.max(min, g.textWidth(row[i * 2 + 1], ts, true) + ui.dp(18)) * k;
            option(row[i * 2], row[i * 2 + 1], bx, y, bw, h);
            bx += bw + gap;
        }
    }

    private void option(String id, String label, float x, float y, float bw, float h) {
        if (id.startsWith("x:")) ui.disabled(x, y, bw, h, label, 12.5f);
        else ui.button(id, x, y, bw, h, label, paletteOn(id), 12.5f);
    }

    /** The options as a panel beside the rail (landscape): two columns. */
    private void sidePalette(Gfx g, String[][] rows, float x, float y, float wdt, float h) {
        g.color(0xEC121820);
        g.fillRect(x, y, wdt, h);
        g.color(0x18FFFFFF);
        g.fillRect(x + wdt - 1, y, 1, h);
        ui.block(x, y, wdt, h);
        String cap = paletteCaption();
        float yy = y + ui.dp(8);
        // sideways the rail has no room for names: the tool's name heads its options
        g.color(Ui.TEXT);
        g.text(toolName(tool), x + ui.dp(10), yy + ui.dp(16), ui.dp(Ui.L), Gfx.ALIGN_LEFT, true);
        yy += ui.dp(22);
        if (cap != null) yy += ui.wrap(cap, x + ui.dp(10), yy + ui.dp(10), wdt - ui.dp(20), ui.dp(Ui.S + 0.5f), Ui.DIM, 3) + ui.dp(6);
        float bw = (wdt - ui.dp(24)) / 2, bh = ui.dp(40);
        int k = 0;
        for (String[] row : rows) {
            for (int i = 0; i < row.length / 2; i++) {
                boolean wide = row.length == 2 || g.textWidth(row[i * 2 + 1], ui.dp(12.5f), true) > bw - ui.dp(12);
                if (wide && k % 2 == 1) k++;
                option(row[i * 2], row[i * 2 + 1], x + ui.dp(8) + (k % 2) * (bw + ui.dp(8)), yy + (k / 2) * (bh + ui.dp(6)), wide ? bw * 2 + ui.dp(8) : bw, bh);
                k += wide ? 2 : 1;
            }
            if (k % 2 == 1) k++;
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

    // the inspector's bars: label, fraction, colour, value text
    private final String[] barLabel = new String[4], barText = new String[4];
    private final float[] barFrac = new float[4];
    private final int[] barCol = new int[4];
    private int bars;
    private String inspIcon = "look";
    private int inspIconCol = Ui.TEXT;

    private void bar(String label, float frac, int col, String text) {
        if (bars >= 4) return;
        barLabel[bars] = label;
        barFrac[bars] = Math.max(0, Math.min(1, frac));
        barCol[bars] = col;
        barText[bars] = text;
        bars++;
    }

    private static int healthCol(float f) {
        return f > 0.6f ? 0xFF5CC86A : f > 0.3f ? 0xFFE0C040 : 0xFFE04848;
    }

    /** Who or what was tapped, as a sheet: a bottom sheet in portrait (above the tools), a side sheet in landscape. */
    private void inspector(Gfx g, float sx, float top, float sw, float bottom) {
        int W = g.width();
        float m = ui.dp(10);
        String title = "", sub = "";
        StringBuilder body = new StringBuilder();
        String[] actions;
        bars = 0;
        inspTrace = -1;
        inspIcon = "look";
        inspIconCol = Ui.TEXT;
        Ents e = w.e;
        if (inspEnt >= 0) {
            int i = inspEnt;
            if (e.loc[i] == Ents.NONE) {
                closeInspector();
                return;
            }
            title = w.nameOf(i);
            float hp = Math.max(0, e.hp[i]) / Math.max(1, e.maxHp[i]);
            if (e.kind[i] == Ents.ZOMBIE) {
                Content.ZType t = w.ztypeOf(i);
                inspIcon = "dead";
                inspIconCol = 0xFFFF7A6A;
                sub = e.loc[i] == Ents.BODY ? "Destroyed" : title.equals(t.name) ? "One of the dead" : t.name;
                bar("Health", hp, 0xFFB03030, (int) Math.max(0, e.hp[i]) + " of " + (int) e.maxHp[i]);
                if ((e.flags[i] & Ents.F_HELMET) != 0) body.append("Wearing a helmet. ");
                if (t.smash) body.append("Smashes through things. ");
            } else {
                Content.Unit u = w.unitOf(i);
                inspIcon = e.kind[i] == Ents.COP ? "police" : e.kind[i] == Ents.SOLDIER ? "army" : "people";
                inspIconCol = e.kind[i] == Ents.COP ? 0xFF7FA8FF : e.kind[i] == Ents.SOLDIER ? WorldView.C_ARMY : WorldView.C_CIV;
                sub = u.name + (u.agency.length() > 0 ? ", " + u.agency : "");
                if (e.loc[i] == Ents.BODY) sub = "Killed. " + sub;
                bar("Health", hp, healthCol(hp), (int) Math.max(0, e.hp[i]) + " of " + (int) e.maxHp[i]);
                if (u.weapon != null) bar("Ammo", e.ammo[i] / (float) Math.max(1, u.weapon.ammo), 0xFFE0C040, e.ammo[i] + " rounds, " + u.weapon.name);
                if (e.armed(i)) {
                    String doing = w.aai.doing(i);
                    if (e.loc[i] == Ents.FREE && doing.length() > 0) sub = doing + ". " + sub;
                    if (e.loc[i] == Ents.BODY) {
                        String why = w.stats.rec.deathReason(e.uid[i]);
                        if (why.length() > 0) body.append("Fell: ").append(why).append(". ");
                    }
                    if (w.stats.rec.traceLength(i) > 0) inspTrace = i;
                }
                if (e.kills[i] > 0) body.append(e.kills[i]).append(" kills. ");
                if ((e.flags[i] & Ents.F_INFECTED) != 0) body.append("Bitten and infected. ");
                if (e.squad[i] >= 0) {
                    Squads.Squad s = w.sq.get(e.squad[i]);
                    body.append(s.name).append(" (").append(s.label).append("): ").append(Squads.ORDER_NAMES[s.order]).append(". ");
                }
                if (e.kind[i] == Ents.CIV && i != w.playerEnt) {
                    String[] st = {"Out and about", "Fleeing", "Heading for shelter", "Evacuating", "Going home", ""};
                    body.append(st[Math.min(5, e.state[i])]).append(". ");
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
            inspIcon = t.faction.equals("police") ? "police" : t.faction.equals("army") ? "army" : "send";
            inspIconCol = t.faction.equals("police") ? 0xFF7FA8FF : t.faction.equals("army") ? WorldView.C_ARMY : 0xFFE3B21F;
            title = t.name + " " + Names.callsign(t.faction.equals("police") ? Ents.COP : Ents.SOLDIER, vs.uid[v]);
            sub = Vehicles.STATE_NAMES[vs.state[v]] + (vs.siren[v] ? ", lights and siren" : "");
            float cond = Math.max(0, vs.hp[v]) / t.hp;
            bar("Condition", cond, healthCol(cond), (int) (cond * 100) + "%");
            bar("Fuel", vs.fuel[v], 0xFFC08040, (int) (vs.fuel[v] * 100) + "%");
            if (t.seats > 0) bar("Aboard", vs.crewN[v] / (float) t.seats, 0xFF7FA8FF, vs.crewN[v] + " of " + t.seats);
            if (vs.kills[v] > 0) body.append(vs.kills[v]).append(" kills. ");
            if (vs.squad[v] >= 0) {
                Squads.Squad s = w.sq.get(vs.squad[v]);
                body.append(s.name).append(": ").append(Squads.ORDER_NAMES[s.order]).append(". ");
            }
            actions = new String[]{"i:followv", cam.followVehicle == v ? "Stop following" : "Follow", "i:close", "Close"};
        } else if (inspFac >= 0) {
            Region.Facility f = w.r.facilities.get(inspFac);
            title = f.name;
            boolean on = w.strat.online[f.id];
            inspIcon = f.kind == Region.F_POLICE ? "police" : f.kind == Region.F_BASE || f.kind == Region.F_ARMORY ? "army" : "town";
            inspIconCol = on ? Ui.TEXT : 0xFFFF7A6A;
            sub = on ? "Working" : "Overrun";
            if (f.kind == Region.F_POWER) body.append(on ? "Powering " : "Was powering ").append(w.strat.gridName(f.grid)).append(". ");
            if (f.kind == Region.F_POLICE || f.kind == Region.F_BASE || f.kind == Region.F_ARMORY) {
                int waiting = w.forces.armedAt(f, Ents.COP) + w.forces.armedAt(f, Ents.SOLDIER);
                bar("Ammo", (float) Math.log10(1 + f.ammo) / 5, 0xFFE0C040, News.num((int) f.ammo) + " rounds");
                body.append(waiting).append(" waiting here. ");
            }
            if (f.kind == Region.F_HOSPITAL) body.append(on ? "Taking in the bitten. " : "The sick inside have turned. ");
            if (f.kind == Region.F_FUEL) body.append(on ? "Refuelling the town's vehicles. " : "Vehicles here can't refuel. ");
            if (f.food > 0) bar("Food", (float) Math.log10(1 + f.food) / 6, 0xFF60C060, fmt((int) f.food) + " person-days");
            if (f.fuel > 0) bar("Fuel", (float) Math.log10(1 + f.fuel) / 4, 0xFFC08040, (int) f.fuel + " t");
            if (f.med > 0) bar("Medicine", (float) Math.log10(1 + f.med) / 4, 0xFFE0E0F0, (int) f.med + " doses");
            actions = new String[]{"i:close", "Close"};
        } else if (inspSettle >= 0) {
            Region.Settlement s = w.r.settlements.get(inspSettle);
            title = (s.capital ? "★ " : "") + s.name;
            inspIcon = "town";
            sub = (s.capital ? "Capital" : Region.SNAMES[s.kind]) + (s.state >= 0 && s.state < w.r.states.size() ? ", " + w.r.states.get(s.state).name : "")
                    + (w.gov.evacuating(s.id) ? ", evacuating" : "") + (w.roles.quarantined(s.id) ? ", QUARANTINED" : "")
                    + (w.disp.perimeterMode(s.id) ? ", under a police cordon" : "");
            Records rc = w.stats.rec;
            if (rc.townHow[s.id] != Records.T_NONE) {
                sub = (rc.townHow[s.id] == Records.T_FELL ? "Fallen, " : "Abandoned, ") + sub;
                body.append(rc.townHow[s.id] == Records.T_FELL ? "Fell on " : "Abandoned on ").append(clockAt(rc.townAt[s.id]).replace("  ", " at "))
                        .append(": ").append(rc.townWhy[s.id]).append(". ");
            } else if (rc.touched(s.id)) body.append("Holding (the dead at most ").append(WorldView.people(rc.townPeakZ[s.id])).append("). ");
            int still = w.stats.popBySettlement[s.id], dead = w.stats.zBySettlement[s.id];
            bar("People", still / (float) Math.max(1, s.residents), WorldView.C_CIV, WorldView.people(still) + " of " + WorldView.people(s.residents));
            bar("The dead", dead <= 0 ? 0 : (float) Math.log10(1 + dead) / 6, 0xFFE04848, WorldView.people(dead));
            if (s.grid >= 0) body.append(w.strat.gridOn[s.grid] ? "Power on. " : "No power. ");
            body.append("The people are ").append(w.media.mood(s.id));
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
            inspIcon = f.faction == Ents.COP ? "police" : "army";
            inspIconCol = f.faction == Ents.COP ? 0xFF7FA8FF : WorldView.C_ARMY;
            boolean gen = underGeneral(f);
            sub = Forces.STATES[f.state] + (f.player ? ", under your command" : f.task >= 0 ? ", sent to " + w.r.settlements.get(f.task).name : "")
                    + (w.roles.commandsFormations() && !f.player ? (gen ? ", under the general" : ", awaiting your orders") : "");
            float frac = f.start > 0 ? f.strength() / (float) f.start : 1;
            bar("Strength", frac, healthCol(frac), f.strength() + (f.faction == Ents.COP ? " officers" : " soldiers"));
            bar("Ammo", (float) Math.log10(1 + f.hq.ammo) / 5, 0xFFE0C040, News.num((int) f.hq.ammo) + " rounds");
            body.append(f.deployed).append(" out in squads");
            Content.VType tank = w.c.vtype("tank");
            if (tank != null && f.hq.vpool[tank.index] + f.tanksOut > 0) body.append(", ").append(f.hq.vpool[tank.index] + f.tanksOut).append(" tanks");
            if (f.t.guns > 0) body.append(", ").append(f.t.guns).append(" guns");
            body.append(". ").append(fmt(f.kills)).append(" of the dead destroyed, ").append(f.losses).append(" lost.");
            for (Records.Unit ru : w.stats.rec.units)
                if (ru.id == f.id && ru.brokeAt >= 0)
                    body.append(" Broke on ").append(clockAt(ru.brokeAt).replace("  ", " at ")).append(": ").append(ru.why).append(".");
            if (w.roles.commandsFormations() && replay == null)
                actions = gen ? new String[]{"o:ftake", "Take command", "i:close", "Close"}
                        : new String[]{"i:fcommand", "Give orders", "o:frelease", "Release to the general", "i:close", "Close"};
            else actions = new String[]{"i:close", "Close"};
        } else {
            Squads.Squad s = inspSquad;
            if (s == null || !s.alive) {
                closeInspector();
                inspSquad = null;
                return;
            }
            title = s.name + " (" + s.label + ")";
            inspIcon = s.faction == Ents.COP ? "police" : "army";
            inspIconCol = s.faction == Ents.COP ? 0xFF7FA8FF : WorldView.C_ARMY;
            sub = Squads.ORDER_NAMES[s.order] + ", " + s.n + (s.faction == Ents.COP ? " officers" : " soldiers");
            body.append(s.kills).append(" kills, ").append(s.losses).append(" lost");
            actions = new String[]{"i:squad", "Give orders", "i:close", "Close"};
        }
        String text = body.toString().trim();
        float pad = ui.dp(14);
        float textW = sw - (landscape ? m : m * 2) - pad * 2;
        int bl = text.length() == 0 ? 0 : Ui.lines(ui.g, text, textW, ui.dp(12.5f));
        float barH = ui.dp(26);
        int acts = 0;
        for (int k = 0; k < actions.length / 2; k++) if (!actions[k * 2].equals("i:close")) acts++;
        float traceH = inspTrace >= 0 ? ui.dp(40) : 0;
        float contentH = ui.dp(58) + bars * barH + traceH + (bl > 0 ? bl * ui.dp(16.5f) + ui.dp(6) : 0) + ui.dp(acts > 0 ? 60 : 8);
        float x, y, pw, ph;
        if (landscape) {
            x = sx + ui.dp(4);
            y = top + ui.dp(6);
            pw = sw - ui.dp(10);
            ph = Math.min(bottom - ui.dp(12), contentH);
        } else {
            inspectorH = Math.min(contentH, g.height() * 0.45f) + ui.dp(4);
            x = m;
            y = bottom - inspectorH;
            pw = W - m * 2;
            ph = inspectorH - ui.dp(6);
        }
        ui.panel(x, y, pw, ph);
        // the header: an icon, the name, what it is
        g.color(0xFF232C38);
        g.fillCircle(x + pad + ui.dp(15), y + ui.dp(28), ui.dp(16));
        Icons.draw(g, inspIcon, x + pad + ui.dp(15), y + ui.dp(28), ui.dp(18), inspIconCol);
        float tx = x + pad + ui.dp(40);
        g.color(Ui.TEXT);
        g.text(Ui.fit(g, title, pw - (tx - x) - pad - ui.dp(36), ui.dp(Ui.L + 1), true), tx, y + ui.dp(25), ui.dp(Ui.L + 1), Gfx.ALIGN_LEFT, true);
        g.color(Ui.DIM);
        g.text(Ui.fit(g, sub, pw - (tx - x) - pad, ui.dp(12), false), tx, y + ui.dp(43), ui.dp(12), Gfx.ALIGN_LEFT, false);
        ui.button("i:close", x + pw - ui.dp(40), y + ui.dp(8), ui.dp(32), ui.dp(32), "✕", false, 13);
        float yy = y + ui.dp(60);
        // the bars
        for (int k = 0; k < bars; k++) {
            float lw = ui.dp(72);
            g.color(Ui.DIM);
            g.text(barLabel[k], x + pad, yy + ui.dp(12), ui.dp(12), Gfx.ALIGN_LEFT, false);
            float bx = x + pad + lw, bw = pw - pad * 2 - lw;
            g.color(0xFF2A333F);
            g.fillRoundRect(bx, yy + ui.dp(3), bw, ui.dp(11), ui.dp(5));
            g.color(barCol[k]);
            if (barFrac[k] > 0) g.fillRoundRect(bx, yy + ui.dp(3), Math.max(ui.dp(10), bw * barFrac[k]), ui.dp(11), ui.dp(5));
            g.color(0xFFFFFFFF);
            g.textHalo(Ui.fit(g, barText[k], bw - ui.dp(10), ui.dp(Ui.S), true), bx + ui.dp(6), yy + ui.dp(12), ui.dp(Ui.S), Gfx.ALIGN_LEFT, true, 0xA0000000, ui.dp(1));
            yy += barH;
        }
        if (inspTrace >= 0) {
            tracer(g, inspTrace, x + pad, yy, pw - pad * 2);
            yy += traceH;
        }
        if (bl > 0) {
            int maxLines = Math.max(1, (int) ((y + ph - ui.dp(acts > 0 ? 60 : 8) - yy) / ui.dp(16.5f)));
            yy += ui.wrap(text, x + pad, yy + ui.dp(13), textW, ui.dp(12.5f), Ui.TEXT, maxLines) + ui.dp(6);
        }
        // the actions (Close is the button in the corner)
        int n = 0;
        for (int k = 0; k < actions.length / 2; k++) if (!actions[k * 2].equals("i:close")) n++;
        if (n == 0) return;
        float aw = (pw - pad * 2 - ui.dp(8) * (n - 1)) / n;
        int q = 0;
        for (int k = 0; k < actions.length / 2; k++) {
            if (actions[k * 2].equals("i:close")) continue;
            ui.button(actions[k * 2], x + pad + q * (aw + ui.dp(8)), y + ph - ui.dp(52), aw, ui.dp(40), actions[k * 2 + 1], q == 0, 13);
            q++;
        }
    }

    // ------------------------------------------------------------------ the end

    private void endPanel(Gfx g) {
        int W = g.width(), H = g.height();
        float pw = Math.min(W - ui.dp(32), ui.dp(360));
        // a person's own end in a campaign: how the campaign stood, too
        String camp = goalShown() && w.roles.progressText != null && w.roles.progressText.length() > 0 ? w.scenario.name + ": " + w.roles.progressText + "." : null;
        int cl = camp == null ? 0 : Math.min(3, Ui.lines(g, camp, pw - ui.dp(32), ui.dp(12)));
        String endText = w.endText == null ? "" : w.endText;
        int el = Math.min(3, Ui.lines(g, endText, pw - ui.dp(32), ui.dp(13)));
        float ph = ui.dp(64) + el * ui.dp(13) * 1.3f + (cl > 0 ? ui.dp(8) + cl * ui.dp(12) * 1.3f : 0) + ui.dp(72);
        float x = (W - pw) / 2, y = Math.min(H * 0.3f, H - ph - ui.dp(16));
        ui.panel(x, y, pw, ph);
        g.color(w.won ? Ui.GOOD : Ui.ACCENT);
        g.text(Ui.fit(g, w.endTitle == null ? "It's over" : w.endTitle, pw - ui.dp(24), ui.dp(20), true), x + pw / 2, y + ui.dp(36), ui.dp(20), Gfx.ALIGN_CENTER, true);
        float ty = y + ui.dp(64) + ui.wrap(endText, x + ui.dp(16), y + ui.dp(64), pw - ui.dp(32), ui.dp(13), Ui.TEXT, 3);
        if (camp != null) ui.wrap(camp, x + ui.dp(16), ty + ui.dp(8), pw - ui.dp(32), ui.dp(12), Ui.DIM, 3);
        float bw = (pw - ui.dp(48)) / 3;
        ui.button("report", x + ui.dp(16), y + ph - ui.dp(56), bw, ui.dp(40), "Report", false, 13);
        ui.button("watch", x + ui.dp(24) + bw, y + ph - ui.dp(56), bw, ui.dp(40), "Keep watching", false, 12);
        ui.button("tomenu", x + ui.dp(32) + bw * 2, y + ph - ui.dp(56), bw, ui.dp(40), "Menu", false, 13);
    }

    // ------------------------------------------------------------------ survivor stick

    private void joystick(Gfx g) {
        float r = ui.dp(54);
        float cx = freeL + ui.dp(24) + r, cy = freeB - ui.dp(24) - r;
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
        g.color(0xC0000000);
        g.fillRect(0, 0, W, H);
        ui.block(0, 0, W, H);
        boolean land = W > H * 1.15f;
        float pw = Math.min(W - ui.dp(32), ui.dp(land ? 560 : 380));
        float bh = ui.dp(44), gap = ui.dp(8);
        String[][] items = {{"report", "Report"}, {"radio", "Radio log"}, {"save", "Save"}, {"loadp", "Load"},
                {"timelapse", "Timelapse"}, {"savereplay", "Save replay"}, {"sharecode", "Challenge code"}, {"players", "Players"},
                {"settings", "Settings"}, {"shot", "Screenshot"}, {"hideui", "Hide buttons"}, {"tomenu", "Quit to the menu"}};
        // the campaign: how it stands, and the days
        boolean goal = goalShown();
        int cols = land ? (goal ? 4 : 3) : 2;
        if (land && goal) pw = Math.min(W - ui.dp(32), ui.dp(680));
        int rows = (items.length + cols - 1) / cols;
        float iw = pw - ui.dp(28);
        float ts = ui.dp(12.5f);
        int gl = goal ? Math.min(3, Ui.lines(g, goalText(), iw - ui.dp(20), ts)) : 0;
        float goalH = goal ? ui.dp(44) + gl * ts * 1.3f + ui.dp(10) : 0;
        float ph = ui.dp(66) + goalH + ui.dp(56) + gap + rows * (bh + gap) + ui.dp(8);
        ph = Math.min(ph, H - ui.dp(16));
        float x = (W - pw) / 2, y = (H - ph) / 2;
        ui.panel(x, y, pw, ph);
        g.color(Ui.TEXT);
        g.text("Paused", x + pw / 2, y + ui.dp(30), ui.dp(Ui.XL), Gfx.ALIGN_CENTER, true);
        g.color(Ui.DIM);
        String line = w.r.name + (w.scenario != null && !goal ? ", " + w.scenario.name : "") + ". " + w.roles.label() + ". " + w.clock();
        if (g.textWidth(line, ui.dp(Ui.S + 0.5f), false) > pw - ui.dp(24)) line = w.roles.label() + ". " + w.clock();
        g.text(Ui.fit(g, line, pw - ui.dp(24), ui.dp(Ui.S + 0.5f), false), x + pw / 2, y + ui.dp(52), ui.dp(Ui.S + 0.5f), Gfx.ALIGN_CENTER, false);
        float yy = y + ui.dp(66), ix = x + ui.dp(14);
        if (goal) {
            float bx = ix, bw = iw, gh = goalH - ui.dp(10);
            g.color(0xFF141A22);
            g.fillRoundRect(bx, yy, bw, gh, ui.dp(8));
            String days = goalDays();
            float dw = days.length() > 0 ? g.textWidth(days, ui.dp(Ui.S + 0.5f), true) + ui.dp(12) : 0;
            g.color(Ui.ACCENT);
            g.text(Ui.fit(g, w.scenario.name.toUpperCase(), bw - ui.dp(20) - dw, ui.dp(Ui.S + 0.5f), true), bx + ui.dp(10), yy + ui.dp(18), ui.dp(Ui.S + 0.5f), Gfx.ALIGN_LEFT, true);
            if (dw > 0) {
                g.color(Ui.DIM);
                g.text(days, bx + bw - ui.dp(10), yy + ui.dp(18), ui.dp(Ui.S + 0.5f), Gfx.ALIGN_RIGHT, true);
            }
            float p = Math.max(0, Math.min(1, w.roles.progress)), barW = bw - ui.dp(20), barH = ui.dp(6);
            g.color(0xFF2A333F);
            g.fillRoundRect(bx + ui.dp(10), yy + ui.dp(27), barW, barH, barH / 2);
            g.color(w.ended ? (w.won ? Ui.GOOD : Ui.ACCENT) : 0xFF5A9CFF);
            if (p > 0) g.fillRoundRect(bx + ui.dp(10), yy + ui.dp(27), Math.max(barH, barW * p), barH, barH / 2);
            ui.wrap(goalText(), bx + ui.dp(10), yy + ui.dp(48), barW, ts, Ui.TEXT, 3);
            yy += goalH;
        }
        // the main thing: carry on
        ui.bigButton("resume", ix, yy, iw, ui.dp(52), "Resume", Ui.ON);
        yy += ui.dp(52) + gap + ui.dp(4);
        float cw = (iw - gap * (cols - 1)) / cols;
        for (int k = 0; k < items.length; k++) {
            boolean quit = items[k][0].equals("tomenu");
            ui.button(items[k][0], ix + (k % cols) * (cw + gap), yy + (k / cols) * (bh + gap), cw, bh, items[k][1], false, 13);
            if (quit) {
                g.color(0x60E0453A);
                g.fillRect(ix + (k % cols) * (cw + gap) + ui.dp(6), yy + (k / cols) * (bh + gap) + bh - ui.dp(2), cw - ui.dp(12), ui.dp(2));
            }
        }
    }

    private void settingsScreen(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(Ui.SCREEN);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(16);
        Settings s = settings;
        // sections of rows: switches for on/off, a value button for the rest
        String[][] rows = {
                {"#", "Sound"},
                {"set:sound", "Sound effects", s.sound + "%"},
                {"set:music", "Music", s.music + "%"},
                {"set:vibrate", "Vibration", "?" + s.vibrate},
                {"set:mono", "Mono sound", "?" + s.mono},
                {"set:captions", "Captions for sounds", "?" + s.captions},
                {"#", "The map"},
                {"set:lean3d", "Buildings stand up (3D)", "?" + s.lean3d},
                {"set:realistic", "Textured ground", "?" + s.realistic},
                {"set:blood", "Blood", "?" + s.blood},
                {"set:health", "Health bars", "?" + s.healthBars},
                {"set:names", "Place names", "?" + s.names},
                {"set:unitnames", "Unit name tags", "?" + s.unitNames},
                {"set:cb", "Colour-blind palette", "?" + s.colourBlind},
                {"set:shake", "Screen shake", "?" + s.shake},
                {"set:effects", "Effects", new String[]{"Low", "Normal", "High"}[s.effects]},
                {"#", "On the screen"},
                {"set:radio", "Radio messages", "?" + s.radio},
                {"set:autoslow", "Slow down for big events", Settings.AUTOSLOW_NAMES[s.autoSlow]},
                {"set:minimap", "Minimap", "?" + s.minimap},
                {"set:ui", "Button size", new String[]{"Small", "Normal", "Large"}[s.uiSize]},
                {"set:fps", "Frame rate", s.fpsCap == 30 ? "30 (saver)" : "60"},
                {"set:debug", "Frame rate (for testing)", "?" + s.debug},
                {"#", "The simulation"},
                {"set:max", "People in detail", WorldView.fmt(s.maxIndividuals)},
                {"set:threads", "Threads", s.threads == 0 ? "Auto (" + s.simThreads() + ")" : "" + s.threads},
                {"set:gpu", "Renderer (next start)", s.gpu ? "GPU (test)" : "Canvas"}};
        boolean land = W > H * 1.15f;
        float colW = land ? (W - m * 3) / 2 : W - m * 2;
        float top = ui.dp(60), rh = ui.dp(46), sh = ui.dp(34);
        g.save();
        g.clip(0, ui.dp(54), W, H - ui.dp(54));
        float y = top - scroll, x = m;
        int col = 0;
        float colTop = y;
        for (int k = 0; k < rows.length; k++) {
            String[] r = rows[k];
            if (land && col == 0 && r[0].equals("#") && k > rows.length / 2 && rows[k][1].equals("On the screen")) {
                col = 1;
                x = m * 2 + colW;
                y = colTop;
            }
            if (r[0].equals("#")) {
                y += ui.dp(8);
                g.color(Ui.ACCENT);
                g.text(r[1].toUpperCase(), x, y + sh - ui.dp(10), ui.dp(Ui.S + 0.5f), Gfx.ALIGN_LEFT, true);
                y += sh;
                continue;
            }
            g.color(0xFF18202A);
            g.fillRoundRect(x, y, colW, rh - ui.dp(4), ui.dp(8));
            g.color(Ui.TEXT);
            g.text(Ui.fit(g, r[1], colW - ui.dp(150), ui.dp(Ui.L - 1), false), x + ui.dp(12), y + (rh - ui.dp(4)) / 2 + ui.dp(5), ui.dp(Ui.L - 1), Gfx.ALIGN_LEFT, false);
            if (r[2].startsWith("?")) ui.toggle(r[0], x, y, colW - ui.dp(10), rh - ui.dp(4), r[2].equals("?true"));
            else ui.button(r[0], x + colW - ui.dp(128), y + ui.dp(5), ui.dp(120), rh - ui.dp(14), r[2], false, 13);
            y += rh;
        }
        y += ui.dp(6);
        float ny = ui.wrap("More people in detail look better and cost more on a slow phone. The rest are still simulated, as counts.", m, y + ui.dp(16), W - m * 2, ui.dp(12), Ui.DIM, 3);
        g.restore();
        maxScroll = Math.max(0, y + ny + ui.dp(30) + scroll - H);
        g.color(Ui.SCREEN);
        g.fillRect(0, 0, W, ui.dp(54));
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(36), "Back", false, 13);
        ui.title("Settings", m + ui.dp(84), ui.dp(35));
    }

    // ------------------------------------------------------------------ pass-and-play

    private static final int[] SHARED_ROLES = {Roles.PRESIDENT, Roles.MILITARY, Roles.INCIDENT, Roles.PRECINCT, Roles.SQUAD};
    private int handTo;

    /** Who plays what: the players sharing this game, and adding one. */
    private void players(Gfx g) {
        int W = g.width(), H = g.height();
        g.color(Ui.SCREEN);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(16), y = ui.dp(70), bw = W - m * 2;
        ui.block(0, 0, W, H);
        ui.button("back", m, ui.dp(14), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("Players", m + ui.dp(84), ui.dp(38));
        Roles ro = w.roles;
        for (int k = 0; k < ro.players; k++) {
            boolean now = k == ro.current;
            ui.card("pl:" + k, m, y, bw, ui.dp(60), "Player " + (k + 1) + (now ? " (playing now)" : ""), ro.labelOf(k), now);
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
        g.color(Ui.SCREEN);
        g.fillRect(0, 0, W, H);
        float m = ui.dp(12);
        g.save();
        g.clip(0, ui.dp(54), W, H - ui.dp(54));
        float y = ui.dp(60) - scroll;
        float ts = ui.dp(Ui.M), tw = W - m * 2 - ui.dp(12);
        List<News.Item> log = w.news.log;
        for (int i = log.size() - 1; i >= 0; i--) {
            News.Item it = log.get(i);
            // each message whole, wrapped over as many lines as it needs (up to four)
            int nl = Math.min(4, Ui.lines(g, it.text, tw, ts));
            float rh = ui.dp(30) + nl * ts * 1.3f + ui.dp(6);
            if (y > -rh && y < H) {
                int col = it.kind == News.HEADLINE ? 0xFFFFC890 : it.kind == News.LOSS ? 0xFFFF8A7A : it.kind == News.COMMAND ? 0xFFFFD87A : it.kind == News.CALL ? 0xFF9EC4FF : Ui.TEXT;
                boolean go = it.x >= 0;
                if (go) ui.button("goto:" + i, m - ui.dp(4), y, W - m * 2 + ui.dp(8), rh - ui.dp(4), "", false);
                else {
                    g.color(0xFF141A22);
                    g.fillRoundRect(m - ui.dp(4), y, W - m * 2 + ui.dp(8), rh - ui.dp(4), ui.dp(8));
                }
                g.color(col);
                g.fillRoundRect(m - ui.dp(4), y + ui.dp(6), ui.dp(3), rh - ui.dp(16), ui.dp(1.5f));
                String kind = it.kind == News.HEADLINE ? "News" : it.kind == News.LOSS ? "Loss" : it.kind == News.COMMAND ? "Command" : it.kind == News.CALL ? "911" : "Units";
                g.color(Ui.DIM);
                g.text(clockAt(it.time) + "  ·  " + kind + (go ? "  ·  tap to go there" : ""), m + ui.dp(6), y + ui.dp(15), ui.dp(Ui.S), Gfx.ALIGN_LEFT, false);
                ui.wrap(it.text, m + ui.dp(6), y + ui.dp(32), tw, ts, col, 4);
            }
            y += rh;
        }
        g.restore();
        maxScroll = Math.max(0, y + scroll - H + ui.dp(20));
        g.color(Ui.SCREEN);
        g.fillRect(0, 0, W, ui.dp(54));
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(36), "Back", false, 13);
        ui.title("Radio log", m + ui.dp(84), ui.dp(35));
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
        g.text(Ui.fit(g, w.r.name + ", " + w.clock() + (w.scenario != null ? ", " + w.scenario.name : ""), W - m * 2, ui.dp(12), false), m, y, ui.dp(12), Gfx.ALIGN_LEFT, false);
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
                    g.text(WorldView.people(Integer.parseInt(r[1])) + " lost, " + duration(Integer.parseInt(r[2]))
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
        y = reportMoments(g, s, y, m, W);
        // the timeline: each line against its own peak (named in the key), days along the bottom
        float gx = m + ui.dp(30), gw = W - m - gx, gh = ui.dp(160);
        y += ui.dp(14);
        g.color(0xFF141A22);
        g.fillRoundRect(m, y - ui.dp(8), W - m * 2, gh + ui.dp(34), ui.dp(8));
        int n = s.tN;
        int maxZ = 1, maxA = 1, maxP = 1, maxR = 1, maxPo = 0, maxRo = 0;
        for (int i = 0; i < n; i++) {
            maxZ = Math.max(maxZ, s.tZombies[i]);
            maxA = Math.max(maxA, s.tAlive[i]);
            // police and soldiers: those out and everyone on one scale, so the two lines compare
            maxP = Math.max(maxP, Math.max(s.tPolice[i], s.tPoliceAll[i]));
            maxR = Math.max(maxR, Math.max(s.tArmy[i], s.tArmyAll[i]));
            maxPo = Math.max(maxPo, s.tPolice[i]);
            maxRo = Math.max(maxRo, s.tArmy[i]);
        }
        float gy = y + ui.dp(4), gh2 = gh - ui.dp(8);
        // axes and gridlines: 0, half, peak
        for (int k = 0; k <= 2; k++) {
            float ly = gy + gh2 - gh2 * k / 2f;
            g.color(k == 0 ? 0x60FFFFFF : 0x22FFFFFF);
            g.fillRect(gx, ly, gw, 1);
            g.color(0xFF7A8490);
            g.text(k == 0 ? "0" : k == 1 ? "½" : "peak", gx - ui.dp(4), ly + ui.dp(4), ui.dp(Ui.S - 1), Gfx.ALIGN_RIGHT, false);
        }
        g.color(0x60FFFFFF);
        g.fillRect(gx, gy, 1, gh2);
        if (n >= 2) {
            float t0 = s.tTime[0], t1 = Math.max(t0 + 1, s.tTime[n - 1]);
            // day ticks (or hours, for a short game)
            float span = t1 - t0;
            float stepMin = span > 60 * 24 * 3 ? 60 * 24 : span > 60 * 24 ? 60 * 6 : span > 60 * 6 ? 60 : 30;
            float first = (float) Math.ceil((World.START_HOUR * 60 + t0) / stepMin) * stepMin - World.START_HOUR * 60;
            int ticks = 0;
            for (float t = first; t <= t1 && ticks < 40; t += stepMin, ticks++) {
                float tx = gx + gw * (t - t0) / span;
                g.color(0x30FFFFFF);
                g.fillRect(tx, gy, 1, gh2);
                if (ticks % Math.max(1, (int) (span / stepMin / 6)) == 0) {
                    g.color(0xFF7A8490);
                    int hr = (int) ((World.START_HOUR + t / 60) % 24 + 0.01f);
                    String lab = stepMin >= 60 * 24 || hr == 0 ? "Day " + (1 + (int) ((World.START_HOUR + t / 60 + 0.01f) / 24)) : (hr < 10 ? "0" : "") + hr + ":00";
                    g.text(lab, tx, gy + gh2 + ui.dp(14), ui.dp(Ui.S - 1), Gfx.ALIGN_CENTER, false);
                }
            }
            series(g, s.tAlive, s.tTime, n, maxA, gx, gy, gw, gh2, 0xFFE8E0C8, t0, span);
            series(g, s.tPoliceAll, s.tTime, n, maxP, gx, gy, gw, gh2, 0xFF5A96FF, t0, span);
            series(g, s.tArmyAll, s.tTime, n, maxR, gx, gy, gw, gh2, WorldView.C_ARMY, t0, span);
            seriesThin(g, s.tPolice, s.tTime, n, maxP, gx, gy, gw, gh2, 0xB05A96FF, t0, span);
            seriesThin(g, s.tArmy, s.tTime, n, maxR, gx, gy, gw, gh2, MapPainter.alpha(WorldView.C_ARMY, 0.7f), t0, span);
            series(g, s.tZombies, s.tTime, n, maxZ, gx, gy, gw, gh2, 0xFFE84848, t0, span);
            int tp = s.turningPoint();
            if (tp >= 0) {
                float tx = gx + gw * (s.tTime[tp] - t0) / span;
                g.color(0xC0FFFFFF);
                WorldView.dashedLine(g, tx, gy, tx, gy + gh2, ui.dp(1.2f), ui.dp(4));
                String lab = "turning point";
                float lw = g.textWidth(lab, ui.dp(Ui.S), true);
                float lx = Math.max(gx + ui.dp(2), Math.min(gx + gw - lw - ui.dp(2), tx - lw / 2));
                g.color(0xFFFFFFFF);
                g.textHalo(lab, lx, gy + ui.dp(12), ui.dp(Ui.S), Gfx.ALIGN_LEFT, true, 0xE0141A22, ui.dp(2));
            }
        } else {
            g.color(Ui.DIM);
            g.text("The timeline fills in as the game goes on", gx + gw / 2, gy + gh2 / 2, ui.dp(12), Gfx.ALIGN_CENTER, false);
        }
        y += gh + ui.dp(30);
        // the key, with each line's peak
        int maxPa = 0, maxRa = 0;
        for (int i = 0; i < n; i++) {
            maxPa = Math.max(maxPa, s.tPoliceAll[i]);
            maxRa = Math.max(maxRa, s.tArmyAll[i]);
        }
        String[] keys = {"people (peak " + WorldView.people(maxA) + ")", "the dead (peak " + WorldView.people(Math.max(maxZ, s.peakZombies)) + ")",
                "police in all (peak " + fmt(maxPa) + ")", "soldiers in all (peak " + fmt(maxRa) + ")",
                "police out (thin, peak " + fmt(maxPo) + ")", "soldiers out (thin, peak " + fmt(maxRo) + ")"};
        int[] kc = {0xFFE8E0C8, 0xFFE84848, 0xFF5A96FF, WorldView.C_ARMY, 0xB05A96FF, MapPainter.alpha(WorldView.C_ARMY, 0.7f)};
        float kx = m;
        for (int k = 0; k < keys.length; k++) {
            float kw = g.textWidth(keys[k], ui.dp(Ui.S + 0.5f), true) + ui.dp(26);
            if (kx + kw > W - m) {
                kx = m;
                y += ui.dp(18);
            }
            g.color(kc[k]);
            g.fillRoundRect(kx, y - ui.dp(k >= 4 ? 7 : 8), ui.dp(14), ui.dp(k >= 4 ? 2 : 4), ui.dp(1));
            g.text(keys[k], kx + ui.dp(18), y, ui.dp(Ui.S + 0.5f), Gfx.ALIGN_LEFT, true);
            kx += kw;
        }
        y += ui.dp(18);
        int tp = s.turningPoint();
        if (tp >= 0) {
            y += ui.wrap("The turning point came on " + clockAt(s.tTime[tp]).replace("  ", " at ") + ", when the dead grew fastest.", m, y + ui.dp(10), W - m * 2, ui.dp(12.5f), Ui.TEXT, 3) + ui.dp(8);
        }
        String[][] nums = {
                {"People killed", fmt(s.civDeaths)}, {"Infected", fmt(s.infectedTotal)}, {"Most of the dead at once", fmt(s.peakZombies)},
                {"Dead destroyed", fmt(s.zombieKills)}, {"  by the police", fmt(s.killsBySide[1])}, {"  by the army", fmt(s.killsBySide[2])},
                {"Officers lost", fmt(s.armedDeaths[Ents.COP])}, {"Soldiers lost", fmt(s.armedDeaths[Ents.SOLDIER])},
                {"Police at the start (in all)", s.rec.startPoliceAll < 0 ? "-" : fmt(s.rec.startPoliceAll)},
                {"Police now (out / in all)", fmt(s.policeOut()) + " / " + fmt(s.policeAll())},
                {"Soldiers at the start (in all)", s.rec.startSoldiersAll < 0 ? "-" : fmt(s.rec.startSoldiersAll)},
                {"Soldiers now (out / in all)", fmt(s.soldiersOut()) + " / " + fmt(s.soldiersAll())},
                {"Help from outside", fmt(s.outsidePolice + s.outsideSoldiers)}, {"Evacuated", fmt(w.strat.evacuatedOut + w.strat.evacuatedArrived)},
                {"Air strikes", fmt(s.airStrikes)}, {"Bridges blown", fmt(s.bridgesBlown)}, {"Places overrun", fmt(s.facilitiesLost)},
                {"Buildings destroyed", fmt(s.buildingsLost)}, {"Vehicles lost", fmt(s.vehiclesLost)}, {"Shots fired", fmt(s.shots)},
                {"Public trust", (int) w.gov.trust + "%"}};
        for (String[] r : nums) {
            g.color(Ui.DIM);
            g.text(r[0], m, y + ui.dp(14), ui.dp(12.5f), Gfx.ALIGN_LEFT, false);
            g.color(Ui.TEXT);
            g.text(r[1], W - m, y + ui.dp(14), ui.dp(12.5f), Gfx.ALIGN_RIGHT, true);
            y += ui.dp(20);
        }
        String lost = s.lossSummary();
        if (lost.length() > 0) {
            y += ui.dp(12);
            g.color(Ui.ACCENT);
            g.text("WHERE PEOPLE WERE LOST", m, y + ui.dp(12), ui.dp(13), Gfx.ALIGN_LEFT, true);
            y += ui.dp(20);
            y += ui.wrap(Character.toUpperCase(lost.charAt(0)) + lost.substring(1) + ".", m, y + ui.dp(12), W - m * 2, ui.dp(12.5f), Ui.TEXT, 6) + ui.dp(4);
        }
        y = reportTowns(g, s, y, m, W);
        y = reportFormations(g, s, y, m, W);
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
        String lastEv = null;
        int repeats = 0;
        for (Stats.Event ev : s.events) {
            // the same thing twice running is told once
            if (ev.text.equals(lastEv)) {
                repeats++;
                continue;
            }
            lastEv = ev.text;
            int mins = (int) (ev.time / 60);
            g.color(Ui.DIM);
            g.text(clockAt(ev.time), m, y + ui.dp(12), ui.dp(Ui.S), Gfx.ALIGN_LEFT, false);
            y += ui.wrap(ev.text, m + ui.dp(84), y + ui.dp(12), W - m * 2 - ui.dp(84), ui.dp(12), Ui.TEXT, 3) + ui.dp(5);
            if (mins < 0) break;
        }
        if (repeats > 0) {
            g.color(Ui.DIM);
            g.text("(" + repeats + " repeated " + (repeats == 1 ? "entry" : "entries") + " left out)", m, y + ui.dp(12), ui.dp(Ui.S), Gfx.ALIGN_LEFT, false);
            y += ui.dp(18);
        }
        y += ui.dp(12);
        g.color(Ui.ACCENT);
        g.text("THE FALLEN (" + s.fallen.size() + ")", m, y + ui.dp(12), ui.dp(13), Gfx.ALIGN_LEFT, true);
        y += ui.dp(18);
        for (int i = s.fallen.size() - 1; i >= Math.max(0, s.fallen.size() - 40); i--) {
            y += ui.wrap(s.fallen.get(i), m, y + ui.dp(12), W - m * 2, ui.dp(11.5f), 0xFFB8C0CA, 3) + ui.dp(4);
        }
        g.restore();
        maxScroll = Math.max(0, y + scroll - H + ui.dp(40));
        g.color(0xFF0E1116);
        g.fillRect(0, 0, W, ui.dp(54));
        ui.button("back", m, ui.dp(10), ui.dp(70), ui.dp(34), "Back", false, 13);
        ui.title("After-action report", m + ui.dp(84), ui.dp(34));
    }

    // ------------------------------------------------------------------ legibility: the numbers, alerts, the tracer, the report's story

    /** The HUD's four numbers: the living, the dead, police in all, soldiers in all (the same as Stats and the report). */
    static int[] hudNumbers(World w) {
        return new int[]{w.livingCivilians(), w.totalZombies(), w.stats.policeAll(), w.stats.soldiersAll()};
    }

    private World alertOf;

    /**
     * New alerts from the records. While time races (or a person skips ahead), one the settings ask for slows the
     * game to 1x and is shown with a way there. Returns true if it slowed down.
     */
    boolean takeAlerts() {
        Records rc = w.stats.rec;
        if (alertOf != w) {
            // a new or loaded game: nothing from before it
            alertOf = w;
            alertSeen = rc.alertSeq - 1;
            alertShow = 0;
            return false;
        }
        long from = Math.max(alertSeen + 1, rc.alertSeq - rc.alerts.length);
        Records.Alert pick = null;
        for (long q = from; q < rc.alertSeq; q++) {
            Records.Alert a = rc.alerts[(int) (q % rc.alerts.length)];
            if (a == null || a.seq != q || !settings.slowsFor(a.kind)) continue;
            // the biggest kind wins (a town lost over a horde near one)
            if (pick == null || a.kind >= pick.kind) pick = a;
        }
        alertSeen = rc.alertSeq - 1;
        if (pick == null || replay != null) return false;
        if (!(skipping || SPEEDS[speedIdx] >= 16)) return false; // at normal speeds the callouts and the radio say it
        speedIdx = 0;
        skipping = false;
        achieved = 0;
        alertText = pick.text;
        alertX = pick.x;
        alertY = pick.y;
        alertShow = 8;
        return true;
    }

    /** The event fast-forward stopped for, under the top bar: what it was, and a way there. Returns the y below it. */
    private float alertBanner(Gfx g, float y) {
        if (alertText == null || alertShow <= 0) return y;
        alertShow -= 0.016f;
        float m = ui.dp(8), x0 = freeL + m, x1 = freeR - m, h = ui.dp(44), top = y + m * 0.5f;
        float a = Math.min(1, alertShow / 0.5f);
        g.color(MapPainter.alpha(0xF0201810, a));
        g.fillRoundRect(x0, top, x1 - x0, h, ui.dp(10));
        g.color(MapPainter.alpha(0xFFFFB040, a));
        g.fillRoundRect(x0, top, ui.dp(4), h, ui.dp(2));
        float bw = ui.dp(84), cw = ui.dp(30);
        g.color(MapPainter.alpha(0xFFFFC070, a));
        g.text("SLOWED TO 1x FOR", x0 + ui.dp(12), top + ui.dp(16), ui.dp(Ui.S - 1), Gfx.ALIGN_LEFT, true);
        g.color(MapPainter.alpha(0xFFFFFFFF, a));
        float ts = ui.dp(12.5f);
        g.text(Ui.fit(g, alertText, x1 - x0 - bw - cw - ui.dp(28), ts, true), x0 + ui.dp(12), top + ui.dp(34), ts, Gfx.ALIGN_LEFT, true);
        ui.block(x0, top, x1 - x0, h);
        ui.button("alertgo", x1 - bw - cw - ui.dp(10), top + ui.dp(6), bw, h - ui.dp(12), "Go there", true, 12);
        ui.button("alertx", x1 - cw - ui.dp(4), top + ui.dp(7), cw, h - ui.dp(14), "✕", false, 12);
        keepHud(x0, top, x1, top + h);
        return top + h;
    }

    /** The numbers panel, open or as a button: returns its height. */
    private float statsPanel(Gfx g, float x, float y, float wdt, float maxH) {
        if (!statsOpen || wdt < ui.dp(150) || maxH < ui.dp(120)) {
            float b = ui.dp(36);
            g.color(0xD0101318);
            g.fillRoundRect(x, y, b, b, ui.dp(10));
            ui.iconButton("statsp", x, y, b, b, "chart", "", false);
            return b;
        }
        Stats s = w.stats;
        float pad = ui.dp(9), lh = ui.dp(16), ts = ui.dp(11.5f);
        float top = y;
        // the frame is drawn after its height is known: everything is laid out first into the rows below
        rowsN = 0;
        row("Kills, police", fmt(s.killsBySide[Ents.COP]), 0xFF8FB4FF);
        row("Kills, army", fmt(s.killsBySide[Ents.SOLDIER]), 0xFFD8CC90);
        row("Kills, civilians", fmt(s.killsBySide[Ents.CIV]), 0xFFE8D8B8);
        row("Kills a minute", fmtRate(s.rec.killRate()), Ui.TEXT);
        row("Most of the dead at once", WorldView.people(s.peakZombies), 0xFFFF8A7A);
        row("Police out / in all", fmt(s.policeOut()) + " / " + fmt(s.policeAll()), 0xFF8FB4FF);
        row("Soldiers out / in all", fmt(s.soldiersOut()) + " / " + fmt(s.soldiersAll()), 0xFFD8CC90);
        int open = 0;
        for (Dispatch.Incident in : w.disp.incidents) if (in.open) open++;
        row("Open calls", fmt(open), open > 0 ? 0xFFFFC070 : Ui.TEXT);
        row("Help from outside", fmt(s.outsidePolice + s.outsideSoldiers), Ui.TEXT);
        int rowsFixed = rowsN;
        // the three best
        Integer[] order = new Integer[10];
        for (int i = 0; i < 10; i++) order[i] = i;
        final int[] bk = s.bestKills;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return bk[b] - bk[a];
            }
        });
        int tops = 0;
        for (int k : order) {
            if (tops >= 3 || s.bestName[k] == null || s.bestKills[k] <= 0) continue;
            tops++;
            row(tops + ". " + s.bestName[k], s.bestKills[k] + "", Ui.TEXT);
        }
        float headH = ui.dp(24), sparkH = ui.dp(34), topsH = tops > 0 ? ui.dp(18) : 0;
        float h = headH + sparkH + ui.dp(6) + rowsN * lh + topsH + pad;
        // short of room: the best units go first, then rows from the bottom
        while (h > maxH && rowsN > 4) {
            rowsN--;
            if (rowsN <= rowsFixed) topsH = 0;
            h = headH + sparkH + ui.dp(6) + rowsN * lh + topsH + pad;
        }
        g.color(0xE8101318);
        g.fillRoundRect(x, top, wdt, h, ui.dp(10));
        ui.block(x, top, wdt, h);
        g.color(Ui.ACCENT);
        g.text("THE NUMBERS", x + pad, top + ui.dp(16), ui.dp(Ui.S), Gfx.ALIGN_LEFT, true);
        g.color(Ui.DIM);
        g.text(w.clock(), x + wdt - pad - ui.dp(22), top + ui.dp(16), ui.dp(Ui.S - 1), Gfx.ALIGN_RIGHT, false);
        ui.button("statsp", x + wdt - ui.dp(28), top + ui.dp(3), ui.dp(24), ui.dp(20), "▴", false, 11);
        // the sparkline: the dead over the timeline (the last 90 samples)
        float sx = x + pad, sw = wdt - pad * 2, sy = top + headH, sh = sparkH - ui.dp(4);
        g.color(0xFF18202A);
        g.fillRoundRect(sx, sy, sw, sh, ui.dp(4));
        int n = s.tN, from = Math.max(0, n - 90), cnt = n - from;
        int mx = 1;
        for (int i = from; i < n; i++) mx = Math.max(mx, s.tZombies[i]);
        if (cnt >= 2) {
            float[] pts = new float[cnt * 2];
            for (int i = 0; i < cnt; i++) {
                pts[i * 2] = sx + sw * i / (cnt - 1f);
                pts[i * 2 + 1] = sy + sh - ui.dp(2) - (sh - ui.dp(6)) * s.tZombies[from + i] / (float) mx;
            }
            g.color(0xFFE84848);
            g.lines(pts, cnt, ui.dp(1.5f));
            g.fillCircle(pts[cnt * 2 - 2], pts[cnt * 2 - 1], ui.dp(2.2f));
        }
        g.color(0xFFFFD0C8);
        g.textHalo("the dead " + WorldView.people(w.totalZombies()), sx + ui.dp(4), sy + ui.dp(11), ui.dp(Ui.S - 1.5f), Gfx.ALIGN_LEFT, true, 0xE0101318, ui.dp(1.5f));
        float yy = sy + sparkH + ui.dp(6);
        for (int k = 0; k < rowsN; k++) {
            if (k == rowsFixed && topsH > 0) {
                g.color(Ui.ACCENT);
                g.text("TOP UNITS", x + pad, yy + ui.dp(12), ui.dp(Ui.S - 1), Gfx.ALIGN_LEFT, true);
                yy += topsH;
            }
            float vw = g.textWidth(rowV[k], ts, true);
            g.color(Ui.DIM);
            g.text(Ui.fit(g, rowL[k], wdt - pad * 2 - vw - ui.dp(8), ts, false), x + pad, yy + ui.dp(11), ts, Gfx.ALIGN_LEFT, false);
            g.color(rowC[k]);
            g.text(rowV[k], x + wdt - pad, yy + ui.dp(11), ts, Gfx.ALIGN_RIGHT, true);
            yy += lh;
        }
        return h;
    }

    private final String[] rowL = new String[16], rowV = new String[16];
    private final int[] rowC = new int[16];
    private int rowsN;

    private void row(String label, String value, int col) {
        if (rowsN >= rowL.length) return;
        rowL[rowsN] = label;
        rowV[rowsN] = value;
        rowC[rowsN] = col;
        rowsN++;
    }

    private static String fmtRate(float r) {
        if (r <= 0) return "0";
        if (r < 10) return String.format(java.util.Locale.ROOT, "%.1f", r);
        return WorldView.fmt(Math.round(r));
    }

    /** What a fighter did in their last ten samples: a strip of cells (the dead near them in each), the latest in words. */
    private void tracer(Gfx g, int i, float x, float y, float wdt) {
        Records rc = w.stats.rec;
        int n = rc.traceLength(i);
        float lw = ui.dp(72);
        g.color(Ui.DIM);
        g.text("Last 10 s", x, y + ui.dp(12), ui.dp(12), Gfx.ALIGN_LEFT, false);
        float cx = x + lw, cw = (wdt - lw) / Records.TRACE, ch = ui.dp(15);
        int[] last = null;
        for (int k = 0; k < Records.TRACE; k++) {
            float bx = cx + k * cw;
            int idx = k - (Records.TRACE - n);
            if (idx < 0) {
                g.color(0xFF222A34);
                g.fillRoundRect(bx + ui.dp(1), y + ui.dp(2), cw - ui.dp(2), ch, ui.dp(3));
                continue;
            }
            int[] sm = rc.sampleAt(i, idx);
            last = sm;
            int a = sm[0];
            int col = a == ArmedAI.A_ENGAGE || a == ArmedAI.A_REARGUARD || a == ArmedAI.A_COVER ? 0xFFD8603E
                    : a == ArmedAI.A_FALLBACK || a == ArmedAI.A_WOUNDED ? 0xFFD8B038 : a == ArmedAI.A_FLEE ? 0xFFA860D8
                    : a == ArmedAI.A_RELOAD ? 0xFF4C8CE0 : 0xFF56626E;
            g.color(col);
            g.fillRoundRect(bx + ui.dp(1), y + ui.dp(2), cw - ui.dp(2), ch, ui.dp(3));
            // health along its foot
            g.color(healthCol(sm[1] / 100f));
            g.fillRect(bx + ui.dp(2), y + ui.dp(2) + ch - ui.dp(2.5f), Math.max(ui.dp(1), (cw - ui.dp(4)) * sm[1] / 100f), ui.dp(2));
            if (sm[2] > 0) {
                g.color(0xFFFFFFFF);
                g.text(sm[2] > 9 ? "9+" : sm[2] + "", bx + cw / 2, y + ui.dp(13), ui.dp(9.5f), Gfx.ALIGN_CENTER, true);
            }
        }
        if (last != null) {
            String t = (last[0] >= 0 && last[0] < ArmedAI.ACT_NAMES.length ? ArmedAI.ACT_NAMES[last[0]] : "") + ", " + last[2] + " of the dead within reach, "
                    + last[3] + " with them, " + last[4] + " rounds" + (last[5] >= 0 ? ", target " + last[5] / 16 + " m" : "");
            g.color(0xFFB0B8C4);
            g.text(Ui.fit(g, t, wdt, ui.dp(Ui.S - 0.5f), false), x, y + ui.dp(32), ui.dp(Ui.S - 0.5f), Gfx.ALIGN_LEFT, false);
        }
    }

    private void seriesThin(Gfx g, int[] v, float[] t, int n, int max, float x, float y, float w2, float h, int color, float t0, float span) {
        g.color(color);
        float px0 = -1, py0 = 0;
        for (int i = 0; i < n; i++) {
            if (v[i] < 0) {
                px0 = -1;
                continue;
            }
            float px1 = x + w2 * (t[i] - t0) / span, py1 = y + h - v[i] / (float) max * h;
            if (px0 >= 0) WorldView.dashedLine(g, px0, py0, px1, py1, ui.dp(1.1f), ui.dp(3));
            px0 = px1;
            py0 = py1;
        }
    }

    /** The decisive moments, named: towns lost, formations broken, blackouts, strains, the turning point. */
    private float reportMoments(Gfx g, Stats s, float y, float m, int W) {
        java.util.List<Records.Moment> ms = s.rec.decisive(7);
        int tp = s.turningPoint();
        if (ms.isEmpty() && tp < 0) return y;
        y += ui.dp(8);
        g.color(Ui.ACCENT);
        g.text("DECISIVE MOMENTS", m, y + ui.dp(12), ui.dp(13), Gfx.ALIGN_LEFT, true);
        y += ui.dp(20);
        boolean tpSaid = tp < 0;
        for (int k = 0; k <= ms.size(); k++) {
            Records.Moment mo = k < ms.size() ? ms.get(k) : null;
            if (!tpSaid && (mo == null || mo.at > s.tTime[tp])) {
                tpSaid = true;
                y = momentLine(g, s.tTime[tp], "The turning point: the dead grew fastest", y, m, W, 0xFFFFFFFF);
            }
            if (mo != null) y = momentLine(g, mo.at, mo.text, y, m, W, Ui.TEXT);
        }
        return y + ui.dp(6);
    }

    private float momentLine(Gfx g, float at, String text, float y, float m, int W, int col) {
        g.color(0xFFFF8A70);
        g.fillCircle(m + ui.dp(4), y + ui.dp(8), ui.dp(3));
        g.color(Ui.DIM);
        g.text(clockAt(at), m + ui.dp(14), y + ui.dp(12), ui.dp(Ui.S), Gfx.ALIGN_LEFT, false);
        float tx = m + ui.dp(98);
        return y + ui.wrap(Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".", tx, y + ui.dp(12), W - tx - m, ui.dp(12), col, 3) + ui.dp(6);
    }

    /** Each town the dead reached: held or lost, when, what it cost and why; with a map of how it ended. */
    private float reportTowns(Gfx g, Stats s, float y, float m, int W) {
        Records rc = s.rec;
        java.util.List<Region.Settlement> lost = new java.util.ArrayList<Region.Settlement>(), held = new java.util.ArrayList<Region.Settlement>();
        for (Region.Settlement t : w.r.settlements) {
            if (rc.townHow[t.id] != Records.T_NONE) lost.add(t);
            else if (rc.touched(t.id)) held.add(t);
        }
        final Records r = rc;
        java.util.Collections.sort(lost, new java.util.Comparator<Region.Settlement>() {
            public int compare(Region.Settlement a, Region.Settlement b) {
                return Float.compare(r.townAt[a.id], r.townAt[b.id]);
            }
        });
        java.util.Collections.sort(held, new java.util.Comparator<Region.Settlement>() {
            public int compare(Region.Settlement a, Region.Settlement b) {
                return r.townPeakZ[b.id] - r.townPeakZ[a.id];
            }
        });
        y += ui.dp(12);
        g.color(Ui.ACCENT);
        g.text("HOW IT ENDED", m, y + ui.dp(12), ui.dp(13), Gfx.ALIGN_LEFT, true);
        y += ui.dp(20);
        y = outcomeMap(g, m, y, W - m * 2) + ui.dp(10);
        if (lost.isEmpty() && held.isEmpty()) return y;
        g.color(Ui.ACCENT);
        g.text("TOWNS (" + lost.size() + " lost, " + held.size() + " held)", m, y + ui.dp(12), ui.dp(13), Gfx.ALIGN_LEFT, true);
        y += ui.dp(20);
        int shown = 0;
        for (Region.Settlement t : lost) {
            if (shown++ >= 24) break;
            String head = rc.townHow[t.id] == Records.T_FELL ? "fell " : "abandoned ";
            y = townLine(g, t.name, head + clockAt(rc.townAt[t.id]).replace("  ", " "), Ui.ACCENT,
                    WorldView.people(s.lostBySettlement[t.id]) + " lost. Why: " + rc.townWhy[t.id] + ".", y, m, W);
        }
        for (Region.Settlement t : held) {
            if (shown++ >= 36) break;
            y = townLine(g, t.name, "held", Ui.GOOD, "The dead at most " + WorldView.people(rc.townPeakZ[t.id]) + "; "
                    + WorldView.people(s.lostBySettlement[t.id]) + " lost" + (w.command.isAbandoned(t.id) ? "" : "") + ".", y, m, W);
        }
        return y;
    }

    private float townLine(Gfx g, String name, String status, int col, String detail, float y, float m, int W) {
        float ts = ui.dp(12.5f);
        float sw = g.textWidth(status, ui.dp(12), true);
        g.color(Ui.TEXT);
        g.text(Ui.fit(g, name, W - m * 2 - sw - ui.dp(10), ts, true), m, y + ui.dp(13), ts, Gfx.ALIGN_LEFT, true);
        g.color(col);
        g.text(status, W - m, y + ui.dp(13), ui.dp(12), Gfx.ALIGN_RIGHT, true);
        y += ui.dp(17);
        return y + ui.wrap(detail, m + ui.dp(8), y + ui.dp(11), W - m * 2 - ui.dp(8), ui.dp(11.5f), 0xFFB0B8C4, 4) + ui.dp(7);
    }

    /** A map of the outcome: where the dead are now (red), each town reached (green held, red fell, amber abandoned), the formations. */
    private float outcomeMap(Gfx g, float x, float y, float wdt) {
        float s = Math.min(wdt, ui.dp(340));
        float mx = x + (wdt - s) / 2;
        g.color(0xFF141A22);
        g.fillRoundRect(mx - ui.dp(4), y - ui.dp(4), s + ui.dp(8), s + ui.dp(8), ui.dp(8));
        if (view != null && view.map.regionImage != null) g.image(view.map.regionImage, mx, y, s, s, 0.55f);
        float k = s / w.r.px();
        float cs = Region.SECT * Tile.SIZE;
        for (int sec = 0; sec < w.sec.n; sec++) {
            int zz = w.sec.zIn[sec] + w.sp.secZ[sec];
            if (zz <= 0) continue;
            g.color(zz > 500 ? 0xC0E02020 : zz > 50 ? 0x90E02020 : 0x50E02020);
            g.fillRect(mx + (sec % w.sec.sw) * cs * k, y + (sec / w.sec.sw) * cs * k, cs * k + 0.5f, cs * k + 0.5f);
        }
        Records rc = w.stats.rec;
        for (Region.Settlement t : w.r.settlements) {
            boolean lost = rc.townHow[t.id] != Records.T_NONE;
            if (!lost && !rc.touched(t.id)) continue;
            float px = mx + t.cx * 16f * k, py = y + t.cy * 16f * k;
            float rr = ui.dp(3) + (float) Math.sqrt(Math.max(0, w.stats.lostBySettlement[t.id])) * ui.dp(0.04f);
            rr = Math.min(rr, ui.dp(11));
            int col = !lost ? 0xFF50D070 : rc.townHow[t.id] == Records.T_FELL ? 0xFFFF4040 : 0xFFFFB040;
            g.color(0xFF0A0A0A);
            g.fillCircle(px, py, rr + ui.dp(1.2f));
            g.color(col);
            g.fillCircle(px, py, rr);
        }
        for (Records.Unit u : rc.units) {
            float px = mx + u.x * k, py = y + u.y * k, h = ui.dp(3);
            if (u.how == 0) {
                g.color(0xFF0A0A0A);
                g.fillRect(px - h - ui.dp(1), py - h - ui.dp(1), h * 2 + ui.dp(2), h * 2 + ui.dp(2));
                g.color(u.faction == Ents.COP ? 0xFF6A9CFF : WorldView.C_ARMY);
                g.fillRect(px - h, py - h, h * 2, h * 2);
            } else {
                g.color(0xFF0A0A0A);
                g.line(px - h, py - h, px + h, py + h, ui.dp(3));
                g.line(px - h, py + h, px + h, py - h, ui.dp(3));
                g.color(0xFFFF7060);
                g.line(px - h, py - h, px + h, py + h, ui.dp(1.6f));
                g.line(px - h, py + h, px + h, py - h, ui.dp(1.6f));
            }
        }
        y += s + ui.dp(10);
        // the key
        String[] keys = {"held", "fell", "abandoned", "formation", "formation broke", "the dead now"};
        int[] kc = {0xFF50D070, 0xFFFF4040, 0xFFFFB040, WorldView.C_ARMY, 0xFFFF7060, 0x90E02020};
        float kx = x, ts = ui.dp(Ui.S);
        for (int q = 0; q < keys.length; q++) {
            float kw = g.textWidth(keys[q], ts, false) + ui.dp(22);
            if (kx + kw > x + wdt) {
                kx = x;
                y += ui.dp(16);
            }
            g.color(kc[q]);
            if (q < 3) g.fillCircle(kx + ui.dp(5), y + ui.dp(6), ui.dp(4));
            else if (q == 3) g.fillRect(kx + ui.dp(2), y + ui.dp(3), ui.dp(6), ui.dp(6));
            else if (q == 4) {
                g.line(kx + ui.dp(2), y + ui.dp(3), kx + ui.dp(8), y + ui.dp(9), ui.dp(1.6f));
                g.line(kx + ui.dp(2), y + ui.dp(9), kx + ui.dp(8), y + ui.dp(3), ui.dp(1.6f));
            } else g.fillRect(kx + ui.dp(1), y + ui.dp(2), ui.dp(8), ui.dp(8));
            g.color(Ui.DIM);
            g.text(keys[q], kx + ui.dp(13), y + ui.dp(10), ts, Gfx.ALIGN_LEFT, false);
            kx += kw;
        }
        return y + ui.dp(16);
    }

    /** Each formation: held or broke, when, what it cost and why. */
    private float reportFormations(Gfx g, Stats s, float y, float m, int W) {
        java.util.List<Records.Unit> us = s.rec.units;
        if (us.isEmpty()) return y;
        java.util.List<Records.Unit> sorted = new java.util.ArrayList<Records.Unit>(us);
        java.util.Collections.sort(sorted, new java.util.Comparator<Records.Unit>() {
            public int compare(Records.Unit a, Records.Unit b) {
                if ((a.how > 0) != (b.how > 0)) return a.how > 0 ? -1 : 1;
                return b.kills - a.kills;
            }
        });
        y += ui.dp(12);
        int broke = 0;
        for (Records.Unit u : us) if (u.how > 0) broke++;
        g.color(Ui.ACCENT);
        g.text("FORMATIONS (" + (us.size() - broke) + " held, " + broke + " broke)", m, y + ui.dp(12), ui.dp(13), Gfx.ALIGN_LEFT, true);
        y += ui.dp(20);
        int shown = 0;
        for (Records.Unit u : sorted) {
            if (shown++ >= 30) break;
            String status = u.how == 2 ? "destroyed " + clockAt(u.goneAt).replace("  ", " ") : u.how == 1 ? "broke " + clockAt(u.brokeAt).replace("  ", " ") : "held";
            String cost = fmt(u.kills) + " of the dead destroyed, " + fmt(u.losses) + " of " + fmt(u.start) + " lost" + (u.how > 0 ? ". Why: " + u.why + "." : ".");
            y = townLine(g, u.name, status, u.how > 0 ? Ui.ACCENT : Ui.GOOD, cost, y, m, W);
        }
        return y;
    }

    // ------------------------------------------------------------------ personal bests

    /** A game time span ("3h 20m", "2d 5h"), from game minutes. */
    static String duration(float minutes) {
        int h = (int) (minutes / 60), mins = (int) (minutes % 60);
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
        // the best game, kept whole: fewest lives lost, and the faster of two equal ones (never the losses of one
        // game with the time of another)
        if (loss < bestLoss || loss == bestLoss && t < bestTime) {
            pf.setPref(key, loss + "," + t);
            toast("A personal best for this challenge");
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
        int minutes = (int) (w.roles.containedAt >= 0 ? w.roles.containedAt : w.time);
        return resultCodeFor(challengeCodeOf(w), name, losses(), minutes, done);
    }

    static String resultCodeFor(String challenge, String name, int losses, int minutes, boolean done) {
        String body = challenge + "~" + name + "~" + losses + "~" + minutes + "~" + (done ? 1 : 0);
        return "ZC2R~" + body + "~" + check(MINUTES + body);
    }

    /**
     * Codes made since 1.3 count minutes and say so in their check (it's worked out over this and the body); 1.2's
     * counted hours and its check is over the body alone.
     */
    private static final String MINUTES = "m~";

    /** A 1.2 result code (its time in hours, checked the old way) in today's form; null if it isn't one. */
    static String upgradeResultCode(String code) {
        String[] p = code.trim().split("~");
        if (p.length != 7 || !p[0].equals("ZC2R")) return null;
        String body = p[1] + "~" + p[2] + "~" + p[3] + "~" + p[4] + "~" + p[5];
        if (check(MINUTES + body).equals(p[6]) || !check(body).equals(p[6])) return null;
        try {
            return resultCodeFor(p[1], p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]) * 60, p[5].equals("1"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String check(String body) {
        long h = 1469598103934665603L;
        for (int i = 0; i < body.length(); i++) h = (h ^ body.charAt(i)) * 1099511628211L;
        return Long.toString(Math.abs(h % 1679616L), 36);
    }

    /** Adds a friend's result code; false if it's mistyped or for another challenge. */
    boolean addResult(String code) {
        String old = upgradeResultCode(code);
        if (old != null) code = old;
        String[] p = code.trim().split("~");
        if (p.length != 7 || !p[0].equals("ZC2R")) return false;
        String body = p[1] + "~" + p[2] + "~" + p[3] + "~" + p[4] + "~" + p[5];
        if (!check(MINUTES + body).equals(p[6])) return false;
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
                out.add(new String[]{playerName(), b[0], "" + (int) Float.parseFloat(b[1]), "1"});
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
            return "Your best: " + WorldView.people(Integer.parseInt(v[0])) + " lives lost, over in " + duration(Float.parseFloat(v[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void series(Gfx g, int[] v, float[] t, int n, int max, float x, float y, float w2, float h, int color, float t0, float span) {
        g.color(color);
        float px0 = -1, py0 = 0;
        for (int i = 0; i < n; i++) {
            if (v[i] < 0) {
                // not known (a sample from an older save)
                px0 = -1;
                continue;
            }
            float px1 = x + w2 * (t[i] - t0) / span, py1 = y + h - v[i] / (float) max * h;
            if (px0 >= 0) g.line(px0, py0, px1, py1, ui.dp(1.8f));
            px0 = px1;
            py0 = py1;
        }
    }

    // ------------------------------------------------------------------ buttons

    private void onButton(String id) {
        pf.playSound("click", 0.4f, 0); // (the mixer applies the sound setting)
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
                        if (applyCode(text)) toast(codeNote != null ? codeNote : "Challenge loaded");
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
            case "olddelete": {
                String base = tooOld.endsWith(".sav") ? tooOld.substring(0, tooOld.length() - 4) : tooOld;
                pf.delete(tooOld);
                pf.setPref("info." + base, "");
                clearError();
                toast("Deleted");
                return;
            }
            case "sharecode":
                ask("This game's challenge code: share it", challengeCodeOf(w), new Platform.TextAnswer() {
                    public void answer(String text) {
                    }
                });
                return;
            case "savereplay": dir.saveReplay(); return;
            case "timelapse": back = screen; screen = S_TIMELAPSE; dir.openTimelapse(); return;
            case "resume":
                if (menuCam && screen == S_MENU) {
                    cam.cx = keptX;
                    cam.cy = keptY;
                    cam.zoom = keptZ;
                }
                menuCam = false;
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
            case "speed": skipping = false; speedIdx = speedIdx >= maxSpeedIdx() ? 0 : speedIdx + 1; paused = false; achieved = 0; return;
            case "skip":
                if (skipping) {
                    skipping = false;
                    speedIdx = 0;
                } else if (deadNear(SKIP_SAFE)) toast("Not with the dead this close");
                else {
                    skipping = true;
                    paused = false;
                    achieved = 0;
                }
                return;
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
            case "n:lower": if (w.gov.level > 0) Actions.level(w, w.gov.level - 1); return;
            // lifting martial law goes back to a state of emergency; ending mobilisation back to martial law
            case "n:martial": Actions.level(w, w.gov.level < 3 ? 3 : 2); return;
            case "n:mobilise": Actions.level(w, w.gov.level < 4 ? 4 : 3); return;
            case "m:jump": mapToggle(); return;
            case "aim:go": fireAimed(); return;
            case "aim:no": view.aimX = -1; return;
            case "legend": legendOpen = !legendOpen; return;
            case "statsp": statsOpen = !statsOpen; pf.setPref("statsOpen", "" + statsOpen); return;
            case "alertgo":
                if (alertText != null) {
                    cam.follow = -1;
                    cam.flyTo(alertX, alertY, Math.max(cam.zoom, w.strategic ? 0.12f : 0.35f));
                    alertShow = 0;
                }
                return;
            case "alertx": alertShow = 0; return;
            case "rolesheet": roleSheet = true; return;
            case "rolesheet:close": roleSheet = false; return;
            case "golive": cam.follow = -1; cam.flyTo(startX, startY, Math.max(cam.zoom, 0.6f)); return;
            case "o:release": Actions.release(w, inspSquad); inspSquad = null; return;
            case "o:frelease":
                if (inspFormation != null) toast(Forces.shortName(inspFormation) + ": the general commands it");
                Actions.releaseFormation(w, inspFormation);
                inspFormation = null;
                return;
            case "o:ftake":
                // taking it back: it holds where it is, under your command, until you send it somewhere
                if (inspFormation != null && inspFormation.alive) {
                    Actions.formation(w, inspFormation, inspFormation.hq.x, inspFormation.hq.y);
                    toast(Forces.shortName(inspFormation) + " waits for your orders");
                    tool = "orders";
                }
                return;
            case "o:general":
                Actions.generalCommands(w, !w.forces.generalCommands);
                return;
            case "o:fdone": inspFormation = null; return;
            case "pc:cordon": Actions.precinctCordon(w, !w.roles.cordon); return;
            case "pc:evac": Actions.precinctEvac(w, !w.roles.districtEvac); return;
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
            view.aimX = -1;
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
        else if (id.startsWith("rolepick:")) {
            ngRole = Integer.parseInt(id.substring(9));
            roleSheet = false;
        } else if (id.startsWith("alert:")) {
            int k = Integer.parseInt(id.substring(6));
            for (Dispatch.Incident in : w.disp.incidents)
                if (in.id == k) {
                    cam.follow = -1;
                    cam.flyTo(in.x, in.y, Math.max(cam.zoom, 0.5f));
                }
        }
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
            case "autoslow": s.autoSlow = (s.autoSlow + 2) % 3; break;
            case "minimap": s.minimap = !s.minimap; break;
            case "vibrate":
                s.vibrate = !s.vibrate;
                if (s.vibrate) pf.vibrate(40);
                break;
            case "mono": s.mono = !s.mono; break;
            case "captions": s.captions = !s.captions; break;
            case "unitnames": s.unitNames = !s.unitNames; break;
            case "cb": s.colourBlind = !s.colourBlind; break;
            case "shake": s.shake = !s.shake; break;
            case "effects": s.effects = (s.effects + 1) % 3; break;
            case "fps": s.fpsCap = s.fpsCap == 30 ? 60 : 30; break;
            case "debug": s.debug = !s.debug; break;
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
        if (cam.zoom > cam.countryZoom() * 1.3f) {
            prevX = cam.cx;
            prevY = cam.cy;
            prevZoom = cam.zoom;
            cam.follow = -1;
            cam.showAll();
        } else cam.flyTo(prevX, prevY, Math.max(prevZoom, 0.3f));
    }

    // ------------------------------------------------------------------ saving

    void saveTo(String name) {
        waitForSave();
        try {
            SaveIO.Snapshot snap = SaveIO.snapshot(w);
            writeSave(name, snap);
            pf.setPref("info." + name, saveInfo());
            if (!name.equals("autosave")) toast("Saved");
        } catch (Exception e) {
            toast("Couldn't save: " + e.getMessage());
        }
    }

    private String saveInfo() {
        return w.r.name + ", " + w.clock() + (w.scenario != null ? ", " + w.scenario.name : "") + ". " + (w.totalZombies() == 0 ? "No dead walking" : WorldView.people(w.totalZombies()) + " dead walking") + ", " + WorldView.people(w.livingCivilians()) + " alive";
    }

    /** Compresses a snapshot into name.tmp, then puts it in place of name.sav (the old save goes only once the new one is whole). */
    private void writeSave(String name, SaveIO.Snapshot snap) throws java.io.IOException {
        OutputStream out = pf.openWrite(name + ".tmp");
        try {
            SaveIO.compress(snap, out);
        } finally {
            out.close();
        }
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
    }

    /** The autosave being compressed and written in the background, if any. */
    private Thread saver;
    /** How long the last autosave held up the game's thread (the snapshot), in ms: for the tests. */
    float lastAutosaveMs;

    /** Waits for an autosave still being written (before another save, or a load, touches the files). */
    void waitForSave() {
        Thread t = saver;
        if (t == null) return;
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        saver = null;
    }

    /**
     * The autosave: the world is copied on the game's thread (a snapshot, the only part that has to stop the
     * game), then compressed and written on a background thread, so it doesn't hitch. The file is the same,
     * byte for byte, as a save made all at once.
     */
    public void autosave() {
        sinceAutosave = 0;
        if (w == null || screen == S_LOADING || error != null || replay != null) return;
        waitForSave();
        final SaveIO.Snapshot snap;
        final String info;
        long t0 = System.nanoTime();
        try {
            snap = SaveIO.snapshot(w);
            info = saveInfo();
        } catch (Exception e) {
            toast("Couldn't save: " + e.getMessage());
            return;
        }
        lastAutosaveMs = (System.nanoTime() - t0) / 1e6f;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    writeSave("autosave", snap);
                    post(new Runnable() {
                        public void run() {
                            pf.setPref("info.autosave", info);
                        }
                    });
                } catch (final Exception e) {
                    post(new Runnable() {
                        public void run() {
                            toast("Couldn't save: " + e.getMessage());
                        }
                    });
                }
            }
        }, "autosave");
        t.setPriority(Thread.MIN_PRIORITY);
        saver = t;
        t.start();
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
    /**
     * A person (the officer, the survivor) can't be fast-forwarded past, which folds everyone into counts; instead,
     * while none of the dead are near, time can be skipped ahead with everyone still in person, as fast as the device
     * steps. It stops by itself when the dead come close.
     */
    boolean skipping;
    private static final float SKIP_SAFE = 900;

    /** Are any of the dead walking within this distance (world px) of the person you play? */
    private boolean deadNear(float r) {
        if (w == null || w.playerEnt < 0) return false;
        Ents e = w.e;
        float px = e.x[w.playerEnt], py = e.y[w.playerEnt], r2 = r * r;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.FREE || e.kind[i] != Ents.ZOMBIE) continue;
            float dx = e.x[i] - px, dy = e.y[i] - py;
            if (dx * dx + dy * dy < r2) return true;
        }
        return false;
    }

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
        if (skipping) return achieved > 0 ? "~" + Math.round(achieved) + "x" : "Skip";
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
        return screen == S_REPORT || screen == S_RADIO || screen == S_HELP || screen == S_NOTES || screen == S_NEW || screen == S_REPLAYS || screen == S_SETTINGS;
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
        view.brushX = x;
        view.brushY = y;
        view.brushR = brushRadius();
        view.brushT = view.time;
        if (tool.equals("erase")) {
            view.brushColor = 0xC0E8E8E8;
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
        view.brushColor = 0xC0E04040;
    }

    /** A tap on the map: the current tool acts there, or it shows what's there. */
    private void tap(float sx, float sy) {
        float x = cam.toWorldX(sx), y = cam.toWorldY(sy);
        rippleX = sx;
        rippleY = sy;
        rippleT = 0;
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
                // aim first: a reticle shows where it lands, and the player confirms
                if (strikeMode.equals("bridge") && Tools.bridgeAt(w, x, y) < 0) {
                    toast("Tap a bridge on a road");
                    return;
                }
                view.aimX = x;
                view.aimY = y;
                view.aimR = strikeMode.equals("bridge") ? 60 : strikeMode.equals("art") ? 110 : 160;
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
                    ping(x, y);
                    inspSquad = s;
                    toast(s.label + " on the way");
                }
                return;
            }
            case "orders": {
                if (w.roles.commandsFormations()) {
                    Forces.Formation fm = w.forces.formationAt(x, y, Math.max(60, ui.dp(24) / cam.zoom));
                    if (fm != null && fm != inspFormation) {
                        inspFormation = fm;
                        inspSquad = null;
                        return;
                    }
                    if (inspFormation != null && inspFormation.alive) {
                        if (Actions.formation(w, inspFormation, x, y)) {
                            toast(inspFormation.name + " moving");
                            ping(x, y);
                        }
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
                    ping(x, y);
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

    /** A flag where an order sends someone, for a moment. */
    private void ping(float x, float y) {
        pingX = x;
        pingY = y;
        pingT = 0;
    }

    /** Carries out the aimed strike. */
    private void fireAimed() {
        float x = view.aimX, y = view.aimY;
        view.aimX = -1;
        if (strikeMode.equals("bridge")) {
            int e = Tools.bridgeAt(w, x, y);
            if (e < 0) toast("Tap a bridge on a road");
            else Actions.bridge(w, e);
        } else if (strikeMode.equals("art")) {
            if (!Actions.artillery(w, x, y)) toast("No guns in range (they need a battery holding within reach)");
        } else if (!Actions.strike(w, x, y)) toast("No air strikes left");
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
        // a formation's symbol, at any zoom (before the base it may stand on)
        Forces.Formation fm = w.forces.formationAt(x, y, ui.dp(18) / cam.zoom);
        if (fm != null) {
            inspFormation = fm;
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

    /** Pausing the app (phone call, home button): stop and keep a save, finished before returning (the app may be stopped next). */
    public void onPause() {
        autosave();
        waitForSave();
    }
}
