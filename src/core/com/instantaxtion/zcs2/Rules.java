package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The rules sheet (New Game and God's pause menu): how fast the dead are and how easily they infect, how strong the
 * forces are, civilians on the streets or not, how people flee, outside help, the weather and the time of day. Each
 * change is a recorded input (Actions.rule), so a replay plays by the same rules; saved with the game.
 */
public final class Rules extends Module {
    public static final int Z_SPEED = 0, INFECTION = 1, FORCE = 2, CIVILIANS = 3, FLEE = 4, OUTSIDE = 5, WEATHER = 6, TIME = 7;
    public static final int KEYS = 8;
    public static final String[] KEY_NAMES = {"The dead's speed", "Infection", "Force strength", "Civilians", "Fleeing",
            "Outside help", "Weather", "Time of day"};
    /** The choices for each key, by value. */
    public static final String[][] CHOICES = {
            {"Slow", "Slower", "Normal", "Faster", "Fast"},
            {"Rare", "Lower", "Normal", "Higher", "Certain"},
            {"Weak", "Weaker", "Normal", "Stronger", "Strong"},
            {"Off", "On"},
            {"Normal", "Early", "Stay put"},
            {"Off", "On"},
            {"Natural", "Clear", "Cloudy", "Rain", "Storm", "Fog", "Snow"},
            {"Natural", "Always day", "Always night", "Dusk"}};
    public static final int[] DEFAULTS = {2, 2, 2, 1, 0, 1, 0, 0};
    private static final float[] SPEED = {0.6f, 0.8f, 1f, 1.25f, 1.5f};
    private static final float[] INFECT = {0.25f, 0.6f, 1f, 1.5f, 3f};
    private static final float[] FORCES = {0.55f, 0.8f, 1f, 1.2f, 1.45f};
    private static final float[] FLEE_SIGHT = {1f, 2f, 0.4f};
    private static final float[] HOURS = {-1, 13, 1, 19.5f};

    public final int[] v = DEFAULTS.clone();

    public Rules(World w) {
        super(w);
    }

    @Override
    public String id() {
        return "rules";
    }

    /** Sets one rule (Actions.rule carries it out). */
    public void set(int key, int value) {
        if (key < 0 || key >= KEYS) return;
        value = Math.max(0, Math.min(CHOICES[key].length - 1, value));
        v[key] = value;
        if (key == WEATHER && value > 0) w.weather.state = value - 1;
    }

    public boolean isDefault() {
        for (int k = 0; k < KEYS; k++) if (v[k] != DEFAULTS[k]) return false;
        return true;
    }

    public float zombieSpeed() {
        return SPEED[v[Z_SPEED]];
    }

    public float infection() {
        return INFECT[v[INFECTION]];
    }

    public float force() {
        return FORCES[v[FORCE]];
    }

    public boolean civilians() {
        return v[CIVILIANS] != 0;
    }

    /** How far people see the dead before they run (1 = as ever). */
    public float fleeSight() {
        return FLEE_SIGHT[v[FLEE]];
    }

    public boolean outsideHelp() {
        return v[OUTSIDE] != 0;
    }

    /** The weather held (Weather.*), or -1 for the natural weather. */
    public int weather() {
        return v[WEATHER] - 1;
    }

    /** The hour of the day held, or -1 for the clock. */
    public float hour() {
        return HOURS[v[TIME]];
    }

    public String text(int key) {
        return CHOICES[key][v[key]];
    }

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(KEYS);
        for (int k = 0; k < KEYS; k++) o.writeInt(v[k]);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        int n = in.readInt();
        for (int k = 0; k < n; k++) {
            int x = in.readInt();
            if (k < KEYS) v[k] = Math.max(0, Math.min(CHOICES[k].length - 1, x));
        }
    }
}
