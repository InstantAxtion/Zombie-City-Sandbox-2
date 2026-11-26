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
            WATCH = 25, ADD_PLAYER = 26, SWITCH_PLAYER = 27;

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
            case SQUAD: return Tools.placeSquad(w, e.s, i[0], f[0], f[1]);
            case VEHICLE: return Tools.placeVehicle(w, e.s, f[0], f[1]);
            case ERASE: return Tools.erase(w, f[0], f[1], f[2]);
            case STRIKE: return w.mil.callStrike(f[0], f[1], true);
            case BRIDGE: return w.strat.blowBridge(i[0], true);
            case ORDER: {
                Squads.Squad s = squad(w, i[0]);
                if (s != null) Tools.orderSquad(w, s, f[0], f[1]);
                return null;
            }
            case RELEASE: Tools.releaseSquad(w, squad(w, i[0])); return null;
            case HOME: {
                Squads.Squad s = squad(w, i[0]);
                if (s != null) {
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
                    fm.task = -1;
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
                if (w.mode != World.MODE_SANDBOX) {
                    w.mode = World.MODE_SANDBOX;
                    w.ended = false;
                }
                return null;
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
