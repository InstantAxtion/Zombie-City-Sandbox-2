package com.instantaxtion.zcs2;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ground, drawn once into pictures and reused: a one-pixel-per-tile picture of the whole region for the
 * far zooms, and 32x32-tile chunks at two levels of detail (roads with markings, pavements, crops, trees, water)
 * for the near ones. Chunks are painted a few per frame and kept in a cache; where one isn't ready yet the
 * region picture stands in.
 */
public final class MapPainter {
    public static final int CHUNK = 32;
    public static final int LOW = 4, HIGH = 10; // px per tile
    private final Region r;
    private final Platform pf;
    public GImage regionImage;
    private int[] regionPixels;
    private final Map<Integer, GImage> cache;
    private final int maxChunks;
    private int seenChanges;
    public int painted;
    public boolean realistic = true;

    public MapPainter(Region r, Platform pf, int maxChunks) {
        this.r = r;
        this.pf = pf;
        this.maxChunks = maxChunks;
        cache = new LinkedHashMap<Integer, GImage>(64, 0.75f, true);
        buildRegionImage();
        seenChanges = r.changes;
    }

    public static int tileColor(Region r, int x, int y, boolean texture) {
        int t = r.t(x, y);
        int c = Tile.MAP_COLOR[t];
        if (t == Tile.BUILDING) {
            int b = r.bldAt[y * r.W + x];
            if (b >= 0) {
                c = r.broof[b];
                if (x == r.bx[b] || y == r.by[b] || x == r.bx[b] + r.bw[b] - 1 || y == r.by[b] + r.bh[b] - 1) c = shade(c, 0.78f);
            }
        }
        if (texture && t != Tile.BUILDING) {
            float n = (Rng.unit(r.seed, x, y) - 0.5f) * (t == Tile.GRASS || t == Tile.FOREST || t == Tile.FARM ? 0.14f : 0.06f);
            c = shade(c, 1 + n);
        }
        return c;
    }

    public static int shade(int c, float k) {
        int a = c >>> 24, rr = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        rr = Math.min(255, Math.max(0, (int) (rr * k)));
        g = Math.min(255, Math.max(0, (int) (g * k)));
        b = Math.min(255, Math.max(0, (int) (b * k)));
        return (a << 24) | (rr << 16) | (g << 8) | b;
    }

    public static int mix(int c1, int c2, float t) {
        int r1 = (c1 >> 16) & 255, g1 = (c1 >> 8) & 255, b1 = c1 & 255;
        int r2 = (c2 >> 16) & 255, g2 = (c2 >> 8) & 255, b2 = c2 & 255;
        return 0xFF000000 | ((int) (r1 + (r2 - r1) * t) << 16) | ((int) (g1 + (g2 - g1) * t) << 8) | (int) (b1 + (b2 - b1) * t);
    }

    /** Tiles per pixel of the region picture (2 for very large maps, to keep it within a texture). */
    public final int step() {
        return r.W > 2048 ? 2 : 1;
    }

    private void buildRegionImage() {
        int st = step(), iw = r.W / st, ih = r.H / st;
        regionPixels = new int[iw * ih];
        for (int y = 0; y < ih; y++) for (int x = 0; x < iw; x++) regionPixels[y * iw + x] = tileColor(r, x * st, y * st, true);
        regionImage = pf.newImage(iw, ih);
        regionImage.setPixels(regionPixels, iw, ih);
    }

    /** Redraws what changed on the map since last time (rubble, blown bridges). */
    public void refresh() {
        if (r.changes == seenChanges) return;
        seenChanges = r.changes;
        for (int[] d : r.dirty) {
            int st = step(), iw = r.W / st;
            for (int y = Math.max(0, d[1]) / st; y <= Math.min(r.H - 1, d[3]) / st; y++)
                for (int x = Math.max(0, d[0]) / st; x <= Math.min(r.W - 1, d[2]) / st; x++) regionPixels[y * iw + x] = tileColor(r, x * st, y * st, true);
            // drop the chunks it touched
            for (int cy = d[1] / CHUNK; cy <= d[3] / CHUNK; cy++)
                for (int cx = d[0] / CHUNK; cx <= d[2] / CHUNK; cx++) {
                    for (int lvl = 0; lvl < 2; lvl++) {
                        GImage img = cache.remove(key(cx, cy, lvl));
                        if (img != null) img.recycle();
                    }
                }
        }
        r.dirty.clear();
        regionImage.setPixels(regionPixels, r.W / step(), r.H / step());
    }

    private static int key(int cx, int cy, int lvl) {
        return (lvl << 30) | (cy << 15) | cx;
    }

    /** A chunk's picture, painting it now if the frame's budget allows (else null). */
    public GImage chunk(int cx, int cy, int lvl, boolean mayPaint) {
        int k = key(cx, cy, lvl);
        GImage img = cache.get(k);
        if (img != null || !mayPaint) return img;
        int ppt = lvl == 0 ? LOW : HIGH;
        img = pf.newImage(CHUNK * ppt, CHUNK * ppt);
        paintChunk(img.gfx(), cx * CHUNK, cy * CHUNK, ppt);
        cache.put(k, img);
        painted++;
        while (cache.size() > maxChunks) {
            Integer oldest = cache.keySet().iterator().next();
            GImage o = cache.remove(oldest);
            if (o != null) o.recycle();
        }
        return img;
    }

    private void paintChunk(Gfx g, int tx0, int ty0, int p) {
        boolean high = p >= 8;
        // ground, tile by tile
        for (int y = 0; y < CHUNK; y++)
            for (int x = 0; x < CHUNK; x++) {
                int tx = tx0 + x, ty = ty0 + y;
                int t = r.t(tx, ty);
                int c = tileColor(r, tx, ty, realistic);
                if (t == Tile.BUILDING && high) c = 0xFF55504A; // the vector buildings cover these
                g.color(c);
                g.fillRect(x * p, y * p, p, p);
                if (!high) continue;
                float px = x * p, py = y * p;
                switch (t) {
                    case Tile.FARM:
                        g.color(shade(c, 0.86f));
                        for (int k = 1; k < 4; k++) g.fillRect(px, py + k * p / 4f, p, 1);
                        break;
                    case Tile.PAVEMENT:
                    case Tile.PLAZA:
                        g.color(shade(c, 0.9f));
                        g.fillRect(px, py, p, 1);
                        g.fillRect(px, py, 1, p);
                        break;
                    case Tile.WATER:
                    case Tile.DEEP:
                        if (Rng.unit(r.seed, tx, ty * 3) < 0.12f) {
                            g.color(shade(c, 1.15f));
                            g.fillRect(px + 2, py + p / 2f, p - 5, 1);
                        }
                        break;
                    case Tile.LOT:
                        if ((tx & 1) == 0) {
                            g.color(0xFF8A8A88);
                            g.fillRect(px, py + 1, 1, p - 2);
                        }
                        break;
                    case Tile.RUBBLE:
                        g.color(0xFF8E857A);
                        g.fillRect(px + 2, py + 3, p / 3f, p / 4f);
                        g.color(0xFF5E5850);
                        g.fillRect(px + p / 2f, py + p / 2f, p / 3f, p / 3f);
                        break;
                    case Tile.FENCE:
                    case Tile.WALL:
                        g.color(t == Tile.WALL ? 0xFF7A6E64 : 0xFF3A3A3A);
                        g.fillRect(px, py, p, p);
                        break;
                    case Tile.BRIDGE:
                        g.color(0xFF8A8480);
                        if (!Tile.roadish(r.t(tx, ty - 1)) && Tile.water(r.t(tx, ty - 1))) g.fillRect(px, py, p, 2);
                        if (!Tile.roadish(r.t(tx, ty + 1)) && Tile.water(r.t(tx, ty + 1))) g.fillRect(px, py + p - 2, p, 2);
                        if (!Tile.roadish(r.t(tx - 1, ty)) && Tile.water(r.t(tx - 1, ty))) g.fillRect(px, py, 2, p);
                        if (!Tile.roadish(r.t(tx + 1, ty)) && Tile.water(r.t(tx + 1, ty))) g.fillRect(px + p - 2, py, 2, p);
                        break;
                    default:
                }
            }
        // kerbs: a dark line where a road meets pavement or grass
        if (high) {
            g.color(0x55000000);
            for (int y = 0; y < CHUNK; y++)
                for (int x = 0; x < CHUNK; x++) {
                    int t = r.t(tx0 + x, ty0 + y);
                    if (!Tile.roadish(t)) continue;
                    if (!Tile.roadish(r.t(tx0 + x, ty0 + y - 1))) g.fillRect(x * p, y * p, p, 1.5f);
                    if (!Tile.roadish(r.t(tx0 + x, ty0 + y + 1))) g.fillRect(x * p, y * p + p - 1.5f, p, 1.5f);
                    if (!Tile.roadish(r.t(tx0 + x - 1, ty0 + y))) g.fillRect(x * p, y * p, 1.5f, p);
                    if (!Tile.roadish(r.t(tx0 + x + 1, ty0 + y))) g.fillRect(x * p + p - 1.5f, y * p, 1.5f, p);
                }
        }
        // road markings from the road graph
        RoadGraph rg = r.roads;
        float ox = tx0 * 16f, oy = ty0 * 16f, sc = p / 16f, size = CHUNK * 16f;
        for (int e = 0; e < rg.edgeCount; e++) {
            if (!rg.ealive[e]) continue;
            float ax = rg.nx[rg.ea[e]], ay = rg.ny[rg.ea[e]], bx = rg.nx[rg.eb[e]], by = rg.ny[rg.eb[e]];
            if (Math.max(ax, bx) < ox - 40 || Math.min(ax, bx) > ox + size + 40 || Math.max(ay, by) < oy - 40 || Math.min(ay, by) > oy + size + 40) continue;
            float dx = bx - ax, dy = by - ay, len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1) continue;
            float ux = dx / len, uy = dy / len;
            // keep the markings out of the junction squares
            float trim = rg.ehalf[e] + 6;
            if (len <= trim * 2) continue;
            float sx = ax + ux * trim, sy = ay + uy * trim, ex = bx - ux * trim, ey = by - uy * trim;
            boolean big = rg.ekind[e] != RoadGraph.STREET;
            if (high) {
                g.color(big ? 0xFFD8B640 : 0xCCE8E8E8);
                float dash = big ? 0 : 10;
                drawLine(g, (sx - ox) * sc, (sy - oy) * sc, (ex - ox) * sc, (ey - oy) * sc, Math.max(1, sc * 1.4f), dash * sc);
                if (rg.ekind[e] == RoadGraph.HIGHWAY) {
                    g.color(0xAAEEEEEE);
                    float h = rg.ehalf[e] - 4;
                    drawLine(g, (sx - uy * h - ox) * sc, (sy + ux * h - oy) * sc, (ex - uy * h - ox) * sc, (ey + ux * h - oy) * sc, Math.max(1, sc), 0);
                    drawLine(g, (sx + uy * h - ox) * sc, (sy - ux * h - oy) * sc, (ex + uy * h - ox) * sc, (ey - ux * h - oy) * sc, Math.max(1, sc), 0);
                }
            }
        }
        // trees: round canopies
        if (high) {
            for (int y = 0; y < CHUNK; y++)
                for (int x = 0; x < CHUNK; x++) {
                    int tx = tx0 + x, ty = ty0 + y;
                    int t = r.t(tx, ty);
                    boolean tree = t == Tile.TREE || (t == Tile.FOREST && Rng.unit(r.seed, tx * 7, ty) < 0.55f);
                    if (!tree) continue;
                    float jx = (Rng.unit(r.seed, tx, ty + 11) - 0.5f) * p * 0.5f, jy = (Rng.unit(r.seed, tx + 5, ty) - 0.5f) * p * 0.5f;
                    float rad = p * (0.45f + Rng.unit(r.seed, tx + 1, ty + 1) * 0.35f);
                    float cx = x * p + p / 2f + jx, cy = y * p + p / 2f + jy;
                    g.color(0x40000000);
                    g.fillCircle(cx + rad * 0.3f, cy + rad * 0.35f, rad);
                    g.color(t == Tile.FOREST ? 0xFF35592F : 0xFF3F6B36);
                    g.fillCircle(cx, cy, rad);
                    g.color(t == Tile.FOREST ? 0xFF41693A : 0xFF4E7E43);
                    g.fillCircle(cx - rad * 0.25f, cy - rad * 0.25f, rad * 0.55f);
                }
        }
    }

    private static void drawLine(Gfx g, float x1, float y1, float x2, float y2, float w, float dash) {
        if (dash <= 0) {
            g.line(x1, y1, x2, y2, w);
            return;
        }
        float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
        int n = (int) (len / (dash * 2));
        for (int i = 0; i < n; i++) {
            float a = i * dash * 2 / len, b = (i * dash * 2 + dash) / len;
            g.line(x1 + dx * a, y1 + dy * a, x1 + dx * b, y1 + dy * b, w);
        }
    }

    public void clear() {
        for (GImage i : cache.values()) i.recycle();
        cache.clear();
        if (regionImage != null) regionImage.recycle();
    }
}
