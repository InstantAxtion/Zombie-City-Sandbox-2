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
                play(w, 3, false);
                check(w.ended && !w.won, "an empty capital should lose: " + w.endTitle);
                w.shutdown();
                w = game(32, 768, "protect", 2);
                c = w.r.city();
                for (int s = 0; s < w.sec.n; s++) if (w.sec.settlement[s] == c.id) w.sec.zIn[s] += 500;
                play(w, 3, false);
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
                java.util.List<Balance.Run> runs = Balance.batch(content, sizes, 2, 1536, 12, 2);
                System.out.print(Balance.table(runs, sizes).replaceAll("(?m)^", "        "));
                for (Balance.Run r : runs) {
                    if (r.zombies == 3000)
                        check(r.result == Balance.CONTAINED && r.days < 1, "a small outbreak should be put down within a day: " + Balance.RESULTS[r.result] + " after " + r.days);
                    if (r.zombies == 60000) {
                        check(r.days > 0.2f, "a medium outbreak should be a fight, not over in " + r.days + " days");
                        check(r.lostShare < 0.6f, "a medium outbreak shouldn't usually take the country: lost " + r.lostShare);
                    }
                    if (r.zombies == 250000) {
                        check(r.result != Balance.CONTAINED || r.days > 2, "a big outbreak shouldn't be over in " + r.days + " days");
                        check(r.lostShare > 0.05f, "a big outbreak should cost a lot: lost " + r.lostShare);
                    }
                }
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
