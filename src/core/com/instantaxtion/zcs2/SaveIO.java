package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Saved games. The save format has a version; the game's rules don't. A save holds everything the simulation
 * depends on, so a loaded game carries on exactly as it would have (the tests check this). The region itself is
 * rebuilt from its seed, then the saved tiles (rubble, blown bridges) are laid over it.
 */
public final class SaveIO {
    public static final int VERSION = 3;
    private static final int MAGIC = 0x5A435332; // "ZCS2"

    public static void save(World w, OutputStream os) throws IOException {
        DeflaterOutputStream z = new DeflaterOutputStream(os);
        DataOutputStream o = new DataOutputStream(new java.io.BufferedOutputStream(z, 1 << 16));
        o.writeInt(MAGIC);
        o.writeInt(VERSION);
        o.writeLong(w.c.rulesHash);
        // the region
        o.writeInt(w.r.W);
        o.writeInt(w.r.preset);
        o.writeLong(w.r.seed);
        o.writeLong(w.seed);
        o.writeUTF(w.scenario == null ? "" : w.scenario.id);
        o.writeInt(w.mode);
        o.write(w.r.tile);
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
        sectors(o, w.sec);
        dispatch(o, w.disp);
        military(o, w.mil);
        strategic(o, w.strat);
        gov(o, w.gov);
        news(o, w.news);
        stats(o, w.stats);
        effects(o, w.fx);
        flow(o, w.flow);
        modules(o, w);
        o.writeInt(MAGIC);
        o.flush();
        z.finish();
    }

    public static World load(Content c, InputStream is, int threads) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new InflaterInputStream(is), 1 << 16));
        if (in.readInt() != MAGIC) throw new IOException("not a Zombie City Sandbox 2 save");
        int ver = in.readInt();
        if (ver > VERSION) throw new IOException("this save is from a newer version of the game");
        long rules = in.readLong();
        int size = in.readInt(), preset = in.readInt();
        long rseed = in.readLong(), seed = in.readLong();
        String scen = in.readUTF();
        int mode = in.readInt();
        Region r = RegionGen.generate(size, preset, rseed);
        World w = new World(c, r, seed, threads);
        w.scenario = scen.length() == 0 ? null : c.scenario(scen);
        w.mode = mode;
        in.readFully(r.tile);
        byte[] floors = readBytes(in);
        System.arraycopy(floors, 0, r.bfloors, 0, Math.min(floors.length, r.bCount));
        for (int b = 0; b < r.bCount; b++) {
            if (r.bfloors[b] != 0) continue;
            for (int ty = r.by[b]; ty < r.by[b] + r.bh[b]; ty++)
                for (int tx = r.bx[b]; tx < r.bx[b] + r.bw[b]; tx++)
                    if (r.in(tx, ty) && r.building(ty * r.W + tx) == b) r.clearBuilding(ty * r.W + tx);
        }
        boolean[] alive = readBools(in);
        System.arraycopy(alive, 0, r.roads.ealive, 0, Math.min(alive.length, r.roads.edgeCount));
        readFacilities(in, w);
        r.rebuildNav(0, 0, r.W - 1, r.H - 1);
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
        readEnts(in, w.e);
        readVehicles(in, w.veh);
        readSquads(in, w);
        readSectors(in, w.sec);
        readDispatch(in, w.disp);
        readMilitary(in, w.mil);
        readStrategic(in, w.strat);
        readGov(in, w.gov);
        readNews(in, w.news);
        readStats(in, w.stats);
        readEffects(in, w.fx);
        readFlow(in, w.flow);
        readModules(in, w);
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

    private static void readFacilities(DataInputStream in, World w) throws IOException {
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
            f.pool = readIntArr(in);
            f.vpool = readIntArr(in);
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

    private static void readModules(DataInputStream in, World w) throws IOException {
        int n = in.readInt();
        for (int k = 0; k < n; k++) {
            String id = in.readUTF();
            byte[] data = new byte[in.readInt()];
            in.readFully(data);
            for (Module m : w.modules)
                if (m.id().equals(id)) m.load(new DataInputStream(new java.io.ByteArrayInputStream(data)));
        }
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

    private static void readEnts(DataInputStream in, Ents e) throws IOException {
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

    private static void readVehicles(DataInputStream in, Vehicles v) throws IOException {
        int n = in.readInt();
        v.ensure(n + 1);
        v.top = n;
        v.setNextUid(in.readInt());
        readFloats(in, v.x); readFloats(in, v.y); readFloats(in, v.head); readFloats(in, v.spd); readFloats(in, v.hp);
        readFloats(in, v.destX); readFloats(in, v.destY); readFloats(in, v.homeX); readFloats(in, v.homeY); readFloats(in, v.fuel);
        readFloats(in, v.stuck); readFloats(in, v.wpnT); readFloats(in, v.wpn2T); readFloats(in, v.timer); readFloats(in, v.mobT);
        readFloats(in, v.seg); readFloats(in, v.orbitA); readFloats(in, v.ghost); readFloats(in, v.alt);
        readShorts(in, v.type); readShorts(in, v.kills);
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
            floatArr(o, s.path);
            o.writeInt(s.pathI);
            o.writeUTF(s.name); o.writeUTF(s.label);
            o.writeInt(s.kills); o.writeInt(s.losses);
            o.writeBoolean(s.alive); o.writeBoolean(s.outside); o.writeBoolean(s.player);
        }
    }

    private static void readSquads(DataInputStream in, World w) throws IOException {
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
            s.path = readFloatArr(in);
            s.pathI = in.readInt();
            s.name = in.readUTF(); s.label = in.readUTF();
            s.kills = in.readInt(); s.losses = in.readInt();
            s.alive = in.readBoolean(); s.outside = in.readBoolean(); s.player = in.readBoolean();
            w.sq.list.add(s);
        }
    }

    // ------------------------------------------------------------------ sectors, calls, command

    private static void sectors(DataOutputStream o, Sectors s) throws IOException {
        int n = s.n;
        ints(o, s.popIn, n); ints(o, s.zIn, n); ints(o, s.bodies, n); ints(o, s.ambient, n);
        floats(o, s.inf, n); floats(o, s.hot, n); floats(o, s.panic, n); floats(o, s.firstZ, n);
        floats(o, s.driftX, n); floats(o, s.driftY, n);
        bools(o, s.alerted, n); bools(o, s.cam, n);
    }

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
        ints(o, d.secIncident, d.secIncident.length);
        bools(o, d.perimeter, d.perimeter.length);
    }

    private static void readDispatch(DataInputStream in, Dispatch d) throws IOException {
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
        readInts(in, d.secIncident);
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
        o.writeFloat(s.columnT); o.writeInt(s.startTownPop); o.writeInt(s.startCityPop); o.writeInt(s.infectedPending);
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
        s.columnT = in.readFloat(); s.startTownPop = in.readInt(); s.startCityPop = in.readInt(); s.infectedPending = in.readInt();
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

    private static void flow(DataOutputStream o, Flow f) throws IOException {
        int n = f.dist.length;
        short[] d = new short[n];
        for (int i = 0; i < n; i++) d[i] = f.stamp[i] == f.cur ? f.dist[i] : Flow.INF;
        shorts(o, d, n);
    }

    private static void readFlow(DataInputStream in, Flow f) throws IOException {
        short[] d = new short[f.dist.length];
        readShorts(in, d);
        f.nextStamp();
        for (int i = 0; i < d.length; i++)
            if (d[i] != Flow.INF) {
                f.dist[i] = d[i];
                f.stamp[i] = (short) f.cur;
            }
    }

    // ------------------------------------------------------------------ arrays

    private static void floats(DataOutputStream o, float[] a, int n) throws IOException {
        o.writeInt(n);
        for (int i = 0; i < n; i++) o.writeFloat(a[i]);
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
        for (int i = 0; i < n; i++) o.writeInt(a[i]);
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
        for (int i = 0; i < n; i++) o.writeShort(a[i]);
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
        for (int i = 0; i < n; i++) o.writeBoolean(a[i]);
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

    private static void intArr(DataOutputStream o, int[] a) throws IOException {
        if (a == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(a.length);
        for (int v : a) o.writeInt(v);
    }

    private static int[] readIntArr(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0) return null;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = in.readInt();
        return a;
    }

    private static void floatArr(DataOutputStream o, float[] a) throws IOException {
        if (a == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(a.length);
        for (float v : a) o.writeFloat(v);
    }

    private static float[] readFloatArr(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0) return null;
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = in.readFloat();
        return a;
    }
}
