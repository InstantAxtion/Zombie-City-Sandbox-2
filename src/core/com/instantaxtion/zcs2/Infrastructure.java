package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The things a society runs on, and what happens when they fail. Water works run water networks: lose one and,
 * after a couple of days dry, people walk out of their towns. Telephone exchanges carry the 911 calls: lose one
 * and calls take far longer, news lags and rumours spread. Every city and town keeps food (in person-days) and its
 * hospitals keep medicine: people eat; farms and the supply convoys refill them. A town that runs out of food goes
 * hungry: people leave home and take to the roads, looting starts, trust falls. Medicine slows the infected's
 * turning and saves wounded soldiers. Fuel runs the cities' vehicles; without it their cars can't refuel.
 */
public final class Infrastructure extends Module {
    /** Per settlement: seconds without water, the food shortage level (0 fine, 1 low, 2 none), looting (seconds). */
    public float[] dryT;
    public int[] shortage;
    public float[] looting;
    /** Per settlement: the facility its food is kept at (food warehouse, else the town hall), or -1. */
    public int[] store;
    private float[] farmYield; // person-days a game day from the farms round it
    private boolean[] waterWas, phoneWas;
    private int[] waterWorks, phoneWorks; // network -> facility
    private boolean inited;
    private float rumourAcc;
    /** Wounded soldiers saved by medics (for the report). */
    public int saved;
    private int[] seenLosses = new int[0];

    public Infrastructure(World w) {
        super(w);
        int ns = w.r.settlements.size();
        dryT = new float[ns];
        shortage = new int[ns];
        looting = new float[ns];
        layout();
    }

    @Override
    public String id() {
        return "infrastructure";
    }

    /** Which facility runs each network, where each town keeps its food, and what its farms grow. */
    private void layout() {
        Region r = w.r;
        int ns = r.settlements.size();
        waterWorks = new int[Math.max(1, r.waterCount)];
        phoneWorks = new int[Math.max(1, r.phoneCount)];
        java.util.Arrays.fill(waterWorks, -1);
        java.util.Arrays.fill(phoneWorks, -1);
        for (Region.Facility f : r.facilities) {
            if (f.kind == Region.F_WATER && f.network >= 0 && f.network < waterWorks.length) waterWorks[f.network] = f.id;
            if (f.kind == Region.F_PHONE && f.network >= 0 && f.network < phoneWorks.length) phoneWorks[f.network] = f.id;
        }
        waterWas = new boolean[waterWorks.length];
        phoneWas = new boolean[phoneWorks.length];
        java.util.Arrays.fill(waterWas, true);
        java.util.Arrays.fill(phoneWas, true);
        store = new int[ns];
        java.util.Arrays.fill(store, -1);
        for (Region.Settlement s : r.settlements) {
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
            int hall = -1, food = -1;
            for (Region.Facility f : r.facilities) {
                if (f.settlement != s.id) continue;
                if (f.kind == Region.F_FOODW && food < 0) food = f.id;
                if (f.kind == Region.F_HALL && hall < 0) hall = f.id;
            }
            store[s.id] = food >= 0 ? food : hall;
        }
        // farms: farmland tiles, sampled, belong to the nearest city or town
        farmYield = new float[ns];
        float perTile = w.c.rule("farmYield", 2f);
        Sectors sec = w.sec;
        for (int k = 0; k < sec.n; k++) {
            int x0 = (k % sec.sw) * Region.SECT, y0 = (k / sec.sw) * Region.SECT, farm = 0;
            for (int y = y0; y < y0 + Region.SECT; y += 2)
                for (int x = x0; x < x0 + Region.SECT; x += 2) if (r.t(x, y) == Tile.FARM) farm++;
            if (farm == 0) continue;
            int best = -1;
            float bd = Float.MAX_VALUE;
            for (Region.Settlement s : r.settlements) {
                if (store[s.id] < 0) continue;
                float dx = s.cx - (x0 + 16), dy = s.cy - (y0 + 16), d = dx * dx + dy * dy;
                if (d < bd) {
                    bd = d;
                    best = s.id;
                }
            }
            if (best >= 0) farmYield[best] += farm * 4 * perTile;
        }
    }

    /** Fills the stores at the start of a game. */
    private void init() {
        inited = true;
        float cityDays = w.c.rule("foodDays", 5), townDays = w.c.rule("townFoodDays", 4);
        for (Region.Settlement s : w.r.settlements) {
            if (store[s.id] < 0) continue;
            Region.Facility f = w.r.facilities.get(store[s.id]);
            f.food = s.residents * (s.kind == Region.S_CITY ? cityDays : townDays);
        }
        for (Region.Facility f : w.r.facilities) {
            Region.Settlement s = f.settlement >= 0 ? w.r.settlements.get(f.settlement) : null;
            boolean city = s != null && s.kind == Region.S_CITY;
            switch (f.kind) {
                case Region.F_HOSPITAL: f.med = city ? 1500 : 400; break;
                case Region.F_FUEL: f.fuel = city ? 300 : 80; break;
                case Region.F_BASE: f.fuel = Math.max(f.fuel, 500); f.med = 2000; f.food = 60000; break;
                case Region.F_AIRBASE: f.fuel = 300; f.med = 1000; break;
                case Region.F_AIRPORT: f.fuel = 200; f.med = 500; f.food = 50000; break;
                case Region.F_PORT: f.fuel = 400; f.med = 1000; f.food = 200000; break;
                case Region.F_HQ: f.med = Math.max(f.med, 600); break;
                default:
            }
        }
    }

    // ------------------------------------------------------------------ what other systems ask

    public boolean waterOut(int settlement) {
        if (settlement < 0) return false;
        int n = w.r.settlements.get(settlement).water;
        return n >= 0 && n < waterWorks.length && waterWorks[n] >= 0 && !w.strat.online(waterWorks[n]);
    }

    public boolean phoneOut(int settlement) {
        if (settlement < 0) return false;
        int n = w.r.settlements.get(settlement).phone;
        return n >= 0 && n < phoneWorks.length && phoneWorks[n] >= 0 && !w.strat.online(phoneWorks[n]);
    }

    /** How much longer a 911 call takes from this sector (the phones are down: four times as long). */
    public float callDelay(int sector) {
        return phoneOut(w.sec.settlement[sector]) ? 4f : 1f;
    }

    /** How much slower the infected turn here (medicine, clean water, a working hospital: up to a third). */
    public float turnSlow(int settlement) {
        if (settlement < 0 || waterOut(settlement)) return 1f;
        for (Region.Facility f : w.r.facilities)
            if (f.kind == Region.F_HOSPITAL && f.settlement == settlement && w.strat.online(f.id) && f.med > 0) return 1.3f;
        return 1f;
    }

    /** Days of food a settlement has left (Float.MAX_VALUE where it isn't counted). */
    public float foodDays(int settlement) {
        if (settlement < 0 || store[settlement] < 0) return Float.MAX_VALUE;
        int pop = Math.max(1, w.stats.popBySettlement[settlement]);
        return w.r.facilities.get(store[settlement]).food / pop;
    }

    // ------------------------------------------------------------------ every second

    @Override
    public void update(float dt) {
        if (!inited) init();
        networks();
        Region r = w.r;
        float day = 1440f;
        int dryDays = (int) w.c.rule("waterDays", 2);
        int phonesOut = 0;
        for (Region.Settlement s : r.settlements) {
            int st = s.id;
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
            int pop = w.stats.popBySettlement[st];
            if (phoneOut(st)) phonesOut++;
            // water
            if (waterOut(st)) dryT[st] += dt;
            else dryT[st] = Math.max(0, dryT[st] - dt * 2);
            boolean dry = dryT[st] > dryDays * day;
            // food: people eat, the farms bring some in
            int sh = 0;
            if (store[st] >= 0) {
                Region.Facility f = r.facilities.get(store[st]);
                f.food += farmYield[st] * dt / day;
                f.food -= pop * w.c.rule("eatPerDay", 1f) * dt / day;
                if (f.food < 0) f.food = 0;
                float days = f.food / Math.max(1, pop);
                sh = f.food <= 0 && pop > 0 ? 2 : days < 1 ? 1 : 0;
            }
            if (sh != shortage[st] && pop > 500) {
                if (sh == 2) w.news.headline("FOOD RUNS OUT in " + s.name + ". People leave home to find something to eat.", s.cx * 16f, s.cy * 16f);
                else if (sh == 1 && shortage[st] == 0) w.news.radio(s.name + ": less than a day of food left", s.cx * 16f, s.cy * 16f, News.LOSS);
            }
            shortage[st] = sh;
            // the hungry and the thirsty take to the roads; looting starts; trust falls
            if (sh == 2 || dry) {
                float rate = (sh == 2 ? 0.0002f : 0) + (dry ? 0.0003f : 0);
                for (int k = 0; k < w.sec.n; k++) {
                    if (w.sec.settlement[k] != st || w.sec.popIn[k] == 0) continue;
                    float want = w.sec.popIn[k] * rate * dt;
                    int m = Math.min(w.sec.popIn[k], (int) want + (Rng.unit(w.seed, w.tick, k * 5L + 3) < want % 1f ? 1 : 0));
                    w.sec.popIn[k] -= m;
                    w.strat.pending[st] += m;
                    w.sec.panic[w.sec.touch(k)] = Math.min(1, w.sec.panic[k] + 0.01f * dt);
                }
                looting[st] += dt;
                w.gov.trust = Math.max(5, w.gov.trust - 0.0005f * dt);
            } else looting[st] = Math.max(0, looting[st] - dt);
            if (dry && dryT[st] - dt <= dryDays * day)
                w.news.headline(s.name + " has had no water for " + dryDays + " days. People are walking out of the town.", s.cx * 16f, s.cy * 16f);
        }
        // phones down: rumours come faster
        if (phonesOut > 0) {
            rumourAcc += dt * phonesOut * 0.5f;
            if (rumourAcc >= 1) {
                w.gov.rumourT -= (int) rumourAcc;
                rumourAcc -= (int) rumourAcc;
            }
        }
        medicine(dt);
        fuel(dt);
    }

    /** Announces networks that went down or came back. */
    private void networks() {
        for (int n = 0; n < waterWorks.length; n++) {
            if (waterWorks[n] < 0) continue;
            boolean on = w.strat.online(waterWorks[n]);
            if (on != waterWas[n]) {
                Region.Facility f = w.r.facilities.get(waterWorks[n]);
                if (!on) w.news.headline("The water stops: " + f.name + " is overrun.", f.x, f.y);
                else w.news.headline("Water flows again from " + f.name, f.x, f.y);
                waterWas[n] = on;
            }
        }
        for (int n = 0; n < phoneWorks.length; n++) {
            if (phoneWorks[n] < 0) continue;
            boolean on = w.strat.online(phoneWorks[n]);
            if (on != phoneWas[n]) {
                Region.Facility f = w.r.facilities.get(phoneWorks[n]);
                if (!on) w.news.headline("Phones dead across the area: " + f.name + " is lost. 911 calls aren't getting through.", f.x, f.y);
                else w.news.radio("Phone lines restored from " + f.name, f.x, f.y, News.COMMAND);
                phoneWas[n] = on;
            }
        }
    }

    /** Hospitals use medicine on the infected; formations' medics save some of the wounded. */
    private void medicine(float dt) {
        float perInf = w.c.rule("medPerInfected", 0.5f);
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_HOSPITAL || f.med <= 0 || !w.strat.online(f.id)) continue;
            int hs = w.r.sectorOfPx(f.x, f.y);
            f.med = Math.max(0, f.med - (w.sec.inf[hs] * perInf + 2) * dt / 1440f);
        }
        java.util.List<Forces.Formation> fs = w.forces.formations;
        if (seenLosses.length < fs.size()) seenLosses = java.util.Arrays.copyOf(seenLosses, fs.size());
        for (int i = 0; i < fs.size(); i++) {
            Forces.Formation fm = fs.get(i);
            int fresh = fm.losses - seenLosses[i];
            seenLosses[i] = fm.losses;
            if (fresh <= 0 || !fm.alive || fm.hq.med < 2) continue;
            int back = Math.min((int) (fresh * 0.25f), (int) (fm.hq.med / 2));
            for (int k = 0; k < back; k++) {
                // the commonest armed type in the formation gets the patched-up soldier back
                Content.Unit best = null;
                for (Content.Unit u : w.c.units)
                    if (u.weapon != null && (best == null || fm.hq.pool[u.index] > fm.hq.pool[best.index])) best = u;
                if (best == null) break;
                fm.hq.pool[best.index]++;
                w.stats.armedDeaths[fm.faction] = Math.max(0, w.stats.armedDeaths[fm.faction] - 1);
                fm.hq.med -= 2;
                saved++;
            }
        }
    }

    /** The cities' vehicles burn their fuel depot's stock; formations on the move burn theirs. */
    private void fuel(float dt) {
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_FUEL || f.fuel <= 0) continue;
            Region.Settlement s = w.r.settlements.get(f.settlement);
            // (rationed under martial law: half as much for the towns, the rest kept for the army)
            f.fuel = Math.max(0, f.fuel - s.residents / 100000f * 6 * dt / 1440f * (w.gov.martialLaw() ? 0.5f : 1));
        }
        for (Forces.Formation fm : w.forces.formations) {
            if (!fm.alive || fm.state != Forces.MOVING) continue;
            fm.hq.fuel -= w.c.rule("formationFuel", 0.02f) * dt;
            if (fm.hq.fuel <= 0) {
                fm.hq.fuel = 0;
                fm.state = Forces.HOLDING;
                fm.route = null;
                w.news.radio(fm.name + ": out of fuel, halted", fm.hq.x, fm.hq.y, News.LOSS);
            }
        }
    }

    // ------------------------------------------------------------------ saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeBoolean(inited);
        o.writeInt(dryT.length);
        for (int i = 0; i < dryT.length; i++) {
            o.writeFloat(dryT[i]);
            o.writeInt(shortage[i]);
            o.writeFloat(looting[i]);
        }
        o.writeInt(waterWas.length);
        for (boolean b : waterWas) o.writeBoolean(b);
        o.writeInt(phoneWas.length);
        for (boolean b : phoneWas) o.writeBoolean(b);
        o.writeFloat(rumourAcc);
        o.writeInt(saved);
        o.writeInt(seenLosses.length);
        for (int v : seenLosses) o.writeInt(v);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        inited = in.readBoolean();
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            float d = in.readFloat();
            int s = in.readInt();
            float l = in.readFloat();
            if (i < dryT.length) {
                dryT[i] = d;
                shortage[i] = s;
                looting[i] = l;
            }
        }
        n = in.readInt();
        for (int i = 0; i < n; i++) {
            boolean b = in.readBoolean();
            if (i < waterWas.length) waterWas[i] = b;
        }
        n = in.readInt();
        for (int i = 0; i < n; i++) {
            boolean b = in.readBoolean();
            if (i < phoneWas.length) phoneWas[i] = b;
        }
        rumourAcc = in.readFloat();
        saved = in.readInt();
        seenLosses = new int[in.readInt()];
        for (int i = 0; i < seenLosses.length; i++) seenLosses[i] = in.readInt();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        if (cam.zoom > 0.5f) return;
        float dp = cam.dp, r = 7 * dp;
        for (Region.Settlement s : w.r.settlements) {
            int st = s.id;
            boolean dry = waterOut(st), phone = phoneOut(st), food = shortage[st] > 0;
            if (!dry && !phone && !food) continue;
            if (s.kind != Region.S_CITY && cam.zoom < 0.04f) continue;
            float x = cam.toScreenX(s.cx * 16f), y = cam.toScreenY(s.cy * 16f) - (s.kind == Region.S_CITY ? 26 : 20) * dp;
            if (x < -60 * dp || y < -40 * dp || x > cam.sw + 60 * dp || y > cam.sh + 40 * dp) continue;
            int n = (food ? 1 : 0) + (dry ? 1 : 0) + (phone ? 1 : 0);
            float bx = x - (n - 1) * r * 1.2f;
            // only where they cover no name or other mark
            if (!view.place(bx - r * 1.3f, y - r * 1.3f, bx + (n - 1) * r * 2.4f + r * 1.3f, y + r * 1.3f)) continue;
            if (food) {
                badge(g, bx, y, r, shortage[st] == 2 ? 0xFFE04030 : 0xFFE0A030, "F");
                bx += r * 2.4f;
            }
            if (dry) {
                badge(g, bx, y, r, 0xFF3070D0, "W");
                bx += r * 2.4f;
            }
            if (phone) badge(g, bx, y, r, 0xFF8060B0, "T");
        }
    }

    /** A warning disc: something this town has run out of (food, water, phones), struck through. */
    private static void badge(Gfx g, float x, float y, float r, int color, String letter) {
        g.color(0xE0000000);
        g.fillCircle(x, y, r * 1.2f);
        g.color(color);
        g.fillCircle(x, y, r);
        g.color(0xFFFFFFFF);
        g.text(letter, x, y + r * 0.45f, r * 1.25f, Gfx.ALIGN_CENTER, true);
        g.color(0xE0FF4040);
        g.line(x - r * 0.75f, y + r * 0.75f, x + r * 0.75f, y - r * 0.75f, Math.max(1.5f, r * 0.22f));
    }
}
