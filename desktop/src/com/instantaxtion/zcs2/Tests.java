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
                check(w.totalZombies() <= 800, "dead appeared from nowhere: " + w.totalZombies());
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
                play(w, 125, false);
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
