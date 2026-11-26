package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Who the player is: from an all-seeing god down to one person in the street. Each role has its own powers (the
 * interface shows only its tools) and its own piece of the world; the AI runs everything else. Also the
 * country-scale campaign goals, quarantines, and the numbers kept for personal bests.
 */
public final class Roles extends Module {
    public static final int GOD = 0, PRESIDENT = 1, MILITARY = 2, INCIDENT = 3, PRECINCT = 4, SQUAD = 5, OFFICER = 6, SURVIVOR = 7;
    public static final String[] NAMES = {"God", "President", "Military commander", "Incident commander", "Precinct commander",
            "Squad leader", "Police officer", "Survivor"};
    public static final String[] IDS = {"god", "president", "military", "incident", "precinct", "squad", "officer", "survivor"};
    public static final String[] DESC = {
            "Every tool: paint the dead, place forces, strike, order anyone, decide everything.",
            "The nation's decisions (alert level, martial law, mobilisation, curfews, quarantines, evacuations, strikes, bridges) and every formation.",
            "The army's formations, air strikes, artillery and bridges. The civil government decides the rest.",
            "One city: its police, the soldiers sent to it, its evacuation. Ask for the army when you need it.",
            "One police station: send its squads out and move them. Everyone else follows their own orders.",
            "One squad: tell it where to go; it fights by itself.",
            "One police officer, with your patrol following you. Move with the stick; you shoot by yourself.",
            "One person in it all. Move with the stick; you shoot by yourself when armed."};

    public int role = GOD;
    /** Pass-and-play: up to four players share the game, each in a role; role/target above are the one playing now. */
    public static final int MAX_PLAYERS = 4;
    public int players = 1, current;
    public final int[] pRole = new int[MAX_PLAYERS], pTarget = new int[MAX_PLAYERS];
    /** The role's piece of the world: a city (incident), a police station (precinct), a squad (squad leader). */
    public int target = -1;
    public boolean[] quarantine;
    /** The state a "retake the state" campaign is about. */
    public int campaignState = -1;
    public float containedAt = -1;
    public boolean hadDead;
    /** Days the goal's condition has held (protect: the capital stayed clear). */
    public float heldFor;

    public Roles(World w) {
        super(w);
        quarantine = new boolean[w.r.settlements.size()];
    }

    @Override
    public String id() {
        return "roles";
    }

    public static int byId(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    /** The world mode a role plays in. */
    public static int modeFor(int role) {
        if (role == GOD) return World.MODE_SANDBOX;
        if (role == SURVIVOR || role == OFFICER) return World.MODE_SURVIVOR;
        return World.MODE_COMMANDER;
    }

    /** Does any player hold this role (for this city, station or squad; -1 = any)? The AI stands aside there. */
    public boolean holds(int r, int t) {
        sync();
        for (int k = 0; k < players; k++) if (pRole[k] == r && (t < 0 || pTarget[k] == t)) return true;
        return false;
    }

    private void sync() {
        pRole[current] = role;
        pTarget[current] = target;
    }

    /** Roles another player can take on (one person in the street is for the first player only). */
    public static boolean shareable(int r) {
        return r == PRESIDENT || r == MILITARY || r == INCIDENT || r == PRECINCT || r == SQUAD;
    }

    /** Another player joins in a role, its piece of the world chosen near (ox, oy). */
    public boolean addPlayer(int r, float ox, float oy) {
        if (players >= MAX_PLAYERS || !shareable(r)) return false;
        sync();
        int savedRole = role, savedTarget = target, savedMode = w.mode, savedStrikes = w.mil.strikesLeft;
        assign(r, ox, oy);
        pRole[players] = role;
        pTarget[players] = target;
        players++;
        role = savedRole;
        target = savedTarget;
        w.mode = savedMode;
        if (r == PRESIDENT || r == MILITARY) w.mil.strikesLeft = Math.max(savedStrikes, 6);
        else w.mil.strikesLeft = savedStrikes;
        w.gov.playerDecides = holds(PRESIDENT, -1);
        w.news.radio("Player " + players + " joins as " + labelOf(players - 1), ox, oy, News.COMMAND);
        return true;
    }

    /** Hands the game to player k. */
    public void switchTo(int k) {
        if (k < 0 || k >= players || k == current) return;
        sync();
        current = k;
        role = pRole[k];
        target = pTarget[k];
        // the first player's mode stays the world's (a person in the street ends the game when they die)
        if (k == 0 || modeFor(role) != World.MODE_SURVIVOR) w.mode = k == 0 ? modeFor(role) : (w.mode == World.MODE_SANDBOX && role == GOD ? World.MODE_SANDBOX : World.MODE_COMMANDER);
    }

    public String labelOf(int k) {
        sync();
        int sr = role, st = target;
        role = pRole[k];
        target = pTarget[k];
        String l = label();
        role = sr;
        target = st;
        return l;
    }

    /** Where player k's part of the world is (for the camera on a hand-over). */
    public float[] focusOf(int k) {
        sync();
        int r = pRole[k], t = pTarget[k];
        if (r == INCIDENT && t >= 0) return new float[]{w.r.settlements.get(t).cx * 16f, w.r.settlements.get(t).cy * 16f, 0.25f};
        if (r == PRECINCT && t >= 0) return new float[]{w.r.facilities.get(t).x, w.r.facilities.get(t).y, 0.5f};
        if (r == SQUAD && t >= 0 && t < w.sq.list.size() && w.sq.get(t).alive) {
            float[] p = w.sq.where(w.sq.get(t));
            return new float[]{p[0], p[1], 0.8f};
        }
        if (k == 0 && w.playerEnt >= 0 && w.e.loc[w.playerEnt] == Ents.FREE) return new float[]{w.e.x[w.playerEnt], w.e.y[w.playerEnt], 1.2f};
        Region.Settlement c = w.r.city();
        return new float[]{c.cx * 16f, c.cy * 16f, 0.08f};
    }

    public boolean commandsFormations() {
        return role == GOD || role == PRESIDENT || role == MILITARY;
    }

    public boolean quarantined(int settlement) {
        return w.command.quarantined(settlement);
    }

    /** What the role is about, for the HUD. */
    public String label() {
        String n = NAMES[role];
        if (role == INCIDENT && target >= 0) return n + ", " + w.r.settlements.get(target).name;
        if (role == PRECINCT && target >= 0) return n + ", " + w.r.facilities.get(target).name;
        if (role == SQUAD && target >= 0 && target < w.sq.list.size()) return n + ", " + w.sq.get(target).name;
        return n;
    }

    /** May the player give this squad orders? */
    public boolean canOrder(Squads.Squad s) {
        if (s == null || !s.alive) return false;
        switch (role) {
            case GOD:
            case PRESIDENT:
                return true;
            case MILITARY:
                return s.faction == Ents.SOLDIER;
            case INCIDENT: {
                float[] p = w.sq.where(s);
                int st = w.sec.settlement[w.r.sectorOfPx(p[0], p[1])];
                return st == target || (s.home >= 0 && w.r.facilities.get(s.home).settlement == target);
            }
            case PRECINCT:
                return s.home == target;
            case SQUAD:
                return s.id == target;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ setting up a role

    /** Gives the player their role at the start of a game (after the scenario is in place). */
    public void assign(int r, float ox, float oy) {
        role = r;
        target = -1;
        w.mode = modeFor(r);
        w.gov.playerDecides = r == PRESIDENT;
        if (w.mode == World.MODE_COMMANDER && (r == PRESIDENT || r == MILITARY)) w.mil.strikesLeft = 6;
        Region.Settlement near = w.r.settlementAt(ox, oy);
        switch (r) {
            case INCIDENT: {
                if (near == null || (near.kind != Region.S_CITY && near.kind != Region.S_TOWN)) near = w.r.city();
                target = near.id;
                break;
            }
            case PRECINCT:
                target = nearestFacility(ox, oy, Region.F_POLICE);
                break;
            case SQUAD: {
                int f = nearestFacility(ox, oy, Region.F_BASE);
                Region.Facility fac = f >= 0 ? w.r.facilities.get(f) : null;
                Content.SquadT t = fac != null ? w.forces.templateFor(fac, Ents.SOLDIER, 30) : null;
                if (t == null) {
                    f = nearestFacility(ox, oy, Region.F_POLICE);
                    fac = f >= 0 ? w.r.facilities.get(f) : null;
                    t = fac != null ? w.forces.templateFor(fac, Ents.COP, 30) : null;
                }
                if (t == null) break;
                Squads.Squad s = w.forces.deploy(fac, t);
                if (s == null) break;
                s.player = true;
                target = s.id;
                float dx = ox - fac.x, dy = oy - fac.y, d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                float hx = ox - dx / d * 400, hy = oy - dy / d * 400;
                if (!w.r.walkablePx(hx, hy)) {
                    hx = fac.x;
                    hy = fac.y;
                }
                s.faceX = dx / d;
                s.faceY = dy / d;
                w.sq.order(s, Squads.O_MOVE, hx, hy);
                break;
            }
            case OFFICER: {
                int f = nearestFacility(ox, oy, Region.F_POLICE);
                if (f < 0) break;
                Region.Facility fac = w.r.facilities.get(f);
                Squads.Squad s = w.forces.deploy(fac, w.c.squadT("patrol"));
                if (s == null) break;
                s.player = true;
                target = s.id;
                int me = s.mem[0];
                w.e.flags[me] |= Ents.F_PLAYER;
                w.playerEnt = me;
                w.sq.order(s, Squads.O_HOLD, w.e.x[me], w.e.y[me]);
                break;
            }
            default:
        }
    }

    private int nearestFacility(float x, float y, int kind) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != kind) continue;
            float dx = f.x - x, dy = f.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f.id;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ every second

    @Override
    public void update(float dt) {
        int z = w.totalZombies();
        if (z > 0) hadDead = true;
        if (hadDead && z == 0 && containedAt < 0) containedAt = (float) w.time;
        for (int st = 0; st < quarantine.length; st++) if (quarantine[st] && w.gov.evac[st]) w.gov.evac[st] = false;
        // your patrol keeps up with you
        if (role == OFFICER && target >= 0 && target < w.sq.list.size() && w.playerEnt >= 0 && w.e.loc[w.playerEnt] == Ents.FREE) {
            Squads.Squad s = w.sq.get(target);
            float px = w.e.x[w.playerEnt], py = w.e.y[w.playerEnt];
            if (s.alive && (Math.abs(s.gx - px) + Math.abs(s.gy - py) > 50 || s.order != Squads.O_HOLD)) {
                s.player = true;
                w.sq.order(s, Squads.O_HOLD, px, py);
            }
        }
        if (role == SQUAD && target >= 0 && !w.ended && w.mode != World.MODE_SANDBOX) {
            if (target >= w.sq.list.size() || !w.sq.get(target).alive) end(false, "Your squad is gone", "Every one of them fell.");
        }
        goals(dt);
    }

    private void goals(float dt) {
        Content.Scenario sc = w.scenario;
        if (sc == null || w.ended || sc.goal == null) return;
        float deadline = sc.days * 1440f;
        boolean late = sc.days > 0 && w.time >= deadline;
        String g = sc.goal;
        if (g.equals("holdcity")) {
            int alive = w.strat.cityAlive();
            if (alive < w.strat.startCityPop * 0.5f) end(false, "The capital has fallen", "Fewer than half its people are left, " + days() + " in.");
            else if (late) end(true, "The capital held", WorldView.fmt(alive) + " of " + WorldView.fmt(w.strat.startCityPop) + " people lived through " + sc.days + " days.");
        } else if (g.equals("evaccoast")) {
            int start = coastalStart(), now = coastalNow();
            int out = start - now;
            if (start > 0 && out >= start * 0.6f) end(true, "The coast is evacuated", WorldView.fmt(out) + " people got away from the coast.");
            else if (late) end(false, "Too many were left behind", WorldView.fmt(Math.max(0, out)) + " of the " + WorldView.fmt((int) (start * 0.6f)) + " needed got out.");
        } else if (g.equals("retakestate")) {
            int zs = deadInState(campaignState);
            String name = campaignState >= 0 && campaignState < w.r.states.size() ? w.r.states.get(campaignState).name : "the state";
            if (zs < 200 && w.time > 120) end(true, name + " is retaken", "The dead there are down to " + zs + ", " + days() + " in.");
            else if (late) end(false, "Out of time", WorldView.fmt(zs) + " of the dead still walk " + name + ".");
        } else if (g.equals("protectcap")) {
            Region.Settlement c = w.r.city();
            int zc = w.stats.zBySettlement[c.id];
            if (zc > 2000) end(false, c.name + " is overrun", "More than 2,000 of the dead got into the capital.");
            else if (late) end(true, c.name + " stands", "The capital held for " + sc.days + " days.");
        }
    }

    private String days() {
        int d = (int) (w.time / 1440);
        int h = (int) (w.time % 1440 / 60);
        return d + (d == 1 ? " day " : " days ") + h + (h == 1 ? " hour" : " hours");
    }

    private void end(boolean win, String title, String text) {
        w.ended = true;
        w.won = win;
        w.endTitle = title;
        w.endText = text;
        w.news.headline(title, -1, -1);
    }

    /** Settlements on the coast (a lot of open water close by). */
    public boolean coastal(Region.Settlement s) {
        if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN && s.kind != Region.S_VILLAGE) return false;
        int water = 0, all = 0;
        int rad = s.radius + 40;
        for (int a = 0; a < 24; a++) {
            double ang = a * Math.PI / 12;
            all++;
            if (Tile.water(w.r.t(s.cx + (int) (Math.cos(ang) * rad), s.cy + (int) (Math.sin(ang) * rad)))) water++;
        }
        return water * 6 >= all;
    }

    private int coastStart = -1;

    private int coastalStart() {
        if (coastStart < 0) {
            coastStart = 0;
            for (Region.Settlement s : w.r.settlements) if (coastal(s)) coastStart += s.residents;
        }
        return coastStart;
    }

    private int coastalNow() {
        int n = 0;
        for (Region.Settlement s : w.r.settlements) if (coastal(s)) n += w.stats.popBySettlement[s.id];
        return n;
    }

    public int deadInState(int st) {
        int n = 0;
        for (int s = 0; s < w.sec.n; s++) {
            int set = w.sec.settlement[s];
            int state = set >= 0 ? w.r.settlements.get(set).state : stateOfSector(s);
            if (state == st) n += w.sec.zIn[s] + w.sp.secZ[s];
        }
        for (Strategic.Horde h : w.strat.hordes) if (h.alive && stateAt(h.x, h.y) == st) n += h.count;
        return n;
    }

    private int[] sectorState;

    /** The state a sector lies in (nearest state capital city). */
    public int stateOfSector(int s) {
        if (sectorState == null) {
            sectorState = new int[w.sec.n];
            for (int k = 0; k < w.sec.n; k++) sectorState[k] = stateAt(w.sec.cx(k), w.sec.cy(k));
        }
        return sectorState[s];
    }

    public int stateAt(float x, float y) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (Region.State st : w.r.states) {
            Region.Settlement c = w.r.settlements.get(st.capitalCity);
            float dx = c.cx * 16f - x, dy = c.cy * 16f - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = st.id;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(role);
        o.writeInt(target);
        o.writeInt(quarantine.length);
        for (boolean q : quarantine) o.writeBoolean(q);
        o.writeInt(campaignState);
        o.writeFloat(containedAt);
        o.writeBoolean(hadDead);
        o.writeFloat(heldFor);
        o.writeInt(coastStart);
        sync();
        o.writeInt(players);
        o.writeInt(current);
        for (int k = 0; k < MAX_PLAYERS; k++) {
            o.writeInt(pRole[k]);
            o.writeInt(pTarget[k]);
        }
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        role = in.readInt();
        target = in.readInt();
        int n = in.readInt();
        for (int k = 0; k < n; k++) {
            boolean q = in.readBoolean();
            if (k < quarantine.length) quarantine[k] = q;
        }
        campaignState = in.readInt();
        containedAt = in.readFloat();
        hadDead = in.readBoolean();
        heldFor = in.readFloat();
        coastStart = in.readInt();
        if (in.available() > 0) {
            players = in.readInt();
            current = in.readInt();
            for (int k = 0; k < MAX_PLAYERS; k++) {
                pRole[k] = in.readInt();
                pTarget[k] = in.readInt();
            }
        } else {
            players = 1;
            current = 0;
            pRole[0] = role;
            pTarget[0] = target;
        }
    }

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        float dp = cam.dp;
        for (int st = 0; st < quarantine.length; st++) {
            if (!quarantine[st]) continue;
            Region.Settlement s = w.r.settlements.get(st);
            float x = cam.toScreenX(s.cx * 16f), y = cam.toScreenY(s.cy * 16f), r = Math.max(14 * dp, (s.radius + 12) * 16f * cam.zoom);
            if (x < -r * 1.5f || y < -r * 1.5f || x > cam.sw + r * 1.5f || y > cam.sh + r * 1.5f) continue;
            g.color(0x18FFC020);
            g.fillCircle(x, y, r);
            g.color(0x80000000);
            g.strokeCircle(x, y, r, 4 * dp);
            g.color(0xE8FFC020);
            g.strokeCircle(x, y, r, 2 * dp);
            view.label(g, "QUARANTINE", x, y - r - 5 * dp, 10.5f, 0xFFFFD060, Gfx.ALIGN_CENTER, true);
        }
    }
}
