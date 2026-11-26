package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The chain of command: a mayor and a police chief for every city and town, a governor for every state, the
 * president, and the general commanding the army. None of them sees the truth. Each works from the reported
 * picture: what 911 calls, units and the news have told them, late and short of the real count, and far worse
 * once the telephone exchange is lost. They decide with it: shelter in place, curfews, evacuations, quarantines,
 * calling in help, blowing bridges, abandoning a city, defending the capital. Each has an approval rating that
 * follows the results; the ones who fail are replaced. In Commander mode the player makes the mayors' and
 * governors' calls (Gov.playerDecides); the reported picture still shapes what the general and president do.
 */
public final class Command extends Module {
    public static final int MAYOR = 0, CHIEF = 1, GOVERNOR = 2, PRESIDENT = 3, GENERAL = 4;
    public static final String[] TITLES = {"Mayor", "Police Chief", "Governor", "President", "General"};

    public static final class Official {
        public int role, where, nameId, replaced;
        public float approval = 62, lowT, cool;
        /** What this official last asked for help over (the reported dead then): asks again only if it doubles. */
        public float asked;
        public String last = "";
        public double lastT = -1;
    }

    public final List<Official> officials = new ArrayList<Official>();
    /** The reported picture: what the authorities believe, per settlement. */
    public float[] repZ, repSectors;
    private int[] repZi;
    public boolean[] shelter, quarantine, abandoned, helpAsked, alerted;
    private float[] calmT;
    public boolean bridgesAuthorised;
    private float hourT, capitalT, fedT;
    /** Game seconds the national picture has stayed below each alert level's lowering mark, and quiet for the curfew. */
    private final float[] downT = new float[5];
    private float curfewCalmT;
    /** The alert level when the president last looked (a change by anyone restarts the wait to lower it). */
    private int lastLevel;
    private int[] phoneFacility; // per phone network: the exchange's facility id

    public Command(World w) {
        super(w);
        int n = w.r.settlements.size();
        repZ = new float[n];
        repSectors = new float[n];
        repZi = new int[n];
        shelter = new boolean[n];
        quarantine = new boolean[n];
        abandoned = new boolean[n];
        helpAsked = new boolean[n];
        alerted = new boolean[n];
        calmT = new float[n];
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
            add(MAYOR, s.id);
            add(CHIEF, s.id);
        }
        for (Region.State st : w.r.states) add(GOVERNOR, st.id);
        if (w.r.states.isEmpty()) add(GOVERNOR, -1);
        add(PRESIDENT, -1);
        add(GENERAL, -1);
        phoneFacility = new int[Math.max(1, w.r.phoneCount)];
        java.util.Arrays.fill(phoneFacility, -1);
        for (Region.Facility f : w.r.facilities)
            if (f.kind == Region.F_PHONE && f.network >= 0 && f.network < phoneFacility.length) phoneFacility[f.network] = f.id;
    }

    private void add(int role, int where) {
        Official o = new Official();
        o.role = role;
        o.where = where;
        o.nameId = 900000 + officials.size() * 13;
        officials.add(o);
    }

    @Override
    public String id() {
        return "command";
    }

    /** An official's temper: cautious (acts early, evacuates and quarantines readily), steady, or reckless. */
    public static final int CAUTIOUS = 0, STEADY = 1, RECKLESS = 2;
    static final String[] STANCE = {"cautious: quick to lock down and evacuate", "steady", "reckless: slow to give up a town or ask for help"};

    /** Each official's temper comes with the person (a replacement is a new person, maybe of another mind). */
    public int trait(Official o) {
        return Rng.hashInt(w.seed, o.nameId, o.replaced * 7L + o.role, 3);
    }

    /** How far the official lets things go before acting, against the rules' marks (cautious lower, reckless higher). */
    float temper(Official o) {
        int t = trait(o);
        return t == CAUTIOUS ? 0.5f : t == RECKLESS ? 2.2f : 1;
    }

    public String name(Official o) {
        return TITLES[o.role] + " " + Names.person(w.seed, o.nameId + o.replaced * 1000);
    }

    public Official official(int role, int where) {
        for (Official o : officials) if (o.role == role && o.where == where) return o;
        return null;
    }

    public boolean quarantined(int st) {
        return st >= 0 && st < quarantine.length && quarantine[st];
    }

    /** Has the settlement been quiet (nothing reported) for this many game seconds? */
    public boolean calm(int st, float secs) {
        return st >= 0 && st < calmT.length && calmT[st] >= secs;
    }

    public boolean sheltering(int st) {
        return st >= 0 && st < shelter.length && shelter[st];
    }

    public boolean isAbandoned(int st) {
        return st >= 0 && st < abandoned.length && abandoned[st];
    }

    /** A town declared clear (Recovery): the officials believe it. */
    public void declaredClear(int st) {
        if (st < 0 || st >= repZ.length) return;
        repZ[st] = 0;
        repSectors[st] = 0;
        // a town taken back: its police return to it
        abandoned[st] = false;
    }

    /** What the authorities believe is in each settlement (the general plans with this, not the truth). */
    public int[] reportedZ() {
        for (int i = 0; i < repZ.length; i++) repZi[i] = (int) repZ[i];
        return repZi;
    }

    public float nationalReported() {
        float t = 0;
        for (float v : repZ) t += v;
        return t;
    }

    /** Is the settlement's telephone exchange working? Without it, calls and reports barely get through. */
    public boolean phonesUp(int st) {
        if (st < 0) return true;
        Region.Settlement s = w.r.settlements.get(st);
        if (s.phone < 0 || s.phone >= phoneFacility.length || phoneFacility[s.phone] < 0) return true;
        return w.strat.online(phoneFacility[s.phone]);
    }

    // ------------------------------------------------------------------ the reported picture

    private void beliefs(float dt) {
        int n = repZ.length;
        float[] calls = new float[n];
        // open 911 calls still being seen (in fast-forward nobody is taking calls: the reports come in as numbers)
        if (!w.strategic)
            for (Dispatch.Incident in : w.disp.incidents)
                if (in.open && in.settlement >= 0 && in.settlement < n && w.time - in.lastSeen < 60) {
                    calls[in.settlement] += in.zombies;
                    alerted[in.settlement] = true;
                }
        int[] sectors = new int[n], infected = new int[n];
        for (int s = 0; s < w.sec.n; s++) {
            int st = w.sec.settlement[s];
            if (st < 0 || st >= n) continue;
            sectors[st]++;
            if (w.sec.zIn[s] + w.sp.secZ[s] > 0) infected[st]++;
        }
        float detectUp = w.c.rule("reportShare", 0.8f), detectDown = w.c.rule("reportShareNoPhones", 0.3f);
        float lagUp = w.c.rule("reportLag", 60), lagDown = w.c.rule("reportLagNoPhones", 400);
        float clearLag = w.c.rule("reportClearLag", 3);
        for (int st = 0; st < n; st++) {
            boolean phones = phonesUp(st);
            float truth = w.stats.zBySettlement[st];
            if (truth > 0 && w.strategic && !alerted[st]) {
                // in fast-forward the calls are taken as read, once there are enough of the dead to be seen
                if (truth >= 5) {
                    alerted[st] = true;
                    Region.Settlement s = w.r.settlements.get(st);
                    w.gov.reportedAt(st, s.cx * 16f, s.cy * 16f);
                }
            }
            // what reaches the authorities: a share of the truth (far less with the phones down); the panic online
            // inflates it a little
            float share = phones ? detectUp : detectDown;
            // a drone over the town: the officials see what it sees
            boolean drone = w.aai.seen(st);
            if (drone) share = Math.max(share, 0.95f);
            float target = alerted[st] ? truth * share * (1 + 0.3f * w.media.social[st]) : 0;
            target = Math.max(target, calls[st] * (phones ? 1 : detectDown / detectUp));
            float lag = phones ? lagUp : lagDown;
            if (drone) lag = Math.min(lag, lagUp * 0.5f);
            // bad news travels faster than good: officials are slow to believe it's over
            if (target < repZ[st]) lag *= clearLag;
            repZ[st] += (target - repZ[st]) * Math.min(1, dt / lag);
            if (repZ[st] < 0.5f && target == 0) repZ[st] = 0;
            float sTarget = sectors[st] == 0 ? 0 : infected[st] / (float) sectors[st] * (phones ? 1 : 0.5f);
            repSectors[st] += (sTarget - repSectors[st]) * Math.min(1, dt / lag);
            if (truth == 0 && repZ[st] == 0) alerted[st] = false;
            calmT[st] = repZ[st] < 2 ? calmT[st] + dt : 0;
        }
    }

    // ------------------------------------------------------------------ decisions

    private void decide(Official o, String what, float x, float y, boolean headline) {
        o.last = what;
        o.lastT = w.time;
        String text = name(o) + (o.where >= 0 && o.role <= CHIEF ? " (" + w.r.settlements.get(o.where).name + ")" : "") + ": " + what;
        if (headline) w.news.headline(text, x, y);
        else {
            w.news.radio(text, x, y, News.COMMAND);
            w.stats.event(text, x, y);
        }
    }

    public void setQuarantine(int st, boolean on) {
        if (st < 0 || st >= quarantine.length || quarantine[st] == on) return;
        quarantine[st] = on;
        Region.Settlement s = w.r.settlements.get(st);
        if (on) w.gov.setEvac(st, false);
        Official g = official(GOVERNOR, s.state);
        if (g == null) g = official(PRESIDENT, -1);
        decide(g, on ? "QUARANTINE of " + s.name + ". Checkpoints on every road out. No one leaves." : "Quarantine of " + s.name + " lifted.", s.cx * 16f, s.cy * 16f, true);
    }

    private void mayor(Official o, float dt) {
        Region.Settlement s = w.r.settlements.get(o.where);
        int st = s.id;
        float rz = repZ[st];
        float x = s.cx * 16f, y = s.cy * 16f;
        float k = temper(o);
        if (!shelter[st] && rz > w.c.rule("shelterAt", 15) * k) {
            shelter[st] = true;
            decide(o, "everyone in " + s.name + " is to shelter in place, lock their doors and stay off the streets.", x, y, false);
        } else if (shelter[st] && calmT[st] > w.c.rule("shelterLiftAfter", 360)) {
            shelter[st] = false;
            decide(o, "the shelter order in " + s.name + " is lifted. People can go out again.", x, y, false);
        }
        // evacuate a town that's being lost; not a big city over a few streets (the capital last of all)
        float evacAt = Math.max(w.c.rule("evacuateAt", 400), s.residents * (s.capital ? 0.05f : s.kind == Region.S_CITY ? 0.02f : 0.01f)) * k;
        boolean spiralling = rz > evacAt || repSectors[st] > Math.min(0.9f, (s.capital ? 0.45f : 0.3f) * k);
        if (!w.gov.evacuating(st) && !quarantine[st] && (w.gov.level >= 2 || w.roles.decidesNation()) && spiralling) {
            w.gov.setEvac(st, true);
            decide(o, "orders the evacuation of " + s.name + ".", x, y, false);
        } else if (w.gov.evacuating(st) && calmT[st] > w.c.rule("evacLiftAfter", 720) && !(w.scenario != null && "evaccoast".equals(w.scenario.goal) && w.roles.coastal(s))) {
            w.gov.setEvac(st, false);
            decide(o, "it is safe again. People may return to " + s.name + ".", x, y, true);
        }
        // calling for help: the state's Guard (again only if it gets much worse)
        if (rz > Math.max(150, policeAt(st) * 0.5f) * k && (!helpAsked[st] || rz > o.asked * 2.5f)) {
            helpAsked[st] = true;
            o.asked = rz;
            w.mil.guardT = Math.min(w.mil.guardT, 0);
            decide(o, "asks the governor for the National Guard.", x, y, false);
        }
        if (helpAsked[st] && calmT[st] > 720) helpAsked[st] = false;
    }

    private int policeAt(int st) {
        int n = 0;
        for (Region.Facility f : w.r.facilities) if (f.settlement == st && f.kind == Region.F_POLICE) n += w.forces.armedAt(f, Ents.COP);
        return n;
    }

    private int policeStart(int st) {
        Region.Settlement s = w.r.settlements.get(st);
        return (int) (s.residents * w.c.rule("policePerResident", 0.0022f)) + 8;
    }

    private void chief(Official o) {
        Region.Settlement s = w.r.settlements.get(o.where);
        int st = s.id;
        float rz = repZ[st];
        // the army is asked for once at each step the outbreak there gets worse (not every few seconds)
        if (rz > 150 * temper(o)) w.mil.requestArmy(st, (int) rz);
        // the force is spent and the town is lost: pull out what's left
        int left = policeAt(st), start = Math.max(1, policeStart(st));
        if (!abandoned[st] && !s.capital && left < start * 0.25f && rz > w.stats.popBySettlement[st] * 0.3f + 100) abandon(o, st);
    }

    /** The police give a town up: its squads come home, its calls go unanswered by the police, the army is asked to take it. */
    void abandon(Official o, int st) {
        if (abandoned[st]) return;
        Region.Settlement s = w.r.settlements.get(st);
        abandoned[st] = true;
        Official m = official(MAYOR, st);
        if (o != null) decide(o, s.name + " can't be held. The police are pulling out; the army takes the town.", s.cx * 16f, s.cy * 16f, true);
        if (m != null) m.approval -= 20;
        w.disp.abandon(st);
        w.mil.armyAsked[st] = 0;
        w.mil.armyAskedT[st] = -1e9f;
        w.mil.requestArmy(st, (int) Math.max(150, repZ[st]));
    }

    private void governor(Official o) {
        float stateZ = 0;
        for (Region.Settlement s : w.r.settlements) {
            if (s.state != o.where && o.where >= 0) continue;
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
            int st = s.id;
            float rz = repZ[st];
            stateZ += rz;
            // quarantine while it can still be contained; give up on it and evacuate once it can't (a governor's
            // own call, whoever is President; not a town whose incident commander is the player)
            if (w.roles.holds(Roles.INCIDENT, st)) continue;
            // (a cautious governor quarantines sooner and gives a failing quarantine up sooner; a reckless one later)
            float qAt = w.c.rule("quarantineAt", 250) * temper(o), giveUp = w.c.rule("quarantineGiveUp", 2500) * temper(o);
            if (!quarantine[st] && !w.gov.evacuating(st) && !s.capital && rz > qAt && rz < giveUp && (w.gov.level >= 2 || w.gov.playerDecides)) setQuarantine(st, true);
            else if (quarantine[st] && (rz > giveUp || (rz < 10 && calmT[st] > 240))) {
                setQuarantine(st, false);
                if (rz > giveUp) {
                    w.gov.setEvac(st, true);
                    decide(o, "the quarantine of " + s.name + " has failed. Evacuate.", s.cx * 16f, s.cy * 16f, true);
                }
            }
        }
        // the plan for the coast: get its people inland before the dead reach them, a town at a time
        Content.Scenario sc = w.scenario;
        if (sc != null && "evaccoast".equals(sc.goal) && w.gov.level >= 2 && !w.gov.playerDecides) {
            Region.Settlement pick = null;
            for (Region.Settlement s : w.r.settlements) {
                if (s.state != o.where && o.where >= 0) continue;
                if ((s.kind != Region.S_CITY && s.kind != Region.S_TOWN) || !w.roles.coastal(s) || w.gov.evacuating(s.id) || quarantine[s.id]) continue;
                if (w.roles.holds(Roles.INCIDENT, s.id)) continue; // the player's city: the player's call
                if (w.stats.popBySettlement[s.id] < 500) continue;
                if (pick == null || repZ[s.id] > repZ[pick.id]) pick = s;
            }
            if (pick != null) {
                w.gov.setEvac(pick.id, true);
                decide(o, "orders " + pick.name + " evacuated inland before the dead get there.", pick.cx * 16f, pick.cy * 16f, false);
            }
        }
        if (!bridgesAuthorised && (w.gov.level >= 3 || w.strat.biggestHorde() != null && w.strat.biggestHorde().count > 400)) {
            bridgesAuthorised = true;
            decide(o, "engineers are authorised to blow bridges in front of the hordes.", -1, -1, false);
        }
        if (stateZ > 1500 && stateZ > o.asked * 2.5f) {
            o.asked = stateZ;
            if (w.gov.playerDecides) decide(o, "asks the President for federal troops.", -1, -1, false);
            else {
                w.mil.regularT = Math.min(w.mil.regularT, 0);
                decide(o, "requests federal troops.", -1, -1, false);
            }
        } else if (stateZ < 50) o.asked = 0;
    }

    private void president(Official o, float dt) {
        float nat = nationalReported();
        Gov g = w.gov;
        boolean player = w.roles.decidesNation();
        if (!player) {
            float mart = w.c.rule("martialLawAt", 1200), mob = w.c.rule("mobiliseAt", 5000);
            // (a player who lowered the alert by hand is not overruled at once: Gov.mayRaise)
            if (nat > 60 && g.level < 2 && g.reported && g.mayRaise(2, nat)) g.raise(2);
            if (nat > mart && g.level < 3 && g.mayRaise(3, nat)) g.raise(3);
            if (nat > mob && g.level < 4 && g.mayRaise(4, nat)) g.raise(4);
            if (g.level != lastLevel) java.util.Arrays.fill(downT, 0);
            lastLevel = g.level;
            // and down again, a step at a time, once the picture has stayed well below the mark for long enough
            float[] mark = {0, 0, 25, mart * 0.15f, mob * 0.15f};
            float hold = w.c.rule("lowerAfter", 720);
            for (int lv = 1; lv < downT.length; lv++) downT[lv] = nat <= mark[lv] ? downT[lv] + dt : 0;
            if (g.level >= 2 && downT[g.level] >= hold * (g.level == 2 ? 2 : 1)) {
                g.lower(g.level - 1);
                java.util.Arrays.fill(downT, 0);
            } else if (g.level == 1 && nat == 0 && downT[2] >= hold * 3) {
                g.lower(0);
                java.util.Arrays.fill(downT, 0);
            }
            lastLevel = g.level;
            curfewCalmT = nat < 30 ? curfewCalmT + dt : 0;
            if (g.level >= 2 && !g.curfew && curfewCalmT == 0 && !g.playerDecides) g.setCurfew(true);
            if (g.curfew && !g.playerDecides && (g.level < 2 || curfewCalmT > w.c.rule("curfewLiftAfter", 480))) g.setCurfew(false);
        }
        // the capital is defended whatever else happens (the general does it if the player hands command over)
        if (capitalT > 0 || !w.forces.aiCommands()) return;
        Region.Settlement c = w.r.city();
        // (with the player as the capital's incident commander, the army comes when they ask for it)
        if (w.roles.holds(Roles.INCIDENT, c.id)) return;
        boolean threat = repZ[c.id] > 300;
        Strategic.Horde h = w.strat.biggestHorde();
        if (h != null && h.count > 500) {
            float dx = h.x - c.cx * 16f, dy = h.y - c.cy * 16f;
            if (dx * dx + dy * dy < 9000f * 9000f) threat = true;
        }
        if (!threat) return;
        capitalT = 120;
        int have = 0;
        for (Forces.Formation f : w.forces.formations) if (f.alive && f.task == c.id) have++;
        if (have >= 2 + Math.min(4, repZ[c.id] / 5000)) return;
        for (Forces.Formation f : w.forces.formations) {
            if (!f.alive || f.task != -1 || !w.forces.aiMay(f) || f.state == Forces.BROKEN || f.faction != Ents.SOLDIER) continue;
            float[] p = w.forces.infectionCentre(c);
            if (w.forces.order(f, p[0], p[1])) {
                f.task = c.id;
                f.job = Forces.JOB_TOWN;
                f.jobId = c.id;
                decide(o, "orders the " + f.name + " to defend " + c.name + ".", c.cx * 16f, c.cy * 16f, false);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ politics

    private void approval(float hours) {
        for (Official o : officials) {
            float target;
            if (o.role == MAYOR || o.role == CHIEF) {
                Region.Settlement s = w.r.settlements.get(o.where);
                float bad = Math.min(1, repZ[s.id] / (s.residents * 0.02f + 200));
                target = 72 - 60 * bad - (abandoned[s.id] ? 25 : 0) + (w.gov.trust - 50) * 0.2f;
            } else if (o.role == GOVERNOR) {
                float sum = 0;
                int k = 0;
                for (Official m : officials)
                    if (m.role == MAYOR && (o.where < 0 || w.r.settlements.get(m.where).state == o.where)) {
                        sum += m.approval;
                        k++;
                    }
                target = k == 0 ? 60 : sum / k;
            } else target = w.gov.trust;
            o.approval += (target - o.approval) * Math.min(1, hours * 0.15f);
            o.lowT = o.approval < 15 ? o.lowT + hours : 0;
            // a failed official goes (not the general's or president's job to resign over one town)
            if (o.lowT > 12 && o.role != PRESIDENT) {
                String old = name(o);
                o.replaced++;
                o.approval = 50;
                o.lowT = 0;
                String where = o.where >= 0 && o.role <= CHIEF ? " of " + w.r.settlements.get(o.where).name
                        : o.role == GOVERNOR && o.where >= 0 ? " of " + w.r.states.get(o.where).name : "";
                w.news.headline(old + where + " is out. " + name(o) + " takes over, " + STANCE[trait(o)] + ".", -1, -1);
            }
        }
    }

    // ------------------------------------------------------------------ every step

    @Override
    public void update(float dt) {
        beliefs(dt);
        hourT += dt;
        capitalT -= dt;
        fedT -= dt;
        boolean hour = false;
        while (hourT >= 60) {
            hourT -= 60;
            hour = true;
        }
        for (Official o : officials) {
            o.cool -= dt;
            if (o.cool > 0) continue;
            o.cool = 10;
            switch (o.role) {
                case MAYOR:
                    // mayors decide for their own towns whoever is President; the player as a city's incident
                    // commander makes that mayor's calls
                    if (!w.roles.holds(Roles.INCIDENT, o.where)) mayor(o, 10);
                    break;
                case CHIEF: chief(o); break;
                case GOVERNOR: governor(o); break;
                case PRESIDENT: president(o, 10); break;
                default:
            }
        }
        if (hour) approval(1);
    }

    /** Lines for a settlement's inspector: its officials and what they've decided. */
    public String describe(int st) {
        StringBuilder sb = new StringBuilder();
        Official m = official(MAYOR, st), c = official(CHIEF, st);
        if (m != null) sb.append(". ").append(name(m)).append(", approval ").append((int) m.approval).append("%");
        if (m != null) sb.append(" (").append(STANCE[trait(m)].split(":")[0]).append(")");
        if (c != null) sb.append(". ").append(name(c)).append(" (").append(STANCE[trait(c)].split(":")[0]).append(")");
        sb.append(". Reported dead: ").append((int) repZ[st]).append(phonesUp(st) ? "" : " (phones down)");
        if (shelter[st]) sb.append(". Shelter in place");
        if (quarantine[st]) sb.append(". QUARANTINED");
        if (abandoned[st]) sb.append(". Abandoned by the police");
        if (m != null && m.last.length() > 0) sb.append(". Last order: ").append(m.last);
        return sb.toString();
    }

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        // checkpoints round quarantined towns
        if (cam.zoom > 0.9f) return;
        float dp = cam.dp;
        for (int st = 0; st < quarantine.length; st++) {
            if (!quarantine[st]) continue;
            Region.Settlement s = w.r.settlements.get(st);
            for (int node : s.edgeNodes) {
                float x = cam.toScreenX(w.r.roads.nx[node]), y = cam.toScreenY(w.r.roads.ny[node]);
                if (x < -20 * dp || y < -20 * dp || x > cam.sw + 20 * dp || y > cam.sh + 20 * dp) continue;
                g.color(0xFF101010);
                g.fillRect(x - 7 * dp, y - 3 * dp, 14 * dp, 6 * dp);
                g.color(0xFFE0C020);
                g.fillRect(x - 6 * dp, y - 2 * dp, 4 * dp, 4 * dp);
                g.fillRect(x + 2 * dp, y - 2 * dp, 4 * dp, 4 * dp);
            }
            if (cam.zoom >= 0.4f) continue;
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

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(officials.size());
        for (Official f : officials) {
            o.writeInt(f.replaced);
            o.writeFloat(f.approval);
            o.writeFloat(f.lowT);
            o.writeFloat(f.cool);
            o.writeUTF(f.last);
            o.writeDouble(f.lastT);
            o.writeFloat(f.asked);
        }
        int n = repZ.length;
        o.writeInt(n);
        for (int i = 0; i < n; i++) {
            o.writeFloat(repZ[i]);
            o.writeFloat(repSectors[i]);
            o.writeBoolean(shelter[i]);
            o.writeBoolean(quarantine[i]);
            o.writeBoolean(abandoned[i]);
            o.writeBoolean(helpAsked[i]);
            o.writeBoolean(alerted[i]);
            o.writeFloat(calmT[i]);
        }
        o.writeBoolean(bridgesAuthorised);
        o.writeFloat(hourT);
        o.writeFloat(capitalT);
        o.writeFloat(fedT);
        for (float d : downT) o.writeFloat(d);
        o.writeFloat(curfewCalmT);
        o.writeInt(lastLevel);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        int k = in.readInt();
        for (int i = 0; i < k; i++) {
            Official f = i < officials.size() ? officials.get(i) : new Official();
            f.replaced = in.readInt();
            f.approval = in.readFloat();
            f.lowT = in.readFloat();
            f.cool = in.readFloat();
            f.last = in.readUTF();
            f.lastT = in.readDouble();
            f.asked = in.readFloat();
        }
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            float a = in.readFloat(), b = in.readFloat();
            boolean sh = in.readBoolean(), q = in.readBoolean(), ab = in.readBoolean(), h = in.readBoolean(), al = in.readBoolean();
            float c = in.readFloat();
            if (i >= repZ.length) continue;
            repZ[i] = a;
            repSectors[i] = b;
            shelter[i] = sh;
            quarantine[i] = q;
            abandoned[i] = ab;
            helpAsked[i] = h;
            alerted[i] = al;
            calmT[i] = c;
        }
        bridgesAuthorised = in.readBoolean();
        hourT = in.readFloat();
        capitalT = in.readFloat();
        fedT = in.readFloat();
        for (int i = 0; i < downT.length; i++) downT[i] = in.readFloat();
        curfewCalmT = in.readFloat();
        if (in.available() > 0) lastLevel = in.readInt();
    }
}
