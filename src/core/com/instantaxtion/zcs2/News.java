package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/** The radio log and the news: what happened, when and where (tap a line to go there). */
public final class News {
    public static final int CALL = 0, UNIT = 1, LOSS = 2, COMMAND = 3, HEADLINE = 4;

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

    private final World w;
    public final List<Item> log = new ArrayList<Item>();
    /** The latest headline, for the ticker. */
    public Item headline;
    public int version;
    public boolean radioOn = true;

    News(World w) {
        this.w = w;
    }

    public void radio(String text, float x, float y, int kind) {
        // the same message again within a few seconds: once is enough
        for (int i = log.size() - 1; i >= Math.max(0, log.size() - 4); i--) {
            Item it = log.get(i);
            if (it.text.equals(text) && w.time - it.time < 12) return;
        }
        add(new Item(text, x, y, kind, w.time));
    }

    public void headline(String text, float x, float y) {
        Item it = new Item(text, x, y, HEADLINE, w.time);
        headline = it;
        add(it);
        w.stats.event(text, x, y);
        w.fx.sound("news", -1, -1);
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
