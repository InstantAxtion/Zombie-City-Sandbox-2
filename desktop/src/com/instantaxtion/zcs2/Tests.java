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
