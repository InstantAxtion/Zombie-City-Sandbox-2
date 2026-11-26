package com.instantaxtion.zcs2;

import java.io.FileInputStream;

/**
 * A scripted survivor for tests and probes: it plays only through Actions (the stick and the survival verbs), so
 * whatever it does is recorded and replays exactly. Competent: it keeps fed and watered, searches buildings,
 * fights off what comes close, hides and barricades when too many come, takes a car when it finds one and heads for
 * the nearest safe place. Idle: it stands where it started.
 *
 *   java -cp build/test com.instantaxtion.zcs2.SurvivalBot [seeds] [size] [days]
 * plays each seed both ways and prints how often each reached safety and lived.
 */
public final class SurvivalBot {
    private final World w;
    private float[] path;
    private int pathAt;
    private long planned = -1000;
    private float goalX = -1, goalY = -1;
    private int stuck;
    private float lastX, lastY;
    public String log = "";
    static final int DEBUG_EVERY = Integer.parseInt(System.getenv().containsKey("BOTEVERY") ? System.getenv("BOTEVERY") : "120");
    /** BOTNOCAR: the competent survivor walks (no cars), to see the rest of it at work. */
    static final boolean NOCAR = System.getenv("BOTNOCAR") != null;
    static final boolean DEBUG = System.getenv("BOTDEBUG") != null;

    public SurvivalBot(World w) {
        this.w = w;
    }

    /** Steers the stick towards (x, y) along a path round walls; true once within reach. */
    public boolean toward(float x, float y, float within) {
        Ents e = w.e;
        int p = w.playerEnt;
        float px = w.playerX(), py = w.playerY();
        float dx = x - px, dy = y - py;
        if (dx * dx + dy * dy < within * within) {
            Actions.joy(w, 0, 0);
            return true;
        }
        if (path == null || goalX != x || goalY != y || w.tick - planned > 40 || pathAt >= path.length) {
            path = w.nav.find(px, py, x, y, 30000);
            pathAt = 0;
            planned = w.tick;
            goalX = x;
            goalY = y;
        }
        float tx = x, ty = y;
        if (path != null) {
            while (pathAt + 2 < path.length && Math.abs(path[pathAt] - px) + Math.abs(path[pathAt + 1] - py) < 14) pathAt += 2;
            tx = path[pathAt];
            ty = path[pathAt + 1];
        }
        float ddx = tx - px, ddy = ty - py, d = (float) Math.sqrt(ddx * ddx + ddy * ddy) + 0.01f;
        // a little sideways when stuck on a corner
        if (Math.abs(px - lastX) + Math.abs(py - lastY) < 0.3f) stuck++;
        else stuck = 0;
        lastX = px;
        lastY = py;
        float sx = ddx / d, sy = ddy / d;
        if (stuck > 10) {
            float a = (stuck / 10 % 2 == 0 ? 1 : -1) * 1.2f;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            float nx = sx * c - sy * s, ny = sx * s + sy * c;
            sx = nx;
            sy = ny;
            if (stuck > 60) {
                path = null;
                stuck = 0;
            }
        }
        float mag = e.loc[p] == Ents.ABOARD ? 1 : w.surv.stamina > 0.35f ? 1 : 0.55f;
        Actions.joy(w, sx * mag, sy * mag);
        return false;
    }

    /** One step of the competent survivor (call before each w.step()). */
    public void competent() {
        if (!w.playerAlive() || w.ended) return;
        Survival s = w.surv;
        Ents e = w.e;
        int p = w.playerEnt;
        boolean second = w.tick % 20 == 0;
        // needs first, wherever we are
        if (second) {
            if (s.food < 0.45f && s.items[Survival.I_FOOD] > 0) Actions.survival(w, Survival.USE, Survival.I_FOOD, 0);
            if (s.water < 0.45f && s.items[Survival.I_WATER] > 0) Actions.survival(w, Survival.USE, Survival.I_WATER, 0);
            if (e.hp[p] < e.maxHp[p] * 0.5f && s.items[Survival.I_MEDS] > 0) Actions.survival(w, Survival.USE, Survival.I_MEDS, 0);
            if (s.gun >= 0 && s.rounds(s.gun) == 0)
                for (int g = Survival.W_PISTOL; g < Survival.NW; g++) if (s.owns(g) && s.ammo[g] > 0) Actions.survival(w, Survival.EQUIP, g, 0);
            if (s.melee == Survival.W_HANDS)
                for (int g = Survival.W_CROWBAR; g >= Survival.W_BAT; g--) if (s.owns(g)) Actions.survival(w, Survival.EQUIP, g, 0);
        }
        byte l = e.loc[p];
        if (l == Ents.INSIDE) {
            if (!second) return;
            if (s.can(Survival.SEARCH)) {
                Actions.survival(w, Survival.SEARCH);
                return;
            }
            int danger = w.sp.zombiesNear(e, s.doorPX, s.doorPY, 160, 8);
            if (!s.barricaded() && danger > 0) Actions.survival(w, Survival.BARRICADE);
            if (s.rest < 0.35f && !s.resting && danger == 0) Actions.survival(w, Survival.REST, 1, 0);
            if (s.resting && s.rest < 0.95f) return;
            if (danger <= 1 && (s.resting ? s.rest >= 0.95f : true)) {
                if (s.resting) Actions.survival(w, Survival.REST, 0, 0);
                Actions.survival(w, Survival.LEAVE);
            }
            return;
        }
        float px = w.playerX(), py = w.playerY();
        if (l == Ents.ABOARD) {
            // drive on to safety; out when the tank is dry or the car is mobbed
            int v = e.vehicle[p];
            if (second && (w.veh.fuel[v] <= 0.01f || w.veh.hp[v] < w.veh.type(v).hp * 0.3f)) {
                if (w.veh.fuel[v] <= 0.01f && s.items[Survival.I_FUEL] > 0) Actions.survival(w, Survival.USE, Survival.I_FUEL, 0);
                else Actions.survival(w, Survival.CAR);
                return;
            }
            if (s.safeD >= 0) drive(v, s.safeX, s.safeY);
            return;
        }
        // the dead close: fight, or hide when there are too many
        int close = w.sp.zombiesNear(e, px, py, 22, 6);
        int around = w.sp.zombiesNear(e, px, py, 110, 12);
        if (close > 0 && s.meleeT <= 0) {
            Actions.survival(w, Survival.MELEE, close >= 2 || s.melee == Survival.W_HANDS ? 0 : 1, 0);
            if (close >= 2 && s.can(Survival.ENTER)) Actions.survival(w, Survival.ENTER);
        }
        boolean outgunned = around >= 6 || around >= 3 && (s.gun < 0 || s.rounds(s.gun) == 0);
        if (outgunned && s.can(Survival.ENTER)) {
            Actions.survival(w, Survival.ENTER);
            if (w.e.loc[p] == Ents.INSIDE) Actions.survival(w, Survival.BARRICADE);
            return;
        }
        if (second && s.can(Survival.PICKUP)) Actions.survival(w, Survival.PICKUP);
        if (second && !NOCAR && s.can(Survival.CAR) && around < 4) {
            int v = s.carNear(px, py);
            if (v >= 0) {
                Actions.survival(w, Survival.CAR);
                return;
            }
        }
        // short of food or water: the next building on the way
        boolean short_ = s.items[Survival.I_FOOD] + s.items[Survival.I_WATER] < 2 && (s.food < 0.6f || s.water < 0.6f);
        if (second && short_ && around == 0 && s.can(Survival.ENTER)) {
            int b = s.buildingAt(px, py);
            if (b >= 0 && !s.searched.contains(b)) {
                Actions.survival(w, Survival.ENTER);
                return;
            }
        }
        // a car close by: get to it
        int v = s.carNear(px, py);
        if (v < 0) v = carWithin(px, py, 260);
        if (v >= 0 && around < 3 && !NOCAR) {
            toward(w.veh.x[v], w.veh.y[v], 20);
            return;
        }
        if (short_ && around == 0) {
            int b = unsearchedNear(px, py, 220);
            if (b >= 0) {
                toward(w.r.doorX(b), w.r.doorY(b), 14);
                return;
            }
        }
        // away from what's around, else on to safety
        if (around >= 3) {
            int z = w.sp.nearestZombie(e, px, py, 110);
            if (z >= 0 && (s.gun < 0 || s.rounds(s.gun) == 0)) {
                float dx = px - e.x[z], dy = py - e.y[z], d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                Actions.joy(w, dx / d, dy / d);
                return;
            }
        }
        if (s.safeD >= 0) toward(s.safeX, s.safeY, 60);
        else Actions.joy(w, 0, 0);
    }

    private int[] route;
    private int routeAt, slowT, backT;
    private long routed = -1000;

    /** At the wheel: along the roads to (x, y), backing off whatever it runs into. */
    private void drive(int v, float x, float y) {
        Vehicles vh = w.veh;
        RoadGraph g = w.r.roads;
        float cx = vh.x[v], cy = vh.y[v];
        if (backT > 0) {
            backT--;
            float h = vh.head[v];
            Actions.joy(w, -(float) Math.cos(h + 0.6f), -(float) Math.sin(h + 0.6f));
            return;
        }
        if (Math.abs(vh.spd[v]) < 6) slowT++;
        else slowT = 0;
        if (slowT > 30) {
            slowT = 0;
            backT = 30;
            return;
        }
        boolean off = route != null && routeAt < route.length && Math.abs(g.nx[route[routeAt]] - cx) + Math.abs(g.ny[route[routeAt]] - cy) > 260;
        if (route == null || off || w.tick - routed > 600) {
            int a = g.nearestNode(cx, cy, 800), b = g.nearestNode(x, y, 1500);
            route = a >= 0 && b >= 0 ? g.route(a, b) : null;
            routeAt = 0;
            routed = w.tick;
            // the second junction, when the first is behind us
            if (route != null && route.length > 1) {
                float h = vh.head[v];
                float ax = g.nx[route[0]] - cx, ay = g.ny[route[0]] - cy;
                if (ax * (float) Math.cos(h) + ay * (float) Math.sin(h) < 0) routeAt = 1;
            }
        }
        float tx = x, ty = y;
        if (route != null) {
            while (routeAt < route.length - 1 && Math.abs(g.nx[route[routeAt]] - cx) + Math.abs(g.ny[route[routeAt]] - cy) < 40) routeAt++;
            if (routeAt < route.length) {
                tx = g.nx[route[routeAt]];
                ty = g.ny[route[routeAt]];
            }
        }
        float dx = tx - cx, dy = ty - cy, d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        // slow for a sharp turn (a car turns tighter slowly), and coming up to a junction
        float err = (float) Math.atan2(dy, dx) - vh.head[v];
        while (err > Math.PI) err -= 2 * Math.PI;
        while (err < -Math.PI) err += 2 * Math.PI;
        float slow = Math.abs(err) > 0.5f ? 0.35f : d < 60 ? 0.6f : 1;
        Actions.joy(w, dx / d * slow, dy / d * slow);
    }

    private int carWithin(float x, float y, float r) {
        Vehicles vh = w.veh;
        int best = -1;
        float bd = r * r;
        for (int v = 0; v < vh.top; v++) {
            if (!vh.usable(v) || vh.crewN[v] > 0 || vh.type(v).flies || vh.type(v).seats < 1 || vh.state[v] == Vehicles.V_DRIVE) continue;
            if (vh.role[v] != Vehicles.R_LIFE && !(vh.role[v] == Vehicles.R_SQUAD && vh.squad[v] < 0) && vh.role[v] != Vehicles.R_TRANSPORT) continue;
            if (vh.role[v] == Vehicles.R_TRANSPORT && vh.fuel[v] <= 0.02f) continue;
            float dx = vh.x[v] - x, dy = vh.y[v] - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = v;
            }
        }
        return best;
    }

    private int unsearchedNear(float x, float y, float r) {
        Region rg = w.r;
        int s0 = rg.sectorOfPx(x, y);
        int best = -1;
        float bd = r * r;
        int sw = rg.secW, sh = rg.secH;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int qx = s0 % sw + dx, qy = s0 / sw + dy;
                if (qx < 0 || qy < 0 || qx >= sw || qy >= sh) continue;
                for (int b : w.secBuildings[qy * sw + qx]) {
                    if (w.surv.searched.contains(b)) continue;
                    float ddx = rg.doorX(b) - x, ddy = rg.doorY(b) - y, d = ddx * ddx + ddy * ddy;
                    if (d < bd) {
                        bd = d;
                        best = b;
                    }
                }
            }
        return best;
    }

    /** The camera on the player, as the game keeps it (what's in view is simulated person by person). */
    public static void camOn(World w) {
        if (w.playerEnt < 0) return;
        float x = w.playerX(), y = w.playerY();
        w.camX0 = x - 500;
        w.camX1 = x + 500;
        w.camY0 = y - 900;
        w.camY1 = y + 900;
        w.camDetail = true;
    }

    /** Plays one game: true = competent; returns {reached safety, alive at the end, seconds lived, searches, kills}. */
    public static float[] play(Content c, long seed, int size, float days, boolean competent) {
        World w = Setup.newGame(c, size, RegionGen.COAST, seed, c.scenario("survivor"), 2, Roles.SURVIVOR, 60000);
        SurvivalBot b = new SurvivalBot(w);
        int steps = (int) (days * 1440 * 20);
        boolean reached = false;
        for (int k = 0; k < steps && !w.ended; k++) {
            if (k % 20 == 0) camOn(w);
            if (competent) b.competent();
            w.step();
            if (DEBUG && k % (DEBUG_EVERY * 20) == 0 && w.playerAlive()) {
                Survival s = w.surv;
                System.out.printf("  t=%.0f loc=%d at %.0f,%.0f hp=%.0f safe %s %.0f px  food %.2f water %.2f rest %.2f st %.2f items %s gun %d/%d comp %d car %d spd %.1f fuel %.2f joy %.2f,%.2f driven %d%n", w.time,
                        w.e.loc[w.playerEnt], w.playerX(), w.playerY(), w.e.hp[w.playerEnt], s.safeKind >= 0 ? Strategic.SAFE_NAMES[s.safeKind] : "-", s.safeD,
                        s.food, s.water, s.rest, s.stamina, java.util.Arrays.toString(s.items), s.gun, s.rounds(s.gun), s.compN, s.car, s.car >= 0 ? w.veh.spd[s.car] : 0f, s.car >= 0 ? w.veh.fuel[s.car] : 0f, w.strat.playerJoyX, w.strat.playerJoyY, w.orders.driven);
            }
        }
        reached = w.ended && w.won && w.endTitle != null && w.endTitle.contains("safety");
        boolean alive = w.playerAlive();
        float[] out = {reached ? 1 : 0, alive ? 1 : 0, (float) w.time, w.surv.searches, w.surv.meleeKills};
        w.shutdown();
        return out;
    }

    public static void main(String[] args) throws Exception {
        Content c = Content.load(new FileInputStream("assets/content.ini"));
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 768;
        float days = args.length > 2 ? Float.parseFloat(args[2]) : 1;
        int[] reached = new int[2], alive = new int[2];
        float[] lived = new float[2];
        for (long seed = 1; seed <= seeds; seed++)
            for (int m = 0; m < 2; m++) {
                float[] r = play(c, seed, size, days, m == 1);
                reached[m] += (int) r[0];
                alive[m] += (int) r[1];
                lived[m] += r[2];
                System.out.printf("seed %d %-9s reached %d alive %d  %.0f s  searches %.0f  melee kills %.0f%n", seed, m == 1 ? "competent" : "idle",
                        (int) r[0], (int) r[1], r[2], r[3], r[4]);
            }
        for (int m = 0; m < 2; m++)
            System.out.printf("%-9s reached safety %d/%d, alive or safe %d/%d, mean %.0f game s%n", m == 1 ? "competent" : "idle", reached[m], seeds,
                    alive[m], seeds, lived[m] / seeds);
    }
}
