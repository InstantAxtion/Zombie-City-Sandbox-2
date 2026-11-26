package com.instantaxtion.zcs2;

/** The player's settings, kept between sessions. */
public final class Settings {
    public int sound = 80, music = 50; // percent
    public boolean lean3d = true, blood = true, healthBars = true, names = true, radio = true, vibrate = true, minimap = true;
    public int maxIndividuals = 60000;
    public int threads = 0; // 0 = automatic
    public int uiSize = 1; // 0 small, 1 normal, 2 large
    public boolean realistic = true;
    /** Draw with the GPU (OpenGL ES) rather than Canvas. Takes effect on the next start. */
    public boolean gpu = false; // experimental until it has run on real phones
    public static final int[] MAX_OPTIONS = {20000, 40000, 60000, 100000};

    public void load(Platform p) {
        sound = i(p, "sound", sound);
        music = i(p, "music", music);
        lean3d = b(p, "lean3d", lean3d);
        blood = b(p, "blood", blood);
        healthBars = b(p, "healthBars", healthBars);
        names = b(p, "names", names);
        radio = b(p, "radio", radio);
        vibrate = b(p, "vibrate", vibrate);
        minimap = b(p, "minimap", minimap);
        maxIndividuals = i(p, "maxIndividuals", maxIndividuals);
        threads = i(p, "threads", threads);
        uiSize = i(p, "uiSize", uiSize);
        realistic = b(p, "realistic", realistic);
        gpu = b(p, "gpuOptIn", gpu);
    }

    public void save(Platform p) {
        p.setPref("sound", "" + sound);
        p.setPref("music", "" + music);
        p.setPref("lean3d", "" + lean3d);
        p.setPref("blood", "" + blood);
        p.setPref("healthBars", "" + healthBars);
        p.setPref("names", "" + names);
        p.setPref("radio", "" + radio);
        p.setPref("vibrate", "" + vibrate);
        p.setPref("minimap", "" + minimap);
        p.setPref("maxIndividuals", "" + maxIndividuals);
        p.setPref("threads", "" + threads);
        p.setPref("uiSize", "" + uiSize);
        p.setPref("realistic", "" + realistic);
        p.setPref("gpuOptIn", "" + gpu);
    }

    private static int i(Platform p, String k, int def) {
        try {
            return Integer.parseInt(p.pref(k, "" + def));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean b(Platform p, String k, boolean def) {
        return Boolean.parseBoolean(p.pref(k, "" + def));
    }

    public int simThreads() {
        if (threads > 0) return threads;
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, cores - 1));
    }
}
