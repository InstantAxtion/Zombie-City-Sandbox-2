package com.instantaxtion.zcs2;

/**
 * Government and the public. The alert level rises with the outbreak and unlocks outside help; officials order
 * curfews and evacuations; public trust (hit by deaths, blackouts and rumours, raised by containment) decides how
 * many people do as they're told. In Commander mode the player makes the calls the governor would.
 */
public final class Gov {
    public static final String[] LEVELS = {"Normal", "Advisory", "State of Emergency", "Martial Law", "National Mobilisation"};
    private final World w;
    public int level;
    public float trust = 70;
    public boolean curfew;
    public final boolean[] evac;
    /** Commander mode: the player decides evacuations and curfews, the AI governor doesn't. */
    public boolean playerDecides;
    boolean reported;
    float rumourT = 300;
    int lastDeaths;

    Gov(World w) {
        this.w = w;
        evac = new boolean[w.r.settlements.size()];
    }

    public boolean evacuating(int settlement) {
        return settlement >= 0 && settlement < evac.length && evac[settlement];
    }

    void firstReport(Dispatch.Incident in) {
        if (reported) return;
        reported = true;
        w.news.headline("Reports of violent attacks" + (in.settlement >= 0 ? " in " + w.r.settlements.get(in.settlement).name : " outside town") + ". Police respond.", in.x, in.y);
        if (level < 1) raise(1);
    }

    /** The player (as president) raises the alert level. */
    public void raiseTo(int to) {
        raise(Math.max(0, Math.min(LEVELS.length - 1, to)));
    }

    void raise(int to) {
        if (to <= level) return;
        level = to;
        String[] say = {"", "Officials urge calm and ask people to stay indoors after reports of attacks.",
                "The governor declares a state of emergency. State police and Guard units are being called in.",
                "Martial law. The National Guard takes command; air strikes authorised on masses of the dead.",
                "National mobilisation: regular army divisions are moving into the region."};
        w.news.headline(LEVELS[level].toUpperCase() + ": " + say[level], -1, -1);
        w.stats.event("Alert: " + LEVELS[level], -1, -1);
    }

    public void setEvac(int settlement, boolean on) {
        if (settlement < 0 || settlement >= evac.length || evac[settlement] == on) return;
        evac[settlement] = on;
        Region.Settlement s = w.r.settlements.get(settlement);
        if (on) {
            w.news.headline("Evacuation ordered for " + s.name + ". Head for the " + direction(settlement) + " road out.", s.cx * 16f, s.cy * 16f);
            w.stats.event("Evacuation of " + s.name, s.cx * 16f, s.cy * 16f);
        } else w.news.radio("Evacuation of " + s.name + " called off", s.cx * 16f, s.cy * 16f, News.COMMAND);
    }

    private String direction(int st) {
        float[] p = w.strat.evacPoint(st);
        Region.Settlement s = w.r.settlements.get(st);
        float dx = p[0] - s.cx * 16f, dy = p[1] - s.cy * 16f;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? "east" : "west";
        return dy > 0 ? "south" : "north";
    }

    public void setCurfew(boolean on) {
        if (curfew == on) return;
        curfew = on;
        w.news.headline(on ? "Curfew: everyone indoors until further notice." : "Curfew lifted.", -1, -1);
    }

    /** Trust; the alert level, curfews and evacuations are decided by the officials (Command), rumours by Media. */
    void update(float dt) {
        int z = w.totalZombies();
        // trust: falls with deaths and losses, recovers slowly while things are under control
        int deaths = w.stats.civDeaths + w.stats.armedDeaths[1] + w.stats.armedDeaths[2];
        int newDeaths = deaths - lastDeaths;
        lastDeaths = deaths;
        trust -= Math.min(2f * dt, newDeaths * 0.004f);
        if (z < 30 && level >= 1) trust += 0.02f * dt;
        for (boolean g : w.strat.gridOn) if (!g) trust -= 0.004f * dt;
        if (trust < 5) trust = 5;
        if (trust > 95) trust = 95;
    }
}
