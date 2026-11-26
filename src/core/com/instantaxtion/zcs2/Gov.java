package com.instantaxtion.zcs2;

/**
 * Government and the public. The alert level rises with the outbreak and unlocks outside help, and comes down
 * again (slowly: officials want to be sure) once it's over; officials order curfews and evacuations and lift them;
 * public trust follows how the government is doing (deaths, blackouts, towns abandoned or held, the outbreak
 * growing or shrinking, people sent home) and decides how many people do as they're told. When the player is the
 * President, the player makes the calls the AI president and governors would.
 */
public final class Gov {
    public static final String[] LEVELS = {"Normal", "Advisory", "State of Emergency", "Martial Law", "National Mobilisation"};
    private final World w;
    public int level;
    public float trust = 70;
    public boolean curfew;
    public final boolean[] evac;
    /** The player is the President: the player decides evacuations, curfews and the alert level. */
    public boolean playerDecides;
    boolean reported;
    float rumourT = 300;
    int lastDeaths;
    /** The real number of the dead an hour ago, and the hour clock (for "is it getting better?"). */
    int lastHourZ = -1;
    float hourT;
    /** Game seconds the outbreak has been shrinking (or the country clear). */
    public float improvingT;
    /** Game seconds the curfew has been on. */
    public float curfewT;

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
        if (level < 1 && !playerDecides) raise(1);
    }

    /** The first report reached the authorities some other way (fast-forward: nobody is at a phone to watch). */
    void reportedAt(int settlement, float x, float y) {
        if (reported) return;
        reported = true;
        w.news.headline("Reports of violent attacks" + (settlement >= 0 ? " in " + w.r.settlements.get(settlement).name : " in the countryside") + ". Police respond.", x, y);
        if (level < 1 && !playerDecides) raise(1);
    }

    /**
     * A player lowered the alert by hand: until heldT runs out the AI president raises it again only if the
     * reported picture gets much worse than it was then (heldNat), and never above heldLevel otherwise.
     */
    public float heldT, heldNat;
    public int heldLevel = -1;

    /** The player sets the alert level, up or down (lifting martial law, ending mobilisation). */
    public void raiseTo(int to) {
        to = Math.max(0, Math.min(LEVELS.length - 1, to));
        if (to > level) {
            raise(to);
            heldT = 0;
            heldLevel = -1;
        } else if (to < level) {
            lower(to);
            heldT = 1440;
            heldNat = w.command.nationalReported();
            heldLevel = to;
        }
    }

    /** May the AI raise the alert to this level now (not straight back over a player's decision to lower it)? */
    boolean mayRaise(int to, float nat) {
        if (heldT <= 0 || to <= heldLevel) return true;
        return nat > Math.max(heldNat * 2.5f, 200);
    }

    void raise(int to) {
        if (to <= level) return;
        level = to;
        String[] say = {"", "Officials urge calm and ask people to stay indoors after reports of attacks.",
                "State police and Guard units are being called in.",
                "The National Guard takes command; air strikes are authorised on masses of the dead.",
                "Regular army divisions are moving into the region."};
        w.news.headline(LEVELS[level].toUpperCase() + " declared. " + say[level], -1, -1);
        w.stats.event("Alert: " + LEVELS[level], -1, -1);
    }

    /** The alert comes down a step (or more): the worst is over. */
    void lower(int to) {
        if (to >= level || to < 0) return;
        level = to;
        String[] say = {"The emergency is over. Life can go back to normal.",
                "The state of emergency is lifted. Stay alert and report anything unusual.",
                "Martial law is lifted. The state of emergency stays in force while the last of the dead are hunted down.",
                "Mobilisation is scaled back: the regular army begins to stand down."};
        w.news.headline("ALERT LOWERED to " + LEVELS[level] + ". " + say[level], -1, -1);
        w.stats.event("Alert lowered: " + LEVELS[level], -1, -1);
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
        if (!on) curfewT = 0;
        w.news.headline(on ? "Curfew: everyone indoors until further notice." : "Curfew lifted.", -1, -1);
    }

    /**
     * How well people keep their doors shut when told to (1 = as exposed as anyone, less is safer): people who
     * trust the government stay in and barricade when there's a curfew or a shelter order.
     */
    public float compliance(int settlement) {
        boolean told = curfew || w.command.sheltering(settlement);
        if (!told) return 1;
        return 1 - 0.45f * trust / 100f;
    }

    /** Trust; the alert level, curfews and evacuations are decided by the officials (Command), rumours by Media. */
    void update(float dt) {
        int z = w.totalZombies();
        if (curfew) curfewT += dt;
        if (heldT > 0) heldT -= dt;
        hourT += dt;
        if (hourT >= 60) {
            hourT -= 60;
            // better or worse than an hour ago?
            if (lastHourZ >= 0) {
                if (z == 0 || z < lastHourZ * 0.97f) improvingT += 60;
                else if (z > lastHourZ * 1.03f) improvingT = 0;
            }
            lastHourZ = z;
        }
        int deaths = w.stats.civDeaths + w.stats.armedDeaths[1] + w.stats.armedDeaths[2];
        int newDeaths = deaths - lastDeaths;
        lastDeaths = deaths;
        // deaths cost trust (each one matters less once thousands have died)
        float scale = 1f / (1 + deaths / 20000f);
        trust -= Math.min(1f * dt, newDeaths * 0.004f * scale);
        if (level >= 1) {
            // an outbreak being beaten back earns trust; one that keeps growing loses it
            if (improvingT >= 120) trust += (z == 0 ? 0.02f : 0.01f) * dt;
            else if (improvingT == 0 && z > 200) trust -= 0.002f * dt;
        } else {
            // in normal times trust drifts back to where it usually sits
            trust += (65 - trust) * 0.0002f * dt;
        }
        for (boolean g : w.strat.gridOn) if (!g) trust -= 0.003f * dt;
        // a curfew that goes on for days wears people down
        if (curfewT > 1440 * 3) trust -= 0.0015f * dt;
        if (trust < 5) trust = 5;
        if (trust > 95) trust = 95;
    }
}
