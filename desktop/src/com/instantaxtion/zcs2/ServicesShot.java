package com.instantaxtion.zcs2;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import javax.imageio.ImageIO;

/**
 * The air and emergency services in action, each set up and shot to a PNG (and what happened printed):
 *   java ... ServicesShot outDir [size] [seed] [scene...]
 * Scenes: assault, lift, ambulance, fire, boats, checkpoint, corridor, rescue, engineers, aid, flight. A size of
 * 8192 or more is a national map.
 */
public final class ServicesShot {
    static Content c;
    static File out;
    static int W = 720, H = 1280;
    static float density = 1.8f;
    static String prefix = "";

    public static void main(String[] a) throws Exception {
        out = new File(a.length > 0 ? a[0] : "build/services");
        out.mkdirs();
        int size = a.length > 1 ? Integer.parseInt(a[1]) : 1536;
        long seed = a.length > 2 ? Long.parseLong(a[2]) : 7;
        String[] scenes = a.length > 3 ? java.util.Arrays.copyOfRange(a, 3, a.length)
                : new String[]{"assault", "lift", "ambulance", "fire", "boats", "checkpoint", "corridor", "rescue", "engineers", "aid", "flight"};
        c = Content.load(new FileInputStream("assets/content.ini"));
        prefix = size >= 8192 ? "nat_" : "";
        for (String s : scenes) {
            long t0 = System.currentTimeMillis();
            try {
                scene(s, size, seed);
            } catch (Throwable t) {
                System.out.println(s + ": FAILED " + t);
                t.printStackTrace(System.out);
            }
            System.out.println(s + " (" + (System.currentTimeMillis() - t0) / 1000 + " s)");
        }
    }

    static World game(int size, long seed, String scen) {
        return Setup.newGame(c, size, RegionGen.COAST, seed, c.scenario(scen), 2);
    }

    static void cam(World w, float x, float y) {
        w.camX0 = x - 500;
        w.camX1 = x + 500;
        w.camY0 = y - 900;
        w.camY1 = y + 900;
        w.camDetail = true;
    }

    static void steps(World w, int n, float x, float y) {
        for (int k = 0; k < n; k++) {
            cam(w, x, y);
            w.step();
        }
    }

    static void shot(World w, String name, float x, float y, float zoom) throws Exception {
        DesktopPlatform pf = new DesktopPlatform(new File(System.getProperty("java.io.tmpdir"), "servicesshot"));
        pf.density = density;
        WorldView view = new WorldView(w, pf);
        Camera cam = new Camera();
        cam.dp = density;
        cam.setScreen(W, H, w.r.px());
        cam.cx = x;
        cam.cy = y;
        cam.zoom = zoom;
        cam.update(0, w.r.px());
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        DesktopGfx g = new DesktopGfx(img.createGraphics(), W, H);
        for (int k = 0; k < 30; k++) {
            if (w.r.cache != null) Thread.sleep(20);
            view.draw(g, cam, 0.05f);
        }
        File f = new File(out, prefix + name + ".png");
        ImageIO.write(img, "png", f);
        System.out.println("  shot " + f.getPath());
    }

    static void news(World w, int n) {
        int from = Math.max(0, w.news.log.size() - n);
        for (int k = from; k < w.news.log.size(); k++) System.out.println("    | " + w.news.log.get(k).text);
    }

    static int transport(World w) {
        for (int v = 0; v < w.veh.top; v++) if (w.veh.usable(v) && w.services.transport(v) && w.veh.state[v] == Vehicles.V_PARKED) return v;
        return -1;
    }

    static void scene(String sceneName, int size, long seed) throws Exception {
        World w;
        switch (sceneName) {
            case "assault": {
                w = game(size, seed, "sandbox");
                int v = transport(w);
                if (v < 0) {
                    System.out.println("  no transport helicopter");
                    return;
                }
                Region.Settlement town = farTown(w, w.veh.x[v], w.veh.y[v]);
                float[] p = w.r.openGroundNear(town.cx * 16f, town.cy * 16f, false);
                Tools.paintZombies(w, p[0] + 260, p[1], 40, 60, -1);
                steps(w, 20, p[0], p[1]);
                boolean ok = Actions.vehicleOrder(w, v, Orders.VO_ASSAULT, p[0], p[1]);
                System.out.printf("  helicopter %d at (%.0f, %.0f) ordered to (%.0f, %.0f), %.0f px: %s%n", v, w.veh.x[v], w.veh.y[v], p[0], p[1],
                        Math.hypot(p[0] - w.veh.x[v], p[1] - w.veh.y[v]), ok);
                boolean flying = false, landed = false;
                for (int k = 0; k < 20 * 900; k++) {
                    cam(w, w.veh.x[v], w.veh.y[v]);
                    w.step();
                    Services.AirTask t = w.services.airTask(v);
                    if (!flying && w.veh.flying(v) && w.veh.crewN[v] > 0 && Math.hypot(w.veh.x[v] - p[0], w.veh.y[v] - p[1]) < 900) {
                        flying = true;
                        shot(w, "assault_1_inbound", w.veh.x[v], w.veh.y[v], 0.7f);
                    }
                    if (!landed && t == null && k > 20) {
                        landed = true;
                        steps(w, 60, p[0], p[1]);
                        System.out.println("  dropped; helicopter now " + Vehicles.STATE_NAMES[w.veh.state[v]] + " at t=" + (int) w.time);
                        shot(w, "assault_2_dropped", p[0], p[1], 0.8f);
                        break;
                    }
                }
                news(w, 8);
                break;
            }
            case "lift": {
                w = game(size, seed, "sandbox");
                int v = transport(w);
                Region.Settlement town = farTown(w, w.veh.x[v], w.veh.y[v]);
                float[] p = w.r.openGroundNear(town.cx * 16f, town.cy * 16f, false);
                Squads.Squad sq = Actions.squad(w, "rifleman", 6, p[0], p[1]);
                steps(w, 40, p[0], p[1]);
                Actions.vehicleOrder(w, v, Orders.VO_EVAC, p[0], p[1]);
                boolean shotLoad = false;
                for (int k = 0; k < 20 * 900 && w.services.airTask(v) != null; k++) {
                    cam(w, p[0], p[1]);
                    w.step();
                    Services.AirTask t = w.services.airTask(v);
                    if (!shotLoad && t != null && t.phase == 1 && w.veh.crewN[v] > 1) {
                        shotLoad = true;
                        shot(w, "lift_1_boarding", p[0], p[1], 1.0f);
                    }
                }
                System.out.println("  squad alive " + sq.alive + " members " + sq.n + "; helicopter " + Vehicles.STATE_NAMES[w.veh.state[v]] + " crew " + w.veh.crewN[v]);
                news(w, 6);
                break;
            }
            case "ambulance": {
                w = game(size, seed, "sandbox");
                Region.Facility h = null;
                for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_HOSPITAL && f.settlement == w.r.city().id) h = f;
                float[] p = w.r.openGroundNear(h.x + 700, h.y + 300, false);
                steps(w, 20, p[0], p[1]);
                Content.Unit civ = w.c.unit("civilian");
                for (int k = 0; k < 6; k++) {
                    float[] q = w.r.openGroundNear(p[0] + k * 30, p[1], false);
                    int i = w.spawnCiv(q[0], q[1], civ);
                    w.stats.startPeople++;
                    w.e.hp[i] = w.e.maxHp[i] * 0.4f;
                    if (k % 2 == 0) {
                        w.e.flags[i] |= Ents.F_INFECTED;
                        w.e.infT[i] = 400;
                    }
                }
                int hs = w.r.sectorOfPx(h.x, h.y);
                float inf0 = w.sec.inf[hs];
                boolean shotScene = false, shotDrive = false;
                for (int k = 0; k < 20 * 400; k++) {
                    cam(w, p[0], p[1]);
                    w.step();
                    for (Services.Ambulance am : w.services.ambs) {
                        if (!shotScene && am.phase == Services.A_SCENE) {
                            shotScene = true;
                            steps(w, 50, p[0], p[1]);
                            shot(w, "ambulance_1_paramedics", w.veh.x[am.v], w.veh.y[am.v], 1.1f);
                            break;
                        }
                        if (shotScene && !shotDrive && am.phase == Services.A_BACK && w.veh.crewN[am.v] > 1) {
                            shotDrive = true;
                            steps(w, 40, p[0], p[1]);
                            shot(w, "ambulance_2_siren", w.veh.x[am.v], w.veh.y[am.v], 0.6f);
                            break;
                        }
                    }
                }
                System.out.printf("  carried %d (bitten %d); hospital sector bitten %.0f -> %.0f; ledger %d%n", w.services.carried, w.services.carriedBitten, inf0, w.sec.inf[hs], Audit.ledger(w));
                news(w, 6);
                break;
            }
            case "fire": {
                w = game(size, seed, "sandbox");
                Region.Facility st = null;
                for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_FIRE && f.settlement == w.r.city().id) st = f;
                // a lived-in building 700 px or more from the station, in the streets round it (chunks active there)
                steps(w, 20, st.x, st.y);
                int b = -1;
                float bd = Float.MAX_VALUE;
                for (int s0 = 0; s0 < w.sec.n; s0++) {
                    if (Math.abs(w.sec.cx(s0) - st.x) > 3000 || Math.abs(w.sec.cy(s0) - st.y) > 3000 || !w.act.activePx(w.sec.cx(s0), w.sec.cy(s0))) continue;
                    for (int k : w.secBuildings[s0]) {
                        if (w.r.bfloors(k) <= 0 || w.r.doorX(k) < 0 || w.r.bres(k) <= 0) continue;
                        float d = (float) Math.hypot(w.r.doorX(k) - st.x, w.r.doorY(k) - st.y);
                        if (d > 700 && d < bd) {
                            bd = d;
                            b = k;
                        }
                    }
                }
                float bx = w.r.doorX(b), by = w.r.doorY(b);
                steps(w, 20, bx, by);
                w.fires.ignite(b, 0.6f);
                boolean sprayed = false;
                int crew = 0;
                for (int k = 0; k < 20 * 300; k++) {
                    cam(w, bx, by);
                    w.weather.state = Weather.CLEAR;
                    w.step();
                    crew = 0;
                    int spray = 0;
                    for (int i = 0; i < w.e.top; i++)
                        if (w.e.loc[i] == Ents.FREE && (w.e.flags[i] & Ents.F_CREW) != 0) {
                            crew++;
                            if (w.e.state[i] == Services.CREW_SPRAY) spray++;
                        }
                    if (spray >= 3 && !sprayed) {
                        sprayed = true;
                        steps(w, 30, bx, by);
                        System.out.printf("  %d firefighters on foot, %d at the hoses, heat %.2f at t=%d%n", crew, spray, w.fires.heat(b), (int) w.time);
                        shot(w, "fire_1_hoses", bx, by, 1.3f);
                        break;
                    }
                }
                if (!sprayed) System.out.println("  nobody sprayed; crew on foot " + crew);
                Tools.paintZombies(w, bx + 150, by + 60, 25, 40, -1);
                for (int k = 0; k < 20 * 6; k++) {
                    cam(w, bx, by);
                    w.step();
                }
                shot(w, "fire_2_pull_back", bx, by, 0.9f);
                news(w, 6);
                System.out.println("  crew deaths " + w.stats.crewDeaths + " ledger " + Audit.ledger(w) + " audit " + Audit.check(w));
                break;
            }
            case "boats": {
                w = game(size, seed, "evacuate");
                int best = -1;
                for (int k = 0; k < w.sec.n && best < 0; k++)
                    if (w.navy.coastal(k) && w.sec.settlement[k] >= 0 && w.sec.popIn[k] > (w.national ? 0 : 300)) best = k;
                if (best < 0) {
                    System.out.println("  no coastal sector");
                    return;
                }
                int st = w.sec.settlement[best];
                Actions.evac(w, st, true);
                float x = w.sec.cx(best), y = w.sec.cy(best);
                for (int k = 0; k < 20 * 120; k++) {
                    cam(w, x, y);
                    w.step();
                    if (w.navy.boats.size() >= 1 && w.navy.boats.get(0).phase >= 1) break;
                }
                Navy.Boat bt = w.navy.boats.isEmpty() ? null : w.navy.boats.get(0);
                System.out.println("  boats " + w.navy.boats.size() + " trips " + w.navy.boatTrips + " carried " + w.navy.boatCarried + " rescued " + w.navy.rescued);
                shot(w, "boats_1_shore", bt != null ? bt.x : x, bt != null ? bt.y : y, 0.9f);
                break;
            }
            case "checkpoint": {
                w = game(size, seed, "sandbox");
                Region.Settlement town = null;
                for (Region.Settlement t : w.r.settlements) if (t.kind == Region.S_TOWN && !t.edgeNodes.isEmpty() && (town == null || t.residents > town.residents)) town = t;
                int node = town.edgeNodes.get(0);
                float x = w.r.roads.nx[node], y = w.r.roads.ny[node];
                steps(w, 20, x, y);
                Actions.quarantine(w, town.id, true);
                // until the post is manned (the police drive out from their station)
                for (int k = 0; k < 20 * 240; k++) {
                    cam(w, x, y);
                    w.step();
                    boolean manned = false;
                    for (Services.Checkpoint cp : w.services.checkpoints) {
                        if (cp.node != node || cp.squad < 0) continue;
                        Squads.Squad q = w.sq.get(cp.squad);
                        int ld = q.leader(w.e);
                        manned = ld >= 0 && w.e.loc[ld] == Ents.FREE && Math.hypot(w.e.x[ld] - x, w.e.y[ld] - y) < 100;
                    }
                    if (manned) break;
                }
                Content.Unit civ = w.c.unit("civilian");
                for (int k = 0; k < 6; k++) {
                    float[] q = w.r.openGroundNear(x + 40 + k * 8, y + 20, false);
                    int i = w.spawnCiv(q[0], q[1], civ);
                    w.stats.startPeople++;
                    if (k < 2) w.e.flags[i] |= Ents.F_INFECTED;
                    w.e.infT[i] = 900;
                }
                steps(w, 20 * 8, x, y);
                int caught = 0, stopped = 0, manned = 0;
                for (Services.Checkpoint cp : w.services.checkpoints) {
                    caught += cp.caught;
                    stopped += cp.stopped;
                    if (cp.squad >= 0) manned++;
                }
                System.out.println("  checkpoints " + w.services.checkpoints.size() + " manned " + manned + " caught " + caught + " stopped " + stopped + " ledger " + Audit.ledger(w));
                shot(w, "checkpoint_1_manned", x, y, 1.0f);
                news(w, 5);
                break;
            }
            case "corridor": {
                w = game(size, seed, "sandbox");
                Region.Settlement town = null;
                for (Region.Settlement t : w.r.settlements) if (t.kind == Region.S_TOWN && (town == null || t.residents > town.residents)) town = t;
                Actions.evac(w, town.id, true);
                boolean ok = Actions.corridor(w, town.id);
                steps(w, 20 * 30, town.cx * 16f, town.cy * 16f);
                Services.Corridor cr = w.services.corridors.isEmpty() ? null : w.services.corridors.get(0);
                System.out.println("  corridor " + ok + " " + (cr != null ? cr.escorts + " officers" : ""));
                if (cr != null) {
                    float mx = (cr.ax + cr.bx) / 2, my = (cr.ay + cr.by) / 2;
                    System.out.println("  escorted at the middle: " + w.forces.escortNear(mx, my, 1));
                    float span = (float) Math.hypot(cr.bx - cr.ax, cr.by - cr.ay);
                    shot(w, "corridor_1", mx, my, Math.max(0.006f, Math.min(0.2f, W * 0.7f / span)));
                }
                news(w, 4);
                break;
            }
            case "rescue": {
                // (by road: the helicopters stay on their pads for this one)
                if (size < 8192) c.setRule("airAssault", 0); // (on a national map the bus is hours away: the helicopter goes)
                w = game(size, seed, "sandbox");
                c.setRule("airAssault", 1);
                Region.Settlement ct = w.r.city();
                // a street of a few hundred people in the city, the dead indoors among them
                int s = -1;
                float bd = Float.MAX_VALUE;
                for (int k = 0; k < w.sec.n; k++) {
                    if (w.sec.settlement[k] != ct.id || w.sec.popIn[k] < 120 || w.sec.popIn[k] > 1500) continue;
                    float d = (float) Math.hypot(w.sec.cx(k) - ct.cx * 16f, w.sec.cy(k) - ct.cy * 16f);
                    if (d < bd) {
                        bd = d;
                        s = k;
                    }
                }
                float x = w.sec.cx(s), y = w.sec.cy(s);
                steps(w, 20, x, y);
                w.sec.zIn[w.sec.touch(s)] += Math.max(40, w.sec.popIn[s] / 2);
                boolean shotBus = false;
                for (int k = 0; k < 20 * 300; k++) {
                    cam(w, x, y);
                    w.step();
                    for (Services.Rescue r : w.services.rescues)
                        if (!shotBus && r.phase == 0 && w.veh.crewN[r.bus] > 4) {
                            shotBus = true;
                            shot(w, "rescue_1_bus", w.veh.x[r.bus], w.veh.y[r.bus], 0.9f);
                        }
                    for (Services.AirTask t : w.services.air)
                        if (t.kind == Services.T_RESCUE && t.phase == 1 && w.veh.crewN[t.v] > 2 && !shotBus) {
                            shotBus = true;
                            shot(w, "rescue_1_air", w.veh.x[t.v], w.veh.y[t.v], 0.9f);
                        }
                    if (shotBus && w.services.rescues.isEmpty() && w.services.air.isEmpty()) break;
                }
                System.out.println("  rescued by road " + w.services.rescuedTotal + ", lifted by air " + w.services.lifted + ", ledger " + Audit.ledger(w));
                news(w, 6);
                break;
            }
            case "engineers": {
                w = game(size, seed, "sandbox");
                Region.Settlement ct = w.r.city();
                RoadGraph rg = w.r.roads;
                int n = rg.nearestNode(ct.cx * 16f + 1200, ct.cy * 16f, 2000);
                float x = rg.nx[n], y = rg.ny[n];
                // half way along a street from that junction, off the junction itself
                for (int k = rg.adjStart[n]; k < rg.adjStart[n + 1]; k++) {
                    int ed = rg.adjEdge[k];
                    if (rg.ebridge[ed]) continue;
                    int o = rg.ea[ed] == n ? rg.eb[ed] : rg.ea[ed];
                    float mx = (rg.nx[n] + rg.nx[o]) / 2, my = (rg.ny[n] + rg.ny[o]) / 2;
                    if (Tile.roadish(w.r.tileAtPx(mx, my))) {
                        x = mx;
                        y = my;
                        break;
                    }
                }
                steps(w, 20, x, y);
                boolean ok = Actions.engineer(w, Engineers.J_WALL, x, y);
                System.out.println("  wall ordered: " + ok + "; jobs " + w.engineers.jobs.size());
                boolean shotWork = false;
                for (int k = 0; k < 20 * 600 && !w.engineers.jobs.isEmpty(); k++) {
                    cam(w, x, y);
                    w.step();
                    if (w.engineers.jobs.isEmpty()) break;
                    Engineers.Job j = w.engineers.jobs.get(0);
                    if (!shotWork && j.done > j.need * 0.4f) {
                        shotWork = true;
                        shot(w, "engineers_1_at_work", x, y, 1.1f);
                    }
                }
                steps(w, 40, x, y);
                System.out.println("  done " + w.engineers.jobsDone + " at t=" + (int) w.time + "; tile " + w.r.tileAtPx(x, y));
                shot(w, "engineers_2_wall", x, y, 1.1f);
                int e = -1;
                for (int k = 0; k < w.r.roads.edgeCount && e < 0; k++) if (w.r.roads.ebridge[k] && w.r.roads.ealive[k]) e = k;
                if (e >= 0) {
                    RoadGraph g = w.r.roads;
                    float bx = (g.nx[g.ea[e]] + g.nx[g.eb[e]]) / 2, by = (g.ny[g.ea[e]] + g.ny[g.eb[e]]) / 2;
                    System.out.println("  blow bridge: " + Actions.engineer(w, Engineers.J_BRIDGE, bx, by));
                    for (int k = 0; k < 20 * 900 && !w.engineers.jobs.isEmpty(); k++) {
                        cam(w, bx, by);
                        w.step();
                    }
                    System.out.println("  bridge alive " + g.ealive[e] + " at t=" + (int) w.time);
                    shot(w, "engineers_3_bridge", bx, by, 0.5f);
                }
                news(w, 6);
                break;
            }
            case "aid": {
                w = game(size, seed, "evacuate");
                w.setStrategic(true);
                for (int k = 0; k < 8 * 144 && w.services.aid.isEmpty(); k++) w.step();
                System.out.println("  camps " + w.society.camps.size() + " aid convoys " + w.services.aid.size() + " at t=" + (int) w.time);
                if (!w.services.aid.isEmpty()) {
                    Services.Aid ad = w.services.aid.get(0);
                    System.out.println("  convoy at (" + (int) ad.x + ", " + (int) ad.y + "), " + w.services.aid.size() + " on the road");
                    shot(w, "aid_1_convoy", ad.x, ad.y, 0.25f);
                }
                for (int k = 0; k < 4 * 144; k++) {
                    w.setStrategic(true);
                    w.step();
                }
                System.out.println("  delivered " + w.services.aidDelivered + " lost " + w.services.aidLost);
                news(w, 4);
                break;
            }
            case "flight": {
                w = game(size, seed, size >= 8192 ? "sandbox" : "lab_leak");
                Region.Facility ap = null;
                for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_AIRPORT) ap = f;
                if (ap == null) {
                    System.out.println("  no airport");
                    return;
                }
                cam(w, Setup.originX, Setup.originY);
                for (int k = 0; k < 20 * 70 && w.services.flightsOut == 0; k++) w.step();
                for (int k = 0; k < 20 * 6; k++) w.step();
                shot(w, "flight_1_departing", ap.x, ap.y, 0.12f);
                System.out.println("  flights " + w.services.flightsOut + " passengers " + w.services.flown + " seeded " + w.services.seededTotal + " closed " + w.services.airportClosed + " level " + w.gov.level);
                news(w, 4);
                break;
            }
            default:
                System.out.println("  unknown scene");
                return;
        }
        String audit = Audit.check(w);
        if (!audit.isEmpty()) System.out.println("  AUDIT: " + audit);
        w.shutdown();
    }

    /** A town 2-6 km from (x, y) (a far one if there's none). */
    static Region.Settlement farTown(World w, float x, float y) {
        Region.Settlement best = null;
        float bd = Float.MAX_VALUE;
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_TOWN && s.kind != Region.S_CITY) continue;
            float d = (float) Math.hypot(s.cx * 16f - x, s.cy * 16f - y);
            float want = Math.abs(d - (w.national ? 20000 : 3500));
            if (want < bd) {
                bd = want;
                best = s;
            }
        }
        return best;
    }
}
