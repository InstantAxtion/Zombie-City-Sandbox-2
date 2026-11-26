package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Saved games. The save format has a version; the game's rules don't. A save holds everything the simulation
 * depends on, so a loaded game carries on exactly as it would have (the tests check this). The region itself is
 * rebuilt from its seed, then the saved overlay (Overlay: barricades, rubble, blown bridges, destroyed buildings) is
 * laid over it; saves before version 8 held every tile instead.
 */
public final class SaveIO {
    /**
     * The newest format this game reads and writes, the one every save is written in.
     * <ul>
     * <li>8: the map as its overlay (the edits since it was generated) instead of every tile; a flag for a sparse
     * active set, then the set as in 7; the sectors sparsely (each field as its differences from a new game's values,
     * runs of no difference counted); the scent field as pages, only those it reaches.</li>
     * <li>7: a game with a sparse active set (ActiveSet): the set after the header, the scent field on active pages.</li>
     * <li>6: every chunk active (today's maps); 3-5 older still. 3-7 hold every tile, the sectors and scent whole.</li>
     * </ul>
     */
    public static final int VERSION = 8;
    /** The oldest save this version still reads (3: the game's 1.2; the fields added since get their defaults). */
    public static final int OLDEST = 3;
    private static final int MAGIC = 0x5A435332; // "ZCS2"

    /**
     * The content a save before version 6 was made with (the game's 1.2 up to the armed-AI work): the ids of its
     * units, squads, vehicles, zombies and formations in index order. Such a save holds content by index; a save from
     * version 6 on holds these lists itself, so content added or reordered since loads by id, never by position.
     */
    private static final String[] OLD_UNITS = {"civilian", "survivor", "militia", "patrol", "trooper", "swat", "riot",
            "marksman", "rifleman", "gunner", "grenadier", "sniper", "engineer", "guard", "firefighter"};
    private static final String[] OLD_SQUADS = {"patrol", "swat", "riot", "troopers", "rifle", "fireteam", "mech",
            "engineers", "guard"};
    private static final String[] OLD_VTYPES = {"patrol_car", "swat_van", "riot_van", "police_heli", "humvee",
            "army_truck", "apc", "tank", "gunship", "transport_heli", "jet", "supply_truck", "bus", "fire_engine",
            "destroyer", "transport_plane"};
    private static final String[] OLD_ZTYPES = {"walker", "runner", "brute", "armoured", "stalker", "giant"};
    private static final String[] OLD_FORMATIONS = {"police_reserve", "guard_brigade", "infantry", "armour",
            "artillery", "marines"};

    /** A save's content indices mapped to this content's, by id (-1: this content has no such thing). */
    static final class Remap {
        int[] unit, squad, vtype, ztype, formation;

        static int at(int[] m, int i) {
            return i >= 0 && i < m.length ? m[i] : -1;
        }

        /** The formation a save's index names, in this content (the first one if this content lacks it). */
        int formation(int i) {
            return Math.max(0, at(formation, i));
        }
    }

    private static int[] mapIds(String[] saved, java.util.List<String> now) {
        int[] m = new int[saved.length];
        for (int i = 0; i < saved.length; i++) m[i] = now.indexOf(saved[i]);
        return m;
    }

    private static java.util.List<String> unitIds(Content c) {
        java.util.List<String> l = new java.util.ArrayList<String>();
        for (Content.Unit u : c.units) l.add(u.id);
        return l;
    }

    private static java.util.List<String> squadIds(Content c) {
        java.util.List<String> l = new java.util.ArrayList<String>();
        for (Content.SquadT t : c.squads) l.add(t.id);
        return l;
    }

    private static java.util.List<String> vtypeIds(Content c) {
        java.util.List<String> l = new java.util.ArrayList<String>();
        for (Content.VType t : c.vtypes) l.add(t.id);
        return l;
    }

    private static java.util.List<String> ztypeIds(Content c) {
        java.util.List<String> l = new java.util.ArrayList<String>();
        for (Content.ZType t : c.ztypes) l.add(t.id);
        return l;
    }

    private static java.util.List<String> formationIds(Content c) {
        java.util.List<String> l = new java.util.ArrayList<String>();
        for (Content.FormationT t : c.formations) l.add(t.id);
        return l;
    }

    private static void writeIds(DataOutputStream o, java.util.List<String> ids) throws IOException {
        o.writeInt(ids.size());
        for (String id : ids) o.writeUTF(id);
    }

    private static String[] readIds(DataInputStream in) throws IOException {
        String[] a = new String[in.readInt()];
        for (int i = 0; i < a.length; i++) a[i] = in.readUTF();
        return a;
    }

    /** The content table a save holds (version 6 on), or the one older saves were made with. */
    private static Remap readRemap(DataInputStream in, Content c, int ver) throws IOException {
        String[] u = OLD_UNITS, sq = OLD_SQUADS, vt = OLD_VTYPES, zt = OLD_ZTYPES, fm = OLD_FORMATIONS;
        if (ver >= 6) {
            u = readIds(in);
            sq = readIds(in);
            vt = readIds(in);
            zt = readIds(in);
            fm = readIds(in);
        }
        Remap m = new Remap();
        m.unit = mapIds(u, unitIds(c));
        m.squad = mapIds(sq, squadIds(c));
        m.vtype = mapIds(vt, vtypeIds(c));
        m.ztype = mapIds(zt, ztypeIds(c));
        m.formation = mapIds(fm, formationIds(c));
        return m;
    }

    /** Counts kept by a save's content index, moved to this content's indices (n of them); null stays null. */
    private static int[] remapCounts(int[] saved, int[] map, int n) {
        if (saved == null) return null;
        int[] a = new int[n];
        for (int i = 0; i < saved.length; i++) {
            int j = Remap.at(map, i);
            if (j >= 0) a[j] += saved[i];
        }
        return a;
    }

    /** A save from a version of the game too old to read. */
    public static final class TooOld extends IOException {
        TooOld() {
            super("made with an older version of the game");
        }
    }

    /** Saves the world to os: a snapshot of it, compressed. */
    public static void save(World w, OutputStream os) throws IOException {
        compress(snapshot(w), os);
    }

    /**
     * Everything a save holds, uncompressed: the part that has to be done while the world stands still (on the game's
     * thread). compress() can then run anywhere, and save() is exactly snapshot() then compress().
     */
    public static Snapshot snapshot(World w) throws IOException {
        // room for the rest at once, so the buffer seldom has to grow (a save is mostly the individuals and modules)
        Snapshot raw = new Snapshot(4 << 20);
        DataOutputStream o = new DataOutputStream(raw);
        write(w, o);
        o.flush();
        return raw;
    }

    /** Compresses a snapshot into os (the save file's bytes). */
    public static void compress(Snapshot raw, OutputStream os) throws IOException {
        Deflater d = new Deflater();
        try {
            DeflaterOutputStream z = new DeflaterOutputStream(os, d, 1 << 16);
            z.write(raw.buf, 0, raw.n);
            z.finish();
            z.flush();
        } finally {
            d.end();
        }
    }

    /**
     * A save's bytes before compression, in a growing array written without ByteArrayOutputStream's locking (a
     * snapshot writes millions of small values).
     */
    public static final class Snapshot extends OutputStream {
        private byte[] buf;
        private int n;

        Snapshot(int cap) {
            buf = new byte[cap];
        }

        public int length() {
            return n;
        }

        @Override
        public void write(int b) {
            if (n == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
            buf[n++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (n + len > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, n + len));
            System.arraycopy(b, off, buf, n, len);
            n += len;
        }

    }

    private static void write(World w, DataOutputStream o) throws IOException {
        o.writeInt(MAGIC);
        o.writeInt(VERSION);
        o.writeLong(w.c.rulesHash);
        // the content by id, so a later game with more or reordered content reads this save by name
        writeIds(o, unitIds(w.c));
        writeIds(o, squadIds(w.c));
        writeIds(o, vtypeIds(w.c));
        writeIds(o, ztypeIds(w.c));
        writeIds(o, formationIds(w.c));
        // the region
        o.writeInt(w.r.W);
        o.writeInt(w.r.preset);
        o.writeLong(w.r.seed);
        o.writeLong(w.seed);
        o.writeUTF(w.scenario == null ? "" : w.scenario.id);
        o.writeInt(w.mode);
        o.writeBoolean(w.act.sparse);
        if (w.act.sparse) w.act.write(o);
        w.r.overlay.write(o);
        bytes(o, w.r.bfloors, w.r.bCount);
        bools(o, w.r.roads.ealive, w.r.roads.edgeCount);
        facilities(o, w);
        // the clock and dice
        o.writeLong(w.tick);
        o.writeDouble(w.time);
        o.writeLong(w.rng.state());
        o.writeInt(w.playerEnt);
        o.writeBoolean(w.ended);
        o.writeBoolean(w.won);
        o.writeUTF(w.endTitle == null ? "" : w.endTitle);
        o.writeUTF(w.endText == null ? "" : w.endText);
        o.writeFloat(w.camX0); o.writeFloat(w.camY0); o.writeFloat(w.camX1); o.writeFloat(w.camY1);
        o.writeBoolean(w.camDetail);
        o.writeInt(w.maxIndividuals);
        o.writeBoolean(w.strategic);
        ents(o, w.e);
        vehicles(o, w.veh);
        squads(o, w);
        sectorsSparse(o, w.sec);
        dispatch(o, w.disp);
        military(o, w.mil);
        strategic(o, w.strat);
        gov(o, w.gov);
        news(o, w.news);
        stats(o, w.stats);
        effects(o, w.fx);
        flowPages(o, w);
        modules(o, w);
        extras(o, w);
        extras5(o, w);
        o.writeInt(MAGIC);
    }

    public static World load(Content c, InputStream is, int threads) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new InflaterInputStream(is), 1 << 16));
        if (in.readInt() != MAGIC) throw new IOException("not a Zombie City Sandbox 2 save");
        int ver = in.readInt();
        if (ver > VERSION) throw new IOException("this save is from a newer version of the game");
        if (ver < OLDEST) throw new TooOld();
        long rules = in.readLong();
        Remap map = readRemap(in, c, ver);
        int size = in.readInt(), preset = in.readInt();
        long rseed = in.readLong(), seed = in.readLong();
        String scen = in.readUTF();
        int mode = in.readInt();
        Region r = Setup.region(size, preset, rseed, threads);
        // a sparse active set (version 7, or 8 says), restored before anything else so the world goes on with exactly
        // that set
        boolean sparse = ver >= 8 ? in.readBoolean() : ver == 7;
        World w = new World(c, r, seed, threads, sparse);
        if (sparse) w.act.read(in);
        w.scenario = scen.length() == 0 ? null : c.scenario(scen);
        Script.attach(w); // (a scenario with triggers or goals has a module for them)
        w.mode = mode;
        byte[][] generated = null;
        if (ver >= 8) {
            Overlay ov = Overlay.read(in);
            if (ov.pages() != r.store.pages()) throw new IOException("save is damaged");
            if (r.nation != null) {
                // a national map: the pages the active set took in get the edits (from the cache), the rest when
                // they come in
                r.overlay = ov;
                ov.takeOriginals(r.nation);
                w.refreshPages();
            } else {
                ov.applyTo(r);
                r.overlay = ov;
            }
        } else {
            // every tile: kept as generated first, so the edits can be told from the rest (the overlay)
            generated = new byte[r.store.pages()][];
            for (int p = 0; p < generated.length; p++) if (r.store.resident(p)) generated[p] = r.store.tile[p].clone();
            r.store.readTiles(in);
        }
        byte[] floors = readBytes(in);
        System.arraycopy(floors, 0, r.bfloors, 0, Math.min(floors.length, r.bCount));
        if (generated != null) r.overlay = Overlay.diff(r, generated);
        for (int b = 0; b < r.bCount && r.nation == null; b++) {
            if (r.bfloors[b] != 0) continue;
            for (int ty = r.by[b]; ty < r.by[b] + r.bh[b]; ty++)
                for (int tx = r.bx[b]; tx < r.bx[b] + r.bw[b]; tx++)
                    if (r.in(tx, ty) && r.building(tx, ty) == b) r.clearBuilding(tx, ty);
        }
        boolean[] alive = readBools(in);
        System.arraycopy(alive, 0, r.roads.ealive, 0, Math.min(alive.length, r.roads.edgeCount));
        readFacilities(in, w, map);
        if (r.nation == null) r.rebuildNav(0, 0, r.W - 1, r.H - 1); // (a chunk's cells come with it, edits applied)
        w.sec.connectivity();
        w.tick = in.readLong();
        w.time = in.readDouble();
        w.rng.setState(in.readLong());
        w.playerEnt = in.readInt();
        w.ended = in.readBoolean();
        w.won = in.readBoolean();
        w.endTitle = in.readUTF();
        w.endText = in.readUTF();
        w.camX0 = in.readFloat(); w.camY0 = in.readFloat(); w.camX1 = in.readFloat(); w.camY1 = in.readFloat();
        w.camDetail = in.readBoolean();
        w.maxIndividuals = in.readInt();
        w.strategic = in.readBoolean();
        readEnts(in, w.e, map);
        readVehicles(in, w.veh, map);
        readSquads(in, w, ver, map);
        if (ver >= 8) readSectorsSparse(in, w.sec);
        else readSectors(in, w.sec);
        w.sec.idx.reset(); // (every district wakes: the sector index starts again from what was loaded)
        readDispatch(in, w.disp, ver);
        readMilitary(in, w.mil);
        readStrategic(in, w.strat);
        readGov(in, w.gov);
        readNews(in, w.news);
        readStats(in, w.stats);
        readEffects(in, w.fx);
        if (ver >= 8) readFlowPages(in, w);
        else readFlow(in, w);
        w.loadRemap = map;
        try {
            readModules(in, w, ver);
        } finally {
            w.loadRemap = null;
        }
        if (ver >= 4) readExtras(in, w);
        else olderDefaults(w);
        if (ver >= 5) readExtras5(in, w);
        else {
            // a vehicle going home parks at home (version 5 gave each its own place)
            Vehicles v = w.veh;
            for (int i = 0; i < v.top; i++) {
                v.slotX[i] = v.homeX[i];
                v.slotY[i] = v.homeY[i];
                v.slotH[i] = v.head[i];
                if (v.state[i] == Vehicles.V_PULLIN) v.state[i] = Vehicles.V_ARRIVED;
            }
        }
        w.r.closeBarricadedRoads();
        if (in.readInt() != MAGIC) throw new IOException("save is damaged");
        w.veh.rebuildGrid();
        w.sp.build(w.e);
        if (rules != c.rulesHash) w.news.radio("(This save was made with different game rules; it may play a little differently.)", -1, -1, News.COMMAND);
        return w;
    }

    // ------------------------------------------------------------------ facilities and modules

    private static void facilities(DataOutputStream o, World w) throws IOException {
        java.util.List<Region.Facility> fs = w.r.facilities;
        o.writeInt(fs.size());
        for (Region.Facility f : fs) {
            o.writeBoolean(f.runtime);
            if (f.runtime) {
                o.writeInt(f.kind);
                o.writeInt(f.settlement);
                o.writeUTF(f.name);
            }
            o.writeFloat(f.x);
            o.writeFloat(f.y);
            intArr(o, f.pool);
            intArr(o, f.vpool);
            o.writeFloat(f.ammo);
            o.writeFloat(f.fuel);
            o.writeFloat(f.food);
            o.writeFloat(f.med);
        }
    }

    private static void readFacilities(DataInputStream in, World w, Remap map) throws IOException {
        int n = in.readInt();
        java.util.List<Region.Facility> fs = w.r.facilities;
        for (int k = 0; k < n; k++) {
            boolean runtime = in.readBoolean();
            Region.Facility f;
            if (runtime) {
                int kind = in.readInt(), st = in.readInt();
                String name = in.readUTF();
                f = k < fs.size() ? fs.get(k) : w.r.addFacility(kind, st, 0, 0, name);
            } else f = fs.get(k);
            f.x = in.readFloat();
            f.y = in.readFloat();
            // kept by content index: moved to this content's indices by id (content may have grown or moved since)
            f.pool = remapCounts(readIntArr(in), map.unit, w.c.units.size());
            f.vpool = remapCounts(readIntArr(in), map.vtype, w.c.vtypes.size());
            f.ammo = in.readFloat();
            f.fuel = in.readFloat();
            f.food = in.readFloat();
            f.med = in.readFloat();
            if (runtime) f.node = w.r.roads.nearestNode(f.x, f.y, 900);
        }
        w.strat.extendFacilities();
    }

    private static void modules(DataOutputStream o, World w) throws IOException {
        o.writeInt(w.modules.size());
        for (Module m : w.modules) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            DataOutputStream mo = new DataOutputStream(bo);
            m.save(mo);
            mo.flush();
            o.writeUTF(m.id());
            o.writeInt(bo.size());
            o.write(bo.toByteArray());
        }
    }

    /** State added in version 4: the army asked for, the alert a player lowered, the population's ledger. */
    private static void extras(DataOutputStream o, World w) throws IOException {
        Military m = w.mil;
        floats(o, m.armyAsked, m.armyAsked.length);
        floats(o, m.armyAskedT, m.armyAskedT.length);
        floats(o, m.playerAskedT, m.playerAskedT.length);
        Gov g = w.gov;
        o.writeFloat(g.heldT);
        o.writeFloat(g.heldNat);
        o.writeInt(g.heldLevel);
        Stats s = w.stats;
        o.writeLong(s.civTurned);
        o.writeLong(s.startPeople);
        o.writeInt(s.crewDeaths);
        Strategic st = w.strat;
        o.writeInt(st.backFromAbroad);
        o.writeFloat(st.clearT);
        o.writeInt(st.latent.size());
        for (float[] l : st.latent) {
            o.writeFloat(l[0]);
            o.writeFloat(l[1]);
            o.writeFloat(l[2]);
        }
    }

    /** State added in version 5: each vehicle's place at the kerb and its wait for room. */
    private static void extras5(DataOutputStream o, World w) throws IOException {
        Vehicles v = w.veh;
        int n = v.top;
        floats(o, v.slotX, n); floats(o, v.slotY, n); floats(o, v.slotH, n); floats(o, v.wait, n);
    }

    private static void readExtras5(DataInputStream in, World w) throws IOException {
        Vehicles v = w.veh;
        readFloats(in, v.slotX); readFloats(in, v.slotY); readFloats(in, v.slotH); readFloats(in, v.wait);
    }

    private static void readExtras(DataInputStream in, World w) throws IOException {
        Military m = w.mil;
        readFloats(in, m.armyAsked);
        readFloats(in, m.armyAskedT);
        readFloats(in, m.playerAskedT);
        Gov g = w.gov;
        g.heldT = in.readFloat();
        g.heldNat = in.readFloat();
        g.heldLevel = in.readInt();
        Stats s = w.stats;
        s.civTurned = in.readLong();
        s.startPeople = in.readLong();
        s.crewDeaths = in.readInt();
        Strategic st = w.strat;
        st.backFromAbroad = in.readInt();
        st.clearT = in.readFloat();
        int nl = in.readInt();
        st.latent.clear();
        for (int k = 0; k < nl; k++) st.latent.add(new float[]{in.readFloat(), in.readFloat(), in.readFloat()});
    }

    private static void readModules(DataInputStream in, World w, int ver) throws IOException {
        int n = in.readInt();
        for (int k = 0; k < n; k++) {
            String id = in.readUTF();
            byte[] data = new byte[in.readInt()];
            in.readFully(data);
            if (ver < 4) data = upgradeModule(id, data);
            for (Module m : w.modules)
                if (m.id().equals(id)) m.load(new DataInputStream(new java.io.ByteArrayInputStream(data)));
        }
    }

    /**
     * A version 3 save (the game's 1.2) lacks a few fields some modules added since, in the middle of their data:
     * rewrites such a module's bytes in today's layout with the new fields at their starting values.
     */
    static byte[] upgradeModule(String id, byte[] data) throws IOException {
        if (!id.equals("forces") && !id.equals("command") && !id.equals("media") && !id.equals("engineers")) return data;
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(data.length + 2560);
        DataOutputStream o = new DataOutputStream(bo);
        if (id.equals("forces")) {
            o.writeInt(in.readInt()); // the next formation's number
            o.writeFloat(in.readFloat()); // the command clock
            int n = in.readInt();
            o.writeInt(n);
            for (int k = 0; k < n; k++) {
                o.writeInt(in.readInt()); // type
                o.writeUTF(in.readUTF()); // name
                copy(in, o, 3 * 4); // faction, hq, state
                int rl = in.readInt();
                o.writeInt(rl);
                copy(in, o, Math.max(0, rl) * 4); // route
                copy(in, o, 4 * 3 + 4 * 7 + 3); // ri, gx, gy, gunT, start, kills, losses, deployed, home, task; alive, outside, player
                o.writeBoolean(false); // released to the general
                o.writeInt(0); // job
                o.writeInt(-1); // job id
                o.writeFloat(0); // idle
                o.writeFloat(0); // on the job
            }
            int f = in.readInt();
            o.writeInt(f);
            copy(in, o, f * 4); // free squads
            o.writeBoolean(true); // the general commands (set from the role after loading)
        } else if (id.equals("command")) {
            int n = in.readInt();
            o.writeInt(n);
            for (int k = 0; k < n; k++) {
                copy(in, o, 4 + 3 * 4); // replaced, approval, lowT, cool
                o.writeUTF(in.readUTF()); // last decision
                copy(in, o, 8); // its time
                o.writeFloat(0); // asked
            }
            int m = in.readInt();
            o.writeInt(m);
            copy(in, o, m * (4 + 4 + 5 + 4));
            copy(in, o, 1 + 3 * 4); // bridges, hourT, capitalT, fedT
            for (int k = 0; k < 5; k++) o.writeFloat(0); // downT
            o.writeFloat(0); // curfewCalmT
            o.writeInt(-1); // last level (none seen yet)
        } else if (id.equals("media")) {
            int n = in.readInt();
            o.writeInt(n);
            copy(in, o, n * 8 + 3 * 4 + 4);
            o.writeFloat(0); // the last figure
            for (int k = 0; k < n; k++) o.writeFloat(LONG_AGO); // no rumour yet anywhere
        } else {
            int n = in.readInt();
            o.writeInt(n);
            copy(in, o, n * 12);
            int b = in.readInt();
            o.writeInt(b);
            copy(in, o, b * 8 + 4 + 4); // built, wallT, fobs
            o.writeInt(0); // no cool-downs
            copy(in, o, 8); // barricades, recovered
        }
        o.flush();
        return bo.toByteArray();
    }

    private static final float LONG_AGO = -1e9f;

    private static void copy(DataInputStream in, DataOutputStream o, int n) throws IOException {
        byte[] b = new byte[n];
        in.readFully(b);
        o.write(b);
    }

    /** What a version 3 save didn't hold, worked out from what it did. */
    private static void olderDefaults(World w) {
        // the ledger: everyone at the start = alive + killed + turned (the turned weren't counted then)
        long alive = Audit.people(w);
        Stats s = w.stats;
        s.startPeople = Math.max(s.startCivilians, alive + s.civDeaths);
        s.civTurned = s.startPeople - alive - s.civDeaths;
        // the player as President or commander commanded the army themselves
        w.forces.generalCommands = !(w.gov.playerDecides || w.roles.role == Roles.MILITARY);
    }

    // ------------------------------------------------------------------ entities

    private static void ents(DataOutputStream o, Ents e) throws IOException {
        int n = e.top;
        o.writeInt(n);
        o.writeInt(e.nextUid());
        floats(o, e.x, n); floats(o, e.y, n); floats(o, e.vx, n); floats(o, e.vy, n); floats(o, e.hp, n);
        floats(o, e.maxHp, n); floats(o, e.face, n); floats(o, e.timer, n); floats(o, e.aimT, n); floats(o, e.infT, n);
        floats(o, e.gx, n); floats(o, e.gy, n); floats(o, e.dvx, n); floats(o, e.dvy, n);
        bytes(o, e.kind, n); bytes(o, e.loc, n); bytes(o, e.state, n);
        shorts(o, e.type, n); shorts(o, e.ammo, n); shorts(o, e.kills, n); shorts(o, e.rank, n);
        ints(o, e.flags, n); ints(o, e.target, n); ints(o, e.squad, n); ints(o, e.vehicle, n); ints(o, e.uid, n); ints(o, e.home, n);
        intArr(o, e.freeOrder());
    }

    private static void readEnts(DataInputStream in, Ents e, Remap map) throws IOException {
        int n = in.readInt();
        e.ensure(n + 1);
        e.top = n;
        e.setNextUid(in.readInt());
        readFloats(in, e.x); readFloats(in, e.y); readFloats(in, e.vx); readFloats(in, e.vy); readFloats(in, e.hp);
        readFloats(in, e.maxHp); readFloats(in, e.face); readFloats(in, e.timer); readFloats(in, e.aimT); readFloats(in, e.infT);
        readFloats(in, e.gx); readFloats(in, e.gy); readFloats(in, e.dvx); readFloats(in, e.dvy);
        readBytesInto(in, e.kind); readBytesInto(in, e.loc); readBytesInto(in, e.state);
        readShorts(in, e.type); readShorts(in, e.ammo); readShorts(in, e.kills); readShorts(in, e.rank);
        readInts(in, e.flags); readInts(in, e.target); readInts(in, e.squad); readInts(in, e.vehicle); readInts(in, e.uid); readInts(in, e.home);
        for (int i = 0; i < n; i++) {
            if (e.loc[i] == Ents.NONE) continue;
            // a zombie's type is its kind of zombie, anyone else's the kind of person (by the save's content index)
            int t = Remap.at(e.kind[i] == Ents.ZOMBIE ? map.ztype : map.unit, e.type[i]);
            e.type[i] = (short) Math.max(0, t);
        }
        e.rebuildFree();
        int[] order = readIntArr(in);
        if (order != null) e.setFreeOrder(order);
    }

    // ------------------------------------------------------------------ vehicles

    private static void vehicles(DataOutputStream o, Vehicles v) throws IOException {
        int n = v.top;
        o.writeInt(n);
        o.writeInt(v.nextUid());
        floats(o, v.x, n); floats(o, v.y, n); floats(o, v.head, n); floats(o, v.spd, n); floats(o, v.hp, n);
        floats(o, v.destX, n); floats(o, v.destY, n); floats(o, v.homeX, n); floats(o, v.homeY, n); floats(o, v.fuel, n);
        floats(o, v.stuck, n); floats(o, v.wpnT, n); floats(o, v.wpn2T, n); floats(o, v.timer, n); floats(o, v.mobT, n);
        floats(o, v.seg, n); floats(o, v.orbitA, n); floats(o, v.ghost, n); floats(o, v.alt, n);
        shorts(o, v.type, n); shorts(o, v.kills, n);
        bytes(o, v.state, n); bytes(o, v.role, n);
        ints(o, v.squad, n); ints(o, v.home, n); ints(o, v.ri, n); ints(o, v.crewN, n); ints(o, v.ammo, n);
        ints(o, v.cargo, n); ints(o, v.uid, n); ints(o, v.target, n);
        bools(o, v.siren, n); bools(o, v.returning, n);
        for (int i = 0; i < n; i++) {
            intArr(o, v.route[i]);
            intArr(o, v.crew[i]);
        }
    }

    private static void readVehicles(DataInputStream in, Vehicles v, Remap map) throws IOException {
        int n = in.readInt();
        v.ensure(n + 1);
        v.top = n;
        v.setNextUid(in.readInt());
        readFloats(in, v.x); readFloats(in, v.y); readFloats(in, v.head); readFloats(in, v.spd); readFloats(in, v.hp);
        readFloats(in, v.destX); readFloats(in, v.destY); readFloats(in, v.homeX); readFloats(in, v.homeY); readFloats(in, v.fuel);
        readFloats(in, v.stuck); readFloats(in, v.wpnT); readFloats(in, v.wpn2T); readFloats(in, v.timer); readFloats(in, v.mobT);
        readFloats(in, v.seg); readFloats(in, v.orbitA); readFloats(in, v.ghost); readFloats(in, v.alt);
        readShorts(in, v.type); readShorts(in, v.kills);
        for (int i = 0; i < n; i++) v.type[i] = (short) Math.max(0, Remap.at(map.vtype, v.type[i]));
        readBytesInto(in, v.state); readBytesInto(in, v.role);
        readInts(in, v.squad); readInts(in, v.home); readInts(in, v.ri); readInts(in, v.crewN); readInts(in, v.ammo);
        readInts(in, v.cargo); readInts(in, v.uid); readInts(in, v.target);
        readBools(in, v.siren); readBools(in, v.returning);
        for (int i = 0; i < n; i++) {
            v.route[i] = readIntArr(in);
            v.crew[i] = readIntArr(in);
            if (v.crew[i] == null) v.crew[i] = new int[1];
        }
    }

    // ------------------------------------------------------------------ squads

    private static void squads(DataOutputStream o, World w) throws IOException {
        o.writeInt(w.sq.list.size());
        for (Squads.Squad s : w.sq.list) {
            o.writeInt(s.faction); o.writeInt(s.n); o.writeInt(s.vehicle); o.writeInt(s.home); o.writeInt(s.order);
            o.writeInt(s.mode); o.writeInt(s.incident); o.writeInt(s.task);
            for (int k = 0; k < s.n; k++) o.writeInt(s.mem[k]);
            o.writeFloat(s.gx); o.writeFloat(s.gy); o.writeFloat(s.faceX); o.writeFloat(s.faceY); o.writeFloat(s.timer);
            o.writeFloat(s.boardT); o.writeFloat(s.fallT); o.writeFloat(s.replanT); o.writeFloat(s.hold);
            o.writeFloat(s.idleT); o.writeInt(s.template);
            floatArr(o, s.path);
            o.writeInt(s.pathI);
            o.writeUTF(s.name); o.writeUTF(s.label);
            o.writeInt(s.kills); o.writeInt(s.losses);
            o.writeBoolean(s.alive); o.writeBoolean(s.outside); o.writeBoolean(s.player);
        }
    }

    private static void readSquads(DataInputStream in, World w, int ver, Remap map) throws IOException {
        int n = in.readInt();
        w.sq.list.clear();
        for (int i = 0; i < n; i++) {
            Squads.Squad s = new Squads.Squad();
            s.id = i;
            s.faction = in.readInt(); s.n = in.readInt(); s.vehicle = in.readInt(); s.home = in.readInt(); s.order = in.readInt();
            s.mode = in.readInt(); s.incident = in.readInt(); s.task = in.readInt();
            s.mem = new int[Math.max(4, s.n)];
            for (int k = 0; k < s.n; k++) s.mem[k] = in.readInt();
            s.gx = in.readFloat(); s.gy = in.readFloat(); s.faceX = in.readFloat(); s.faceY = in.readFloat(); s.timer = in.readFloat();
            s.boardT = in.readFloat(); s.fallT = in.readFloat(); s.replanT = in.readFloat(); s.hold = in.readFloat();
            if (ver >= 4) {
                s.idleT = in.readFloat();
                s.template = in.readInt();
                if (s.template >= 0) s.template = Remap.at(map.squad, s.template); // by the save's content index
            }
            s.path = readFloatArr(in);
            s.pathI = in.readInt();
            s.name = in.readUTF(); s.label = in.readUTF();
            s.kills = in.readInt(); s.losses = in.readInt();
            s.alive = in.readBoolean(); s.outside = in.readBoolean(); s.player = in.readBoolean();
            if (ver < 4) {
                // older saves didn't keep which kind of squad it was: its label is the kind's name
                for (Content.SquadT t : w.c.squads)
                    if (t.name.equals(s.label)) {
                        s.template = t.index;
                        break;
                    }
            }
            w.sq.list.add(s);
        }
    }

    // ------------------------------------------------------------------ sectors, calls, command

    /**
     * Version 8: each field as its differences from a new game's values (popIn: home; firstZ: -1; everything else
     * nothing), runs of no difference counted rather than written. Most of a big map stays as it started.
     */
    private static void sectorsSparse(DataOutputStream o, Sectors s) throws IOException {
        int n = s.n;
        int[] d = new int[n];
        for (int i = 0; i < n; i++) d[i] = s.popIn[i] - s.home[i];
        runs(o, d, n, true);
        runs(o, s.zIn, n, true);
        runs(o, s.bodies, n, true);
        runs(o, s.ambient, n, true);
        for (float[] f : new float[][]{s.inf, s.hot, s.panic, s.firstZ, s.driftX, s.driftY}) {
            int base = Float.floatToRawIntBits(f == s.firstZ ? -1f : 0f);
            for (int i = 0; i < n; i++) d[i] = Float.floatToRawIntBits(f[i]) ^ base;
            runs(o, d, n, false);
        }
        for (boolean[] b : new boolean[][]{s.alerted, s.cam}) {
            for (int i = 0; i < n; i++) d[i] = b[i] ? 1 : 0;
            runs(o, d, n, true);
        }
    }

    private static void readSectorsSparse(DataInputStream in, Sectors s) throws IOException {
        int n = s.n;
        int[] d = new int[n];
        readRuns(in, d, true);
        for (int i = 0; i < n; i++) s.popIn[i] = s.home[i] + d[i];
        readRuns(in, s.zIn, true);
        readRuns(in, s.bodies, true);
        readRuns(in, s.ambient, true);
        for (float[] f : new float[][]{s.inf, s.hot, s.panic, s.firstZ, s.driftX, s.driftY}) {
            int base = Float.floatToRawIntBits(f == s.firstZ ? -1f : 0f);
            readRuns(in, d, false);
            for (int i = 0; i < n; i++) f[i] = Float.intBitsToFloat(d[i] ^ base);
        }
        for (boolean[] b : new boolean[][]{s.alerted, s.cam}) {
            readRuns(in, d, true);
            for (int i = 0; i < n; i++) b[i] = d[i] != 0;
        }
    }

    /**
     * a[0..n) with its zeros in runs: the count, then (zeros, values, the values) until the end. small: the values as
     * zigzag varints (counts), else as plain ints (float bits).
     */
    static void runs(DataOutputStream o, int[] a, int n, boolean small) throws IOException {
        Overlay.writeVar(o, n);
        int i = 0;
        while (i < n) {
            int z = i;
            while (z < n && a[z] == 0) z++;
            int e = z;
            while (e < n && a[e] != 0) e++;
            Overlay.writeVar(o, z - i);
            Overlay.writeVar(o, e - z);
            for (int k = z; k < e; k++) {
                if (small) Overlay.writeVar(o, a[k] << 1 ^ a[k] >> 31);
                else o.writeInt(a[k]);
            }
            i = e;
        }
    }

    /** Module saves: float[] as runs of their bits (0f is none), booleans and bytes as small runs. */
    static void floatRuns(DataOutputStream o, float[] f) throws IOException {
        int[] d = new int[f.length];
        for (int i = 0; i < f.length; i++) d[i] = Float.floatToRawIntBits(f[i]);
        runs(o, d, d.length, false);
    }

    static void readFloatRuns(DataInputStream in, float[] f) throws IOException {
        int[] d = new int[f.length];
        readRuns(in, d, false);
        for (int i = 0; i < f.length; i++) f[i] = Float.intBitsToFloat(d[i]);
    }

    static void boolRuns(DataOutputStream o, boolean[] b) throws IOException {
        int[] d = new int[b.length];
        for (int i = 0; i < b.length; i++) d[i] = b[i] ? 1 : 0;
        runs(o, d, d.length, true);
    }

    static void readBoolRuns(DataInputStream in, boolean[] b) throws IOException {
        int[] d = new int[b.length];
        readRuns(in, d, true);
        for (int i = 0; i < b.length; i++) b[i] = d[i] != 0;
    }

    static void byteRuns(DataOutputStream o, byte[] b) throws IOException {
        int[] d = new int[b.length];
        for (int i = 0; i < b.length; i++) d[i] = b[i];
        runs(o, d, d.length, true);
    }

    static void readByteRuns(DataInputStream in, byte[] b) throws IOException {
        int[] d = new int[b.length];
        readRuns(in, d, true);
        for (int i = 0; i < b.length; i++) b[i] = (byte) d[i];
    }

    /** Reads what runs() wrote into a (zeros where it says none; values past a's end are read and dropped). */
    static void readRuns(DataInputStream in, int[] a, boolean small) throws IOException {
        int n = Overlay.readVar(in), i = 0;
        java.util.Arrays.fill(a, 0);
        while (i < n) {
            i += Overlay.readVar(in);
            int m = Overlay.readVar(in);
            if (i > n || m > n - i) throw new IOException("save is damaged");
            for (int k = 0; k < m; k++, i++) {
                int v;
                if (small) {
                    int u = Overlay.readVar(in);
                    v = u >>> 1 ^ -(u & 1);
                } else v = in.readInt();
                if (i < a.length) a[i] = v;
            }
        }
    }

    /** Versions 3-7: every sector's fields in full. */
    private static void readSectors(DataInputStream in, Sectors s) throws IOException {
        readInts(in, s.popIn); readInts(in, s.zIn); readInts(in, s.bodies); readInts(in, s.ambient);
        readFloats(in, s.inf); readFloats(in, s.hot); readFloats(in, s.panic); readFloats(in, s.firstZ);
        readFloats(in, s.driftX); readFloats(in, s.driftY);
        readBools(in, s.alerted); readBools(in, s.cam);
    }

    private static void dispatch(DataOutputStream o, Dispatch d) throws IOException {
        o.writeInt(d.incidents.size());
        for (Dispatch.Incident i : d.incidents) {
            o.writeInt(i.sector); o.writeInt(i.settlement); o.writeInt(i.zombies); o.writeInt(i.assigned); o.writeInt(i.needed);
            o.writeInt(i.armyAssigned); o.writeFloat(i.x); o.writeFloat(i.y); o.writeFloat(i.created); o.writeFloat(i.lastSeen);
            o.writeBoolean(i.open); o.writeBoolean(i.army);
            o.writeInt(i.squads.size());
            for (int s : i.squads) o.writeInt(s);
        }
        o.writeInt(d.posts.size());
        for (Dispatch.Post p : d.posts) {
            o.writeFloat(p.x); o.writeFloat(p.y); o.writeFloat(p.fx); o.writeFloat(p.fy); o.writeInt(p.squad); o.writeInt(p.settlement);
        }
        o.writeInt(d.callsTotal);
        o.writeFloat(d.cordonT);
        // version 8: sparse, against -1 (most sectors have no incident)
        int[] inc = new int[d.secIncident.length];
        for (int i = 0; i < inc.length; i++) inc[i] = d.secIncident[i] + 1;
        runs(o, inc, inc.length, true);
        bools(o, d.perimeter, d.perimeter.length);
    }

    private static void readDispatch(DataInputStream in, Dispatch d, int ver) throws IOException {
        int n = in.readInt();
        d.incidents.clear();
        for (int k = 0; k < n; k++) {
            Dispatch.Incident i = new Dispatch.Incident();
            i.id = k;
            i.sector = in.readInt(); i.settlement = in.readInt(); i.zombies = in.readInt(); i.assigned = in.readInt(); i.needed = in.readInt();
            i.armyAssigned = in.readInt(); i.x = in.readFloat(); i.y = in.readFloat(); i.created = in.readFloat(); i.lastSeen = in.readFloat();
            i.open = in.readBoolean(); i.army = in.readBoolean();
            int m = in.readInt();
            for (int j = 0; j < m; j++) i.squads.add(in.readInt());
            d.incidents.add(i);
        }
        n = in.readInt();
        d.posts.clear();
        for (int k = 0; k < n; k++) {
            Dispatch.Post p = new Dispatch.Post();
            p.x = in.readFloat(); p.y = in.readFloat(); p.fx = in.readFloat(); p.fy = in.readFloat(); p.squad = in.readInt(); p.settlement = in.readInt();
            d.posts.add(p);
        }
        d.callsTotal = in.readInt();
        d.cordonT = in.readFloat();
        if (ver >= 8) {
            int[] inc = new int[d.secIncident.length];
            readRuns(in, inc, true);
            for (int i = 0; i < inc.length; i++) d.secIncident[i] = inc[i] - 1;
        } else readInts(in, d.secIncident);
        readBools(in, d.perimeter);
    }

    private static void military(DataOutputStream o, Military m) throws IOException {
        bools(o, m.armyCalled, m.armyCalled.length);
        o.writeFloat(m.strikeT); o.writeFloat(m.supplyT); o.writeFloat(m.waveT); o.writeFloat(m.guardT);
        o.writeFloat(m.regularT); o.writeFloat(m.bridgeT);
        o.writeInt(m.strikesLeft); o.writeInt(m.wavesArrived); o.writeInt(m.guardsArrived); o.writeInt(m.regularsArrived);
        o.writeInt(m.blownBridges.size());
        for (int[] b : m.blownBridges) {
            o.writeInt(b[0]);
            o.writeInt(b[1]);
        }
    }

    private static void readMilitary(DataInputStream in, Military m) throws IOException {
        readBools(in, m.armyCalled);
        m.strikeT = in.readFloat(); m.supplyT = in.readFloat(); m.waveT = in.readFloat(); m.guardT = in.readFloat();
        m.regularT = in.readFloat(); m.bridgeT = in.readFloat();
        m.strikesLeft = in.readInt(); m.wavesArrived = in.readInt(); m.guardsArrived = in.readInt(); m.regularsArrived = in.readInt();
        int n = in.readInt();
        m.blownBridges.clear();
        for (int k = 0; k < n; k++) m.blownBridges.add(new int[]{in.readInt(), in.readInt()});
    }

    private static void strategic(DataOutputStream o, Strategic s) throws IOException {
        bools(o, s.online, s.online.length);
        floats(o, s.overrunT, s.overrunT.length);
        bools(o, s.supplyOnWay, s.supplyOnWay.length);
        bools(o, s.gridOn, s.gridOn.length);
        o.writeInt(s.hordes.size());
        for (Strategic.Horde h : s.hordes) {
            o.writeInt(h.id); o.writeInt(h.count); o.writeInt(h.target);
            o.writeFloat(h.x); o.writeFloat(h.y); o.writeFloat(h.tx); o.writeFloat(h.ty); o.writeBoolean(h.alive); o.writeFloat(h.keepT);
        }
        o.writeInt(s.columns.size());
        for (Strategic.Column c : s.columns) {
            o.writeInt(c.count); o.writeInt(c.infected); o.writeInt(c.from); o.writeInt(c.to); o.writeInt(c.exitNode); o.writeInt(c.ri);
            intArr(o, c.route);
            o.writeFloat(c.x); o.writeFloat(c.y); o.writeBoolean(c.alive);
        }
        o.writeInt(s.strain); o.writeFloat(s.strainClock); o.writeFloat(s.crashX); o.writeFloat(s.crashY);
        o.writeInt(s.evacuatedOut); o.writeInt(s.evacuatedArrived);
        ints(o, s.pending, s.pending.length);
        o.writeFloat(s.columnT); o.writeInt(s.startTownPop); o.writeInt(s.startCityPop); o.writeInt(0);
        o.writeInt(s.nextHorde);
        o.writeFloat(s.playerJoyX); o.writeFloat(s.playerJoyY); o.writeBoolean(s.playerFire);
    }

    private static void readStrategic(DataInputStream in, Strategic s) throws IOException {
        s.extendFacilities();
        readBools(in, s.online);
        readFloats(in, s.overrunT);
        readBools(in, s.supplyOnWay);
        readBools(in, s.gridOn);
        int n = in.readInt();
        s.hordes.clear();
        for (int k = 0; k < n; k++) {
            Strategic.Horde h = new Strategic.Horde();
            h.id = in.readInt(); h.count = in.readInt(); h.target = in.readInt();
            h.x = in.readFloat(); h.y = in.readFloat(); h.tx = in.readFloat(); h.ty = in.readFloat(); h.alive = in.readBoolean(); h.keepT = in.readFloat();
            s.hordes.add(h);
        }
        n = in.readInt();
        s.columns.clear();
        for (int k = 0; k < n; k++) {
            Strategic.Column c = new Strategic.Column();
            c.count = in.readInt(); c.infected = in.readInt(); c.from = in.readInt(); c.to = in.readInt(); c.exitNode = in.readInt(); c.ri = in.readInt();
            c.route = readIntArr(in);
            c.x = in.readFloat(); c.y = in.readFloat(); c.alive = in.readBoolean();
            s.columns.add(c);
        }
        s.strain = in.readInt(); s.strainClock = in.readFloat(); s.crashX = in.readFloat(); s.crashY = in.readFloat();
        s.evacuatedOut = in.readInt(); s.evacuatedArrived = in.readInt();
        readInts(in, s.pending);
        s.columnT = in.readFloat(); s.startTownPop = in.readInt(); s.startCityPop = in.readInt(); in.readInt();
        s.nextHorde = in.readInt();
        s.playerJoyX = in.readFloat(); s.playerJoyY = in.readFloat(); s.playerFire = in.readBoolean();
    }

    private static void gov(DataOutputStream o, Gov g) throws IOException {
        o.writeInt(g.level); o.writeFloat(g.trust); o.writeBoolean(g.curfew);
        bools(o, g.evac, g.evac.length);
        o.writeBoolean(g.playerDecides); o.writeBoolean(g.reported); o.writeFloat(g.rumourT); o.writeInt(g.lastDeaths);
    }

    private static void readGov(DataInputStream in, Gov g) throws IOException {
        g.level = in.readInt(); g.trust = in.readFloat(); g.curfew = in.readBoolean();
        readBools(in, g.evac);
        g.playerDecides = in.readBoolean(); g.reported = in.readBoolean(); g.rumourT = in.readFloat(); g.lastDeaths = in.readInt();
    }

    private static void news(DataOutputStream o, News n) throws IOException {
        o.writeInt(n.log.size());
        for (News.Item it : n.log) {
            o.writeUTF(it.text.length() > 2000 ? it.text.substring(0, 2000) : it.text);
            o.writeFloat(it.x); o.writeFloat(it.y); o.writeInt(it.kind); o.writeDouble(it.time);
        }
    }

    private static void readNews(DataInputStream in, News n) throws IOException {
        int c = in.readInt();
        n.log.clear();
        for (int k = 0; k < c; k++) n.restore(in.readUTF(), in.readFloat(), in.readFloat(), in.readInt(), in.readDouble());
    }

    private static void stats(DataOutputStream o, Stats s) throws IOException {
        int[] v = {s.civDeaths, s.zombieKills, s.zombiesMade, s.risen, s.infectedTotal, s.shots, s.vehiclesLost, s.facilitiesLost,
                s.buildingsLost, s.airStrikes, s.bridgesBlown, s.supplyRuns, s.outsidePolice, s.outsideSoldiers, s.peakZombies,
                s.startPolice, s.startSoldiers, s.startCivilians, s.rotted, s.artillery, s.troopTrains, s.trades};
        ints(o, v, v.length);
        ints(o, s.killsBySide, 3);
        ints(o, s.armedDeaths, 4);
        ints(o, s.zBySettlement, s.zBySettlement.length);
        ints(o, s.popBySettlement, s.popBySettlement.length);
        o.writeInt(s.tN);
        floats(o, s.tTime, s.tN); ints(o, s.tZombies, s.tN); ints(o, s.tAlive, s.tN); ints(o, s.tPolice, s.tN); ints(o, s.tArmy, s.tN);
        o.writeInt(s.events.size());
        for (Stats.Event e : s.events) {
            o.writeUTF(e.text); o.writeFloat(e.x); o.writeFloat(e.y); o.writeDouble(e.time);
        }
        o.writeInt(s.fallen.size());
        for (String f : s.fallen) o.writeUTF(f);
        ints(o, s.bestUid, 10);
        ints(o, s.bestKills, 10);
        for (int k = 0; k < 10; k++) o.writeUTF(s.bestName[k] == null ? "" : s.bestName[k]);
    }

    private static void readStats(DataInputStream in, Stats s) throws IOException {
        int[] v = new int[22];
        readInts(in, v);
        s.civDeaths = v[0]; s.zombieKills = v[1]; s.zombiesMade = v[2]; s.risen = v[3]; s.infectedTotal = v[4]; s.shots = v[5];
        s.vehiclesLost = v[6]; s.facilitiesLost = v[7]; s.buildingsLost = v[8]; s.airStrikes = v[9]; s.bridgesBlown = v[10];
        s.supplyRuns = v[11]; s.outsidePolice = v[12]; s.outsideSoldiers = v[13]; s.peakZombies = v[14];
        s.startPolice = v[15]; s.startSoldiers = v[16]; s.startCivilians = v[17];
        s.rotted = v[18]; s.artillery = v[19]; s.troopTrains = v[20]; s.trades = v[21];
        readInts(in, s.killsBySide);
        readInts(in, s.armedDeaths);
        readInts(in, s.zBySettlement);
        readInts(in, s.popBySettlement);
        s.tN = in.readInt();
        readFloats(in, s.tTime); readInts(in, s.tZombies); readInts(in, s.tAlive); readInts(in, s.tPolice); readInts(in, s.tArmy);
        int n = in.readInt();
        s.events.clear();
        for (int k = 0; k < n; k++) s.events.add(new Stats.Event(in.readUTF(), in.readFloat(), in.readFloat(), in.readDouble()));
        n = in.readInt();
        s.fallen.clear();
        for (int k = 0; k < n; k++) s.fallen.add(in.readUTF());
        readInts(in, s.bestUid);
        readInts(in, s.bestKills);
        for (int k = 0; k < 10; k++) {
            String t = in.readUTF();
            s.bestName[k] = t.length() == 0 ? null : t;
        }
    }

    private static void effects(DataOutputStream o, Effects f) throws IOException {
        o.writeInt(f.noiseN);
        floats(o, f.noiseX, f.noiseN); floats(o, f.noiseY, f.noiseN); floats(o, f.noiseLoud, f.noiseN); floats(o, f.noiseT, f.noiseN);
    }

    private static void readEffects(DataInputStream in, Effects f) throws IOException {
        f.noiseN = in.readInt();
        readFloats(in, f.noiseX); readFloats(in, f.noiseY); readFloats(in, f.noiseLoud); readFloats(in, f.noiseT);
    }

    /**
     * Version 8: the scent field by pages (every page with every chunk active, else the active ones, in page order),
     * each a flag and, if the scent reaches anywhere on it, its 32x32 cells.
     */
    private static void flowPages(DataOutputStream o, World w) throws IOException {
        Flow f = w.flow;
        ActiveSet a = w.act;
        short[] d = new short[ChunkStore.NAV_AREA];
        int nw = w.r.navW, nh = w.r.navH;
        for (int p = 0; p < a.pages; p++) {
            if (!a.active(p)) continue;
            int x0 = (p % a.pw) * ChunkStore.NAVS, y0 = (p / a.pw) * ChunkStore.NAVS;
            boolean any = false;
            for (int k = 0; k < d.length; k++) {
                int x = x0 + (k & (ChunkStore.NAVS - 1)), y = y0 + (k >> ChunkStore.NAV_SHIFT);
                d[k] = x < nw && y < nh ? f.atCell(x, y) : Flow.INF;
                if (d[k] != Flow.INF) any = true;
            }
            o.writeBoolean(any);
            if (any) shorts(o, d, d.length);
        }
    }

    private static void readFlowPages(DataInputStream in, World w) throws IOException {
        Flow f = w.flow;
        ActiveSet a = w.act;
        f.nextStamp();
        short[] d = new short[ChunkStore.NAV_AREA];
        int nw = w.r.navW, nh = w.r.navH;
        for (int p = 0; p < a.pages; p++) {
            if (!a.active(p) || !in.readBoolean()) continue;
            readShorts(in, d);
            int x0 = (p % a.pw) * ChunkStore.NAVS, y0 = (p / a.pw) * ChunkStore.NAVS;
            for (int k = 0; k < d.length; k++) {
                int x = x0 + (k & (ChunkStore.NAVS - 1)), y = y0 + (k >> ChunkStore.NAV_SHIFT);
                if (d[k] != Flow.INF && x < nw && y < nh) f.setCell(x, y, d[k]);
            }
        }
    }

    /** Versions 3-7: the scent field whole, row by row (INF where no scent reaches); 7: each active page's cells. */
    private static void readFlow(DataInputStream in, World w) throws IOException {
        Flow f = w.flow;
        int nw = w.r.navW, nh = w.r.navH;
        f.nextStamp();
        if (!w.act.sparse) {
            short[] d = new short[nw * nh];
            readShorts(in, d);
            for (int i = 0; i < d.length; i++) if (d[i] != Flow.INF) f.setCell(i % nw, i / nw, d[i]);
            return;
        }
        ActiveSet a = w.act;
        short[] d = new short[ChunkStore.NAV_AREA];
        for (int p = 0; p < a.pages; p++) {
            if (!a.active(p)) continue;
            readShorts(in, d);
            int x0 = (p % a.pw) * ChunkStore.NAVS, y0 = (p / a.pw) * ChunkStore.NAVS;
            for (int k = 0; k < d.length; k++)
                if (d[k] != Flow.INF) f.setCell(x0 + (k & (ChunkStore.NAVS - 1)), y0 + (k >> ChunkStore.NAV_SHIFT), d[k]);
        }
    }

    // ------------------------------------------------------------------ arrays

    // arrays are encoded a chunk at a time (the same bytes DataOutputStream would write value by value, faster)
    private static final ThreadLocal<byte[]> CHUNK = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[1 << 15];
        }
    };

    private static void floats(DataOutputStream o, float[] a, int n) throws IOException {
        o.writeInt(n);
        byte[] b = CHUNK.get();
        int k = 0;
        for (int i = 0; i < n; i++) {
            int v = Float.floatToIntBits(a[i]);
            b[k] = (byte) (v >>> 24);
            b[k + 1] = (byte) (v >>> 16);
            b[k + 2] = (byte) (v >>> 8);
            b[k + 3] = (byte) v;
            k += 4;
            if (k == b.length) {
                o.write(b, 0, k);
                k = 0;
            }
        }
        o.write(b, 0, k);
    }

    private static void readFloats(DataInputStream in, float[] a) throws IOException {
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            float v = in.readFloat();
            if (i < a.length) a[i] = v;
        }
    }

    private static void ints(DataOutputStream o, int[] a, int n) throws IOException {
        o.writeInt(n);
        byte[] b = CHUNK.get();
        int k = 0;
        for (int i = 0; i < n; i++) {
            int v = a[i];
            b[k] = (byte) (v >>> 24);
            b[k + 1] = (byte) (v >>> 16);
            b[k + 2] = (byte) (v >>> 8);
            b[k + 3] = (byte) v;
            k += 4;
            if (k == b.length) {
                o.write(b, 0, k);
                k = 0;
            }
        }
        o.write(b, 0, k);
    }

    private static void readInts(DataInputStream in, int[] a) throws IOException {
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            int v = in.readInt();
            if (i < a.length) a[i] = v;
        }
    }

    private static void shorts(DataOutputStream o, short[] a, int n) throws IOException {
        o.writeInt(n);
        byte[] b = CHUNK.get();
        int k = 0;
        for (int i = 0; i < n; i++) {
            int v = a[i];
            b[k] = (byte) (v >>> 8);
            b[k + 1] = (byte) v;
            k += 2;
            if (k == b.length) {
                o.write(b, 0, k);
                k = 0;
            }
        }
        o.write(b, 0, k);
    }

    private static void readShorts(DataInputStream in, short[] a) throws IOException {
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            short v = in.readShort();
            if (i < a.length) a[i] = v;
        }
    }

    private static void bytes(DataOutputStream o, byte[] a, int n) throws IOException {
        o.writeInt(n);
        o.write(a, 0, n);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        byte[] a = new byte[in.readInt()];
        in.readFully(a);
        return a;
    }

    private static void readBytesInto(DataInputStream in, byte[] a) throws IOException {
        byte[] b = readBytes(in);
        System.arraycopy(b, 0, a, 0, Math.min(a.length, b.length));
    }

    private static void bools(DataOutputStream o, boolean[] a, int n) throws IOException {
        o.writeInt(n);
        byte[] b = CHUNK.get();
        int k = 0;
        for (int i = 0; i < n; i++) {
            b[k++] = (byte) (a[i] ? 1 : 0);
            if (k == b.length) {
                o.write(b, 0, k);
                k = 0;
            }
        }
        o.write(b, 0, k);
    }

    private static boolean[] readBools(DataInputStream in) throws IOException {
        boolean[] a = new boolean[in.readInt()];
        for (int i = 0; i < a.length; i++) a[i] = in.readBoolean();
        return a;
    }

    private static void readBools(DataInputStream in, boolean[] a) throws IOException {
        boolean[] b = readBools(in);
        System.arraycopy(b, 0, a, 0, Math.min(a.length, b.length));
    }

    static void intArr(DataOutputStream o, int[] a) throws IOException {
        if (a == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(a.length);
        for (int v : a) o.writeInt(v);
    }

    static int[] readIntArr(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0) return null;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = in.readInt();
        return a;
    }

    static void floatArr(DataOutputStream o, float[] a) throws IOException {
        if (a == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(a.length);
        for (float v : a) o.writeFloat(v);
    }

    static float[] readFloatArr(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0) return null;
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = in.readFloat();
        return a;
    }
}
