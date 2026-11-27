package com.instantaxtion.zcs2;

/**
 * The guided first game and the first-time tips.
 *
 * The tour plays a small fixed game (a test map of a city of over a million and its towns, an open sandbox, as God)
 * through the real Game: each step points at the control it means with a coach mark (a short card, the control
 * ringed), and waits for the player to do it, watching the game's own state (the camera, the tool, the inputs the
 * director recorded, the screen). Skip moves on, Back goes back, the cross stops it; where it got to is kept, so the
 * menu offers to pick it up again. It never blocks play: only the card takes taps. Its game is never autosaved or
 * counted as a best, and when it ends the world is closed and the choices it changed are put back.
 *
 * The tips are one line, once, the first time each tool is picked (remembered in the prefs), and can be turned off in
 * Settings.
 */
final class Tutorial {
    private final Game g0;

    static final int WELCOME = 0, PAN = 1, ZOOM_OUT = 2, ZOOM_IN = 3, INSPECT = 4, PAINT = 5, POLICE = 6, ORDER = 7,
            STRIKE = 8, FAST = 9, SLOW = 10, LAYERS = 11, ROLES = 12, NUMBERS = 13, REPORT = 14, DONE = 15, STEPS = 16;
    static final String[] TITLES = {"Welcome", "Move round", "Out to the whole country", "Back to the street", "Tap to look",
            "Paint the dead", "Send in the police", "Order a squad", "An air strike", "Fast-forward", "It slows down for you",
            "Map layers", "Who you play", "The numbers", "The report", "You're ready"};
    /** The tour's map: a quick test map (a city of over a million and the towns round it), always the same one. */
    static final int MAP = 1536;
    static final long SEED = 4242;
    private static final String[] ROLE_SHORT = {"God", "President", "Military", "Incident", "Precinct", "Squad", "Officer", "Survivor"};

    /** The tour is on: its game being built, or played. */
    boolean active;
    /** The step shown. */
    int step;
    /** The tour's own game (null while it's built). */
    World world;
    private boolean building;
    /** The step's action is done (it moves on by itself a moment later, or waits for Next). */
    boolean met;
    private float metT, t;
    /** Where the step started from, to see what the player did since. */
    private float baseX, baseY;
    private int baseEvents, baseOverlay, baseSquads;
    private boolean baseStats;
    /** The role tapped in the roles step, or -1. */
    int rolePicked = -1;
    /** The player's choices as they were before the tour (put back at the end). */
    private Object[] kept;

    Tutorial(Game game) {
        g0 = game;
    }

    // ------------------------------------------------------------------ starting and ending

    /** Where a tour left off (0 for none, or when it was finished). */
    int savedStep() {
        if (Boolean.parseBoolean(g0.pf.pref("tut.done", "false")) && "0".equals(g0.pf.pref("tut.step", "0"))) return 0;
        try {
            return Math.max(0, Math.min(STEPS - 1, Integer.parseInt(g0.pf.pref("tut.step", "0"))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The menu's label for the tour. */
    String menuLabel() {
        int s = savedStep();
        return s > 0 ? "Resume the tutorial (" + (s + 1) + " of " + STEPS + ")" : "Learn to play";
    }

    /** Starts the tour (from where it left off, or from the start), building its game in the background. */
    void start(boolean fromStart) {
        g0.pf.setPref("tut.offered", "true");
        if (active) return;
        // a game in progress is kept first: the tour replaces it, and Continue brings it back
        if (g0.w != null && g0.replay == null) {
            g0.autosave();
            g0.waitForSave();
        }
        step = fromStart ? 0 : savedStep();
        kept = g0.tutorialKeep();
        active = true;
        building = true;
        world = null;
        Content.Scenario base = g0.content.scenario("sandbox");
        Content.Scenario sc = new Content.Scenario();
        sc.id = base != null ? base.id : "sandbox";
        sc.name = "Learning to play";
        sc.mode = "sandbox";
        sc.origin = "none";
        sc.goal = "";
        sc.desc = "The tutorial's own small country.";
        g0.startTutorialWorld(sc, MAP, SEED);
    }

    /** Is this the tour's game (never autosaved, never a best)? */
    boolean owns(World w) {
        return active && w != null && w == world;
    }

    /** Ends the tour: where it got to is kept (or that it was finished), its game closed, the choices put back. */
    void end(boolean finished) {
        if (!active) return;
        g0.pf.setPref("tut.step", finished ? "0" : "" + step);
        if (finished) g0.pf.setPref("tut.done", "true");
        active = false;
        building = false;
        World tw = world;
        world = null;
        if (tw != null && g0.w == tw) g0.closeTutorialWorld();
        if (kept != null) g0.tutorialRestore(kept);
        kept = null;
    }

    /** The tour stopped without ending properly (its load cancelled, another game loaded over it). */
    private void drop(boolean restore) {
        g0.pf.setPref("tut.step", "" + step);
        active = false;
        building = false;
        world = null;
        if (restore && kept != null) g0.tutorialRestore(kept);
        kept = null;
    }

    void go(int s) {
        step = Math.max(0, Math.min(STEPS - 1, s));
        g0.pf.setPref("tut.step", "" + step);
        enter();
    }

    /** A step starts: what the game is now, to see what the player does. */
    private void enter() {
        met = false;
        metT = 0;
        t = 0;
        rolePicked = -1;
        World w = g0.w;
        if (w == null) return;
        if (step < REPORT && g0.screen != Game.S_PLAY && (g0.screen == Game.S_PAUSE || g0.screen == Game.S_REPORT)) g0.screen = Game.S_PLAY;
        baseX = g0.cam.cx;
        baseY = g0.cam.cy;
        baseEvents = w.director.events.size();
        baseOverlay = g0.view != null ? g0.view.overlay : 0;
        baseStats = g0.statsOpen;
        baseSquads = squads();
    }

    // ------------------------------------------------------------------ what each step waits for

    /** Has the player done what the step asks? */
    private boolean done() {
        World w = g0.w;
        Camera cam = g0.cam;
        switch (step) {
            case PAN: {
                float dx = (cam.cx - baseX) * cam.zoom, dy = (cam.cy - baseY) * cam.zoom;
                return dx * dx + dy * dy > g0.ui.dp(90) * g0.ui.dp(90);
            }
            case ZOOM_OUT: return cam.zoom <= cam.countryZoom() * 1.35f;
            case ZOOM_IN: return cam.zoom >= 0.55f;
            case INSPECT: return g0.inspectorOpen();
            case PAINT: return recorded(Actions.PAINT, Actions.MASS, Actions.HORDE);
            case POLICE: return recorded(Actions.SQUAD, Actions.TEMPLATE, Actions.VEHICLE) && squads() > baseSquads; // (placed, not "can't stand there")
            case ORDER: return recorded(Actions.ORDER, Actions.SQ_ORDER, Actions.FORM_ORDER);
            case STRIKE: return recorded(Actions.STRIKE, Actions.ARTILLERY, Actions.BRIDGE);
            case FAST: return Game.SPEEDS[g0.speedIdx] >= 60;
            case LAYERS: return g0.view != null && g0.view.overlay != baseOverlay;
            case ROLES: return rolePicked >= 0;
            case NUMBERS: return g0.statsOpen != baseStats;
            case REPORT: return g0.screen == Game.S_REPORT;
            default: return false; // the welcome, the slow-down and the end wait for Next
        }
    }

    /** Steps that wait for Next even when done (there's something to read). */
    private boolean waitsForNext() {
        return step == WELCOME || step == SLOW || step == ROLES || step == DONE;
    }

    private boolean recorded(int... kinds) {
        java.util.List<Director.Event> ev = g0.w.director.events;
        for (int k = baseEvents; k < ev.size(); k++)
            for (int kind : kinds) if (ev.get(k).kind == kind) return true;
        return false;
    }

    private int squads() {
        int n = 0;
        for (Squads.Squad s : g0.w.sq.list) if (s.alive && s.n > 0) n++;
        return n;
    }

    private boolean squadOut() {
        // (one the player put down in this game)
        for (Director.Event e : g0.w.director.events) if (e.kind == Actions.SQUAD || e.kind == Actions.TEMPLATE) return true;
        return false;
    }

    /** What the step says now (it changes as the player goes: pick the tool, then tap...). */
    String text() {
        String tool = g0.tool;
        switch (step) {
            case WELCOME: return "A small country of your own to learn on: a city of over a million and the towns round it, going about its day. Each step shows you one thing and waits for you to do it. Skip moves on, Back goes back, and the cross stops (the menu picks it up again).";
            case PAN: return "Drag the map with one finger to look round the city.";
            case ZOOM_OUT: return "Pinch (or turn the mouse wheel) to zoom out until you see the whole country. Close in it's every person and car; further out, dots, and a red stain where the dead are.";
            case ZOOM_IN: return "Now spread two fingers to zoom back in, close enough to see people in the street.";
            case INSPECT: return !tool.equals("look") ? "Pick the Look tool, then tap anyone or anything." : "Tap anyone or anything (a person, a car, a building, a town) to see who or what it is.";
            case PAINT: return !tool.equals("dead") ? "Pick the Dead tool." : "Choose a kind and how many above the tools, then drag across the map to paint the dead near people.";
            case POLICE: return !tool.equals("police") ? "Pick the Police tool: every officer, vehicle and squad in the game." : "Tap the map near the dead to put a squad there.";
            case ORDER:
                if (!squadOut()) return "First put a squad down with the Police tool.";
                if (!tool.equals("orders")) return "Pick the Order tool.";
                if (g0.inspSquad == null) return "Tap your squad (the blue symbol) to pick it.";
                return "Now tap where they should go: a flag marks the spot. Above the tools: routes, cordons, falling back and stance.";
            case STRIKE:
                if (!tool.equals("strike")) return "Pick the Strike tool.";
                if (!g0.aimShown()) return "Tap where it should land: a strike is aimed first.";
                return "The ring shows where it lands and how wide. Fire to confirm, or Cancel to call it off.";
            case FAST: return "Tap the speed button: 1x to 16x, then fast-forward at an hour, six hours and a day a second. Go up to 1h/s.";
            case SLOW: return "Fast-forward folds everyone into numbers, and drops back to 1x by itself for big events (a horde near a town, a town lost, a new strain), with a banner to go and look. Settings: Slow down for big events.";
            case LAYERS: return !tool.equals("map") ? "Pick the Map tool." : "Pick a layer: infection, utilities (power, water, phones), supplies, trust and fear, forces, deaths. The first button jumps to the whole country and back.";
            case ROLES: return rolePicked >= 0 ? Roles.NAMES[rolePicked] + ": " + Roles.DESC[rolePicked] + " Choose it on the New Game screen."
                    : "This tour plays God, with every tool. A game can put you anywhere, from the President down to one survivor. Tap a role to see what it does.";
            case NUMBERS: return "The chart button opens the numbers: kills on each side, kills a minute, the most of the dead at once, open calls, help from outside and the best fighters.";
            case REPORT: return g0.screen == Game.S_PAUSE ? "Now tap Report." : "The menu button pauses the game. Open it, then Report: the story of the outbreak so far.";
            default: return "That's the basics. The report has the timeline, the turning point, where people were lost and the fallen. How to play explains the rest, and Settings and Help bring this tour back.";
        }
    }

    /** The control the step points at, or null (the map itself). */
    String target() {
        String tool = g0.tool;
        switch (step) {
            case INSPECT: return tool.equals("look") ? null : "tool:look";
            case PAINT: return tool.equals("dead") ? null : "tool:dead";
            case POLICE: return tool.equals("police") ? null : "tool:police";
            case ORDER: return !squadOut() ? (tool.equals("police") ? null : "tool:police") : tool.equals("orders") ? null : "tool:orders";
            case STRIKE: return !tool.equals("strike") ? "tool:strike" : g0.aimShown() ? "aim:go" : null;
            case FAST:
            case SLOW: return "speed";
            case LAYERS: return tool.equals("map") ? "ov:5" : "tool:map";
            case NUMBERS: return "statsp";
            case REPORT: return g0.screen == Game.S_PAUSE ? "report" : "pause";
            default: return null;
        }
    }

    /** A gesture drawn over the map for the steps that are done on the map: 1 drag, 2 pinch in, 3 spread, 4 tap, 5 paint. */
    private int gesture() {
        switch (step) {
            case PAN: return 1;
            case ZOOM_OUT: return 2;
            case ZOOM_IN: return 3;
            case INSPECT: return g0.tool.equals("look") ? 4 : 0;
            case PAINT: return g0.tool.equals("dead") ? 5 : 0;
            case POLICE: return g0.tool.equals("police") ? 4 : 0;
            case ORDER: return g0.tool.equals("orders") ? 4 : 0;
            case STRIKE: return g0.tool.equals("strike") && !g0.aimShown() ? 4 : 0;
            default: return 0;
        }
    }

    // ------------------------------------------------------------------ each frame

    /** After the screen is drawn: the coach mark over the tour's game, the first-time offer on the menu, the tips. */
    void frame(Gfx g, float dt) {
        if (!active) {
            if (g0.screen == Game.S_MENU) offer(g);
            else if (g0.screen == Game.S_HELP) helpButton(g);
            tips(g, dt);
            return;
        }
        if (building) {
            if (g0.screen == Game.S_PLAY && g0.w != null) {
                world = g0.w;
                building = false;
                g0.tutorialBegun();
                enter();
            } else if (g0.screen != Game.S_LOADING) drop(true); // cancelled with back, or it couldn't be built
            return;
        }
        if (g0.w != world) {
            // another game took its place (a save loaded from the pause menu): the tour stops there
            drop(false);
            return;
        }
        if (g0.screen == Game.S_MENU) {
            // quit to the menu: the tour stops (Resume picks it up), its game isn't kept
            end(false);
            return;
        }
        if (g0.screen != Game.S_PLAY && g0.screen != Game.S_PAUSE && g0.screen != Game.S_REPORT) return;
        t += dt;
        if (!met && done()) met = true;
        if (met) {
            metT += dt;
            if (!waitsForNext() && metT >= 1.1f) go(step + 1);
        }
        coach(g);
    }

    /** Taps on the tour's controls ("tut:..."). */
    void onButton(String id) {
        switch (id) {
            case "tut:start": start(false); return;
            case "tut:restart": start(true); return;
            case "tut:offer:no": g0.pf.setPref("tut.offered", "true"); return;
            case "tut:tipx": tipT = 0; return;
            default:
        }
        if (!active) return;
        if (id.startsWith("tut:role:")) {
            rolePicked = Integer.parseInt(id.substring(9));
            return;
        }
        switch (id) {
            case "tut:next":
                if (step == SLOW) g0.speedIdx = 0; // (back to 1x for the rest of the tour)
                if (step == DONE) end(true);
                else go(step + 1);
                return;
            case "tut:skip":
                if (step == SLOW || step == FAST) g0.speedIdx = 0;
                if (step == DONE) end(true);
                else go(step + 1);
                return;
            case "tut:back": go(step - 1); return;
            case "tut:leave": end(false); return;
            case "tut:new": end(true); g0.screen = Game.S_NEW; g0.scroll = 0; return;
            default:
        }
    }

    // ------------------------------------------------------------------ the coach mark

    private static final int RING = 0xFFFFD040;

    private void coach(Gfx g) {
        Ui ui = g0.ui;
        int W = g.width(), H = g.height();
        String tid = target();
        float[] r = tid != null ? ui.rect(tid) : null;
        boolean onMap = g0.screen == Game.S_PLAY;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 5);
        if (r != null) {
            float e = ui.dp(4), x0 = r[0] - e, y0 = r[1] - e, x1 = r[0] + r[2] + e, y1 = r[1] + r[3] + e;
            if (onMap) {
                // the rest a little darker, so the eye goes to it (taps still go through to everything)
                g.color(0x50000000);
                g.fillRect(0, 0, W, y0);
                g.fillRect(0, y1, W, H - y1);
                g.fillRect(0, y0, x0, y1 - y0);
                g.fillRect(x1, y0, W - x1, y1 - y0);
            }
            g.color(((int) (150 + 105 * pulse) << 24) | (RING & 0xFFFFFF));
            g.strokeRect(x0, y0, x1 - x0, y1 - y0, ui.dp(2.5f));
        } else if (onMap && !met) gestureHint(g);
        // the card: away from what it points at
        float cw = Math.min(W - ui.dp(24), ui.dp(430)), m = ui.dp(14);
        float tw = cw - m * 2;
        String txt = text();
        float ts = ui.dp(13);
        int lines = Math.min(6, Ui.lines(g, txt, tw, ts));
        boolean roles = step == ROLES;
        float rolesH = roles ? ui.dp(80) : 0;
        float ch = ui.dp(58) + lines * ts * 1.3f + rolesH + ui.dp(54);
        float left = onMap ? g0.freeL : 0, right = onMap ? g0.freeR : W;
        float cx = Math.max(ui.dp(12), Math.min(W - cw - ui.dp(12), (left + right - cw) / 2));
        boolean top;
        if (!onMap) top = false;
        else if (r != null) top = r[1] + r[3] / 2 > H * 0.45f;
        else top = true;
        float cy = top ? (onMap ? g0.freeT : 0) + ui.dp(8) : (onMap ? g0.freeB : H) - ch - ui.dp(10);
        if (!onMap) cy = H - ch - ui.dp(16);
        if (r != null && !top && cy < r[1] + r[3] + ui.dp(8) && r[1] > ch + ui.dp(20)) cy = r[1] - ch - ui.dp(12); // (keep off it)
        cy = Math.max(ui.dp(4), cy);
        if (r != null) pointer(g, r, cx, cy, cw, ch, top, pulse);
        ui.panel(cx, cy, cw, ch);
        g.color(RING);
        g.fillRoundRect(cx, cy + ui.dp(10), ui.dp(4), ch - ui.dp(20), ui.dp(2));
        // the step and the dots
        g.color(Ui.DIM);
        g.text("TUTORIAL  " + (step + 1) + " / " + STEPS, cx + m, cy + ui.dp(18), ui.dp(Ui.S), Gfx.ALIGN_LEFT, true);
        float dx = cx + m + g.textWidth("TUTORIAL  " + (step + 1) + " / " + STEPS, ui.dp(Ui.S), true) + ui.dp(10);
        float dotGap = Math.min(ui.dp(9), (cx + cw - ui.dp(48) - dx) / STEPS);
        for (int k = 0; k < STEPS; k++) {
            g.color(k < step ? Ui.GOOD : k == step ? RING : 0xFF3A4452);
            g.fillCircle(dx + k * dotGap + dotGap / 2, cy + ui.dp(14), Math.min(ui.dp(3), dotGap * 0.36f));
        }
        ui.button("tut:leave", cx + cw - ui.dp(40), cy + ui.dp(4), ui.dp(34), ui.dp(26), "✕", false, 12);
        g.color(Ui.TEXT);
        g.text(Ui.fit(g, TITLES[step], tw - ui.dp(70), ui.dp(Ui.L + 1), true), cx + m, cy + ui.dp(42), ui.dp(Ui.L + 1), Gfx.ALIGN_LEFT, true);
        if (met && !waitsForNext()) {
            g.color(Ui.GOOD);
            g.text("✓ Done", cx + cw - m, cy + ui.dp(42), ui.dp(Ui.M), Gfx.ALIGN_RIGHT, true);
        }
        float y = cy + ui.dp(58);
        ui.wrap(txt, cx + m, y + ts * 0.2f, tw, ts, 0xFFD8DEE6, 6);
        y += lines * ts * 1.3f + ui.dp(6);
        if (roles) {
            float bw = (tw - ui.dp(6) * 3) / 4, bh = ui.dp(32);
            for (int k = 0; k < ROLE_SHORT.length; k++)
                ui.button("tut:role:" + k, cx + m + (k % 4) * (bw + ui.dp(6)), y + (k / 4) * (bh + ui.dp(6)), bw, bh, ROLE_SHORT[k], k == rolePicked, 12);
            y += rolesH;
        }
        // Back, Skip, and Next where the step waits for it
        float bh = ui.dp(38), by = cy + ch - bh - ui.dp(10), gap = ui.dp(8);
        float bw = (tw - gap * 2) / 3;
        if (step > 0) ui.button("tut:back", cx + m, by, bw, bh, "Back", false, 13);
        if (step == DONE) {
            ui.button("tut:new", cx + m + bw + gap, by, bw, bh, "New game", false, 13);
            ui.bigButton("tut:next", cx + m + (bw + gap) * 2, by, bw, bh, "Finish", Ui.ON);
        } else if (waitsForNext() && (step != ROLES || met)) {
            ui.button("tut:skip", cx + m + bw + gap, by, bw, bh, "Skip", false, 13);
            ui.bigButton("tut:next", cx + m + (bw + gap) * 2, by, bw, bh, "Next", Ui.ON);
        } else ui.button("tut:skip", cx + m + (bw + gap) * 2, by, bw, bh, met ? "Next" : "Skip", false, 13);
    }

    /** A small arrow from the card towards the control, bobbing. */
    private void pointer(Gfx g, float[] r, float cx, float cy, float cw, float ch, boolean cardTop, float pulse) {
        Ui ui = g0.ui;
        float tx = r[0] + r[2] / 2, s = ui.dp(9), bob = ui.dp(4) * pulse;
        boolean below = r[1] > cy + ch; // the control is under the card
        boolean above = r[1] + r[3] < cy;
        if (!below && !above) return;
        float ay = below ? r[1] - ui.dp(8) - bob : r[1] + r[3] + ui.dp(8) + bob;
        float dir = below ? 1 : -1;
        g.color(RING);
        g.fillPoly(new float[]{tx - s, ay - dir * s * 1.4f, tx + s, ay - dir * s * 1.4f, tx, ay}, 3);
    }

    /** One finger or two, moving the way the step wants, in the middle of the map. */
    private void gestureHint(Gfx g) {
        int kind = gesture();
        if (kind == 0) return;
        Ui ui = g0.ui;
        float mx = (g0.freeL + g0.freeR) / 2, my = g0.freeT + (g0.freeB - g0.freeT) * 0.58f;
        float ph = (t % 1.6f) / 1.6f, ease = ph * ph * (3 - 2 * ph);
        float r = ui.dp(15), span = ui.dp(70);
        float a = ph < 0.85f ? 1 : (1 - ph) / 0.15f;
        int col = ((int) (a * 170) << 24) | 0xFFFFFF;
        switch (kind) {
            case 1: finger(g, mx - span / 2 + span * ease, my, r, col); break;
            case 2:
                finger(g, mx - span + span * 0.7f * ease, my - span + span * 0.7f * ease, r, col);
                finger(g, mx + span - span * 0.7f * ease, my + span - span * 0.7f * ease, r, col);
                break;
            case 3:
                finger(g, mx - span * 0.3f - span * 0.7f * ease, my - span * 0.3f - span * 0.7f * ease, r, col);
                finger(g, mx + span * 0.3f + span * 0.7f * ease, my + span * 0.3f + span * 0.7f * ease, r, col);
                break;
            case 4: {
                float k = ph < 0.3f ? ph / 0.3f : 1;
                g.color(((int) ((1 - k) * 200) << 24) | 0xFFFFFF);
                g.strokeCircle(mx, my, r + ui.dp(18) * k, ui.dp(2));
                finger(g, mx, my, r, col);
                break;
            }
            default: {
                float x = mx - span + 2 * span * ease;
                finger(g, x, my + (float) Math.sin(ease * Math.PI * 3) * ui.dp(24), r, col);
            }
        }
    }

    private void finger(Gfx g, float x, float y, float r, int col) {
        g.color(((col >>> 24) / 3 << 24) | 0xFFFFFF);
        g.fillCircle(x, y, r * 1.5f);
        g.color(col);
        g.fillCircle(x, y, r);
    }

    // ------------------------------------------------------------------ the menu's offer, Help's button

    /** On the menu the first time the game is opened: offer the tour (once; it's also in the menu, Settings and Help). */
    private void offer(Gfx g) {
        if (g0.w != null || g0.pf.pref("tut.offered", null) != null || g0.pf.exists("autosave.sav")) return;
        Ui ui = g0.ui;
        int W = g.width(), H = g.height();
        float[] nb = ui.rect("new");
        float cw = Math.min(W - ui.dp(48), ui.dp(340)), ch = ui.dp(124);
        float cx = nb != null ? nb[0] + nb[2] / 2 - cw / 2 : (W - cw) / 2;
        float cy = nb != null ? nb[1] - ch - ui.dp(18) : H * 0.4f;
        ui.panel(cx, cy, cw, ch);
        g.color(RING);
        g.fillRoundRect(cx, cy + ui.dp(10), ui.dp(4), ch - ui.dp(20), ui.dp(2));
        float m = ui.dp(16);
        g.color(Ui.TEXT);
        g.text("New here?", cx + m, cy + ui.dp(26), ui.dp(Ui.L + 1), Gfx.ALIGN_LEFT, true);
        ui.wrap("A short guided game shows you the controls, a step at a time.", cx + m, cy + ui.dp(46), cw - m * 2, ui.dp(Ui.M), Ui.DIM, 2);
        float bw = (cw - m * 2 - ui.dp(8)) / 2, bh = ui.dp(38), by = cy + ch - bh - ui.dp(12);
        ui.button("tut:offer:no", cx + m, by, bw, bh, "Not now", false, 13);
        ui.bigButton("tut:start", cx + m + bw + ui.dp(8), by, bw, bh, "Learn to play", Ui.ACCENT);
    }

    /** Over How to play: the tour, from the start. */
    private void helpButton(Gfx g) {
        Ui ui = g0.ui;
        float bw = ui.dp(124);
        ui.button("tut:restart", g.width() - bw - ui.dp(16), ui.dp(10), bw, ui.dp(34), "Learn to play", false, 13);
    }

    // ------------------------------------------------------------------ first-time tips

    /** The tip shown, and its seconds left. */
    String tipText;
    float tipT;
    private String tipTool;
    private World tipWorld;

    static String tipFor(String tool) {
        switch (tool) {
            case "look": return "Tap anyone or anything to see who or what it is. Drag to move, pinch to zoom.";
            case "dead": return "Drag on the map to paint the dead; pick a kind and how many above the tools.";
            case "police": return "Tap the map to put police there: officers, vehicles, or whole squads with their cars.";
            case "army": return "Tap the map to put soldiers there; squads come with their vehicles.";
            case "build": return "Tap or drag to build: barricades, walls, sandbags, wire, gun nests, crates.";
            case "strike": return "Tap where to aim, then Fire to confirm (or Cancel).";
            case "orders": return "Tap a squad or formation, then where it should go. Routes, cordons and stance are above.";
            case "town": return "Tap a town to see how it's doing, or to evacuate, quarantine or send the army.";
            case "erase": return "Drag to clear the dead away, or clear wrecks, blood, barricades or a town's dead.";
            case "map": return "The national map's layers. The first button jumps out to the whole country and back.";
            case "send": return "Pick who to send from the station, then tap where they go.";
            case "kit": return "What you carry: tap to use it, take a weapon in hand, or tell your people what to do.";
            case "fire": return "Auto fire, tap to aim, or hold fire to slip past quietly.";
            case "replay": return "Tap the track to go anywhere; +Key and Path direct the camera; Clip exports it as pictures.";
            default: return null;
        }
    }

    private void tips(Gfx g, float dt) {
        if (g0.screen != Game.S_PLAY || g0.w == null || g0.hideUi) {
            if (g0.screen != Game.S_PLAY) tipTool = null;
            return;
        }
        String key = g0.replay != null ? "replay" : g0.tool;
        if (g0.w != tipWorld || !key.equals(tipTool)) {
            tipWorld = g0.w;
            tipTool = key;
            String tip = tipFor(key);
            if (g0.settings.tips && tip != null && g0.pf.pref("tip." + key, null) == null) {
                g0.pf.setPref("tip." + key, "seen");
                tipText = tip;
                tipT = 6.5f;
            }
        }
        if (tipT <= 0 || tipText == null) return;
        tipT -= dt;
        Ui ui = g0.ui;
        float a = Math.min(1, Math.min(tipT / 0.4f, 1));
        float ts = ui.dp(Ui.M);
        float maxW = Math.min(g0.freeR - g0.freeL - ui.dp(24), ui.dp(460));
        String line = Ui.fit(g, tipText, maxW - ui.dp(64), ts, false);
        int lines = 1;
        if (g.textWidth(tipText, ts, false) > maxW - ui.dp(64)) lines = Math.min(2, Ui.lines(g, tipText, maxW - ui.dp(64), ts));
        float bw = lines > 1 ? maxW : g.textWidth(line, ts, false) + ui.dp(64), bh = ui.dp(16) + lines * ts * 1.3f;
        float x = (g0.freeL + g0.freeR - bw) / 2, y = g0.freeT + ui.dp(8);
        g.color(((int) (a * 235) << 24) | 0x1C2633);
        g.fillRoundRect(x, y, bw, bh, ui.dp(10));
        g.color(((int) (a * 255) << 24) | (RING & 0xFFFFFF));
        g.text("TIP", x + ui.dp(10), y + ui.dp(8) + ts, ui.dp(Ui.S), Gfx.ALIGN_LEFT, true);
        if (lines > 1) ui.wrap(tipText, x + ui.dp(44), y + ui.dp(8) + ts * 0.9f, bw - ui.dp(54), ts, Ui.TEXT, 2);
        else {
            g.color(((int) (a * 255) << 24) | (Ui.TEXT & 0xFFFFFF));
            g.text(line, x + ui.dp(44), y + ui.dp(8) + ts * 0.9f, ts, Gfx.ALIGN_LEFT, false);
        }
        ui.area("tut:tipx", x, y, bw, bh);
    }
}
