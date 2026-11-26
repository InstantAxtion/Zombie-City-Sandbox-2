package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * People with their own ideas. Early in an outbreak families cross town to reach each other (and carry the
 * infection with them); streets that hold out barricade themselves in; where the police are thin and fear is
 * high, people arm themselves as militias; hunger and chaos bring looting (and fires); refugees crowding into a
 * town end up in a camp on its edge (where one bitten arrival can start it all again); and after a town falls,
 * its survivors found settlements in the countryside, which can fall in turn.
 */
public final class Society extends Module {
    public static final class Camp {
        public int town, sector, facility, people;
        public float x, y;
        public String name;
    }

    public static final class Colony {
        public int sector, start, from;
        public float x, y;
        public String name;
        public boolean fallen;
    }

    private final float[] holdT;
    /** Armed volunteers per settlement (part of its people, counted among them). */
    public final int[] militia;
    private final double[] firstAlert;
    private final int[] arrivals, arrivalsInf;
    private final boolean[] colonyMade;
    public final List<Camp> camps = new ArrayList<Camp>();
    public final List<Colony> colonies = new ArrayList<Colony>();
    private final List<int[]> responders = new ArrayList<int[]>(); // squad id, seconds left
    private float minuteT, hourT;
    private final int militiaUnit;

    public Society(World w) {
        super(w);
        int n = w.r.settlements.size();
        holdT = new float[w.sec.n];
        w.sec.holdout = new boolean[w.sec.n];
        militia = new int[n];
        firstAlert = new double[n];
        java.util.Arrays.fill(firstAlert, -1);
        arrivals = new int[n];
        arrivalsInf = new int[n];
        colonyMade = new boolean[n];
        Content.Unit m = w.c.unit("militia");
        militiaUnit = m == null ? -1 : m.index;
    }

    @Override
    public String id() {
        return "society";
    }

    /** Refugees arrived in a town (from Strategic): crowding makes camps. */
    void arrived(int st, int count, int infected) {
        if (st < 0 || st >= arrivals.length) return;
        arrivals[st] += count;
        arrivalsInf[st] += infected;
    }

    private int stoch(float v, long salt) {
        int f = (int) v;
        return f + (Rng.unit(w.seed, w.tick, salt) < v - f ? 1 : 0);
    }

    @Override
    public void update(float dt) {
        holdouts(dt);
        families(dt);
        militiaFight(dt);
        minuteT += dt;
        hourT += dt;
        if (minuteT >= 60) {
            minuteT = 0;
            militias();
            camps();
        }
        if (hourT >= 60 * 6) {
            hourT = 0;
            looting();
            colonies();
        }
        for (int k = responders.size() - 1; k >= 0; k--) {
            int[] r = responders.get(k);
            r[1] -= (int) Math.ceil(dt);
            if (r[1] > 0) continue;
            if (r[0] < w.sq.list.size()) {
                Squads.Squad s = w.sq.get(r[0]);
                if (s.alive && s.order == Squads.O_RESPOND && s.incident < 0) w.sq.goHome(s);
            }
            responders.remove(k);
        }
    }

    // ------------------------------------------------------------------ holdouts

    private void holdouts(float dt) {
        Sectors sec = w.sec;
        float after = w.c.rule("holdoutAfter", 1440);
        int announced = 0;
        for (int s = 0; s < sec.n; s++) {
            if (sec.home[s] < 200) continue;
            if (sec.holdout[s]) {
                if (sec.popIn[s] < sec.home[s] * 0.08f) {
                    sec.holdout[s] = false;
                    holdT[s] = 0;
                }
                continue;
            }
            boolean threatened = sec.zIn[s] + w.sp.secZ[s] > 0;
            for (int d = 0; d < 8 && !threatened; d++) {
                int t = sec.neighbour(s, d);
                if (t >= 0 && sec.zIn[t] + w.sp.secZ[t] > 0) threatened = true;
            }
            if (threatened && sec.popIn[s] >= sec.home[s] * 0.3f) holdT[s] += dt;
            if (holdT[s] >= after) {
                sec.holdout[s] = true;
                w.stats.holdouts++;
                if (announced++ == 0 && w.stats.holdouts <= 6) {
                    int st = sec.settlement[s];
                    w.news.radio("People in " + (st >= 0 ? w.r.settlements.get(st).name : "the countryside") + " have barricaded their streets and are holding out",
                            sec.cx(s), sec.cy(s), News.CALL);
                }
            }
        }
    }

    // ------------------------------------------------------------------ families

    private void families(float dt) {
        Sectors sec = w.sec;
        float window = w.c.rule("familyWindow", 240), rate = w.c.rule("familyRate", 0.002f);
        for (int st = 0; st < firstAlert.length; st++)
            if (firstAlert[st] < 0 && w.command.alerted[st]) firstAlert[st] = w.time;
        for (int s = 0; s < sec.n; s++) {
            int st = sec.settlement[s];
            if (st < 0 || firstAlert[st] < 0 || w.time - firstAlert[st] > window) continue;
            if (sec.popIn[s] == 0 && sec.inf[s] < 1) continue;
            int d = Rng.hashInt(w.seed, w.tick, s, 8);
            int t = sec.neighbour(s, d);
            if (t < 0 || sec.settlement[t] != st || sec.home[t] == 0) continue;
            int m = Math.min(sec.popIn[s], stoch(sec.popIn[s] * rate * dt, s * 29L + 1));
            sec.popIn[s] -= m;
            sec.popIn[t] += m;
            float carry = sec.inf[s] * rate * dt * 2;
            int c = Math.min((int) sec.inf[s], stoch(carry, s * 29L + 2));
            sec.inf[s] -= c;
            sec.inf[t] += c;
        }
    }

    // ------------------------------------------------------------------ militias

    private int policeIn(int st) {
        int n = 0;
        for (Region.Facility f : w.r.facilities) if (f.settlement == st && f.kind == Region.F_POLICE) n += w.forces.armedAt(f, Ents.COP);
        return n;
    }

    private void militias() {
        if (militiaUnit < 0) return;
        float thin = w.c.rule("militiaPolicePerThousand", 0.6f), fearAt = w.c.rule("militiaFear", 0.45f);
        for (Region.Settlement s : w.r.settlements) {
            int st = s.id;
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN && s.kind != Region.S_VILLAGE) continue;
            int pop = w.stats.popBySettlement[st];
            int cap = (int) (pop * w.c.rule("militiaShare", 0.003f));
            if (militia[st] > cap) militia[st] = cap;
            if (pop < 50 || w.stats.zBySettlement[st] < 20) continue;
            float perK = policeIn(st) / (pop / 1000f + 0.01f);
            if (perK > thin || w.media.fear(st) < fearAt || militia[st] >= cap) continue;
            int join = Math.min(cap - militia[st], Math.max(5, cap / 6));
            boolean first = militia[st] == 0;
            militia[st] += join;
            w.stats.militiaJoined += join;
            if (first) w.news.radio("With the police stretched thin, armed residents of " + s.name + " are forming militias", s.cx * 16f, s.cy * 16f, News.CALL);
        }
        if (w.strategic) return;
        // in the streets where it's happening, some of them come out
        int[] out = new int[militia.length];
        Ents e = w.e;
        for (int i = 0; i < e.top; i++)
            if (e.loc[i] == Ents.FREE && e.kind[i] == Ents.CIV && e.type[i] == militiaUnit) {
                int st = w.sec.settlement[w.r.sectorOfPx(e.x[i], e.y[i])];
                if (st >= 0) out[st]++;
            }
        Content.Unit mu = w.c.units.get(militiaUnit);
        Sectors sec = w.sec;
        for (int s = 0; s < sec.n; s++) {
            int st = sec.settlement[s];
            if (st < 0 || militia[st] == 0 || sec.hot[s] <= 0 || w.sp.secZ[s] == 0 || sec.popIn[s] < 20) continue;
            int want = Math.min(militia[st], 16) - out[st];
            for (int k = 0; k < Math.min(want, 6); k++) {
                float[] p = sec.spot(s, 400 + k);
                if (p == null) break;
                int i = w.spawnCiv(p[0], p[1], mu);
                e.state[i] = CivAI.S_MILITIA;
                e.gx[i] = p[0];
                e.gy[i] = p[1];
                sec.popIn[s]--;
                out[st]++;
            }
        }
    }

    /** Militias fight the dead in their own streets where nobody is watching. */
    private void militiaFight(float dt) {
        Sectors sec = w.sec;
        float kill = w.c.rule("aggregateKill", 0.05f) * 0.5f, loss = w.c.rule("aggregateLoss", 0.0004f) * 2;
        int[] engaged = new int[militia.length];
        for (int s = 0; s < sec.n; s++) {
            int st = sec.settlement[s];
            if (st >= 0 && militia[st] > 0 && sec.zIn[s] > 0 && sec.hot[s] <= 0) engaged[st]++;
        }
        for (int s = 0; s < sec.n; s++) {
            int st = sec.settlement[s];
            if (st < 0 || militia[st] <= 0 || sec.zIn[s] <= 0 || sec.hot[s] > 0) continue;
            float share = militia[st] / (float) engaged[st];
            int k = Math.min(sec.zIn[s], stoch(share * kill * dt, s * 41L + 1));
            sec.zIn[s] -= k;
            sec.bodies[s] += k;
            w.stats.killsBySide[0] += k;
            w.stats.zombieKills += k;
            int lost = Math.min(militia[st], stoch(Math.min(sec.zIn[s], share * 6) * loss * dt, s * 41L + 2));
            if (lost > 0) {
                militia[st] -= lost;
                int dead = Math.min(lost, sec.popIn[s]);
                sec.popIn[s] -= dead;
                w.stats.civDeaths += dead;
                sec.zIn[s] += stoch(dead * 0.6f, s * 41L + 3);
            }
        }
    }

    // ------------------------------------------------------------------ looting

    private void looting() {
        float trustAt = w.c.rule("lootTrust", 35), fearAt = w.c.rule("lootFear", 0.5f);
        for (Region.Settlement s : w.r.settlements) {
            int st = s.id;
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
            if (w.gov.trust > trustAt || w.media.fear(st) < fearAt || w.stats.popBySettlement[st] < 200) continue;
            if (Rng.unit(w.seed, w.tick, st * 7L + 99) > 0.4f) continue;
            // a shop or warehouse in the town
            int b = -1;
            for (int k = 0; k < 30 && b < 0; k++) {
                int c = Rng.hashInt(w.seed, w.tick + k, st, w.r.bCount);
                int kind = w.r.bkind[c];
                if (w.r.bset[c] == st && w.r.bfloors[c] > 0 && (kind == Region.B_SHOP || kind == Region.B_MALL || kind == Region.B_WAREHOUSE || kind == Region.B_FOODW))
                    b = c;
            }
            if (b < 0) continue;
            float x = (w.r.bx[b] + w.r.bw[b] / 2f) * 16, y = (w.r.by[b] + w.r.bh[b] / 2f) * 16;
            w.stats.lootings++;
            // stores lost
            for (Region.Facility f : w.r.facilities) {
                if (f.settlement != st) continue;
                if (f.kind == Region.F_FOODW) f.food *= 0.8f;
                if (f.kind == Region.F_FUEL) f.fuel *= 0.85f;
            }
            boolean burned = Rng.unit(w.seed, w.tick, b) < w.c.rule("lootFire", 0.25f);
            w.fx.add(Effects.SMOKE, x, y, 60, 0, burned ? 400 : 120);
            if (burned) {
                w.r.destroyBuilding(b);
                w.stats.buildingsLost++;
            }
            w.news.radio("Looting in " + s.name + (burned ? ": a building has been set on fire" : ""), x, y, News.CALL);
            w.gov.trust -= 1;
            // the police go if they can
            if (w.strategic) continue;
            Region.Facility best = null;
            float bd = Float.MAX_VALUE;
            for (Region.Facility f : w.r.facilities) {
                if (f.kind != Region.F_POLICE || f.settlement != st || f.pool == null) continue;
                float dx = f.x - x, dy = f.y - y, d = dx * dx + dy * dy;
                if (d < bd) {
                    bd = d;
                    best = f;
                }
            }
            if (best == null) continue;
            Content.SquadT t = w.forces.templateFor(best, Ents.COP, 0);
            if (t == null) continue;
            Squads.Squad sq = w.forces.deploy(best, t);
            if (sq == null) continue;
            float px = w.doorX.length > b && w.doorX[b] >= 0 ? w.doorX[b] : x, py = w.doorY.length > b && w.doorY[b] >= 0 ? w.doorY[b] : y;
            w.sq.order(sq, Squads.O_RESPOND, px, py);
            responders.add(new int[]{sq.id, 120});
        }
    }

    // ------------------------------------------------------------------ refugee camps

    private void camps() {
        for (int st = 0; st < arrivals.length; st++) {
            if (arrivals[st] <= 0) continue;
            Region.Settlement s = w.r.settlements.get(st);
            int pop = w.stats.popBySettlement[st];
            int excess = pop - (int) (s.residents * w.c.rule("campCrowding", 1.1f));
            if (excess <= 0) {
                arrivals[st] = 0;
                arrivalsInf[st] = 0;
                continue;
            }
            Camp c = campFor(st);
            if (c == null) c = makeCamp(s);
            if (c == null) {
                arrivals[st] = 0;
                arrivalsInf[st] = 0;
                continue;
            }
            int move = Math.min(arrivals[st], excess);
            Sectors sec = w.sec;
            long total = 0;
            for (int k = 0; k < sec.n; k++) if (sec.settlement[k] == st) total += sec.popIn[k];
            int moved = 0;
            for (int k = 0; k < sec.n && total > 0; k++) {
                if (sec.settlement[k] != st || sec.popIn[k] == 0) continue;
                int m = Math.min(sec.popIn[k], (int) ((long) sec.popIn[k] * move / total));
                sec.popIn[k] -= m;
                moved += m;
            }
            sec.popIn[c.sector] += moved;
            c.people += moved;
            // the bitten among them come too
            int inf = arrivalsInf[st];
            for (int k = 0; k < sec.n && inf > 0; k++) {
                if (sec.settlement[k] != st || sec.inf[k] < 1) continue;
                int m = Math.min(inf, (int) sec.inf[k]);
                sec.inf[k] -= m;
                sec.inf[c.sector] += m;
                inf -= m;
            }
            arrivals[st] = 0;
            arrivalsInf[st] = 0;
        }
    }

    private Camp campFor(int st) {
        for (Camp c : camps) if (c.town == st) return c;
        return null;
    }

    private Camp makeCamp(Region.Settlement s) {
        Region r = w.r;
        for (int a = 0; a < 24; a++) {
            double ang = a * Math.PI / 12 + s.id;
            int cx = s.cx + (int) (Math.cos(ang) * (s.radius + 14)), cy = s.cy + (int) (Math.sin(ang) * (s.radius + 14));
            boolean ok = true;
            for (int y = cy - 6; y <= cy + 6 && ok; y++)
                for (int x = cx - 8; x <= cx + 8 && ok; x++) {
                    int t = r.t(x, y);
                    if (t != Tile.GRASS && t != Tile.FARM && t != Tile.FOREST && t != Tile.YARD) ok = false;
                }
            if (!ok || r.settlementAt(cx * 16f, cy * 16f) != null) continue;
            for (int y = cy - 6; y <= cy + 6; y++) for (int x = cx - 8; x <= cx + 8; x++) r.set(x, y, Tile.CAMP);
            r.rebuildNav(cx - 9, cy - 7, cx + 9, cy + 7);
            Camp c = new Camp();
            c.town = s.id;
            c.x = cx * 16f;
            c.y = cy * 16f;
            c.sector = r.sectorOfPx(c.x, c.y);
            c.name = s.name + " Refugee Camp";
            Region.Facility f = r.addFacility(Region.F_CAMP, -1, c.x, c.y, c.name);
            f.node = r.roads.nearestNode(c.x, c.y, 900);
            c.facility = f.id;
            w.strat.extendFacilities();
            camps.add(c);
            w.news.headline("A refugee camp has been set up outside " + s.name + ".", c.x, c.y);
            return c;
        }
        return null;
    }

    // ------------------------------------------------------------------ survivor settlements

    private void colonies() {
        Sectors sec = w.sec;
        for (Region.Settlement s : w.r.settlements) {
            int st = s.id;
            if (colonyMade[st] || (s.kind != Region.S_CITY && s.kind != Region.S_TOWN)) continue;
            int pop = w.stats.popBySettlement[st];
            if (s.residents < 3000 || pop > s.residents * 0.15f || pop < 100 || w.stats.zBySettlement[st] < s.residents * 0.02f) continue;
            colonyMade[st] = true;
            // somewhere quiet in the country round about
            int sx = (int) (s.cx * 16f / (Region.SECT * Tile.SIZE)), sy = (int) (s.cy * 16f / (Region.SECT * Tile.SIZE));
            int best = -1;
            for (int k = 0; k < 80 && best < 0; k++) {
                int dx = Rng.hashInt(w.seed, st, k * 2L, 25) - 12, dy = Rng.hashInt(w.seed, st, k * 2L + 1, 25) - 12;
                if (Math.abs(dx) < 4 && Math.abs(dy) < 4) continue;
                int x = sx + dx, y = sy + dy;
                if (x < 0 || y < 0 || x >= sec.sw || y >= sec.sh) continue;
                int t = y * sec.sw + x;
                if (sec.settlement[t] >= 0 || sec.zIn[t] > 0 || sec.connect[t] == 0 || !w.r.walkablePx(sec.cx(t), sec.cy(t))) continue;
                best = t;
            }
            if (best < 0) continue;
            int want = Math.min(2500, pop / 2);
            int got = 0;
            for (int k = 0; k < sec.n && got < want; k++) {
                if (sec.settlement[k] != st || sec.popIn[k] == 0 || sec.zIn[k] > 0) continue;
                int m = Math.min(sec.popIn[k], want - got);
                sec.popIn[k] -= m;
                got += m;
            }
            if (got == 0) continue;
            sec.popIn[best] += got;
            sec.holdout[best] = true;
            Colony c = new Colony();
            c.sector = best;
            c.start = got;
            c.from = st;
            c.x = sec.cx(best);
            c.y = sec.cy(best);
            c.name = Names.place(new Rng(w.seed + st * 977L), Region.S_HOLDOUT);
            colonies.add(c);
            w.news.headline("Survivors from " + s.name + " have founded a settlement in the country: " + c.name + " (" + got + ").", c.x, c.y);
        }
        for (Colony c : colonies) {
            if (c.fallen) continue;
            if (sec.popIn[c.sector] < c.start * 0.2f) {
                c.fallen = true;
                w.news.headline(c.name + " has fallen.", c.x, c.y);
            }
        }
    }

    // ------------------------------------------------------------------ drawing, saves

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        float z = cam.zoom;
        if (z < 0.03f) return;
        Sectors sec = w.sec;
        if (z < 1.2f && z > 0.05f) {
            float cs = Region.SECT * Tile.SIZE;
            int sx0 = Math.max(0, (int) (cam.x0() / cs)), sy0 = Math.max(0, (int) (cam.y0() / cs));
            int sx1 = Math.min(sec.sw - 1, (int) (cam.x1() / cs)), sy1 = Math.min(sec.sh - 1, (int) (cam.y1() / cs));
            for (int sy = sy0; sy <= sy1; sy++)
                for (int sx = sx0; sx <= sx1; sx++) {
                    int s = sy * sec.sw + sx;
                    if (!sec.holdout[s]) continue;
                    float x = cam.toScreenX(sec.cx(s)), y = cam.toScreenY(sec.cy(s));
                    // a barricade: a striped bar
                    g.color(0xFF101010);
                    g.fillRect(x - 10, y - 4, 20, 8);
                    for (int k = 0; k < 4; k++) {
                        g.color((k & 1) == 0 ? 0xFFE0B030 : 0xFF303030);
                        g.fillRect(x - 9 + k * 4.5f, y - 3, 4.5f, 6);
                    }
                }
        }
        for (Camp c : camps) {
            float x = cam.toScreenX(c.x), y = cam.toScreenY(c.y);
            if (x < -60 || y < -60 || x > cam.sw + 60 || y > cam.sh + 60) continue;
            float s = Math.max(3, 14 * z);
            for (int k = 0; k < 9; k++) {
                float tx = x + ((k % 3) - 1) * s * 2.2f, ty = y + ((k / 3) - 1) * s * 2;
                g.color(0xFFD8D0B0);
                float[] p = {tx, ty - s, tx + s, ty + s * 0.7f, tx - s, ty + s * 0.7f};
                g.fillPoly(p, 3);
            }
            if (z < 0.5f) {
                g.color(0xFFFFFFFF);
                g.text(c.name + " (" + WorldView.fmt(Math.max(0, sec.popIn[c.sector])) + ")", x, y + s * 4, 10, Gfx.ALIGN_CENTER, false);
            }
        }
        for (Colony c : colonies) {
            float x = cam.toScreenX(c.x), y = cam.toScreenY(c.y);
            if (x < -60 || y < -60 || x > cam.sw + 60 || y > cam.sh + 60) continue;
            float r = Math.max(6, 120 * z);
            g.color(c.fallen ? 0xFF803030 : 0xFFA07840);
            g.strokeCircle(x, y, r, 3);
            g.color(0xFFFFFFFF);
            g.text(c.name + (c.fallen ? " (fallen)" : " (" + WorldView.fmt(sec.popIn[c.sector]) + ")"), x, y - r - 4, 10, Gfx.ALIGN_CENTER, true);
        }
    }

    @Override
    public void save(DataOutputStream o) throws IOException {
        Sectors sec = w.sec;
        o.writeInt(sec.n);
        for (int s = 0; s < sec.n; s++) {
            o.writeFloat(holdT[s]);
            o.writeBoolean(sec.holdout[s]);
        }
        o.writeInt(militia.length);
        for (int i = 0; i < militia.length; i++) {
            o.writeInt(militia[i]);
            o.writeDouble(firstAlert[i]);
            o.writeInt(arrivals[i]);
            o.writeInt(arrivalsInf[i]);
            o.writeBoolean(colonyMade[i]);
        }
        o.writeInt(camps.size());
        for (Camp c : camps) {
            o.writeInt(c.town);
            o.writeInt(c.sector);
            o.writeInt(c.facility);
            o.writeInt(c.people);
            o.writeFloat(c.x);
            o.writeFloat(c.y);
            o.writeUTF(c.name);
        }
        o.writeInt(colonies.size());
        for (Colony c : colonies) {
            o.writeInt(c.sector);
            o.writeInt(c.start);
            o.writeInt(c.from);
            o.writeFloat(c.x);
            o.writeFloat(c.y);
            o.writeUTF(c.name);
            o.writeBoolean(c.fallen);
        }
        o.writeInt(responders.size());
        for (int[] r : responders) {
            o.writeInt(r[0]);
            o.writeInt(r[1]);
        }
        o.writeFloat(minuteT);
        o.writeFloat(hourT);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        Sectors sec = w.sec;
        int n = in.readInt();
        for (int s = 0; s < n; s++) {
            float t = in.readFloat();
            boolean h = in.readBoolean();
            if (s < sec.n) {
                holdT[s] = t;
                sec.holdout[s] = h;
            }
        }
        n = in.readInt();
        for (int i = 0; i < n; i++) {
            int m = in.readInt();
            double fa = in.readDouble();
            int ar = in.readInt(), ai = in.readInt();
            boolean cm = in.readBoolean();
            if (i >= militia.length) continue;
            militia[i] = m;
            firstAlert[i] = fa;
            arrivals[i] = ar;
            arrivalsInf[i] = ai;
            colonyMade[i] = cm;
        }
        camps.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            Camp c = new Camp();
            c.town = in.readInt();
            c.sector = in.readInt();
            c.facility = in.readInt();
            c.people = in.readInt();
            c.x = in.readFloat();
            c.y = in.readFloat();
            c.name = in.readUTF();
            camps.add(c);
        }
        colonies.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            Colony c = new Colony();
            c.sector = in.readInt();
            c.start = in.readInt();
            c.from = in.readInt();
            c.x = in.readFloat();
            c.y = in.readFloat();
            c.name = in.readUTF();
            c.fallen = in.readBoolean();
            colonies.add(c);
        }
        responders.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) responders.add(new int[]{in.readInt(), in.readInt()});
        minuteT = in.readFloat();
        hourT = in.readFloat();
    }
}
