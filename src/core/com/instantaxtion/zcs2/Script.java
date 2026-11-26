package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A scenario's script: its triggers ("at time T / when C, do A") and its own goals (survive N days, keep a town above
 * N%, put down all the dead, keep losses under N). Read from the scenario (content.ini or the editor's), checked once
 * a game second, and part of the simulation: a trigger's action is carried out through Actions.apply like any input,
 * but not recorded, because a replay rebuilds the scenario and its script fires again at the same step. A module
 * only in games whose scenario has a script (so every other game, and its save, is as it was).
 *
 * The text, one trigger a line (trigger.1, trigger.2... in content.ini), places as fractions of the map's side:
 *   time:120 -> horde:0.40,0.62,2000       (game minutes since the start; a horde of 2,000 there)
 *   dead:3:500 -> strike:0.5,0.5           (more than 500 of the dead in town 3; an air strike there)
 *   falls:0 -> send:0.5,0.5                (the capital falls; the nearest army formation sent there)
 *   lost:12 -> weather:4                   (facility 12 overrun; the weather turns (Rules.WEATHER's choices))
 *   time:60 -> message:Reports of riots in the north
 * and the goals (goals = ...; ...):  days:7   keep:2:50   cleared   losses:5000
 */
public final class Script extends Module {
    public static final int C_TIME = 0, C_DEAD = 1, C_FALLS = 2, C_LOST = 3;
    public static final String[] COND_IDS = {"time", "dead", "falls", "lost"};
    public static final int A_HORDE = 0, A_STRIKE = 1, A_SEND = 2, A_WEATHER = 3, A_MESSAGE = 4;
    public static final String[] ACT_IDS = {"horde", "strike", "send", "weather", "message"};
    public static final int G_DAYS = 0, G_KEEP = 1, G_CLEARED = 2, G_LOSSES = 3;
    public static final String[] GOAL_IDS = {"days", "keep", "cleared", "losses"};

    /** One trigger: when cond (with a and n) holds, do act (at x, y, with m or text), once. */
    public static final class Trigger {
        public int cond, a, act, m;
        public float n, x, y;
        public String text = "";
        public boolean fired;

        public String toText() {
            StringBuilder b = new StringBuilder(COND_IDS[cond]);
            if (cond == C_TIME) b.append(':').append(num(n));
            else if (cond == C_DEAD) b.append(':').append(a).append(':').append(num(n));
            else b.append(':').append(a);
            b.append(" -> ").append(ACT_IDS[act]);
            switch (act) {
                case A_HORDE: b.append(':').append(frac(x)).append(',').append(frac(y)).append(',').append(m); break;
                case A_STRIKE:
                case A_SEND: b.append(':').append(frac(x)).append(',').append(frac(y)); break;
                case A_WEATHER: b.append(':').append(m); break;
                default: b.append(':').append(text.replace('\n', ' '));
            }
            return b.toString();
        }
    }

    /** One goal: days (n), keep (town a above n%), cleared, losses (under n). */
    public static final class Goal {
        public int kind, a;
        public float n;

        public String toText() {
            switch (kind) {
                case G_DAYS: return "days:" + num(n);
                case G_KEEP: return "keep:" + a + ":" + num(n);
                case G_LOSSES: return "losses:" + num(n);
                default: return "cleared";
            }
        }
    }

    static String num(float v) {
        return v == (int) v ? Integer.toString((int) v) : Float.toString(v);
    }

    static String frac(float v) {
        return Float.toString(Math.round(v * 10000) / 10000f);
    }

    /** Reads one trigger's text; throws with a plain reason if it can't. */
    public static Trigger parseTrigger(String s) throws IOException {
        int arrow = s.indexOf("->");
        if (arrow < 0) throw new IOException("a trigger is \"condition -> action\": " + s);
        String c = s.substring(0, arrow).trim(), a = s.substring(arrow + 2).trim();
        Trigger t = new Trigger();
        String[] cp = c.split(":");
        t.cond = index(COND_IDS, cp[0].trim());
        if (t.cond < 0) throw new IOException("no condition called " + cp[0] + " (time, dead, falls, lost)");
        try {
            if (t.cond == C_TIME) t.n = Float.parseFloat(cp[1].trim());
            else if (t.cond == C_DEAD) {
                t.a = Integer.parseInt(cp[1].trim());
                t.n = Float.parseFloat(cp[2].trim());
            } else t.a = Integer.parseInt(cp[1].trim());
        } catch (RuntimeException e) {
            throw new IOException("the condition " + c + " is missing a number");
        }
        int colon = a.indexOf(':');
        String an = colon < 0 ? a : a.substring(0, colon).trim(), args = colon < 0 ? "" : a.substring(colon + 1).trim();
        t.act = index(ACT_IDS, an);
        if (t.act < 0) throw new IOException("no action called " + an + " (horde, strike, send, weather, message)");
        try {
            String[] p = args.split(",");
            switch (t.act) {
                case A_HORDE:
                    t.x = Float.parseFloat(p[0].trim());
                    t.y = Float.parseFloat(p[1].trim());
                    t.m = Integer.parseInt(p[2].trim());
                    break;
                case A_STRIKE:
                case A_SEND:
                    t.x = Float.parseFloat(p[0].trim());
                    t.y = Float.parseFloat(p[1].trim());
                    break;
                case A_WEATHER: t.m = Integer.parseInt(args.trim()); break;
                default: t.text = args;
            }
        } catch (RuntimeException e) {
            throw new IOException("the action " + a + " is missing a number");
        }
        return t;
    }

    public static Goal parseGoal(String s) throws IOException {
        String[] p = s.trim().split(":");
        Goal g = new Goal();
        g.kind = index(GOAL_IDS, p[0].trim());
        if (g.kind < 0) throw new IOException("no goal called " + p[0] + " (days, keep, cleared, losses)");
        try {
            if (g.kind == G_DAYS || g.kind == G_LOSSES) g.n = Float.parseFloat(p[1].trim());
            else if (g.kind == G_KEEP) {
                g.a = Integer.parseInt(p[1].trim());
                g.n = Float.parseFloat(p[2].trim());
            }
        } catch (RuntimeException e) {
            throw new IOException("the goal " + s + " is missing a number");
        }
        return g;
    }

    public static List<Goal> parseGoals(String s) throws IOException {
        List<Goal> out = new ArrayList<Goal>();
        if (s == null) return out;
        for (String part : s.split(";")) if (part.trim().length() > 0) out.add(parseGoal(part));
        return out;
    }

    /** A scenario's extra outbreak points: {x, y, n} each (x, y fractions of the map's side). */
    public static List<float[]> points(String s) throws IOException {
        List<float[]> out = new ArrayList<float[]>();
        if (s == null) return out;
        for (String part : s.split(";")) {
            if (part.trim().length() == 0) continue;
            String[] p = part.split(",");
            try {
                out.add(new float[]{Float.parseFloat(p[0].trim()), Float.parseFloat(p[1].trim()), Integer.parseInt(p[2].trim())});
            } catch (RuntimeException e) {
                throw new IOException("an outbreak point is x,y,how many: " + part.trim());
            }
        }
        return out;
    }

    /** What a scenario puts down at the start: {"squad" or "defence", the [squad] id or Defences kind, x, y}. */
    public static List<String[]> place(String s) throws IOException {
        List<String[]> out = new ArrayList<String[]>();
        if (s == null) return out;
        for (String part : s.split(";")) {
            part = part.trim();
            if (part.length() == 0) continue;
            int colon = part.indexOf(':'), at = part.indexOf('@');
            if (colon < 0 || at < colon) throw new IOException("a placing is squad:id@x,y or defence:kind@x,y: " + part);
            String kind = part.substring(0, colon).trim(), id = part.substring(colon + 1, at).trim();
            String[] xy = part.substring(at + 1).split(",");
            if (!kind.equals("squad") && !kind.equals("defence") || xy.length < 2) throw new IOException("a placing is squad:id@x,y or defence:kind@x,y: " + part);
            try {
                Float.parseFloat(xy[0].trim());
                Float.parseFloat(xy[1].trim());
                if (kind.equals("defence")) Integer.parseInt(id);
            } catch (RuntimeException e) {
                throw new IOException("a placing is squad:id@x,y or defence:kind@x,y: " + part);
            }
            out.add(new String[]{kind, id, xy[0].trim(), xy[1].trim()});
        }
        return out;
    }

    /** A scenario's rules sheet: {key, value} each (Rules.*). */
    public static List<int[]> rules(String s) throws IOException {
        List<int[]> out = new ArrayList<int[]>();
        if (s == null) return out;
        for (String part : s.split(",")) {
            if (part.trim().length() == 0) continue;
            String[] p = part.split(":");
            try {
                int k = Integer.parseInt(p[0].trim()), v = Integer.parseInt(p[1].trim());
                if (k < 0 || k >= Rules.KEYS) throw new IOException("no rule " + k + " (0-" + (Rules.KEYS - 1) + ")");
                out.add(new int[]{k, v});
            } catch (RuntimeException e) {
                throw new IOException("a rule is key:value (two numbers): " + part.trim());
            }
        }
        return out;
    }

    /** Every part of a scenario the editor adds, read once to find mistakes; null if it all reads, else the first. */
    public static String check(Content c, Content.Scenario sc) {
        try {
            points(sc.points);
            for (String[] p : place(sc.place))
                if (p[0].equals("squad") && c.squadT(p[1]) == null) throw new IOException("no squad called " + p[1]);
            rules(sc.rules);
            parseGoals(sc.goals);
            for (String t : sc.triggers) parseTrigger(t);
            if (sc.strain < 0 || sc.strain > 3) throw new IOException("strain is 0 to 3");
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static int index(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ the module

    public final List<Trigger> triggers = new ArrayList<Trigger>();
    public final List<Goal> goals = new ArrayList<Goal>();
    /** The game minute it started (goals count days from here). */
    public double start;
    private int seconds;

    Script(World w) {
        super(w);
    }

    @Override
    public String id() {
        return "script";
    }

    /** True if a scenario has anything for a script to do. */
    public static boolean has(Content.Scenario sc) {
        return sc != null && (!sc.triggers.isEmpty() || sc.goals.length() > 0);
    }

    /**
     * Gives the world a script if its scenario has one (a new game and a loaded one alike, so the module list is the
     * same). Bad lines are left out (the editor and the importer check them before they get this far).
     */
    static void attach(World w) {
        if (!has(w.scenario) || w.script != null) return;
        Script s = new Script(w);
        for (String t : w.scenario.triggers) {
            try {
                s.triggers.add(parseTrigger(t));
            } catch (IOException ignored) {
            }
        }
        try {
            s.goals.addAll(parseGoals(w.scenario.goals));
        } catch (IOException ignored) {
        }
        s.start = w.time;
        w.script = s;
        // before the record of why things happened, which reads the world and must stay last
        int at = w.modules.indexOf(w.stats.rec);
        w.modules.add(at < 0 ? w.modules.size() : at, s);
    }

    @Override
    public void update(float dt) {
        for (int k = 0; k < triggers.size(); k++) {
            Trigger t = triggers.get(k);
            if (!t.fired && holds(t)) {
                t.fired = true;
                fire(t);
            }
        }
        if (!w.ended && !goals.isEmpty()) judge();
        seconds++;
    }

    private boolean holds(Trigger t) {
        Region r = w.r;
        switch (t.cond) {
            case C_TIME: return w.time - start >= t.n;
            case C_DEAD: return t.a >= 0 && t.a < w.stats.zBySettlement.length && w.stats.zBySettlement[t.a] > t.n;
            case C_FALLS: return t.a >= 0 && t.a < r.settlements.size() && fallen(r.settlements.get(t.a));
            case C_LOST: return t.a >= 0 && t.a < r.facilities.size() && !w.strat.online(t.a);
            default: return false;
        }
    }

    /** A town has fallen: few of its people left and more of the dead than the living there (as Records judges it). */
    boolean fallen(Region.Settlement t) {
        int z = w.stats.zBySettlement[t.id], pop = w.stats.popBySettlement[t.id];
        return t.residents >= 200 && z >= 50 && z > pop && pop < t.residents * 0.25f;
    }

    private void fire(Trigger t) {
        float px = w.r.px();
        float x = t.x * px, y = t.y * px;
        switch (t.act) {
            case A_HORDE: Actions.scripted(w, Actions.HORDE, new float[]{x, y}, new int[]{t.m}, null); break;
            case A_STRIKE: Actions.scripted(w, Actions.STRIKE, new float[]{x, y}, null, null); break;
            case A_SEND: Actions.scripted(w, Actions.SEND, new float[]{x, y}, null, null); break;
            case A_WEATHER: Actions.scripted(w, Actions.RULE, null, new int[]{Rules.WEATHER, t.m}, null); break;
            default: Actions.scripted(w, Actions.MESSAGE, null, null, t.text);
        }
    }

    /** Losses so far: the people bitten or killed, and the police and soldiers lost. */
    public long losses() {
        Stats s = w.stats;
        long n = s.civDeaths + s.civTurned;
        for (int v : s.armedDeaths) n += v;
        return n;
    }

    private void judge() {
        double days = (w.time - start) / 1440.0;
        Goal win = null;
        for (Goal g : goals) {
            switch (g.kind) {
                case G_KEEP: {
                    if (g.a < 0 || g.a >= w.r.settlements.size()) break;
                    Region.Settlement t = w.r.settlements.get(g.a);
                    float lost = w.stats.lostBySettlement[g.a];
                    if (t.residents > 0 && lost > t.residents * (100 - g.n) / 100f) {
                        end(false, t.name + " was lost", "Fewer than " + num(g.n) + "% of " + t.name + "'s people are left, " + dayText(days) + " in.");
                        return;
                    }
                    break;
                }
                case G_LOSSES:
                    if (losses() > g.n) {
                        end(false, "Too many lost", News.num((int) Math.min(Integer.MAX_VALUE, losses())) + " people lost, more than the " + News.num((int) g.n) + " allowed, " + dayText(days) + " in.");
                        return;
                    }
                    break;
                case G_DAYS:
                    if (days >= g.n && win == null) win = g;
                    break;
                case G_CLEARED:
                    if (seconds > 30 && w.totalZombies() == 0 && win == null) win = g;
                    break;
                default:
            }
        }
        if (win != null) {
            if (win.kind == G_DAYS) end(true, "You held on", num(win.n) + " days, and everything you had to keep is still standing.");
            else end(true, "The dead are gone", "Every one of the dead is down, " + dayText(days) + " in.");
        }
    }

    private static String dayText(double days) {
        return days < 1 ? (int) (days * 24) + " hours" : String.format(java.util.Locale.US, "%.1f days", days);
    }

    private void end(boolean win, String title, String text) {
        w.ended = true;
        w.won = win;
        w.endTitle = title;
        w.endText = text;
        w.news.headline(title, -1, -1);
    }

    /** A line on the pause menu: the goals and how they stand. */
    public String progress() {
        if (goals.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        double days = (w.time - start) / 1440.0;
        for (Goal g : goals) {
            if (b.length() > 0) b.append(". ");
            switch (g.kind) {
                case G_DAYS: b.append("Hold ").append(num(g.n)).append(" days (").append(String.format(java.util.Locale.US, "%.1f", days)).append(")"); break;
                case G_KEEP: {
                    String name = g.a >= 0 && g.a < w.r.settlements.size() ? w.r.settlements.get(g.a).name : "?";
                    b.append("Keep ").append(num(g.n)).append("% of ").append(name);
                    break;
                }
                case G_LOSSES: b.append("Lose under ").append(News.num((int) g.n)).append(" (").append(News.num((int) Math.min(Integer.MAX_VALUE, losses()))).append(")"); break;
                default: b.append("Put down every one of the dead (").append(News.num(w.totalZombies())).append(" left)");
            }
        }
        return b.toString();
    }

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(1);
        o.writeDouble(start);
        o.writeInt(seconds);
        o.writeInt(triggers.size());
        for (Trigger t : triggers) o.writeBoolean(t.fired);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        in.readInt();
        start = in.readDouble();
        seconds = in.readInt();
        int n = in.readInt();
        for (int k = 0; k < n; k++) {
            boolean f = in.readBoolean();
            if (k < triggers.size()) triggers.get(k).fired = f;
        }
    }
}
