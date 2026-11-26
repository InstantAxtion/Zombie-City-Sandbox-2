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

    private void raise(int to) {
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

    void update() {
        int z = w.totalZombies();
        if (z > 60 && level < 2 && reported) raise(2);
        if (z > 1500 && level < 3) raise(3);
        if (z > 7000 && level < 4) raise(4);
        // trust: falls with deaths and losses, recovers slowly while things are under control
        int deaths = w.stats.civDeaths + w.stats.armedDeaths[1] + w.stats.armedDeaths[2];
        int newDeaths = deaths - lastDeaths;
        lastDeaths = deaths;
        trust -= Math.min(2f, newDeaths * 0.004f);
        if (z < 30 && level >= 1) trust += 0.02f;
        for (boolean g : w.strat.gridOn) if (!g) trust -= 0.004f;
        if (trust < 5) trust = 5;
        if (trust > 95) trust = 95;
        rumourT -= 1;
        if (rumourT <= 0 && level >= 1) {
            rumourT = 240 + w.rng.nextFloat() * 300;
            rumour();
        }
        if (playerDecides) return;
        // the governor's calls
        if (level >= 2 && !curfew) setCurfew(true);
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
            int zs = w.stats.zBySettlement[s.id];
            int pop = Math.max(1, w.stats.popBySettlement[s.id]);
            if (!evac[s.id] && level >= 2 && (zs > 400 || zs > pop * 0.25f)) setEvac(s.id, true);
        }
    }

    private void rumour() {
        String[] good = {"Officials: the outbreak is being contained street by street.",
                "Volunteers organise food and water for people sheltering at home.",
                "Hospitals: anyone bitten must report for treatment immediately."};
        String[] bad = {"RUMOUR: 'the bite isn't dangerous if you wash it'. Officials say this is false.",
                "RUMOUR: the army is pulling out tonight. The Pentagon denies it.",
                "RUMOUR: the water supply is infected. People are fleeing on foot.",
                "Footage of a police line breaking spreads online. Panic grows."};
        if (trust > 55 && w.rng.chance(0.5f)) {
            w.news.headline(good[w.rng.nextInt(good.length)], -1, -1);
            trust += 2;
        } else {
            w.news.headline(bad[w.rng.nextInt(bad.length)], -1, -1);
            trust -= 4;
            // the frightened come out of hiding and take to the roads
            for (int s = 0; s < w.sec.n; s++) w.sec.panic[s] = Math.min(1, w.sec.panic[s] + 0.2f);
        }
    }
}
