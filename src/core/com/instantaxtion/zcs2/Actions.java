package com.instantaxtion.zcs2;

import java.util.Comparator;
import java.util.List;

/**
 * Every way the player changes the world goes through here: it's recorded (Director) and then carried out, and a
 * replay carries the same records out at the same ticks. One path, so a replay can't drift from the game.
 */
public final class Actions {
    public static final int PAINT = 1, HORDE = 2, SQUAD = 3, VEHICLE = 4, ERASE = 5, STRIKE = 6, BRIDGE = 7, ORDER = 8,
            RELEASE = 9, HOME = 10, FORM_ORDER = 11, FORM_RELEASE = 12, CURFEW = 13, EVAC = 14, LEVEL = 15, QUARANTINE = 16,
            DEPLOY = 17, ARTILLERY = 18, REQUEST_ARMY = 19, CAMERA = 20, STRATEGIC = 21, JOY = 22, FIRE = 23, MAX_IND = 24,
            WATCH = 25, ADD_PLAYER = 26, SWITCH_PLAYER = 27, GENERAL = 28, PRECINCT_CORDON = 29, PRECINCT_EVAC = 30,
            DEFENCE = 31, BUILD = 32, VEH_ORDER = 33, DRIVE = 34, POSSESS = 35, STANCE = 36, SQ_ORDER = 37, MOUNT = 38,
            POSTURE = 39, RULE = 40, CLEAR = 41, TEMPLATE = 42, MASS = 43, SURV = 44;

    public static Object exec(World w, int kind, float[] f, int[] i, String s) {
        w.director.record(kind, f, i, s);
        Director.Event e = new Director.Event();
        e.kind = kind;
        e.f = f == null ? new float[0] : f;
        e.i = i == null ? new int[0] : i;
        e.s = s == null ? "" : s;
        return apply(w, e);
    }

    /** Carries one event out. Returns what it made (a squad, a vehicle id...), or null. */
    public static Object apply(World w, Director.Event e) {
        float[] f = e.f;
        int[] i = e.i;
        switch (e.kind) {
            case PAINT: return Tools.paintZombies(w, f[0], f[1], i[0], f[2], i[1]);
            case HORDE: Tools.paintHorde(w, f[0], f[1], i[0]); return null;
            case SQUAD: {
                Content.Unit u = w.c.unit(e.s);
                // ordinary people aren't a squad: they go on the street one by one
                if (u != null && u.kind == Content.CIV) return Tools.placePeople(w, u, i[0], f[0], f[1]) > 0 ? Boolean.TRUE : null;
                return Tools.placeSquad(w, e.s, i[0], f[0], f[1]);
            }
            case VEHICLE: return Tools.placeVehicle(w, e.s, f[0], f[1]);
            case ERASE: return Tools.erase(w, f[0], f[1], f[2]);
            case STRIKE: return w.mil.callStrike(f[0], f[1], true);
            case BRIDGE: return w.strat.blowBridge(i[0], true);
            case ORDER: {
                Squads.Squad s = squad(w, i[0]);
                if (s != null) {
                    w.orders.cancel(s);
                    Tools.orderSquad(w, s, f[0], f[1]);
                }
                return null;
            }
            case RELEASE: Tools.releaseSquad(w, squad(w, i[0])); return null;
            case HOME: {
                Squads.Squad s = squad(w, i[0]);
                if (s != null) {
                    w.orders.cancel(s);
                    w.sq.goHome(s);
                    s.player = true;
                }
                return null;
            }
            case FORM_ORDER: {
                Forces.Formation fm = formation(w, i[0]);
                if (fm == null) return false;
                fm.player = true;
                fm.task = i.length > 1 ? i[1] : -1;
                return w.forces.order(fm, f[0], f[1]);
            }
            case FORM_RELEASE: {
                Forces.Formation fm = formation(w, i[0]);
                if (fm != null) {
                    fm.player = false;
                    fm.released = true;
                    fm.task = -1;
                    fm.job = Forces.JOB_NONE;
                }
                return null;
            }
            case CURFEW: w.gov.setCurfew(i[0] != 0); return null;
            case EVAC: w.gov.setEvac(i[0], i[1] != 0); return null;
            case LEVEL: w.gov.raiseTo(i[0]); return null;
            case QUARANTINE: {
                int st = i[0];
                if (st >= 0 && st < w.roles.quarantine.length) {
                    // one quarantine: the chain of command's (checkpoints, columns turned back)
                    w.roles.quarantine[st] = i[1] != 0;
                    w.command.setQuarantine(st, i[1] != 0);
                }
                return null;
            }
            case DEPLOY: {
                if (i[0] < 0 || i[0] >= w.r.facilities.size()) return null;
                Region.Facility fac = w.r.facilities.get(i[0]);
                Content.SquadT t = w.c.squadT(e.s);
                if (t == null || fac.pool == null) return null;
                Squads.Squad s = w.forces.deploy(fac, t);
                if (s != null) {
                    s.player = true;
                    if (f.length >= 2) Tools.orderSquad(w, s, f[0], f[1]);
                }
                return s;
            }
            case ARTILLERY: return w.forces.fireMission(f[0], f[1]);
            case REQUEST_ARMY: {
                if (i[0] < 0) return null;
                Region.Settlement st = w.r.settlements.get(i[0]);
                Forces.Formation best = null;
                float bd = Float.MAX_VALUE;
                for (Forces.Formation fm : w.forces.formations) {
                    if (!fm.alive || fm.faction != Ents.SOLDIER || fm.task == st.id || fm.state == Forces.BROKEN) continue;
                    float dx = fm.hq.x - st.cx * 16f, dy = fm.hq.y - st.cy * 16f, d = dx * dx + dy * dy;
                    if (d < bd) {
                        bd = d;
                        best = fm;
                    }
                }
                if (best == null) {
                    w.news.radio("Command: no formation is free to send to " + st.name, st.cx * 16f, st.cy * 16f, News.COMMAND);
                    return null;
                }
                float[] p = w.forces.infectionCentre(st);
                if (w.forces.order(best, p[0], p[1])) {
                    best.task = st.id;
                    w.news.radio("Command: " + best.name + " sent to " + st.name + " at your request", p[0], p[1], News.COMMAND);
                }
                return best;
            }
            case CAMERA:
                w.camX0 = f[0];
                w.camY0 = f[1];
                w.camX1 = f[2];
                w.camY1 = f[3];
                w.camDetail = i[0] != 0;
                return null;
            case STRATEGIC: w.setStrategic(i[0] != 0); return w.strategic;
            case JOY:
                w.strat.playerJoyX = f[0];
                w.strat.playerJoyY = f[1];
                return null;
            case FIRE: w.strat.playerFire = i[0] != 0; return null;
            case MAX_IND: w.maxIndividuals = i[0]; return null;
            case ADD_PLAYER: return w.roles.addPlayer(i[0], f[0], f[1]);
            case SWITCH_PLAYER: w.roles.switchTo(i[0]); return null;
            case WATCH:
                // the game goes on as a sandbox with no goal (a campaign played as God too)
                w.watching = true;
                w.ended = false;
                w.mode = World.MODE_SANDBOX;
                return null;
            case GENERAL:
                w.forces.setGeneralCommands(i[0] != 0);
                return null;
            case PRECINCT_CORDON: w.roles.setCordon(i[0] != 0); return null;
            case PRECINCT_EVAC: w.roles.setDistrictEvac(i[0] != 0); return null;
            case DEFENCE: return Tools.placeDefence(w, i[0], f[0], f[1]) != null;
            case BUILD: return Tools.build(w, i[0], f[0], f[1]);
            case VEH_ORDER: {
                int v = i[0];
                if (v < 0 || v >= w.veh.top || w.veh.uid[v] != i[1]) return false;
                return w.orders.orderVehicle(v, i[2], f[0], f[1]);
            }
            case DRIVE: {
                int v = i[0];
                if (v >= 0 && (v >= w.veh.top || w.veh.uid[v] != i[1])) return false;
                w.orders.drive(v);
                return v < 0 || w.orders.driven == v;
            }
            case POSSESS: return w.orders.possess(i[0], i[1]);
            case STANCE: {
                Squads.Squad s = squad(w, i[0]);
                if (s == null) return null;
                Orders.SqOrder o = w.orders.of(s);
                if (o != null && o.kind == Orders.SO_FALLBACK) o.prevStance = i[1];
                else s.stance = i[1];
                s.player = true;
                return null;
            }
            case SQ_ORDER: {
                Squads.Squad s = squad(w, i[0]);
                if (s == null) return false;
                switch (i[1]) {
                    case Orders.SO_ROUTE: w.orders.route(s, f); break;
                    case Orders.SO_FALLBACK: w.orders.fallBack(s, f[0], f[1]); break;
                    case Orders.SO_CORDON: w.orders.cordon(s, f[0], f[1], f[2], f[3]); break;
                    case Orders.SO_CLEAR: w.orders.clearArea(s, f[0], f[1], f[2]); break;
                    case Orders.SO_ESCORT: {
                        boolean veh = i[2] != 0;
                        int t = i[3];
                        if (veh ? t < 0 || t >= w.veh.top || w.veh.uid[t] != i[4] : t < 0 || t >= w.e.top || w.e.uid[t] != i[4]) return false;
                        w.orders.escort(s, t, veh);
                        break;
                    }
                    default: return false;
                }
                return true;
            }
            case MOUNT: {
                Squads.Squad s = squad(w, i[0]);
                if (s == null) return false;
                s.player = true;
                if (i[1] != 0) return w.sq.mount(s);
                w.sq.dismountAll(s);
                return true;
            }
            case POSTURE: return w.orders.setPosture(formation(w, i[0]), i[1], f[0], f[1]);
            case RULE: w.rules.set(i[0], i[1]); return null;
            case CLEAR: return Tools.clear(w, i[0], i.length > 1 ? i[1] : -1);
            case TEMPLATE: return Tools.placeTemplate(w, e.s, f[0], f[1]);
            case MASS: return Tools.paintMass(w, f[0], f[1], i[0], i[1]);
            case SURV: return i.length > 0 ? w.surv.act(i[0], f, i) : Boolean.FALSE;
            default:
                return null;
        }
    }

    private static Squads.Squad squad(World w, int id) {
        if (id < 0 || id >= w.sq.list.size()) return null;
        Squads.Squad s = w.sq.get(id);
        return s.alive ? s : null;
    }

    private static Forces.Formation formation(World w, int id) {
        if (id < 0 || id >= w.forces.formations.size()) return null;
        Forces.Formation f = w.forces.formations.get(id);
        return f.alive ? f : null;
    }

    // ------------------------------------------------------------------ the player's verbs

    public static int paint(World w, float x, float y, int count, float radius, int ztype) {
        return (Integer) exec(w, PAINT, new float[]{x, y, radius}, new int[]{count, ztype}, null);
    }

    public static void horde(World w, float x, float y, int count) {
        exec(w, HORDE, new float[]{x, y}, new int[]{count}, null);
    }

    public static Squads.Squad squad(World w, String unit, int size, float x, float y) {
        return (Squads.Squad) exec(w, SQUAD, new float[]{x, y}, new int[]{size}, unit);
    }

    public static int vehicle(World w, String type, float x, float y) {
        return (Integer) exec(w, VEHICLE, new float[]{x, y}, null, type);
    }

    public static int erase(World w, float x, float y, float r) {
        return (Integer) exec(w, ERASE, new float[]{x, y, r}, null, null);
    }

    public static boolean strike(World w, float x, float y) {
        return (Boolean) exec(w, STRIKE, new float[]{x, y}, null, null);
    }

    public static boolean bridge(World w, int edge) {
        return (Boolean) exec(w, BRIDGE, null, new int[]{edge}, null);
    }

    public static void order(World w, Squads.Squad s, float x, float y) {
        exec(w, ORDER, new float[]{x, y}, new int[]{s.id}, null);
    }

    public static void release(World w, Squads.Squad s) {
        if (s != null) exec(w, RELEASE, null, new int[]{s.id}, null);
    }

    public static void home(World w, Squads.Squad s) {
        if (s != null) exec(w, HOME, null, new int[]{s.id}, null);
    }

    public static boolean formation(World w, Forces.Formation f, float x, float y) {
        return (Boolean) exec(w, FORM_ORDER, new float[]{x, y}, new int[]{f.id, -1}, null);
    }

    public static void releaseFormation(World w, Forces.Formation f) {
        if (f != null) exec(w, FORM_RELEASE, null, new int[]{f.id}, null);
    }

    public static boolean addPlayer(World w, int role, float x, float y) {
        Object r = exec(w, ADD_PLAYER, new float[]{x, y}, new int[]{role}, null);
        return r instanceof Boolean && (Boolean) r;
    }

    public static void switchPlayer(World w, int k) {
        exec(w, SWITCH_PLAYER, null, new int[]{k}, null);
    }

    public static void curfew(World w, boolean on) {
        exec(w, CURFEW, null, new int[]{on ? 1 : 0}, null);
    }

    public static void evac(World w, int settlement, boolean on) {
        exec(w, EVAC, null, new int[]{settlement, on ? 1 : 0}, null);
    }

    public static void level(World w, int level) {
        exec(w, LEVEL, null, new int[]{level}, null);
    }

    public static void quarantine(World w, int settlement, boolean on) {
        exec(w, QUARANTINE, null, new int[]{settlement, on ? 1 : 0}, null);
    }

    public static Squads.Squad deploy(World w, int facility, String template, float x, float y) {
        return (Squads.Squad) exec(w, DEPLOY, new float[]{x, y}, new int[]{facility}, template);
    }

    public static boolean artillery(World w, float x, float y) {
        return (Boolean) exec(w, ARTILLERY, new float[]{x, y}, null, null);
    }

    public static Forces.Formation requestArmy(World w, int settlement) {
        return (Forces.Formation) exec(w, REQUEST_ARMY, null, new int[]{settlement}, null);
    }

    public static boolean strategic(World w, boolean on) {
        return (Boolean) exec(w, STRATEGIC, null, new int[]{on ? 1 : 0}, null);
    }

    public static void joy(World w, float x, float y) {
        // quantised, so a held stick isn't a stream of events
        float qx = Math.round(x * 20) / 20f, qy = Math.round(y * 20) / 20f;
        if (qx == w.strat.playerJoyX && qy == w.strat.playerJoyY) return;
        exec(w, JOY, new float[]{qx, qy}, null, null);
    }

    public static void fire(World w, boolean on) {
        exec(w, FIRE, null, new int[]{on ? 1 : 0}, null);
    }

    public static void maxIndividuals(World w, int n) {
        if (w.maxIndividuals != n) exec(w, MAX_IND, null, new int[]{n}, null);
    }

    /** The President or Military commander hands the whole army to the AI general (or takes it back). */
    /** The precinct commander puts a police cordon round the edge of their district (or lifts it). */
    public static void precinctCordon(World w, boolean on) {
        exec(w, PRECINCT_CORDON, null, new int[]{on ? 1 : 0}, null);
    }

    /** The precinct commander evacuates their district (or stops). */
    public static void precinctEvac(World w, boolean on) {
        exec(w, PRECINCT_EVAC, null, new int[]{on ? 1 : 0}, null);
    }

    /** A defence (Defences.*) at a place: sandbags, wire, a machine-gun nest, a crate, a medkit, a command post. */
    public static boolean defence(World w, int kind, float x, float y) {
        return (Boolean) exec(w, DEFENCE, new float[]{x, y}, new int[]{kind}, null);
    }

    /** Builds (Tools.B_*) at a place; returns the tiles changed. */
    public static int build(World w, int what, float x, float y) {
        return (Integer) exec(w, BUILD, new float[]{x, y}, new int[]{what}, null);
    }

    /** A vehicle sent somewhere (Orders.VO_*). */
    public static boolean vehicleOrder(World w, int v, int kind, float x, float y) {
        return (Boolean) exec(w, VEH_ORDER, new float[]{x, y}, new int[]{v, w.veh.uid[v], kind}, null);
    }

    /** Takes the wheel of vehicle v (v = -1: lets go). */
    public static boolean drive(World w, int v) {
        return (Boolean) exec(w, DRIVE, null, new int[]{v, v >= 0 ? w.veh.uid[v] : 0}, null);
    }

    /** God controls person ent (ent = -1: lets go). */
    public static boolean possess(World w, int ent) {
        return (Boolean) exec(w, POSSESS, null, new int[]{ent, ent >= 0 ? w.e.uid[ent] : 0}, null);
    }

    public static void stance(World w, Squads.Squad s, int stance) {
        if (s != null) exec(w, STANCE, null, new int[]{s.id, stance}, null);
    }

    /** A route of waypoints (x, y pairs). */
    public static boolean route(World w, Squads.Squad s, float[] pts) {
        return (Boolean) exec(w, SQ_ORDER, pts.clone(), new int[]{s.id, Orders.SO_ROUTE}, null);
    }

    public static boolean fallBack(World w, Squads.Squad s, float x, float y) {
        return (Boolean) exec(w, SQ_ORDER, new float[]{x, y}, new int[]{s.id, Orders.SO_FALLBACK}, null);
    }

    public static boolean cordon(World w, Squads.Squad s, float ax, float ay, float bx, float by) {
        return (Boolean) exec(w, SQ_ORDER, new float[]{ax, ay, bx, by}, new int[]{s.id, Orders.SO_CORDON}, null);
    }

    public static boolean clearArea(World w, Squads.Squad s, float x, float y, float r) {
        return (Boolean) exec(w, SQ_ORDER, new float[]{x, y, r}, new int[]{s.id, Orders.SO_CLEAR}, null);
    }

    /** Escort a person (vehicle false) or a vehicle. */
    public static boolean escort(World w, Squads.Squad s, int target, boolean vehicle) {
        int uid = vehicle ? w.veh.uid[target] : w.e.uid[target];
        return (Boolean) exec(w, SQ_ORDER, null, new int[]{s.id, Orders.SO_ESCORT, vehicle ? 1 : 0, target, uid}, null);
    }

    public static boolean mount(World w, Squads.Squad s, boolean on) {
        return (Boolean) exec(w, MOUNT, null, new int[]{s.id, on ? 1 : 0}, null);
    }

    /** A formation's posture (Orders.FP_*), towards (x, y) where it moves. */
    public static boolean posture(World w, Forces.Formation f, int posture, float x, float y) {
        return (Boolean) exec(w, POSTURE, new float[]{x, y}, new int[]{f.id, posture}, null);
    }

    /** One line of the rules sheet (Rules.*). */
    public static void rule(World w, int key, int value) {
        if (w.rules.v[key] != value) exec(w, RULE, null, new int[]{key, value}, null);
    }

    /** Clears (Tools.CLEAR_*); settlement for a town's dead. Returns how many went. */
    public static int clear(World w, int what, int settlement) {
        return (Integer) exec(w, CLEAR, null, new int[]{what, settlement}, null);
    }

    /** A squad from content.ini's list, with its vehicle. */
    public static Squads.Squad template(World w, String id, float x, float y) {
        return (Squads.Squad) exec(w, TEMPLATE, new float[]{x, y}, null, id);
    }

    /** Ordinary people on the street; returns whether any could stand there. */
    public static boolean people(World w, String unit, int n, float x, float y) {
        return exec(w, SQUAD, new float[]{x, y}, new int[]{n}, unit) != null;
    }

    /** Tens of thousands of the dead: returns how many went down as individuals (the rest into the counts). */
    public static int mass(World w, float x, float y, int count, int ztype) {
        return (Integer) exec(w, MASS, new float[]{x, y}, new int[]{count, ztype}, null);
    }

    /** One of the survivor's verbs (Survival.ENTER...), with its argument and a second one (a target's uid). */
    public static boolean survival(World w, int op, int arg, int arg2) {
        Object r = exec(w, SURV, null, new int[]{op, arg, arg2}, null);
        return r instanceof Boolean && (Boolean) r;
    }

    public static boolean survival(World w, int op) {
        return survival(w, op, 0, 0);
    }

    public static void generalCommands(World w, boolean on) {
        exec(w, GENERAL, null, new int[]{on ? 1 : 0}, null);
    }

    public static void watch(World w) {
        exec(w, WATCH, null, null, null);
    }

    // ------------------------------------------------------------------ replays

    /** Puts events in tick order (stable: same-tick events keep the order they happened in). */
    public static void sort(List<Director.Event> events) {
        java.util.Collections.sort(events, new Comparator<Director.Event>() {
            public int compare(Director.Event a, Director.Event b) {
                return a.tick < b.tick ? -1 : a.tick > b.tick ? 1 : 0;
            }
        });
    }
}
