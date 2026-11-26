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

    public boolean sheltering(int st) {
        return st >= 0 && st < shelter.length && shelter[st];
    }

    public boolean isAbandoned(int st) {
        return st >= 0 && st < abandoned.length && abandoned[st];
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
        for (Dispatch.Incident in : w.disp.incidents)
            if (in.open && in.settlement >= 0 && in.settlement < n) {
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
        for (int st = 0; st < n; st++) {
            boolean phones = phonesUp(st);
            float truth = w.stats.zBySettlement[st];
            if (truth > 0 && w.strategic) alerted[st] = true; // in fast-forward the calls are taken as read
            float target = alerted[st] ? truth * (phones ? detectUp : detectDown) : 0;
            // calls only get through as far as the phones let them
            target = Math.max(target, calls[st] * (phones ? 1 : detectDown / detectUp));
            float lag = phones ? lagUp : lagDown;
            repZ[st] += (target - repZ[st]) * Math.min(1, dt / lag);
            float sTarget = sectors[st] == 0 ? 0 : infected[st] / (float) sectors[st] * (phones ? 1 : 0.5f);
            repSectors[st] += (sTarget - repSectors[st]) * Math.min(1, dt / lag);
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
        if (rz < 2) calmT[st] += dt;
        else calmT[st] = 0;
        if (!shelter[st] && rz > w.c.rule("shelterAt", 15)) {
            shelter[st] = true;
            decide(o, "everyone in " + s.name + " is to shelter in place, lock their doors and stay off the streets.", x, y, false);
        } else if (shelter[st] && calmT[st] > 60) {
            shelter[st] = false;
            decide(o, "the shelter order in " + s.name + " is lifted.", x, y, false);
        }
        boolean spiralling = rz > Math.max(w.c.rule("evacuateAt", 400), s.residents * 0.01f) || repSectors[st] > 0.25f;
        if (!w.gov.evacuating(st) && !quarantine[st] && w.gov.level >= 2 && spiralling) {
            w.gov.setEvac(st, true);
            decide(o, "orders the evacuation of " + s.name + ".", x, y, false);
        } else if (w.gov.evacuating(st) && calmT[st] > 240) {
            w.gov.setEvac(st, false);
            decide(o, "people may return to " + s.name + ".", x, y, false);
        }
        // calling for help: the state's Guard
        if (!helpAsked[st] && rz > Math.max(150, policeAt(st) * 0.5f)) {
            helpAsked[st] = true;
            w.mil.guardT = Math.min(w.mil.guardT, 0);
            decide(o, "asks the governor for the National Guard.", x, y, false);
        }
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
        if (rz > 150) w.mil.requestArmy(st, (int) rz);
        // the force is spent and the town is lost: pull out what's left
        int left = policeAt(st), start = Math.max(1, policeStart(st));
        if (!abandoned[st] && !s.capital && left < start * 0.25f && rz > w.stats.popBySettlement[st] * 0.3f + 100) {
            abandoned[st] = true;
            Official m = official(MAYOR, st);
            decide(o, s.name + " can't be held. The police are pulling out.", s.cx * 16f, s.cy * 16f, true);
            if (m != null) m.approval -= 20;
        }
    }

    private void governor(Official o) {
        float stateZ = 0;
        for (Region.Settlement s : w.r.settlements) {
            if (s.state != o.where && o.where >= 0) continue;
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
            int st = s.id;
            float rz = repZ[st];
            stateZ += rz;
            if (w.gov.playerDecides) continue;
            // quarantine while it can still be contained; give up on it and evacuate once it can't
            float qAt = w.c.rule("quarantineAt", 250), giveUp = w.c.rule("quarantineGiveUp", 2500);
            if (!quarantine[st] && !w.gov.evacuating(st) && !s.capital && rz > qAt && rz < giveUp && w.gov.level >= 2) setQuarantine(st, true);
            else if (quarantine[st] && (rz > giveUp || rz < 10)) {
                setQuarantine(st, false);
                if (rz > giveUp) {
                    w.gov.setEvac(st, true);
                    decide(o, "the quarantine of " + s.name + " has failed. Evacuate.", s.cx * 16f, s.cy * 16f, true);
                }
            }
        }
        if (!bridgesAuthorised && (w.gov.level >= 3 || w.strat.biggestHorde() != null && w.strat.biggestHorde().count > 400)) {
            bridgesAuthorised = true;
            decide(o, "engineers are authorised to blow bridges in front of the hordes.", -1, -1, false);
        }
        if (stateZ > 1500 && fedT <= 0) {
            fedT = 240;
            w.mil.regularT = Math.min(w.mil.regularT, 0);
            decide(o, "requests federal troops.", -1, -1, false);
        }
    }

    private void president(Official o) {
        float nat = nationalReported();
        Gov g = w.gov;
        if (nat > 60 && g.level < 2 && g.reported) g.raise(2);
        if (nat > w.c.rule("martialLawAt", 1200) && g.level < 3) g.raise(3);
        if (nat > w.c.rule("mobiliseAt", 5000) && g.level < 4) g.raise(4);
        if (g.level >= 2 && !g.curfew && !g.playerDecides) g.setCurfew(true);
        // the capital is defended whatever else happens
        if (capitalT > 0) return;
        Region.Settlement c = w.r.city();
        boolean threat = repZ[c.id] > 300;
        Strategic.Horde h = w.strat.biggestHorde();
        if (h != null && h.count > 500) {
            float dx = h.x - c.cx * 16f, dy = h.y - c.cy * 16f;
            if (dx * dx + dy * dy < 9000f * 9000f) threat = true;
        }
        if (!threat) return;
        capitalT = 120;
        for (Forces.Formation f : w.forces.formations) {
            if (!f.alive || f.task != -1 || f.player || f.state == Forces.BROKEN || f.faction != Ents.SOLDIER) continue;
            if (w.forces.order(f, c.cx * 16f, c.cy * 16f)) {
                f.task = c.id;
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
                w.news.headline(old + where + " is out. " + name(o) + " takes over.", -1, -1);
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
                case MAYOR: if (!w.gov.playerDecides) mayor(o, 10); break;
                case CHIEF: chief(o); break;
                case GOVERNOR: governor(o); break;
                case PRESIDENT: president(o); break;
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
        if (c != null) sb.append(". ").append(name(c));
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
        for (int st = 0; st < quarantine.length; st++) {
            if (!quarantine[st]) continue;
            Region.Settlement s = w.r.settlements.get(st);
            for (int node : s.edgeNodes) {
                float x = cam.toScreenX(w.r.roads.nx[node]), y = cam.toScreenY(w.r.roads.ny[node]);
                if (x < -20 || y < -20 || x > cam.sw + 20 || y > cam.sh + 20) continue;
                g.color(0xFF101010);
                g.fillRect(x - 7, y - 3, 14, 6);
                g.color(0xFFE0C020);
                g.fillRect(x - 6, y - 2, 4, 4);
                g.fillRect(x + 2, y - 2, 4, 4);
            }
            if (cam.zoom < 0.4f) {
                float x = cam.toScreenX(s.cx * 16f), y = cam.toScreenY(s.cy * 16f);
                g.color(0xFFE0C020);
                g.strokeCircle(x, y, s.radius * 16f * cam.zoom * 1.1f, 2);
                g.text("QUARANTINE", x, y + 18, 11, Gfx.ALIGN_CENTER, true);
            }
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
    }
}
