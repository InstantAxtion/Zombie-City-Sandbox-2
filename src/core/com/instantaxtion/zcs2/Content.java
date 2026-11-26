package com.instantaxtion.zcs2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Game content (people, weapons, vehicles, zombies, scenarios), read from assets/content.ini. */
public final class Content {
    public static final int CIV = 0, COP = 1, SOLDIER = 2, ZOMBIE = 3;

    public static final class Weapon {
        public String id, name, sound;
        public float range, rof, dmg, head, blast, blastDmg, reload;
        public int ammo, index, mag;
    }

    public static final class Unit {
        public String id, name, agency;
        /** What this person does beyond fighting (medic, commander, marksman, k9, traffic, drone; "" for none),
         *  and the training they fight by (police, army, swat). See ArmedAI. */
        public String role, doctrine;
        public int kind, index, color;
        public float hp, walk, run, armor, infect, aim, nerve, radius;
        public Weapon weapon, grenade;
        public boolean engineer;
    }

    public static final class ZType {
        public String id, name;
        public int index, color, strain;
        public float hp, walk, run, bite, infect, radius, weight;
        public boolean smash, helmet, night, placedOnly;
        /** How hard each blow lands on a barricade, fence or wall (smash; 0 = can't); smash >= 5 also counts as smash. */
        public float smashPower;
        /** Lunges after a wind-up (lunge), charges and knocks the living back (charge), runs in packs (pack). */
        public boolean lunge, charge, pack;
        /** False for the dead that can't get into buildings or claw at cars (dogs). */
        public boolean breakIn = true;
        /** The share of these that lead the others (alphas, pack leaders); -1 = the rules' default. */
        public float leads = -1;
    }

    public static final class VType {
        public String id, name, faction;
        public int index, color, stripe, seats;
        public float speed, hp, length, width, armor;
        public boolean flies, tracked, siren, supply;
        public Weapon weapon, weapon2;
    }

    /** A squad: who goes out together, in what, from where. */
    public static final class SquadT {
        public String id, name, faction;
        public int index;
        public Unit[] units;
        public VType vehicle;
        public String[] homes;
    }

    /** A formation on the national map. */
    public static final class FormationT {
        public String id, name, faction;
        public int index, tanks, guns;
        public SquadT[] squads;
        public float speed;
    }

    public static final class Scenario {
        public String id, name, mode, origin, goal, desc, role = "";
        public int zombies, days, index, hordes, hordeSize;
        /** Day of the year it starts (-1 = the default, 1 September). */
        public int season = -1;
        public boolean custom;

        /** The scenario as a content.ini section (for custom scenarios and replays). */
        public String toIni() {
            StringBuilder b = new StringBuilder();
            b.append("[scenario ").append(id).append("]\n");
            b.append("name = ").append(name).append("\n");
            b.append("mode = ").append(mode).append("\n");
            b.append("origin = ").append(origin).append("\n");
            if (goal.length() > 0) b.append("goal = ").append(goal).append("\n");
            b.append("zombies = ").append(zombies).append("\n");
            b.append("days = ").append(days).append("\n");
            b.append("hordes = ").append(hordes).append("\n");
            b.append("hordeSize = ").append(hordeSize).append("\n");
            if (season >= 0) b.append("season = ").append(season).append("\n");
            if (role.length() > 0) b.append("role = ").append(role).append("\n");
            b.append("desc = ").append(desc.replace('\n', ' ')).append("\n");
            return b.toString();
        }
    }

    public final List<Weapon> weapons = new ArrayList<Weapon>();
    public final List<Unit> units = new ArrayList<Unit>();
    public final List<ZType> ztypes = new ArrayList<ZType>();
    public final List<VType> vtypes = new ArrayList<VType>();
    public final List<Scenario> scenarios = new ArrayList<Scenario>();
    public final List<SquadT> squads = new ArrayList<SquadT>();
    public final List<FormationT> formations = new ArrayList<FormationT>();
    private final Map<String, SquadT> squadById = new HashMap<String, SquadT>();
    private final Map<String, FormationT> formationById = new HashMap<String, FormationT>();
    private final Map<String, Float> rules = new HashMap<String, Float>();
    /** Rules written key@national in content.ini: they replace the plain value on national (real-scale) maps. */
    private final Map<String, Float> nationalRules = new HashMap<String, Float>();
    private final Map<String, Weapon> weaponById = new HashMap<String, Weapon>();
    private final Map<String, Unit> unitById = new HashMap<String, Unit>();
    private final Map<String, ZType> zById = new HashMap<String, ZType>();
    private final Map<String, VType> vById = new HashMap<String, VType>();
    private final Map<String, Scenario> scById = new HashMap<String, Scenario>();
    /** A fingerprint of the rules, stored in saves. */
    public long rulesHash;

    public static Content load(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        List<String[]> heads = new ArrayList<String[]>();
        List<Map<String, String>> bodies = new ArrayList<Map<String, String>>();
        Map<String, String> cur = null;
        String line;
        long h = 1469598103934665603L;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            for (int i = 0; i < line.length(); i++) h = (h ^ line.charAt(i)) * 1099511628211L;
            if (line.startsWith("[") && line.endsWith("]")) {
                String[] p = line.substring(1, line.length() - 1).trim().split("\\s+");
                if (p.length != 2) throw new IOException("bad section: " + line);
                heads.add(p);
                cur = new LinkedHashMap<String, String>();
                bodies.add(cur);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0 || cur == null) throw new IOException("bad line: " + line);
            cur.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        Content c = new Content();
        c.rulesHash = h;
        // weapons first, everything else refers to them
        for (int i = 0; i < heads.size(); i++) if (heads.get(i)[0].equals("weapon")) c.addWeapon(heads.get(i)[1], bodies.get(i));
        for (int i = 0; i < heads.size(); i++) {
            String kind = heads.get(i)[0], id = heads.get(i)[1];
            Map<String, String> b = bodies.get(i);
            if (kind.equals("unit")) c.addUnit(id, b);
            else if (kind.equals("zombie")) c.addZombie(id, b);
            else if (kind.equals("vehicle")) c.addVehicle(id, b);
            else if (kind.equals("scenario")) c.addScenario(id, b);
            else if (kind.equals("rules")) {
                for (Map.Entry<String, String> en : b.entrySet()) {
                    String k = en.getKey();
                    float v = ruleValue(en.getValue());
                    if (k.endsWith(NATIONAL)) c.nationalRules.put(k.substring(0, k.length() - NATIONAL.length()), v);
                    else if (k.indexOf('@') >= 0) throw new IOException("bad rule key: " + k);
                    else c.rules.put(k, v);
                }
            } else if (!kind.equals("weapon") && !kind.equals("squad") && !kind.equals("formation"))
                throw new IOException("unknown section kind: " + kind);
        }
        for (int i = 0; i < heads.size(); i++) if (heads.get(i)[0].equals("squad")) c.addSquad(heads.get(i)[1], bodies.get(i));
        for (int i = 0; i < heads.size(); i++) if (heads.get(i)[0].equals("formation")) c.addFormation(heads.get(i)[1], bodies.get(i));
        for (String need : new String[]{"civilian", "patrol", "rifleman"}) if (c.unit(need) == null) throw new IOException("content needs unit " + need);
        if (c.ztype("walker") == null) throw new IOException("content needs zombie walker");
        for (String need : new String[]{"patrol_car", "army_truck", "humvee"}) if (c.vtype(need) == null) throw new IOException("content needs vehicle " + need);
        return c;
    }

    private static float f(Map<String, String> b, String k, float def) {
        String v = b.get(k);
        return v == null ? def : Float.parseFloat(v);
    }

    private static String s(Map<String, String> b, String k, String def) {
        String v = b.get(k);
        return v == null ? def : v;
    }

    private static int color(Map<String, String> b, String k, int def) {
        String v = b.get(k);
        if (v == null) return def;
        if (v.startsWith("#")) v = v.substring(1);
        return 0xFF000000 | Integer.parseInt(v, 16);
    }

    private void addWeapon(String id, Map<String, String> b) {
        Weapon w = new Weapon();
        w.id = id;
        w.name = s(b, "name", id);
        w.range = f(b, "range", 150);
        w.rof = f(b, "rof", 1);
        w.dmg = f(b, "dmg", 25);
        w.head = f(b, "head", 0.3f);
        w.ammo = (int) f(b, "ammo", 60);
        w.blast = f(b, "blast", 0);
        w.blastDmg = f(b, "blastDmg", 0);
        w.sound = s(b, "sound", "pistol");
        // a magazine: rounds between reloads (0 = no reloading), and seconds to reload
        w.mag = (int) f(b, "mag", 0);
        w.reload = f(b, "reload", 2);
        w.index = weapons.size();
        weapons.add(w);
        weaponById.put(id, w);
    }

    private void addUnit(String id, Map<String, String> b) {
        Unit u = new Unit();
        u.id = id;
        u.name = s(b, "name", id);
        String k = s(b, "kind", "civ");
        u.kind = k.equals("cop") ? COP : k.equals("soldier") ? SOLDIER : CIV;
        u.hp = f(b, "hp", 100);
        u.walk = f(b, "walk", 20);
        u.run = f(b, "run", 36);
        u.armor = f(b, "armor", 0);
        u.infect = f(b, "infect", 1);
        u.aim = f(b, "aim", 0.5f);
        u.nerve = f(b, "nerve", 0.5f);
        u.radius = f(b, "radius", 4);
        u.color = color(b, "color", 0xFFCCCCCC);
        u.weapon = weaponById.get(s(b, "weapon", ""));
        u.grenade = weaponById.get(s(b, "grenade", ""));
        u.engineer = f(b, "engineer", 0) > 0;
        u.agency = s(b, "agency", u.kind == SOLDIER ? "Army" : "");
        u.role = s(b, "role", "");
        u.doctrine = s(b, "doctrine", u.kind == SOLDIER ? "army" : u.kind == COP ? "police" : "");
        u.index = units.size();
        units.add(u);
        unitById.put(id, u);
    }

    private void addZombie(String id, Map<String, String> b) {
        ZType z = new ZType();
        z.id = id;
        z.name = s(b, "name", id);
        z.hp = f(b, "hp", 90);
        z.walk = f(b, "walk", 9);
        z.run = f(b, "run", 18);
        z.bite = f(b, "bite", 17);
        z.infect = f(b, "infect", 0.9f);
        z.radius = f(b, "radius", 4);
        z.color = color(b, "color", 0xFF7F9C5A);
        z.weight = f(b, "weight", 0);
        z.strain = (int) f(b, "strain", 0);
        z.smashPower = f(b, "smash", 1);
        z.smash = z.smashPower >= 5;
        z.lunge = f(b, "lunge", 0) > 0;
        z.charge = f(b, "charge", 0) > 0;
        z.pack = f(b, "pack", 0) > 0;
        z.breakIn = f(b, "breakIn", 1) > 0;
        if (!z.breakIn) z.smashPower = 0;
        z.leads = f(b, "leads", -1);
        z.helmet = f(b, "helmet", 0) > 0;
        z.night = f(b, "night", 0) > 0;
        z.placedOnly = f(b, "placedOnly", 0) > 0;
        z.index = ztypes.size();
        ztypes.add(z);
        zById.put(id, z);
    }

    private void addVehicle(String id, Map<String, String> b) {
        VType v = new VType();
        v.id = id;
        v.name = s(b, "name", id);
        v.faction = s(b, "faction", "civil");
        v.speed = f(b, "speed", 120);
        v.hp = f(b, "hp", 500);
        v.seats = (int) f(b, "seats", 4);
        v.length = f(b, "length", 28);
        v.width = f(b, "width", 14);
        v.armor = f(b, "armor", 0);
        v.color = color(b, "color", 0xFF888888);
        v.stripe = color(b, "stripe", 0);
        v.flies = f(b, "flies", 0) > 0;
        v.tracked = f(b, "tracked", 0) > 0;
        v.siren = f(b, "siren", 0) > 0;
        v.supply = f(b, "supply", 0) > 0;
        v.weapon = weaponById.get(s(b, "weapon", ""));
        v.weapon2 = weaponById.get(s(b, "weapon2", ""));
        v.index = vtypes.size();
        vtypes.add(v);
        vById.put(id, v);
    }

    private void addScenario(String id, Map<String, String> b) {
        Scenario sc = new Scenario();
        sc.id = id;
        sc.name = s(b, "name", id);
        sc.mode = s(b, "mode", "sandbox");
        sc.origin = s(b, "origin", "none");
        sc.goal = s(b, "goal", "");
        sc.desc = s(b, "desc", "");
        sc.zombies = (int) f(b, "zombies", 0);
        sc.days = (int) f(b, "days", 0);
        sc.hordes = (int) f(b, "hordes", 0);
        sc.hordeSize = (int) f(b, "hordeSize", 800);
        sc.season = (int) f(b, "season", -1);
        sc.role = s(b, "role", "");
        sc.index = scenarios.size();
        scenarios.add(sc);
        scById.put(id, sc);
    }

    private void addSquad(String id, Map<String, String> b) throws IOException {
        SquadT t = new SquadT();
        t.id = id;
        t.name = s(b, "name", id);
        t.faction = s(b, "faction", "army");
        List<Unit> us = new ArrayList<Unit>();
        for (String part : s(b, "units", "").split(",")) {
            part = part.trim();
            if (part.length() == 0) continue;
            String[] p = part.split("\\*");
            Unit u = unit(p[0].trim());
            if (u == null) throw new IOException("squad " + id + ": no unit " + p[0]);
            int n = p.length > 1 ? Integer.parseInt(p[1].trim()) : 1;
            for (int k = 0; k < n; k++) us.add(u);
        }
        t.units = us.toArray(new Unit[0]);
        t.vehicle = vtype(s(b, "vehicle", ""));
        String[] h = s(b, "homes", "").split(",");
        for (int i = 0; i < h.length; i++) h[i] = h[i].trim();
        t.homes = h;
        t.index = squads.size();
        squads.add(t);
        squadById.put(id, t);
    }

    private void addFormation(String id, Map<String, String> b) throws IOException {
        FormationT t = new FormationT();
        t.id = id;
        t.name = s(b, "name", id);
        t.faction = s(b, "faction", "army");
        List<SquadT> sq = new ArrayList<SquadT>();
        for (String part : s(b, "squads", "").split(",")) {
            part = part.trim();
            if (part.length() == 0) continue;
            String[] p = part.split("\\*");
            SquadT st = squadById.get(p[0].trim());
            if (st == null) throw new IOException("formation " + id + ": no squad " + p[0]);
            int n = p.length > 1 ? Integer.parseInt(p[1].trim()) : 1;
            for (int k = 0; k < n; k++) sq.add(st);
        }
        t.squads = sq.toArray(new SquadT[0]);
        t.tanks = (int) f(b, "tanks", 0);
        t.guns = (int) f(b, "guns", 0);
        t.speed = f(b, "speed", 30);
        t.index = formations.size();
        formations.add(t);
        formationById.put(id, t);
    }

    public SquadT squadT(String id) {
        return squadById.get(id);
    }

    public FormationT formationT(String id) {
        return formationById.get(id);
    }

    /** Overrides a rule (for balancing tools; the game reads content.ini). */
    public void setRule(String key, float value) {
        rules.put(key, value);
    }

    /** A number from the [rules] section, as on today's maps (World.rule picks the national value on a national map). */
    public float rule(String key, float def) {
        Float v = rules.get(key);
        return v == null ? def : v;
    }

    /**
     * A number from the [rules] section for a map of either kind: on a national map key@national if content.ini has
     * it, else the plain key; def when neither is there. World.rule passes the world's own flag.
     */
    public float rule(String key, float def, boolean national) {
        if (national) {
            Float v = nationalRules.get(key);
            if (v != null) return v;
        }
        Float v = rules.get(key);
        return v == null ? def : v;
    }

    /** The plain value of a rule, or null if content.ini has none (only a national one, or neither). */
    public Float plainRule(String key) {
        return rules.get(key);
    }

    /** The keys that have an @national override, sorted. */
    public List<String> nationalRuleKeys() {
        List<String> k = new ArrayList<String>(nationalRules.keySet());
        java.util.Collections.sort(k);
        return k;
    }

    /** The suffix of a rule that applies only on national maps: indoorDensity@national = 40000. */
    public static final String NATIONAL = "@national";
    /** Pixels in a kilometre: a tile is 16 px and 4 m. */
    public static final float PX_PER_KM = 1000f / 4f * Tile.SIZE;
    /** Game seconds (simulated seconds) in a game hour. */
    public static final float SECONDS_PER_HOUR = 60f;

    /** A speed in km per game hour as px per game second (1 km/h = 66.7 px/s). */
    public static float kmh(float kmPerHour) {
        return kmPerHour * PX_PER_KM / SECONDS_PER_HOUR;
    }

    /** A distance in km as px (1 km = 4,000 px). */
    public static float km(float km) {
        return km * PX_PER_KM;
    }

    /** A rule's value: a number, or a number and a unit ("2 km/h", "20 km", "500 m"), converted to px/s or px. */
    static float ruleValue(String v) {
        v = v.trim();
        if (v.endsWith("km/h")) return kmh(Float.parseFloat(v.substring(0, v.length() - 4).trim()));
        if (v.endsWith("km")) return km(Float.parseFloat(v.substring(0, v.length() - 2).trim()));
        if (v.endsWith(" m")) return km(Float.parseFloat(v.substring(0, v.length() - 2).trim()) / 1000f);
        return Float.parseFloat(v);
    }

    /** Reads extra scenarios (custom ones, or one from a replay). Only [scenario] sections; returns them. */
    public List<Scenario> addScenarios(InputStream in, boolean custom) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        List<Scenario> out = new ArrayList<Scenario>();
        String id = null;
        Map<String, String> cur = null;
        String line;
        while (true) {
            line = r.readLine();
            boolean head = line != null && line.trim().startsWith("[");
            if ((line == null || head) && id != null) {
                scenarios.remove(scById.get(id));
                addScenario(id, cur);
                Scenario sc = scById.get(id);
                sc.custom = custom;
                out.add(sc);
                id = null;
            }
            if (line == null) break;
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            if (head) {
                String[] p = line.substring(1, line.length() - 1).trim().split("\\s+");
                if (p.length == 2 && p[0].equals("scenario")) {
                    id = p[1];
                    cur = new LinkedHashMap<String, String>();
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0 && cur != null) cur.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        for (int i = 0; i < scenarios.size(); i++) scenarios.get(i).index = i;
        return out;
    }

    public Weapon weapon(String id) {
        return weaponById.get(id);
    }

    public Unit unit(String id) {
        return unitById.get(id);
    }

    public ZType ztype(String id) {
        return zById.get(id);
    }

    public VType vtype(String id) {
        return vById.get(id);
    }

    public Scenario scenario(String id) {
        return scById.get(id);
    }
}
