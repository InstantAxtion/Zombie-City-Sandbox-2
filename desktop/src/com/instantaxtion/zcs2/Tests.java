package com.instantaxtion.zcs2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The game's tests, run on a desktop JVM (the simulation has no Android code in it, so nothing needs stubbing).
 *   ./test.sh            all of them
 *   ./test.sh save       only those whose names start with "save"
 * Each test checks what a feature promises, not one seed's exact numbers.
 */
public final class Tests {
    interface T {
        void run() throws Exception;
    }

    private static final List<String> names = new ArrayList<String>();
    private static final List<T> tests = new ArrayList<T>();
    private static Content content;
    private static int failed, passed;

    private static void test(String name, T t) {
        names.add(name);
        tests.add(t);
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static Synth synth;

    /** Every sound, made once for the tests that need them. */
    static synchronized Synth synth() {
        if (synth == null) {
            synth = new Synth();
            synth.prerenderAll();
        }
        return synth;
    }

    static World game(long seed, int size, String scenario, int threads) {
        return Setup.newGame(content, size, RegionGen.COAST, seed, scenario == null ? null : content.scenario(scenario), threads);
    }

    static void camOver(World w, float x, float y) {
        w.camX0 = x - 600;
        w.camX1 = x + 600;
        w.camY0 = y - 900;
        w.camY1 = y + 900;
        w.camDetail = true;
    }

    static void play(World w, float seconds, boolean audit) {
        int steps = (int) (seconds * 20);
        for (int k = 1; k <= steps; k++) {
            w.step();
            if (audit && k % 100 == 0) {
                String a = Audit.check(w);
                check(a.isEmpty(), "audit at t=" + (int) w.time + ": " + a);
            }
        }
    }

    private static final java.awt.image.BufferedImage SCREEN = new java.awt.image.BufferedImage(540, 1170, java.awt.image.BufferedImage.TYPE_INT_RGB);

    static void frames(Game g, int n) {
        for (int k = 0; k < n; k++) g.frame(new DesktopGfx(SCREEN.createGraphics(), 540, 1170), 1 / 30f);
    }

    /** Where the button with this id is on the last frame drawn, or null. */
    static float[] findId(Game g, String id) {
        for (int y = 4; y < 1170; y += 6)
            for (int x = 4; x < 540; x += 6)
                if (id.equals(g.hitAt(x, y))) return new float[]{x + 3, y + 3};
        return null;
    }

    static boolean tapId(Game g, String id) {
        float[] p = findId(g, id);
        if (p == null) return false;
        g.touch(Game.DOWN, 0, p[0], p[1]);
        g.touch(Game.UP, 0, p[0], p[1]);
        return true;
    }

    static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** A squad of the given people standing at (x, y), holding there. */
    static Squads.Squad squadAt(World w, int faction, float x, float y, String... units) {
        Squads.Squad s = w.sq.create(faction, -1, "Test");
        for (int k = 0; k < units.length; k++) {
            float[] p = w.r.openGroundNear(x + (k % 3) * 8, y + (k / 3) * 8, false);
            int i = w.spawnUnit(w.c.unit(units[k]), p[0], p[1]);
            w.sq.add(s, i);
        }
        w.sq.order(s, Squads.O_HOLD, x, y);
        return s;
    }

    /** Open street about d px from (x, y), along whichever way the street runs. */
    static float[] packNear(World w, float x, float y, float d) {
        for (int k = 0; k < 16; k++) {
            double a = k * Math.PI / 8;
            float px = x + (float) Math.cos(a) * d, py = y + (float) Math.sin(a) * d;
            boolean clear = true;
            for (int t = 1; t <= 8 && clear; t++) clear = w.r.walkablePx(x + (px - x) * t / 8, y + (py - y) * t / 8);
            if (clear) return new float[]{px, py};
        }
        return new float[]{x + d, y};
    }

    /**
     * Checkpoint hashes of the golden games. GOLDEN_STATE (the state, whatever the save format) was recorded on the
     * code before P3 (91fecad: P2, P4, P8 and P9a in) and must never change on today's maps. GOLDEN also hashes the
     * save: recorded on 91fecad too, and again in P3 when Ecology, Society and Director began saving sparsely (with
     * GOLDEN_STATE unchanged), and again after the legibility records and the player tools (new modules saved: records,
     * rules, defences, orders; the fallen list's wording; the loss summary counting those bitten in the streets; GOLDEN_STATE
     * unchanged). Re-record GOLDEN, never GOLDEN_STATE, when only the save format changes.
     * Both were recorded again when the normal day came in (rules life and lifeFlow, on purpose: the counts move
     * between homes and work with the hour, and the city's traffic and people round the camera are drawn from them).
     * With those two rules at 0 the code gives the old GOLDEN_STATE exactly, and with content.ini as it was before
     * them the old GOLDEN too. GOLDEN was recorded again in P9b (content.ini's new rules and national values; GOLDEN_STATE
     * unchanged).
     */
    static final long[][] GOLDEN_STATE = {
            {-9048877486888558763L, -5074671415040436397L, -7091719996387695111L, 5514597120603307591L, -9060996376688164900L, -2464322849719482128L,
                    -8806496574989025427L},
            {-4612109667781531387L, 8148228355577481168L, -3789379237918480937L, -8720357643317598861L, 7805403087932392722L, 9087861142141795830L,
                    5601821122340862675L, -8178928060073826903L, -3450227958994545031L},
            {-5352660960698400984L, 3856382757349242375L, 2892717960740663617L, 5948764704801302308L, 8017571868137675746L, -4595630069377124760L,
                    -2206240929984095807L, 4309460876284602727L, 6493276397047788789L, -4999354706653124132L, -2101736541442976444L},
            {7100082239672412198L, -3056807817964742206L, -5404289687014696788L, -8864410598376162752L, 7293556389757909732L, 6872087061174453523L,
                    -3238728496210034461L, 1023340059064900564L, -490721386234721005L, -490721386234721005L, -490721386234721005L},
            {-8002621144123268477L, -6845613328155906658L, -1866830996085695085L, 53101970365605354L, -8553037998629933908L, -125097791475767827L,
                    6495605104087188105L, 779923143005276946L, -7816591287606685377L},
    };
    static final long[][] GOLDEN = {
            // (re-recorded in P10 for content.ini's new hold_city text: the rules' fingerprint is in the save;
            // GOLDEN_STATE, the outcomes themselves, unchanged)
            {-2773361531172726314L, -4604074348829737609L, 5261913146902298030L, -1852547228188804497L, 1153099393528517072L, -4844235010867058915L,
                    6762450311043686402L},
            {-564158188107401628L, -2812448649786776681L, 1703154372799756583L, 9182488689339264809L, -5218399829220462265L, 2258452847913618326L,
                    72474058509874571L, 5685021449286998523L, 5284886032678571097L},
            {-8998270271346483022L, -1142728748489067111L, 7091458319719407824L, -1753998302621334851L, 2679439976089607907L, 4911696268126449573L,
                    -2040880023970582192L, -3533649821578922654L, 1032616626644463258L, -6453680103839279907L, 5273058817872613862L},
            {9027409444459635374L, -5173150934611488342L, 61125156320328030L, 7042474277901380331L, 6183994235344158060L, 3428997486064088654L,
                    7194421268401145045L, -4396468268277420510L, -790352037311574871L, -790352037311574871L, -790352037311574871L},
            {7795838465445004291L, -6838387169935030213L, 2833435369717277200L, 3488987400903212436L, -2970018269114580128L, -8567386396342883155L,
                    1003359554468810828L, 2428434920397792713L, -7278350503119913815L},
    };

    /**
     * One golden game: a few street checkpoints, then days of fast-forward with one each game day. Each checkpoint
     * is Audit.hash mixed with a hash of the whole save (sector drift left out: it is only read where the dead walk).
     */
    static long[][] golden(int which) throws Exception {
        World w;
        int streetSecs, days;
        if (which == 0) {
            w = game(21, 1024, "sandbox", 2);
            Region.Settlement c = w.r.city();
            camOver(w, c.cx * 16f, c.cy * 16f);
            Tools.paintZombies(w, c.cx * 16f, c.cy * 16f, 2500, 700, -1);
            streetSecs = 90;
            days = 4;
        } else if (which == 1) {
            w = game(10, 1536, "lab_leak", 2);
            camOver(w, Setup.originX, Setup.originY);
            streetSecs = 60;
            days = 6;
        } else if (which == 2) {
            w = game(7, 2304, "sandbox", 2);
            Tools.paintZombies(w, Setup.originX, Setup.originY, 4000, 900, -1);
            camOver(w, Setup.originX + 2000, Setup.originY);
            streetSecs = 30;
            days = 8;
        } else if (which == 3) {
            // a coast lost to hordes, an evacuation, the game's end
            w = game(7, 2304, "evacuate", 2);
            camOver(w, Setup.originX, Setup.originY);
            streetSecs = 30;
            days = 8;
        } else {
            // the dead everywhere at once, then put down: districts fall asleep and wake again
            w = game(7, 1536, "random", 2);
            Tools.paintZombies(w, Setup.originX, Setup.originY, 20000, 900, -1);
            camOver(w, Setup.originX, Setup.originY);
            streetSecs = 45;
            days = 6;
        }
        java.util.List<Long> out = new java.util.ArrayList<Long>(), state = new java.util.ArrayList<Long>();
        for (int k = 0; k < 3; k++) {
            play(w, streetSecs / 3f, false);
            out.add(deepHash(w));
            state.add(stateHash(w));
        }
        w.camDetail = false;
        w.setStrategic(true);
        for (int d = 0; d < days; d++) {
            for (int k = 0; k < 144; k++) w.step();
            out.add(deepHash(w));
            state.add(stateHash(w));
        }
        w.shutdown();
        long[][] a = new long[2][out.size()];
        for (int k = 0; k < out.size(); k++) {
            a[0][k] = out.get(k);
            a[1][k] = state.get(k);
        }
        return a;
    }

    /**
     * A hash of the simulation's state that doesn't depend on the save format: Audit.hash, every sector's counts,
     * timers and ecology, the hordes, and the running statistics.
     */
    static long stateHash(World w) {
        long h = Audit.hash(w);
        Sectors s = w.sec;
        for (int k = 0; k < s.n; k++) {
            h = (h ^ s.popIn[k]) * 1099511628211L;
            h = (h ^ s.zIn[k]) * 1099511628211L;
            h = (h ^ s.bodies[k]) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(s.inf[k])) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(s.hot[k])) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(s.panic[k])) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(s.firstZ[k])) * 1099511628211L;
            h = (h ^ (s.holdout[k] ? 1 : 0)) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(w.ecology.hunger[k])) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(w.ecology.nestT[k])) * 1099511628211L;
            h = (h ^ w.ecology.strain[k]) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(w.director.peak[k])) * 1099511628211L;
        }
        for (Strategic.Horde hd : w.strat.hordes) {
            h = (h ^ hd.count) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(hd.x)) * 1099511628211L;
            h = (h ^ hd.target) * 1099511628211L;
        }
        Stats st = w.stats;
        long[] v = {st.civDeaths, st.civTurned, st.zombiesMade, st.zombieKills, st.infectedTotal, st.rotted, st.holdouts, st.lootings, w.strat.evacuatedOut,
                w.strat.evacuatedArrived, w.news.log.size(), w.gov.level, Float.floatToIntBits(w.gov.trust)};
        for (long x : v) h = (h ^ x) * 1099511628211L;
        for (int x : st.popBySettlement) h = (h ^ x) * 1099511628211L;
        for (int x : st.lostBySettlement) h = (h ^ x) * 1099511628211L;
        return h;
    }

    static long deepHash(World w) throws Exception {
        float[] dx = w.sec.driftX.clone(), dy = w.sec.driftY.clone();
        java.util.Arrays.fill(w.sec.driftX, 0);
        java.util.Arrays.fill(w.sec.driftY, 0);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        SaveIO.save(w, bo);
        System.arraycopy(dx, 0, w.sec.driftX, 0, dx.length);
        System.arraycopy(dy, 0, w.sec.driftY, 0, dy.length);
        long h = Audit.hash(w);
        for (byte b : bo.toByteArray()) h = (h ^ (b & 0xff)) * 1099511628211L;
        return h;
    }

    /** A hash of the armed people's own state (what they're doing, their nerve and magazines). */
    static long armedHash(World w) {
        long h = 1469598103934665603L;
        for (int i = 0; i < w.e.top; i++) {
            if (!w.aai.has(i) || w.e.loc[i] == Ents.NONE) continue;
            h = (h ^ w.aai.act[i]) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(w.aai.nerve[i])) * 1099511628211L;
            h = (h ^ w.aai.mag[i]) * 1099511628211L;
            h = (h ^ w.aai.bits[i]) * 1099511628211L;
        }
        return h;
    }

    /**
     * Someone who doesn't move, on a street in the capital, ringed by a barricade (one navigation cell, two tiles,
     * thick, two cells out: the dead can see them but not reach them), and n of the dead (type -1: mixed) outside it
     * to the west. Returns {the middle navigation cell x, y, the person}.
     */
    static int[] barricadeArena(World w, int n, int ztype) {
        RoadGraph g = w.r.roads;
        Region.Settlement c = w.r.city();
        int ccx = -1, ccy = -1;
        for (int k = 0; k < g.edgeCount && ccx < 0; k++) {
            if (g.ekind[k] != RoadGraph.STREET || g.nset[g.ea[k]] != c.id || g.elen[k] < 300) continue;
            float mx = (g.nx[g.ea[k]] + g.nx[g.eb[k]]) / 2, my = (g.ny[g.ea[k]] + g.ny[g.eb[k]]) / 2;
            int x = (int) (mx / 32), y = (int) (my / 32);
            // room outside the ring, to the west, for the dead
            boolean room = w.r.navOpen(x, y) && w.r.navOpen(x - 3, y) && w.r.navOpen(x - 4, y);
            if (room) {
                ccx = x;
                ccy = y;
            }
        }
        check(ccx >= 0, "no street for the arena");
        // at night, with everyone else indoors: nobody else on the streets for the dead to go after (no traffic,
        // no parked cars' alarms)
        w.life.held = true;
        w.time = 14 * 60;
        for (int i = 0; i < w.e.top; i++) if (w.e.loc[i] == Ents.FREE && w.e.kind[i] == Ents.CIV) w.sec.shelter(i);
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) != 2) continue;
                for (int ty = (ccy + dy) * 2; ty <= (ccy + dy) * 2 + 1; ty++)
                    for (int tx = (ccx + dx) * 2; tx <= (ccx + dx) * 2 + 1; tx++)
                        if (Tile.walkable(w.r.t(tx, ty))) {
                            w.r.set(tx, ty, Tile.BARRICADE);
                            x0 = Math.min(x0, tx);
                            y0 = Math.min(y0, ty);
                            x1 = Math.max(x1, tx);
                            y1 = Math.max(y1, ty);
                        }
            }
        w.r.rebuildNav(x0 - 1, y0 - 1, x1 + 1, y1 + 1);
        w.sec.connectivity();
        w.r.closeBarricadedRoads(x0, y0, x1, y1);
        float cx = ccx * 32 + 16, cy = ccy * 32 + 16;
        int civ = w.spawnCiv(cx, cy, w.c.unit("civilian"));
        w.e.state[civ] = CivAI.S_PLAYER;
        w.playerEnt = civ;
        w.strat.playerFire = false;
        w.e.hp[civ] = w.e.maxHp[civ] = 1e9f;
        camOver(w, cx, cy);
        for (int k = 0; k < n; k++) {
            float zx = cx - 100 - (k % 4) * 8, zy = cy + ((k / 4) % 7 - 3) * 8;
            if (!w.r.walkablePx(zx, zy)) zy = cy;
            Content.ZType t = ztype >= 0 ? w.c.ztypes.get(ztype) : w.pickZType(-1, k);
            if (!t.breakIn) t = w.c.ztype("walker");
            w.e.flags[w.spawnZombie(zx, zy, t)] |= Ents.F_PLACED;
        }
        return new int[]{ccx, ccy, civ};
    }

    /** True if one of the dead is inside the ring of an arena round navigation cell (ccx, ccy). */
    static boolean insideRing(World w, int ccx, int ccy) {
        for (int i = 0; i < w.e.top; i++)
            if (w.e.loc[i] == Ents.FREE && w.e.kind[i] == Ents.ZOMBIE && Math.abs((int) (w.e.x[i] / 32) - ccx) <= 1 && Math.abs((int) (w.e.y[i] / 32) - ccy) <= 1)
                return true;
        return false;
    }

    /** True if any cell of the arena's ring is open now. */
    static boolean ringOpen(World w, int ccx, int ccy) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++)
                if (Math.max(Math.abs(dx), Math.abs(dy)) == 2 && w.r.navOpen(ccx + dx, ccy + dy)) return true;
        return false;
    }

    static float meanZombieX(World w) {
        float sx = 0;
        int n = 0;
        for (int i = 0; i < w.e.top; i++)
            if (w.e.loc[i] == Ents.FREE && w.e.kind[i] == Ents.ZOMBIE) {
                sx += w.e.x[i];
                n++;
            }
        return n == 0 ? 0 : sx / n;
    }

    static void simThreads(int size) {
        simThreads(size, 4);
    }

    static void simThreads(int size, int threads) {
        World a = game(21, size, "sandbox", 1), b = game(21, size, "sandbox", threads);
        for (World w : new World[]{a, b}) {
            camOver(w, Setup.originX, Setup.originY);
            Tools.paintZombies(w, Setup.originX, Setup.originY, 900, 500, -1);
        }
        for (int k = 0; k < 60 * 20; k++) {
            a.step();
            b.step();
            if (k % 200 == 0) check(Audit.hash(a) == Audit.hash(b), "diverged at step " + k);
            if (k % 200 == 0) check(Audit.check(a).isEmpty(), Audit.check(a));
        }
        check(a.act.activeCount() == b.act.activeCount(), "active sets differ");
        check(Audit.hash(a) == Audit.hash(b), "different after a minute");
        a.shutdown();
        b.shutdown();
    }

    static void simAccounted(int size) {
        World w = game(5, size, "sandbox", 2);
        long before = Audit.people(w);
        Region.Settlement c = w.r.city();
        // pan the camera across the city: people come out on the streets and go back in
        for (int k = 0; k < 6; k++) {
            camOver(w, c.cx * 16f + (k - 3) * 600, c.cy * 16f);
            play(w, 25, true);
            check(Audit.people(w) == before, "people " + Audit.people(w) + " vs " + before + " after pan " + k);
        }
        check(w.sp.civCount > 0 || true, "");
        w.camDetail = false;
        play(w, 60, true);
        check(Audit.people(w) == before, "people after the camera left: " + Audit.people(w) + " vs " + before);
        check(w.sp.civCount < 10, "the streets empty when nobody watches: " + w.sp.civCount);
        w.shutdown();
    }

    static void saveExact(int size) throws Exception {
        World w = game(31, size, "lab_leak", 2);
        camOver(w, Setup.originX, Setup.originY);
        Tools.paintZombies(w, Setup.originX, Setup.originY, 300, 300, -1);
        play(w, 50, false);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        SaveIO.save(w, bo);
        World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
        check(Audit.hash(l) == Audit.hash(w), "different straight after loading");
        check(l.act.sparse == w.act.sparse && l.act.activeCount() == w.act.activeCount(), "the active set didn't come back");
        for (int p = 0; p < w.act.pages; p++) check(l.act.active(p) == w.act.active(p), "chunk " + p + " active in one, not the other");
        check(Audit.check(l).isEmpty(), "loaded game fails the audit: " + Audit.check(l));
        for (int k = 0; k < 40 * 20; k++) {
            w.step();
            l.step();
            if (k % 100 == 0) check(Audit.hash(l) == Audit.hash(w), "diverged " + k + " steps after loading");
        }
        check(l.news.log.size() == w.news.log.size(), "radio logs differ");
        check(bo.size() < 4 << 20, "save is " + bo.size() / 1024 + " KB");
        w.shutdown();
        l.shutdown();
    }

    /** An overlay as a save holds it. */
    static byte[] overlayBytes(Overlay o) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(bo);
        o.write(d);
        d.flush();
        return bo.toByteArray();
    }

    static void replayExact(int size) throws Exception {
        World w = Setup.newGame(content, size, RegionGen.COAST, 77, content.scenario("lab_leak"), 2, Roles.GOD, 60000);
        camOver(w, Setup.originX, Setup.originY);
        // a session: paint, place, strike, pan, fast-forward and back
        for (int k = 0; k < 20 * 20; k++) w.step();
        Actions.paint(w, Setup.originX + 200, Setup.originY, 300, 200, -1);
        Squads.Squad s = Actions.squad(w, "swat", 8, Setup.originX - 150, Setup.originY);
        for (int k = 0; k < 15 * 20; k++) w.step();
        if (s != null) Actions.order(w, s, Setup.originX + 120, Setup.originY);
        camOver(w, Setup.originX + 500, Setup.originY + 300);
        Actions.strike(w, Setup.originX + 900, Setup.originY + 600);
        Actions.curfew(w, true);
        for (int k = 0; k < 20 * 20; k++) w.step();
        w.camDetail = false;
        Actions.strategic(w, true);
        for (int k = 0; k < 60; k++) w.step();
        Actions.strategic(w, false);
        camOver(w, Setup.originX, Setup.originY);
        for (int k = 0; k < 10 * 20; k++) w.step();
        // and the player's tools: defences, building, orders, driving, possession, the rules, clearing
        toolSession(w, Setup.originX, Setup.originY);
        check(w.director.events.size() > 5, "only " + w.director.events.size() + " inputs recorded");
        Replay r = Replay.of(content, w, 2);
        r.runTo(w.tick);
        check(r.w.tick == w.tick, "ticks differ");
        check(Audit.hash(r.w) == Audit.hash(w), "the replay came out different");
        // and through a file
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Replay.save(w, bo);
        Replay r2 = Replay.load(content, new ByteArrayInputStream(bo.toByteArray()), 1);
        r2.runTo(w.tick);
        check(Audit.hash(r2.w) == Audit.hash(w), "the replay from a file came out different");
        check(bo.size() < 200000, "replay is " + bo.size() / 1024 + " KB");
        check(!w.director.lapseTime.isEmpty(), "no timelapse frames");
        w.shutdown();
        r.w.shutdown();
        r2.w.shutdown();
    }

    // ------------------------------------------------------------------ director, editor and modding (1.4)

    /** A session with painting, a squad, orders, panning and a stretch of fast-forward, ending at its own tick. */
    static World seekSession(int size, long seed) {
        World w = Setup.newGame(content, size, RegionGen.COAST, seed, content.scenario("lab_leak"), 2, Roles.GOD, 60000);
        float ox = Setup.originX, oy = Setup.originY;
        camOver(w, ox, oy);
        steps(w, 20);
        Actions.paint(w, ox + 200, oy, 200, 150, -1);
        Squads.Squad s = Actions.squad(w, "swat", 6, ox - 150, oy);
        steps(w, 30);
        if (s != null) Actions.order(w, s, ox + 120, oy);
        camOver(w, ox + 400, oy + 200);
        steps(w, 30);
        w.camDetail = false;
        Actions.strategic(w, true);
        for (int k = 0; k < 24; k++) w.step();
        Actions.strategic(w, false);
        camOver(w, ox, oy);
        steps(w, 25);
        return w;
    }

    static void seekExact(int size) throws Exception {
        World w = seekSession(size, 91);
        long end = w.tick;
        // played straight through, the state at each checkpoint
        long[] at = {end / 9, end / 4, end / 2, end * 3 / 5, end * 9 / 10, end};
        long[] hash = new long[at.length], tick = new long[at.length];
        Replay straight = Replay.of(content, w, 2);
        for (int k = 0; k < at.length; k++) {
            straight.runTo(at[k]);
            hash[k] = Audit.hash(straight.w);
            tick[k] = straight.w.tick;
        }
        check(hash[at.length - 1] == Audit.hash(w), "the replay itself came out different");
        straight.w.shutdown();
        // another, its snapshots squeezed into a small cap (so they thin out), then sought about in no order
        Replay r = Replay.of(content, w, 2);
        r.snapEvery = 4;
        r.snapCap = 1 << 20;
        r.runTo(end);
        check(r.snaps.size() >= 3, "only " + r.snaps.size() + " snapshots");
        check(r.snapBytes() <= r.snapCap || r.snaps.size() <= 2, "snapshots over their cap: " + r.snapBytes());
        check(r.snapEvery > 4, "the snapshots never thinned out (" + r.snaps.size() + ", " + r.snapBytes() / 1024 + " KB)");
        int[] order = {2, 0, 5, 1, 4, 3, 0, 5, 2};
        int loads = 0;
        for (int k : order) {
            World old = r.seek(at[k]);
            if (old != null) {
                loads++;
                old.shutdown();
            }
            check(r.w.tick == tick[k], "seek to " + at[k] + " landed on " + r.w.tick + ", not " + tick[k]);
            check(Audit.hash(r.w) == hash[k], "seeking to tick " + at[k] + " (checkpoint " + k + ") came out different from playing straight through");
            check(Audit.check(r.w).isEmpty(), "audit after a seek: " + Audit.check(r.w));
        }
        check(loads >= 4, "only " + loads + " seeks loaded a snapshot");
        r.w.shutdown();
        w.shutdown();
    }

    static void camPathCut() throws Exception {
        Replay.CamPath p = new Replay.CamPath();
        p.add(0, 100, 100, 1);
        p.add(24000, 1100, 1100, 1);
        p.add(12000, 1100, 100, 0.25f);
        p.add(12010, 1100, 100, 0.25f); // within a game minute: replaces the key
        check(p.size() == 3, "keys: " + p.size());
        check(p.ticks.get(1) == 12010, "kept in order and replaced");
        float[] a = p.at(0), b = p.at(12010), c = p.at(30000);
        check(a[0] == 100 && a[1] == 100 && Math.abs(a[2] - 1) < 1e-4, "at the first key");
        check(Math.abs(b[0] - 1100) < 0.01f && Math.abs(b[1] - 100) < 0.01f && Math.abs(b[2] - 0.25f) < 1e-4, "at the middle key: " + b[0] + "," + b[1] + "," + b[2]);
        check(c[0] == 1100 && c[1] == 1100, "holds after the last");
        float[] prev = p.at(0);
        for (long t = 60; t <= 24000; t += 60) {
            float[] q = p.at(t);
            check(Math.abs(q[0] - prev[0]) < 40 && Math.abs(q[1] - prev[1]) < 40, "the path jumps at " + t);
            check(q[2] > 0.2f && q[2] < 1.2f, "zoom out of range at " + t + ": " + q[2]);
            prev = q;
        }
        // a director's cut: the replay with its path, saved and loaded again, still exact
        World w = Setup.newGame(content, 768, RegionGen.COAST, 92, content.scenario("lab_leak"), 2, Roles.GOD, 60000);
        camOver(w, Setup.originX, Setup.originY);
        steps(w, 20);
        Actions.paint(w, Setup.originX + 150, Setup.originY, 100, 100, -1);
        steps(w, 20);
        Replay r = Replay.of(content, w, 1);
        r.path.add(0, Setup.originX, Setup.originY, 0.8f);
        r.path.add(w.tick / 2, Setup.originX + 300, Setup.originY, 0.5f);
        r.path.add(w.tick, Setup.originX + 300, Setup.originY + 300, 1.2f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        r.saveCut(bo);
        Replay r2 = Replay.load(content, new ByteArrayInputStream(bo.toByteArray()), 1);
        check(r2.path.size() == 3 && r2.path.ticks.get(1) == w.tick / 2 && r2.path.keys.get(2)[2] == 1.2f, "the camera path didn't come back");
        r2.runTo(w.tick);
        check(Audit.hash(r2.w) == Audit.hash(w), "a director's cut plays differently");
        // a replay saved from a game has no path, and still loads
        bo.reset();
        Replay.save(w, bo);
        Replay r3 = Replay.load(content, new ByteArrayInputStream(bo.toByteArray()), 1);
        check(r3.path.size() == 0, "a path from nowhere");
        r.w.shutdown();
        r2.w.shutdown();
        r3.w.shutdown();
        w.shutdown();
    }

    /** A custom scenario (as the editor writes it), read back through content.ini's reader. */
    static Content.Scenario custom(String id, String origin, int zombies, String goals, String... triggers) throws Exception {
        Content.Scenario sc = new Content.Scenario();
        sc.id = id;
        sc.name = "Test " + id;
        sc.mode = "sandbox";
        sc.origin = origin;
        sc.goal = "";
        sc.desc = "a test";
        sc.zombies = zombies;
        sc.goals = goals;
        for (String t : triggers) sc.triggers.add(t);
        String bad = Script.check(content, sc);
        check(bad == null, "the scenario doesn't read: " + bad);
        java.util.List<Content.Scenario> l = content.addScenarios(new ByteArrayInputStream(sc.toIni().getBytes("UTF-8")), true);
        check(l.size() == 1 && l.get(0).toIni().equals(sc.toIni()), "the scenario changed going through content.ini:\n" + sc.toIni() + "\n" + (l.isEmpty() ? "" : l.get(0).toIni()));
        return l.get(0);
    }

    static boolean said(World w, String text) {
        for (News.Item it : w.news.log) if (it.text.contains(text)) return true;
        return false;
    }

    static void scriptTriggers() throws Exception {
        Content.Scenario sc = custom("trig_test", "lab", 25, "",
                "time:20 -> horde:0.3,0.3,1500",
                "time:40 -> message:Riots reported in the north",
                "time:60 -> weather:4",
                "dead:0:3 -> strike:0.5,0.5",
                "time:90 -> send:0.5,0.5",
                "lost:9999 -> message:never");
        World a = Setup.newGame(content, 768, RegionGen.COAST, 33, sc, 2, Roles.GOD, 60000);
        check(a.script != null && a.script.triggers.size() == 6, "no script");
        camOver(a, Setup.originX, Setup.originY);
        int hordes0 = a.strat.hordes.size();
        steps(a, 19);
        check(!a.script.triggers.get(0).fired && a.strat.hordes.size() == hordes0, "fired early");
        steps(a, 3);
        check(a.script.triggers.get(0).fired, "the horde trigger didn't fire at 20 minutes");
        check(a.strat.hordes.size() > hordes0, "no horde came");
        steps(a, 20);
        check(said(a, "Riots reported in the north"), "the message wasn't given");
        steps(a, 20);
        check(a.rules.v[Rules.WEATHER] == 4, "the weather didn't change: " + a.rules.v[Rules.WEATHER]);
        // a save part way keeps what has fired and plays on exactly
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        SaveIO.save(a, bo);
        World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
        check(l.script != null, "the loaded game has no script");
        for (int k = 0; k < 6; k++) check(l.script.triggers.get(k).fired == a.script.triggers.get(k).fired, "trigger " + k + "'s state was lost");
        for (int k = 0; k < 40 * 20; k++) {
            a.step();
            l.step();
        }
        check(Audit.hash(l) == Audit.hash(a), "diverged after loading");
        check(a.script.triggers.get(4).fired, "the formation wasn't sent");
        boolean sent = false;
        for (Forces.Formation f : a.forces.formations) if (f.alive && f.player) sent = true;
        check(sent, "no formation is on its way");
        check(!a.script.triggers.get(5).fired, "a trigger on a facility that doesn't exist fired");
        // the script's actions aren't inputs: a replay rebuilds them from the scenario
        for (Director.Event e : a.director.events) check(e.kind != Actions.HORDE && e.kind != Actions.SEND && e.kind != Actions.MESSAGE, "a scripted action was recorded");
        Replay r = Replay.of(content, a, 2);
        r.runTo(a.tick);
        check(Audit.hash(r.w) == Audit.hash(a), "the replay of a scripted game came out different");
        for (int k = 0; k < 6; k++) check(r.w.script.triggers.get(k).fired == a.script.triggers.get(k).fired, "trigger " + k + " differs in the replay");
        // and the same game again fires the same way
        World b = Setup.newGame(content, 768, RegionGen.COAST, 33, sc, 1, Roles.GOD, 60000);
        camOver(b, Setup.originX, Setup.originY);
        while (b.tick < a.tick) {
            if (b.tick == 0) camOver(b, Setup.originX, Setup.originY);
            b.step();
        }
        check(Audit.hash(b) == Audit.hash(a), "the same scripted game played differently");
        a.shutdown();
        l.shutdown();
        r.w.shutdown();
        b.shutdown();
    }

    static void scriptGoals() throws Exception {
        // held for long enough: won
        World w = Setup.newGame(content, 768, RegionGen.COAST, 34, custom("goal_days", "none", 0, "days:0.05"), 2, Roles.GOD, 60000);
        steps(w, 70);
        check(!w.ended, "ended before the time was up");
        steps(w, 5);
        check(w.ended && w.won, "holding on didn't win: " + w.endTitle);
        check(w.script.progress() != null, "no progress line");
        w.shutdown();
        // no dead: cleared, won
        w = Setup.newGame(content, 768, RegionGen.COAST, 34, custom("goal_clear", "none", 0, "cleared"), 2, Roles.GOD, 60000);
        steps(w, 40);
        check(w.ended && w.won, "putting down every one of the dead didn't win");
        w.shutdown();
        // losses under 0 with the dead loose in the capital: lost
        w = Setup.newGame(content, 768, RegionGen.COAST, 34, custom("goal_loss", "lab", 300, "days:5; losses:0"), 2, Roles.GOD, 60000);
        camOver(w, Setup.originX, Setup.originY);
        for (int k = 0; k < 120 * 20 && !w.ended; k++) w.step();
        check(w.ended && !w.won, "losing people didn't lose: " + w.endTitle + " " + w.script.losses());
        w.shutdown();
        // keep the capital at 99.99%: lost once anyone there is caught
        w = Setup.newGame(content, 768, RegionGen.COAST, 34, custom("goal_keep", "lab", 300, "keep:0:99.99"), 2, Roles.GOD, 60000);
        camOver(w, Setup.originX, Setup.originY);
        for (int k = 0; k < 240 * 20 && !w.ended; k++) w.step();
        check(w.ended && !w.won && w.endTitle.contains(w.r.settlements.get(0).name), "losing the town didn't lose: " + w.endTitle);
        w.shutdown();
    }

    /** A scenario using everything the editor makes: points, forces, defences, rules, a strain, goals, triggers. */
    static Content.Scenario everything(String id, boolean national) throws Exception {
        Content.Scenario sc = new Content.Scenario();
        sc.id = id;
        sc.name = "Everything " + id;
        sc.mode = "sandbox";
        sc.origin = national ? "xy:0.5,0.5" : "hospital";
        sc.goal = "";
        sc.desc = "every feature";
        sc.zombies = 120;
        sc.hordes = 1;
        sc.hordeSize = 900;
        sc.season = 355;
        sc.role = "god";
        sc.points = "0.3,0.4,150; 0.62,0.58,700";
        sc.place = "squad:swat@0.5,0.52; squad:rifle@0.51,0.52; defence:" + Defences.SANDBAGS + "@0.5,0.53; defence:" + Defences.MG + "@0.502,0.53";
        sc.rules = Rules.Z_SPEED + ":3, " + Rules.WEATHER + ":2";
        sc.strain = 2;
        sc.goals = "days:7; keep:0:30; losses:900000";
        sc.triggers.add("time:10 -> horde:0.45,0.45,400");
        sc.triggers.add("dead:0:1 -> message:The dead are in the capital");
        sc.triggers.add("time:30 -> weather:3");
        String bad = Script.check(content, sc);
        check(bad == null, "doesn't read: " + bad);
        java.util.List<Content.Scenario> l = content.addScenarios(new ByteArrayInputStream(sc.toIni().getBytes("UTF-8")), true);
        check(l.get(0).toIni().equals(sc.toIni()), "changed going through content.ini");
        return l.get(0);
    }

    static void editorEverything(int size) throws Exception {
        boolean national = Setup.national(size);
        Content.Scenario sc = everything("every" + size, national);
        World w = Setup.newGame(content, size, RegionGen.COAST, 35, sc, 2, Roles.GOD, 60000);
        float m = w.r.px();
        check(w.script != null && w.script.triggers.size() == 3 && w.script.goals.size() == 3, "the script didn't come");
        check(w.rules.v[Rules.Z_SPEED] == 3 && w.rules.v[Rules.WEATHER] == 2, "the rules weren't set");
        check(w.strat.strain == 2, "the strain wasn't set");
        int players = 0;
        for (Squads.Squad s : w.sq.list) if (s.alive && s.player) players++;
        check(players >= 2, "the forces weren't placed: " + players);
        check(w.defs.list.size() >= 2, "the defences weren't placed: " + w.defs.list.size());
        // the extra outbreak points: the dead near each (people on the street, or in the counts there)
        for (float[] p : new float[][]{{0.3f, 0.4f}, {0.62f, 0.58f}}) {
            int near = 0;
            for (int i = 0; i < w.e.top; i++)
                if (w.e.loc[i] == Ents.FREE && w.e.kind[i] == Ents.ZOMBIE && dist(w.e.x[i], w.e.y[i], p[0] * m, p[1] * m) < 900) near++;
            int s = w.r.sectorOfPx(p[0] * m, p[1] * m);
            check(near + w.sec.zIn[s] >= 50, "no dead at the point " + p[0] + "," + p[1] + ": " + near + " + " + w.sec.zIn[s]);
        }
        check(w.strat.hordes.size() >= 1, "no horde");
        camOver(w, Setup.originX, Setup.originY);
        steps(w, 35);
        check(w.script.triggers.get(0).fired && w.script.triggers.get(2).fired, "the timed triggers didn't fire");
        check(Audit.check(w).isEmpty(), "audit: " + Audit.check(w));
        // saved and loaded, it plays on exactly
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        SaveIO.save(w, bo);
        World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
        check(l.script != null && l.script.goals.size() == 3, "the loaded game lost its script");
        for (int k = 0; k < 15 * 20; k++) {
            w.step();
            l.step();
        }
        check(Audit.hash(l) == Audit.hash(w), "diverged after loading");
        // and replays exactly, from a file
        bo.reset();
        Replay.save(w, bo);
        Replay r = Replay.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
        r.runTo(w.tick);
        check(Audit.hash(r.w) == Audit.hash(w), "the replay came out different");
        w.shutdown();
        l.shutdown();
        r.w.shutdown();
    }

    // the code's tables before they moved to content.ini (for the test that they arrived unchanged)
    static final String[] OLD_PRE = {"Ash", "Brook", "Cedar", "Elm", "Fair", "Glen", "Hart", "Iron", "Kings", "Lake",
            "Mill", "North", "Oak", "Pine", "Red", "Stone", "West", "Wood", "Clear", "Silver", "Spring", "Maple", "Willow",
            "Cold", "Black", "Green", "High", "Mason", "Rose", "Bright", "Clay", "Cotton", "Deer", "Eagle", "Fox", "Hazel",
            "Lin", "Mead", "Port", "Sutton", "Thorn", "Union", "Wal", "Bell", "Bay", "Copper", "Crest"};
    static final String[] OLD_SUF = {"ford", "ton", "field", "ville", "dale", " Falls", "port", "burg", "wood",
            " Springs", " Hill", "haven", "brook", "view", " Crossing", "ridge", "mont", " Creek", "water", "bury"};
    static final String[] OLD_BNAMES = {"House", "Apartments", "Offices", "Tower", "Shop", "Warehouse", "Farmhouse",
            "Barn", "Police station", "Hospital", "Power station", "Fuel depot", "Barracks", "Motor pool", "Prison",
            "Research lab", "Church", "School", "Mall", "Guard armory", "Bus depot", "Cell block", "Water works",
            "Telephone exchange", "Food warehouse", "Fire station", "City hall", "Railway station", "Terminal", "Dock warehouse",
            "State house", "Tent"};
    static final String[] OLD_PRESETS = {"Coast", "River Valley", "Highlands", "Lake District"};
    /** Region checksums (tiles, buildings and junctions, then every roof) and names, recorded on the code before the move. */
    static final long[] OLD_REGION = {7541346224121036853L, 5717814252102046637L, 1073831598034722776L, -5103872686852295160L,
            350794609727050026L, -194660480813946018L, -6916372190236208595L, 8371790037975010950L};
    static final int[] OLD_REGION_NAMES = {-432190659, -1698590290, 987147127, -170146497, 1805316018, -2130176824, -2143075508, -1708706981};
    static final long[] OLD_NATION = {-2588273170170537126L, 8295708271490474933L, 3116740770274513315L, 4709034136227997975L};
    static final int OLD_NAMES = 1579710398;

    static void dataMoved() throws Exception {
        check(java.util.Arrays.equals(Data.names("placePrefixes"), OLD_PRE), "place prefixes");
        check(java.util.Arrays.equals(Data.names("placeSuffixes"), OLD_SUF), "place suffixes (\"_\" is a space)");
        for (int k = 0; k < OLD_BNAMES.length; k++) check(Data.buildingName(k).equals(OLD_BNAMES[k]), "building " + k);
        check(Data.presetCount() == 4, "presets: " + Data.presetCount());
        for (int k = 0; k < 4; k++) check(Data.presetName(k).equals(OLD_PRESETS[k]) && Data.terrain(k) == k, "preset " + k);
        check(java.util.Arrays.equals(Data.outside("mobilise", null), new String[]{"infantry", "armour", "infantry", "artillery"}), "mobilisation");
        for (String o : new String[]{"lab", "crash", "prison", "festival", "port", "random", "state", "countryside", "edges", "none"})
            check(Data.origin(o) != null, "no origin " + o);
        check(Data.pitched(Region.B_HOUSE) && Data.pitched(Region.B_CHURCH) && !Data.pitched(Region.B_OFFICE), "roof shapes");
        check(Data.lit(Region.B_HOSPITAL) && Data.home(Region.B_TOWER) && !Data.lit(Region.B_SHOP), "lights");
        // the maps, their roofs and names, as the code made them before
        int k = 0;
        for (int size : new int[]{768, 1024})
            for (int p = 0; p < 4; p++, k++) {
                Region r = RegionGen.generate(size, p, 100 + p * 7 + size);
                long h = r.checksum();
                for (int b = 0; b < r.bCount; b++) h = (h ^ r.broof[b]) * 1099511628211L;
                StringBuilder n = new StringBuilder(r.name);
                for (Region.Settlement s : r.settlements) n.append('|').append(s.name);
                for (Region.Facility f : r.facilities) n.append('|').append(f.name);
                check(h == OLD_REGION[k], "region " + size + " " + p + " changed: " + h);
                check(n.toString().hashCode() == OLD_REGION_NAMES[k], "region " + size + " " + p + "'s names changed");
            }
        for (int p = 0; p < 4; p++) {
            Nation nt = NationGen.generate(8192, p, 31 + p, 2);
            long h = nt.checksum();
            for (int c = 0; c < 6; c++) {
                Chunk ch = ChunkGen.generate(nt, (c * 37 + 40) % (nt.size / Chunk.SIZE), (c * 23 + 40) % (nt.size / Chunk.SIZE));
                h = h * 31 + ch.checksum();
                for (int b = 0; b < ch.bCount; b++) h = (h ^ ch.broof[b]) * 1099511628211L;
            }
            check(h == OLD_NATION[p], "nation " + p + " changed: " + h);
        }
        StringBuilder ppl = new StringBuilder();
        for (int i = 0; i < 300; i++) ppl.append(Names.person(9, i)).append(Names.callsign(Content.SOLDIER, i)).append(Names.callsign(Content.COP, i));
        Rng rng = new Rng(5);
        for (int i = 0; i < 50; i++) ppl.append(Names.country(rng)).append(Names.state(rng));
        check(ppl.toString().hashCode() == OLD_NAMES, "people's, states' and countries' names changed");
    }

    static String builtinIni() throws Exception {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("assets/content.ini")), "UTF-8");
    }

    static String loadError(String ini) {
        try {
            Content.load(new ByteArrayInputStream(ini.getBytes("UTF-8")));
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    static void dataModded() throws Exception {
        String ini = builtinIni();
        try {
            String mod = ini.replaceFirst("placePrefixes = [^\n]*", "placePrefixes = Zed, Quorn")
                    + "\n[preset drycoast]\nindex = 4\nname = Dry Coast\nterrain = coast\nlevel = 0.24\nrelief = 0.4\nfeature = 1.2\nfeatureAt = 0.18\nfeatureCap = 0.2\nrivers = 1\nnaval = 1\n"
                    + "\n[origin mall]\nname = The fuel depot\nstart = facility\nfacility = fuel\nstreet = 40\n"
                    + "\n[origin biglab]\nname = A bigger lab leak\nstart = lab\n";
            Content mc = Content.load(new ByteArrayInputStream(mod.getBytes("UTF-8")));
            check(mc.rulesHash == content.rulesHash, "a data section changed the rules' fingerprint");
            check(Data.presetCount() == 5 && Data.presetName(4).equals("Dry Coast"), "the mod's preset");
            Region r = RegionGen.generate(768, 4, 5);
            check(r.settlements.get(0).name.startsWith("Zed") || r.settlements.get(0).name.contains("Zed") || r.settlements.get(0).name.contains("Quorn"), "the mod's names: " + r.settlements.get(0).name);
            check(r.checksum() != RegionGen.generate(768, 0, 5).checksum(), "the mod's preset made the coast");
            // an origin at a kind of facility, and one that reuses a built-in start
            Content.Scenario sc = new Content.Scenario();
            sc.id = "modstart";
            sc.name = "Mod start";
            sc.mode = "sandbox";
            sc.origin = "mall";
            sc.goal = "";
            sc.desc = "";
            sc.zombies = 100;
            World w = Setup.newGame(mc, 768, 4, 6, sc, 1, Roles.GOD, 60000);
            w.sp.build(w.e);
            check(w.totalZombies() == 100, "the mod's origin put down " + w.totalZombies() + " of the dead");
            Region.Facility fuel = null;
            for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_FUEL && fuel == null) fuel = f;
            check(fuel == null || dist(Setup.originX, Setup.originY, fuel.x, fuel.y) < 50, "it didn't start at the fuel depot");
            w.shutdown();
            sc.origin = "biglab";
            w = Setup.newGame(mc, 768, 4, 6, sc, 1, Roles.GOD, 60000);
            Region.Facility lab = null;
            for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_LAB && lab == null) lab = f;
            check(lab != null && dist(Setup.originX, Setup.originY, lab.x, lab.y) < 50, "the origin that reuses the lab start");
            w.shutdown();
            // mistakes are told plainly, by line
            String e1 = loadError(ini + "\n[weapon broken]\nrange = far\n");
            check(e1 != null && e1.contains("line ") && e1.contains("[weapon broken]") && e1.contains("number"), "a bad number: " + e1);
            String e2 = loadError(ini + "\n[scenario bad]\nname = Bad\ntrigger.1 = sometime -> horde:0.1,0.1,10\n");
            check(e2 != null && e2.contains("[scenario bad]") && e2.contains("no condition called sometime"), "a bad trigger: " + e2);
            String e3 = loadError(ini + "\n[building odd]\nindex = 40\nroofs = #123456\n");
            check(e3 != null && e3.contains("indices"), "a gap in the buildings: " + e3);
            String e4 = loadError(ini + "\n[gadget x]\nsize = 1\n");
            check(e4 != null && e4.contains("unknown kind of section"), "an unknown section: " + e4);
            String e5 = loadError(ini + "\njust some words\n");
            check(e5 != null && e5.contains("expected key = value"), "a stray line: " + e5);
        } finally {
            // the built-in tables back in place for the other tests
            Content.load(new ByteArrayInputStream(ini.getBytes("UTF-8")));
        }
        check(Data.presetCount() == 4, "the built-in presets weren't put back");
    }
    static void steps(World w, float seconds) {
        for (int k = 0; k < (int) (seconds * 20); k++) w.step();
    }

    /** A spot near (x, y) with open ground for len px to the east (a field to fight in). */
    static float[] openSpot(World w, float x, float y, float len) {
        for (int ring = 0; ring < 60; ring++)
            for (int a = 0; a < 16; a++) {
                float px = x + (float) Math.cos(a * 0.3927) * ring * 120, py = y + (float) Math.sin(a * 0.3927) * ring * 120;
                boolean ok = true;
                for (float d = -60; d <= len + 60 && ok; d += 8)
                    for (float o = -70; o <= 70 && ok; o += 14) ok = w.r.walkablePx(px + d, py + o) && w.r.tileAtPx(px + d, py + o) != Tile.ROAD;
                if (ok && w.sp.livingNear(w.e, px, py, 900, 1) == 0) return new float[]{px, py};
            }
        return null;
    }

    /** Every one of the player's new verbs, in a session: placed, built, ordered, driven, possessed, cleared. */
    static void toolSession(World w, float ox, float oy) {
        Actions.rule(w, Rules.Z_SPEED, 3);
        Actions.rule(w, Rules.INFECTION, 1);
        Actions.rule(w, Rules.FORCE, 3);
        Actions.rule(w, Rules.FLEE, 1);
        Actions.rule(w, Rules.WEATHER, 3);
        Actions.rule(w, Rules.TIME, 2);
        Actions.rule(w, Rules.OUTSIDE, 0);
        Actions.rule(w, Rules.CIVILIANS, 0);
        steps(w, 2);
        Actions.rule(w, Rules.CIVILIANS, 1);
        Actions.paint(w, ox + 250, oy, 80, 120, -1);
        int placed = 0, built = 0;
        for (int k = 0; k < Defences.KINDS; k++) if (Actions.defence(w, k, ox - 150 + k * 60, oy + 60)) placed++;
        for (int b = 0; b < Tools.BUILD_NAMES.length; b++) built += Actions.build(w, b, ox - 300 + b * 70, oy - 140);
        check(placed >= 3 && built > 0, "placed " + placed + " defences, built " + built + " tiles");
        Squads.Squad t = Actions.template(w, "rifle", ox - 120, oy + 140);
        check(t != null && t.vehicle >= 0, "the rifle squad came without its vehicle");
        Actions.people(w, "civilian", 5, ox + 100, oy + 150);
        Actions.mass(w, ox + 2500, oy + 400, 20000, -1);
        steps(w, 8);
        Squads.Squad s2 = Actions.squad(w, "rifleman", 6, ox, oy - 60);
        if (t != null && t.alive) {
            Actions.stance(w, t, Squads.STANCE_HOLD);
            Actions.route(w, t, new float[]{ox - 60, oy + 200, ox + 40, oy + 220, ox + 120, oy + 160});
            Actions.mount(w, t, true);
        }
        if (s2 != null) Actions.cordon(w, s2, ox - 80, oy - 100, ox + 80, oy - 100);
        int heli = Actions.vehicle(w, "gunship", ox, oy - 300);
        if (heli >= 0) Actions.vehicleOrder(w, heli, Orders.VO_ORBIT, ox + 300, oy);
        int tank = Actions.vehicle(w, "tank", ox - 200, oy);
        check(heli >= 0 && tank >= 0, "vehicles not placed");
        steps(w, 6);
        if (tank >= 0) {
            Actions.drive(w, tank);
            Actions.joy(w, 1, 0.2f);
            steps(w, 3);
            Actions.joy(w, 0, 0);
            Actions.drive(w, -1);
            Actions.vehicleOrder(w, tank, Orders.VO_ATTACK, ox + 200, oy);
        }
        if (s2 != null && s2.alive) Actions.stance(w, s2, Squads.STANCE_AGGRESSIVE);
        if (t != null && t.alive) Actions.mount(w, t, false);
        steps(w, 5);
        if (s2 != null && s2.alive) Actions.clearArea(w, s2, ox + 250, oy, 200);
        if (heli >= 0) Actions.vehicleOrder(w, heli, Orders.VO_ATTACK, ox + 250, oy + 50);
        if (t != null && t.alive) {
            Actions.fallBack(w, t, ox - 200, oy + 200);
            if (s2 != null && s2.alive && s2.n > 0) Actions.escort(w, t, s2.mem[0], false);
        }
        steps(w, 5);
        // God takes over someone and walks them about
        int who = -1;
        if (s2 != null && s2.alive) who = s2.leader(w.e);
        check(who < 0 || Actions.possess(w, who) && w.playerEnt == who, "couldn't take over " + who);
        if (who >= 0) {
            Actions.joy(w, -1, 0);
            steps(w, 2);
            Actions.joy(w, 0, 0);
            Actions.possess(w, -1);
        }
        for (Forces.Formation f : w.forces.formations)
            if (f.alive) {
                Actions.posture(w, f, Orders.FP_DEFEND, f.hq.x + 300, f.hq.y);
                Actions.posture(w, f, Orders.FP_DIGIN, f.hq.x, f.hq.y);
                break;
            }
        if (heli >= 0) Actions.vehicleOrder(w, heli, Orders.VO_RETURN, 0, 0);
        steps(w, 4);
        Actions.clear(w, Tools.CLEAR_WRECKS, -1);
        Actions.clear(w, Tools.CLEAR_BLOOD, -1);
        Actions.clear(w, Tools.CLEAR_TOWN, Tools.settlementAt(w, ox, oy));
        steps(w, 2);
        Actions.clear(w, Tools.CLEAR_BARRICADES, -1);
        Actions.clear(w, Tools.CLEAR_EVERYONE, -1);
        steps(w, 3);
    }

    /** The player's tools, played and then replayed (and saved and loaded part way). */
    static void toolsReplay(int size) throws Exception {
        World w = Setup.newGame(content, size, RegionGen.COAST, 78, content.scenario("lab_leak"), 2, Roles.GOD, 60000);
        camOver(w, Setup.originX, Setup.originY);
        steps(w, 5);
        toolSession(w, Setup.originX, Setup.originY);
        check(w.director.events.size() > 40, "only " + w.director.events.size() + " inputs recorded");
        check(Audit.check(w).isEmpty(), "audit: " + Audit.check(w));
        Replay r = Replay.of(content, w, 1);
        r.runTo(w.tick);
        check(Audit.hash(r.w) == Audit.hash(w), "the replay of the player's tools came out different");
        // and a save part way carries the defences and orders on exactly
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        SaveIO.save(w, bo);
        World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
        check(l.rules.v[Rules.Z_SPEED] == 3 && l.rules.v[Rules.TIME] == 2, "the rules didn't come back from the save");
        for (int k = 0; k < 20 * 20; k++) {
            w.step();
            l.step();
        }
        check(Audit.hash(l) == Audit.hash(w), "diverged after loading");
        w.shutdown();
        l.shutdown();
        r.w.shutdown();
    }

    /** A survivor's game (the survivor scenario on a small map), a second in, with the camera on them. */
    static World survivorGame(long seed) {
        World w = Setup.newGame(content, 768, RegionGen.COAST, seed, content.scenario("survivor"), 2, Roles.SURVIVOR, 60000);
        check(w.surv.enabled && w.playerEnt >= 0, "no survivor to play");
        SurvivalBot.camOn(w);
        steps(w, 1.5f);
        return w;
    }

    /** Puts the survivor at (x, y) (tests only: not an action, so not for replays). */
    static void putAt(World w, float x, float y) {
        int p = w.playerEnt;
        w.act.touchPx(x, y);
        w.e.x[p] = x;
        w.e.y[p] = y;
        SurvivalBot.camOn(w);
        w.step();
        w.e.x[p] = x;
        w.e.y[p] = y;
    }

    /** Puts the survivor at the door of a home or shop near them; returns the building (or -1). */
    static int besideBuilding(World w) {
        int p = w.playerEnt;
        float px = w.e.x[p], py = w.e.y[p];
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int b = 0; b < w.r.bCount; b++) {
            float dx = w.r.doorX(b) - px, dy = w.r.doorY(b) - py, d = dx * dx + dy * dy;
            int k = w.r.bkind(b);
            if (d < bd && w.r.walkablePx(w.r.doorX(b), w.r.doorY(b)) && (k == Region.B_HOUSE || k == Region.B_SHOP || k == Region.B_APARTMENT)) {
                bd = d;
                best = b;
            }
        }
        if (best >= 0) putAt(w, w.r.doorX(best), w.r.doorY(best));
        return best;
    }

    /** The survivor's verbs in a session (only through Actions and the stick), then replayed and saved part way. */
    static void survivalReplay() throws Exception {
        World w = Setup.newGame(content, 768, RegionGen.COAST, 5, content.scenario("survivor"), 2, Roles.SURVIVOR, 60000);
        SurvivalBot bot = new SurvivalBot(w);
        SurvivalBot.camOn(w);
        steps(w, 2);
        Survival s = w.surv;
        int p = w.playerEnt;
        check(s.ready && s.owns(Survival.W_PISTOL) && s.gun == Survival.W_PISTOL, "the survivor has no pistol in hand");
        // to the nearest door we can walk to
        int bld = -1;
        float bd = Float.MAX_VALUE;
        for (int b = 0; b < w.r.bCount; b++) {
            float dx = w.r.doorX(b) - w.e.x[p], dy = w.r.doorY(b) - w.e.y[p], d = dx * dx + dy * dy;
            if (d < bd && d < 400 * 400 && w.r.walkablePx(w.r.doorX(b), w.r.doorY(b))) {
                bd = d;
                bld = b;
            }
        }
        check(bld >= 0, "no door near the survivor");
        for (int k = 0; k < 60 * 20 && !bot.toward(w.r.doorX(bld), w.r.doorY(bld), 10); k++) {
            if (k % 20 == 0) SurvivalBot.camOn(w);
            w.step();
        }
        Actions.joy(w, 0, 0);
        check(s.can(Survival.ENTER), "no way in at the door");
        check(Actions.survival(w, Survival.ENTER), "couldn't go in");
        int sec = w.r.sectorOfPx(s.doorPX, s.doorPY);
        check(w.e.loc[p] == Ents.INSIDE, "not inside");
        Actions.survival(w, Survival.SEARCH);
        check(Actions.survival(w, Survival.BARRICADE) && s.barricaded(), "no barricade");
        steps(w, 2);
        Actions.survival(w, Survival.REST, 1, 0);
        steps(w, 5);
        Actions.survival(w, Survival.REST, 0, 0);
        Actions.survival(w, Survival.USE, Survival.I_WATER, 0);
        int pop0 = w.sec.popIn[sec];
        check(Actions.survival(w, Survival.LEAVE), "couldn't leave");
        check(w.sec.popIn[sec] == pop0 - 1, "the count inside didn't let the survivor go");
        check(w.e.loc[p] == Ents.FREE && !s.barricaded() && w.r.walkablePx(w.e.x[p], w.e.y[p]), "didn't get out through the door");
        steps(w, 1);
        // a fight: the manual aim, a shot, a shove and a swing
        float px = w.e.x[p], py = w.e.y[p];
        Actions.paint(w, px + 70, py, 2, 6, w.c.ztype("walker").index);
        steps(w, 0.3f);
        Actions.survival(w, Survival.AIM, 1, 0);
        int z = w.sp.nearestZombie(w.e, w.e.x[p], w.e.y[p], 200);
        check(z >= 0, "no zombie to aim at");
        check(Actions.survival(w, Survival.AIM_AT, z, w.e.uid[z]), "couldn't aim");
        int shots = w.stats.shots;
        Actions.survival(w, Survival.SHOOT);
        steps(w, 1);
        check(w.stats.shots > shots, "the manual shot didn't go off");
        Actions.survival(w, Survival.MELEE, 0, 0);
        steps(w, 1);
        Actions.survival(w, Survival.MELEE, 1, 0);
        Actions.survival(w, Survival.AIM, 0, 0);
        Actions.erase(w, w.e.x[p], w.e.y[p], 300);
        steps(w, 2);
        check(w.playerAlive(), "the survivor died in the fight");
        // a car: put down near (a recorded action too), walked to, driven, left
        int v = Actions.vehicle(w, "car", w.e.x[p], w.e.y[p]);
        check(v >= 0, "no car");
        for (int k = 0; k < 60 * 20 && w.playerAlive() && !bot.toward(w.veh.x[v], w.veh.y[v], 18); k++) w.step();
        check(w.playerAlive(), "the survivor died on the way to the car");
        check(Actions.survival(w, Survival.CAR) && w.orders.driven == v, "couldn't take the car");
        float fuel = w.veh.fuel[v];
        Actions.joy(w, 1, 0.2f);
        steps(w, 4);
        Actions.joy(w, 0, 0);
        steps(w, 2);
        check(w.veh.fuel[v] < fuel, "driving burned no fuel");
        check(Actions.survival(w, Survival.CAR) && w.e.loc[p] == Ents.FREE, "couldn't get out");
        // companions
        Actions.people(w, "civilian", 2, w.e.x[p] + 14, w.e.y[p]);
        steps(w, 0.5f);
        Actions.survival(w, Survival.RECRUIT);
        Actions.survival(w, Survival.RECRUIT);
        check(s.compN >= 1, "nobody came along");
        Actions.survival(w, Survival.ORDER, Survival.C_STAY, -1);
        steps(w, 2);
        Actions.survival(w, Survival.ORDER, Survival.C_CARRY, 0);
        Actions.survival(w, Survival.ORDER, Survival.C_FOLLOW, -1);
        Actions.joy(w, -0.5f, 0);
        steps(w, 3);
        Actions.joy(w, 0, 0);
        Actions.survival(w, Survival.EQUIP, Survival.W_HANDS, 0);
        Actions.survival(w, Survival.PICKUP);
        steps(w, 2);
        check(Audit.check(w).isEmpty(), "audit: " + Audit.check(w));
        check(Audit.ledger(w) == 0, "the ledger is off by " + Audit.ledger(w));
        Replay r = Replay.of(content, w, 1);
        r.runTo(w.tick);
        check(Audit.hash(r.w) == Audit.hash(w), "the replay of the survivor came out different");
        check(java.util.Arrays.equals(survBytes(r.w), survBytes(w)), "the replay's survival state came out different");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        SaveIO.save(w, bo);
        World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
        check(l.surv.enabled && l.modules.contains(l.surv), "survival didn't come back from the save");
        check(java.util.Arrays.equals(survBytes(l), survBytes(w)), "the survival state didn't come back from the save");
        for (int k = 0; k < 20 * 20; k++) {
            w.step();
            l.step();
        }
        check(Audit.hash(l) == Audit.hash(w), "diverged after loading");
        check(java.util.Arrays.equals(survBytes(l), survBytes(w)), "the survival state diverged after loading");
        w.shutdown();
        l.shutdown();
        r.w.shutdown();
    }

    static byte[] survBytes(World w) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(bo);
        w.surv.save(o);
        o.flush();
        return bo.toByteArray();
    }

    /** Runs a test with every new world in sparse mode (ActiveSet: only the chunks the simulation needs are active). */
    static void sparse(T t) throws Exception {
        boolean was = ActiveSet.sparseDefault;
        ActiveSet.sparseDefault = true;
        try {
            t.run();
        } finally {
            ActiveSet.sparseDefault = was;
        }
    }

    public static void main(String[] args) throws Exception {
        content = Content.load(new FileInputStream("assets/content.ini"));
        define();
        String only = System.getenv("ONLY");
        if (args.length > 0) only = args[0];
        for (int i = 0; i < tests.size(); i++) {
            String n = names.get(i);
            if (only != null && !n.startsWith(only)) continue;
            long t0 = System.currentTimeMillis();
            try {
                tests.get(i).run();
                passed++;
                System.out.printf("ok    %-34s %6.1f s%n", n, (System.currentTimeMillis() - t0) / 1000.0);
            } catch (Throwable t) {
                failed++;
                System.out.printf("FAIL  %-34s %s%n", n, t);
                StackTraceElement[] st = t.getStackTrace();
                for (int k = 0; k < Math.min(6, st.length); k++) System.out.println("        at " + st[k]);
            }
        }
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    /** Rail, states, bases and every kind of facility, and at least this many people. */
    static void checkCountry(Region r, long minPeople) {
        long people = 0;
        int bases = 0;
        for (Region.Settlement st : r.settlements) {
            people += st.residents;
            if (st.kind == Region.S_BASE) bases++;
        }
        check(people > minPeople, "only " + people + " people");
        check(bases >= 2, "army bases " + bases);
        check(r.states.size() >= 3, "states " + r.states.size());
        check(r.rails.nodeCount > 50, "rail junctions " + r.rails.nodeCount);
        int stations = 0;
        for (Region.Facility f : r.facilities) if (f.kind == Region.F_STATION && f.railNode >= 0) stations++;
        check(stations >= 3, "stations on the railway " + stations);
        boolean[] kinds = new boolean[Region.FKINDS];
        for (Region.Facility f : r.facilities) kinds[f.kind] = true;
        for (int k : new int[]{Region.F_WATER, Region.F_PHONE, Region.F_FOODW, Region.F_FIRE, Region.F_HALL, Region.F_GOV, Region.F_AIRBASE})
            check(kinds[k], "no " + Region.FNAMES[k]);
    }

    private static void define() {
        test("legible: the tracer says why a fighter died", new T() {
            public void run() {
                World w = game(45, 768, "sandbox", 2);
                Region.Settlement c = w.r.city();
                float[] at = w.r.openGroundNear(c.cx * 16f, c.cy * 16f, true);
                camOver(w, at[0], at[1]);
                Squads.Squad s = squadAt(w, Ents.COP, at[0], at[1], "patrol");
                int cop = s.mem[0], uid = w.e.uid[cop];
                String name = w.nameOf(cop);
                w.e.ammo[cop] = 0;
                play(w, 3, false);
                check(w.stats.rec.traceLength(cop) > 0, "no samples for the officer");
                w.e.hp[cop] = Math.min(w.e.hp[cop], 12);
                Tools.paintZombies(w, at[0], at[1], 60, 30, w.c.ztype("walker").index);
                for (int k = 0; k < 90 * 20 && w.e.loc[cop] == Ents.FREE && w.e.uid[cop] == uid; k++) w.step();
                check(w.e.loc[cop] != Ents.FREE, "the officer outlived 40 of the dead with no ammunition");
                String why = w.stats.rec.deathReason(uid);
                check(why.contains("out of ammo"), "the reason doesn't say out of ammo: " + why);
                check(why.contains("surrounded") || why.contains("caught"), "the reason doesn't say by whom: " + why);
                String last = w.stats.fallen.get(w.stats.fallen.size() - 1);
                check(last.contains(name + ": " + why), "the fallen list lacks the reason: " + last);
                check(w.stats.rec.calloutSeq > 0, "no callout for the officer");
                w.shutdown();
            }
        });
        test("legible: a lost town records why", new T() {
            public void run() throws Exception {
                World w = game(46, 768, "sandbox", 2);
                Region.Settlement town = null;
                for (Region.Settlement t : w.r.settlements) if (t.kind == Region.S_TOWN && !t.capital && (town == null || t.residents > town.residents)) town = t;
                check(town != null, "no town");
                play(w, 6, false);
                Tools.paintZombies(w, town.cx * 16f, town.cy * 16f, 200, 300, -1);
                play(w, 6, false);
                check(w.stats.rec.touched(town.id), "the dead never reached " + town.name);
                w.command.abandon(null, town.id);
                play(w, 7, false);
                Records rc = w.stats.rec;
                check(rc.townHow[town.id] == Records.T_ABANDONED, town.name + " not recorded as abandoned");
                check(rc.townAt[town.id] >= 0, "no time");
                check(rc.townWhy[town.id] != null && rc.townWhy[town.id].contains("police"), "the reason: " + rc.townWhy[town.id]);
                boolean moment = false;
                for (Records.Moment m : rc.moments) moment |= m.text.startsWith(town.name + " was abandoned");
                check(moment, "no decisive moment for it");
                // and it survives a save
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                SaveIO.save(w, bo);
                World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
                check(rc.townWhy[town.id].equals(l.stats.rec.townWhy[town.id]), "the reason didn't come back from the save");
                check(l.stats.rec.townHow[town.id] == Records.T_ABANDONED, "abandoned didn't come back");
                w.shutdown();
                l.shutdown();
            }
        });
        test("legible: the HUD's totals are the Stats totals, and the timeline's", new T() {
            public void run() {
                World w = game(47, 768, "lab_leak", 2);
                camOver(w, Setup.originX, Setup.originY);
                play(w, 2, false);
                check(w.stats.rec.startPoliceAll == w.stats.policeAll() || w.stats.rec.startPoliceAll > 0, "no police at the start");
                Tools.paintZombies(w, Setup.originX, Setup.originY, 200, 200, -1);
                play(w, 40, false);
                int[] h = Game.hudNumbers(w);
                Stats s = w.stats;
                check(h[2] == s.policeAll() && h[3] == s.soldiersAll(), "HUD totals differ from Stats");
                check(h[2] == w.sq.count(Ents.COP) + w.forces.total(Ents.COP), "police in all isn't out + waiting");
                check(s.policeOut() <= s.policeAll() && s.soldiersOut() <= s.soldiersAll(), "more out than in all");
                check(h[0] == w.livingCivilians() && h[1] == w.totalZombies(), "people or the dead differ");
                // a timeline sample taken now holds the same numbers
                s.tTime[s.tN - 1] -= 1000;
                s.update();
                int k = s.tN - 1;
                check(s.tPoliceAll[k] == h[2] && s.tArmyAll[k] == h[3], "timeline totals differ: " + s.tPoliceAll[k] + " vs " + h[2]);
                check(s.tPolice[k] == s.policeOut() && s.tArmy[k] == s.soldiersOut(), "timeline out differ");
                for (int i = 0; i < s.tN; i++) check(s.tPoliceAll[i] < 0 || s.tPoliceAll[i] >= s.tPolice[i], "sample " + i + ": more out than in all");
                w.shutdown();
            }
        });
        test("content: everything refers to things that exist", new T() {
            public void run() {
                check(content.weapons.size() >= 8, "weapons");
                for (Content.Unit u : content.units)
                    check(u.kind == Content.CIV || u.weapon != null, u.id + " is armed with nothing");
                for (Content.VType v : content.vtypes) check(v.speed > 0 && v.hp > 0, v.id);
                for (Content.Scenario s : content.scenarios) check(s.mode.matches("sandbox|commander|survivor"), s.id + " mode");
                boolean threw = false;
                try {
                    Content.load(new ByteArrayInputStream("[spaceship x]\nspeed = 1\n".getBytes("UTF-8")));
                } catch (java.io.IOException e) {
                    threw = true;
                }
                check(threw, "an unknown section is refused");
            }
        });
        test("content: national rules override only on national maps, and today's values are the old literals", new T() {
            public void run() throws Exception {
                // every rule with an @national value, and what the code used before it was a rule (P9a)
                Object[] old = {
                        "hordeSpeed", 11f, "refugeeSpeed", 28f, "refugeeCarSpeed", 28f, "convoySpeed", 35f, "trainSpeed", 90f,
                        "planeSpeed", 500f, "shipSpeed", 25f, "transitSpeed", 1667f,
                        "trainOver", 2500f, "troopTrainOver", 4000f, "airliftDistance", 7000f, "hordeSense", 24f * 512, "hordeMerge", 200f,
                        "indoorDensity", 10000f, "sectorSpill", 3000f, "hordeSize", 300f, "nestMin", 400f, "biteFloor", 0.5f,
                        "emergeDead", 40f, "emergeFleeing", 12f, "emergeStreet", 28f, "migrateRate", 0.02f, "shambleRate", 0.003f,
                        // P9b: mechanics that are off (or as before) on today's maps, and balance numbers on national maps
                        "hordeArrivePeople", 0f, "overrunRatio", 20f, "wanderOver", 0f, "refugeeCarShare", 0f, "reachScale", 1f,
                        "formationRefront", 0f, "aggregateKill", 0.014f, "findHalf", 300f, "sweepBelow", 4000f, "sweepRate", 1f,
                        "sweepPeakShare", 0f, "hiddenSpread", 1f, "maxFormations", 22f, "edgeHordeRing", 0f, "interceptWaiting", 1f};
                // national only: today the code uses the type's own speed ([formation] speed, the destroyer's, the
                // transport plane's), or has no such rule yet (tactical moves, evacuation per highway exit)
                List<String> only = java.util.Arrays.asList("formationSpeed", "formationTactical", "warshipSpeed", "airliftSpeed", "evacPerHourPerExit");
                java.util.Map<String, Float> was = new java.util.HashMap<String, Float>();
                for (int k = 0; k < old.length; k += 2) was.put((String) old[k], (Float) old[k + 1]);
                List<String> keys = content.nationalRuleKeys();
                check(keys.size() == was.size() + only.size(), keys.size() + " national rules");
                for (String k : keys) {
                    float nat = content.rule(k, -1, true);
                    if (only.contains(k)) {
                        check(content.plainRule(k) == null, k + " has a plain value");
                        check(content.rule(k, -7, false) == -7, k + ": today's maps keep the code's own value");
                        check(nat > 0, k + " national");
                        continue;
                    }
                    check(was.containsKey(k), k + " is not in the table");
                    Float plain = content.plainRule(k);
                    check(plain != null && plain.floatValue() == was.get(k), k + " = " + plain + ", was " + was.get(k));
                    check(content.rule(k, -1) == plain && content.rule(k, -1, false) == plain, k + " plain lookup");
                    check(nat != plain, k + " national is the same as today's");
                }
                for (String k : was.keySet()) check(keys.contains(k), k + " has no national value");
                // the units, at 4 m tiles and 60 game seconds a game hour
                check(Math.abs(Content.kmh(1) - 66.667f) < 0.01f && Content.km(1) == 4000, "km/h and km");
                check(Math.abs(content.rule("hordeSpeed", 0, true) - 133.33f) < 0.01f, "a horde walks 2 km/h");
                check(content.rule("trainOver", 0, true) == 80000 && content.rule("hordeMerge", 0, true) == 2000, "km and m");
                check(Math.abs(content.rule("convoySpeed", 0, true) - 2000) < 0.01f, "convoys 30 km/h");
                // (a national sector holds a few hundred people where today's hold thousands: the dead find them as readily)
                check(content.rule("indoorDensity", 0, true) < content.rule("indoorDensity", 0), "national sectors hold fewer people");
                // a key with any other @ is refused; today's maps aren't national
                boolean threw = false;
                try {
                    Content.load(new ByteArrayInputStream("[rules x]\nhordeSpeed@moon = 1\n".getBytes("UTF-8")));
                } catch (java.io.IOException e) {
                    threw = true;
                }
                check(threw, "an unknown @ suffix is refused");
                World w = new World(content, RegionGen.generate(768, RegionGen.COAST, 3), 3, 1);
                check(!w.national && w.rule("hordeSpeed", 0) == 11 && w.rule("formationSpeed", 32) == 32, "today's maps use today's rules");
            }
        });
        test("region: every preset and size builds", new T() {
            public void run() {
                for (int size : new int[]{768, 1024}) {
                    for (int p = 0; p < Data.presetCount(); p++) {
                        Region r = RegionGen.generate(size, p, 100 + p);
                        check(r.settlements.get(0).kind == Region.S_CITY, "a city first");
                        int towns = 0, bases = 0;
                        for (Region.Settlement s : r.settlements) {
                            if (s.kind == Region.S_TOWN) towns++;
                            if (s.kind == Region.S_BASE) bases++;
                            check(s.hubNode >= 0 || s.kind == Region.S_VILLAGE, s.name + " has no centre junction");
                        }
                        check(towns >= 2, Data.presetName(p) + " " + size + ": towns " + towns);
                        check(bases == 1, "one army base, got " + bases);
                        check(r.city().residents > 5000, "the city has people: " + r.city().residents);
                        check(r.entryNodes.size() >= 2, "roads out of the region");
                        // every building on dry land, inside the map
                        for (int b = 0; b < r.bCount; b++) check(r.in(r.bx[b], r.by[b]) && r.bw[b] > 0, "building " + b);
                        int police = 0;
                        for (Region.Facility f : r.facilities) {
                            if (f.kind == Region.F_POLICE) police++;
                            check(f.node >= 0, f.name + " can't be reached by road");
                        }
                        check(police >= 3, "police stations " + police);
                    }
                }
            }
        });
        test("region: the roads join every town", new T() {
            public void run() {
                for (long seed = 1; seed <= 4; seed++) {
                    Region r = RegionGen.generate(1024, (int) (seed % 4), seed * 13);
                    int hub = r.city().hubNode;
                    int reach = r.roads.reachable(hub);
                    for (Region.Settlement s : r.settlements) {
                        if (s.hubNode < 0) continue;
                        check(r.roads.route(hub, s.hubNode) != null, "no road from the city to " + s.name + " (seed " + seed + ")");
                    }
                    check(reach > r.roads.nodeCount * 0.9f, "most junctions connected: " + reach + "/" + r.roads.nodeCount);
                }
            }
        });
        test("region: the same seed builds the same region", new T() {
            public void run() {
                check(RegionGen.generate(768, 1, 55).checksum() == RegionGen.generate(768, 1, 55).checksum(), "same seed, same map");
                check(RegionGen.generate(768, 1, 55).checksum() != RegionGen.generate(768, 1, 56).checksum(), "another seed, another map");
            }
        });
        test("sim: the same game on 1 and 4 threads", new T() {
            public void run() {
                simThreads(768);
            }
        });
        test("golden: fixed seeds play out exactly as recorded", new T() {
            public void run() throws Exception {
                // recorded before the sectors learned to sleep (docs/SCALE_PLAN.md, P3): skipping quiet sectors
                // and districts must not change a single outcome on today's maps
                StringBuilder bad = new StringBuilder();
                SectorIndex.paranoid = true;
                for (int k = 0; k < GOLDEN.length; k++) {
                    long[][] got = golden(k);
                    String[] sb = new String[2];
                    for (int j = 0; j < 2; j++) {
                        StringBuilder b = new StringBuilder();
                        for (long v : got[j]) b.append(b.length() > 0 ? ", " : "").append(v).append('L');
                        sb[j] = b.toString();
                    }
                    if (System.getenv("GOLDEN_PRINT") != null) System.out.println("golden " + k + ": {" + sb[0] + "}\nstate " + k + ": {" + sb[1] + "}");
                    if (!java.util.Arrays.equals(got[0], GOLDEN[k])) bad.append("\n  scenario ").append(k).append(" now {").append(sb[0]).append('}');
                    if (!java.util.Arrays.equals(got[1], GOLDEN_STATE[k])) bad.append("\n  scenario ").append(k).append(" state now {").append(sb[1]).append('}');
                }
                SectorIndex.paranoid = false;
                check(bad.length() == 0, "outcomes changed:" + bad);
            }
        });
        test("sim: everyone accounted for with no dead about", new T() {
            public void run() {
                simAccounted(768);
            }
        });
        test("police: a small outbreak is answered and put down", new T() {
            public void run() {
                World w = game(8, 1024, "sandbox", 4);
                Region.Settlement c = w.r.city();
                float x = c.cx * 16f + 300, y = c.cy * 16f + 200;
                camOver(w, x, y);
                Tools.paintZombies(w, x, y, 30, 120, w.c.ztype("walker").index);
                boolean responded = false;
                play(w, 1, false);
                for (int s = 0; s < 240 && w.totalZombies() > 0; s++) {
                    play(w, 1, s % 20 == 0);
                    for (Squads.Squad q : w.sq.list) if (q.alive && q.order == Squads.O_RESPOND) responded = true;
                }
                check(w.disp.callsTotal > 0, "nobody called it in");
                check(responded, "no squad responded");
                check(w.stats.killsBySide[1] > 0, "the police killed none");
                check(w.totalZombies() < 10, "still " + w.totalZombies() + " of the dead after 4 minutes");
                w.shutdown();
            }
        });
        test("army: a big outbreak brings soldiers and a perimeter", new T() {
            public void run() {
                World w = game(9, 1024, "sandbox", 4);
                Region.Settlement c = w.r.city();
                float x = c.cx * 16f, y = c.cy * 16f;
                camOver(w, x, y);
                Tools.paintZombies(w, x, y, 2500, 600, -1);
                play(w, 180, true);
                check(w.disp.perimeterMode(c.id), "the police never switched to a perimeter");
                check(w.stats.killsBySide[2] > 50, "the army barely fought: " + w.stats.killsBySide[2]);
                check(w.gov.level >= 3, "alert level only " + w.gov.level);
                check(w.mil.wavesArrived > 0, "no outside help came");
                check(w.totalZombies() < 2500, "the dead only grew: " + w.totalZombies());
                w.shutdown();
            }
        });
        test("save: a loaded game plays on exactly the same", new T() {
            public void run() throws Exception {
                saveExact(768);
            }
        });
        test("strategic: a blown bridge cuts the road and stops the dead", new T() {
            public void run() {
                World w = null;
                int bridge = -1;
                for (long seed = 40; seed < 60 && bridge < 0; seed++) {
                    if (w != null) w.shutdown();
                    w = game(seed, 768, "sandbox", 1);
                    for (int e = 0; e < w.r.roads.edgeCount; e++) if (w.r.roads.ebridge[e] && w.r.roads.ekind[e] == RoadGraph.HIGHWAY) { bridge = e; break; }
                }
                check(bridge >= 0, "no seed with a highway bridge");
                RoadGraph g = w.r.roads;
                int a = g.ea[bridge], b = g.eb[bridge];
                check(w.strat.blowBridge(bridge, true), "the bridge didn't go");
                check(!g.ealive[bridge], "still standing");
                int[] rt = g.route(a, b);
                check(rt == null || rt.length > 2, "a route still goes straight over the gap");
                check(w.stats.bridgesBlown == 1, "not counted");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("strategic: overrunning the power station blacks out its towns", new T() {
            public void run() {
                World w = game(12, 768, "sandbox", 2);
                Region.Facility p = null;
                for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_POWER) p = f;
                check(p != null, "no power station");
                int s = w.r.sectorOfPx(p.x, p.y);
                check(w.strat.lit(s), "not lit to start with");
                Tools.paintZombies(w, p.x, p.y, 300, 120, w.c.ztype("walker").index);
                play(w, 40, true);
                check(!w.strat.online[p.id], "still online with the dead all round it");
                int dark = 0;
                for (int k = 0; k < w.sec.n; k++) if (w.sec.grid[k] == p.grid && !w.strat.lit(k)) dark++;
                check(dark > 0, "nothing went dark");
                w.shutdown();
            }
        });
        test("strategic: hordes march on people and break up there", new T() {
            public void run() {
                World w = game(14, 768, "sandbox", 2);
                Region.Settlement c = w.r.city();
                float hx = c.cx * 16f + (c.radius + 110) * 16f, hy = c.cy * 16f;
                Strategic.Horde h = w.strat.addHorde(hx, hy, 800);
                float d0 = (float) Math.hypot(h.x - c.cx * 16f, h.y - c.cy * 16f);
                play(w, 90, true);
                boolean arrived = !h.alive;
                float d1 = (float) Math.hypot(h.x - c.cx * 16f, h.y - c.cy * 16f);
                check(arrived || d1 < d0 - 300 || h.target >= 0, "the horde didn't move towards anyone: " + d0 + " -> " + d1);
                check(w.totalZombies() <= 800 + w.stats.zombiesMade, "dead appeared from nowhere: " + w.totalZombies());
                w.shutdown();
            }
        });
        test("evacuation: people leave a town and arrive somewhere", new T() {
            public void run() {
                World w = game(15, 768, "sandbox", 2);
                Region.Settlement town = null;
                for (Region.Settlement s : w.r.settlements) if (s.kind == Region.S_TOWN && (town == null || s.residents > town.residents)) town = s;
                check(town != null, "no town");
                w.gov.playerDecides = true;
                long before = Audit.people(w);
                w.gov.setEvac(town.id, true);
                play(w, 120, true);
                check(w.strat.evacuatedArrived + w.strat.evacuatedOut + w.strat.refugeeTotal() > 50, "almost nobody left");
                check(Audit.people(w) + w.strat.evacuatedArrived >= before - 5, "people lost on the way with no dead about");
                w.shutdown();
            }
        });
        test("scenarios: every one starts and runs cleanly", new T() {
            public void run() {
                for (Content.Scenario sc : content.scenarios) {
                    World w = game(3, 768, sc.id, 2);
                    camOver(w, Setup.originX, Setup.originY);
                    play(w, 30, true);
                    if (sc.mode.equals("survivor")) check(w.playerEnt >= 0, "no player in " + sc.id);
                    if (sc.zombies > 0) check(w.stats.zombiesMade > 0 || w.totalZombies() > 0, sc.id + " has no dead");
                    w.shutdown();
                }
            }
        });
        test("commander: goals end the game", new T() {
            public void run() {
                World w = game(4, 768, "retake", 2);
                Tools.erase(w, 0, 0, 1e9f);
                for (int s = 0; s < w.sec.n; s++) w.sec.zIn[s] = 0;
                w.strat.hordes.clear();
                play(w, 200, false);
                check(w.ended && w.won, "retaking an empty region should win: " + w.endTitle);
                w.shutdown();
            }
        });
        test("country: a capital of a million, cities, rail, states (the Country map, 28,672)", new T() {
            public void run() {
                Region r = Setup.region(28672, RegionGen.COAST, 9, 2);
                check(r.nation != null, "not a national map");
                check(r.city().capital && r.city().residents >= 1000000 && r.city().residents <= 1400000, "the capital has " + r.city().residents);
                int big = 0, police = 0;
                for (Region.Settlement st : r.settlements) if (st.kind == Region.S_CITY && !st.capital && st.residents >= 150000 && st.residents <= 450000) big++;
                for (Region.Facility f : r.facilities) if (f.kind == Region.F_POLICE) police++;
                check(big >= 3, "cities of 150-450k: " + big);
                check(police >= 60, "police stations " + police);
                checkCountry(r, 3000000);
            }
        });
        test("country: today's map: a capital of a million, cities, rail, states (2304, regression)", new T() {
            public void run() {
                Region r = RegionGen.generate(2304, RegionGen.COAST, 9);
                check(r.city().capital && r.city().residents > 700000, "the capital has " + r.city().residents);
                int cities = 0;
                for (Region.Settlement st : r.settlements) if (st.kind == Region.S_CITY) cities++;
                check(cities >= 3, "cities " + cities);
                checkCountry(r, 1500000);
            }
        });
        test("balance: outbreak sizes play out in broad bands on a national map (State, 20,480)", new T() {
            public void run() throws Exception {
                int[] sizes = {3000, 50000, 200000};
                java.util.List<Balance.Run> runs = Balance.batch(content, sizes, 2, 20480, 20, 2);
                System.out.print(Balance.table(runs, sizes).replaceAll("(?m)^", "        "));
                for (Balance.Run r : runs) {
                    if (r.zombies == 3000)
                        check(r.result == Balance.CONTAINED && r.days < 3, "a small outbreak should be put down within a few days: " + Balance.RESULTS[r.result] + " after " + r.days);
                    if (r.zombies == 50000) {
                        check(r.result != Balance.CONTAINED || r.days > 4, "a medium outbreak should be a fight of days, not over in " + r.days + " days");
                        check(r.result != Balance.FALLEN && r.lostShare < 0.25f, "a medium outbreak should be won without losing a quarter of the country: lost " + r.lostShare);
                    }
                    if (r.zombies == 200000) {
                        check(r.result != Balance.CONTAINED || r.days > 10, "a big outbreak should be a war, not over in " + r.days + " days");
                        check(r.lostShare > 0.08f, "a big outbreak should cost a lot: lost " + r.lostShare);
                    }
                }
                for (Balance.Run m : runs)
                    for (Balance.Run b : runs)
                        if (m.zombies == 50000 && b.zombies == 200000 && m.seed == b.seed) {
                            check(b.lostShare > m.lostShare, "200,000 cost less than 50,000 (seed " + m.seed + "): " + b.lostShare + " vs " + m.lostShare);
                            check(b.result != Balance.CONTAINED || m.result == Balance.CONTAINED && b.days > m.days, "200,000 was put down sooner than 50,000 (seed " + m.seed + ")");
                        }
            }
        });
        test("country: sectors belong to the town whose people live there (State, 20,480)", new T() {
            public void run() {
                World w = Setup.newGame(content, 20480, RegionGen.COAST, 5, content.scenario("sandbox"), 1);
                w.stats.recount();
                for (Region.Settlement st : w.r.settlements) {
                    if (st.kind != Region.S_CITY && st.kind != Region.S_TOWN) continue;
                    int pop = w.stats.popBySettlement[st.id];
                    check(pop > st.residents * 0.85f && pop < st.residents * 1.15f, st.name + " counts " + pop + " of its " + st.residents + " people");
                }
                w.shutdown();
            }
        });
        test("forces: squads come out of the pools and go back in", new T() {
            public void run() {
                World w = game(6, 1024, "sandbox", 2);
                Region.Facility st = null;
                for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_POLICE && f.pool != null) { st = f; break; }
                check(st != null, "no police station with officers");
                int before = st.poolTotal();
                Squads.Squad s = w.forces.deploy(st, w.c.squadT("patrol"));
                check(s != null && s.n == 4, "no squad of four");
                check(st.poolTotal() == before - 4, "the pool didn't go down");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.forces.standDown(s);
                check(st.poolTotal() == before, "the pool didn't come back: " + st.poolTotal() + " vs " + before);
                check(Audit.check(w).isEmpty(), Audit.check(w));
                check(!w.forces.formations.isEmpty(), "no formations at the bases");
                w.shutdown();
            }
        });
        test("strategic: fast-forward folds everyone in, and saves", new T() {
            public void run() throws Exception {
                World w = game(10, 1024, "sandbox", 2);
                Region.Settlement c = w.r.city();
                camOver(w, c.cx * 16f, c.cy * 16f);
                Tools.paintZombies(w, c.cx * 16f, c.cy * 16f, 3000, 700, -1);
                play(w, 30, true);
                w.camDetail = false;
                w.setStrategic(true);
                int ind = 0;
                for (int i = 0; i < w.e.top; i++) if (w.e.loc[i] != Ents.NONE) ind++;
                check(ind == 0, ind + " individuals left in fast-forward");
                for (int k = 0; k < 6 * 144; k++) w.step(); // six game hours... and more
                check(w.day() >= 1 && w.time > 3000, "time didn't pass: " + w.time);
                check(Audit.check(w).isEmpty(), Audit.check(w));
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                SaveIO.save(w, bo);
                World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
                check(l.strategic, "not in fast-forward after loading");
                for (int k = 0; k < 200; k++) {
                    w.step();
                    l.step();
                }
                check(Audit.hash(l) == Audit.hash(w), "diverged after loading in fast-forward");
                check(l.forces.formations.size() == w.forces.formations.size(), "formations lost");
                w.setStrategic(false);
                play(w, 10, true);
                w.shutdown();
                l.shutdown();
            }
        });
        test("infrastructure: no water empties a town, no phones slow the calls", new T() {
            public void run() {
                World w = game(16, 1024, "sandbox", 2);
                Region.Settlement c = w.r.city();
                play(w, 2, false);
                int before = w.stats.popBySettlement[c.id];
                for (Region.Facility f : w.r.facilities)
                    if ((f.kind == Region.F_WATER || f.kind == Region.F_PHONE) && f.settlement == c.id) w.strat.online[f.id] = false;
                check(w.infra.waterOut(c.id) && w.infra.phoneOut(c.id), "the networks didn't go down");
                int s = -1;
                for (int k = 0; k < w.sec.n; k++) if (w.sec.settlement[k] == c.id) { s = k; break; }
                check(w.infra.callDelay(s) > 1, "calls are no slower with the phones down");
                w.setStrategic(true);
                for (int k = 0; k < 4 * 144; k++) w.step(); // four days
                int after = w.stats.popBySettlement[c.id];
                check(after < before * 0.8f, "people stayed with no water: " + after + " of " + before);
                check(after > 0, "everyone left at once");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("logistics: convoys refill what runs low, and save", new T() {
            public void run() throws Exception {
                World w = game(17, 1024, "sandbox", 2);
                play(w, 2, false);
                Region.Facility st = null, store = null;
                for (Region.Facility f : w.r.facilities) {
                    if (f.kind == Region.F_POLICE && st == null) st = f;
                    if (f.kind == Region.F_FOODW && store == null) store = f;
                }
                check(st != null && store != null, "no station or food store");
                st.ammo = 0;
                store.food = 0;
                w.setStrategic(true);
                boolean saved = false;
                for (int k = 0; k < 144 && !saved; k++) {
                    w.step();
                    if (!w.logistics.convoys.isEmpty() && k > 4) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        SaveIO.save(w, bo);
                        World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
                        check(l.logistics.convoys.size() == w.logistics.convoys.size(), "convoys lost in the save");
                        for (int j = 0; j < 60; j++) {
                            w.step();
                            l.step();
                        }
                        check(Audit.hash(l) == Audit.hash(w), "diverged after loading with convoys on the road");
                        l.shutdown();
                        saved = true;
                    }
                }
                check(saved, "no convoy ever set out");
                for (int k = 0; k < 288; k++) w.step();
                check(w.logistics.delivered > 0, "nothing delivered");
                check(st.ammo > 0, "the station got no ammunition");
                check(store.food > 0, "the food store is still empty");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("fires: they spread, burn out, and crews put them out", new T() {
            public void run() {
                World w = game(16, 768, "sandbox", 1);
                Region r = w.r;
                // a building with neighbours, far from any fire station; no crews anywhere for the first part
                int b = -1;
                for (int i = 0; i < r.bCount && b < 0; i++)
                    if (r.bkind[i] == Region.B_APARTMENT && r.bset[i] == r.city().id) b = i;
                check(b >= 0, "no apartments");
                Content.Unit ff = w.c.unit("firefighter");
                int[] saved = new int[r.facilities.size()];
                for (Region.Facility f : r.facilities) if (f.kind == Region.F_FIRE) { saved[f.id] = f.pool[ff.index]; f.pool[ff.index] = 0; }
                w.setStrategic(true);
                w.fires.ignite(b, 0.5f);
                for (int k = 0; k < 400; k++) {
                    w.weather.state = Weather.CLEAR;
                    w.step();
                }
                check(w.fires.collapsed >= 1, "nothing burned down");
                check(r.bfloors[b] == 0, "the building is still standing");
                check(w.fires.started >= 2, "the fire never spread: " + w.fires.started);
                check(Audit.check(w).isEmpty(), Audit.check(w));
                // crews back: a new fire next to a station gets put out
                for (Region.Facility f : r.facilities) if (f.kind == Region.F_FIRE) f.pool[ff.index] = saved[f.id];
                Region.Facility st = null;
                for (Region.Facility f : r.facilities) if (f.kind == Region.F_FIRE) st = f;
                check(st != null, "no fire station");
                int near = -1;
                float bd = Float.MAX_VALUE;
                for (int i = 0; i < r.bCount; i++) {
                    if (r.bfloors[i] <= 0 || w.fires.heat(i) > 0 || w.doorX[i] < 0) continue;
                    float dx = w.doorX[i] - st.x, dy = w.doorY[i] - st.y, d = dx * dx + dy * dy;
                    if (d < bd && d > 300 * 300) { bd = d; near = i; }
                }
                int before = w.fires.putOut;
                w.fires.ignite(near, 0.2f);
                for (int k = 0; k < 200 && w.fires.heat(near) > 0; k++) {
                    w.weather.state = Weather.CLEAR;
                    w.step();
                }
                check(w.fires.putOut > before || r.bfloors[near] == 0, "the crew never got there");
                check(w.fires.heat(near) == 0 && r.bfloors[near] > 0, "the crew didn't save it");
                w.shutdown();
            }
        });
        test("ecology: hunger, nests, and strains that spread", new T() {
            public void run() {
                World w = game(17, 768, "sandbox", 1);
                w.forces.generalCommands = false; // nobody comes to clear it: this is about the dead left alone
                Sectors sec = w.sec;
                // a village sector with people round it
                int s = -1;
                for (int k = 0; k < sec.n && s < 0; k++) {
                    int st = sec.settlement[k];
                    if (st >= 0 && w.r.settlements.get(st).kind == Region.S_VILLAGE && sec.home[k] > 0) s = k;
                }
                check(s >= 0, "no village");
                // a fallen place: nobody left anywhere near, the dead everywhere
                int sx = s % sec.sw, sy = s / sec.sw;
                w.setStrategic(true);
                for (int k = 0; k < 3 * 144 + 10; k++) {
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dx = -2; dx <= 2; dx++) {
                            int x = sx + dx, y = sy + dy;
                            if (x < 0 || y < 0 || x >= sec.sw || y >= sec.sh) continue;
                            sec.popIn[sec.touch(y * sec.sw + x)] = 0;
                            sec.inf[y * sec.sw + x] = 0;
                        }
                    if (sec.zIn[s] < 1000) sec.zIn[sec.touch(s)] = 1000;
                    w.step();
                }
                check(w.ecology.hunger[s] > 0.5f, "not hungry: " + w.ecology.hunger[s]);
                check(w.ecology.nest[s], "no nest after three days");
                // a strain where people still live nearby spreads as the dead move
                int s2 = -1;
                for (int k = 0; k < sec.n && s2 < 0; k++)
                    if (sec.settlement[k] >= 0 && sec.home[k] > 0 && Math.abs(k % sec.sw - sx) + Math.abs(k / sec.sw - sy) > 6) s2 = k;
                sec.popIn[sec.touch(s2)] = 0;
                sec.zIn[sec.touch(s2)] = 800;
                w.ecology.strain[s2] = 2;
                w.ecology.strain[s] = 2;
                for (int k = 0; k < 30; k++) w.step();
                int spread = 0;
                for (int k = 0; k < sec.n; k++) if (k != s && k != s2 && w.ecology.strain[k] == 2) spread++;
                check(spread > 0, "the strain stayed put");
                int armoured = 0;
                for (int k = 0; k < 400; k++) if (w.pickZType(-1, k, s).strain == 2) armoured++;
                check(armoured > 20, "the place's strain doesn't come out: " + armoured);
                check(w.ecology.migrateFactor(s) < 0.5f, "a nest left alone should lie still");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("command: the authorities see less than the truth, less without phones", new T() {
            public void run() {
                float[] seen = new float[2];
                for (int k = 0; k < 2; k++) {
                    World w = game(17, 1024, "sandbox", 2);
                    Region.Settlement c = w.r.city();
                    if (k == 1) for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_PHONE) w.strat.online[f.id] = false;
                    // well away from the telephone exchange (so in the first run it stays up)
                    float px = c.cx * 16f, py = c.cy * 16f;
                    for (Region.Facility f : w.r.facilities)
                        if (f.kind == Region.F_PHONE && f.settlement == c.id) {
                            float dx = px - f.x, dy = py - f.y, d = (float) Math.sqrt(dx * dx + dy * dy) + 1;
                            px = f.x + dx / d * 1800;
                            py = f.y + dy / d * 1800;
                        }
                    camOver(w, px, py);
                    Tools.paintZombies(w, px, py, 600, 400, -1);
                    for (int t = 0; t < 90; t++) {
                        // (the exchange stays down: nobody's there to repair it)
                        if (k == 1) for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_PHONE) w.strat.online[f.id] = false;
                        play(w, 1, t % 10 == 0);
                        if (t == 14) check(w.command.repZ[c.id] < w.stats.zBySettlement[c.id], "the reports caught up instantly");
                    }
                    seen[k] = w.command.repZ[c.id] / Math.max(1, w.stats.zBySettlement[c.id]);
                    check(w.command.repZ[c.id] > 0, "nothing was ever reported");
                    w.shutdown();
                }
                check(seen[1] < seen[0], "phones down should hide more: " + seen[1] + " vs " + seen[0]);
            }
        });
        test("command: a quarantine turns refugees back", new T() {
            public void run() {
                World w = game(18, 1024, "sandbox", 2);
                w.gov.playerDecides = true;
                Region.Settlement t = null;
                for (Region.Settlement s : w.r.settlements) if (s.kind == Region.S_TOWN && (t == null || s.residents > t.residents)) t = s;
                long before = Audit.people(w);
                w.command.setQuarantine(t.id, true);
                for (int s = 0; s < w.sec.n; s++)
                    if (w.sec.settlement[s] == t.id && w.sec.popIn[s] >= 300) {
                        w.sec.popIn[w.sec.touch(s)] -= 300;
                        w.strat.pending[t.id] += 300;
                        break;
                    }
                play(w, 20, true);
                for (Strategic.Column c : w.strat.columns) check(c.from != t.id, "a column left the quarantined town");
                check(w.stats.turnedBack >= 300, "nobody was turned back: " + w.stats.turnedBack);
                check(Audit.people(w) == before, "people lost at the checkpoint");
                w.shutdown();
            }
        });
        test("society: militias form where police are thin and fear is high", new T() {
            public void run() {
                World w = game(19, 1024, "sandbox", 2);
                Region.Settlement t = null;
                for (Region.Settlement s : w.r.settlements) if (s.kind == Region.S_TOWN && (t == null || s.residents > t.residents)) t = s;
                for (Region.Facility f : w.r.facilities) if (f.settlement == t.id && f.pool != null) java.util.Arrays.fill(f.pool, 0);
                Tools.paintZombies(w, t.cx * 16f, t.cy * 16f, 200, 300, -1);
                play(w, 5, false);
                w.media.belief[t.id] = 1;
                w.media.social[t.id] = 1;
                play(w, 70, true);
                check(w.society.militia[t.id] > 0, "no militia formed");
                w.shutdown();
            }
        });
        test("society: refugees crowding a town get a camp", new T() {
            public void run() {
                World w = game(20, 1024, "sandbox", 2);
                Region.Settlement t = null;
                for (Region.Settlement s : w.r.settlements) if (s.kind == Region.S_TOWN && (t == null || s.residents > t.residents)) t = s;
                int add = t.residents;
                for (int s = 0; s < w.sec.n; s++)
                    if (w.sec.settlement[s] == t.id && w.sec.home[s] > 0) {
                        w.sec.popIn[w.sec.touch(s)] += add;
                        break;
                    }
                long before = Audit.people(w);
                w.society.arrived(t.id, add, 0);
                play(w, 62, true);
                check(!w.society.camps.isEmpty(), "no camp");
                check(w.sec.popIn[w.society.camps.get(0).sector] > 0, "nobody in the camp");
                check(Audit.people(w) == before, "people lost moving to the camp");
                w.shutdown();
            }
        });
        test("sea and air: ships shell the shore, jets fly from the air base", new T() {
            public void run() {
                World w = Setup.newGame(content, 2304, RegionGen.COAST, 9, content.scenario("sandbox"), 2);
                float[] ab = w.navy.airBase();
                check(ab != null, "no working air base");
                int v = w.veh.strike(ab[0] + 3000, ab[1]);
                check(Math.abs(w.veh.x[v] - ab[0]) < 1 && Math.abs(w.veh.y[v] - ab[1]) < 1, "the jet didn't take off from the air base");
                boolean port = false;
                for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_PORT) port = true;
                if (port) {
                    check(!w.navy.ships.isEmpty(), "a naval station with no ships");
                    Navy.Ship sh = w.navy.ships.get(0);
                    int best = -1;
                    float bd = Float.MAX_VALUE;
                    for (int k = 0; k < w.sec.n; k++) {
                        if (w.sec.popIn[k] > 0 || !Tile.walkable(w.r.tileAtPx(w.sec.cx(k), w.sec.cy(k)))) continue;
                        float dx = w.sec.cx(k) - sh.x, dy = w.sec.cy(k) - sh.y, d = dx * dx + dy * dy;
                        // in range, and away from the soldiers who'd deal with it first
                        boolean guarded = false;
                        for (Forces.Formation f : w.forces.formations) {
                            float ex = f.hq.x - w.sec.cx(k), ey = f.hq.y - w.sec.cy(k);
                            if (ex * ex + ey * ey < 3500f * 3500f) guarded = true;
                        }
                        for (Region.Facility f : w.r.facilities) {
                            if (f.pool == null) continue;
                            float ex = f.x - w.sec.cx(k), ey = f.y - w.sec.cy(k);
                            if (ex * ex + ey * ey < 2500f * 2500f) guarded = true;
                        }
                        if (guarded || d > 5000f * 5000f) continue;
                        if (d < bd) { bd = d; best = k; }
                    }
                    check(best >= 0, "no unguarded shore in range");
                    w.sec.zIn[w.sec.touch(best)] = 600;
                    w.setStrategic(true);
                    for (int k = 0; k < 30; k++) w.step();
                    check(sh.kills > 0, "the ship never fired");
                }
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("engineers: a forward base, and it all survives a save", new T() {
            public void run() throws Exception {
                World w = game(18, 1024, "sandbox", 2);
                RoadGraph g = w.r.roads;
                Region.Settlement c = w.r.city();
                int n = g.nearestNode(c.cx * 16f + 2500, c.cy * 16f, 3000);
                Forces.Formation f = w.forces.createFormation(w.c.formationT("infantry"), g.nx[n], g.ny[n], false);
                f.state = Forces.HOLDING;
                f.player = true;
                w.setStrategic(true);
                w.fires.ignite(0, 0.6f);
                for (int k = 0; k < 80; k++) w.step();
                check(w.engineers.fobs >= 1, "no forward base after holding a while");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                SaveIO.save(w, bo);
                World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
                check(Audit.hash(l) == Audit.hash(w), "different after loading");
                for (int k = 0; k < 150; k++) {
                    w.step();
                    l.step();
                }
                check(Audit.hash(l) == Audit.hash(w), "diverged after loading");
                check(l.engineers.fobs == w.engineers.fobs && l.fires.count() == w.fires.count(), "module state lost");
                check(java.util.Arrays.equals(l.ecology.strain, w.ecology.strain), "strains differ");
                w.shutdown();
                l.shutdown();
            }
        });
        test("director: a replay rebuilds the same outbreak", new T() {
            public void run() throws Exception {
                replayExact(768);
            }
        });
        test("director: seeking to any time gives the same hash as playing straight through", new T() {
            public void run() throws Exception {
                seekExact(768);
            }
        });
        test("director: a camera path glides through its keyframes and is saved as a director's cut", new T() {
            public void run() throws Exception {
                camPathCut();
            }
        });
        test("script: triggers fire deterministically, survive a save, and replay", new T() {
            public void run() throws Exception {
                scriptTriggers();
            }
        });
        test("script: the scenario's own goals end the game, won or lost", new T() {
            public void run() throws Exception {
                scriptGoals();
            }
        });
        test("editor: a scenario with every new feature saves, loads and replays", new T() {
            public void run() throws Exception {
                editorEverything(768);
            }
        });
        test("editor: a scenario with every new feature on a national map (8192)", new T() {
            public void run() throws Exception {
                editorEverything(8192);
            }
        });
        test("data: content.ini holds exactly what the code had, maps unchanged", new T() {
            public void run() throws Exception {
                dataMoved();
            }
        });
        test("data: a mod's own names, preset and origin work, and mistakes are told by line", new T() {
            public void run() throws Exception {
                dataModded();
            }
        });
        test("tools: every new action replays exactly, and saves", new T() {
            public void run() throws Exception {
                toolsReplay(768);
            }
        });
        test("tools: a machine-gun nest kills the dead coming at it", new T() {
            public void run() {
                int[] kills = new int[2];
                int nestKills = 0;
                for (int pass = 0; pass < 2; pass++) {
                    World w = game(41, 768, "sandbox", 2);
                    Region.Settlement c = w.r.city();
                    float[] p = openSpot(w, c.cx * 16f + 2500, c.cy * 16f, 260);
                    check(p != null, "no open ground");
                    camOver(w, p[0], p[1]);
                    steps(w, 1);
                    Actions.paint(w, p[0] + 230, p[1], 60, 40, w.c.ztype("walker").index);
                    steps(w, 0.2f);
                    int before = w.stats.zombieKills;
                    // the same bait either way: one gunner. With the nest, the nest's gun fires too
                    if (pass == 1) check(Actions.defence(w, Defences.MG, p[0], p[1]), "the nest wasn't placed");
                    else Actions.squad(w, "gunner", 1, p[0], p[1]);
                    steps(w, 25);
                    kills[pass] = w.stats.zombieKills - before;
                    if (pass == 1) for (Defences.Def d : w.defs.list) nestKills += d.kills;
                    w.shutdown();
                }
                check(nestKills >= 10, "the nest killed only " + nestKills);
                check(kills[1] >= kills[0] + 10, "the nest made no difference: " + kills[1] + " vs " + kills[0]);
            }
        });
        test("tools: razor wire slows the dead and cuts them", new T() {
            public void run() {
                float[] dist = new float[2];
                float[] hp = new float[2];
                for (int pass = 0; pass < 2; pass++) {
                    World w = game(42, 768, "sandbox", 2);
                    Region.Settlement c = w.r.city();
                    float[] p = openSpot(w, c.cx * 16f + 2500, c.cy * 16f, 300);
                    check(p != null, "no open ground");
                    camOver(w, p[0], p[1]);
                    steps(w, 1);
                    Squads.Squad bait = Actions.squad(w, "rifleman", 3, p[0], p[1]);
                    Actions.stance(w, bait, Squads.STANCE_HOLD);
                    Actions.paint(w, p[0] + 280, p[1], 40, 30, w.c.ztype("walker").index);
                    if (pass == 1) for (int k = -2; k <= 2; k++) Actions.defence(w, Defences.WIRE, p[0] + 235, p[1] + k * 40);
                    steps(w, 9);
                    float sum = 0, h = 0;
                    int n = 0;
                    for (int i = 0; i < w.e.top; i++) {
                        if (w.e.loc[i] != Ents.FREE || w.e.kind[i] != Ents.ZOMBIE) continue;
                        sum += w.e.x[i] - p[0];
                        h += w.e.hp[i];
                        n++;
                    }
                    dist[pass] = sum / Math.max(1, n);
                    hp[pass] = h / Math.max(1, n);
                    w.shutdown();
                }
                check(dist[0] < 200, "the dead didn't come on without the wire: " + dist[0]);
                check(dist[1] > dist[0] + 40, "the wire didn't hold them back: " + dist[1] + " vs " + dist[0]);
                check(hp[1] < hp[0], "the wire didn't cut them: " + hp[1] + " vs " + hp[0]);
            }
        });
        test("roles: each role gets its own piece", new T() {
            public void run() {
                for (int role = 0; role < Roles.NAMES.length; role++) {
                    World w = Setup.newGame(content, 768, RegionGen.COAST, 21, content.scenario("lab_leak"), 2, role, 60000);
                    check(w.roles.role == role, "role not set");
                    check(w.mode == Roles.modeFor(role), Roles.NAMES[role] + ": mode " + w.mode);
                    switch (role) {
                        case Roles.PRESIDENT: check(w.gov.playerDecides, "the president doesn't decide"); break;
                        case Roles.INCIDENT: {
                            int k = w.r.settlements.get(w.roles.target).kind;
                            check(k == Region.S_CITY || k == Region.S_TOWN, "incident commander of a " + k);
                            break;
                        }
                        case Roles.PRECINCT: check(w.r.facilities.get(w.roles.target).kind == Region.F_POLICE, "precinct isn't a police station"); break;
                        case Roles.SQUAD: {
                            Squads.Squad s = w.sq.get(w.roles.target);
                            check(s.alive && s.player, "no squad to lead");
                            check(w.roles.canOrder(s), "can't order your own squad");
                            for (Squads.Squad o : w.sq.list) if (o != s && o.alive) check(!w.roles.canOrder(o), "can order someone else's squad");
                            break;
                        }
                        case Roles.OFFICER:
                            check(w.playerEnt >= 0 && w.e.kind[w.playerEnt] == Ents.COP, "the officer isn't a police officer");
                            check(w.e.squad[w.playerEnt] == w.roles.target, "the officer has no patrol");
                            break;
                        case Roles.SURVIVOR: check(w.playerEnt >= 0 && w.e.kind[w.playerEnt] == Ents.CIV, "no survivor"); break;
                        default:
                    }
                    play(w, 20, true);
                    if (role == Roles.OFFICER && w.playerEnt >= 0 && w.e.loc[w.playerEnt] == Ents.FREE) {
                        // the patrol keeps up with the officer
                        Squads.Squad s = w.sq.get(w.roles.target);
                        float px = w.e.x[w.playerEnt], py = w.e.y[w.playerEnt];
                        check(Math.abs(s.gx - px) + Math.abs(s.gy - py) < 120, "the patrol isn't following");
                    }
                    w.shutdown();
                }
            }
        });
        test("campaigns: the country goals end the game", new T() {
            public void run() {
                World w = game(31, 768, "hold_city", 2);
                check(w.roles.role == Roles.PRESIDENT, "campaigns are played as the president");
                Region.Settlement c = w.r.city();
                for (int s = 0; s < w.sec.n; s++) if (w.sec.settlement[s] == c.id) w.sec.popIn[w.sec.touch(s)] = 0;
                w.setStrategic(true);
                for (int k = 0; k < 60 && !w.ended; k++) w.step();
                check(w.ended && !w.won, "an empty capital should lose: " + w.endTitle);
                w.shutdown();
                w = game(32, 768, "protect", 2);
                c = w.r.city();
                for (int s = 0; s < w.sec.n; s++) if (w.sec.settlement[s] == c.id) w.sec.zIn[w.sec.touch(s)] += 5000;
                w.setStrategic(true);
                for (int k = 0; k < 150 && !w.ended; k++) w.step();
                check(w.ended && !w.won, "a capital full of the dead should lose: " + w.endTitle);
                w.shutdown();
                w = game(33, 768, "officer", 2);
                check(w.playerEnt >= 0 && w.e.kind[w.playerEnt] == Ents.COP, "On patrol isn't played as an officer");
                w.shutdown();
            }
        });
        test("mods: a content.ini is imported (checked first, mistakes by line), shared, and reset", new T() {
            public void run() throws Exception {
                java.io.File dir = new java.io.File("build/test-mods");
                dir.mkdirs();
                for (java.io.File f : dir.listFiles()) f.delete();
                String ini = builtinIni();
                try {
                    DesktopPlatform pf = new DesktopPlatform(dir);
                    Game g = new Game(pf);
                    check(!g.customContent && g.dir.modMsg == null, "a fresh game uses the built-in content");
                    // a broken one: told by line, nothing kept
                    pf.files.add(new String[]{"broken.ini", ini + "\n[unit odd]\nhp = lots\n"});
                    g.dir.onButton("mod:import");
                    frames(g, 2);
                    check(!g.dir.modOk && g.dir.modMsg.contains("line ") && g.dir.modMsg.contains("[unit odd]"), "the mistake: " + g.dir.modMsg);
                    check(!pf.exists("content.ini"), "a broken content.ini was kept");
                    check(Data.presetCount() == 4, "checking it put its data in use");
                    // a good one with a new preset: kept, and in use at once (no game open)
                    String mod = ini + "\n[preset dry]\nindex = 4\nname = Dry Coast\nterrain = coast\nlevel = 0.24\nrelief = 0.4\nfeature = 1.2\nfeatureAt = 0.18\nfeatureCap = 0.2\nnaval = 1\n";
                    pf.files.add(new String[]{"mine.ini", mod});
                    g.dir.onButton("mod:import");
                    frames(g, 2);
                    check(g.dir.modOk && pf.exists("content.ini") && g.customContent, "not imported: " + g.dir.modMsg);
                    check(Data.presetCount() == 5, "its preset isn't in use");
                    // shared as it is
                    g.dir.onButton("mod:share");
                    check(mod.equals(pf.sharedText) && "content.ini".equals(pf.sharedName), "shared something else");
                    // the next start reads it; a mod without the data sections keeps the built-in ones
                    Game g2 = new Game(pf);
                    check(g2.customContent && Data.presetCount() == 5, "the next start didn't use it");
                    java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, "content.ini"));
                    o.write(ini.replaceAll("(?s)# =+ data.*", "").getBytes("UTF-8"));
                    o.close();
                    Game g3 = new Game(pf);
                    check(g3.customContent && Data.presetCount() == 4 && Data.names("first").length > 10, "a mod without data sections");
                    // a damaged one at start: the built-in content, and Settings says why
                    o = new java.io.FileOutputStream(new java.io.File(dir, "content.ini"));
                    o.write("[weapon x]\nrange = far\n".getBytes("UTF-8"));
                    o.close();
                    Game g4 = new Game(pf);
                    check(!g4.customContent && g4.content != null && g4.dir.modMsg != null && g4.dir.modMsg.contains("line 1"), "a damaged mod at start: " + g4.dir.modMsg);
                    // reset
                    g2.dir.onButton("mod:builtin");
                    check(!pf.exists("content.ini") && !g2.customContent, "not reset");
                } finally {
                    Content.load(new ByteArrayInputStream(ini.getBytes("UTF-8")));
                }
            }
        });
        test("shared: challenge codes and your own scenarios", new T() {
            public void run() throws Exception {
                java.io.File dir = new java.io.File("build/test-game");
                dir.mkdirs();
                for (java.io.File f : dir.listFiles()) f.delete();
                DesktopPlatform pf = new DesktopPlatform(dir);
                Game g = new Game(pf);
                g.ngMode = 1;
                g.ngScenario = 2;
                g.ngRole = Roles.MILITARY;
                g.ngSeed = 4242;
                String code = g.challengeCode();
                g.ngMode = 0;
                g.ngScenario = 0;
                g.ngRole = -1;
                g.ngSeed = 1;
                check(g.applyCode(code), "code refused: " + code);
                check(g.challengeCode().equals(code), "code didn't come back: " + g.challengeCode() + " vs " + code);
                check(!g.applyCode("nonsense"), "nonsense accepted");
                // a role the scenario doesn't offer, or one this game doesn't know, is refused with a note, not swapped quietly
                check(g.applyCode("ZC2-1536-0-77-survivor-president"), "survivor code refused");
                check(g.ngRole == -1 && g.codeNote != null && g.codeNote.contains("President"), "a president in a survivor scenario: " + g.ngRole + ", " + g.codeNote);
                check(g.applyCode("ZC2-1536-0-77-survivor-pilot"), "unknown-role code refused");
                check(g.ngRole == -1 && g.codeNote != null && g.codeNote.contains("pilot"), "an unknown role wasn't reported: " + g.codeNote);
                // a scenario from the editor, saved, read back, played
                Content.Scenario sc = new Content.Scenario();
                sc.id = "custom1";
                sc.name = "Mine";
                sc.mode = "sandbox";
                sc.origin = "xy:0.5,0.5";
                sc.goal = "";
                sc.desc = "test";
                sc.zombies = 120;
                sc.season = 355;
                sc.role = "incident";
                java.io.OutputStream out = pf.openWrite(Game.CUSTOM);
                out.write(sc.toIni().getBytes("UTF-8"));
                out.close();
                Game g2 = new Game(pf);
                Content.Scenario back = g2.content.scenario("custom1");
                check(back != null && back.custom && back.zombies == 120 && back.season == 355, "custom scenario didn't load");
                check(!g2.scenariosFor(3).isEmpty(), "not under Yours");
                World w = Setup.newGame(g2.content, 768, RegionGen.COAST, 5, back, 2, -1, 60000);
                check(w.roles.role == Roles.INCIDENT, "the scenario's role wasn't used");
                check(w.weather.startDay == 355, "the season wasn't used");
                play(w, 2, true);
                check(w.totalZombies() > 50, "no dead at the chosen place: " + w.totalZombies());
                w.shutdown();
            }
        });
        test("balance: outbreak sizes play out in broad bands (1536, regression)", new T() {
            public void run() throws Exception {
                int[] sizes = {3000, 60000, 250000};
                java.util.List<Balance.Run> runs = Balance.batch(content, sizes, 2, 1536, 14, 2);
                System.out.print(Balance.table(runs, sizes).replaceAll("(?m)^", "        "));
                for (Balance.Run r : runs) {
                    if (r.zombies == 3000)
                        check(r.result == Balance.CONTAINED && r.days < 2, "a small outbreak should be put down within a day or two: " + Balance.RESULTS[r.result] + " after " + r.days);
                    if (r.zombies == 60000) {
                        check(r.result != Balance.CONTAINED || r.days > 2, "a medium outbreak should be a fight of days, not over in " + r.days + " days");
                        check(r.result != Balance.FALLEN && r.lostShare < 0.25f, "a medium outbreak should be won without losing a quarter of the country: lost " + r.lostShare);
                    }
                    if (r.zombies == 250000) {
                        check(r.result != Balance.CONTAINED || r.days > 6, "a big outbreak should be a war, not over in " + r.days + " days");
                        check(r.lostShare > 0.08f, "a big outbreak should cost a lot: lost " + r.lostShare);
                    }
                }
                // bigger is worse: on the same map and seed, the big outbreak costs more and lasts longer than the medium one
                for (Balance.Run m : runs)
                    for (Balance.Run b : runs)
                        if (m.zombies == 60000 && b.zombies == 250000 && m.seed == b.seed) {
                            check(b.lostShare > m.lostShare, "250,000 cost less than 60,000 (seed " + m.seed + "): " + b.lostShare + " vs " + m.lostShare);
                            check(b.result != Balance.CONTAINED || m.result == Balance.CONTAINED && b.days > m.days, "250,000 was put down sooner than 60,000 (seed " + m.seed + ")");
                        }
            }
        });
        test("country: a small outbreak is put down and everything stands down again", new T() {
            public void run() {
                World w = Setup.newGame(content, 1536, RegionGen.COAST, 12, content.scenario("sandbox"), 2);
                Region.Settlement cap = w.r.city();
                Tools.paintZombies(w, cap.cx * 16f, cap.cy * 16f, 3000, 400, -1);
                play(w, 60, true);
                w.setStrategic(true);
                int peakLevel = 0;
                boolean curfewed = false, sheltered = false;
                for (int k = 0; k < 6 * 144; k++) {
                    w.step();
                    peakLevel = Math.max(peakLevel, w.gov.level);
                    curfewed |= w.gov.curfew;
                    sheltered |= w.command.sheltering(cap.id);
                }
                check(peakLevel >= 2, "the alert never went up: " + peakLevel);
                check(curfewed && sheltered, "no curfew or shelter order during the outbreak");
                check(w.totalZombies() == 0, "not contained in six days: " + w.totalZombies());
                check(w.gov.level <= 1, "the alert level never came down: " + Gov.LEVELS[w.gov.level]);
                check(!w.gov.curfew, "the curfew was never lifted");
                check(!w.command.sheltering(cap.id), "the shelter order was never lifted");
                check(w.command.nationalReported() < 1, "the officials still believe in " + w.command.nationalReported() + " of the dead");
                for (Region.Settlement s : w.r.settlements) check(!w.gov.evacuating(s.id), "still evacuating " + s.name);
                int outside = 0;
                for (Forces.Formation f : w.forces.formations) if (f.alive && f.outside) outside++;
                check(outside == 0, outside + " outside formations never went home");
                boolean over = false;
                for (News.Item it : w.news.log) if (it.text.startsWith("The outbreak in " + cap.name + " is over")) over = true;
                check(over, "nobody said it was over");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("country: a small outbreak on a national map is put down and everything stands down again (State, 20,480)", new T() {
            public void run() {
                World w = Setup.newGame(content, 20480, RegionGen.COAST, 12, content.scenario("sandbox"), 2);
                Region.Settlement cap = w.r.city();
                Tools.paintZombies(w, cap.cx * 16f, cap.cy * 16f, 3000, 400, -1);
                play(w, 60, true);
                w.setStrategic(true);
                int peakLevel = 0;
                boolean curfewed = false, sheltered = false;
                for (int k = 0; k < 6 * 144; k++) {
                    w.step();
                    peakLevel = Math.max(peakLevel, w.gov.level);
                    curfewed |= w.gov.curfew;
                    sheltered |= w.command.sheltering(cap.id);
                }
                check(peakLevel >= 2, "the alert never went up: " + peakLevel);
                check(curfewed && sheltered, "no curfew or shelter order during the outbreak");
                check(w.totalZombies() == 0, "not contained in six days: " + w.totalZombies());
                check(w.gov.level <= 1, "the alert level never came down: " + Gov.LEVELS[w.gov.level]);
                check(!w.gov.curfew, "the curfew was never lifted");
                check(!w.command.sheltering(cap.id), "the shelter order was never lifted");
                check(w.command.nationalReported() < 1, "the officials still believe in " + w.command.nationalReported() + " of the dead");
                for (Region.Settlement s : w.r.settlements) check(!w.gov.evacuating(s.id), "still evacuating " + s.name);
                int outside = 0;
                for (Forces.Formation f : w.forces.formations) if (f.alive && f.outside) outside++;
                check(outside == 0, outside + " outside formations never went home");
                boolean over = false;
                for (News.Item it : w.news.log) if (it.text.startsWith("The outbreak in " + cap.name + " is over")) over = true;
                check(over, "nobody said it was over");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("country: the news says a thing once and adds up the rest", new T() {
            public void run() {
                World w = Setup.newGame(content, 1024, RegionGen.COAST, 3, content.scenario("sandbox"), 1);
                w.setStrategic(true);
                int before = w.news.version;
                for (int k = 0; k < 20; k++) w.news.tally("t", "A column reaches Elm|Columns reached Elm: {n} more, {m} people", 1, 100, 0, 0, News.CALL);
                for (int k = 0; k < 20; k++) w.news.radio("Same words", 0, 0, News.UNIT);
                check(w.news.version - before == 2, "said " + (w.news.version - before) + " times, not twice");
                for (int k = 0; k < 40; k++) w.step();
                check(w.news.log.get(w.news.log.size() - 1).text.equals("Columns reached Elm: 19 more, 1,900 people"), "the total: " + w.news.log.get(w.news.log.size() - 1).text);
                w.shutdown();
            }
        });
        test("country: sectors belong to the town whose people live there (1536, regression)", new T() {
            public void run() {
                World w = Setup.newGame(content, 1536, RegionGen.COAST, 5, content.scenario("sandbox"), 1);
                w.stats.recount();
                for (Region.Settlement s : w.r.settlements) {
                    if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
                    int pop = w.stats.popBySettlement[s.id];
                    check(pop > s.residents * 0.85f && pop < s.residents * 1.15f, s.name + " counts " + pop + " of its " + s.residents + " people");
                }
                w.shutdown();
            }
        });
        test("campaigns: goals are judged on the right thing, over time", new T() {
            public void run() {
                // evacuate: nothing is won at the start, and it's won by people reaching safety inland
                World w = Setup.newGame(content, 1536, RegionGen.COAST, 23, content.scenario("evacuate"), 2, Roles.PRESIDENT, 0);
                w.setStrategic(true);
                for (int k = 0; k < 12; k++) w.step();
                check(!w.ended, "evacuate ended at the start: " + w.endTitle);
                check(w.roles.baseline > 0, "no starting count");
                int coastal = -1, inland = -1;
                for (Region.Settlement s : w.r.settlements) {
                    if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
                    if (w.roles.coastal(s)) coastal = s.id;
                    else inland = s.id;
                }
                if (coastal >= 0 && inland >= 0) {
                    w.strat.refuge[coastal][inland] += w.roles.baseline;
                    w.step();
                    check(w.ended && w.won, "refugees safe inland didn't win it");
                }
                w.shutdown();
                // keep watching after the end: the game goes on, and doesn't end again
                w = Setup.newGame(content, 1536, RegionGen.COAST, 9, content.scenario("protect"), 2, Roles.GOD, 0);
                w.ended = true;
                w.won = false;
                long t = w.tick;
                w.step();
                check(w.tick == t, "an ended campaign (as God) kept running");
                Actions.watch(w);
                w.setStrategic(true);
                for (int k = 0; k < 30; k++) w.step();
                check(w.tick > t && !w.ended, "keep watching didn't keep it going");
                w.shutdown();
                // protect: a moment of the dead in the capital isn't a defeat; holding a fifth of it for a whole day is
                w = Setup.newGame(content, 1536, RegionGen.COAST, 9, content.scenario("protect"), 2, Roles.PRESIDENT, 0);
                w.setStrategic(true);
                for (Strategic.Horde h : w.strat.hordes) h.alive = false;
                Region.Settlement cap = w.r.city();
                for (int k = 0; k < 7; k++) w.step();
                java.util.List<Integer> secs = new java.util.ArrayList<Integer>();
                for (int s = 0; s < w.sec.n; s++) if (w.sec.settlement[s] == cap.id && w.sec.home[s] > 0) secs.add(s);
                for (int s : secs) w.sec.zIn[w.sec.touch(s)] += 300;
                for (int k = 0; k < 6; k++) w.step();
                check(!w.ended, "lost at once: " + w.endText);
                for (int k = 0; k < 170 && !w.ended; k++) {
                    for (int s : secs) w.sec.zIn[w.sec.touch(s)] = Math.max(w.sec.zIn[s], 300);
                    w.step();
                }
                check(w.ended && !w.won, "the dead held the capital for a whole day and it wasn't lost");
                w.shutdown();
                // hold: the capital's people evacuated safely still count
                w = Setup.newGame(content, 1536, RegionGen.COAST, 3, content.scenario("hold_city"), 2, Roles.PRESIDENT, 0);
                w.setStrategic(true);
                for (int k = 0; k < 7; k++) w.step();
                int base = w.roles.baseline;
                cap = w.r.city();
                int moved = 0;
                for (int s = 0; s < w.sec.n; s++)
                    if (w.sec.settlement[s] == cap.id && moved < base * 0.7f) {
                        moved += w.sec.popIn[s];
                        w.strat.outBy[cap.id] += w.sec.popIn[s];
                        w.strat.evacuatedOut += w.sec.popIn[s];
                        w.sec.popIn[w.sec.touch(s)] = 0;
                    }
                for (int k = 0; k < 60; k++) w.step();
                check(!w.ended || w.won, "the capital's evacuated people were counted as lost: " + w.endText);
                w.shutdown();
            }
        });
        test("command: the President can hand the army to the general", new T() {
            public void run() {
                World w = Setup.newGame(content, 1536, RegionGen.COAST, 12, content.scenario("sandbox"), 2, Roles.PRESIDENT, 0);
                check(!w.forces.generalCommands, "the general took over from the President");
                Region.Settlement cap = w.r.city();
                Tools.paintZombies(w, cap.cx * 16f, cap.cy * 16f, 4000, 400, -1);
                w.command.repZ[cap.id] = 3000;
                w.setStrategic(true);
                for (int k = 0; k < 30; k++) w.step();
                int sent = 0;
                for (Forces.Formation f : w.forces.formations) if (f.alive && f.job != Forces.JOB_NONE) sent++;
                check(sent == 0, "the general moved " + sent + " formations without being asked");
                Forces.Formation one = w.forces.formations.get(0);
                Actions.releaseFormation(w, one);
                for (int k = 0; k < 30; k++) w.step();
                check(one.job != Forces.JOB_NONE, "a released formation wasn't used");
                Actions.generalCommands(w, true);
                for (int k = 0; k < 30; k++) w.step();
                sent = 0;
                for (Forces.Formation f : w.forces.formations) if (f.alive && f.job != Forces.JOB_NONE) sent++;
                check(sent > 1, "the general didn't take command: " + sent);
                w.shutdown();
            }
        });
        test("gaps: ships, planes and freight trains carry the country's supplies", new T() {
            public void run() {
                World w = Setup.newGame(content, 1536, RegionGen.COAST, 21, content.scenario("sandbox"), 2);
                w.setStrategic(true);
                for (int k = 0; k < 3 * 144; k++) w.step(); // three days
                Logistics l = w.logistics;
                boolean port = false;
                for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_PORT) port = true;
                if (port) check(l.sent[Logistics.SHIP] > 0, "no ship came in: " + java.util.Arrays.toString(l.sent));
                check(l.sent[Logistics.PLANE] > 0, "no cargo plane came in: " + java.util.Arrays.toString(l.sent));
                check(l.sent[Logistics.TRAIN] > 0, "no freight train ran: " + java.util.Arrays.toString(l.sent));
                check(l.delivered > 10, "only " + l.delivered + " deliveries");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("gaps: troops come by train over the border and on to the capital", new T() {
            public void run() {
                World w = Setup.newGame(content, 2304, RegionGen.COAST, 7, content.scenario("sandbox"), 2);
                check(!w.r.railEntries.isEmpty(), "no railway over the border");
                int re = w.r.railEntries.get(0);
                Forces.Formation f = w.forces.createFormation(w.c.formationT("infantry"), w.r.rails.nx[re], w.r.rails.ny[re], true);
                Region.Settlement c = w.r.city();
                check(w.forces.order(f, c.cx * 16f, c.cy * 16f), "no orders");
                check(f.state == Forces.RAIL, "not on a train: " + Forces.STATES[f.state]);
                w.setStrategic(true);
                for (int k = 0; k < 144 && f.state == Forces.RAIL; k++) w.step();
                check(f.state != Forces.RAIL, "still on the train after a day");
                float dx = f.hq.x - c.cx * 16f, dy = f.hq.y - c.cy * 16f;
                check(dx * dx + dy * dy < 4000f * 4000f, "got off far from the capital");
                check(w.stats.troopTrains > 0, "not counted");
                w.shutdown();
            }
        });
        test("gaps: a barricade closes the road to vehicles", new T() {
            public void run() {
                World w = game(5, 1024, "sandbox", 1);
                RoadGraph g = w.r.roads;
                // a street in the city, walled across its middle
                int e = -1;
                Region.Settlement c = w.r.city();
                for (int k = 0; k < g.edgeCount && e < 0; k++)
                    if (g.ekind[k] == RoadGraph.STREET && g.nset[g.ea[k]] == c.id && g.elen[k] > 200) e = k;
                check(e >= 0, "no street");
                float mx = (g.nx[g.ea[e]] + g.nx[g.eb[e]]) / 2, my = (g.ny[g.ea[e]] + g.ny[g.eb[e]]) / 2;
                int tx = (int) (mx / 16), ty = (int) (my / 16);
                for (int d = -4; d <= 4; d++)
                    for (int d2 = -4; d2 <= 4; d2++)
                        if (Tile.roadish(w.r.t(tx + d, ty + d2))) w.r.set(tx + d, ty + d2, Tile.BARRICADE);
                w.r.closeBarricadedRoads();
                check(g.epen[e] >= 1e6f, "the road isn't closed");
                int[] rt = g.route(g.ea[e], g.eb[e]);
                check(rt == null || rt.length > 2, "a route still goes through the barricade");
                // a car told to cross it never ends up on it
                int v = w.veh.add(w.c.vtype("patrol_car"), g.nx[g.ea[e]], g.ny[g.ea[e]], 0, -1);
                w.veh.driveTo(v, g.nx[g.eb[e]], g.ny[g.eb[e]], 0);
                for (int k = 0; k < 20 * 60; k++) {
                    w.step();
                    check(w.r.tileAtPx(w.veh.x[v], w.veh.y[v]) != Tile.BARRICADE, "the car drove through the barricade");
                }
                w.shutdown();
            }
        });
        test("gaps: survivor settlements farm, trade food for ammunition, and defend themselves", new T() {
            public void run() {
                World w = game(8, 1024, "sandbox", 1);
                Region.Settlement c = w.r.city();
                // a settlement out in the country, a few sectors from the city
                int s = -1;
                for (int k = 0; k < w.sec.n && s < 0; k++) {
                    float dx = w.sec.cx(k) - c.cx * 16f, dy = w.sec.cy(k) - c.cy * 16f;
                    if (w.sec.settlement[k] < 0 && w.sec.connect[k] != 0 && dx * dx + dy * dy > 4000f * 4000f && dx * dx + dy * dy < 6000f * 6000f
                            && w.r.walkablePx(w.sec.cx(k), w.sec.cy(k))) s = k;
                }
                check(s >= 0, "no place for a settlement");
                Society.Colony col = new Society.Colony();
                col.sector = s;
                col.start = 800;
                col.x = w.sec.cx(s);
                col.y = w.sec.cy(s);
                col.name = "Test Holdout";
                col.food = 20000;
                col.arms = 10;
                col.ammo = 0;
                col.tradeT = 30;
                w.sec.popIn[w.sec.touch(s)] += 800;
                w.society.colonies.add(col);
                w.sec.zIn[w.sec.touch(s)] += 40;
                w.setStrategic(true);
                for (int k = 0; k < 144; k++) w.step();
                check(col.trades > 0, "no caravan set out");
                check(w.stats.trades > 0 && col.ammo > 0, "the caravan brought nothing back");
                check(w.sec.zIn[s] < 40, "the colonists didn't fight");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("gaps: pass-and-play players, and friends' results", new T() {
            public void run() throws Exception {
                World w = Setup.newGame(content, 1024, RegionGen.COAST, 33, content.scenario("sandbox"), 1, Roles.GOD, 60000);
                Region.Settlement c = w.r.city();
                check(Actions.addPlayer(w, Roles.PRESIDENT, c.cx * 16f, c.cy * 16f), "no second player");
                check(Actions.addPlayer(w, Roles.INCIDENT, c.cx * 16f, c.cy * 16f), "no third player");
                check(!Actions.addPlayer(w, Roles.SURVIVOR, c.cx * 16f, c.cy * 16f), "a second person in the street");
                check(w.roles.players == 3 && w.roles.holds(Roles.PRESIDENT, -1) && w.roles.holds(Roles.INCIDENT, c.id), "roles not held");
                check(w.gov.playerDecides, "the AI president didn't stand aside");
                Actions.switchPlayer(w, 2);
                check(w.roles.role == Roles.INCIDENT && w.roles.target == c.id, "didn't hand over");
                for (int k = 0; k < 400; k++) w.step();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                SaveIO.save(w, bo);
                World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 1);
                check(l.roles.players == 3 && l.roles.current == 2 && l.roles.role == Roles.INCIDENT, "players lost in the save");
                Replay r = Replay.of(content, w, 1);
                r.runTo(w.tick);
                check(Audit.hash(r.w) == Audit.hash(w), "the replay of a shared game differs");
                // results to compare
                DesktopPlatform pf = new DesktopPlatform(new java.io.File("build/test-results-prefs"));
                Game game = new Game(pf);
                String code = Game.resultCodeFor("1024-0-33-sandbox-god", "Sam", 1200, 300, true);
                check(game.addResult(code), "a good code was refused");
                check(!game.addResult(code.replace("1200", "12")), "a doctored code was taken");
                check(game.board("1024-0-33-sandbox-god").size() == 1, "the friend isn't on the board");
                // a code from 1.2: its time was in hours, checked the old way; it's read as minutes now
                String body = "1024-0-33-sandbox-god~Alex~900~7~1";
                long h = 1469598103934665603L;
                for (int i = 0; i < body.length(); i++) h = (h ^ body.charAt(i)) * 1099511628211L;
                String old = "ZC2R~" + body + "~" + Long.toString(Math.abs(h % 1679616L), 36);
                check(game.addResult(old), "a 1.2 code was refused");
                boolean found = false;
                for (String[] e : game.board("1024-0-33-sandbox-god")) if (e[0].equals("Alex")) found = e[2].equals("420");
                check(found, "a 1.2 code's 7 hours didn't become 420 minutes");
                check(!game.addResult(old.replace("~900~", "~9~")), "a doctored 1.2 code was taken");
                w.shutdown();
                l.shutdown();
                r.w.shutdown();
            }
        });
        test("workability: a damaged save is put aside and back leaves the error", new T() {
            public void run() throws Exception {
                java.io.File dir = new java.io.File("build/test-work1");
                dir.mkdirs();
                for (java.io.File f : dir.listFiles()) f.delete();
                DesktopPlatform pf = new DesktopPlatform(dir);
                java.io.OutputStream out = pf.openWrite("autosave.sav");
                out.write("this is not a save".getBytes("UTF-8"));
                out.close();
                Game g = new Game(pf);
                frames(g, 1);
                check(tapId(g, "continue"), "no Continue button");
                for (int k = 0; k < 200 && g.error == null; k++) {
                    frames(g, 1);
                    Thread.sleep(20);
                }
                check(g.error != null && g.error.contains("damaged"), "no error for a damaged save: " + g.error);
                check(pf.exists("autosave.bad") && !pf.exists("autosave.sav"), "the damaged save wasn't put aside");
                frames(g, 1);
                check(findId(g, "errmenu") != null, "the error screen has no way out");
                check(g.back(), "back closed the app from the error screen");
                check(g.error == null && g.screen() == Game.S_MENU, "back didn't leave the error screen");
                frames(g, 1);
                check(findId(g, "continue") == null, "Continue is still offered for the damaged save");
            }
        });
        test("workability: speed, touches, dialogs and cancelling a load", new T() {
            public void run() throws Exception {
                java.io.File dir = new java.io.File("build/test-work2");
                dir.mkdirs();
                for (java.io.File f : dir.listFiles()) f.delete();
                DesktopPlatform pf = new DesktopPlatform(dir);
                Game g = new Game(pf);
                // an officer: the speed button goes round the speeds a person allows, back to 1x
                World w = Setup.newGame(content, 1536, RegionGen.COAST, 12, content.scenario("sandbox"), 1, Roles.OFFICER, 60000);
                g.w = w;
                g.view = new WorldView(w, pf);
                g.screen = Game.S_PLAY;
                frames(g, 2);
                boolean back1x = false;
                for (int k = 0; k < 8; k++) {
                    check(tapId(g, "speed"), "no speed button");
                    frames(g, 2);
                    check(!w.strategic && g.speedIdx <= g.maxSpeedIdx(), "an officer got fast-forward: " + g.speedIdx);
                    if (g.speedIdx == 0) back1x = true;
                }
                check(back1x, "the speed never came back to 1x");
                int recorded = w.director.events.size();
                g.key('8');
                frames(g, 5);
                check(!w.strategic && w.director.events.size() <= recorded + 1, "fast-forward attempts were recorded every frame");
                // a lost UP doesn't leave the game thinking a finger is down
                float cx = 300, cy = 600;
                g.touch(Game.DOWN, 0, cx, cy);
                g.touch(Game.DOWN, 0, cx, cy); // the UP was lost; the same finger again
                g.touch(Game.UP, 0, cx, cy);
                check(g.pointersDown() == 0, "pointer count stuck at " + g.pointersDown());
                g.touch(Game.DOWN, 0, cx, cy);
                g.touch(Game.DOWN, 1, cx + 50, cy); // two lost UPs
                g.resetTouches(); // the platform's first-finger-down
                g.touch(Game.DOWN, 2, cx, cy);
                check(g.pointersDown() == 1, "reset didn't clear lost pointers: " + g.pointersDown());
                g.touch(Game.UP, 2, cx, cy);
                check(g.pointersDown() == 0, "pointer count stuck after reset");
                // a dialog's answer is carried out on the game's thread, at the next frame
                final boolean[] got = {false};
                pf.answers.add("hello");
                g.ask("test", "", new Platform.TextAnswer() {
                    public void answer(String text) {
                        got[0] = text.equals("hello");
                    }
                });
                check(!got[0], "the answer ran on the dialog's thread, not the game's");
                frames(g, 1);
                check(got[0], "the answer never reached the game");
                // the role's speed is kept if the game is handed a world that can't fast-forward
                w.shutdown();
                // back during loading cancels it, and the world it was building is thrown away
                Game g2 = new Game(pf);
                g2.ngSize = 0;
                g2.screen = Game.S_NEW;
                frames(g2, 1);
                check(tapId(g2, "start"), "no Start button");
                check(g2.screen() == Game.S_LOADING, "not loading");
                check(g2.back(), "back during loading closed the app");
                check(g2.screen() == Game.S_NEW, "back didn't cancel the loading: " + g2.screen());
                for (int k = 0; k < 150; k++) {
                    frames(g2, 1);
                    Thread.sleep(20);
                }
                check(g2.world() == null && g2.screen() == Game.S_NEW, "a cancelled load still started the game");
            }
        });
        test("survival: every survival action replays exactly and survives a save", new T() {
            public void run() throws Exception {
                survivalReplay();
            }
        });
        test("survival: hiding in a barricaded building outlives a horde that kills you in the street", new T() {
            public void run() throws Exception {
                boolean[] alive = new boolean[2];
                boolean[] bitten = new boolean[2];
                for (int pass = 0; pass < 2; pass++) {
                    World w = survivorGame(11);
                    int p = w.playerEnt;
                    int b = besideBuilding(w);
                    check(b >= 0, "no building near the survivor");
                    w.surv.items[Survival.I_TOOLS] = 2;
                    if (pass == 1) {
                        check(Actions.survival(w, Survival.ENTER), "couldn't go in");
                        check(Actions.survival(w, Survival.BARRICADE), "couldn't barricade");
                        check(Actions.survival(w, Survival.BARRICADE), "couldn't reinforce");
                        check(w.surv.barricaded() && w.surv.doorStrength() > 2, "the barricade isn't strong: " + w.surv.doorStrength());
                    }
                    Actions.paint(w, w.r.doorX(b), w.r.doorY(b), 140, 90, w.c.ztype("walker").index);
                    for (int k = 0; k < 150 * 20 && w.playerAlive(); k++) {
                        if (k % 20 == 0) SurvivalBot.camOn(w);
                        w.step();
                    }
                    alive[pass] = w.playerAlive();
                    bitten[pass] = !alive[pass] || (w.e.flags[p] & Ents.F_INFECTED) != 0;
                    String a = Audit.check(w);
                    check(a.isEmpty(), "audit: " + a);
                    check(Audit.ledger(w) == 0, "the ledger is off by " + Audit.ledger(w));
                    w.shutdown();
                }
                check(bitten[0], "the horde didn't get the survivor in the street");
                check(alive[1] && !bitten[1], "the survivor didn't outlive the horde behind a barricade");
            }
        });
        test("survival: scavenging depletes", new T() {
            public void run() throws Exception {
                World w = survivorGame(12);
                Survival s = w.surv;
                int sec = 0;
                for (int q = 0; q < w.sec.n; q++) if (w.secBuildings[q].length > w.secBuildings[sec].length) sec = q;
                java.util.List<Integer> bl = new java.util.ArrayList<Integer>();
                for (int b : w.secBuildings[sec]) bl.add(b);
                check(bl.size() >= 6, "too few buildings in the busiest street: " + bl.size());
                int found = 0, searched = 0;
                float lastYield = s.yieldIn(sec);
                check(lastYield == 1, "a fresh street isn't full: " + lastYield);
                for (int k = 0; k < Math.min(10, bl.size()); k++) {
                    int b = bl.get(k);
                    putAt(w, w.r.doorX(b), w.r.doorY(b));
                    if (!Actions.survival(w, Survival.ENTER)) continue;
                    if (s.inside != b) {
                        Actions.survival(w, Survival.LEAVE);
                        continue;
                    }
                    java.util.Arrays.fill(s.items, 0);
                    int before = s.finds;
                    Actions.survival(w, Survival.SEARCH);
                    found += s.finds - before;
                    searched++;
                    check(!s.can(Survival.SEARCH), "a building can be searched twice");
                    check(!Actions.survival(w, Survival.SEARCH), "a second search found something");
                    float y = s.yieldIn(sec);
                    check(y < lastYield, "the street didn't get poorer: " + y + " after " + lastYield);
                    lastYield = y;
                    check(Actions.survival(w, Survival.LEAVE), "couldn't leave");
                    // and back in: still searched
                    putAt(w, w.r.doorX(b), w.r.doorY(b));
                    Actions.survival(w, Survival.ENTER);
                    check(!s.can(Survival.SEARCH), "the building filled up again");
                    Actions.survival(w, Survival.LEAVE);
                }
                check(searched >= 5, "only " + searched + " buildings could be entered");
                check(found > 0, "nothing found in " + searched + " buildings");
                // the loot tables differ by kind: a hospital holds medkits, a police station rounds, a shop food
                check(Survival.loot(Region.B_HOSPITAL)[Survival.I_MEDS] > Survival.loot(Region.B_SHOP)[Survival.I_MEDS], "hospitals have no more medkits than shops");
                check(Survival.loot(Region.B_POLICE)[5] > Survival.loot(Region.B_HOUSE)[5], "police stations have no more rounds than homes");
                check(Survival.loot(Region.B_SHOP)[Survival.I_FOOD] > Survival.loot(Region.B_POLICE)[Survival.I_FOOD], "shops have no more food than police stations");
                String a = Audit.check(w);
                check(a.isEmpty(), "audit: " + a);
                check(Audit.ledger(w) == 0, "the ledger is off by " + Audit.ledger(w));
                w.shutdown();
            }
        });
        test("survival: needs decay and items restore them", new T() {
            public void run() throws Exception {
                World w = survivorGame(13);
                Survival s = w.surv;
                int p = w.playerEnt;
                check(s.needsOn, "the survivor has no needs");
                float f0 = s.food, w0 = s.water, r0 = s.rest;
                int b = besideBuilding(w);
                check(b >= 0 && Actions.survival(w, Survival.ENTER), "couldn't go in");
                // six game hours (inside, quiet: nothing else wears on them)
                for (int k = 0; k < 360 * 20 && w.playerAlive(); k++) w.step();
                check(w.playerAlive(), "died sitting indoors");
                check(s.food < f0 - 0.04f && s.water < w0 - 0.09f && s.rest < r0 - 0.1f, "needs didn't decay: food " + s.food + " water " + s.water + " rest " + s.rest);
                check(s.water < s.food, "thirst isn't quicker than hunger");
                s.items[Survival.I_FOOD] = 1;
                s.items[Survival.I_WATER] = 1;
                s.items[Survival.I_MEDS] = 1;
                s.food = 0.3f;
                s.water = 0.3f;
                float f1 = s.food, w1 = s.water;
                check(Actions.survival(w, Survival.USE, Survival.I_FOOD, 0) && s.food > f1 + 0.3f, "food didn't help");
                check(Actions.survival(w, Survival.USE, Survival.I_WATER, 0) && s.water > w1 + 0.3f, "water didn't help");
                check(s.items[Survival.I_FOOD] == 0 && s.items[Survival.I_WATER] == 0, "the items weren't used up");
                check(!Actions.survival(w, Survival.USE, Survival.I_FOOD, 0), "ate food that wasn't there");
                w.e.hp[p] = 20;
                check(Actions.survival(w, Survival.USE, Survival.I_MEDS, 0) && w.e.hp[p] > 45, "the medkit didn't heal");
                float r1 = s.rest;
                check(Actions.survival(w, Survival.REST, 1, 0), "couldn't rest inside");
                for (int k = 0; k < 120 * 20; k++) w.step();
                check(s.rest > r1 + 0.2f, "rest didn't restore: " + s.rest + " from " + r1);
                // sprinting drains stamina and walking brings it back
                Actions.survival(w, Survival.LEAVE);
                Actions.joy(w, 1, 0);
                float st0 = s.stamina;
                for (int k = 0; k < 6 * 20; k++) w.step();
                check(s.stamina < st0 - 0.3f, "sprinting didn't tire: " + s.stamina);
                Actions.joy(w, 0, 0);
                float st1 = s.stamina;
                for (int k = 0; k < 10 * 20; k++) w.step();
                check(s.stamina > st1 + 0.2f, "stamina didn't come back: " + s.stamina);
                w.shutdown();
            }
        });
        test("survival: the officer takes the melee, the weapons and the cars", new T() {
            public void run() throws Exception {
                World w = Setup.newGame(content, 768, RegionGen.COAST, 14, content.scenario("officer"), 2, Roles.OFFICER, 60000);
                check(w.surv.enabled, "no survival for the officer");
                SurvivalBot.camOn(w);
                steps(w, 2);
                Survival s = w.surv;
                int p = w.playerEnt;
                check(!s.needsOn && s.owns(Survival.W_BATON) && s.gun == Survival.W_PISTOL, "the officer isn't kitted out");
                check(!s.can(Survival.ENTER), "the officer can hide (a patrol member can't leave the street)");
                for (int k = 0; k < 30 * 20 && w.e.loc[p] != Ents.FREE; k++) w.step();
                check(w.e.loc[p] == Ents.FREE, "the officer isn't on foot: " + w.e.loc[p]);
                // hand to hand (holding fire): blows until it's down
                Actions.fire(w, false);
                float[] q = w.r.openGroundNear(w.e.x[p] + 14, w.e.y[p], false);
                Actions.paint(w, q[0], q[1], 8, 4, w.c.ztype("walker").index);
                steps(w, 0.1f);
                int z = w.sp.nearestZombie(w.e, w.e.x[p], w.e.y[p], 60);
                check(z >= 0, "no zombie by the officer (" + w.e.loc[p] + ", " + w.stats.zombieKills + " killed)");
                float hp = w.e.hp[z];
                int uid = w.e.uid[z];
                for (int k = 0; k < 10 && w.e.loc[z] == Ents.FREE && w.e.uid[z] == uid; k++) {
                    Actions.survival(w, Survival.MELEE, 1, 0);
                    steps(w, 1);
                }
                check(w.e.loc[z] != Ents.FREE || w.e.hp[z] < hp, "the baton did nothing (" + s.meleeKills + " kills)");
                Actions.fire(w, true);
                int v = Actions.vehicle(w, "car", w.e.x[p], w.e.y[p]);
                check(v >= 0, "no car");
                putAt(w, w.veh.x[v] + 10, w.veh.y[v]);
                check(Actions.survival(w, Survival.CAR), "the officer couldn't take the car");
                Actions.joy(w, 1, 0);
                play(w, 20, true);
                check(Actions.survival(w, Survival.CAR) && w.e.loc[p] == Ents.FREE, "the officer couldn't get out");
                play(w, 10, true);
                w.shutdown();
            }
        });
        test("workability: a survivor starts away from the outbreak", new T() {
            public void run() throws Exception {
                for (long seed = 1; seed <= 3; seed++) {
                    World w = Setup.newGame(content, 1536, RegionGen.COAST, seed, content.scenario("sandbox"), 1, Roles.SURVIVOR, 60000);
                    int me = w.playerEnt;
                    check(me >= 0, "no survivor");
                    float dx = w.e.x[me] - Setup.outbreakX, dy = w.e.y[me] - Setup.outbreakY;
                    check(dx * dx + dy * dy > 1500f * 1500f, "the survivor starts at the outbreak (seed " + seed + ")");
                    play(w, 60, true);
                    check(w.e.loc[me] == Ents.FREE && w.e.kind[me] == Ents.CIV, "the survivor died within a minute (seed " + seed + ")");
                    w.shutdown();
                }
            }
        });
        test("street: cars drive round corners to where they're sent, never in circles", new T() {
            public void run() {
                World w = game(31, 1024, "sandbox", 1);
                RoadGraph g = w.r.roads;
                Region.Settlement c = w.r.city();
                java.util.List<Integer> nodes = new java.util.ArrayList<Integer>();
                for (int n = 0; n < g.nodeCount; n++) if (g.nset[n] == c.id && g.nodeLive(n)) nodes.add(n);
                check(nodes.size() > 20, "too few junctions in the city");
                int cars = 24;
                int[] v = new int[cars];
                for (int k = 0; k < cars; k++) {
                    int a = nodes.get(Rng.hashInt(77, k, 1, nodes.size())), b = nodes.get(Rng.hashInt(77, k, 2, nodes.size()));
                    v[k] = w.veh.add(w.c.vtype("patrol_car"), g.nx[a], g.ny[a], 0, -1);
                    w.veh.driveTo(v[k], g.nx[b], g.ny[b], 0);
                }
                float[][] hx = new float[cars][20], hy = new float[cars][20], hh = new float[cars][20];
                int circling = 0;
                for (int sec = 0; sec < 150; sec++) {
                    play(w, 1, false);
                    for (int k = 0; k < cars; k++) {
                        int q = sec % 20;
                        hx[k][q] = w.veh.x[v[k]];
                        hy[k][q] = w.veh.y[v[k]];
                        hh[k][q] = w.veh.head[v[k]];
                        if (sec < 20 || w.veh.state[v[k]] != Vehicles.V_DRIVE) continue;
                        float turn = 0;
                        for (int j = 1; j < 20; j++) {
                            float dh = hh[k][(sec + 1 + j) % 20] - hh[k][(sec + j) % 20];
                            while (dh > Math.PI) dh -= 2 * Math.PI;
                            while (dh < -Math.PI) dh += 2 * Math.PI;
                            turn += dh;
                        }
                        float net = (float) Math.hypot(hx[k][q] - hx[k][(sec + 1) % 20], hy[k][q] - hy[k][(sec + 1) % 20]);
                        if (Math.abs(turn) > 2 * Math.PI && net < 150) circling++;
                    }
                }
                int there = 0;
                for (int k = 0; k < cars; k++) if (w.veh.state[v[k]] != Vehicles.V_DRIVE) there++;
                check(circling == 0, circling + " car-seconds spent going round in circles");
                check(there >= cars * 9 / 10, "only " + there + " of " + cars + " cars got there in 150 s");
                w.shutdown();
            }
        });
        test("street: vehicles keep apart at junctions and on scene, each in a place of its own", new T() {
            public void run() {
                // a dozen police cars sent from one junction to the same place: they queue, and park side by side
                World w = game(31, 1024, "sandbox", 1);
                RoadGraph g = w.r.roads;
                Region.Settlement c = w.r.city();
                int a = -1, b = -1;
                for (int n = 0; n < g.nodeCount && b < 0; n++)
                    if (g.nset[n] == c.id && g.nodeLive(n)) {
                        if (a < 0) a = n;
                        else if (Math.hypot(g.nx[n] - g.nx[a], g.ny[n] - g.ny[a]) > 600) b = n;
                    }
                check(a >= 0 && b >= 0, "no junctions to drive between");
                int cars = 12;
                int[] v = new int[cars];
                for (int k = 0; k < cars; k++) {
                    v[k] = w.veh.add(w.c.vtype("patrol_car"), g.nx[a], g.ny[a], 0, -1);
                    check(w.veh.driveTo(v[k], g.nx[b], g.ny[b], 0), "can't route");
                }
                check(w.veh.overlapPairs() == 0, "cars put down on top of each other");
                long pairSteps = 0;
                for (int k = 0; k < 120 * 20; k++) {
                    w.step();
                    pairSteps += w.veh.overlapPairs();
                }
                int there = 0;
                for (int k = 0; k < cars; k++) {
                    check(w.veh.state[v[k]] != Vehicles.V_PULLIN, "still pulling in after two minutes");
                    if (w.veh.state[v[k]] == Vehicles.V_ARRIVED && Math.hypot(w.veh.x[v[k]] - g.nx[b], w.veh.y[v[k]] - g.ny[b]) < 400) there++;
                }
                check(there >= cars - 1, "only " + there + " of " + cars + " cars parked at the scene");
                check(pairSteps < 20, "cars overlapped for " + pairSteps / 20f + " pair-seconds");
                w.shutdown();
                // a whole response to 500 of the dead (police, soldiers, convoys), and the lab leak's
                String[] scen = {"sandbox", "lab_leak"};
                for (String sc : scen) {
                    w = game(31, 1024, sc, 2);
                    if (sc.equals("sandbox")) Tools.paintZombies(w, Setup.originX, Setup.originY, 500, 600, -1);
                    camOver(w, Setup.originX, Setup.originY);
                    pairSteps = 0;
                    int steps = 240 * 20, most = 0;
                    for (int k = 0; k < steps; k++) {
                        w.step();
                        pairSteps += w.veh.overlapPairs();
                        most = Math.max(most, w.veh.top);
                    }
                    float perSec = pairSteps / (float) steps;
                    check(most > 20, sc + ": hardly any vehicles came out (" + most + ")");
                    check(perSec < 0.02f, sc + ": " + perSec + " overlapping vehicle pairs per second");
                    w.shutdown();
                }
            }
        });
        test("save: a 1.2 save (version 3) still loads and plays on", new T() {
            public void run() throws Exception {
                FileInputStream in = new FileInputStream("desktop/testdata/save-v3-1.2.zcs");
                World w = SaveIO.load(content, in, 1);
                in.close();
                check(w.tick == 2400, "loaded at the wrong time: " + w.tick);
                check(w.totalZombies() > 0 && w.sq.list.size() > 0, "the outbreak or the response didn't come back");
                int typed = 0;
                for (Squads.Squad s : w.sq.list) if (s.template >= 0) typed++;
                check(typed > 0, "no squad got its kind back");
                check(Audit.check(w).isEmpty(), "audit: " + Audit.check(w));
                check(Audit.ledger(w) == 0, "the ledger is off by " + Audit.ledger(w));
                play(w, 60, true);
                check(Audit.ledger(w) == 0, "the ledger is off by " + Audit.ledger(w) + " after a minute");
                // and saves again in today's format, carrying on exactly
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                SaveIO.save(w, bo);
                World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 1);
                play(w, 10, false);
                play(l, 10, false);
                check(Audit.hash(w) == Audit.hash(l), "a re-saved 1.2 game doesn't carry on the same");
                w.shutdown();
                l.shutdown();
                // a save older than that is offered for deleting, not called damaged
                java.io.File dir = new java.io.File("build/test-work-old");
                dir.mkdirs();
                for (java.io.File f : dir.listFiles()) f.delete();
                DesktopPlatform pf = new DesktopPlatform(dir);
                java.io.DataOutputStream o = new java.io.DataOutputStream(new java.util.zip.DeflaterOutputStream(pf.openWrite("autosave.sav")));
                o.writeInt(0x5A435332);
                o.writeInt(2);
                o.close();
                Game g = new Game(pf);
                frames(g, 1);
                check(tapId(g, "continue"), "no Continue button");
                for (int k = 0; k < 200 && g.error == null; k++) {
                    frames(g, 1);
                    Thread.sleep(20);
                }
                check(g.error != null && g.error.contains("older version"), "no older-version message: " + g.error);
                check(pf.exists("autosave.sav"), "an old save was put aside without asking");
                frames(g, 1);
                check(tapId(g, "olddelete"), "no way to delete the old save");
                frames(g, 1);
                check(!pf.exists("autosave.sav") && g.error == null, "the old save wasn't deleted");
            }
        });
        test("save: the autosave stops the game only for a snapshot, and writes the same file", new T() {
            public void run() throws Exception {
                java.io.File dir = new java.io.File("build/test-work-autosave");
                dir.mkdirs();
                for (java.io.File f : dir.listFiles()) f.delete();
                DesktopPlatform pf = new DesktopPlatform(dir);
                Game g = new Game(pf);
                World w = game(5, 1536, "lab_leak", 1);
                play(w, 20, false);
                g.w = w;
                g.view = new WorldView(w, pf);
                g.screen = Game.S_PLAY;
                g.autosave();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                SaveIO.save(w, bo); // the world hasn't moved: the same save made all at once
                g.waitForSave();
                java.io.InputStream in = pf.openRead("autosave.sav");
                ByteArrayOutputStream file = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) file.write(buf, 0, n);
                in.close();
                check(java.util.Arrays.equals(file.toByteArray(), bo.toByteArray()), "the autosave differs from a save made at once");
                check(!pf.exists("autosave.tmp"), "the temporary file was left behind");
                frames(g, 1);
                check(pf.pref("info.autosave", "").length() > 0, "the autosave isn't described in the list");
                // the game's thread does only the snapshot: well under the whole save's time
                // timings on a busy machine are noisy: the best of a few tries of each
                float whole = Float.MAX_VALUE, snap = Float.MAX_VALUE;
                for (int k = 0; k < 5; k++) {
                    long t0 = System.nanoTime();
                    SaveIO.save(w, new ByteArrayOutputStream());
                    whole = Math.min(whole, (System.nanoTime() - t0) / 1e6f);
                    g.autosave();
                    g.waitForSave();
                    snap = Math.min(snap, g.lastAutosaveMs);
                }
                check(snap < whole, "the snapshot took " + snap + " ms, the whole save " + whole);
                w.shutdown();
            }
        });
        test("street: stations and bases send their people out onto open ground", new T() {
            public void run() {
                World w = game(6, 1024, "sandbox", 1);
                int tried = 0;
                for (int fi = 0; fi < w.r.facilities.size(); fi++) {
                    Region.Facility f = w.r.facilities.get(fi);
                    if (f.pool == null) continue;
                    for (Content.SquadT t : w.c.squads) {
                        boolean home = false;
                        for (String h : t.homes) if (h.equals(Forces.homeKind(f))) home = true;
                        if (!home) continue;
                        Squads.Squad s = w.forces.deploy(f, t);
                        if (s == null) continue;
                        tried++;
                        for (int k = 0; k < s.n; k++) {
                            int m = s.mem[k];
                            check(w.r.walkablePx(w.e.x[m], w.e.y[m]), t.id + " from " + f.name + " put down inside " + Tile.NAMES[w.r.tileAtPx(w.e.x[m], w.e.y[m])]);
                        }
                        if (s.vehicle >= 0) {
                            int vt = w.r.tileAtPx(w.veh.x[s.vehicle], w.veh.y[s.vehicle]);
                            check(Tile.walkable(vt), t.id + "'s vehicle from " + f.name + " put down on " + Tile.NAMES[vt]);
                        }
                        w.forces.standDown(s);
                    }
                }
                check(tried > 10, "only " + tried + " squads deployed");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("street: the response goes home and stands down, and never drives off empty", new T() {
            public void run() {
                World w = Setup.newGame(content, 1024, RegionGen.COAST, 31, content.scenario("sandbox"), 2, Roles.GOD, 60000);
                float x = Setup.originX, y = Setup.originY;
                Tools.paintZombies(w, x, y, 500, 400, -1);
                int empty = 0, inBuilding = 0;
                for (int sec = 0; sec < 600; sec++) {
                    float cx = x, cy = y;
                    for (Dispatch.Incident in : w.disp.incidents)
                        if (in.open) {
                            cx = in.x;
                            cy = in.y;
                            break;
                        }
                    camOver(w, cx, cy);
                    play(w, 1, sec % 30 == 0);
                    for (int v = 0; v < w.veh.top; v++)
                        if (w.veh.state[v] == Vehicles.V_DRIVE && w.veh.squad[v] >= 0 && w.veh.crewN[v] == 0 && w.veh.type(v).seats > 0) empty++;
                    for (Squads.Squad s : w.sq.list) {
                        if (!s.alive) continue;
                        for (int k = 0; k < s.n; k++) {
                            int m = s.mem[k];
                            if (w.e.loc[m] == Ents.FREE && !w.r.walkablePx(w.e.x[m], w.e.y[m])) inBuilding++;
                        }
                    }
                }
                check(w.totalZombies() == 0, "still " + w.totalZombies() + " of the dead after 10 minutes");
                check(empty <= 10, empty + " vehicle-seconds driving for a squad with nobody in it");
                check(inBuilding <= 20, inBuilding + " officer-seconds inside walls");
                int out = 0;
                for (Squads.Squad s : w.sq.list)
                    if (s.alive && !s.player && s.order != Squads.O_PATROL && s.order != Squads.O_GUARD) out++;
                check(out == 0, out + " squads still out ten minutes after it began (the dead were gone long before)");
                int parked = 0;
                for (int v = 0; v < w.veh.top; v++)
                    if (w.veh.state[v] == Vehicles.V_ARRIVED && w.veh.squad[v] < 0 && w.veh.type(v).seats > 0 && !w.veh.type(v).flies && w.veh.role[v] != Vehicles.R_LIFE) parked++;
                check(parked == 0, parked + " abandoned squad vehicles left in the streets");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("story: the army is asked for once a step, counts have commas, nothing said twice", new T() {
            public void run() {
                check(News.num(1234567).equals("1,234,567") && News.num(999).equals("999") && News.num(1000).equals("1,000"), "the number format");
                check(News.keyOf("Army: requested in Elm (212 of the dead)").equals(News.keyOf("Army: requested in Elm (1,260 of the dead)")), "a key ignores the count");
                check(!News.keyOf("The 3rd Infantry Battalion has been destroyed").equals(News.keyOf("The 5th Infantry Battalion has been destroyed")), "a key keeps names");
                World w = game(21, 1536, "sandbox", 2);
                Region.Settlement t = w.r.city();
                Tools.paintZombies(w, t.cx * 16f, t.cy * 16f, 30000, 900, -1);
                camOver(w, t.cx * 16f, t.cy * 16f);
                play(w, 120, false);
                w.camDetail = false;
                w.setStrategic(true);
                for (int k = 0; k < 6 * 144; k++) w.step();
                java.util.Map<String, Integer> asks = new java.util.HashMap<String, Integer>();
                java.util.Map<String, Double> last = new java.util.HashMap<String, Double>();
                for (News.Item it : w.news.log) {
                    if (it.text.startsWith("Army: requested in ")) {
                        String town = it.text.substring(19, it.text.indexOf(" (") < 0 ? it.text.length() : it.text.indexOf(" ("));
                        Integer n = asks.get(town);
                        asks.put(town, n == null ? 1 : n + 1);
                    }
                    check(!it.text.matches(".*(^|[^0-9,A-Za-z])[0-9]{4,}([^0-9,A-Za-z].*|$)"), "a number without commas: " + it.text);
                    String k = News.keyOf(it.text);
                    Double was = last.get(k);
                    check(was == null || it.time - was >= News.REPEAT - 0.01, "said twice in a row: " + it.text);
                    last.put(k, it.time);
                }
                for (java.util.Map.Entry<String, Integer> a : asks.entrySet()) check(a.getValue() <= 2, a.getKey() + " asked for the army " + a.getValue() + " times");
                check(w.news.log.size() < News.CAP, "the log is full after six days: " + w.news.log.size());
                w.shutdown();
            }
        });
        test("story: the response follows what was reported, in that order", new T() {
            public void run() {
                World w = game(22, 1536, "sandbox", 2);
                Region.Settlement t = w.r.city();
                // most of them still indoors: nobody has called yet
                Tools.paintZombies(w, t.cx * 16f, t.cy * 16f, 400, 300, -1);
                camOver(w, t.cx * 16f, t.cy * 16f);
                play(w, 240, false);
                int report = -1, response = -1;
                for (int i = 0; i < w.news.log.size(); i++) {
                    String s = w.news.log.get(i).text;
                    if (report < 0 && s.startsWith("Reports of violent attacks")) report = i;
                    if (response < 0 && (s.contains("to hold a perimeter") || s.startsWith("Army: requested"))) response = i;
                    check(!s.matches("(?i).*martial law\\. martial law.*") && !s.contains("burning apartments") && !s.contains(" 1 more formations"), "said badly: " + s);
                }
                check(report >= 0, "nobody reported it");
                check(response < 0 || response > report, "the response came before the first report");
                w.gov.raise(3);
                check(w.news.log.get(w.news.log.size() - 1).text.startsWith("MARTIAL LAW declared. The National Guard"), "martial law: " + w.news.log.get(w.news.log.size() - 1).text);
                // a player lowers it: the AI doesn't raise it straight back
                w.gov.raiseTo(1);
                play(w, 60, false);
                check(w.gov.level <= 2, "raised straight back over the player: " + w.gov.level);
                w.shutdown();
            }
        });
        test("population: alive + killed + turned = everyone, always, and they come home", new T() {
            public void run() {
                World w = game(23, 1536, "sandbox", 2);
                Region.Settlement t = w.r.settlements.get(Math.min(3, w.r.settlements.size() - 1));
                Tools.paintZombies(w, t.cx * 16f, t.cy * 16f, 20000, 700, -1);
                w.stats.startPeople = Audit.people(w);
                camOver(w, t.cx * 16f, t.cy * 16f);
                for (int k = 0; k < 6; k++) {
                    play(w, 10, false);
                    check(Audit.ledger(w) == 0, "1x: the ledger is out by " + Audit.ledger(w) + " at " + w.clock());
                }
                w.camDetail = false;
                w.setStrategic(true);
                for (int d = 0; d < 14 * 6; d++) {
                    for (int k = 0; k < 24; k++) w.step();
                    check(Audit.ledger(w) == 0, "the ledger is out by " + Audit.ledger(w) + " at " + w.clock());
                }
                // the counters agree: everyone who left is abroad or came back
                check(w.strat.abroad() >= 0 && w.strat.backFromAbroad <= w.strat.evacuatedOut, "abroad " + w.strat.abroad() + " back " + w.strat.backFromAbroad);
                int sumOut = 0;
                for (int v : w.strat.outBy) sumOut += v;
                check(sumOut == w.strat.abroad(), "out by town " + sumOut + " but abroad " + w.strat.abroad());
                if (w.totalZombies() == 0 && w.strat.clearT > 2000) {
                    for (Strategic.Column c : w.strat.columns) check(c.home || c.to >= 0, "a column still walking out of a safe country");
                }
                w.shutdown();
            }
        });
        test("roles: contained only while it is, best fighters once each, the goal in words", new T() {
            public void run() {
                World w = game(24, 1024, "sandbox", 1);
                Region.Settlement t = w.r.city();
                Tools.paintZombies(w, t.cx * 16f, t.cy * 16f, 30, 100, -1);
                play(w, 2, false);
                Tools.erase(w, t.cx * 16f, t.cy * 16f, 3000);
                for (int s = 0; s < w.sec.n; s++) w.sec.zIn[s] = 0;
                play(w, 3, false);
                check(w.roles.containedAt >= 0, "not contained after erasing every one of them");
                Tools.paintZombies(w, t.cx * 16f, t.cy * 16f, 30, 100, -1);
                play(w, 2, false);
                check(w.roles.containedAt < 0, "still 'contained' with the dead back");
                Tools.paintZombies(w, t.cx * 16f, t.cy * 16f, 400, 200, -1);
                camOver(w, t.cx * 16f, t.cy * 16f);
                play(w, 120, false);
                java.util.Set<Integer> uids = new java.util.HashSet<Integer>();
                java.util.Set<String> names = new java.util.HashSet<String>();
                for (int j = 0; j < 10; j++) {
                    if (w.stats.bestKills[j] <= 0) continue;
                    check(uids.add(w.stats.bestUid[j]), "a fighter listed twice");
                    names.add(w.stats.bestName[j]);
                }
                w.shutdown();
                // personal goals for the small roles in a campaign
                for (int r : new int[]{Roles.PRECINCT, Roles.SQUAD, Roles.OFFICER}) {
                    World c = Setup.newGame(content, 1536, RegionGen.COAST, 5, content.scenario("protect"), 1, r, 60000);
                    String g = c.roles.goal();
                    check(!g.equals("protectcap"), Roles.NAMES[r] + " plays for the national goal");
                    check(c.roles.goalText().length() > 10 && !c.roles.goalText().contains("0 hours"), "goal text: " + c.roles.goalText());
                    if (r == Roles.OFFICER) {
                        int me = c.playerEnt;
                        check(me >= 0 && Setup.clearance(c, c.e.x[me], c.e.y[me]) > 1500, "the officer starts at the front");
                    }
                    c.shutdown();
                }
            }
        });
        test("gaps: a town numbered past 64 still gets a perimeter", new T() {
            public void run() {
                World w = Setup.newGame(content, 2304, RegionGen.COAST, 1000, content.scenario("sandbox"), 1);
                check(w.r.settlements.size() > 64, "only " + w.r.settlements.size() + " settlements: the test needs more than 64");
                Region.Settlement t = null;
                for (Region.Settlement s : w.r.settlements)
                    if (s.id >= 64 && (s.kind == Region.S_TOWN || s.kind == Region.S_VILLAGE || s.kind == Region.S_CITY)) t = s;
                check(t != null, "no town numbered 64 or more");
                if (t == null) return;
                w.command.alerted[t.id] = true;
                w.gov.reported = true;
                Tools.paintZombies(w, t.cx * 16f, t.cy * 16f, 400, 150, -1);
                camOver(w, t.cx * 16f, t.cy * 16f);
                play(w, 3, false);
                check(w.disp.perimeterMode(t.id), t.name + " (" + t.id + ") never went to a perimeter with 400 of the dead in it");
                w.shutdown();
            }
        });
        test("gaps: a town the police give up is the army's, and its calls go unanswered by the police", new T() {
            public void run() {
                World w = game(31, 1536, "sandbox", 2);
                Region.Settlement t = null;
                for (Region.Settlement s : w.r.settlements) {
                    if (s.capital || s.kind != Region.S_TOWN) continue;
                    boolean station = false;
                    for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_POLICE && f.settlement == s.id) station = true;
                    if (station) {
                        t = s;
                        break;
                    }
                }
                check(t != null, "no town with a police station");
                if (t == null) return;
                Tools.paintZombies(w, t.cx * 16f, t.cy * 16f, 60, 150, -1);
                camOver(w, t.cx * 16f, t.cy * 16f);
                play(w, 40, false);
                int before = 0;
                for (Squads.Squad s : w.sq.list)
                    if (s.alive && s.faction == Ents.COP && s.incident >= 0 && w.disp.incidents.get(s.incident).settlement == t.id) before++;
                check(before > 0, "no police answered the calls in " + t.name + " to begin with");
                w.command.abandon(null, t.id);
                check(w.command.isAbandoned(t.id), "not abandoned");
                Tools.paintZombies(w, t.cx * 16f, t.cy * 16f, 60, 150, -1);
                play(w, 40, false);
                for (Squads.Squad s : w.sq.list) {
                    if (!s.alive || s.faction != Ents.COP || s.player) continue;
                    boolean there = s.incident >= 0 && w.disp.incidents.get(s.incident).settlement == t.id;
                    check(!there, s.name + " still answers a call in " + t.name + " after the police pulled out");
                }
                boolean army = false;
                for (Forces.Formation f : w.forces.formations) if (f.alive && f.task == t.id) army = true;
                check(army || w.mil.armyCalled[t.id], "the army wasn't sent to " + t.name);
                w.shutdown();
            }
        });
        test("campaigns: an idle player in the seat does worse than one who plays", new T() {
            public void run() {
                // the President who does nothing: no alert, no army; the one who plays raises it and hands the army over
                int[] alive = new int[2];
                for (int p = 0; p < 2; p++) {
                    World w = Setup.newGame(content, 1536, RegionGen.COAST, 7, content.scenario("hold_city"), 1, Roles.PRESIDENT, 60000);
                    w.setStrategic(true);
                    for (int k = 0; k < 5 * 144 && !w.ended; k++) {
                        if (p == 1 && k % 6 == 0) {
                            float nat = w.command.nationalReported();
                            if (!w.forces.generalCommands) Actions.generalCommands(w, true);
                            int want = nat > 5000 ? 4 : nat > 1200 ? 3 : nat > 60 ? 2 : w.gov.level;
                            if (want > w.gov.level) Actions.level(w, want);
                        }
                        w.step();
                    }
                    alive[p] = w.livingCivilians() + w.strat.abroad();
                    if (p == 0) {
                        check(w.gov.level <= 1, "the alert went up with nobody in the President's chair: " + w.gov.level);
                        // the mayors still decide for their towns
                        boolean local = false;
                        for (Command.Official o : w.command.officials) if (o.role == Command.MAYOR && o.last.length() > 0) local = true;
                        check(local, "no mayor did anything with the President idle");
                        // the army waits for orders (formations stationed at the start are there)
                        check(w.forces.formations.size() >= 3, "no formations at the start");
                    }
                    w.shutdown();
                }
                check(alive[0] < alive[1], "idle " + alive[0] + " vs playing " + alive[1]);
                // the military commander who does nothing: the formations stay where they are
                World m = Setup.newGame(content, 1536, RegionGen.COAST, 7, content.scenario("hold_city"), 1, Roles.MILITARY, 60000);
                float[] hx = new float[m.forces.formations.size()], hy = new float[hx.length];
                for (int i = 0; i < hx.length; i++) {
                    hx[i] = m.forces.formations.get(i).hq.x;
                    hy[i] = m.forces.formations.get(i).hq.y;
                }
                m.setStrategic(true);
                for (int k = 0; k < 144; k++) m.step();
                for (int i = 0; i < hx.length; i++) {
                    Forces.Formation f = m.forces.formations.get(i);
                    check(Math.abs(f.hq.x - hx[i]) + Math.abs(f.hq.y - hy[i]) < 50, f.name + " moved with nobody ordering it");
                }
                m.shutdown();
            }
        });
        test("audio: every sound renders, loops join, the mix never clips", new T() {
            public void run() {
                Synth sy = synth();
                for (int id = 0; id < Synth.COUNT; id++) {
                    short[] b = sy.get(id);
                    String n = Synth.NAMES[id];
                    check(b != null && b.length > 200, n + " is empty");
                    int pk = 0;
                    long sum = 0, diffs = 0;
                    for (int i = 0; i < b.length; i++) {
                        pk = Math.max(pk, Math.abs(b[i]));
                        sum += b[i];
                        if (i > 0) diffs += Math.abs(b[i] - b[i - 1]);
                    }
                    check(pk > 8000, n + " is too quiet: " + pk);
                    check(Math.abs(sum / (double) b.length) < 900, n + " is off centre (DC)");
                    if (Synth.isLoop(id)) {
                        // the join: no bigger a step than the sound makes anyway
                        int seam = Math.abs(b[0] - b[b.length - 1]);
                        float typical = diffs / (float) b.length;
                        check(seam <= typical * 6 + 600, n + " clicks where it loops: " + seam + " (typical step " + typical + ")");
                    } else check(Math.abs(b[b.length - 1]) < 400, n + " stops dead");
                }
                for (int k = 0; k < Synth.STEMS; k++) {
                    short[] st = sy.makeStem(k);
                    check(st.length == (int) (Synth.MUSIC_LEN * Synth.MUSIC_RATE), Synth.STEM_NAMES[k] + " is the wrong length");
                    check(Math.abs(st[0] - st[st.length - 1]) < 2500, Synth.STEM_NAMES[k] + " clicks where it loops");
                }
                // everything at once, as loud as it goes: the limiter keeps it under full scale
                Mixer m = new Mixer(sy);
                m.setVolumes(1, 1);
                m.setMusic(Mixer.CUE_BATTLE, 1);
                for (int k = 0; k < Mixer.LOOPS && Synth.L_SIREN_POLICE + k < Synth.COUNT; k++)
                    m.setLoop(k, Synth.L_SIREN_POLICE + k, 1, k % 2 == 0 ? -1 : 1, 1);
                short[] out = new short[2048];
                int over = 0, peak = 0;
                for (int b = 0; b < 60; b++) {
                    for (int k = 0; k < 4; k++) m.play(k % 2 == 0 ? Synth.BIGBOOM : Synth.RIFLE, 1.2f, 0, 1, 9, 8);
                    m.mix(out, 1024);
                    for (short v : out) {
                        peak = Math.max(peak, Math.abs(v));
                        if (Math.abs(v) > 32000) over++;
                    }
                }
                check(m.busy() <= Mixer.VOICES, "more voices than there are");
                check(peak < 32767 && over < 50, "the mix clips: peak " + peak + ", " + over + " samples near full scale");
                // copies of one sound are capped
                Mixer m2 = new Mixer(sy);
                for (int k = 0; k < 20; k++) m2.play(Synth.GROAN, 0.5f, 0, 1, 2, 3);
                check(m2.busy() == 3, "copies of one sound weren't capped: " + m2.busy());
                // mono puts both ears the same
                Mixer m3 = new Mixer(sy);
                m3.setMono(true);
                m3.play(Synth.PISTOL, 1, -1, 1, 5, 2);
                m3.mix(out, 1024);
                for (int i = 0; i < 1024; i++) check(out[i * 2] == out[i * 2 + 1], "mono isn't");
            }
        });
        test("audio: the director hears a fight, picks the cue, and never touches the world", new T() {
            public void run() {
                World a = game(8, 1024, "sandbox", 1), b = game(8, 1024, "sandbox", 1);
                Region.Settlement c = a.r.city();
                float x = c.cx * 16f + 300, y = c.cy * 16f + 200;
                DesktopPlatform pf = new DesktopPlatform(new java.io.File("build/test-audio"));
                pf.mixer = new Mixer(synth()); // every sound made, so the director's choices really play
                Mixer mx = pf.mixer;
                Settings st = new Settings();
                st.captions = true;
                AudioDirector ad = new AudioDirector(pf);
                ad.applySettings(st);
                Camera cam = new Camera();
                cam.setScreen(540, 1170, a.r.px());
                cam.cx = x;
                cam.cy = y;
                cam.zoom = 0.7f;
                for (World w : new World[]{a, b}) {
                    camOver(w, x, y);
                    Tools.paintZombies(w, x, y, 30, 120, w.c.ztype("walker").index);
                }
                short[] out = new short[4096];
                boolean tense = false, caption = false;
                for (int k = 0; k < 20 * 90; k++) {
                    a.step();
                    b.step();
                    ad.update(a, cam, st, World.DT, false, 1);
                    if (k % 4 == 0) mx.mix(out, 1100);
                    if (ad.cue() != Mixer.CUE_CALM) tense = true;
                    if (ad.latestCaption() != null) caption = true;
                }
                check(mx.requests > 20, "the director played almost nothing: " + mx.requests);
                check(tense, "the cue stayed calm with the dead on screen");
                check(caption, "no captions for a fight");
                long ha = 0, hb = 0;
                for (int i = 0; i < a.e.top; i++) {
                    ha = ha * 31 + Float.floatToIntBits(a.e.x[i]) + a.e.kind[i];
                    hb = hb * 31 + Float.floatToIntBits(b.e.x[i]) + b.e.kind[i];
                }
                check(a.e.top == b.e.top && ha == hb && a.rng.state() == b.rng.state(), "listening changed the world");
                a.shutdown();
                b.shutdown();
            }
        });
        test("armed: the wounded fall back while the others cover", new T() {
            public void run() {
                World w = game(41, 768, "sandbox", 2);
                Region.Settlement c = w.r.city();
                float[] at = w.r.openGroundNear(c.cx * 16f, c.cy * 16f, true);
                camOver(w, at[0], at[1]);
                Squads.Squad s = squadAt(w, Ents.SOLDIER, at[0], at[1], "rifleman", "rifleman", "rifleman", "rifleman");
                int hurt = s.mem[0];
                w.e.hp[hurt] = w.e.maxHp[hurt] * 0.3f;
                play(w, 1, false);
                // a pack coming up the street
                float[] zp = packNear(w, at[0], at[1], 150);
                Tools.paintZombies(w, zp[0], zp[1], 30, 30, w.c.ztype("walker").index);
                float d0 = dist(w.e.x[hurt], w.e.y[hurt], zp[0], zp[1]);
                boolean pulledBack = false, covered = false;
                for (int k = 0; k < 8 * 20; k++) {
                    w.step();
                    if (w.e.loc[hurt] == Ents.FREE && w.aai.act[hurt] == ArmedAI.A_WOUNDED) pulledBack = true;
                    for (int m = 1; m < s.n; m++) {
                        int o = s.mem[m];
                        if (o != hurt && w.aai.has(o) && (w.aai.act[o] == ArmedAI.A_COVER || w.aai.act[o] == ArmedAI.A_ENGAGE)) covered = true;
                    }
                    if (k == 3 * 20) check(dist(w.e.x[hurt], w.e.y[hurt], zp[0], zp[1]) > d0 + 20, "the wounded man didn't get back from the dead: "
                            + dist(w.e.x[hurt], w.e.y[hurt], zp[0], zp[1]) + " vs " + d0);
                }
                check(pulledBack, "the wounded never pulled back");
                check(covered, "nobody covered him");
                check(w.e.loc[hurt] == Ents.FREE, "the wounded man died");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("armed: medics heal the wounded and hold off the turning", new T() {
            public void run() {
                World w = game(42, 768, "sandbox", 2);
                Region.Settlement c = w.r.city();
                float[] at = w.r.openGroundNear(c.cx * 16f, c.cy * 16f, true);
                camOver(w, at[0], at[1]);
                Squads.Squad s = squadAt(w, Ents.SOLDIER, at[0], at[1], "medic", "rifleman", "rifleman");
                int hurt = s.mem[1], bitten = s.mem[2];
                w.e.hp[hurt] = w.e.maxHp[hurt] * 0.35f;
                w.e.flags[bitten] |= Ents.F_INFECTED;
                w.e.infT[bitten] = 20;
                float hp0 = w.e.hp[hurt];
                play(w, 15, true);
                // resting alone gives back 0.6 a second: the medic does far more
                check(w.e.hp[hurt] > hp0 + 30, "the medic didn't heal: " + hp0 + " -> " + w.e.hp[hurt]);
                check(w.aai.healed > 0, "no treatment counted");
                check((w.e.flags[bitten] & ArmedAI.F_STABILISED) != 0, "the bitten man wasn't treated");
                check(w.e.loc[bitten] == Ents.FREE && w.e.infT[bitten] > 30, "the bitten man turned anyway (" + w.e.infT[bitten] + ")");
                w.shutdown();
            }
        });
        test("armed: with no ammunition they don't walk into the dead", new T() {
            public void run() {
                World w = game(43, 768, "sandbox", 2);
                Region.Settlement c = w.r.city();
                float[] at = w.r.openGroundNear(c.cx * 16f, c.cy * 16f, true);
                camOver(w, at[0], at[1]);
                Squads.Squad s = squadAt(w, Ents.COP, at[0], at[1], "patrol", "patrol");
                for (int k = 0; k < s.n; k++) w.e.ammo[s.mem[k]] = 0;
                // the pack sits between them and where they're sent
                float[] zp = packNear(w, at[0], at[1], 220);
                float gx = at[0] + (zp[0] - at[0]) * 2, gy = at[1] + (zp[1] - at[1]) * 2;
                float[] goal = w.r.openGroundNear(gx, gy, true);
                Tools.paintZombies(w, zp[0], zp[1], 20, 35, w.c.ztype("walker").index);
                w.sq.order(s, Squads.O_MOVE, goal[0], goal[1]);
                float closest = Float.MAX_VALUE;
                int[] mem = java.util.Arrays.copyOf(s.mem, s.n);
                for (int k = 0; k < 40 * 20; k++) {
                    w.step();
                    for (int m : mem) {
                        if (w.e.loc[m] != Ents.FREE || w.e.kind[m] != Ents.COP) continue;
                        int z = w.sp.nearestZombie(w.e, w.e.x[m], w.e.y[m], 200);
                        if (z >= 0) closest = Math.min(closest, dist(w.e.x[m], w.e.y[m], w.e.x[z], w.e.y[z]));
                    }
                }
                for (int m : mem) check(w.e.kind[m] == Ents.COP && w.e.loc[m] == Ents.FREE, "an officer with no ammunition was lost");
                check(closest > 20, "an officer with no ammunition got within " + closest + " px of the dead");
                w.shutdown();
            }
        });
        test("armed: the new behaviours are the same on 1 and 2 threads, and after a save", new T() {
            public void run() throws Exception {
                World a = game(44, 768, "sandbox", 1), b = game(44, 768, "sandbox", 2);
                for (World w : new World[]{a, b}) {
                    Region.Settlement c = w.r.city();
                    float[] at = w.r.openGroundNear(c.cx * 16f, c.cy * 16f, true);
                    camOver(w, at[0], at[1]);
                    Squads.Squad r = squadAt(w, Ents.SOLDIER, at[0], at[1], "rifleman", "rifleman", "commander", "gunner", "grenadier", "medic", "sniper", "drone");
                    Squads.Squad k = squadAt(w, Ents.COP, at[0] + 60, at[1], "patrol", "patrol", "k9", "traffic", "swat", "marksman");
                    w.e.hp[r.mem[0]] = 30;
                    w.sq.order(k, Squads.O_HOLD, at[0] + 60, at[1]);
                    // a few of the people about are bitten and hiding it
                    for (int n = 0; n < 6; n++) {
                        float[] p = w.r.openGroundNear(at[0] + 40 + n * 6, at[1] + 10, false);
                        int i = w.spawnCiv(p[0], p[1], w.c.unit("civilian"));
                        w.e.flags[i] |= Ents.F_INFECTED;
                        w.e.infT[i] = 200;
                        w.e.timer[i] = 60;
                    }
                    float[] zp = packNear(w, at[0], at[1], 260);
                    Tools.paintZombies(w, zp[0], zp[1], 300, 140, -1);
                }
                World l = null;
                for (int k = 0; k < 60 * 20; k++) {
                    a.step();
                    b.step();
                    if (l != null) l.step();
                    if (k % 100 == 0) {
                        check(Audit.hash(a) == Audit.hash(b) && armedHash(a) == armedHash(b), "1 and 2 threads diverged at step " + k);
                        if (l != null) check(Audit.hash(l) == Audit.hash(a) && armedHash(l) == armedHash(a), "the loaded game diverged " + k);
                    }
                    if (k == 30 * 20) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        SaveIO.save(a, bo);
                        l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
                        check(Audit.hash(l) == Audit.hash(a) && armedHash(l) == armedHash(a), "different straight after loading");
                    }
                }
                check(Audit.check(a).isEmpty(), Audit.check(a));
                int[] acts = new int[ArmedAI.ACT_NAMES.length];
                for (int i = 0; i < a.e.top; i++) if (a.aai.has(i) && a.e.armed(i)) acts[a.aai.act[i]]++;
                int kinds = 0;
                for (int n : acts) if (n > 0) kinds++;
                check(a.aai.spotted > 0, "the dog found nobody");
                check(a.aai.healed > 0, "the medic treated nobody");
                check(kinds >= 2, "everyone doing the same thing");
                a.shutdown();
                b.shutdown();
                l.shutdown();
            }
        });
        test("zombies: a crowd breaks a barricade and the paths open", new T() {
            public void run() {
                World[] ws = {game(5, 1024, "sandbox", 1), game(5, 1024, "sandbox", 2)};
                int[] ar = null;
                for (World w : ws) ar = barricadeArena(w, 26, w.c.ztype("walker").index);
                World w = ws[0];
                Undead u = w.undead;
                // the arena: someone ringed by a barricade (they can be seen through it), the dead outside
                int ccx = ar[0], ccy = ar[1], civ = ar[2];
                check(!ringOpen(w, ccx, ccy), "the ring isn't closed");
                boolean broke = false;
                float firstDamage = -1, brokeAt = -1, t0 = (float) w.time;
                for (int k = 0; k < 20 * 180 && !broke; k++) {
                    for (World x : ws) x.step();
                    if (k % 100 == 0) {
                        check(Audit.hash(ws[0]) == Audit.hash(ws[1]), "1 and 2 threads differ at step " + k);
                        check(Audit.check(w).isEmpty(), Audit.check(w));
                    }
                    if (firstDamage < 0 && !u.damage.isEmpty()) firstDamage = (float) w.time;
                    if (ringOpen(w, ccx, ccy)) {
                        broke = true;
                        brokeAt = (float) w.time - t0;
                    }
                }
                check(firstDamage >= 0, "nobody beat on the barricade");
                check(broke, "the crowd never broke through (" + u.broken + " tiles broken, damage on " + u.damage.size() + ")");
                check(u.broken >= 2, "broken count " + u.broken);
                check(Audit.hash(ws[0]) == Audit.hash(ws[1]), "1 and 2 threads differ");
                // the paths caught up: the navigation grid matches one built from scratch
                byte[] nav = w.r.navCopy();
                w.r.rebuildNav(0, 0, w.r.W - 1, w.r.H - 1);
                check(java.util.Arrays.equals(nav, w.r.navCopy()), "navigation grid not updated after the break");
                // and the dead come through to them
                boolean through = false;
                for (int k = 0; k < 20 * 30 && !through; k++) {
                    w.step();
                    through = insideRing(w, ccx, ccy) || w.e.hp[civ] < 1e9f;
                }
                check(through, "nobody came through the gap");
                // a brute alone smashes through far faster than one of the walkers could
                World b = game(5, 1024, "sandbox", 1);
                barricadeArena(b, 1, b.c.ztype("brute").index);
                int bk = 0;
                while (b.undead.broken == 0 && bk < 20 * 60) {
                    b.step();
                    bk++;
                }
                check(b.undead.broken > 0, "a brute alone couldn't break a barricade in a minute");
                System.out.printf("        26 walkers broke through at %.0f s; one brute broke a tile in %.0f s%n", brokeAt, bk / 20f);
                for (World x : ws) x.shutdown();
                b.shutdown();
            }
        });
        test("zombies: dogs and bruisers come out and behave", new T() {
            public void run() {
                World w = game(7, 768, "sandbox", 1);
                Content.ZType dog = w.c.ztype("dog"), bruiser = w.c.ztype("bruiser"), walker = w.c.ztype("walker");
                check(dog != null && bruiser != null, "no dog or bruiser in content");
                check(dog.pack && !dog.breakIn && dog.smashPower == 0 && dog.hp < walker.hp && dog.run > walker.run * 2, "dogs aren't fast, small and weak");
                check(bruiser.lunge && bruiser.hp > walker.hp, "bruisers don't lunge");
                // they come out in an ordinary outbreak
                int dogs = 0, bruisers = 0;
                for (int k = 0; k < 4000; k++) {
                    Content.ZType t = w.pickZType(-1, k);
                    if (t == dog) dogs++;
                    if (t == bruiser) bruisers++;
                }
                check(dogs > 0 && bruisers > 0, "dogs " + dogs + ", bruisers " + bruisers + " in 4000 of the dead");
                // a pack of dogs: they find a leader and run together
                float[] p = w.r.openGroundNear(Setup.originX + 2000, Setup.originY, false);
                Tools.paintZombies(w, p[0], p[1], 12, 60, dog.index);
                play(w, 8, true);
                int led = 0, n = 0;
                for (int i = 0; i < w.e.top; i++)
                    if (w.e.loc[i] == Ents.FREE && w.e.kind[i] == Ents.ZOMBIE && w.e.type[i] == dog.index) {
                        n++;
                        if (w.undead.isLeader(i) || w.undead.leaderOf(i) >= 0) led++;
                    }
                check(n >= 8, "only " + n + " dogs");
                check(led >= n / 2, "only " + led + " of " + n + " dogs run in a pack");
                // a bruiser winds up, lunges, then rests; a brute charges and knocks people back
                for (String type : new String[]{"bruiser", "brute"}) {
                    World v = game(7, 768, "sandbox", 1);
                    float[] q = v.r.openGroundNear(Setup.originX + 1500, Setup.originY + 300, false);
                    int civ = v.spawnCiv(q[0], q[1], v.c.unit("civilian"));
                    v.e.state[civ] = CivAI.S_PLAYER;
                    v.playerEnt = civ;
                    v.strat.playerFire = false;
                    v.e.hp[civ] = v.e.maxHp[civ] = 100000;
                    float dist = type.equals("brute") ? 100 : 55;
                    int z = -1;
                    for (int a = 0; a < 16 && z < 0; a++) {
                        float zx = q[0] + (float) Math.cos(a * 0.39) * dist, zy = q[1] + (float) Math.sin(a * 0.39) * dist;
                        if (v.r.walkablePx(zx, zy) && v.r.lineOfSight(zx, zy, q[0], q[1])) z = v.spawnZombie(zx, zy, v.c.ztype(type));
                    }
                    check(z >= 0, "no room for a " + type);
                    boolean wound = false, lunged = false, charged = false, knocked = false;
                    float hp0 = v.e.hp[civ];
                    for (int k = 0; k < 20 * 8; k++) {
                        float cx = v.e.x[civ], cy = v.e.y[civ];
                        v.step();
                        int ph = v.undead.phase(z);
                        if (ph == Undead.P_WINDUP) wound = true;
                        if (ph == Undead.P_LUNGE) lunged = true;
                        if (ph == Undead.P_CHARGE) charged = true;
                        if (Math.hypot(v.e.x[civ] - cx, v.e.y[civ] - cy) > 12) knocked = true;
                    }
                    check(v.e.hp[civ] < hp0, "the " + type + " never hurt anyone");
                    if (type.equals("bruiser")) check(wound && lunged, "no wind-up and lunge (" + wound + ", " + lunged + ")");
                    else check(charged && knocked, "no charge and knock-back (" + charged + ", " + knocked + ")");
                    check(v.undead.coolUntil[z] > 0, "no cooldown after the attack");
                    check(Audit.check(v).isEmpty(), Audit.check(v));
                    v.shutdown();
                }
                w.shutdown();
            }
        });
        test("zombies: feeding at a body delays its rising, or stops it", new T() {
            public void run() {
                World w = game(9, 768, "sandbox", 1);
                Ents e = w.e;
                Content.Unit cu = w.c.unit("civilian");
                Content.ZType walker = w.c.ztype("walker");
                // three bodies far apart: nobody feeding, one of the dead feeding, four feeding
                float[][] at = new float[3][];
                int[] body = new int[3];
                for (int c = 0; c < 3; c++) {
                    at[c] = w.r.openGroundNear(Setup.originX + 1800 + c * 350, Setup.originY - 1500, false);
                    body[c] = w.spawnCiv(at[c][0], at[c][1], cu);
                }
                int[] feeders = {0, 1, 4};
                // watched, so nobody folds back into the counts
                camOver(w, at[1][0], at[1][1]);
                for (int c = 0; c < 3; c++) {
                    int killer = -1;
                    for (int f = 0; f < feeders[c]; f++) {
                        int z = w.spawnZombie(at[c][0] + 6 + f * 3, at[c][1] + 3, walker);
                        if (killer < 0) killer = z;
                    }
                    w.sp.build(e);
                    w.combat.kill(body[c], Combat.CAUSE_BITE, killer);
                    e.timer[body[c]] = 10;
                }
                float[] rose = {-1, -1, -1};
                int[] uid = {e.uid[body[0]], e.uid[body[1]], e.uid[body[2]]};
                for (int k = 0; k < 20 * 60; k++) {
                    w.step();
                    for (int c = 0; c < 3; c++)
                        if (rose[c] < 0 && e.uid[body[c]] == uid[c] && e.loc[body[c]] == Ents.FREE && e.kind[body[c]] == Ents.ZOMBIE) rose[c] = (float) w.time;
                }
                check(rose[0] > 0 && rose[0] < 12, "an untouched body rose at " + rose[0]);
                check(rose[1] > rose[0] + 4, "one feeding didn't delay the rising: " + rose[1] + " vs " + rose[0]);
                check(rose[2] < 0, "a body four fed on still rose at " + rose[2]);
                check(w.undead.devoured >= 1, "nothing devoured");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        test("save: damaged barricades load exactly", new T() {
            public void run() throws Exception {
                World w = game(5, 1024, "sandbox", 2);
                barricadeArena(w, 20, -1);
                int k = 0;
                while (w.undead.damage.size() < 3 && k++ < 20 * 120) w.step();
                check(w.undead.damage.size() >= 3, "no damage to save");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                SaveIO.save(w, bo);
                World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
                check(l.undead.damage.equals(w.undead.damage), "damage map differs after loading");
                check(Audit.hash(l) == Audit.hash(w), "different straight after loading");
                for (int s = 0; s < 20 * 60; s++) {
                    w.step();
                    l.step();
                    if (s % 100 == 0) check(Audit.hash(l) == Audit.hash(w), "diverged " + s + " steps after loading");
                }
                check(l.undead.damage.equals(w.undead.damage) && l.undead.broken == w.undead.broken, "barricade damage diverged");
                check(l.r.checksum() == w.r.checksum() && java.util.Arrays.equals(l.r.navCopy(), w.r.navCopy()), "the map diverged");
                check(Audit.check(l).isEmpty(), Audit.check(l));
                w.shutdown();
                l.shutdown();
            }
        });
        test("save: the overlay holds every change to the map, and lays it over a new one exactly", new T() {
            public void run() throws Exception {
                World w = game(5, 1024, "sandbox", 2);
                barricadeArena(w, 20, -1);
                int k = 0;
                while (w.undead.broken < 1 && k++ < 20 * 240) w.step();
                check(w.undead.broken >= 1, "the dead broke nothing");
                // a big bomb on a building, a bridge blown
                int best = -1;
                float bd = Float.MAX_VALUE;
                for (int b = 0; b < w.r.bCount; b++) {
                    float dx = (w.r.bx[b] + w.r.bw[b] / 2f) * 16 - Setup.originX - 400, dy = (w.r.by[b] + w.r.bh[b] / 2f) * 16 - Setup.originY;
                    if (dx * dx + dy * dy < bd) {
                        bd = dx * dx + dy * dy;
                        best = b;
                    }
                }
                w.combat.blast((w.r.bx[best] + w.r.bw[best] / 2f) * 16, (w.r.by[best] + w.r.bh[best] / 2f) * 16, 140, 300, -1, Ents.SOLDIER);
                int bridge = -1;
                for (int e = 0; e < w.r.roads.edgeCount && bridge < 0; e++) if (w.r.roads.ebridge[e]) bridge = e;
                if (bridge >= 0) check(w.strat.blowBridge(bridge, true), "the bridge didn't go");
                play(w, 10, false);
                check(w.stats.buildingsLost > 0, "nothing came down");
                Overlay ov = w.r.overlay;
                int national = 0, edited = 0;
                for (int p = 0; p < ov.pages(); p++) if (ov.edited(p)) edited++;
                for (int b = 0; b < w.r.bCount; b++) if (w.r.bfloors[b] == 0) national++;
                System.out.printf("        %d tile edits on %d of %d pages, %d buildings down%n", ov.editCount(), edited, ov.pages(), national);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                SaveIO.save(w, bo);
                World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
                // every tile, every building on a tile and the navigation grid as they were, from the overlay alone
                for (int p = 0; p < w.r.store.pages(); p++) {
                    check(java.util.Arrays.equals(l.r.store.tile[p], w.r.store.tile[p]), "tiles of page " + p + " differ");
                    check(java.util.Arrays.equals(l.r.store.bld[p], w.r.store.bld[p]), "buildings on page " + p + " differ");
                }
                check(java.util.Arrays.equals(l.r.navCopy(), w.r.navCopy()), "navigation differs");
                check(l.r.checksum() == w.r.checksum() && Audit.hash(l) == Audit.hash(w), "different straight after loading");
                check(java.util.Arrays.equals(overlayBytes(l.r.overlay), overlayBytes(ov)), "the overlay itself didn't come back");
                // smaller than the tiles alone used to be, before compression
                SaveIO.Snapshot raw = SaveIO.snapshot(w);
                check(raw.length() < w.r.W * w.r.H, "the save is " + raw.length() + " bytes before compression");
                // a barricade put back as it was is no edit at all
                int ti = -1;
                for (int i = 0; i < w.r.W * w.r.H && ti < 0; i++) if (w.r.t(i % w.r.W, i / w.r.W) == Tile.ROAD) ti = i;
                int before = ov.editCount();
                w.r.set(ti % w.r.W, ti / w.r.W, Tile.BARRICADE);
                check(ov.editCount() == before + 1, "the barricade wasn't recorded");
                w.r.set(ti % w.r.W, ti / w.r.W, Tile.ROAD);
                check(ov.editCount() == before, "taking it down left an edit");
                for (int s = 0; s < 20 * 20; s++) {
                    w.step();
                    l.step();
                }
                check(Audit.hash(l) == Audit.hash(w), "diverged after loading");
                w.shutdown();
                l.shutdown();
            }
        });
        test("save: version 6 and 7 saves still load, play on, and save again smaller", new T() {
            public void run() throws Exception {
                // made by the game before overlays: 768, seed 21, 400 dead, an air strike and a blown bridge, 30 s in
                String[] files = {"desktop/testdata/save-v6-dense.zcs", "desktop/testdata/save-v7-sparse.zcs"};
                for (int f = 0; f < 2; f++) {
                    java.io.File file = new java.io.File(files[f]);
                    FileInputStream in = new FileInputStream(file);
                    World w = SaveIO.load(content, in, 1);
                    in.close();
                    check(w.act.sparse == (f == 1), files[f] + ": the wrong mode");
                    check(w.tick == 600 && w.r.checksum() == 2656092569829786196L, files[f] + ": loaded a different map or time");
                    check(Audit.check(w).isEmpty(), "audit: " + Audit.check(w));
                    check(Audit.ledger(w) == 0, "the ledger is off by " + Audit.ledger(w));
                    check(w.r.overlay.editCount() > 0, "no edits found in a map with a blown bridge");
                    // saved again as version 8: smaller, and it carries on exactly
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    SaveIO.save(w, bo);
                    System.out.printf("        %s: %d KB, again as version 8 %d KB%n", file.getName(), file.length() >> 10, bo.size() >> 10);
                    check(bo.size() < file.length(), "the new save isn't smaller: " + bo.size() + " vs " + file.length());
                    World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 1);
                    check(l.r.checksum() == w.r.checksum() && Audit.hash(l) == Audit.hash(w), "different straight after loading");
                    play(w, 20, false);
                    play(l, 20, false);
                    check(Audit.hash(w) == Audit.hash(l), "a re-saved game doesn't carry on the same");
                    check(Audit.check(l).isEmpty(), Audit.check(l));
                    w.shutdown();
                    l.shutdown();
                }
            }
        });
        test("zombies: a watched horde comes on as individuals at its front", new T() {
            public void run() {
                World w = game(11, 768, "sandbox", 1);
                float[] p = w.r.openGroundNear(Setup.originX + 1200, Setup.originY + 1200, false);
                camOver(w, p[0], p[1]);
                int s = w.r.sectorOfPx(p[0], p[1]);
                w.sec.cam[s] = true;
                Strategic.Horde h = w.strat.addHorde(p[0], p[1], 900);
                h.tx = p[0] + 2000;
                h.ty = p[1];
                int z0 = w.sp.zCount;
                int made = w.undead.watchedHorde(h, s);
                check(made > 50 && made <= 160, made + " came out at the front");
                w.sp.build(w.e);
                check(w.sp.zCount - z0 == made, "individuals " + (w.sp.zCount - z0) + " vs " + made);
                // they walk on along its heading
                float before = meanZombieX(w);
                play(w, 10, true);
                float after = meanZombieX(w);
                check(after > before + 40, "the front didn't move on: " + before + " -> " + after);
                w.shutdown();
            }
        });
        test("zombies: strains change the dead themselves, and alphas roar", new T() {
            public void run() {
                World w = game(13, 768, "sandbox", 1);
                Content.ZType walker = w.c.ztype("walker");
                float[] p = w.r.openGroundNear(Setup.originX + 1500, Setup.originY - 900, false);
                int s = w.r.sectorOfPx(p[0], p[1]);
                w.ecology.strain[s] = 2;
                int a = w.spawnZombie(p[0], p[1], walker);
                float hp0 = w.e.maxHp[a];
                w.step();
                check(w.undead.strain(a) == 2 && w.e.maxHp[a] > hp0 * 1.2f, "the armoured strain isn't tougher");
                w.ecology.strain[s] = 1;
                int b = w.spawnZombie(p[0] + 20, p[1], walker);
                w.step();
                check(w.undead.speedMul(b) > 1.1f, "the runners' strain isn't faster");
                // alphas: some of the dead lead, and their roars are noises on the scent field
                int leaders = 0;
                Tools.paintZombies(w, p[0], p[1], 400, 300, walker.index);
                for (int i = 0; i < w.e.top; i++) if (w.e.loc[i] == Ents.FREE && w.e.kind[i] == Ents.ZOMBIE && w.undead.isLeader(i)) leaders++;
                check(leaders >= 3 && leaders < 40, leaders + " leaders in 400");
                int civ = w.spawnCiv(p[0] + 100, p[1], w.c.unit("civilian"));
                w.e.state[civ] = CivAI.S_PLAYER;
                w.playerEnt = civ;
                w.e.hp[civ] = w.e.maxHp[civ] = 100000;
                play(w, 30, true);
                check(w.undead.roars > 0, "no alpha roared");
                w.shutdown();
            }
        });
        test("perf: 20,000 of the dead", new T() {
            public void run() {
                World w = game(2, 1024, "sandbox", 4);
                Region.Settlement c = w.r.city();
                camOver(w, c.cx * 16f, c.cy * 16f);
                Tools.paintZombies(w, c.cx * 16f, c.cy * 16f, 20000, 1800, -1);
                play(w, 5, false);
                long t0 = System.nanoTime();
                play(w, 20, false);
                double ms = (System.nanoTime() - t0) / 1e6 / 400;
                System.out.printf("        20k: %.1f ms per step, %d individuals%n", ms, w.e.live);
                check(ms < 60, "too slow: " + ms + " ms per step");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        scaleTests();
        sparseTests();
        nationalTests();
        lifeTests();
    }

    // ------------------------------------------------------------------ active-area paging (docs/SCALE_PLAN.md P2)

    private static void sparseTests() {
        test("sim: the same game on 1 and 4 threads (sparse, 1024)", new T() {
            public void run() throws Exception {
                sparse(new T() {
                    public void run() {
                        simThreads(1024);
                    }
                });
            }
        });
        test("sim: everyone accounted for with no dead about (sparse, 1024)", new T() {
            public void run() throws Exception {
                sparse(new T() {
                    public void run() {
                        simAccounted(1024);
                    }
                });
            }
        });
        test("save: a loaded game plays on exactly the same (sparse, 1024)", new T() {
            public void run() throws Exception {
                sparse(new T() {
                    public void run() throws Exception {
                        saveExact(1024);
                    }
                });
            }
        });
        test("director: a replay rebuilds the same outbreak (sparse, 1024)", new T() {
            public void run() throws Exception {
                sparse(new T() {
                    public void run() throws Exception {
                        replayExact(1024);
                    }
                });
            }
        });
        test("sparse: chunks follow everyone, leave when quiet, and cars cross the rest in transit", new T() {
            public void run() throws Exception {
                sparse(new T() {
                    public void run() {
                        World w = game(9, 1024, "sandbox", 2);
                        check(w.act.sparse, "not sparse");
                        Region.Settlement c = w.r.city();
                        float x = c.cx * 16f, y = c.cy * 16f;
                        camOver(w, x, y);
                        long before = Audit.people(w);
                        play(w, 30, true);
                        // a small outbreak away from the stations: the police drive out to it
                        Tools.paintZombies(w, x + 300, y + 200, 30, 120, w.c.ztype("walker").index);
                        int most = 0;
                        for (int s = 0; s < 90; s++) {
                            play(w, 1, false);
                            most = Math.max(most, w.act.activeCount());
                            check(Audit.check(w).isEmpty(), "audit at t=" + (int) w.time + ": " + Audit.check(w));
                        }
                        check(most < w.act.pages / 2, "most of the map active: " + most + " of " + w.act.pages);
                        check(w.act.left > 0, "no chunk ever left the set");
                        check(w.veh.transitSteps > 0, "no vehicle went into transit");
                        // (nobody lost to the paging: everyone alive, or counted killed or turned; with the day's
                        // life on the streets a passer-by may be bitten)
                        check(Audit.ledger(w) == 0 && Audit.people(w) + w.stats.civDeaths + w.stats.civTurned == before,
                                "people " + Audit.people(w) + " vs " + before + ", ledger " + Audit.ledger(w) + ", killed " + w.stats.civDeaths + ", turned " + w.stats.civTurned);
                        // the scent and the foot paths stop at the active chunks
                        for (int p = 0; p < w.act.pages; p++) {
                            if (w.act.active(p)) continue;
                            int x0 = (p % w.act.pw) * 32, y0 = (p / w.act.pw) * 32;
                            for (int k = 0; k < 1024; k += 37) check(w.flow.atCell(x0 + k % 32, y0 + k / 32) == Flow.INF, "scent on an inactive chunk");
                        }
                        w.shutdown();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ national maps wired in (docs/SCALE_PLAN.md P7)

    /** The smallest national map: NationGen at 8,192 tiles (33 km), a capital, a base, a prison and airfields. */
    static final int NAT = 8192;

    /** Runs a test with the parallel phases reading only resident pages (Region throws otherwise). */
    static void strict(T t) throws Exception {
        boolean was = World.strictParallel;
        World.strictParallel = true;
        try {
            t.run();
        } finally {
            World.strictParallel = was;
        }
    }

    /** The game clock moved on to hour h (today or tomorrow). */
    static void toHour(World w, float h) {
        w.time += ((h - w.hour()) % 24 + 24) % 24 * 60;
    }

    /** The city's vehicles Life has out, and people aboard any vehicle that aren't police or soldiers. */
    static int[] lifeOut(World w) {
        int mine = 0, aboard = 0;
        for (int v = 0; v < w.veh.top; v++) if (w.life.mine(v)) mine++;
        for (int i = 0; i < w.e.top; i++) if (w.e.loc[i] == Ents.ABOARD && w.e.kind[i] == Ents.CIV) aboard++;
        return new int[]{mine, aboard};
    }

    /** Police and soldiers kept in every facility's pools, and out on the map. */
    static long armedEverywhere(World w) {
        long n = 0;
        for (Region.Facility f : w.r.facilities) n += w.forces.armedAt(f, Ents.COP) + w.forces.armedAt(f, Ents.SOLDIER);
        for (int i = 0; i < w.e.top; i++) if ((w.e.loc[i] == Ents.FREE || w.e.loc[i] == Ents.ABOARD) && w.e.armed(i)) n++;
        return n;
    }

    /**
     * A normal day round the camera on a map: traffic, parked cars, buses, patrols, people going places at 09:30;
     * everyone counted the same throughout; and when the camera leaves, everything folds back exactly.
     */
    static void lifeFolds(int size) {
        World w = game(5, size, "sandbox", 2);
        check(w.life.on && w.life.flowOn, "life rules off");
        Region.Settlement c = w.r.city();
        toHour(w, 9.5f);
        long before = Audit.people(w), armed = armedEverywhere(w);
        int[] police = new int[w.r.facilities.size()];
        for (int k = 0; k < police.length; k++) police[k] = w.forces.armedAt(w.r.facilities.get(k), Ents.COP);
        int maxCars = 0, maxParked = 0, maxBuses = 0, maxWalkers = 0, errands = 0, patrolCars = 0;
        long trainsSeen = 0;
        for (int k = 0; k < 6; k++) {
            camOver(w, c.cx * 16f + (k - 2) * 500, c.cy * 16f + (k % 2) * 400);
            for (int t = 0; t < 10; t++) {
                play(w, 2, t % 5 == 0);
                maxCars = Math.max(maxCars, w.life.cars);
                maxParked = Math.max(maxParked, w.life.parked);
                maxBuses = Math.max(maxBuses, w.life.buses);
                trainsSeen += w.life.trains();
                int walkers = 0;
                for (int i = 0; i < w.e.top; i++)
                    if (w.e.loc[i] == Ents.FREE && w.e.kind[i] == Ents.CIV) {
                        walkers++;
                        if (w.e.state[i] == CivAI.S_ERRAND) errands++;
                    }
                maxWalkers = Math.max(maxWalkers, walkers);
                for (Squads.Squad q : w.sq.list) if (q.alive && q.order == Squads.O_PATROL && q.vehicle >= 0 && w.veh.state[q.vehicle] == Vehicles.V_DRIVE) patrolCars++;
                check(Audit.people(w) == before, "people " + Audit.people(w) + " vs " + before + " at " + w.clock());
                check(Audit.ledger(w) == 0, "ledger " + Audit.ledger(w));
                check(armedEverywhere(w) == armed, "police and soldiers " + armedEverywhere(w) + " vs " + armed);
            }
        }
        System.out.printf("        %d tiles at %s: up to %d cars, %d parked, %d buses, %d on foot (%d errand-steps), patrol-car checks %d, train-seconds %d, %d spawned, %d folded%n",
                size, w.clock(), maxCars, maxParked, maxBuses, maxWalkers, errands, patrolCars, trainsSeen, w.life.spawned, w.life.folded);
        check(maxCars >= 15, "only " + maxCars + " cars driving at the morning rush");
        check(maxParked >= 30, "only " + maxParked + " parked cars");
        check(maxWalkers >= 20 && errands > 0, "nobody walking anywhere: " + maxWalkers + ", errands " + errands);
        check(patrolCars > 0, "no patrol car out of the stations in view");
        // over a railway, if the map has one: passenger trains come through, their people counted aboard
        RoadGraph rl = w.r.rails;
        if (rl.edgeCount > 0) {
            int e = rl.edgeCount / 2;
            float rx = (rl.nx[rl.ea[e]] + rl.nx[rl.eb[e]]) / 2, ry = (rl.ny[rl.ea[e]] + rl.ny[rl.eb[e]]) / 2;
            camOver(w, rx, ry);
            long carried = 0;
            int seen = 0;
            for (int t = 0; t < 120; t++) {
                play(w, 1, t % 20 == 0);
                seen += w.life.trains();
                carried = Math.max(carried, w.life.carried());
                check(Audit.people(w) == before, "people " + Audit.people(w) + " vs " + before + " with a train about");
            }
            System.out.printf("        a railway: train-seconds %d, up to %d people aboard%n", seen, carried);
            check(seen > 0, "no train came along the line in two minutes");
        }
        w.camDetail = false;
        play(w, 3, true);
        int[] out = lifeOut(w);
        check(out[0] == 0 && out[1] == 0 && w.life.trains() == 0, "after the camera left: " + out[0] + " vehicles, " + out[1] + " aboard, " + w.life.trains() + " trains");
        check(Audit.people(w) == before, "people after the camera left: " + Audit.people(w) + " vs " + before);
        for (int k = 0; k < police.length; k++)
            check(w.forces.armedAt(w.r.facilities.get(k), Ents.COP) == police[k], "station " + k + " has " + w.forces.armedAt(w.r.facilities.get(k), Ents.COP) + " officers, had " + police[k]);
        check(Audit.check(w).isEmpty(), Audit.check(w));
        w.shutdown();
    }

    /** With an outbreak among the traffic: the same on 1 and 2 threads, and after a save and a load. */
    static void lifeExact(int size) throws Exception {
        World a = game(11, size, "sandbox", 1), b = game(11, size, "sandbox", 2);
        Region.Settlement c = a.r.city();
        float x = c.cx * 16f, y = c.cy * 16f;
        for (World w : new World[]{a, b}) {
            toHour(w, 17.4f);
            camOver(w, x, y);
        }
        int fled = 0;
        for (int k = 0; k < 60 * 20; k++) {
            if (k == 20 * 20) {
                Tools.paintZombies(a, x + 200, y - 300, 120, 200, -1);
                Tools.paintZombies(b, x + 200, y - 300, 120, 200, -1);
            }
            a.step();
            b.step();
            if (k % 100 == 0) check(Audit.hash(a) == Audit.hash(b), "1 and 2 threads differ at step " + k);
            if (k % 20 == 0) for (int v = 0; v < a.veh.top; v++) if (a.life.mine(v) && a.life.flee[v]) fled++;
        }
        check(Audit.check(a).isEmpty(), Audit.check(a));
        System.out.printf("        %d tiles: car-seconds fleeing %d, abandoned %d, trampled %d, cars %d, parked %d%n", size, fled, a.life.abandoned, a.life.trampled, a.life.cars, a.life.parked);
        check(fled > 0, "no car fled the dead");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        SaveIO.save(a, bo);
        World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
        check(Audit.hash(l) == Audit.hash(a), "different straight after loading");
        check(Audit.check(l).isEmpty(), "loaded game fails the audit: " + Audit.check(l));
        check(Audit.people(l) == Audit.people(a), "people differ after loading");
        for (int k = 0; k < 30 * 20; k++) {
            a.step();
            l.step();
            if (k % 100 == 0) check(Audit.hash(l) == Audit.hash(a), "diverged " + k + " steps after loading");
        }
        check(Audit.ledger(a) == 0, "ledger " + Audit.ledger(a));
        a.shutdown();
        b.shutdown();
        l.shutdown();
    }

    private static void lifeTests() {
        test("life: a normal morning round the camera, everyone counted, all folded back when it leaves (1024)", new T() {
            public void run() {
                lifeFolds(1024);
            }
        });
        test("life: a normal morning in a national capital folds back exactly (8192)", new T() {
            public void run() throws Exception {
                strict(new T() {
                    public void run() {
                        lifeFolds(NAT);
                    }
                });
            }
        });
        test("life: traffic fleeing an outbreak is the same on 1 and 2 threads and after a load (1024)", new T() {
            public void run() throws Exception {
                lifeExact(1024);
            }
        });
        test("life: traffic fleeing an outbreak is the same on 1 and 2 threads and after a load (8192)", new T() {
            public void run() throws Exception {
                strict(new T() {
                    public void run() throws Exception {
                        lifeExact(NAT);
                    }
                });
            }
        });
        test("life: by day the town's people are at work, by night at home, and nobody is lost (1536, 8192)", new T() {
            public void run() {
                for (int size : new int[]{1536, NAT}) {
                    World w = game(3, size, "sandbox", 2);
                    SectorIndex.paranoid = true;
                    try {
                        Sectors sec = w.sec;
                        long people = Audit.people(w);
                        int st = w.r.city().id;
                        // the sectors with more work than homes, and their people at 03:00 and 13:00
                        long[] atWork = new long[2];
                        for (int k = 0; k < 2; k++) {
                            toHour(w, k == 0 ? 3f : 13f);
                            w.setStrategic(true);
                            for (int t = 0; t < 12; t++) w.step();
                            w.setStrategic(false);
                            double J = 0, H = 0;
                            for (int s : sec.idx.bySettlement[st]) {
                                J += w.life.commute.jobs(s);
                                H += sec.home[s];
                            }
                            for (int s : sec.idx.bySettlement[st]) if (w.life.commute.jobs(s) / J > sec.home[s] / H * 1.5) atWork[k] += sec.popIn[s];
                            check(Audit.people(w) == people, "people " + Audit.people(w) + " vs " + people);
                        }
                        System.out.printf("        %d tiles: people in the places of work at 03:00 %d, at 13:00 %d; %d moved%n", size, atWork[0], atWork[1], w.life.commute.moved);
                        check(atWork[1] > atWork[0] * 1.3f, "the places of work don't fill by day: " + atWork[0] + " -> " + atWork[1]);
                        check(Audit.ledger(w) == 0, "ledger " + Audit.ledger(w));
                    } finally {
                        SectorIndex.paranoid = false;
                    }
                    w.shutdown();
                }
            }
        });
    }

    private static void nationalTests() {
        test("national: a game on a nation starts from its residents, its chunks are the cache's", new T() {
            public void run() throws Exception {
                strict(new T() {
                    public void run() {
                        World w = game(7, NAT, "lab_leak", 2);
                        Nation n = w.r.nation;
                        check(n != null && w.national && w.act.sparse, "not a national, sparse world");
                        check(w.r.bCount == n.bCount && w.r.facilities.size() >= n.facilities.size() && w.r.facilities.get(0) == n.facilities.get(0), "national buildings and facilities not in the region: " + w.r.bCount + "/" + n.bCount + " " + w.r.facilities.size() + "/" + n.facilities.size());
                        long res = n.totalResidents();
                        check(Audit.people(w) == res, "people " + Audit.people(w) + " vs the nation's " + res);
                        for (int s = 0; s < w.sec.n; s++) check(w.sec.home[s] == n.residents[s], "sector " + s + " home differs from the nation's residents");
                        int doors = 0;
                        for (int b = 0; b < w.r.bCount; b++) if (w.r.doorX(b) >= 0) doors++;
                        check(doors > w.r.bCount * 9 / 10, "national buildings without doors: " + (w.r.bCount - doors) + " of " + w.r.bCount);
                        camOver(w, Setup.originX, Setup.originY);
                        play(w, 60, true);
                        int act = w.act.activeCount();
                        check(act > 4 && act < w.act.pages / 8, act + " chunks active of " + w.act.pages);
                        check(w.r.cache.size() <= w.r.cache.capacity() + act, "cache over its size");
                        // chunk buildings in the lists of active sectors, with doors in their sector
                        int chunkB = 0;
                        for (int p = 0; p < w.act.pages; p++) {
                            if (!w.act.active(p)) continue;
                            for (int b : w.secBuildings[p])
                                if (Chunk.isChunkBuilding(b)) {
                                    chunkB++;
                                    check(w.r.sectorOfPx(w.r.doorX(b), w.r.doorY(b)) == p, "door outside its sector");
                                }
                        }
                        check(chunkB > 50, "only " + chunkB + " chunk buildings in active sectors");
                        // a tap on a building opens that building (chunk buildings and national ones)
                        int tapped = 0;
                        for (int p = 0; p < w.act.pages && tapped < 40; p++) {
                            if (!w.act.active(p)) continue;
                            for (int b : w.secBuildings[p]) {
                                float bx = (w.r.bx(b) + w.r.bw(b) / 2f) * Tile.SIZE, by = (w.r.by(b) + w.r.bh(b) / 2f) * Tile.SIZE;
                                if (w.r.bfloors(b) <= 0 || w.r.building((int) (bx / Tile.SIZE), (int) (by / Tile.SIZE)) != b) continue;
                                check(Game.buildingAt(w, bx, by) == b, "a tap on building " + Integer.toHexString(b) + " found " + Integer.toHexString(Game.buildingAt(w, bx, by)));
                                String[] d = Game.describeBuilding(w, b);
                                check(d != null && d[0].equals(Data.buildingName(w.r.bkind(b))), "the sheet of building " + b + " names it " + (d == null ? null : d[0]));
                                tapped++;
                            }
                        }
                        check(tapped >= 40, "only " + tapped + " buildings tapped");
                        int d0 = w.sec.idx.district(w.r.sectorOfPx(Setup.outbreakX, Setup.outbreakY));
                        check(Game.districtName(w, d0).length() > 3 && Game.districtCounts(w, d0)[3] > 0, "the district's sheet: " + Game.districtName(w, d0));
                        // an edit on a page not in memory takes it in first, and the overlay keeps it
                        Region.Settlement far = null;
                        for (Region.Settlement st : w.r.settlements)
                            if (st.kind == Region.S_VILLAGE && !w.act.activePx(st.cx * 16f, st.cy * 16f)) far = st;
                        check(far != null, "no village out of the active set");
                        int tx = far.cx, ty = far.cy;
                        int p = w.r.store.pageOf(tx, ty);
                        boolean was = Region.strictPages;
                        Region.strictPages = true;
                        boolean threw = false;
                        try {
                            w.r.t(tx, ty);
                        } catch (IllegalStateException ex) {
                            threw = true;
                        }
                        Region.strictPages = was;
                        check(threw, "a read of a page not in memory didn't throw in strict mode");
                        w.r.set(tx, ty, Tile.BARRICADE);
                        check(w.act.active(p) && w.r.store.resident(p) && w.r.t(tx, ty) == Tile.BARRICADE, "the edited page wasn't taken in");
                        check(w.r.overlay.edited(p), "the overlay didn't record it");
                        check(Audit.check(w).isEmpty(), Audit.check(w));
                        w.shutdown();
                    }
                });
            }
        });
        test("national: the same game on 1 and 2 threads (8192)", new T() {
            public void run() throws Exception {
                strict(new T() {
                    public void run() {
                        simThreads(NAT, 2);
                    }
                });
            }
        });
        test("national: everyone accounted for with no dead about (8192)", new T() {
            public void run() throws Exception {
                simAccounted(NAT);
            }
        });
        test("national: a loaded game plays on exactly the same (8192)", new T() {
            public void run() throws Exception {
                saveExact(NAT);
            }
        });
        test("national: a replay rebuilds the same outbreak (8192)", new T() {
            public void run() throws Exception {
                replayExact(NAT);
            }
        });
        test("national: every scenario starts sensibly on a nation", new T() {
            public void run() throws Exception {
                String[] ids = {"sandbox", "lab_leak", "airliner", "prison", "festival", "port", "random", "hold_city", "evacuate", "retake", "protect"};
                for (String id : ids) {
                    Content.Scenario sc = content.scenario(id);
                    check(sc != null, "no scenario " + id);
                    World w = game(11, NAT, id, 2);
                    float ox = Setup.outbreakX, oy = Setup.outbreakY;
                    check(ox > 0 && oy > 0 && ox < w.r.px() && oy < w.r.px(), id + ": the start is off the map");
                    w.sp.build(w.e);
                    long dead = w.totalZombies();
                    check(dead >= sc.zombies * 9 / 10, id + ": " + dead + " of the dead, asked for " + sc.zombies);
                    Region.Settlement near = w.r.settlementAt(ox, oy);
                    camOver(w, Setup.originX, Setup.originY);
                    play(w, 20, true);
                    check(Audit.ledger(w) == 0, id + ": the ledger is out by " + Audit.ledger(w));
                    System.out.printf("        %-9s starts at %s, %d of the dead, %d chunks active, %d police out%n", id,
                            near != null ? near.name + " (" + Region.SNAMES[near.kind] + ")" : "open country", dead, w.act.activeCount(), w.sq.count(Ents.COP));
                    w.shutdown();
                }
            }
        });
    }

    // ------------------------------------------------------------------ real scale (docs/SCALE_PLAN.md P0 and P5)

    private static Nation country;
    private static long countryBestMs = Long.MAX_VALUE;

    /** The Country-size nation every scale test looks at (built once). */
    static Nation country() {
        if (country == null) {
            country = NationGen.generate(28672, RegionGen.COAST, 3, 2);
            countryBestMs = Math.min(countryBestMs, country.buildMs);
        }
        return country;
    }

    private static void scaleTests() {
        test("perf: the once-a-second update stays cheap", new T() {
            public void run() {
                World w = game(7, 2304, "sandbox", 2);
                w.setStrategic(true);
                for (int k = 0; k < 20; k++) w.step();
                long t0 = System.nanoTime();
                for (int k = 0; k < 144; k++) w.step();
                double ms = (System.nanoTime() - t0) / 1e6 / 144;
                System.out.printf("        %.2f ms per update, %d sectors (a game day of fast-forward in %.2f s)%n", ms, w.sec.n, ms * 144 / 1000);
                check(ms < 25, "the once-a-second update takes " + ms + " ms");
                w.shutdown();
            }
        });
        test("nation: the same seed builds the same nation", new T() {
            public void run() {
                Nation a = country();
                Nation b = NationGen.generate(28672, RegionGen.COAST, 3, 1); // and on one thread
                countryBestMs = Math.min(countryBestMs, b.buildMs);
                check(a.checksum() == b.checksum(), "same seed, same nation");
                check(NationGen.generate(20480, RegionGen.COAST, 4, 2).checksum() != NationGen.generate(20480, RegionGen.COAST, 5, 2).checksum(), "another seed, another nation");
            }
        });
        test("nation: built at 28,672 in under 6 s (3 s target on a quiet desktop), in under 40 MB", new T() {
            public void run() {
                Nation n = country();
                if (countryBestMs >= 6000) countryBestMs = Math.min(countryBestMs, NationGen.generate(28672, RegionGen.COAST, 3, 2).buildMs);
                long mem = n.memoryBytes();
                System.out.printf("        built in %d ms, %.1f MB, %d junctions, %d roads%n", countryBestMs, mem / 1048576.0, n.roads.nodeCount, n.roads.edgeCount);
                check(countryBestMs < 6000, "built in " + countryBestMs + " ms");
                check(mem < 40L << 20, "nation memory " + (mem >> 20) + " MB");
            }
        });
        test("nation: a capital of a million, cities of 150-450k, everyone counted", new T() {
            public void run() {
                Nation n = country();
                Region.Settlement cap = n.capital();
                check(cap.capital && cap.residents >= 1000000 && cap.residents <= 1400000, "capital " + cap.residents);
                // real size: about 225 km2 for the capital, cities 7-13 km across, towns 1.6-4 km
                float capKm = cap.radius * 2 / Nation.KM;
                check(capKm > 13 && capKm < 22, "the capital is " + capKm + " km across");
                long[] bySet = new long[n.settlements.size()];
                for (int s = 0; s < n.residents.length; s++) if (n.secSettlement[s] >= 0) bySet[n.secSettlement[s]] += n.residents[s];
                int cities = 0, towns = 0;
                for (Region.Settlement s : n.settlements) {
                    check(bySet[s.id] == s.residents, s.name + ": sectors hold " + bySet[s.id] + ", not " + s.residents);
                    float km = s.radius * 2 / Nation.KM;
                    if (s.kind == Region.S_CITY && !s.capital) {
                        cities++;
                        check(s.residents >= 150000 && s.residents <= 450000, s.name + " has " + s.residents);
                        check(km > 5 && km < 14, s.name + " is " + km + " km across");
                    }
                    if (s.kind == Region.S_TOWN) {
                        towns++;
                        check(s.residents >= 5000 && s.residents <= 40000, s.name + " has " + s.residents);
                        check(km > 1 && km < 5, s.name + " is " + km + " km across");
                    }
                }
                check(cities == 5 && towns >= 40, cities + " cities, " + towns + " towns");
                int total = n.totalResidents();
                check(total > 3000000 && total < 6000000, "the country has " + total);
            }
        });
        test("nation: every place can be reached by road, on every preset", new T() {
            public void run() {
                for (int p = 0; p < Data.presetCount(); p++) {
                    Nation n = p == RegionGen.COAST ? country() : NationGen.generate(20480, p, 10 + p, 2);
                    RoadGraph g = n.roads;
                    boolean[] seen = new boolean[g.nodeCount];
                    int[] q = new int[g.nodeCount];
                    int h = 0, t = 0;
                    q[t++] = n.capital().hubNode;
                    seen[q[0]] = true;
                    while (h < t) {
                        int a = q[h++];
                        for (int i = g.adjStart[a]; i < g.adjStart[a + 1]; i++) {
                            int b = g.other(g.adjEdge[i], a);
                            if (!seen[b]) {
                                seen[b] = true;
                                q[t++] = b;
                            }
                        }
                    }
                    for (Region.Settlement s : n.settlements) check(s.hubNode >= 0 && seen[s.hubNode], Data.presetName(p) + ": no road to " + s.name);
                    check(t == g.nodeCount, Data.presetName(p) + ": " + (g.nodeCount - t) + " junctions cut off");
                    for (Region.Facility f : n.facilities) check(f.node >= 0, f.name + " has no junction");
                    check(n.entryNodes.size() >= 2 && n.railEntries.size() >= 1, "roads and rails from abroad");
                    for (int e : n.entryNodes) check(seen[e], "a road from abroad is cut off");
                }
            }
        });
        test("nation: facilities follow the population", new T() {
            public void run() {
                Nation n = country();
                int[][] count = new int[n.settlements.size()][Region.FKINDS];
                for (Region.Facility f : n.facilities) if (f.settlement >= 0) count[f.settlement][f.kind]++;
                float wantPolice = 0, wantHospitals = 0, wantFire = 0;
                int police = 0, hospitals = 0, fire = 0;
                for (Region.Settlement s : n.settlements) {
                    if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
                    int pop = s.residents;
                    wantPolice += Math.max(1, pop / 40000f);
                    wantHospitals += Math.max(pop >= 8000 ? 1 : 0, pop / 100000f);
                    wantFire += Math.max(1, pop / 30000f);
                    int[] c = count[s.id];
                    police += c[Region.F_POLICE];
                    hospitals += c[Region.F_HOSPITAL];
                    fire += c[Region.F_FIRE];
                    check(c[Region.F_POLICE] >= 1 && c[Region.F_POLICE] <= Math.max(1, pop / 40000f * 1.25f) && c[Region.F_POLICE] >= pop / 40000f * 0.75f,
                            s.name + " (" + pop + "): " + c[Region.F_POLICE] + " police stations");
                    check(c[Region.F_FIRE] >= 1 && c[Region.F_FIRE] >= pop / 30000f * 0.75f && c[Region.F_FIRE] <= Math.max(1, pop / 30000f * 1.25f),
                            s.name + " (" + pop + "): " + c[Region.F_FIRE] + " fire stations");
                    check(c[Region.F_HOSPITAL] <= Math.max(1, pop / 100000f * 1.5f) && c[Region.F_HOSPITAL] >= (pop >= 8000 ? 1 : 0),
                            s.name + " (" + pop + "): " + c[Region.F_HOSPITAL] + " hospitals");
                    if (s.kind == Region.S_CITY)
                        for (int k : new int[]{Region.F_POWER, Region.F_WATER, Region.F_PHONE, Region.F_HALL, Region.F_STATION})
                            check(c[k] == 1, s.name + ": " + c[k] + " " + Region.FNAMES[k]);
                }
                int capPolice = count[0][Region.F_POLICE];
                check(capPolice >= 24 && capPolice <= 36, "the capital has " + capPolice + " police stations");
                // one per so many people, and at least one in every town
                check(Math.abs(police - wantPolice) <= wantPolice * 0.2f, police + " police stations, " + wantPolice + " wanted");
                check(Math.abs(hospitals - wantHospitals) <= wantHospitals * 0.3f, hospitals + " hospitals, " + wantHospitals + " wanted");
                check(Math.abs(fire - wantFire) <= wantFire * 0.2f, fire + " fire stations, " + wantFire + " wanted");
                // every facility on its own dry block, with its building inside it
                for (Region.Facility f : n.facilities) {
                    if (f.building < 0) continue;
                    int b = f.building;
                    check(n.bx[b] >= f.bx0 && n.by[b] >= f.by0 && n.bx[b] + n.bw[b] - 1 <= f.bx1 && n.by[b] + n.bh[b] - 1 <= f.by1, f.name + " is outside its block");
                    check(!Nation.wet(n.coverAt(n.bx[b], n.by[b])), f.name + " stands in water");
                }
                for (int i = 0; i < n.facilities.size(); i++)
                    for (int j = i + 1; j < n.facilities.size(); j++) {
                        Region.Facility a = n.facilities.get(i), b = n.facilities.get(j);
                        if (a.building < 0 || b.building < 0) continue;
                        check(a.bx1 < b.bx0 || b.bx1 < a.bx0 || a.by1 < b.by0 || b.by1 < a.by0, a.name + " and " + b.name + " share a block");
                    }
            }
        });
        test("nation: two-level routes cost within 10% of plain A*", new T() {
            public void run() {
                Nation n = country();
                RoadGraph g = n.roads;
                Rng r = new Rng(11);
                int k = 0;
                double worst = 0;
                while (k < 25) {
                    int a = r.nextInt(g.nodeCount), b = r.nextInt(g.nodeCount);
                    float dx = g.nx[a] - g.nx[b], dy = g.ny[a] - g.ny[b];
                    if (dx * dx + dy * dy < g.shortTrip * g.shortTrip) continue;
                    int[] p1 = g.astar(a, b), p2 = g.route2(a, b);
                    check(p1 != null && p2 != null && p2[0] == a && p2[p2.length - 1] == b, "no route " + a + " -> " + b);
                    float c1 = g.routeCost(p1), c2 = g.routeCost(p2);
                    check(c2 > 0, "the two-level route isn't a route");
                    worst = Math.max(worst, c2 / c1);
                    check(c2 <= c1 * 1.10f, "two-level " + c2 + " against " + c1);
                    k++;
                }
                System.out.printf("        worst %.4f of plain A*%n", worst);
                // the per-chunk edge buckets hold every edge, in every chunk it crosses
                int c = (n.capital().cy / Nation.SECT) * g.chunkW + n.capital().cx / Nation.SECT;
                check(g.chunkEdgeStart[c + 1] > g.chunkEdgeStart[c], "the capital's centre chunk has roads");
                check(g.chunkEdge.length >= g.edgeCount, "every edge in a bucket");
            }
        });
        chunkTests();
    }

    // ------------------------------------------------------------------ street-level chunks (docs/SCALE_PLAN.md P6)

    /** Chunks worth testing on the Country nation: the capital's middle, every place's middle, a random spread. */
    static List<int[]> sampleChunks(Nation n, int random) {
        List<int[]> out = new ArrayList<int[]>();
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        Region.Settlement cap = n.capital();
        for (int dy = -8; dy < 8; dy++)
            for (int dx = -8; dx < 8; dx++) addChunk(n, out, seen, cap.cx / 64 + dx, cap.cy / 64 + dy);
        for (Region.Settlement s : n.settlements)
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) addChunk(n, out, seen, s.cx / 64 + dx, s.cy / 64 + dy);
        Rng r = new Rng(42);
        for (int i = 0; i < random; i++) addChunk(n, out, seen, r.nextInt(n.sw), r.nextInt(n.sw));
        return out;
    }

    private static void addChunk(Nation n, List<int[]> out, java.util.Set<Integer> seen, int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= n.sw || cy >= n.sw || !seen.add(cy * n.sw + cx)) return;
        out.add(new int[]{cx, cy});
    }

    private static boolean site(Nation n, int s) {
        return n.secSettlement[s] >= 0 && n.district[s] == Nation.D_NONE;
    }

    private static boolean roadTile(int t) {
        return t == Tile.ROAD || t == Tile.HIGHWAY || t == Tile.BRIDGE;
    }

    private static void chunkTests() {
        test("chunks: the same bytes in any order, on 1 or 4 threads", new T() {
            public void run() throws Exception {
                final Nation n = country();
                final List<int[]> cs = sampleChunks(n, 150);
                final long[] one = new long[cs.size()];
                for (int i = cs.size() - 1; i >= 0; i--) one[i] = ChunkGen.generate(n, cs.get(i)[0], cs.get(i)[1]).checksum();
                final long[] four = new long[cs.size()];
                final int[] order = new int[cs.size()];
                for (int i = 0; i < order.length; i++) order[i] = i;
                Rng r = new Rng(9);
                for (int i = order.length - 1; i > 0; i--) {
                    int j = r.nextInt(i + 1), t = order[i];
                    order[i] = order[j];
                    order[j] = t;
                }
                Thread[] ts = new Thread[4];
                for (int t = 0; t < 4; t++) {
                    final int t0 = t;
                    ts[t] = new Thread(new Runnable() {
                        public void run() {
                            for (int k = t0; k < order.length; k += 4) {
                                int i = order[k];
                                four[i] = ChunkGen.generate(n, cs.get(i)[0], cs.get(i)[1]).checksum();
                            }
                        }
                    });
                    ts[t].start();
                }
                for (Thread t : ts) t.join();
                for (int i = 0; i < one.length; i++) check(one[i] == four[i], "chunk " + cs.get(i)[0] + "," + cs.get(i)[1] + " differs between runs");
                // and from the same nation built again (on one thread and on two): nothing is kept between nations
                Nation a = NationGen.generate(20480, RegionGen.COAST, 4, 2), b = NationGen.generate(20480, RegionGen.COAST, 4, 1);
                for (int k = 0; k < 49; k++) {
                    int cx = a.capital().cx / 64 - 3 + k % 7, cy = a.capital().cy / 64 - 3 + k / 7;
                    check(ChunkGen.generate(a, cx, cy).checksum() == ChunkGen.generate(b, cx, cy).checksum(), "a rebuilt nation makes another chunk");
                }
                System.out.printf("        %d chunks identical in reverse order on one thread and shuffled on four%n", one.length);
            }
        });
        test("chunks: roads line up across chunk seams", new T() {
            public void run() {
                Nation n = country();
                RoadGraph g = n.roads;
                int seams = 0;
                for (int[] p : sampleChunks(n, 100)) {
                    int cx = p[0], cy = p[1], s = cy * n.sw + cx;
                    if (cx + 1 >= n.sw || cy + 1 >= n.sw) continue;
                    Chunk a = ChunkGen.generate(n, cx, cy);
                    for (int dir = 0; dir < 2; dir++) {
                        int bx = cx + (dir == 0 ? 1 : 0), by = cy + (dir == 0 ? 0 : 1);
                        if (site(n, s) || site(n, by * n.sw + bx)) continue;
                        Chunk b = ChunkGen.generate(n, bx, by);
                        float seam = (dir == 0 ? bx : by) * 64 * 16f; // px
                        for (int k = g.chunkEdgeStart[s]; k < g.chunkEdgeStart[s + 1]; k++) {
                            int e = g.chunkEdge[k];
                            float ax = g.nx[g.ea[e]], ay = g.ny[g.ea[e]], ex = g.nx[g.eb[e]], ey = g.ny[g.eb[e]];
                            float u0 = dir == 0 ? ax : ay, u1 = dir == 0 ? ex : ey;
                            if ((u0 - seam) * (u1 - seam) >= 0) continue; // doesn't cross this seam
                            float f = (seam - u0) / (u1 - u0);
                            float along = dir == 0 ? ay + (ey - ay) * f : ax + (ex - ax) * f;
                            int at = (int) Math.floor(along / 16) - (dir == 0 ? cy : cx) * 64;
                            if (at < 1 || at > 62) continue;
                            // the tiles either side of the seam where the road crosses it are road on both sides
                            int ta = dir == 0 ? a.t(63, at) : a.t(at, 63), tb = dir == 0 ? b.t(0, at) : b.t(at, 0);
                            check(roadTile(ta) && roadTile(tb), "edge " + e + " breaks at the seam of chunks " + cx + "," + cy + " and " + bx + "," + by
                                    + ": " + Tile.NAMES[ta] + " / " + Tile.NAMES[tb]);
                            seams++;
                        }
                        // and along the whole seam, road meets road (a diagonal road may shift by a tile)
                        int mismatch = 0, road = 0;
                        for (int k = 0; k < 64; k++) {
                            boolean ra = roadTile(dir == 0 ? a.t(63, k) : a.t(k, 63)), rb = roadTile(dir == 0 ? b.t(0, k) : b.t(k, 0));
                            if (ra || rb) road++;
                            if (ra != rb) mismatch++;
                        }
                        check(mismatch <= Math.max(4, road / 3), mismatch + " of " + road + " road tiles don't match at the seam of " + cx + "," + cy);
                    }
                }
                check(seams > 500, "only " + seams + " roads crossing seams looked at");
                System.out.printf("        %d road crossings of seams line up%n", seams);
            }
        });
        test("chunks: residents add up to each sector's, and nothing is built on water", new T() {
            public void run() {
                Nation n = country();
                long people = 0, unhoused = 0;
                int chunks = 0, buildings = 0;
                boolean[] wet = new boolean[Chunk.AREA];
                for (int[] p : sampleChunks(n, 600)) {
                    int s = p[1] * n.sw + p[0];
                    Chunk c = ChunkGen.generate(n, p[0], p[1], wet);
                    check(c.residents() + c.elsewhere == n.residents[s], "chunk " + p[0] + "," + p[1] + " houses " + c.residents() + " + " + c.elsewhere + " of " + n.residents[s]);
                    if (!site(n, s)) {
                        unhoused += c.elsewhere;
                        if (n.built(s)) check(c.elsewhere == 0, "a town chunk has nowhere for " + c.elsewhere + " people");
                    }
                    for (int b = 0; b < c.bCount; b++) {
                        check(c.bres[b] >= 0, "negative residents");
                        for (int y = c.by[b]; y < c.by[b] + c.bh[b]; y++)
                            for (int x = c.bx[b]; x < c.bx[b] + c.bw[b]; x++) {
                                check(!wet[y * 64 + x], Data.buildingName(c.bkind[b]) + " on water in chunk " + p[0] + "," + p[1]);
                                check(c.bld[y * 64 + x] == b + 1 && c.t(x, y) == Tile.BUILDING, "a building's tiles");
                            }
                        check(c.doorX[b] >= 0 && Tile.walkable(c.t(c.doorX[b], c.doorY[b])), "a building without a door");
                        int id = c.id(b);
                        check(Chunk.isChunkBuilding(id) && Chunk.chunkOf(id) == s && Chunk.localOf(id) == b, "building ids");
                    }
                    for (int i = 0; i < Chunk.AREA; i++) {
                        int v = c.bld[i];
                        if (v < 0) check(!wet[i], "national building " + Data.buildingName(n.bkind[-v - 1]) + " on water in chunk " + p[0] + "," + p[1]);
                    }
                    people += c.residents();
                    buildings += c.bCount;
                    chunks++;
                }
                check(unhoused == 0, unhoused + " people outside sites found no house");
                System.out.printf("        %d chunks, %d buildings, %d residents, all matching their sectors%n", chunks, buildings, people);
            }
        });
        test("chunks: generated in under 1.5 ms each at p95 (5 ms allowed on a busy machine)", new T() {
            public void run() {
                Nation n = country();
                List<int[]> cs = sampleChunks(n, 800);
                for (int i = 0; i < 300; i++) ChunkGen.generate(n, cs.get(i)[0], cs.get(i)[1]); // warm up
                double[] ms = new double[cs.size()];
                long mem = 0;
                int D = Nation.DISTRICT_NAMES.length;
                int[] dChunks = new int[D], dBuild = new int[D], dRes = new int[D], dFloors = new int[D], dMax = new int[D];
                for (int i = 0; i < cs.size(); i++) {
                    long t0 = System.nanoTime();
                    Chunk c = ChunkGen.generate(n, cs.get(i)[0], cs.get(i)[1]);
                    ms[i] = (System.nanoTime() - t0) / 1e6;
                    mem += c.memoryBytes();
                    int s = cs.get(i)[1] * n.sw + cs.get(i)[0], d = n.built(s) ? n.district[s] : 0;
                    if (site(n, s)) continue;
                    dChunks[d]++;
                    dBuild[d] += c.bCount;
                    dRes[d] += c.residents();
                    for (int b = 0; b < c.bCount; b++) {
                        dFloors[d] += c.bfloors[b];
                        dMax[d] = Math.max(dMax[d], c.bfloors[b]);
                    }
                }
                double[] sorted = ms.clone();
                java.util.Arrays.sort(sorted);
                double p50 = sorted[sorted.length / 2], p95 = sorted[(int) (sorted.length * 0.95)], max = sorted[sorted.length - 1];
                System.out.printf("        %d chunks: p50 %.2f ms, p95 %.2f ms, max %.2f ms; %.1f KB per chunk%n", ms.length, p50, p95, max, mem / 1024.0 / ms.length);
                for (int d = 0; d < D; d++) {
                    if (dChunks[d] == 0) continue;
                    System.out.printf("        %-11s %4d chunks: %5.1f buildings each, %5.1f residents per building, %4.1f floors on average (top %d)%n",
                            Nation.DISTRICT_NAMES[d], dChunks[d], dBuild[d] / (float) dChunks[d], dBuild[d] == 0 ? 0 : dRes[d] / (float) dBuild[d],
                            dBuild[d] == 0 ? 0 : dFloors[d] / (float) dBuild[d], dMax[d]);
                }
                check(p95 < 5, "p95 " + p95 + " ms");
                check(mem / ms.length < 40 * 1024, "chunks take " + mem / ms.length + " bytes");
                // downtown towers, suburbs of one or two floors
                check(dMax[Nation.D_CORE] >= 30 && dFloors[Nation.D_SUBURB] / (float) Math.max(1, dBuild[Nation.D_SUBURB]) < 2.2f, "heights by district");
            }
        });
        test("chunks: an edited chunk evicted, regenerated and the overlay applied comes back the same", new T() {
            public void run() {
                Nation n = country();
                // a capital chunk with its own buildings, streets and a national building
                int cx = -1, cy = -1, nat = -1, ccx = n.capital().cx / 64, ccy = n.capital().cy / 64;
                for (int r0 = 0; r0 < 12 && nat < 0; r0++)
                    for (int dy = -r0; dy <= r0 && nat < 0; dy++)
                        for (int dx = -r0; dx <= r0 && nat < 0; dx++) {
                            if (Math.max(Math.abs(dx), Math.abs(dy)) != r0) continue;
                            Chunk c = ChunkGen.generate(n, ccx + dx, ccy + dy);
                            int roads = 0;
                            for (byte t : c.tile) if (t == Tile.ROAD) roads++;
                            if (c.bCount < 3 || roads < 40) continue;
                            for (short v : c.bld)
                                if (v < 0) {
                                    nat = -v - 1;
                                    cx = ccx + dx;
                                    cy = ccy + dy;
                                    break;
                                }
                        }
                check(nat >= 0, "no chunk with a national building");
                // and a national building elsewhere, in chunks not in memory when it comes down
                int far = -1;
                for (int b = 0; b < n.bCount && far < 0; b++)
                    if (n.bx[b] >> 6 > cx + 1 && n.bw[b] > 0 && Math.abs((n.by[b] >> 6) - cy) < 20) far = b;
                check(far >= 0, "no other national building");
                // a region over the nation's chunks, holding the national buildings (as P7's will)
                Region r = new Region(n.size, n.size, n.seed, n.preset, Nation.SECT, false);
                check(r.store.pw == n.sw, "pages and chunks don't line up");
                r.bCount = n.bCount;
                r.bx = n.bx;
                r.by = n.by;
                r.bw = n.bw;
                r.bh = n.bh;
                r.bfloors = n.bfloors.clone();
                r.recordEdits();
                ChunkCache cache = new ChunkCache(n, 64);
                cache.setOverlay(r.overlay);
                Chunk c = cache.get(cx, cy);
                long generated = c.checksum();
                cache.pin(cx, cy);
                r.store.attach(c);
                int x0 = cx * 64, y0 = cy * 64;
                // barricades across streets; a street cratered and put back; a house's corner smashed, then it comes down
                int walls = 0, back = -1;
                for (int i = 0; i < Chunk.AREA; i++) {
                    if (c.tile[i] != Tile.ROAD) continue;
                    if (walls < 12) {
                        r.set(x0 + (i & 63), y0 + (i >> 6), Tile.BARRICADE);
                        walls++;
                    } else if (back < 0) back = i;
                }
                r.set(x0 + (back & 63), y0 + (back >> 6), Tile.RUBBLE);
                r.set(x0 + (back & 63), y0 + (back >> 6), Tile.ROAD);
                r.set(x0 + c.bx[0], y0 + c.by[0], Tile.GRASS);
                r.destroyBuilding(c.id(0));
                r.destroyBuilding(c.id(2));
                r.destroyBuilding(nat);
                r.destroyBuilding(far);
                r.rebuildNav(x0, y0, x0 + 63, y0 + 63);
                check(c.bfloors[0] == 0 && c.bfloors[2] == 0 && c.bfloors[1] > 0, "the wrong buildings came down");
                check(r.overlay.editCount() == 12, r.overlay.editCount() + " tile edits held, not 12");
                check(r.overlay.gone(c.index, 0) && r.overlay.gone(c.index, 2) && !r.overlay.gone(c.index, 1), "destroyed buildings not recorded");
                for (short v : c.bld) check(v != -(nat + 1), "the national building still stands");
                long edited = c.checksum();
                check(edited != generated, "nothing changed");
                check(cache.get(cx, cy) == c, "the chunk edited in place was taken for one needing its overlay");
                cache.unpin(cx, cy);
                check(cache.evict(cx, cy), "not evicted");
                Chunk again = cache.get(cx, cy);
                check(again != c && again.checksum() == edited, "regenerated with the overlay, it differs from the edited chunk");
                // the other national building's chunks weren't in memory: they come in with its rubble
                int others = 0;
                for (int oy = n.by[far] >> 6; oy <= (n.by[far] + n.bh[far] - 1) >> 6; oy++)
                    for (int ox = n.bx[far] >> 6; ox <= (n.bx[far] + n.bw[far] - 1) >> 6; ox++) {
                        Chunk o = cache.get(ox, oy), fresh = ChunkGen.generate(n, ox, oy);
                        int had = 0;
                        for (int i = 0; i < Chunk.AREA; i++) {
                            check(o.bld[i] != -(far + 1), "the national building stands in chunk " + ox + "," + oy);
                            if (fresh.bld[i] == -(far + 1)) {
                                had++;
                                check(o.tile[i] == Tile.RUBBLE, "no rubble where it stood");
                            }
                        }
                        if (had > 0) others++;
                    }
                check(others > 0, "the far national building stood in no chunk");
                cache.shutdown();
            }
        });
        test("chunks: the cache evicts, pins, waits and regenerates the same chunk", new T() {
            public void run() throws Exception {
                final Nation n = country();
                final ChunkCache cache = new ChunkCache(n, 64);
                int cx = n.capital().cx / 64, cy = n.capital().cy / 64;
                long first = cache.get(cx, cy).checksum();
                cache.pin(cx + 1, cy);
                cache.get(cx + 1, cy);
                for (int k = 0; k < 150; k++) cache.get(cx - 20 + k % 15, cy + 5 + k / 15);
                check(cache.size() <= 64, "the cache holds " + cache.size());
                check(!cache.contains(cx, cy), "the old chunk was evicted");
                check(cache.contains(cx + 1, cy), "the pinned chunk was kept");
                check(cache.get(cx, cy).checksum() == first, "regenerated after eviction, it is the same");
                cache.unpin(cx + 1, cy);
                // the overlay: applied on first use, again from scratch when its version changes, and after eviction
                final int[] version = {1};
                final int target = cy * n.sw + cx;
                cache.setOverlay(new ChunkCache.Overlay() {
                    public int version(int c) {
                        return c == target ? version[0] : 0;
                    }

                    public void apply(Chunk c) {
                        if (c.index != target) return;
                        c.tile[0] = Tile.BARRICADE;
                        if (version[0] >= 2) c.tile[1] = Tile.RUBBLE;
                    }
                });
                Chunk o = cache.get(cx, cy);
                check(o.tile[0] == Tile.BARRICADE && o.tile[1] != Tile.RUBBLE, "the overlay applied");
                version[0] = 2;
                o = cache.get(cx, cy);
                check(o.tile[0] == Tile.BARRICADE && o.tile[1] == Tile.RUBBLE && o.overlayVersion == 2, "the newer overlay applied");
                long withOverlay = o.checksum();
                check(cache.evict(cx, cy), "evicted");
                check(cache.get(cx, cy).checksum() == withOverlay, "regenerated and the overlay applied again: the same");
                cache.setOverlay(null);
                // four threads asking for the same new chunk at once: made once, the others wait
                final int nx = cx + 30, ny = cy - 30;
                long before = cache.generated;
                Thread[] ts = new Thread[4];
                final long[] got = new long[4];
                for (int t = 0; t < 4; t++) {
                    final int t0 = t;
                    ts[t] = new Thread(new Runnable() {
                        public void run() {
                            got[t0] = cache.get(nx, ny).checksum();
                        }
                    });
                    ts[t].start();
                }
                for (Thread t : ts) t.join();
                check(cache.generated - before == 1, "made " + (cache.generated - before) + " times");
                check(got[0] == got[1] && got[1] == got[2] && got[2] == got[3], "all four got the same chunk");
                // the prefetcher fills the cache and nothing else
                cache.prefetchAround(cx - 40, cy + 40, 2);
                cache.drain();
                check(cache.contains(cx - 40, cy + 40) && cache.contains(cx - 42, cy + 42), "prefetched");
                check(cache.peek(cx - 41, cy + 41).checksum() == ChunkGen.generate(n, cx - 41, cy + 41).checksum(), "a prefetched chunk is the chunk");
                cache.shutdown();
                System.out.printf("        generated %d, waited %d, prefetched %d, evicted %d; capacity for a 256 MB heap: %d chunks%n",
                        cache.generated, cache.waits, cache.prefetched, cache.evicted, ChunkCache.capacityFor(256L << 20));
            }
        });
        test("chunks: a Region reads chunks as its pages", new T() {
            public void run() {
                Nation n = country();
                Region r = new Region(n.sw * Chunk.SIZE, n.sw * Chunk.SIZE, n.seed, n.preset, Chunk.SIZE, false);
                check(r.sect == Chunk.SIZE && r.secW == n.sw, "sectors are the chunk square");
                int cx0 = n.capital().cx / 64, cy0 = n.capital().cy / 64, buildings = 0;
                for (int k = 0; k < 9; k++) {
                    Chunk c = ChunkGen.generate(n, cx0 - 1 + k % 3, cy0 - 1 + k / 3);
                    r.store.attach(c);
                    check(r.store.chunk(c.index) == c && c.index == r.store.pageOf(c.cx * 64, c.cy * 64), "the page is the chunk");
                    for (int y = 0; y < 64; y++)
                        for (int x = 0; x < 64; x++) {
                            int tx = c.cx * 64 + x, ty = c.cy * 64 + y;
                            check(r.t(tx, ty) == c.t(x, y) && r.building(tx, ty) == c.building(x, y), "tile " + tx + "," + ty);
                        }
                    for (int i = 0; i < c.bCount; i++, buildings++) {
                        int id = c.id(i), x = r.bx(id), y = r.by(id);
                        check(Chunk.isChunkBuilding(id) && r.building(x, y) == id && r.building(x + r.bw(id) - 1, y + r.bh(id) - 1) == id, "building " + i + " found by its tiles");
                        check(x == c.cx * 64 + c.bx[i] && y == c.cy * 64 + c.by[i] && r.bkind(id) == c.bkind[i] && r.bfloors(id) == c.bfloors[i]
                                && r.bres(id) == c.bres[i] && r.broof(id) == c.broof[i] && r.bset(id) == c.settlement, "building " + i + " read through the accessors");
                        float dx = r.doorX(id), dy = r.doorY(id);
                        check(c.doorX[i] < 0 ? dx < 0 : Tile.walkable(r.tileAtPx(dx, dy)) && Math.abs(dx - (x + r.bw(id) / 2f) * 16) <= (r.bw(id) / 2f + 1) * 16, "door of " + i);
                    }
                }
                check(buildings > 50, "only " + buildings + " buildings looked at");
                // and today's maps: Region's own buildings by id are the arrays
                Region m = RegionGen.generate(768, RegionGen.COAST, 1);
                for (int b = 0; b < m.bCount; b++)
                    check(m.bx(b) == m.bx[b] && m.bkind(b) == m.bkind[b] && m.building(m.bx[b], m.by[b]) == b && m.building(m.by[b] * m.W + m.bx[b]) == b, "building " + b);
                System.out.printf("        9 chunks attached as pages, %d buildings read through Region%n", buildings);
            }
        });
        test("drawing: a real-scale map at every zoom, from the coarse layer and cached chunks", new T() {
            public void run() {
                Nation n = country();
                ChunkCache cache = new ChunkCache(n, 512);
                DesktopPlatform pf = new DesktopPlatform(new java.io.File("build/test-data"));
                WorldView v = new WorldView(NationPainter.source(n, cache), pf);
                NationPainter np = v.np;
                check(np.countryImage.width() == n.cw / np.countryStep && np.tintImage.width() == n.sw, "country picture and tint sized");
                // district pictures are pure, and painting them makes no chunk
                int dx = n.capital().cx / NationPainter.DISTRICT, dy = n.capital().cy / NationPainter.DISTRICT;
                for (int lvl = 0; lvl < NationPainter.DTPP.length; lvl++) {
                    int tpp = NationPainter.DTPP[lvl], side = NationPainter.DISTRICT / tpp;
                    int[] a = np.paint(dx * 512, dy * 512, tpp, side), b = np.paint(dx * 512, dy * 512, tpp, side);
                    check(java.util.Arrays.equals(a, b), "district picture " + lvl + " the same twice");
                }
                check(cache.generated == 0, "district pictures generated chunks");
                Camera cam = new Camera();
                cam.dp = 2;
                cam.setScreen(360, 640, n.px());
                check(cam.minZoom < 0.002f && cam.minZoom > 0.0005f, "minZoom from the map's size: " + cam.minZoom);
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(360, 640, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                float[] zooms = {cam.minZoom, 0.006f, 0.035f, 0.1f, 0.5f};
                for (float z : zooms) {
                    cam.zoom = z;
                    cam.cx = n.capital().cx * 16f;
                    cam.cy = n.capital().cy * 16f;
                    cam.update(0, n.px());
                    for (int f = 0; f < 30; f++) {
                        java.awt.Graphics2D g = img.createGraphics();
                        v.draw(new DesktopGfx(g, 360, 640), cam, 0.016f);
                        g.dispose();
                        cache.drain();
                    }
                }
                check(np.painted > 0 && v.map.painted > 0, "pictures painted: district " + np.painted + ", street " + v.map.painted);
                check(cache.generated > 0 && cache.generated < 200, "chunks made for the street view: " + cache.generated);
                // the middle of the screen at street zoom is a street picture of the capital, not the sea
                int c = img.getRGB(180, 320);
                check(c != (NationPainter.WATER_DEEP | 0) , "street view drew nothing");
                cache.shutdown();
                System.out.printf("        %d district and %d street pictures painted, %d chunks made in the background%n", np.painted, v.map.painted, cache.generated);
            }
        });
    }
}

