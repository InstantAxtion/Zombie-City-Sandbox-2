package com.instantaxtion.zcs2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * content.ini's data sections: the lists of names, building kinds and their looks, the map presets, the places an
 * outbreak can start, and the kinds of formation that come from outside. They were code; now a mod can change them.
 * The map makers and the name lists have no Content of their own (a map is made from its size, preset and seed), so
 * each kind a loaded content.ini has is installed here for them (a file without a kind keeps the one in use).
 */
public final class Data {
    static final String[] KINDS = {"names", "building", "preset", "origin", "outside"};

    static boolean isKind(String k) {
        for (String s : KINDS) if (s.equals(k)) return true;
        return false;
    }

    /** A kind of building: its name, its roofs' colours (today's maps, and national maps), and how it's drawn. */
    public static final class Building {
        public String id, name;
        public int index;
        public int[] roofs, roofsNational;
        /** A pitched roof (two slopes) rather than a flat one. */
        public boolean pitched;
        /** People live there (its windows light up at night). */
        public boolean home;
        /** Its windows light up at night (homes, and the hospital and police that never close). */
        public boolean lit;
    }

    /** A map preset: its name and its terrain (one of the four kinds of land, with the numbers that shape it). */
    public static final class Preset {
        public String id, name;
        public int index, terrain;
        public float level, relief, feature, featureAt, featureCap, featureScale;
        public int riversMin, riversMax;
        public boolean naval;
    }

    /** A place an outbreak can start: a built-in start, or at a facility of a kind. */
    public static final class Origin {
        public String id, name, desc, start;
        /** For start = facility: the facility kind (Region.F_*), and how many of the dead start on the street. */
        public int facility = -1, street = 300;
    }

    public static final String[] TERRAINS = {"coast", "valley", "highlands", "lakes"};
    /** Facility kinds by id, in Region.F_* order (for [origin] facility = ...). */
    public static final String[] FACILITY_IDS = {"police", "hospital", "power", "fuel", "base", "armory", "prison", "lab", "depot",
            "helipad", "park", "water", "phone", "foodw", "fire", "hall", "station", "airbase", "port", "airport", "gov", "camp", "fob", "hq"};

    final Map<String, String[]> names = new HashMap<String, String[]>();
    final List<Building> buildings = new ArrayList<Building>();
    final List<Preset> presets = new ArrayList<Preset>();
    final List<Origin> origins = new ArrayList<Origin>();
    final Map<String, String[]> outside = new LinkedHashMap<String, String[]>();
    private boolean hasNames, hasBuildings, hasPresets, hasOrigins, hasOutside;

    void add(String kind, String id, Map<String, String> b, String where) throws IOException {
        if (kind.equals("names")) {
            hasNames = true;
            for (Map.Entry<String, String> e : b.entrySet()) names.put(e.getKey(), list(e.getValue(), true));
        } else if (kind.equals("outside")) {
            hasOutside = true;
            for (Map.Entry<String, String> e : b.entrySet()) outside.put(e.getKey(), list(e.getValue(), false));
        } else if (kind.equals("building")) {
            hasBuildings = true;
            Building k = new Building();
            k.id = id;
            k.name = str(b, "name", id);
            k.index = num(b, "index", where, -1);
            if (k.index < 0) throw new IOException(where + ": a building needs its index (0, 1, 2...)");
            k.roofs = colours(b.get("roofs"), where);
            k.roofsNational = b.containsKey("roofs@national") ? colours(b.get("roofs@national"), where) : k.roofs;
            k.pitched = "pitched".equals(str(b, "roof", "flat"));
            k.home = num(b, "home", where, 0) > 0;
            k.lit = k.home || num(b, "lit", where, 0) > 0;
            buildings.add(k);
        } else if (kind.equals("preset")) {
            hasPresets = true;
            Preset p = new Preset();
            p.id = id;
            p.name = str(b, "name", id);
            p.index = num(b, "index", where, -1);
            if (p.index < 0) throw new IOException(where + ": a preset needs its index (0, 1, 2...)");
            String t = str(b, "terrain", "coast");
            p.terrain = -1;
            for (int i = 0; i < TERRAINS.length; i++) if (TERRAINS[i].equals(t)) p.terrain = i;
            if (p.terrain < 0) throw new IOException(where + ": terrain is coast, valley, highlands or lakes, not " + t);
            p.level = fnum(b, "level", where, 0.25f);
            p.relief = fnum(b, "relief", where, 0.45f);
            p.feature = fnum(b, "feature", where, 0.5f);
            p.featureAt = fnum(b, "featureAt", where, 0);
            p.featureCap = fnum(b, "featureCap", where, 1);
            p.featureScale = fnum(b, "featureScale", where, 120);
            String rv = str(b, "rivers", "1");
            int dash = rv.indexOf('-');
            try {
                p.riversMin = Integer.parseInt((dash > 0 ? rv.substring(0, dash) : rv).trim());
                p.riversMax = dash > 0 ? Integer.parseInt(rv.substring(dash + 1).trim()) : p.riversMin;
            } catch (NumberFormatException e) {
                throw new IOException(where + ": rivers is a number or a range like 1-2, not " + rv);
            }
            p.naval = num(b, "naval", where, 0) > 0;
            presets.add(p);
        } else if (kind.equals("origin")) {
            hasOrigins = true;
            Origin o = new Origin();
            o.id = id;
            o.name = str(b, "name", id);
            o.desc = str(b, "desc", "");
            o.start = str(b, "start", id);
            String fac = str(b, "facility", "");
            if (fac.length() > 0) {
                for (int i = 0; i < FACILITY_IDS.length; i++) if (FACILITY_IDS[i].equals(fac)) o.facility = i;
                if (o.facility < 0) throw new IOException(where + ": no facility kind called " + fac);
                o.start = "facility";
            }
            o.street = num(b, "street", where, 300);
            origins.add(o);
        }
    }

    /** Puts the buildings and presets in index order and checks there are no gaps. */
    void check() throws IOException {
        sortAndCheck(buildings, "building");
        sortAndCheck(presets, "preset");
    }

    private static <T> void sortAndCheck(List<T> l, String what) throws IOException {
        java.util.Collections.sort(l, new java.util.Comparator<T>() {
            public int compare(T a, T b) {
                return idx(a) - idx(b);
            }
        });
        for (int i = 0; i < l.size(); i++)
            if (idx(l.get(i)) != i) throw new IOException("content.ini: the " + what + "s' indices must be 0, 1, 2... with none missing or repeated (index " + i + ")");
    }

    private static int idx(Object o) {
        return o instanceof Building ? ((Building) o).index : ((Preset) o).index;
    }

    private static String str(Map<String, String> b, String k, String def) {
        String v = b.get(k);
        return v == null ? def : v;
    }

    private static int num(Map<String, String> b, String k, String where, int def) throws IOException {
        String v = b.get(k);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IOException(where + ": " + k + " should be a whole number, not " + v);
        }
    }

    private static float fnum(Map<String, String> b, String k, String where, float def) throws IOException {
        String v = b.get(k);
        if (v == null) return def;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            throw new IOException(where + ": " + k + " should be a number, not " + v);
        }
    }

    private static int[] colours(String v, String where) throws IOException {
        if (v == null) return new int[]{0xFF888888};
        String[] p = v.split(",");
        int[] c = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            String s = p[i].trim();
            if (s.startsWith("#")) s = s.substring(1);
            try {
                c[i] = 0xFF000000 | Integer.parseInt(s, 16);
            } catch (NumberFormatException e) {
                throw new IOException(where + ": a colour is written #RRGGBB, not " + p[i].trim());
            }
        }
        return c;
    }

    /** A comma list; in a names list "_" stands for a space (" Falls" is written _Falls). */
    private static String[] list(String v, boolean underscores) {
        String[] p = v.split(",");
        List<String> out = new ArrayList<String>();
        for (String s : p) {
            s = s.trim();
            if (s.length() == 0) continue;
            out.add(underscores ? s.replace('_', ' ') : s);
        }
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ the tables in use

    private static volatile Data names0, buildings0, presets0, origins0, outside0;

    /** Installs each kind this file has. */
    static synchronized void install(Data d) {
        if (d.hasNames) names0 = d;
        if (d.hasBuildings) buildings0 = d;
        if (d.hasPresets) presets0 = d;
        if (d.hasOrigins) origins0 = d;
        if (d.hasOutside) outside0 = d;
    }

    /** A tool that made a map before loading any content: the built-in content.ini from the working directory. */
    private static synchronized void fallback() {
        if (names0 != null && buildings0 != null && presets0 != null && origins0 != null && outside0 != null) return;
        try {
            java.io.InputStream in = new java.io.FileInputStream("assets/content.ini");
            try {
                Content.load(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("content.ini's data isn't loaded: " + e.getMessage());
        }
        if (names0 == null || buildings0 == null || presets0 == null || origins0 == null || outside0 == null)
            throw new IllegalStateException("content.ini has no [names], [building], [preset], [origin] or [outside] sections");
    }

    private static Data names() {
        if (names0 == null) fallback();
        return names0;
    }

    static Data buildings() {
        if (buildings0 == null) fallback();
        return buildings0;
    }

    static Data presets() {
        if (presets0 == null) fallback();
        return presets0;
    }

    static Data originsTable() {
        if (origins0 == null) fallback();
        return origins0;
    }

    private static Data outsideData() {
        if (outside0 == null) fallback();
        return outside0;
    }

    /** A list of names ([names] key). */
    public static String[] names(String key) {
        String[] l = names().names.get(key);
        if (l == null || l.length == 0) throw new IllegalStateException("content.ini's [names] has no " + key);
        return l;
    }

    /** A list from [outside] (formation ids), or def if it has none. */
    public static String[] outside(String key, String[] def) {
        String[] l = outsideData().outside.get(key);
        return l == null || l.length == 0 ? def : l;
    }

    public static Building building(int kind) {
        List<Building> l = buildings().buildings;
        return kind >= 0 && kind < l.size() ? l.get(kind) : null;
    }

    public static String buildingName(int kind) {
        Building b = building(kind);
        return b == null ? "Building" : b.name;
    }

    /** A new building's roof colour: one of its kind's, picked with r when there are several (as the map makers did). */
    public static int roof(int kind, Rng r, boolean national) {
        Building b = building(kind);
        if (b == null) return 0xFF888888;
        int[] pal = national ? b.roofsNational : b.roofs;
        if (pal.length == 1) return pal[0];
        return pal[r.nextInt(pal.length)];
    }

    /** A kind's national roofs (the national painter picks from them by hash). */
    public static int[] roofs(int kind, boolean national) {
        Building b = building(kind);
        return b == null ? new int[]{0xFF888888} : national ? b.roofsNational : b.roofs;
    }

    public static boolean pitched(int kind) {
        Building b = building(kind);
        return b != null && b.pitched;
    }

    public static boolean home(int kind) {
        Building b = building(kind);
        return b != null && b.home;
    }

    public static boolean lit(int kind) {
        Building b = building(kind);
        return b != null && b.lit;
    }

    public static int presetCount() {
        return presets().presets.size();
    }

    public static Preset preset(int i) {
        List<Preset> l = presets().presets;
        return l.get(Math.max(0, Math.min(l.size() - 1, i)));
    }

    public static String presetName(int i) {
        return preset(i).name;
    }

    /** The kind of land a preset makes (RegionGen.COAST...). */
    public static int terrain(int preset) {
        return preset(preset).terrain;
    }

    public static List<Origin> origins() {
        return originsData();
    }

    private static List<Origin> originsData() {
        return originsTable().origins;
    }

    public static Origin origin(String id) {
        for (Origin o : originsData()) if (o.id.equals(id)) return o;
        return null;
    }
}
