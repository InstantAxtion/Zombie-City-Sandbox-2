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
        public float range, rof, dmg, head, blast, blastDmg;
        public int ammo, index;
    }

    public static final class Unit {
        public String id, name, agency;
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
    }

    public static final class VType {
        public String id, name, faction;
        public int index, color, stripe, seats;
        public float speed, hp, length, width, armor;
        public boolean flies, tracked, siren, supply;
        public Weapon weapon, weapon2;
    }

    public static final class Scenario {
        public String id, name, mode, origin, goal, desc;
        public int zombies, days, index;
    }

    public final List<Weapon> weapons = new ArrayList<Weapon>();
    public final List<Unit> units = new ArrayList<Unit>();
    public final List<ZType> ztypes = new ArrayList<ZType>();
    public final List<VType> vtypes = new ArrayList<VType>();
    public final List<Scenario> scenarios = new ArrayList<Scenario>();
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
            else if (!kind.equals("weapon")) throw new IOException("unknown section kind: " + kind);
        }
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
        z.smash = f(b, "smash", 0) > 0;
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
        sc.index = scenarios.size();
        scenarios.add(sc);
        scById.put(id, sc);
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
