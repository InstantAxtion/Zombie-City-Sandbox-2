package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/**
 * The radio log and the news: what happened, when and where (tap a line to go there).
 *
 * The same message is said once, not every few seconds; things that happen over and over (columns reaching a
 * town, fire missions, formations sent) are said the first time and then added up: one line every few hours
 * with the totals ("Refugees reached Elm Creek: 12 columns, 40,000 people").
 */
public final class News {
    public static final int CALL = 0, UNIT = 1, LOSS = 2, COMMAND = 3, HEADLINE = 4;
    /** Game seconds a repeated line is held back, and the length of a tally's window. */
    public static final float REPEAT = 300, HEADLINE_REPEAT = 720, WINDOW = 360;

    public static final class Item {
        public final String text;
        public final float x, y;
        public final int kind;
        public final double time;

        Item(String text, float x, float y, int kind, double time) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.kind = kind;
            this.time = time;
        }
    }

    /** Something being added up: its text ({n} and {m} for the two numbers), where, and the totals so far. */
    static final class Tally {
        String key, text;
        float x, y;
        int kind, n, m;
        double since;
    }

    private final World w;
    public final List<Item> log = new ArrayList<Item>();
    /** The latest headline, for the ticker. */
    public Item headline;
    public int version;
    public boolean radioOn = true;
    final List<Tally> tallies = new ArrayList<Tally>();

    News(World w) {
        this.w = w;
    }

    private boolean recent(String text, float within) {
        for (int i = log.size() - 1; i >= Math.max(0, log.size() - 60); i--) {
            Item it = log.get(i);
            if (w.time - it.time > within) break;
            if (it.text.equals(text)) return true;
        }
        return false;
    }

    public void radio(String text, float x, float y, int kind) {
        if (recent(text, REPEAT)) return;
        add(new Item(text, x, y, kind, w.time));
    }

    public void headline(String text, float x, float y) {
        if (recent(text, HEADLINE_REPEAT)) return;
        Item it = new Item(text, x, y, HEADLINE, w.time);
        headline = it;
        add(it);
        w.stats.event(text, x, y);
        w.fx.sound("news", -1, -1);
    }

    /** Counts one more of something that happens over and over (text with %d for the running count). */
    public void count(String key, String text, int n, float x, float y) {
        tally(key, text.replace("%d", "{n}"), n, 0, x, y, COMMAND);
    }

    /**
     * Adds to a tally: the first in a while is said at once, the rest are added up and said together when the
     * window closes. text has {n} and {m} for the two totals.
     */
    public void tally(String key, String text, int n, int m, float x, float y, int kind) {
        for (Tally t : tallies)
            if (t.key.equals(key)) {
                t.n += n;
                t.m += m;
                t.x = x;
                t.y = y;
                t.text = text;
                return;
            }
        // the first: said now; the window starts
        Tally t = new Tally();
        t.key = key;
        t.text = text;
        t.x = x;
        t.y = y;
        t.kind = kind;
        t.since = w.time;
        tallies.add(t);
        say(first(text), n, m, x, y, kind);
    }

    /** A tally's text can be "said the first time|said for the totals". */
    private static String first(String text) {
        int b = text.indexOf('|');
        return b < 0 ? text : text.substring(0, b);
    }

    private static String totals(String text) {
        int b = text.indexOf('|');
        return b < 0 ? text : text.substring(b + 1);
    }

    private void say(String text, int n, int m, float x, float y, int kind) {
        String s = text.replace("{n}", WorldView.fmt(n)).replace("{m}", WorldView.fmt(m));
        add(new Item(s, x, y, kind, w.time));
        if (kind == HEADLINE) w.stats.event(s, x, y);
    }

    /** Once a second: tallies whose window has closed are said, with their totals. */
    void update() {
        for (int i = tallies.size() - 1; i >= 0; i--) {
            Tally t = tallies.get(i);
            if (w.time - t.since < WINDOW) continue;
            tallies.remove(i);
            if (t.n > 0 || t.m > 0) {
                // the window starts again with this line said: more of the same are added up again
                Tally again = new Tally();
                again.key = t.key;
                again.text = t.text;
                again.x = t.x;
                again.y = t.y;
                again.kind = t.kind;
                again.since = w.time;
                tallies.add(again);
                say(totals(t.text), t.n, t.m, t.x, t.y, t.kind);
            }
        }
    }

    void add(Item it) {
        log.add(it);
        if (log.size() > 400) log.remove(0);
        version++;
    }

    /** Restores an item from a save. */
    void restore(String text, float x, float y, int kind, double time) {
        Item it = new Item(text, x, y, kind, time);
        log.add(it);
        if (kind == HEADLINE) headline = it;
        version++;
    }
}
