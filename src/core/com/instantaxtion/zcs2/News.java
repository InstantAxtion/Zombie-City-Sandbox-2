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

    /** The most messages kept. When it's full the routine ones go first (units, then calls), the story stays. */
    public static final int CAP = 6000;

    /** A number as people write it: 1,234,567. Every number the game says goes through here. */
    public static String num(long n) {
        if (n < 0) return "-" + num(-n);
        if (n < 1000) return Long.toString(n);
        String s = Long.toString(n);
        StringBuilder sb = new StringBuilder(s.length() + s.length() / 3);
        int lead = s.length() % 3;
        if (lead > 0) sb.append(s, 0, lead);
        for (int i = lead; i < s.length(); i += 3) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s, i, i + 3);
        }
        return sb.toString();
    }

    /** "1 formation" / "3 formations". */
    public static String count(long n, String one, String many) {
        return num(n) + " " + (n == 1 ? one : many);
    }

    /**
     * A message's key: its text with the counts taken out, so "(212 of the dead)" and "(260 of the dead)" are the
     * same message. Names with numbers in them ("3rd Infantry", "Day 4") keep theirs.
     */
    static String keyOf(String text) {
        StringBuilder sb = null;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9' && (i == 0 || !Character.isLetter(text.charAt(i - 1)))) {
                int j = i;
                while (j + 1 < n && (Character.isDigit(text.charAt(j + 1)) || text.charAt(j + 1) == ',' && j + 2 < n && Character.isDigit(text.charAt(j + 2)))) j++;
                if (j + 1 < n && Character.isLetter(text.charAt(j + 1))) {
                    // an ordinal or a name: kept
                    if (sb != null) sb.append(text, i, j + 1);
                } else {
                    if (sb == null) sb = new StringBuilder(text.substring(0, i));
                    sb.append('#');
                }
                i = j;
            } else if (sb != null) sb.append(c);
        }
        return sb == null ? text : sb.toString();
    }

    /** Keys said recently, and when (the same message isn't said again within REPEAT). */
    private final java.util.HashMap<String, Double> said = new java.util.HashMap<String, Double>();

    private boolean recent(String key, float within) {
        Double t = said.get(key);
        if (t != null && w.time - t < within) return true;
        if (said.size() > 4000) {
            // forget the old ones
            java.util.Iterator<java.util.Map.Entry<String, Double>> it = said.entrySet().iterator();
            while (it.hasNext()) if (w.time - it.next().getValue() > HEADLINE_REPEAT) it.remove();
        }
        said.put(key, w.time);
        return false;
    }

    public void radio(String text, float x, float y, int kind) {
        radio(keyOf(text), text, x, y, kind);
    }

    /** A radio message said once for its key within REPEAT (the text may carry changing numbers). */
    public void radio(String key, String text, float x, float y, int kind) {
        radio(key, text, x, y, kind, REPEAT);
    }

    /** As above, not said again for its key within `within` game seconds. */
    public void radio(String key, String text, float x, float y, int kind, float within) {
        if (recent(key, within)) return;
        add(new Item(text, x, y, kind, w.time));
    }

    public void headline(String text, float x, float y) {
        if (recent(keyOf(text), HEADLINE_REPEAT)) return;
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
        String s = plural(text, n, m).replace("{n}", num(n)).replace("{m}", num(m));
        add(new Item(s, x, y, kind, w.time));
        if (kind == HEADLINE) w.stats.event(s, x, y);
    }

    /** "{n} more column{s}, {m} {mpeople}": the words agree with the numbers (one column, one person). */
    static String plural(String text, int n, int m) {
        if (text.indexOf('{') < 0) return text;
        return text.replace("{s}", n == 1 ? "" : "s").replace("{have}", n == 1 ? "has" : "have").replace("{people}", n == 1 ? "person" : "people")
                .replace("{ms}", m == 1 ? "" : "s").replace("{mpeople}", m == 1 ? "person" : "people");
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
        if (log.size() > CAP) drop();
        version++;
    }

    /** Full: the oldest routine message goes (unit chatter first, then calls, losses, orders; headlines last). */
    private void drop() {
        int[] order = {UNIT, CALL, LOSS, COMMAND, HEADLINE};
        int half = log.size() / 2;
        for (int kind : order)
            for (int i = 0; i < half; i++)
                if (log.get(i).kind == kind) {
                    log.remove(i);
                    return;
                }
        log.remove(0);
    }

    /** Restores an item from a save. */
    void restore(String text, float x, float y, int kind, double time) {
        Item it = new Item(text, x, y, kind, time);
        log.add(it);
        said.put(keyOf(text), time);
        if (kind == HEADLINE) headline = it;
        version++;
    }
}
