package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The dead as a living system. Hunger: the counted dead that haven't fed get restless, wander further and faster and
 * gather into hordes sooner; feeding calms them; over weeks the starving fall apart. Nests: city streets full of the
 * dead and empty of people for days go dormant (unless noise or the living wake them), and new strains arise there.
 * Strains belong to places: each sector carries its dominant strain, spread by migration and hordes, and used when the
 * dead come out as individuals. Terrain: the dead flow along valleys, rivers and roads and avoid mountains.
 * Infection travels with people: bitten soldiers in a formation turn wherever it has gone.
 */
public final class Ecology extends Module {
    public static final String[] STRAIN_NEWS = {"", "RUNNERS: a fast strain of the dead is spreading from ",
            "ARMOURED: the dead in riot gear and helmets, shrugging off headshots, are coming out of ",
            "STALKERS: a strain that hunts at night has appeared in "};

    /** 0 = fed, 1 = starving. */
    public final float[] hunger;
    /** Seconds a sector has been full of the dead and empty of people. */
    public final float[] nestT;
    public final boolean[] nest;
    /** The dominant strain of the dead in each sector (0 = the original). */
    public final byte[] strain;
    /** Strains carried by hordes, by horde id. */
    private final java.util.Map<Integer, Integer> hordeStrain = new java.util.HashMap<Integer, Integer>();
    /** How hard each sector is for the dead to cross: 1 = open lowland or road, more for forest, hills, mountains. */
    public final float[] cost;
    /** Bitten soldiers hidden inside each formation (by formation id), and the losses seen last time. */
    private int[] fmInfected = new int[0], fmLosses = new int[0];
    private float nestAnnounced;

    public Ecology(World w) {
        super(w);
        int n = w.sec.n;
        hunger = new float[n];
        nestT = new float[n];
        nest = new boolean[n];
        strain = new byte[n];
        cost = new float[n];
        terrain();
    }

    @Override
    public String id() {
        return "ecology";
    }

    /** Sector crossing costs from the tiles: roads and open fields are easy, forest slower, rock and water hard. */
    private void terrain() {
        Region r = w.r;
        int sw = w.sec.sw;
        for (int s = 0; s < w.sec.n; s++) {
            int x0 = (s % sw) * Region.SECT, y0 = (s / sw) * Region.SECT;
            float sum = 0;
            int n = 0, roads = 0;
            for (int y = y0; y < y0 + Region.SECT; y += 4)
                for (int x = x0; x < x0 + Region.SECT; x += 4) {
                    int t = r.t(x, y);
                    float c;
                    if (Tile.roadish(t) || t == Tile.RAIL) {
                        c = 0.7f;
                        roads++;
                    } else if (t == Tile.ROCK) c = 4f;
                    else if (Tile.water(t)) c = 3f;
                    else if (t == Tile.FOREST) c = 1.5f;
                    else if (t == Tile.BUILDING) c = 1.3f;
                    else c = 1f;
                    sum += c;
                    n++;
                }
            float avg = n == 0 ? 1 : sum / n;
            // a road through the sector makes it a corridor
            if (roads > n / 12) avg *= 0.75f;
            cost[s] = Math.max(0.5f, avg);
        }
    }

    // ------------------------------------------------------------------ hooks used by the rest of the simulation

    /** How much faster (or slower) the counted dead of a sector migrate: hunger, nests, deep winter. */
    public float migrateFactor(int s) {
        float f = 1 + hunger[s] * w.c.rule("hungerRestless", 2f);
        if (nest[s] && w.sec.hot[s] <= 0 && !noiseNear(s)) f *= 0.08f;
        if (w.weather.temperature() < -10) f *= 0.2f;
        return f;
    }

    /** The dead a sector needs before they set off as a horde (fewer when hungry). */
    public int hordeThreshold(int s) {
        return (int) (w.c.rule("hordeSize", 300) * (1 - 0.6f * hunger[s]));
    }

    /** People were bitten here: the dead have fed. */
    public void fed(int s, int bites) {
        if (bites > 0) hunger[s] = Math.max(0, hunger[s] - bites * 0.05f);
    }

    /** m of the dead moved from sector a to b: their strain goes with them. */
    public void carry(int a, int b, int m, int zBefore) {
        if (m <= 0 || strain[a] == strain[b]) return;
        float share = m / (float) (m + Math.max(0, zBefore));
        if (Rng.unit(w.seed, w.tick, a * 31L + b) < share) strain[b] = strain[a];
        hunger[b] = Math.max(hunger[b], hunger[a] * share);
    }

    /** A sector's strain when a horde forms there or arrives. */
    public void hordeFormed(Strategic.Horde h, int s) {
        hordeStrain.put(h.id, (int) strain[s]);
    }

    public void hordeArrived(Strategic.Horde h, int s, int zBefore) {
        Integer st = hordeStrain.remove(h.id);
        if (st == null) return;
        if (h.count >= zBefore) strain[s] = (byte) (int) st;
    }

    public void hordesMerged(Strategic.Horde into, Strategic.Horde from) {
        Integer a = hordeStrain.get(into.id), b = hordeStrain.remove(from.id);
        if (b != null && (a == null || from.count > into.count)) hordeStrain.put(into.id, b);
    }

    /** Somewhere new infections were brought in (a camp, a hospital, a column): seed them. */
    public void seedInfection(int sector, int n) {
        if (sector < 0 || n <= 0) return;
        w.sec.inf[sector] += n;
        w.stats.infectedTotal += n;
    }

    /** How a horde weighs a step into sector t towards its target (lower is better). */
    public float stepScore(int t, float distToTarget) {
        return distToTarget + (cost[t] - 1) * 220;
    }

    private boolean noiseNear(int s) {
        Effects fx = w.fx;
        float cx = w.sec.cx(s), cy = w.sec.cy(s);
        for (int k = 0; k < fx.noiseN; k++) {
            float dx = fx.noiseX[k] - cx, dy = fx.noiseY[k] - cy;
            float reach = fx.noiseLoud[k] * Region.NAV * Tile.SIZE + 256;
            if (dx * dx + dy * dy < reach * reach) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the strain of the dead coming out here

    /** The weight of a zombie type for the dead coming out in sector s. */
    public float weight(Content.ZType z, int s) {
        if (z.placedOnly) return 0;
        int st = s >= 0 ? strain[s] : w.strat.strain;
        if (z.strain > st) return z.weight;
        if (z.strain > 0) return (z.night && !w.night()) ? 0 : (z.strain == st ? 30 : 10);
        return z.weight;
    }

    // ------------------------------------------------------------------ every second (or strategic step)

    @Override
    public void update(float dt) {
        Sectors sec = w.sec;
        float hungerTime = w.c.rule("hungerDays", 2f) * 1440f;
        float starve = w.c.rule("starvePerDay", 0.02f) / 1440f;
        float nestDays = w.c.rule("nestDays", 2f) * 1440f;
        int nestMin = (int) w.c.rule("nestMin", 400);
        float strainDays = w.c.rule("strainDays", 1.5f) * 1440f;
        for (int s = 0; s < sec.n; s++) {
            int z = sec.zIn[s] + w.sp.secZ[s];
            if (z <= 0) {
                hunger[s] = Math.max(0, hunger[s] - dt / hungerTime);
                if (nest[s]) nest[s] = false;
                nestT[s] = 0;
                if (z == 0 && strain[s] != 0 && Rng.unit(w.seed, w.tick, s) < dt / 86400f) strain[s] = 0;
                continue;
            }
            // nobody to eat: hunger grows; it ebbs where people are being found
            if (sec.popIn[s] == 0 && w.sp.secCiv[s] == 0) hunger[s] = Math.min(1, hunger[s] + dt / hungerTime);
            // the starving fall apart over the weeks
            if (hunger[s] > 0.5f && sec.zIn[s] > 0) {
                float rot = sec.zIn[s] * starve * dt * (hunger[s] - 0.5f) * 2;
                int lost = (int) rot + (Rng.unit(w.seed, w.tick, s * 5L + 1) < rot % 1f ? 1 : 0);
                if (lost > 0) {
                    lost = Math.min(lost, sec.zIn[s]);
                    sec.zIn[s] -= lost;
                    w.stats.rotted += lost;
                }
            }
            // nests: settled streets full of the dead and empty of people
            boolean town = sec.settlement[s] >= 0;
            if (town && sec.zIn[s] >= nestMin && sec.popIn[s] == 0) {
                nestT[s] += dt;
                if (!nest[s] && nestT[s] >= nestDays) {
                    nest[s] = true;
                    if (w.time - nestAnnounced > 1440) {
                        nestAnnounced = (float) w.time;
                        w.news.radio("Recon: the dead have nested in " + place(s) + ". They lie still until something wakes them.", sec.cx(s), sec.cy(s), News.COMMAND);
                    }
                }
                // a new strain can arise in a nest
                if (nest[s] && strain[s] < 3 && Rng.unit(w.seed, w.tick, s * 7L + 3) < dt / strainDays * 0.25f) {
                    strain[s]++;
                    if (strain[s] > w.strat.strain) {
                        w.strat.strain = strain[s];
                        w.news.headline("New strain. " + STRAIN_NEWS[strain[s]] + place(s), sec.cx(s), sec.cy(s));
                    }
                }
            } else {
                nestT[s] = Math.max(0, nestT[s] - dt * 2);
                if (nest[s] && (sec.zIn[s] < nestMin / 4 || sec.popIn[s] > 0)) nest[s] = false;
            }
        }
        infectedFormations(dt);
        splitHordes();
    }

    private String place(int s) {
        int st = w.sec.settlement[s];
        return st >= 0 ? w.r.settlements.get(st).name : "the countryside";
    }

    /** Soldiers bitten in a fight hide it; they turn wherever their formation is by then. */
    private void infectedFormations(float dt) {
        java.util.List<Forces.Formation> fs = w.forces.formations;
        if (fmInfected.length < fs.size()) {
            fmInfected = java.util.Arrays.copyOf(fmInfected, fs.size());
            int old = fmLosses.length;
            fmLosses = java.util.Arrays.copyOf(fmLosses, fs.size());
            for (int i = old; i < fs.size(); i++) fmLosses[i] = fs.get(i).losses;
        }
        float hide = w.c.rule("hiddenBitten", 0.12f);
        for (int i = 0; i < fs.size(); i++) {
            Forces.Formation f = fs.get(i);
            int d = f.losses - fmLosses[i];
            fmLosses[i] = f.losses;
            if (!f.alive) {
                fmInfected[i] = 0;
                continue;
            }
            if (d > 0) fmInfected[i] += (int) (d * hide) + (Rng.unit(w.seed, w.tick, i * 3L) < (d * hide) % 1f ? 1 : 0);
            if (fmInfected[i] <= 0) continue;
            float turn = fmInfected[i] * dt / w.c.rule("turnTime", 40) * 0.1f;
            int t = Math.min(fmInfected[i], (int) turn + (Rng.unit(w.seed, w.tick, i * 3L + 1) < turn % 1f ? 1 : 0));
            if (t <= 0) continue;
            fmInfected[i] -= t;
            int turned = 0;
            for (int k = 0; k < t; k++) {
                // one of the formation's people is gone (and walks again among them)
                for (Content.Unit u : w.c.units) {
                    if (f.hq.pool == null || f.hq.pool[u.index] <= 0 || u.weapon == null) continue;
                    f.hq.pool[u.index]--;
                    turned++;
                    break;
                }
            }
            if (turned > 0) {
                int s = w.r.sectorOfPx(f.hq.x, f.hq.y);
                w.sec.zIn[s] += turned;
                w.stats.armedDeaths[f.faction] += turned;
                f.losses += turned;
                fmLosses[i] += turned;
                if (turned >= 5) w.news.radio(f.name + ": bitten soldiers have turned inside the lines", f.hq.x, f.hq.y, News.LOSS);
            }
        }
    }

    /** A very big horde with two places worth going to splits between them. */
    private void splitHordes() {
        Strategic st = w.strat;
        Sectors sec = w.sec;
        int min = (int) w.c.rule("hordeSplit", 3000);
        int n = st.hordes.size();
        for (int k = 0; k < n; k++) {
            Strategic.Horde h = st.hordes.get(k);
            if (!h.alive || h.count < min || h.keepT > 0) continue;
            int s = w.r.sectorOfPx(h.x, h.y), sx = s % sec.sw, sy = s / sec.sw;
            int best = -1, second = -1;
            float ba = 0, sa = 0;
            for (int t = 0; t < sec.n; t++) {
                if (sec.popIn[t] == 0) continue;
                int dx = t % sec.sw - sx, dy = t / sec.sw - sy;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > 24) continue;
                float a = (sec.popIn[t] + 50) / (1 + d * d);
                if (a > ba) {
                    // the old best becomes the second if it's well apart
                    if (best >= 0 && apart(best, t) >= 6) {
                        second = best;
                        sa = ba;
                    }
                    ba = a;
                    best = t;
                } else if (a > sa && apart(best, t) >= 6) {
                    sa = a;
                    second = t;
                }
            }
            if (best < 0 || second < 0 || sa < ba * 0.35f) {
                h.keepT = 600;
                continue;
            }
            Strategic.Horde o = st.addHorde(h.x + 60, h.y + 60, h.count / 2);
            h.count -= o.count;
            h.target = best;
            h.tx = sec.cx(best);
            h.ty = sec.cy(best);
            o.target = second;
            o.tx = sec.cx(second);
            o.ty = sec.cy(second);
            h.keepT = o.keepT = 1800;
            Integer strn = hordeStrain.get(h.id);
            if (strn != null) hordeStrain.put(o.id, strn);
            w.news.radio("Recon: the horde has split in two, one half for " + place(best) + ", the other for " + place(second), h.x, h.y, News.COMMAND);
        }
    }

    private int apart(int a, int b) {
        int sw = w.sec.sw;
        return Math.max(Math.abs(a % sw - b % sw), Math.abs(a / sw - b / sw));
    }

    // ------------------------------------------------------------------ saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        int n = w.sec.n;
        o.writeInt(n);
        for (int s = 0; s < n; s++) {
            o.writeFloat(hunger[s]);
            o.writeFloat(nestT[s]);
            o.writeBoolean(nest[s]);
            o.writeByte(strain[s]);
        }
        o.writeInt(hordeStrain.size());
        java.util.List<Integer> keys = new java.util.ArrayList<Integer>(hordeStrain.keySet());
        java.util.Collections.sort(keys);
        for (int k : keys) {
            o.writeInt(k);
            o.writeInt(hordeStrain.get(k));
        }
        o.writeInt(fmInfected.length);
        for (int i = 0; i < fmInfected.length; i++) {
            o.writeInt(fmInfected[i]);
            o.writeInt(fmLosses[i]);
        }
        o.writeFloat(nestAnnounced);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        int n = in.readInt();
        for (int s = 0; s < n; s++) {
            float hu = in.readFloat(), nt = in.readFloat();
            boolean ne = in.readBoolean();
            byte st = in.readByte();
            if (s < hunger.length) {
                hunger[s] = hu;
                nestT[s] = nt;
                nest[s] = ne;
                strain[s] = st;
            }
        }
        hordeStrain.clear();
        int m = in.readInt();
        for (int k = 0; k < m; k++) hordeStrain.put(in.readInt(), in.readInt());
        int f = in.readInt();
        fmInfected = new int[f];
        fmLosses = new int[f];
        for (int i = 0; i < f; i++) {
            fmInfected[i] = in.readInt();
            fmLosses[i] = in.readInt();
        }
        nestAnnounced = in.readFloat();
    }

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        // nests, from high up: a dark red ring round the sector, the strain's number in it
        if (cam.zoom > 0.5f) return;
        float cs = Region.SECT * Tile.SIZE, dp = cam.dp;
        for (int s = 0; s < w.sec.n; s++) {
            if (!nest[s]) continue;
            float x = cam.toScreenX(w.sec.cx(s)), y = cam.toScreenY(w.sec.cy(s));
            if (x < -50 * dp || y < -50 * dp || x > cam.sw + 50 * dp || y > cam.sh + 50 * dp) continue;
            float r = Math.max(7 * dp, cs * cam.zoom * 0.45f);
            g.color(0x90000000);
            g.strokeCircle(x, y, r, 3.5f * dp);
            g.color(0xE0B02020);
            g.strokeCircle(x, y, r, 1.8f * dp);
            if (strain[s] > 0) view.label(g, "S" + strain[s], x, y + 4 * dp, 10.5f, 0xFFFFB0B0, Gfx.ALIGN_CENTER, true);
        }
    }
}
