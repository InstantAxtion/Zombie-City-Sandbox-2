package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Goods on the move. Ammunition, fuel, food and medicine travel as convoys: trucks on the roads, trains on the
 * railways, cargo planes to the air base and the airport, ships to the naval station. Stations, formations,
 * hospitals, food stores and fuel depots ask when they run low; the nearest depot (a base, the port, another city's
 * food store) sends what it can, and the depots themselves are refilled from outside the country. The dead on the
 * road eat into a convoy or destroy it; a blown bridge sends it round. Trains also carry refugees out, and a bitten
 * passenger aboard carries the infection to wherever the train stops.
 */
public final class Logistics extends Module {
    public static final int TRUCK = 0, TRAIN = 1, PLANE = 2, SHIP = 3;
    public static final String[] MODES = {"Supply convoy", "Freight train", "Cargo plane", "Supply ship"};
    public static final int G_AMMO = 1, G_FUEL = 2, G_FOOD = 4, G_MED = 8;
    public static final int OUTSIDE = -1;

    public static final class Convoy {
        public int id, mode, from = OUTSIDE, to = -1, toSettlement = -1, ri, refugees, infected, goods;
        public float ammo, fuel, food, med, x, y, tx, ty;
        public int[] route;
        public boolean alive = true, back;
        /** A trade caravan from a survivor settlement (its index), or -1. */
        public int colony = -1;

        public int size() {
            if (refugees > 0) return Math.max(3, Math.min(14, refugees / 150));
            int n = (int) (ammo / 5000 + fuel / 25 + food / 25000 + med / 600) + 1;
            return Math.max(1, Math.min(12, n));
        }
    }

    public final List<Convoy> convoys = new ArrayList<Convoy>();
    private int[] onWay = new int[0];
    private float requestT, trainT, importT;
    private int nextId;
    public int lost, delivered;
    /** Convoys sent, by mode (trucks, trains, planes, ships). */
    public final int[] sent = new int[4];

    public Logistics(World w) {
        super(w);
    }

    @Override
    public String id() {
        return "logistics";
    }

    private void grow() {
        int nf = w.r.facilities.size();
        if (onWay.length < nf) onWay = java.util.Arrays.copyOf(onWay, nf);
    }

    // ------------------------------------------------------------------ every second

    @Override
    public void update(float dt) {
        grow();
        requestT -= dt;
        if (requestT <= 0) {
            requestT = 30;
            requests();
        }
        importT -= dt;
        if (importT <= 0) {
            importT = w.c.rule("importEvery", 360);
            imports();
        }
        trainT -= dt;
        if (trainT <= 0) {
            trainT = 60;
            refugeeTrains();
        }
        for (int k = 0; k < convoys.size(); k++) {
            Convoy c = convoys.get(k);
            if (c.alive) move(c, dt);
        }
        for (int k = convoys.size() - 1; k >= 0; k--) if (!convoys.get(k).alive) convoys.remove(k);
    }

    // ------------------------------------------------------------------ imports

    /**
     * The country's lifelines from abroad, on a schedule: ships into the port with food and fuel, cargo planes into
     * the airfields with medicine and ammunition, topping them up to what they can hold. The ports and airfields
     * then supply the cities (by train where there's a railway).
     */
    private void imports() {
        for (Region.Facility f : w.r.facilities) {
            if (!w.strat.online(f.id)) continue;
            if (f.kind == Region.F_PORT) {
                float food = w.c.rule("portFood", 400000), fuel = w.c.rule("portFuel", 800);
                if (f.food < food * 0.95f) send(OUTSIDE, f, G_FOOD, food - f.food, SHIP);
                if (f.fuel < fuel * 0.95f) send(OUTSIDE, f, G_FUEL, fuel - f.fuel, SHIP);
            } else if (f.kind == Region.F_AIRPORT || f.kind == Region.F_AIRBASE) {
                float med = w.c.rule("airMed", 3000), ammo = f.kind == Region.F_AIRBASE ? w.c.rule("airAmmo", 60000) : 0;
                float food = f.kind == Region.F_AIRPORT ? w.c.rule("airFood", 100000) : 0;
                if (f.med < med * 0.95f) send(OUTSIDE, f, G_MED, med - f.med, PLANE);
                if (ammo > 0 && f.ammo < ammo * 0.95f) send(OUTSIDE, f, G_AMMO, ammo - f.ammo, PLANE);
                if (food > 0 && f.food < food * 0.95f) send(OUTSIDE, f, G_FOOD, Math.min(30000, food - f.food), PLANE);
            }
        }
    }

    // ------------------------------------------------------------------ who needs what

    private void requests() {
        Region r = w.r;
        Infrastructure inf = w.infra;
        for (Region.Facility f : r.facilities) {
            if (!w.strat.online(f.id)) continue;
            switch (f.kind) {
                case Region.F_POLICE:
                case Region.F_ARMORY:
                case Region.F_PRISON: {
                    int armed = w.forces.armedAt(f, Ents.COP) + w.forces.armedAt(f, Ents.SOLDIER) + w.sq.count(Ents.COP) / 200;
                    float want = Math.max(800, armed * 150f);
                    if (f.ammo < want * 0.4f) request(f, G_AMMO, want - f.ammo);
                    break;
                }
                case Region.F_HQ: {
                    Forces.Formation fm = w.forces.formationOf(f);
                    if (fm == null || !fm.alive) break;
                    float want = Math.max(4000, fm.start * 120f);
                    if (f.ammo < want * 0.4f) request(f, G_AMMO, want - f.ammo);
                    if (f.fuel < 40) request(f, G_FUEL, 100 - f.fuel);
                    if (f.med < 200) request(f, G_MED, 600 - f.med);
                    break;
                }
                case Region.F_HOSPITAL:
                    if (f.med < 300) request(f, G_MED, 1500 - f.med);
                    break;
                case Region.F_FUEL:
                    if (f.fuel < 100) request(f, G_FUEL, 300 - f.fuel);
                    break;
                case Region.F_BASE:
                case Region.F_AIRBASE:
                case Region.F_PORT:
                case Region.F_AIRPORT:
                    // the depots: refilled from outside the country
                    if (f.ammo < 15000 && f.kind != Region.F_AIRPORT) request(f, G_AMMO, 40000 - f.ammo);
                    if (f.fuel < 150) request(f, G_FUEL, 500 - f.fuel);
                    if (f.med < 500) request(f, G_MED, 2000 - f.med);
                    if ((f.kind == Region.F_PORT || f.kind == Region.F_AIRPORT) && f.food < 80000) request(f, G_FOOD, 250000 - f.food);
                    break;
                default:
            }
        }
        // each city and town's food
        for (Region.Settlement s : r.settlements) {
            int st = inf.store[s.id];
            if (st < 0 || !w.strat.online(st)) continue;
            Region.Facility f = r.facilities.get(st);
            int pop = w.stats.popBySettlement[s.id];
            if (pop < 50) continue;
            float low = w.c.rule("foodRequestDays", 4), full = w.c.rule("foodFullDays", 7);
            if (f.food < pop * low) request(f, G_FOOD, pop * full - f.food);
        }
    }

    private boolean depot(Region.Facility f) {
        return f.kind == Region.F_BASE || f.kind == Region.F_AIRBASE || f.kind == Region.F_PORT || f.kind == Region.F_AIRPORT;
    }

    private float stock(Region.Facility f, int good) {
        return good == G_AMMO ? f.ammo : good == G_FUEL ? f.fuel : good == G_FOOD ? f.food : f.med;
    }

    /** Finds a source for need of one good, and sends it. */
    private void request(Region.Facility dst, int good, float need) {
        if (need <= 0 || (onWay[dst.id] & good) != 0) return;
        Region r = w.r;
        // depots are refilled from outside: planes to airfields, ships to the port, trucks from the borders
        if (depot(dst)) {
            int mode = dst.kind == Region.F_PORT ? SHIP : dst.kind == Region.F_AIRBASE || dst.kind == Region.F_AIRPORT ? PLANE : TRUCK;
            float load = mode == PLANE ? Math.min(need, good == G_FOOD ? 20000 : good == G_FUEL ? 60 : need) : need;
            send(OUTSIDE, dst, good, load, mode);
            return;
        }
        // food: another city's store with plenty, by train if both have stations, else the port or airport, else outside
        Region.Facility best = null;
        float bd = Float.MAX_VALUE;
        for (Region.Facility f : r.facilities) {
            if (f == dst || !w.strat.online(f.id)) continue;
            boolean source = depot(f) || (good == G_FOOD && f.kind == Region.F_FOODW);
            if (!source) continue;
            float have = stock(f, good);
            if (good == G_FOOD && f.kind == Region.F_FOODW) {
                int pop = Math.max(1, w.stats.popBySettlement[f.settlement]);
                have -= pop * 6f; // a city keeps six days for itself
            } else if (depot(f)) have -= good == G_AMMO ? 8000 : good == G_FUEL ? 60 : good == G_MED ? 200 : 20000;
            if (have < need * 0.3f) continue;
            float dx = f.x - dst.x, dy = f.y - dst.y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        if (best == null) {
            send(OUTSIDE, dst, good, need, TRUCK);
            return;
        }
        float give = Math.min(need, stock(best, good) * 0.5f);
        int mode = TRUCK;
        // long hauls between two places with stations go by freight train, whatever the goods
        float dx = best.x - dst.x, dy = best.y - dst.y;
        Region.Facility headA = railHead(best), headB = station(dst.settlement);
        if (headA != null && headB != null && headA != headB
                && dx * dx + dy * dy > w.c.rule("trainOver", 2500) * w.c.rule("trainOver", 2500)) mode = TRAIN;
        send(best.id, dst, good, give, mode);
    }

    /** A railway station for goods leaving this facility: its own town's, or the nearest city's (a short haul to it). */
    private Region.Facility railHead(Region.Facility f) {
        Region.Facility s = station(f.settlement);
        if (s != null) return s;
        Region.Facility best = null;
        float bd = w.c.rule("railHeadReach", 3500);
        bd *= bd;
        for (Region.Facility g : w.r.facilities) {
            if (g.kind != Region.F_STATION || g.railNode < 0 || !w.strat.online(g.id)) continue;
            float dx = g.x - f.x, dy = g.y - f.y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = g;
            }
        }
        return best;
    }

    private Region.Facility station(int settlement) {
        if (settlement < 0) return null;
        for (Region.Facility f : w.r.facilities)
            if (f.kind == Region.F_STATION && f.settlement == settlement && f.railNode >= 0 && w.strat.online(f.id)) return f;
        return null;
    }

    private void send(int from, Region.Facility dst, int good, float amount, int mode) {
        if (amount <= 0) return;
        Region r = w.r;
        Convoy c = new Convoy();
        c.id = nextId++;
        c.mode = mode;
        c.from = from;
        c.to = dst.id;
        c.goods = good;
        Region.Facility src = from >= 0 ? r.facilities.get(from) : null;
        if (mode == TRUCK) {
            int a;
            if (src != null) a = r.roads.nearestNode(src.x, src.y, 1500);
            else a = nearestEntry(dst.x, dst.y);
            int b = r.roads.nearestNode(dst.x, dst.y, 1500);
            if (a < 0 || b < 0) return;
            c.route = r.roads.route(a, b);
            if (c.route == null) return;
            c.x = r.roads.nx[a];
            c.y = r.roads.ny[a];
        } else if (mode == TRAIN) {
            Region.Facility sa = railHead(src), sb = station(dst.settlement);
            c.route = r.rails.route(sa.railNode, sb.railNode);
            if (c.route == null) {
                send(from, dst, good, amount, TRUCK);
                return;
            }
            c.x = sa.x;
            c.y = sa.y;
        } else {
            // straight in from the edge of the map (planes), or from the open sea (ships)
            float[] p = mode == SHIP ? offshore(dst) : edge(dst.x, dst.y);
            c.x = p[0];
            c.y = p[1];
            c.tx = dst.x;
            c.ty = dst.y;
        }
        if (src != null) take(src, good, amount);
        put(c, good, amount);
        onWay[dst.id] |= good;
        convoys.add(c);
        sent[mode]++;
        w.stats.supplyRuns++;
        if (w.stats.supplyRuns <= 2 || (mode == SHIP && Rng.unit(w.seed, c.id, 5) < 0.3f))
            w.news.radio(MODES[mode] + " with " + goodsName(c) + " heading for " + dst.name, c.x, c.y, News.UNIT);
    }

    private int nearestEntry(float x, float y) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int n : w.r.entryNodes) {
            float dx = w.r.roads.nx[n] - x, dy = w.r.roads.ny[n] - y, d = dx * dx + dy * dy;
            if (d < bd && w.r.roads.nodeLive(n)) {
                bd = d;
                best = n;
            }
        }
        return best;
    }

    private float[] edge(float x, float y) {
        float m = w.r.px();
        float l = x, rr = m - x, t = y, b = m - y;
        float min = Math.min(Math.min(l, rr), Math.min(t, b));
        if (min == l) return new float[]{-200, y};
        if (min == rr) return new float[]{m + 200, y};
        if (min == t) return new float[]{x, -200};
        return new float[]{x, m + 200};
    }

    /** A point out at sea from a coastal facility: the direction with the most water. */
    private float[] offshore(Region.Facility f) {
        float best = -1, bx = 0, by = 0;
        for (int a = 0; a < 16; a++) {
            double ang = a * Math.PI / 8;
            int wet = 0;
            for (int d = 200; d <= 4000; d += 200)
                if (Tile.water(w.r.tileAtPx(f.x + (float) Math.cos(ang) * d, f.y + (float) Math.sin(ang) * d))) wet++;
            if (wet > best) {
                best = wet;
                bx = (float) Math.cos(ang);
                by = (float) Math.sin(ang);
            }
        }
        float m = w.r.px();
        return new float[]{Math.max(-200, Math.min(m + 200, f.x + bx * 5000)), Math.max(-200, Math.min(m + 200, f.y + by * 5000))};
    }

    private void take(Region.Facility f, int good, float a) {
        if (good == G_AMMO) f.ammo -= a;
        else if (good == G_FUEL) f.fuel -= a;
        else if (good == G_FOOD) f.food -= a;
        else f.med -= a;
    }

    private static void put(Convoy c, int good, float a) {
        if (good == G_AMMO) c.ammo += a;
        else if (good == G_FUEL) c.fuel += a;
        else if (good == G_FOOD) c.food += a;
        else c.med += a;
    }

    static String goodsName(Convoy c) {
        if (c.refugees > 0) return c.refugees + " refugees";
        StringBuilder sb = new StringBuilder();
        if (c.ammo > 0) sb.append((int) c.ammo).append(" rounds");
        if (c.fuel > 0) sb.append(sb.length() > 0 ? ", " : "").append((int) c.fuel).append(" t of fuel");
        if (c.food > 0) sb.append(sb.length() > 0 ? ", " : "").append(WorldView.fmt((int) c.food)).append(" days' food");
        if (c.med > 0) sb.append(sb.length() > 0 ? ", " : "").append((int) c.med).append(" doses of medicine");
        return sb.length() == 0 ? "nothing left" : sb.toString();
    }

    // ------------------------------------------------------------------ refugee trains

    private void refugeeTrains() {
        Region r = w.r;
        int totalPending = 0;
        for (int p : w.strat.pending) totalPending += p;
        for (Region.Settlement s : r.settlements) {
            int st = s.id;
            if (st >= w.strat.pending.length || w.strat.pending[st] < 300) continue;
            Region.Facility from = station(st);
            if (from == null) continue;
            // the safest city with a station
            Region.Facility best = null;
            float bs = Float.MAX_VALUE;
            for (Region.Settlement d : r.settlements) {
                if (d.id == st || d.kind != Region.S_CITY) continue;
                Region.Facility to = station(d.id);
                if (to == null || w.gov.evacuating(d.id)) continue;
                float score = w.stats.zBySettlement[d.id] * 10f / (d.residents + 100);
                if (w.stats.zBySettlement[d.id] > 100) continue;
                if (score < bs) {
                    bs = score;
                    best = to;
                }
            }
            if (best == null) continue;
            int[] route = r.rails.route(from.railNode, best.railNode);
            if (route == null) continue;
            Convoy c = new Convoy();
            c.id = nextId++;
            c.mode = TRAIN;
            c.from = from.id;
            c.to = best.id;
            c.toSettlement = best.settlement;
            c.route = route;
            c.x = from.x;
            c.y = from.y;
            c.refugees = Math.min(w.strat.pending[st], 2000);
            w.strat.pending[st] -= c.refugees;
            int inf = totalPending > 0 ? Math.min(w.strat.infectedPending, (int) ((long) w.strat.infectedPending * c.refugees / totalPending)) : 0;
            c.infected = inf;
            w.strat.infectedPending -= inf;
            convoys.add(c);
            w.news.radio("Evacuation train leaves " + s.name + " with " + c.refugees + " people", c.x, c.y, News.CALL);
        }
    }

    // ------------------------------------------------------------------ moving

    private void move(Convoy c, float dt) {
        Region r = w.r;
        if (c.mode == PLANE || c.mode == SHIP) {
            float sp = (c.mode == PLANE ? w.c.rule("planeSpeed", 500) : w.c.rule("shipSpeed", 25)) * dt;
            float dx = c.tx - c.x, dy = c.ty - c.y, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d <= sp) {
                arrive(c);
                return;
            }
            c.x += dx / d * sp;
            c.y += dy / d * sp;
            return;
        }
        RoadGraph g = c.mode == TRAIN ? r.rails : r.roads;
        float budget = (c.mode == TRAIN ? w.c.rule("trainSpeed", 90) : w.c.rule("convoySpeed", 35)) * dt;
        while (budget > 0 && c.route != null && c.ri < c.route.length - 1) {
            int nn = c.route[c.ri + 1];
            int e = g.edgeBetween(c.route[c.ri], nn);
            if (e >= 0 && !g.ealive[e]) {
                // the bridge is gone: another way round, or back where it came from
                int dst = c.mode == TRAIN ? c.route[c.route.length - 1] : g.nearestNode(target(c)[0], target(c)[1], 1500);
                int[] rt = g.route(c.route[c.ri], dst);
                if (rt != null) {
                    c.route = rt;
                    c.ri = 0;
                    continue;
                }
                turnBack(c);
                return;
            }
            float dx = g.nx[nn] - c.x, dy = g.ny[nn] - c.y, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d <= budget) {
                c.x = g.nx[nn];
                c.y = g.ny[nn];
                c.ri++;
                budget -= d;
            } else {
                c.x += dx / d * budget;
                c.y += dy / d * budget;
                budget = 0;
            }
        }
        // the dead on the way
        int s = r.sectorOfPx(c.x, c.y);
        int z = w.sp.secZ[s] + w.sec.zIn[s];
        if (z >= 15 && c.refugees == 0) {
            float frac = Math.min(0.6f, z / 500f) * Math.min(1, dt / 2f) * (c.mode == TRAIN ? 0.3f : 1f);
            c.ammo *= 1 - frac;
            c.fuel *= 1 - frac;
            c.food *= 1 - frac;
            c.med *= 1 - frac;
            if (z >= 80 && Rng.unit(w.seed, w.tick, c.id * 7L + 1) < 0.04f * dt * (c.mode == TRAIN ? 0.3f : 1f)) {
                c.alive = false;
                lost++;
                onWay[c.to] &= ~c.goods;
                w.stats.vehiclesLost += c.size();
                w.news.radio(MODES[c.mode] + " overrun by the dead on the way to " + r.facilities.get(c.to).name, c.x, c.y, News.LOSS);
                return;
            }
        }
        // a bitten passenger: the carriage turns into a trap
        if (c.infected > 0 && c.refugees > c.infected) {
            int bitten = Math.min(c.refugees - c.infected, (int) (c.infected * 0.01f * dt) + (Rng.unit(w.seed, w.tick, c.id * 7L + 2) < 0.01f * dt ? 1 : 0));
            c.infected += bitten;
        }
        if (c.route == null || c.ri >= c.route.length - 1) arrive(c);
    }

    private float[] target(Convoy c) {
        Region.Facility f = w.r.facilities.get(c.to);
        return new float[]{f.x, f.y};
    }

    private void turnBack(Convoy c) {
        c.alive = false;
        onWay[c.to] &= ~c.goods;
        if (c.from >= 0 && c.refugees == 0) {
            Region.Facility f = w.r.facilities.get(c.from);
            f.ammo += c.ammo;
            f.fuel += c.fuel;
            f.food += c.food;
            f.med += c.med;
        } else if (c.refugees > 0 && c.from >= 0) {
            int st = w.r.facilities.get(c.from).settlement;
            if (st >= 0 && st < w.strat.pending.length) w.strat.pending[st] += c.refugees;
        }
    }

    private void arrive(Convoy c) {
        c.alive = false;
        Region.Facility f = w.r.facilities.get(c.to);
        onWay[c.to] &= ~c.goods;
        if (c.refugees > 0) {
            settle(c.toSettlement, c.refugees - c.infected, c.infected);
            w.strat.evacuatedArrived += c.refugees;
            w.news.radio("Evacuation train arrives in " + w.r.settlements.get(c.toSettlement).name + " with " + c.refugees + " people"
                    + (c.infected > 20 ? ". Some of them have been bitten." : ""), f.x, f.y, News.CALL);
            return;
        }
        f.ammo += c.ammo;
        f.fuel += c.fuel;
        f.food += c.food;
        f.med += c.med;
        delivered++;
        if (c.colony >= 0) w.society.traded(c.colony, c.food);
    }

    /** A caravan of food from a survivor settlement at (x, y) to a town's store. */
    public boolean trade(int colony, float x, float y, Region.Facility store, float food) {
        Region r = w.r;
        int a = r.roads.nearestNode(x, y, 3000), b = r.roads.nearestNode(store.x, store.y, 1500);
        if (a < 0 || b < 0) return false;
        int[] rt = r.roads.route(a, b);
        if (rt == null) return false;
        Convoy c = new Convoy();
        c.id = nextId++;
        c.mode = TRUCK;
        c.from = OUTSIDE;
        c.to = store.id;
        c.goods = 0; // not a request: doesn't block the town's own supply
        c.colony = colony;
        c.route = rt;
        c.x = r.roads.nx[a];
        c.y = r.roads.ny[a];
        c.food = food;
        convoys.add(c);
        return true;
    }

    private void settle(int st, int healthy, int infected) {
        Sectors sec = w.sec;
        List<Integer> homes = new ArrayList<Integer>();
        for (int s = 0; s < sec.n; s++) if (sec.settlement[s] == st && sec.home[s] > 0 && sec.zIn[s] == 0) homes.add(s);
        if (homes.isEmpty()) {
            w.strat.evacuatedOut += healthy + infected;
            return;
        }
        int per = healthy / homes.size(), extra = healthy % homes.size();
        for (int k = 0; k < homes.size(); k++) sec.popIn[homes.get(k)] += per + (k < extra ? 1 : 0);
        if (infected > 0) sec.inf[homes.get(0)] += infected;
    }

    // ------------------------------------------------------------------ queries

    public Convoy convoyAt(float x, float y, float rad) {
        Convoy best = null;
        float bd = rad * rad;
        for (Convoy c : convoys) {
            float dx = c.x - x, dy = c.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    public String describe(Convoy c) {
        return MODES[c.mode] + (c.size() > 1 && c.mode == TRUCK ? " (" + c.size() + " trucks)" : "") + ": " + goodsName(c) + " for "
                + w.r.facilities.get(c.to).name + (c.from == OUTSIDE ? ", from outside the country" : ", from " + w.r.facilities.get(c.from).name);
    }

    @Override
    public String status() {
        return convoys.isEmpty() ? null : convoys.size() + " convoys on the move";
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        float z = cam.zoom;
        for (Convoy c : convoys) {
            float sx = cam.toScreenX(c.x), sy = cam.toScreenY(c.y);
            if (z < 0.15f) {
                // supply routes: a faint line to where it's going
                float[] t = target(c);
                g.color(c.refugees > 0 ? 0x40FFFFFF : 0x40E0C060);
                g.line(sx, sy, cam.toScreenX(t[0]), cam.toScreenY(t[1]), 1);
            }
            if (sx < -200 || sy < -200 || sx > cam.sw + 200 || sy > cam.sh + 200) continue;
            if (c.mode == PLANE) {
                float[] t = target(c);
                float a = (float) Math.atan2(t[1] - c.y, t[0] - c.x);
                g.save();
                g.translate(sx, sy);
                g.rotate(a);
                float s = Math.max(6, 40 * z);
                float[] p = {s, 0, -s, -s * 0.8f, -s * 0.6f, 0, -s, s * 0.8f};
                g.color(0xFFE8ECF0);
                g.fillPoly(p, 4);
                g.restore();
                continue;
            }
            if (c.mode == SHIP) {
                float[] t = target(c);
                float a = (float) Math.atan2(t[1] - c.y, t[0] - c.x);
                g.save();
                g.translate(sx, sy);
                g.rotate(a);
                float s = Math.max(5, 70 * z);
                g.color(0xFF505A64);
                g.fillRoundRect(-s, -s * 0.28f, s * 2, s * 0.56f, s * 0.25f);
                g.color(0xFFB04030);
                g.fillRect(-s * 0.5f, -s * 0.2f, s * 0.6f, s * 0.4f);
                g.restore();
                continue;
            }
            int col = c.mode == TRAIN ? (c.refugees > 0 ? 0xFF3060A0 : 0xFF8A3A2A) : 0xFF8A8A50;
            if (z < 0.25f) {
                float s = c.mode == TRAIN ? 6 : 4.5f;
                g.color(0xFF101010);
                g.fillRect(sx - s - 1, sy - s * 0.6f - 1, s * 2 + 2, s * 1.2f + 2);
                g.color(col);
                g.fillRect(sx - s, sy - s * 0.6f, s * 2, s * 1.2f);
                continue;
            }
            // close: a column of trucks (or the carriages) strung back along the way it came
            RoadGraph gr = c.mode == TRAIN ? w.r.rails : w.r.roads;
            int n = c.mode == TRAIN ? 10 : c.size();
            float gap = c.mode == TRAIN ? 30 : 42, len = c.mode == TRAIN ? 26 : 30, wid = c.mode == TRAIN ? 11 : 13;
            float px = c.x, py = c.y;
            int idx = c.ri;
            float need = 0;
            for (int k = 0; k < n; k++) {
                // walk back along the route by 'gap'
                float rem = k == 0 ? 0 : gap;
                float hx = 1, hy = 0;
                while (rem > 0) {
                    if (c.route == null || idx < 0) break;
                    float bx = gr.nx[c.route[idx]], by = gr.ny[c.route[idx]];
                    float dx = bx - px, dy = by - py, d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d >= rem) {
                        px += dx / d * rem;
                        py += dy / d * rem;
                        rem = 0;
                    } else {
                        px = bx;
                        py = by;
                        rem -= d;
                        idx--;
                    }
                }
                if (c.route != null && c.ri + 1 < c.route.length) {
                    int ahead = Math.min(c.route.length - 1, Math.max(idx + 1, 0));
                    hx = gr.nx[c.route[ahead]] - px;
                    hy = gr.ny[c.route[ahead]] - py;
                }
                float a = (float) Math.atan2(hy, hx);
                g.save();
                g.translate(cam.toScreenX(px), cam.toScreenY(py));
                g.rotate(a);
                g.color(0x40000000);
                g.fillRect(-len / 2 * z + 2 * z, -wid / 2 * z + 3 * z, len * z, wid * z);
                g.color(k == 0 && c.mode == TRAIN ? 0xFF303030 : col);
                g.fillRoundRect(-len / 2 * z, -wid / 2 * z, len * z, wid * z, 2 * z);
                g.color(0x30FFFFFF);
                g.fillRect(-len / 2 * z, -wid / 2 * z, len * 0.3f * z, wid * z);
                g.restore();
                need += 0;
            }
        }
    }

    // ------------------------------------------------------------------ saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeFloat(requestT);
        o.writeFloat(trainT);
        o.writeFloat(importT);
        o.writeInt(nextId);
        o.writeInt(lost);
        o.writeInt(delivered);
        o.writeInt(onWay.length);
        for (int v : onWay) o.writeInt(v);
        o.writeInt(convoys.size());
        for (Convoy c : convoys) {
            o.writeInt(c.id);
            o.writeInt(c.mode);
            o.writeInt(c.from);
            o.writeInt(c.to);
            o.writeInt(c.toSettlement);
            o.writeInt(c.ri);
            o.writeInt(c.refugees);
            o.writeInt(c.infected);
            o.writeInt(c.goods);
            o.writeFloat(c.ammo);
            o.writeFloat(c.fuel);
            o.writeFloat(c.food);
            o.writeFloat(c.med);
            o.writeFloat(c.x);
            o.writeFloat(c.y);
            o.writeFloat(c.tx);
            o.writeFloat(c.ty);
            o.writeInt(c.route == null ? -1 : c.route.length);
            if (c.route != null) for (int v : c.route) o.writeInt(v);
            o.writeBoolean(c.alive);
            o.writeInt(c.colony);
        }
        for (int m = 0; m < 4; m++) o.writeInt(sent[m]);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        requestT = in.readFloat();
        trainT = in.readFloat();
        importT = in.readFloat();
        nextId = in.readInt();
        lost = in.readInt();
        delivered = in.readInt();
        onWay = new int[in.readInt()];
        for (int i = 0; i < onWay.length; i++) onWay[i] = in.readInt();
        int n = in.readInt();
        convoys.clear();
        for (int k = 0; k < n; k++) {
            Convoy c = new Convoy();
            c.id = in.readInt();
            c.mode = in.readInt();
            c.from = in.readInt();
            c.to = in.readInt();
            c.toSettlement = in.readInt();
            c.ri = in.readInt();
            c.refugees = in.readInt();
            c.infected = in.readInt();
            c.goods = in.readInt();
            c.ammo = in.readFloat();
            c.fuel = in.readFloat();
            c.food = in.readFloat();
            c.med = in.readFloat();
            c.x = in.readFloat();
            c.y = in.readFloat();
            c.tx = in.readFloat();
            c.ty = in.readFloat();
            int rl = in.readInt();
            if (rl >= 0) {
                c.route = new int[rl];
                for (int i = 0; i < rl; i++) c.route[i] = in.readInt();
            }
            c.alive = in.readBoolean();
            c.colony = in.readInt();
            convoys.add(c);
        }
        if (in.available() > 0) for (int m = 0; m < 4; m++) sent[m] = in.readInt();
    }
}
