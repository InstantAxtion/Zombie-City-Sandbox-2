package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Where people are by the hour. Each sector has a weight as a place of work (offices, shops, schools, industry, the
 * centre) and a weight as a busy street; by day a share of each town's people (rule lifeFlowShare at its height)
 * moves out of the homes into the places of work, and back in the evening, so an outbreak at 3 pm hits the offices
 * and one at 3 am the homes (rule lifeFlow; the street-level life reads the weights either way).
 *
 * Only quiet sectors (no dead or infected indoors, no panic, nothing hot) send or take anyone: once the dead are in
 * a street its people stay barricaded where they are. Towns are brought to their hour's spread in turn, each once a
 * game hour in a phase fixed by its id, so the work is spread evenly over the steps (fast-forward too). A sector in a
 * sleeping district changes without waking it (SectorIndex.addPop keeps the frozen totals right), so the quiet
 * country stays cheap.
 */
final class Commute {
    private final World w;
    /** Per sector: weight as a place of work, weight of its traffic (0..1), weight of its parked cars (0..1). */
    private float[] jobs, busy, park;
    /** Each town's total job weight. */
    private double[] jobsOf;
    private double lastT = -1;
    /** People moved so far (for probes and tests). */
    long moved;

    Commute(World w) {
        this.w = w;
    }

    float busy(int s) {
        weights();
        return busy[s];
    }

    float parkWeight(int s) {
        weights();
        return park[s];
    }

    float jobs(int s) {
        weights();
        return jobs[s];
    }

    /** The share of a town's people at work at hour h, as a fraction of the rule's height. */
    static float atWork(float h) {
        if (h < 5.5f) return 0;
        if (h < 9) return (h - 5.5f) / 3.5f;
        if (h < 16) return 1;
        if (h < 19) return 1 - (h - 16) / 3f * 0.85f;
        if (h < 22) return 0.15f - (h - 19) / 3f * 0.15f;
        return 0;
    }

    private void weights() {
        if (jobs != null) return;
        Sectors sec = w.sec;
        int n = sec.n;
        jobs = new float[n];
        busy = new float[n];
        park = new float[n];
        Region r = w.r;
        if (r.nation != null) {
            Nation na = r.nation;
            // by district type: the centre and the campuses are where people go; the suburbs are where they sleep
            float[] job = {0.02f, 6f, 2f, 1.4f, 0.25f, 3f, 0.3f, 3.5f, 0.1f};
            float[] traffic = {0.08f, 1f, 0.8f, 0.6f, 0.3f, 0.45f, 0.15f, 0.5f, 0.15f};
            float[] parkW = {0.05f, 0.9f, 1f, 0.8f, 0.6f, 0.3f, 0.1f, 0.5f, 0.3f};
            for (int s = 0; s < n; s++) {
                int d = na.district[s];
                float dens = 0.4f + (na.density[s] & 15) / 15f;
                jobs[s] = sec.settlement[s] >= 0 ? job[d] * dens * 1000 : 0;
                busy[s] = Math.min(1, traffic[d] * (0.6f + 0.4f * dens));
                park[s] = parkW[d];
            }
        } else {
            // today's maps: from the buildings round each sector's doors
            float[] area = new float[n];
            for (int b = 0; b < r.bCount; b++) {
                if (w.doorX[b] < 0) continue;
                int s = r.sectorOfPx(w.doorX[b], w.doorY[b]);
                float a = r.bw[b] * r.bh[b] * Math.max(1, r.bfloors[b]);
                float k;
                switch (r.bkind[b]) {
                    case Region.B_OFFICE: case Region.B_TOWER: case Region.B_GOV: case Region.B_HALL: k = 1.6f; break;
                    case Region.B_SHOP: case Region.B_MALL: k = 1.2f; break;
                    case Region.B_SCHOOL: case Region.B_HOSPITAL: k = 1.4f; break;
                    case Region.B_WAREHOUSE: case Region.B_DEPOT: case Region.B_LAB: case Region.B_POWER: k = 0.7f; break;
                    case Region.B_STATION: case Region.B_TERMINAL: case Region.B_POLICE: case Region.B_FIRE: k = 0.8f; break;
                    default: k = 0;
                }
                jobs[s] += a * k;
                area[s] += a;
            }
            float max = 1;
            for (int s = 0; s < n; s++) max = Math.max(max, area[s] + jobs[s]);
            for (int s = 0; s < n; s++) {
                float act = (area[s] + jobs[s]) / max;
                busy[s] = sec.settlement[s] < 0 && sec.home[s] == 0 ? 0.08f : Math.min(1, 0.12f + (float) Math.sqrt(act) * 0.95f);
                park[s] = Math.min(1, (float) Math.sqrt(area[s] / max) * 1.3f);
                if (sec.settlement[s] < 0) jobs[s] = 0;
            }
        }
        jobsOf = new double[r.settlements.size()];
        for (int s = 0; s < n; s++) if (sec.settlement[s] >= 0) jobsOf[sec.settlement[s]] += jobs[s];
    }

    /** A sector people can come and go from: nothing of the outbreak in it. */
    private boolean calm(int s) {
        Sectors sec = w.sec;
        return sec.zIn[s] == 0 && sec.inf[s] <= 0 && sec.panic[s] <= 0 && sec.hot[s] <= 0 && w.sp.secZ[s] == 0
                && (sec.holdout == null || !sec.holdout[s]);
    }

    private int[] want = new int[64];

    /** Once a second (or once a strategic step): the towns whose turn it is are brought to the hour's spread. */
    void update(float dt) {
        weights();
        double t = w.time;
        if (lastT < 0 || t < lastT) lastT = t - dt;
        float period = 60; // a game hour
        java.util.List<Region.Settlement> sts = w.r.settlements;
        for (int st = 0; st < sts.size(); st++) {
            double ph = (st * 37 % 60) * (period / 60.0);
            if (Math.floor((t + ph) / period) == Math.floor((lastT + ph) / period)) continue;
            spread(st);
        }
        lastT = t;
    }

    private void spread(int st) {
        Sectors sec = w.sec;
        SectorIndex idx = sec.idx;
        int[] list = idx.bySettlement[st];
        if (list.length < 2 || jobsOf[st] <= 0) return;
        Region.Settlement S = w.r.settlements.get(st);
        if (S.kind != Region.S_CITY && S.kind != Region.S_TOWN && S.kind != Region.S_VILLAGE) return;
        // closed streets and shelter orders keep everyone at home; evacuations too
        boolean stayIn = w.gov.streetsClosed() || w.command.sheltering(st) || w.gov.evacuating(st);
        float share = stayIn ? 0 : w.rule("lifeFlowShare", 0.4f) * atWork(w.hour());
        // the calm sectors' people and homes, and their job weight
        long people = 0, homes = 0;
        double jobsC = 0;
        for (int s : list) {
            if (!calm(s)) continue;
            people += sec.popIn[s];
            homes += sec.home[s];
            jobsC += jobs[s];
        }
        if (people <= 0 || homes <= 0) return;
        if (want.length < list.length) want = new int[list.length];
        // the hour's spread of those people: the homes' share, and the rest by job weight
        long sum = 0;
        int last = -1;
        for (int k = 0; k < list.length; k++) {
            int s = list[k];
            if (!calm(s)) {
                want[k] = sec.popIn[s];
                continue;
            }
            double atHome = (double) sec.home[s] / homes * (1 - share);
            double atJob = jobsC > 0 ? jobs[s] / jobsC * share : (double) sec.home[s] / homes * share;
            want[k] = (int) Math.floor(people * (atHome + atJob));
            sum += want[k];
            last = k;
        }
        // (what rounding left goes to the calm sector with most homes... simply the last calm one)
        if (last >= 0) want[last] += (int) (people - sum);
        // surplus to deficit, in order: a pair of cursors over the list
        int i = 0, j = 0;
        while (true) {
            while (i < list.length && sec.popIn[list[i]] <= want[i]) i++;
            while (j < list.length && sec.popIn[list[j]] >= want[j]) j++;
            if (i >= list.length || j >= list.length) break;
            int a = list[i], b = list[j];
            int m = Math.min(sec.popIn[a] - want[i], want[j] - sec.popIn[b]);
            idx.addPop(a, -m);
            idx.addPop(b, m);
            moved += m;
        }
    }

    void save(DataOutputStream o) throws IOException {
        o.writeDouble(lastT);
        o.writeLong(moved);
    }

    void load(DataInputStream in) throws IOException {
        lastT = in.readDouble();
        moved = in.readLong();
    }
}
