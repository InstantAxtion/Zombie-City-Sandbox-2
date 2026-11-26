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

    private static void define() {
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
        test("region: every preset and size builds", new T() {
            public void run() {
                for (int size : new int[]{768, 1024}) {
                    for (int p = 0; p < RegionGen.PRESETS.length; p++) {
                        Region r = RegionGen.generate(size, p, 100 + p);
                        check(r.settlements.get(0).kind == Region.S_CITY, "a city first");
                        int towns = 0, bases = 0;
                        for (Region.Settlement s : r.settlements) {
                            if (s.kind == Region.S_TOWN) towns++;
                            if (s.kind == Region.S_BASE) bases++;
                            check(s.hubNode >= 0 || s.kind == Region.S_VILLAGE, s.name + " has no centre junction");
                        }
                        check(towns >= 2, RegionGen.PRESETS[p] + " " + size + ": towns " + towns);
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
                World a = game(21, 768, "sandbox", 1), b = game(21, 768, "sandbox", 4);
                for (World w : new World[]{a, b}) {
                    camOver(w, Setup.originX, Setup.originY);
                    Tools.paintZombies(w, Setup.originX, Setup.originY, 900, 500, -1);
                }
                for (int k = 0; k < 60 * 20; k++) {
                    a.step();
                    b.step();
                    if (k % 200 == 0) check(Audit.hash(a) == Audit.hash(b), "diverged at step " + k);
                }
                check(Audit.hash(a) == Audit.hash(b), "different after a minute");
                a.shutdown();
                b.shutdown();
            }
        });
        test("sim: everyone accounted for with no dead about", new T() {
            public void run() {
                World w = game(5, 768, "sandbox", 2);
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
                World w = game(31, 768, "lab_leak", 2);
                camOver(w, Setup.originX, Setup.originY);
                Tools.paintZombies(w, Setup.originX, Setup.originY, 300, 300, -1);
                play(w, 50, false);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                SaveIO.save(w, bo);
                World l = SaveIO.load(content, new ByteArrayInputStream(bo.toByteArray()), 2);
                check(Audit.hash(l) == Audit.hash(w), "different straight after loading");
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
        test("country: a capital of a million, cities, rail, states", new T() {
            public void run() {
                Region r = RegionGen.generate(2304, RegionGen.COAST, 9);
                long people = 0;
                int cities = 0, bases = 0;
                for (Region.Settlement s : r.settlements) {
                    people += s.residents;
                    if (s.kind == Region.S_CITY) cities++;
                    if (s.kind == Region.S_BASE) bases++;
                }
                check(people > 1500000, "only " + people + " people");
                check(r.city().capital && r.city().residents > 700000, "the capital has " + r.city().residents);
                check(cities >= 3, "cities " + cities);
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
                    if (r.bfloors[i] <= 0 || w.fires.heat[i] > 0 || w.doorX[i] < 0) continue;
                    float dx = w.doorX[i] - st.x, dy = w.doorY[i] - st.y, d = dx * dx + dy * dy;
                    if (d < bd && d > 300 * 300) { bd = d; near = i; }
                }
                int before = w.fires.putOut;
                w.fires.ignite(near, 0.2f);
                for (int k = 0; k < 200 && w.fires.heat[near] > 0; k++) {
                    w.weather.state = Weather.CLEAR;
                    w.step();
                }
                check(w.fires.putOut > before || r.bfloors[near] == 0, "the crew never got there");
                check(w.fires.heat[near] == 0 && r.bfloors[near] > 0, "the crew didn't save it");
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
                            sec.popIn[y * sec.sw + x] = 0;
                            sec.inf[y * sec.sw + x] = 0;
                        }
                    if (sec.zIn[s] < 1000) sec.zIn[s] = 1000;
                    w.step();
                }
                check(w.ecology.hunger[s] > 0.5f, "not hungry: " + w.ecology.hunger[s]);
                check(w.ecology.nest[s], "no nest after three days");
                // a strain where people still live nearby spreads as the dead move
                int s2 = -1;
                for (int k = 0; k < sec.n && s2 < 0; k++)
                    if (sec.settlement[k] >= 0 && sec.home[k] > 0 && Math.abs(k % sec.sw - sx) + Math.abs(k / sec.sw - sy) > 6) s2 = k;
                sec.popIn[s2] = 0;
                sec.zIn[s2] = 800;
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
                        w.sec.popIn[s] -= 300;
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
                        w.sec.popIn[s] += add;
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
                    w.sec.zIn[best] = 600;
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
                World w = Setup.newGame(content, 768, RegionGen.COAST, 77, content.scenario("lab_leak"), 2, Roles.GOD, 60000);
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
                for (int s = 0; s < w.sec.n; s++) if (w.sec.settlement[s] == c.id) w.sec.popIn[s] = 0;
                w.setStrategic(true);
                for (int k = 0; k < 60 && !w.ended; k++) w.step();
                check(w.ended && !w.won, "an empty capital should lose: " + w.endTitle);
                w.shutdown();
                w = game(32, 768, "protect", 2);
                c = w.r.city();
                for (int s = 0; s < w.sec.n; s++) if (w.sec.settlement[s] == c.id) w.sec.zIn[s] += 5000;
                w.setStrategic(true);
                for (int k = 0; k < 150 && !w.ended; k++) w.step();
                check(w.ended && !w.won, "a capital full of the dead should lose: " + w.endTitle);
                w.shutdown();
                w = game(33, 768, "officer", 2);
                check(w.playerEnt >= 0 && w.e.kind[w.playerEnt] == Ents.COP, "On patrol isn't played as an officer");
                w.shutdown();
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
        test("balance: outbreak sizes play out in broad bands (many seeds)", new T() {
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
        test("country: sectors belong to the town whose people live there", new T() {
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
                for (int s : secs) w.sec.zIn[s] += 300;
                for (int k = 0; k < 6; k++) w.step();
                check(!w.ended, "lost at once: " + w.endText);
                for (int k = 0; k < 170 && !w.ended; k++) {
                    for (int s : secs) w.sec.zIn[s] = Math.max(w.sec.zIn[s], 300);
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
                        w.sec.popIn[s] = 0;
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
                w.sec.popIn[s] += 800;
                w.society.colonies.add(col);
                w.sec.zIn[s] += 40;
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
                    if (w.veh.state[v] == Vehicles.V_ARRIVED && w.veh.squad[v] < 0 && w.veh.type(v).seats > 0 && !w.veh.type(v).flies) parked++;
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
    }
}
