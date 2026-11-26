package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The news, social media, rumours, and what the public believes. People act on belief, not truth: each town has
 * a social-media panic level and a perceived danger built from what its people see, what the news (the
 * authorities' reported picture) says and what's going round online. Fear makes people leave without being told;
 * trust makes them obey curfews and orders; rumours change behaviour ("the bite isn't dangerous" and the bitten
 * hide; "the army is pulling out" and everyone runs; "the water is poisoned" and a town empties).
 */
public final class Media extends Module {
    private static final String[] OUTLETS = {"Channel 4 News", "The Daily Courier", "StateNet Radio", "Metro Live"};
    public static final int R_BITE = 0, R_ARMY = 1, R_WATER = 2, R_CURE = 3;

    /** Per settlement: online panic (0..1) and perceived danger (0..1). */
    public final float[] social, belief;
    /** How strongly "the bite isn't dangerous" is going round (0..1). */
    public float biteRumour;
    private float rumourT = 300, reportT = 180;
    private int lastReported = -1;

    public Media(World w) {
        super(w);
        int n = w.r.settlements.size();
        social = new float[n];
        belief = new float[n];
        java.util.Arrays.fill(lastRumour, -1e9f);
    }

    @Override
    public String id() {
        return "media";
    }

    /** The share of the bitten who hide it: more when trust is low, and when the rumour says it's harmless. */
    public float hidden() {
        float h = 0.08f + (1 - w.gov.trust / 100f) * 0.45f + biteRumour * 0.3f;
        return Math.max(0, Math.min(0.9f, h));
    }

    public float fear(int st) {
        if (st < 0 || st >= belief.length) return 0;
        return Math.max(0, Math.min(1, belief[st] * (1.15f - w.gov.trust / 200f)));
    }

    public String mood(int st) {
        float f = fear(st);
        return f < 0.15f ? "calm" : f < 0.35f ? "uneasy" : f < 0.6f ? "frightened" : "panicking";
    }

    @Override
    public void update(float dt) {
        Command cmd = w.command;
        float fleeAt = w.c.rule("fleeAt", 0.55f), fleeRate = w.c.rule("fleeRate", 0.002f);
        for (Region.Settlement s : w.r.settlements) {
            int st = s.id;
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN && s.kind != Region.S_VILLAGE) continue;
            float scale = s.residents * 0.008f + 30;
            float seen = Math.min(1, w.stats.zBySettlement[st] / scale);
            float told = Math.min(1, cmd.repZ[st] / scale);
            float lost = !w.strat.lit(w.r.sectorOfPx(s.cx * 16f, s.cy * 16f)) ? 0.25f : 0;
            // social media runs ahead of the truth, and fades slowly
            social[st] += (seen * 0.9f + lost - social[st] * 0.5f) * dt / 400f;
            social[st] = Math.max(0, Math.min(1, social[st]));
            float target = Math.min(1, 0.45f * seen + 0.3f * told + 0.4f * social[st] + lost * 0.5f);
            belief[st] += (target - belief[st]) * Math.min(1, dt / 120f);
            // fear: people leave on their own, unless the town is under quarantine
            float f = fear(st);
            if (f > fleeAt && !cmd.quarantined(st) && w.stats.zBySettlement[st] > 0) flee(st, (f - fleeAt) * fleeRate * dt);
        }
        if (biteRumour > 0) biteRumour = Math.max(0, biteRumour - dt / 1440f);
        // rumours grow out of fear: where people are frightened and the dead are about, and not every hour
        rumourT -= dt;
        if (rumourT <= 0) {
            int scared = -1;
            float top = w.c.rule("rumourFear", 0.3f);
            for (int st = 0; st < belief.length; st++)
                if (fear(st) > top && w.stats.zBySettlement[st] + cmdRep(st) > 0 && w.time - lastRumour[st] > 1440 * 3) {
                    top = fear(st);
                    scared = st;
                }
            if (scared >= 0 && w.gov.level >= 1) {
                rumourT = w.c.rule("rumourEvery", 720) * (0.7f + w.rng.nextFloat() * 0.6f);
                lastRumour[scared] = (float) w.time;
                rumour(scared);
            } else rumourT = 60;
        }
        reportT -= dt;
        if (reportT <= 0) {
            reportT = 180;
            report();
        }
    }

    /** A share of a town's sheltering people (and its hidden bitten) take to the roads. */
    private void flee(int st, float share) {
        Sectors sec = w.sec;
        float hid = hidden();
        for (int s = 0; s < sec.n; s++) {
            if (sec.settlement[s] != st || sec.popIn[s] == 0 || sec.zIn[s] > 0) continue;
            float want = sec.popIn[s] * share;
            int m = Math.min(sec.popIn[s], (int) want + (Rng.unit(w.seed, w.tick, s * 5L + 3) < want % 1f ? 1 : 0));
            if (m <= 0) continue;
            sec.popIn[s] -= m;
            w.strat.pending[st] += m;
            w.stats.fled += m;
            // the bitten who hide it go with them
            float carry = sec.inf[s] * share * hid;
            int ci = Math.min((int) sec.inf[s], (int) carry + (Rng.unit(w.seed, w.tick, s * 5L + 4) < carry % 1f ? 1 : 0));
            if (ci > 0) {
                sec.inf[s] -= ci;
                w.strat.pending[st] += ci;
                w.strat.infPending[st] += ci;
            }
        }
    }

    /** When each town last had a rumour going round (one at a time, not every few hours). */
    private final float[] lastRumour = new float[512];
    private float lastFigure;

    private float cmdRep(int st) {
        return w.command.repZ[st];
    }

    private void rumour(int scared) {
        Rng r = w.rng;
        float trust = w.gov.trust;
        if (trust > 55 && r.chance(0.45f)) {
            String[] good = {"Officials: the outbreak is being contained street by street.",
                    "Volunteers organise food and water for people sheltering at home.",
                    "Hospitals: anyone bitten must report for treatment. Treatment is free."};
            w.news.headline(OUTLETS[r.nextInt(OUTLETS.length)] + ": " + good[r.nextInt(good.length)], -1, -1);
            w.gov.trust += 2;
            for (int i = 0; i < social.length; i++) social[i] = Math.max(0, social[i] - 0.08f);
            return;
        }
        // a rumour; officials deny it, and the denial works as far as people trust them
        int kind = r.nextInt(4);
        float stick = 1.1f - trust / 100f;
        switch (kind) {
            case R_BITE:
                w.news.headline("RUMOUR going round " + w.r.settlements.get(scared).name + ": 'the bite isn't dangerous if you clean it'. Officials say this is false.", -1, -1);
                biteRumour = Math.min(1, biteRumour + 0.7f * stick);
                break;
            case R_ARMY:
                w.news.headline("RUMOUR in " + w.r.settlements.get(scared).name + ": the army is pulling out tonight. The government denies it. Roads are filling.", -1, -1);
                social[scared] = Math.min(1, social[scared] + 0.5f * stick);
                for (int i = 0; i < social.length; i++) social[i] = Math.min(1, social[i] + 0.1f * stick);
                break;
            case R_WATER: {
                // the most frightened town's water is said to be poisoned
                int best = scared;
                if (best < 0 || w.stats.popBySettlement[best] <= 0) return;
                Region.Settlement s = w.r.settlements.get(best);
                w.news.headline("RUMOUR: the water in " + s.name + " is poisoned. People are leaving on foot.", s.cx * 16f, s.cy * 16f);
                social[best] = Math.min(1, social[best] + 0.8f * stick);
                belief[best] = Math.min(1, belief[best] + 0.5f * stick);
                break;
            }
            default:
                w.news.headline("RUMOUR: a cure is being given out at the hospitals. Crowds are gathering.", -1, -1);
                // the infected and the healthy crowd together: the hospitals fill
                for (int s = 0; s < w.sec.n; s++) {
                    int st = w.sec.settlement[s];
                    if (st < 0 || w.sec.inf[s] < 2) continue;
                    int hs = w.strat.hospitalSector(st);
                    if (hs < 0 || hs == s) continue;
                    float m = (int) (w.sec.inf[s] * 0.3f * stick);
                    w.sec.inf[s] -= m;
                    w.sec.inf[w.sec.touch(hs)] += m;
                }
        }
        w.gov.trust -= 4 * stick;
        w.stats.rumours++;
    }

    /** A news report: the official figure, and what social media says. */
    private void report() {
        Command cmd = w.command;
        int worst = -1;
        float wz = 0;
        for (int st = 0; st < cmd.repZ.length; st++)
            if (cmd.repZ[st] > wz) {
                wz = cmd.repZ[st];
                worst = st;
            }
        if (worst < 0 || wz < 10) return;
        Region.Settlement s = w.r.settlements.get(worst);
        int truth = w.stats.zBySettlement[worst];
        String outlet = OUTLETS[(int) (w.time / 180) % OUTLETS.length];
        String official = outlet + ": officials say about " + round((int) wz) + " of the dead are in " + s.name + ".";
        // the news runs the figure when it's new: another town, or a number that has moved
        boolean moved = Math.abs(wz - lastFigure) > Math.max(20, lastFigure * 0.25f);
        if (worst != lastReported || moved) {
            lastFigure = wz;
            w.news.radio(official, s.cx * 16f, s.cy * 16f, News.CALL);
            // online the number is bigger (and sometimes closer to the truth)
            int claim = (int) Math.max(truth * (0.8f + social[worst]), wz * 2);
            w.news.radio("Online: people in " + s.name + " say there are " + round(claim) + " of the dead.", s.cx * 16f, s.cy * 16f, News.CALL);
            lastReported = worst;
        }
    }

    private static String round(int n) {
        if (n >= 10000) return News.num(n / 1000 * 1000);
        if (n >= 1000) return News.num(n / 100 * 100);
        return News.num(n);
    }

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(social.length);
        for (int i = 0; i < social.length; i++) {
            o.writeFloat(social[i]);
            o.writeFloat(belief[i]);
        }
        o.writeFloat(biteRumour);
        o.writeFloat(rumourT);
        o.writeFloat(reportT);
        o.writeInt(lastReported);
        o.writeFloat(lastFigure);
        for (int i = 0; i < social.length; i++) o.writeFloat(lastRumour[i]);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            float a = in.readFloat(), b = in.readFloat();
            if (i < social.length) {
                social[i] = a;
                belief[i] = b;
            }
        }
        biteRumour = in.readFloat();
        rumourT = in.readFloat();
        reportT = in.readFloat();
        lastReported = in.readInt();
        lastFigure = in.readFloat();
        for (int i = 0; i < social.length; i++) lastRumour[i] = in.readFloat();
    }
}
