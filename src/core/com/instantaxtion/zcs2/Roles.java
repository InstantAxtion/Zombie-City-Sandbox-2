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
    /** The officer's second patrol, keeping close (-1 if none). */
    public int backup = -1;
    public boolean[] quarantine;
    /** The state a "retake the state" campaign is about. */
    public int campaignState = -1;
    public float containedAt = -1;
    public boolean hadDead;
    /** Days the goal's condition has held (protect: the capital stayed clear). */
    public float heldFor;
    /** The campaign's starting figure (taken once the first count is in), and how long its losing condition has held. */
    public int baseline = -1;
    public float lowT;
    /** The goal's progress for the HUD: 0..1 towards winning, and a line saying where things stand. */
    public float progress;
    public String progressText = "";
    /** The precinct's levers: a police cordon on its district's edge, and its district being evacuated. */
    public boolean cordon, districtEvac;
    /** People of the precinct's district who got out (its own evacuation, or the town's), counted as safe. */
    public int districtOut;
    /** People caught (bitten or killed) in the precinct's district since the start. */
    public int districtLost;
    /** The district's open 911 calls: game seconds each has waited with no officers or soldiers at the scene. */
    public final java.util.TreeMap<Integer, Float> callWait = new java.util.TreeMap<Integer, Float>();
    /** The longest any district call has waited unanswered. */
    public float worstWait;

    /** People caught in sector s (for the precinct's count of its district). */
    public void lostAt(int s, int n) {
        if (n > 0 && role == PRECINCT && inDistrict(s)) districtLost += n;
    }
    /** The officer's calls answered (911 calls whose scene the officer reached), by incident id. */
    public final java.util.List<Integer> callsAnswered = new java.util.ArrayList<Integer>();
    /** Where the officer's shift began (their station): calls at its door don't count as going out to them. */
    public float beatX = -1, beatY = -1;
    /** The squads standing on the precinct's cordon posts. */
    private final java.util.List<Integer> cordonSquads = new java.util.ArrayList<Integer>();
    private float cordonT, evacCarry;

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

    /** A command campaign (not a sandbox, not a survival story). */
    public boolean campaign() {
        return w.scenario != null && "commander".equals(w.scenario.mode) && w.scenario.goal != null && w.scenario.goal.length() > 0;
    }

    /**
     * Does a player make the nation's decisions (the alert level, martial law, mobilisation, the national curfew)?
     * The President does, and God in a campaign. Mayors, police chiefs and governors still decide for their own
     * towns and states.
     */
    public boolean decidesNation() {
        sync();
        for (int k = 0; k < players; k++) if (pRole[k] == PRESIDENT || pRole[k] == GOD && k == 0 && campaign()) return true;
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
        w.gov.playerDecides = decidesNation();
        if (r == PRESIDENT || r == MILITARY) w.forces.generalCommands = false;
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
        // God in a campaign sits in the President's chair: the campaign is theirs to win, not the AI's
        boolean nation = r == PRESIDENT || r == GOD && campaign();
        w.gov.playerDecides = nation;
        // the player commands the army until they hand it to the general (Actions.generalCommands)
        w.forces.generalCommands = !(nation || r == MILITARY);
        if (w.mode == World.MODE_COMMANDER && (r == PRESIDENT || r == MILITARY)) w.mil.strikesLeft = 6;
        Region.Settlement near = w.r.settlementAt(ox, oy);
        switch (r) {
            case INCIDENT: {
                if (near == null || (near.kind != Region.S_CITY && near.kind != Region.S_TOWN)) near = w.r.city();
                target = near.id;
                break;
            }
            case PRECINCT:
                // in a campaign, a station on the front but not swallowed by it: there is a district to hold, and it will be hit
                target = campaign() ? Setup.safeStation(w, ox, oy, 300) : nearestFacility(ox, oy, Region.F_POLICE);
                if (target < 0) target = nearestFacility(ox, oy, Region.F_POLICE);
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
                if (campaign()) {
                    // a line to hold between the base and the outbreak, where the dead aren't yet
                    for (int k = 2; k <= 20; k++) {
                        float px = ox - dx * k / 20f, py = oy - dy * k / 20f;
                        if (w.r.walkablePx(px, py) && Setup.clearance(w, px, py) >= 1200) {
                            hx = px;
                            hy = py;
                            break;
                        }
                    }
                }
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
                // a station a few kilometres from where it began, not in it: you hear the calls before you meet it
                int f = Setup.safeStation(w, ox, oy, 2500);
                if (f < 0) f = nearestFacility(ox, oy, Region.F_POLICE);
                if (f < 0) break;
                Region.Facility fac = w.r.facilities.get(f);
                Squads.Squad s = w.forces.deploy(fac, w.c.squadT("patrol"));
                if (s == null) break;
                // and another patrol from your station holding with you
                Squads.Squad b = w.forces.deploy(fac, w.c.squadT("patrol"));
                if (b != null) {
                    b.player = true;
                    backup = b.id;
                }
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
        // contained: none of the dead, and none of the bitten left to turn (seen or not)
        if (hadDead && z == 0 && containedAt < 0 && w.strat.latent.isEmpty() && noInfected()) containedAt = (float) w.time;
        // the dead are back (painted, or a nest found): it isn't contained any more
        if (z > 0 && containedAt >= 0) containedAt = -1;
        for (int st = 0; st < quarantine.length; st++) if (quarantine[st] && w.gov.evac[st]) w.gov.evac[st] = false;
        // your patrol keeps up with you
        if (role == OFFICER && target >= 0 && target < w.sq.list.size() && w.playerEnt >= 0 && w.e.loc[w.playerEnt] == Ents.FREE) {
            Squads.Squad s = w.sq.get(target);
            float px = w.e.x[w.playerEnt], py = w.e.y[w.playerEnt];
            if (s.alive && (Math.abs(s.gx - px) + Math.abs(s.gy - py) > 50 || s.order != Squads.O_HOLD)) {
                s.player = true;
                w.sq.order(s, Squads.O_HOLD, px, py);
            }
            if (backup >= 0 && backup < w.sq.list.size()) {
                Squads.Squad b = w.sq.get(backup);
                float bx = px + 40, by = py + 30;
                if (!w.r.walkablePx(bx, by)) {
                    bx = px;
                    by = py;
                }
                if (b.alive && (Math.abs(b.gx - bx) + Math.abs(b.gy - by) > 120 || b.order != Squads.O_HOLD)) {
                    b.player = true;
                    w.sq.order(b, Squads.O_HOLD, bx, by);
                }
            }
        }
        if (role == PRECINCT && target >= 0) precinct(dt);
        if (role == OFFICER && w.playerEnt >= 0 && w.e.loc[w.playerEnt] == Ents.FREE) answerCalls();
        if (role == SQUAD && target >= 0 && !w.ended && w.mode != World.MODE_SANDBOX) {
            if (target >= w.sq.list.size() || !w.sq.get(target).alive) end(false, "Your squad is gone", "Every one of them fell.");
        }
        goals(dt);
    }

    // ------------------------------------------------------------------ the precinct's levers, the officer's calls

    public void setCordon(boolean on) {
        if (role != PRECINCT || target < 0 || cordon == on) return;
        cordon = on;
        Region.Facility f = w.r.facilities.get(target);
        w.news.radio(on ? f.name + ": a cordon round the district. Officers to the posts on its edge." : f.name + ": the cordon is lifted.", f.x, f.y, News.COMMAND);
        if (!on) releaseCordon();
    }

    public void setDistrictEvac(boolean on) {
        if (role != PRECINCT || target < 0 || districtEvac == on) return;
        districtEvac = on;
        Region.Facility f = w.r.facilities.get(target);
        w.news.radio(on ? f.name + ": the district is to be evacuated. Officers going door to door." : f.name + ": the district's evacuation is stopped.", f.x, f.y, News.COMMAND);
    }

    /** The cordon stands while the station is ours and has the officers to man it. */
    public boolean cordonHeld() {
        if (!cordon || role != PRECINCT || target < 0 || !w.strat.online(target)) return false;
        Region.Facility f = w.r.facilities.get(target);
        int n = w.forces.armedAt(f, Ents.COP);
        for (int id : cordonSquads) if (id < w.sq.list.size() && w.sq.get(id).alive) n += w.sq.get(id).n;
        return n >= 8;
    }

    /** Does the cordon stop the counted dead moving from sector a into sector b (from outside the district into it)? */
    public boolean blocks(int a, int b) {
        return cordon && inDistrict(b) && !inDistrict(a) && cordonHeld();
    }

    private void precinct(float dt) {
        Region.Facility f = w.r.facilities.get(target);
        // the district's calls: how long each waits with nobody armed at the scene
        if (!w.strategic) {
            for (Dispatch.Incident in : w.disp.incidents) {
                if (!in.open || in.zombies <= 0 || !inDistrict(in.sector)) continue;
                // (officers or soldiers in its street, or the next one)
                boolean there = w.sp.secCop[in.sector] + w.sp.secSol[in.sector] > 0;
                for (int d = 0; d < 8 && !there; d++) {
                    int t = w.sec.neighbour(in.sector, d);
                    if (t >= 0 && w.sp.secCop[t] + w.sp.secSol[t] > 0) there = true;
                }
                Float t = callWait.get(in.id);
                float nt = (t == null ? 0 : t) + (there ? 0 : dt);
                callWait.put(in.id, nt);
                worstWait = Math.max(worstWait, nt);
            }
            java.util.Iterator<java.util.Map.Entry<Integer, Float>> it = callWait.entrySet().iterator();
            while (it.hasNext()) {
                int id = it.next().getKey();
                if (id >= w.disp.incidents.size() || !w.disp.incidents.get(id).open) it.remove();
            }
        }
        if (districtEvac && f.settlement >= 0 && !w.command.quarantined(f.settlement) && w.strat.online(target)) {
            // door to door: the streets with none of the dead in them go first, at what the officers can manage
            float want = w.c.rule("districtEvacPerHour", 1500) / 60f * dt + evacCarry;
            int total = 0;
            int[] dl = districtSectors();
            for (int s : dl) if (w.sec.zIn[s] == 0) total += w.sec.popIn[s];
            int moved = 0;
            if (total > 0) {
                float share = Math.min(1, want / total);
                for (int s : dl) {
                    if (w.sec.zIn[s] > 0 || w.sec.popIn[s] == 0) continue;
                    int m = Math.min(w.sec.popIn[s], (int) (w.sec.popIn[s] * share));
                    w.sec.popIn[w.sec.touch(s)] -= m;
                    moved += m;
                }
            }
            evacCarry = total > 0 ? Math.max(0, Math.min(want - moved, 50)) : 0;
            if (moved > 0) {
                w.strat.sendOut(f.settlement, moved);
                districtOut += moved;
            }
        }
        if (cordon && !w.strategic) {
            cordonT -= dt;
            if (cordonT <= 0) {
                cordonT = 20;
                manCordon(f);
            }
        }
    }

    /** Posts on the district's edge, nearest the dead first, each with a squad from the station standing on it. */
    private void manCordon(Region.Facility f) {
        for (int k = cordonSquads.size() - 1; k >= 0; k--) {
            int id = cordonSquads.get(k);
            if (id >= w.sq.list.size() || !w.sq.get(id).alive || w.sq.get(id).home != target) cordonSquads.remove(k);
        }
        int want = 6;
        if (cordonSquads.size() >= want) return;
        // junctions in the district next to streets outside it, scored by how close the dead are
        RoadGraph g = w.r.roads;
        java.util.List<float[]> posts = new java.util.ArrayList<float[]>();
        for (int n = 0; n < g.nodeCount; n++) {
            int s = w.r.sectorOfPx(g.nx[n], g.ny[n]);
            if (!inDistrict(s)) continue;
            boolean edge = false;
            for (int d = 0; d < 8 && !edge; d++) {
                int t = w.sec.neighbour(s, d);
                if (t >= 0 && !inDistrict(t) && w.sec.home[t] > 0) edge = true;
            }
            if (!edge) continue;
            boolean close = false;
            for (float[] p : posts) if (Math.abs(p[0] - g.nx[n]) + Math.abs(p[1] - g.ny[n]) < 500) close = true;
            if (close) continue;
            int zd = w.sp.nearestZombie(w.e, g.nx[n], g.ny[n], 2500);
            // (with none of the dead in sight: the posts furthest out first, on the roads into the district)
            float score = zd >= 0 ? Math.abs(w.e.x[zd] - g.nx[n]) + Math.abs(w.e.y[zd] - g.ny[n]) : 10000 - Math.abs(f.x - g.nx[n]) - Math.abs(f.y - g.ny[n]);
            posts.add(new float[]{g.nx[n], g.ny[n], score});
        }
        java.util.Collections.sort(posts, new java.util.Comparator<float[]>() {
            public int compare(float[] a, float[] b) {
                return Float.compare(a[2], b[2]);
            }
        });
        for (int k = 0; k < posts.size() && cordonSquads.size() < want; k++) {
            float[] p = posts.get(k);
            boolean taken = false;
            for (int id : cordonSquads) {
                Squads.Squad s = w.sq.get(id);
                if (Math.abs(s.gx - p[0]) + Math.abs(s.gy - p[1]) < 300) taken = true;
            }
            if (taken) continue;
            Content.SquadT t = w.forces.templateFor(f, Ents.COP, 10);
            if (t == null) return;
            Squads.Squad s = w.forces.deploy(f, t);
            if (s == null) return;
            s.player = true;
            s.hold = 50;
            // facing out of the district
            float dx = p[0] - f.x, dy = p[1] - f.y, d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
            s.faceX = dx / d;
            s.faceY = dy / d;
            w.sq.order(s, Squads.O_CORDON, p[0], p[1]);
            cordonSquads.add(s.id);
        }
    }

    private void releaseCordon() {
        for (int id : cordonSquads) {
            if (id >= w.sq.list.size()) continue;
            Squads.Squad s = w.sq.get(id);
            if (!s.alive || s.home != target) continue;
            s.player = false;
            w.sq.goHome(s);
        }
        cordonSquads.clear();
    }

    /** The officer reached the scene of an open 911 call. */
    private void answerCalls() {
        float px = w.e.x[w.playerEnt], py = w.e.y[w.playerEnt];
        if (beatX < 0) {
            beatX = px;
            beatY = py;
        }
        float out = w.c.rule("callAwayFromStation", 600);
        for (Dispatch.Incident in : w.disp.incidents) {
            if (!in.open) continue;
            float dx = in.x - px, dy = in.y - py;
            if (dx * dx + dy * dy > 250 * 250 || callsAnswered.contains(in.id)) continue;
            // (out on the beat: not one that came to the station's door)
            float bx = in.x - beatX, by = in.y - beatY;
            if (bx * bx + by * by < out * out) continue;
            callsAnswered.add(in.id);
            w.news.radio("Officer on scene" + (in.settlement >= 0 ? " in " + w.r.settlements.get(in.settlement).name : "") + " (" + callsAnswered.size() + " calls answered)", in.x, in.y, News.UNIT);
        }
    }


    private boolean noInfected() {
        // (only awake sectors hold any of the infected)
        for (int s = w.sec.idx.first(); s >= 0; s = w.sec.idx.next(s)) if (w.sec.inf[s] >= 1) return false;
        return true;
    }

    /**
     * The goal this player is playing for. A national campaign is the President's, the military commander's, an
     * incident commander's (and God's); roles too small to decide it (a precinct, a squad, one officer, one
     * person) play for a goal of their own in it: "precinct", "squad" or "survive".
     */
    public String goal() {
        Content.Scenario sc = w.scenario;
        if (sc == null || sc.goal == null) return null;
        if (campaign()) {
            if (role == PRECINCT) return "precinct";
            if (role == SQUAD) return "squad";
            // one person can't hold anything; they play for what they can do: answer calls, or get to safety
            if (role == OFFICER) return "patrol";
            if (role == SURVIVOR) return "reach";
            // the coast's evacuation city by city: an incident commander's part is getting their own city out
            if (role == INCIDENT && "evaccoast".equals(sc.goal) && target >= 0) return "evaccity";
            // the army's part in it: getting them out alive (holding the roads, escorting, hunting the hordes)
            if (role == MILITARY && "evaccoast".equals(sc.goal)) return "evacmil";
        }
        return sc.goal;
    }

    /** Days a personal goal runs for (shorter than the national campaign's). */
    public int goalDays() {
        Content.Scenario sc = w.scenario;
        int d = sc == null ? 0 : sc.days;
        String g = goal();
        if (g == null || g.equals(sc.goal) || g.equals("evaccity") || g.equals("evacmil")) return d;
        return Math.max(1, Math.min(d, g.equals("precinct") || g.equals("patrol") ? 3 : 2));
    }

    /** Calls an officer must answer, the dead a squad must put down, for their goals. */
    public static final int CALLS_NEEDED = 4, SQUAD_KILLS = 60;

    /** Game seconds the dead may hold a fifth of the capital's streets before it's lost (protect the capital). */
    public static final float PROTECT_HOLD = 1440;

    /** The share of its district's people a precinct must keep alive. */
    public static final float PRECINCT_KEEP = 0.95f;

    /** What the player is playing for, in a sentence (the HUD and the start of the game show it). */
    public String goalText() {
        String g = goal();
        if (g == null) return "";
        int d = goalDays();
        String days = d + (d == 1 ? " day" : " days");
        if (g.equals("precinct") && target >= 0)
            return "Hold " + w.r.facilities.get(target).name + "'s district for " + days + ": keep the station, let no more than " + (100 - (int) (PRECINCT_KEEP * 100)) + "% of its people be caught (those you evacuate are safe), and leave no 911 call there unanswered for " + (int) w.c.rule("precinctCallWait", 90) + " minutes. Other stations won't answer them: they're yours. Cordon the district, evacuate it.";
        if (g.equals("evaccity") && target >= 0)
            return "Get " + (int) (w.c.rule("evacNeed", 0.5f) * 100) + "% of " + w.r.settlements.get(target).name + "'s people inland within " + days + ", before the dead reach them. Ordering the evacuation is yours.";
        if (g.equals("evacmil"))
            return "The civil government is getting the coast's people inland; keep them alive on the way: " + (int) (w.c.rule("evacNeed", 0.5f) * 100) + "% inland within " + days
                    + ", with no more than " + (int) (w.c.rule("evacLossMilitary", 0.03f) * 100) + "% of the coast's people caught. Hold the roads out, escort the columns, hunt the hordes.";
        if (g.equals("squad")) return "Put down " + SQUAD_KILLS + " of the dead within " + days + " and keep at least half your squad alive (and the dead out of your streets).";
        if (g.equals("patrol")) return "Answer " + CALLS_NEEDED + " 911 calls out on your beat (get to the scene, away from the station) within " + days + ", and stay alive.";
        if (g.equals("reach")) return "Get to safety within " + days + ": the army base, an evacuation point, a survivor settlement or the boats on the shore.";
        if (g.equals("survive")) return role == OFFICER ? "Stay alive with your patrol for " + days + "." : "Stay alive for " + days + ".";
        return w.scenario.desc;
    }

    /** Sectors of the player's precinct: the station's own town, nearer this station than any other there. */
    private boolean[] district;
    private int districtPop = -1;

    public boolean inDistrict(int s) {
        if (role != PRECINCT || target < 0 || !campaign()) return false;
        if (district == null) {
            district = new boolean[w.sec.n];
            Region.Facility me = w.r.facilities.get(target);
            for (int k : w.sec.idx.settlementSectors(me.settlement)) {
                if (w.sec.home[k] == 0) continue;
                float x = w.sec.cx(k), y = w.sec.cy(k);
                float dm = (me.x - x) * (me.x - x) + (me.y - y) * (me.y - y);
                boolean mine = dm < 1600f * 1600f;
                for (Region.Facility f : w.r.facilities)
                    if (mine && f != me && f.kind == Region.F_POLICE && f.settlement == me.settlement && (f.x - x) * (f.x - x) + (f.y - y) * (f.y - y) < dm) mine = false;
                district[k] = mine;
            }
            int m = 0;
            for (boolean b : district) if (b) m++;
            districtList = new int[m];
            m = 0;
            for (int k = 0; k < district.length; k++) if (district[k]) districtList[m++] = k;
        }
        return s >= 0 && s < district.length && district[s];
    }

    private int[] districtList;
    private static final int[] NONE = new int[0];

    /** The sectors of the player's precinct district, ascending (none if there isn't one now). */
    private int[] districtSectors() {
        if (role != PRECINCT || target < 0 || !campaign()) return NONE;
        inDistrict(-1); // (works the district out the first time)
        return districtList;
    }

    private int districtAlive() {
        int n = 0;
        for (int s : districtSectors()) n += w.sec.popIn[s] + w.sp.secCiv[s] + (int) w.sec.inf[s];
        return n;
    }

    private void goals(float dt) {
        Content.Scenario sc = w.scenario;
        if (sc == null || w.ended || w.watching || sc.goal == null || w.mode == World.MODE_SANDBOX && w.watching) return;
        // nothing is judged before the first count of everyone is in
        if (w.time < 60) return;
        String g = goal();
        int gd = goalDays();
        float deadline = gd * 1440f;
        boolean late = gd > 0 && w.time >= deadline;
        if (g.equals("patrol")) {
            int n = callsAnswered.size();
            progress = Math.min(1, n / (float) CALLS_NEEDED);
            progressText = n + " of " + CALLS_NEEDED + " calls answered; " + days() + " of " + gd + (gd == 1 ? " day" : " days");
            if (w.playerEnt < 0 || w.e.loc[w.playerEnt] != Ents.FREE) return;
            if (n >= CALLS_NEEDED && late) end(true, "Your shift is over", "You answered " + n + " calls and lived through " + gd + (gd == 1 ? " day" : " days") + " of it.");
            else if (late) end(false, "Too few calls answered", "You answered " + n + " of the " + CALLS_NEEDED + " calls needed in " + gd + (gd == 1 ? " day" : " days") + ".");
            return;
        }
        if (g.equals("reach")) {
            progress = Math.min(1, (float) (w.time / Math.max(1, deadline)));
            progressText = "Find safety: " + days() + " of " + gd + (gd == 1 ? " day" : " days") + " gone";
            if (w.playerEnt < 0 || w.e.loc[w.playerEnt] != Ents.FREE) return;
            float[] safe = w.strat.safeAt(w.e.x[w.playerEnt], w.e.y[w.playerEnt]);
            if (safe != null) end(true, "You reached safety", "You made it to " + Strategic.SAFE_NAMES[(int) safe[2]] + ", " + days() + " in.");
            else if (late) end(false, "Still out there", "Alive, but not safe: " + gd + (gd == 1 ? " day" : " days") + " gone and nowhere reached.");
            return;
        }
        if (g.equals("survive")) {
            // (the survival stories judge this in Strategic; in a campaign it's the player's own goal)
            if (!campaign()) return;
            progress = (float) (w.time / Math.max(1, deadline));
            progressText = "Alive " + days() + " of " + gd + (gd == 1 ? " day" : " days");
            if (w.playerEnt >= 0 && w.e.loc[w.playerEnt] == Ents.FREE && late) end(true, "You survived", "You lived through " + gd + " days of it.");
            return;
        }
        if (g.equals("precinct")) {
            // the district's people at the start; those caught there since (bitten, killed) against what may be lost.
            // People who got out (its own evacuation, or the town's) are safe: they can't be caught there.
            if (baseline < 0) baseline = Math.max(1, districtAlive());
            int cap = (int) (baseline * (1 - PRECINCT_KEEP));
            String name = target >= 0 ? w.r.facilities.get(target).name : "your precinct";
            progress = Math.min(1, (float) (w.time / Math.max(1, deadline)));
            float wait = w.c.rule("precinctCallWait", 90);
            progressText = News.num(districtLost) + " of " + News.num(baseline) + " in " + name + "'s district caught (at most " + News.num(cap) + "); " + News.num(districtOut) + " got out"
                    + (worstWait > 0 ? "; longest unanswered call " + (int) worstWait + " of " + (int) wait + " min" : "");
            boolean station = target < 0 || w.strat.online(target);
            if (!station) end(false, "Your station has fallen", "The dead took " + name + ", " + days() + " in.");
            else if (worstWait >= wait) end(false, "A call went unanswered", "Nobody went to a 911 call in " + name + "'s district for " + News.num((int) wait) + " minutes, " + days() + " in.");
            else if (districtLost > cap) end(false, "Your district has fallen", News.num(districtLost) + " of the " + News.num(baseline) + " people in " + name + "'s district were caught, " + days() + " in.");
            else if (late) end(true, "Your district held", "Only " + News.num(districtLost) + " of its " + News.num(baseline) + " people were caught in " + gd + (gd == 1 ? " day" : " days") + "; " + News.num(districtOut) + " got out.");
            return;
        }
        if (g.equals("evaccity")) {
            if (baseline < 0) baseline = Math.max(1, w.stats.popBySettlement[target]);
            int safe = w.strat.outBy[target];
            for (Region.Settlement b : w.r.settlements) if (!coastal(b)) safe += w.strat.refuge[target][b.id];
            int need = (int) (baseline * w.c.rule("evacNeed", 0.5f)), lost = w.stats.lostBySettlement[target];
            String name = w.r.settlements.get(target).name;
            progress = safe / (float) Math.max(1, need);
            progressText = News.num(safe) + " of the " + News.num(need) + " of " + name + "'s people needed are safe inland";
            if (safe >= need) end(true, name + " is evacuated", News.num(safe) + " of its people reached safety inland, " + days() + " in.");
            else if (lost > baseline * 0.4f) end(false, name + " is lost", News.num(lost) + " of its people were caught before they could get away.");
            else if (late) end(false, "Too many were left behind", News.num(safe) + " of the " + News.num(need) + " needed got out of " + name + " in time.");
            return;
        }
        if (g.equals("squad")) {
            if (target < 0 || target >= w.sq.list.size()) return;
            Squads.Squad s = w.sq.get(target);
            if (baseline < 0) baseline = Math.max(1, s.n);
            float[] p = w.sq.where(s);
            int sec = w.r.sectorOfPx(p[0], p[1]);
            int zHere = w.sec.zIn[sec] + w.sp.secZ[sec];
            progress = Math.min(1, (float) (w.time / Math.max(1, deadline)));
            progressText = s.n + " of " + baseline + " of your squad standing; " + s.kills + " of " + SQUAD_KILLS + " of the dead put down; " + News.num(zHere) + " in your streets";
            lowT = zHere > 150 ? lowT + dt : Math.max(0, lowT - dt);
            if (s.alive && s.n * 2 < baseline) end(false, "Your squad is broken", "Only " + s.n + " of the " + baseline + " are left, " + days() + " in.");
            else if (lowT >= 240) end(false, "Your sector is overrun", "The dead held your streets for four hours, " + days() + " in.");
            else if (late && s.alive && s.kills >= SQUAD_KILLS) end(true, "Your squad held", s.n + " of the " + baseline + " made it through " + gd + (gd == 1 ? " day" : " days") + ", with " + s.kills + " of the dead put down.");
            else if (late && s.alive) end(false, "Your squad sat it out", "Only " + s.kills + " of the " + SQUAD_KILLS + " of the dead needed were put down in " + gd + (gd == 1 ? " day" : " days") + ".");
            return;
        }
        Region.Settlement cap = w.r.city();
        if (g.equals("holdcity")) {
            // the capital's own people alive: at home, or safe somewhere else (evacuated, sheltering in another town)
            int alive = Math.max(0, w.strat.cityAlive() - w.strat.hosted(cap.id)) + w.strat.awayFrom(cap.id);
            if (baseline < 0) baseline = Math.max(1, alive);
            progress = alive / (float) baseline;
            progressText = News.num(alive) + " of " + News.num(baseline) + " of the capital's people alive (half must live)";
            lowT = alive < baseline * 0.5f ? lowT + dt : 0;
            if (lowT >= 360) end(false, "The capital has fallen", "Fewer than half its people are left, " + days() + " in.");
            else if (late) end(true, "The capital held", News.num(alive) + " of " + News.num(baseline) + " of its people lived through " + sc.days + " days.");
        } else if (g.equals("evaccoast") || g.equals("evacmil")) {
            if (baseline < 0) baseline = Math.max(1, coastalNow());
            int safe = coastalSafe(), need = (int) (baseline * w.c.rule("evacNeed", 0.5f));
            int lost = 0;
            for (Region.Settlement s : w.r.settlements) if (coastal(s)) lost += w.stats.lostBySettlement[s.id];
            progress = safe / (float) Math.max(1, need);
            int lossCap = (int) (baseline * w.c.rule("evacLossMilitary", 0.03f));
            progressText = News.num(safe) + " of the " + News.num(need) + " needed are safe inland" + (g.equals("evacmil") ? "; " + News.num(lost) + " caught (at most " + News.num(lossCap) + ")" : "");
            if (g.equals("evacmil") && lost > lossCap)
                end(false, "Too many caught on the roads", News.num(lost) + " of the coast's people were caught before they reached safety, " + days() + " in.");
            else if (safe >= need) end(true, "The coast is evacuated", News.num(safe) + " people from the coast reached safety inland, " + days() + " in.");
            else if (lost > baseline * 0.4f) end(false, "The coast is lost", News.num(lost) + " of the coast's people were caught before they could get away.");
            else if (late) end(false, "Too many were left behind", News.num(safe) + " of the " + News.num(need) + " needed got out in time.");
        } else if (g.equals("retakestate")) {
            int zs = deadInState(campaignState);
            if (baseline < 0) baseline = Math.max(1, zs);
            int goal = Math.max(300, baseline / 100);
            String name = campaignState >= 0 && campaignState < w.r.states.size() ? w.r.states.get(campaignState).name : "the state";
            progress = 1 - Math.min(1, zs / (float) baseline);
            progressText = News.num(zs) + " of the dead left in " + name + " (bring them below " + News.num(goal) + ")";
            lowT = zs < goal ? lowT + dt : 0;
            if (lowT >= 120) end(true, name + " is retaken", "The dead there are down to " + News.num(zs) + ", " + days() + " in.");
            else if (late) end(false, "Out of time", News.num(zs) + " of the dead still walk " + name + ".");
        } else if (g.equals("protectcap")) {
            float share = infestedShare(cap.id);
            int zc = w.stats.zBySettlement[cap.id];
            if (baseline < 0) baseline = Math.max(1, w.stats.popBySettlement[cap.id]);
            boolean bad = share > 0.2f || zc > baseline * 0.03f;
            lowT = bad ? lowT + dt : Math.max(0, lowT - dt * 0.5f);
            progress = late ? 1 : (float) (w.time / Math.max(1, deadline));
            int left = (int) Math.ceil(Math.max(0, PROTECT_HOLD - lowT) / 60f);
            progressText = (int) (share * 100) + "% of the capital's streets have the dead in them"
                    + (bad ? " (too many: clear them " + (left <= 1 ? "within the hour" : "within " + left + " hours") + " or the capital is lost)" : "");
            if (lowT >= PROTECT_HOLD) end(false, cap.name + " is overrun", "The dead held a fifth of the capital's streets for a whole day, " + days() + " in.");
            else if (late) end(true, cap.name + " stands", "The capital held for " + sc.days + " days.");
        }
    }

    /** The reported dead in a settlement are in the player's precinct district (calls there are the player's). */
    public boolean playerDistrict(float x, float y) {
        return role == PRECINCT && campaign() && inDistrict(w.r.sectorOfPx(x, y));
    }

    /** The share of a settlement's streets (sectors) with a real number of the dead in them. */
    public float infestedShare(int st) {
        int all = 0, bad = 0;
        for (int s : w.sec.idx.bySettlement[st]) {
            if (w.sec.home[s] == 0) continue;
            all++;
            if (w.sec.zIn[s] + w.sp.secZ[s] >= 20) bad++;
        }
        return all == 0 ? 0 : bad / (float) all;
    }

    /** People of the coast who are safe inland (or out of the country). */
    public int coastalSafe() {
        int n = 0;
        for (Region.Settlement a : w.r.settlements) {
            if (!coastal(a)) continue;
            n += w.strat.outBy[a.id];
            for (Region.Settlement b : w.r.settlements) if (!coastal(b)) n += w.strat.refuge[a.id][b.id];
        }
        return n;
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
    private boolean[] coastalCache;

    public boolean coastal(Region.Settlement s) {
        if (coastalCache == null) {
            coastalCache = new boolean[w.r.settlements.size()];
            for (Region.Settlement t : w.r.settlements) coastalCache[t.id] = coastalNow(t);
        }
        return coastalCache[s.id];
    }

    private boolean coastalNow(Region.Settlement s) {
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
        // (only awake sectors hold any of the dead)
        for (int s = w.sec.idx.first(); s >= 0; s = w.sec.idx.next(s)) {
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
        o.writeInt(baseline);
        o.writeFloat(lowT);
        o.writeInt(backup);
        o.writeBoolean(cordon);
        o.writeBoolean(districtEvac);
        o.writeInt(districtOut);
        o.writeFloat(cordonT);
        o.writeFloat(evacCarry);
        o.writeInt(callsAnswered.size());
        for (int id : callsAnswered) o.writeInt(id);
        o.writeInt(cordonSquads.size());
        for (int id : cordonSquads) o.writeInt(id);
        o.writeInt(districtLost);
        o.writeFloat(beatX);
        o.writeFloat(beatY);
        o.writeFloat(worstWait);
        o.writeInt(callWait.size());
        for (java.util.Map.Entry<Integer, Float> c : callWait.entrySet()) {
            o.writeInt(c.getKey());
            o.writeFloat(c.getValue());
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
            if (in.available() > 0) {
                baseline = in.readInt();
                lowT = in.readFloat();
            }
            if (in.available() > 0) backup = in.readInt();
            if (in.available() > 0) {
                cordon = in.readBoolean();
                districtEvac = in.readBoolean();
                districtOut = in.readInt();
                cordonT = in.readFloat();
                evacCarry = in.readFloat();
                callsAnswered.clear();
                for (int k = in.readInt(); k > 0; k--) callsAnswered.add(in.readInt());
                cordonSquads.clear();
                for (int k = in.readInt(); k > 0; k--) cordonSquads.add(in.readInt());
                districtLost = in.readInt();
                beatX = in.readFloat();
                beatY = in.readFloat();
                worstWait = in.readFloat();
                callWait.clear();
                for (int k = in.readInt(); k > 0; k--) callWait.put(in.readInt(), in.readFloat());
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
