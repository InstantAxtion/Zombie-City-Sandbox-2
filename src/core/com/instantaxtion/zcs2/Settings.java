package com.instantaxtion.zcs2;

/** The player's settings, kept between sessions. */
public final class Settings {
    public int sound = 80, music = 50; // percent
    public boolean lean3d = true, blood = true, healthBars = true, names = true, radio = true, vibrate = true, minimap = true;
    /** Frame rate and counts in a corner, for testing. */
    public boolean debug = false;
    public int maxIndividuals = 60000;
    public int threads = 0; // 0 = automatic
    public int uiSize = 1; // 0 small, 1 normal, 2 large
    public boolean realistic = true;
    /** Draw with the GPU (OpenGL ES) rather than Canvas. Takes effect on the next start. */
    public boolean gpu = false; // experimental until it has run on real phones
    /** The screen shakes with nearby blasts. */
    public boolean shake = true;
    /** Effects: 0 low (fewer particles), 1 normal, 2 high. */
    public int effects = 1;
    /** Frames a second at most: 60, or 30 to save the battery. */
    public int fpsCap = 60;
    /** Both ears the same (one earbud, a phone's single speaker). */
    public boolean mono = false;
    /** Short lines on the screen for the important sounds, with where they came from. */
    public boolean captions = false;
    /** Names under the formations' and squads' symbols. */
    public boolean unitNames = true;
    /** Colours for the dead, the living, police and army that differ for every kind of colour blindness. */
    public boolean colourBlind = false;
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
        debug = b(p, "debug", debug);
        shake = b(p, "shake", shake);
        effects = Math.max(0, Math.min(2, i(p, "effects", effects)));
        fpsCap = i(p, "fpsCap", fpsCap) == 30 ? 30 : 60;
        mono = b(p, "mono", mono);
        captions = b(p, "captions", captions);
        unitNames = b(p, "unitNames", unitNames);
        colourBlind = b(p, "colourBlind", colourBlind);
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
        p.setPref("debug", "" + debug);
        p.setPref("shake", "" + shake);
        p.setPref("effects", "" + effects);
        p.setPref("fpsCap", "" + fpsCap);
        p.setPref("mono", "" + mono);
        p.setPref("captions", "" + captions);
        p.setPref("unitNames", "" + unitNames);
        p.setPref("colourBlind", "" + colourBlind);
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
