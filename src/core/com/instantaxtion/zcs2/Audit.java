package com.instantaxtion.zcs2;

/**
 * Checks that the world is in a sound state: everyone is in exactly one place, crews and squads agree with the
 * people in them, nothing is off the map or not-a-number, everyone on the map stands in an active chunk. Built in from day one (the first game found most of its
 * worst bugs only once such checks existed). Returns a description of the first few problems, or "" if none.
 */
public final class Audit {
    public static String check(World w) {
        StringBuilder sb = new StringBuilder();
        Ents e = w.e;
        float max = w.r.px();
        int[] seenInVehicle = new int[e.top];
        int problems = 0;
        for (int v = 0; v < w.veh.top; v++) {
            if (w.veh.state[v] == Vehicles.V_NONE) {
                if (w.veh.crewN[v] != 0 && problems++ < 5) sb.append("vehicle ").append(v).append(" gone with crew\n");
                continue;
            }
            for (int k = 0; k < w.veh.crewN[v]; k++) {
                int m = w.veh.crew[v][k];
                if (e.loc[m] != Ents.ABOARD || e.vehicle[m] != v) {
                    if (problems++ < 5) sb.append("crew ").append(m).append(" of vehicle ").append(v).append(" is loc ").append(e.loc[m]).append('\n');
                }
                seenInVehicle[m]++;
            }
            if (Float.isNaN(w.veh.x[v]) || Float.isNaN(w.veh.y[v])) if (problems++ < 5) sb.append("vehicle ").append(v).append(" NaN\n");
        }
        int[] inSquads = new int[e.top];
        for (Squads.Squad s : w.sq.list) {
            if (!s.alive) continue;
            for (int k = 0; k < s.n; k++) {
                int m = s.mem[k];
                inSquads[m]++;
                if (e.squad[m] != s.id && problems++ < 5) sb.append("squad ").append(s.id).append(" lists ").append(m).append(" whose squad is ").append(e.squad[m]).append('\n');
                if (e.loc[m] != Ents.FREE && e.loc[m] != Ents.ABOARD && problems++ < 5) sb.append("squad member ").append(m).append(" is loc ").append(e.loc[m]).append('\n');
            }
        }
        int live = 0;
        for (int i = 0; i < e.top; i++) {
            byte l = e.loc[i];
            if (l != Ents.NONE) live++;
            if (l == Ents.ABOARD && seenInVehicle[i] != 1 && problems++ < 5) sb.append("entity ").append(i).append(" aboard but in ").append(seenInVehicle[i]).append(" crews\n");
            if (l != Ents.ABOARD && seenInVehicle[i] != 0 && problems++ < 5) sb.append("entity ").append(i).append(" in a crew but loc ").append(l).append('\n');
            if (inSquads[i] > 1 && problems++ < 5) sb.append("entity ").append(i).append(" in ").append(inSquads[i]).append(" squads\n");
            if (l == Ents.FREE || l == Ents.BODY) {
                if (Float.isNaN(e.x[i]) || Float.isNaN(e.y[i]) || e.x[i] < 0 || e.y[i] < 0 || e.x[i] > max || e.y[i] > max) {
                    if (problems++ < 5) sb.append("entity ").append(i).append(" off the map at ").append(e.x[i]).append(',').append(e.y[i]).append('\n');
                }
            }
            // the street-level simulation reads only active chunks: everyone on the map stands in one
            if (l == Ents.FREE && !w.act.activePx(e.x[i], e.y[i]) && problems++ < 5)
                sb.append("entity ").append(i).append(" stands in chunk ").append(w.act.pageOfPx(e.x[i], e.y[i])).append(", which isn't active\n");
            if (l == Ents.FREE && e.squad[i] >= 0 && inSquads[i] == 0 && e.armed(i) && problems++ < 5)
                sb.append("entity ").append(i).append(" thinks it's in squad ").append(e.squad[i]).append('\n');
        }
        if (live != e.live && problems++ < 5) sb.append("live count ").append(e.live).append(" but ").append(live).append(" slots in use\n");
        // a national map: the resident pages are exactly the active chunks, each standing as the cache's own chunk
        // (never a copy), with the overlay's edits on
        if (w.r.nation != null) {
            ChunkStore st = w.r.store;
            for (int p = 0; p < st.pages(); p++) {
                boolean res = st.resident(p), act = w.act.active(p);
                if (res != act && problems++ < 5) sb.append("chunk ").append(p).append(res ? " resident but not active\n" : " active but not resident\n");
                if (!res) continue;
                Chunk c = st.chunk(p);
                if (c == null || c.index != p) {
                    if (problems++ < 5) sb.append("page ").append(p).append(" isn't its chunk\n");
                    continue;
                }
                if (!w.r.cache.pinned(c.cx, c.cy) && problems++ < 5) sb.append("chunk ").append(p).append(" attached but not pinned\n");
                if (w.r.cache.peek(c.cx, c.cy) != c && problems++ < 5) sb.append("chunk ").append(p).append(" attached is not the cache's\n");
                if (c.overlayVersion != w.r.overlay.version(p) && w.r.overlay.version(p) != 0 && problems++ < 5)
                    sb.append("chunk ").append(p).append(" hasn't the overlay's edits\n");
            }
        }
        for (int s = 0; s < w.sec.n; s++)
            if ((w.sec.popIn[s] < 0 || w.sec.zIn[s] < 0 || w.sec.inf[s] < 0) && problems++ < 5) sb.append("sector ").append(s).append(" negative count\n");
        return sb.toString();
    }

    /** Everyone still alive and human, wherever they are: indoors, on the streets, on the road out. */
    public static long people(World w) {
        long n = 0;
        for (int s = 0; s < w.sec.n; s++) n += w.sec.popIn[s] + (long) Math.ceil(w.sec.inf[s] - 1e-4);
        Ents e = w.e;
        for (int i = 0; i < e.top; i++) if ((e.loc[i] == Ents.FREE || e.loc[i] == Ents.ABOARD) && e.kind[i] == Ents.CIV && w.stats.resident(i)) n++;
        for (Logistics.Convoy c : w.logistics.convoys) if (c.alive) n += c.refugees;
        return n + w.strat.refugeeTotal() + w.strat.abroad() + w.life.carried();
    }

    /**
     * The population's ledger: everyone alive (at home, sheltering, on the road, abroad) plus the civilians killed
     * plus those who turned, less everyone at the start. Zero, always.
     */
    public static long ledger(World w) {
        return people(w) + w.stats.civDeaths + w.stats.civTurned - w.stats.startPeople;
    }

    /** A hash of the whole simulation state, for determinism tests. */
    public static long hash(World w) {
        long h = 1469598103934665603L;
        Ents e = w.e;
        for (int i = 0; i < e.top; i++) {
            h = (h ^ e.loc[i]) * 1099511628211L;
            if (e.loc[i] == Ents.NONE) continue;
            h = (h ^ Float.floatToIntBits(e.x[i])) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(e.y[i])) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(e.hp[i])) * 1099511628211L;
            h = (h ^ e.kind[i]) * 1099511628211L;
        }
        for (int v = 0; v < w.veh.top; v++) {
            h = (h ^ Float.floatToIntBits(w.veh.x[v])) * 1099511628211L;
            h = (h ^ w.veh.state[v]) * 1099511628211L;
        }
        for (int s = 0; s < w.sec.n; s++) h = (h ^ (w.sec.popIn[s] * 31L + w.sec.zIn[s])) * 1099511628211L;
        h ^= w.rng.state();
        return h;
    }
}
