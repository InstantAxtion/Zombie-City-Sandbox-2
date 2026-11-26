package com.instantaxtion.zcs2;

/**
 * Draws the world at any zoom. Close in: every person, vehicle and shot, buildings standing up with walls, windows,
 * roofs and soft shadows, leaning away from the screen's middle. Further out: people as dots over glowing density,
 * the ground from cached chunks. Right out: an operations table of the region: the infection as a soft red bleed,
 * hordes, refugee columns, formations as unit symbols, towns with their numbers. Everything that marks something
 * (people, symbols, labels, rings) is sized in dp, so it reads the same on any phone; labels have outlines.
 */
public final class WorldView {
    private final World w;
    private final Platform pf;
    public final MapPainter map;
    /** On a real-scale map: the country and district pictures (null on today's maps). */
    public final NationPainter np;
    private final Nation nat;
    public boolean lean3d = true, blood = true, healthBars = true, names = true;
    /** No words on the map at all (the menu's backdrop). */
    public boolean noLabels;
    public Squads.Squad selected;
    public Forces.Formation selectedFormation;
    /** The map overlay shown (the national map's layers). */
    public int overlay = OV_INFECTION;
    public static final int OV_NONE = 0, OV_INFECTION = 1, OV_UTIL = 2, OV_SUPPLY = 3, OV_TRUST = 4, OV_FORCES = 5, OV_DEATHS = 6, OV_PEAK = 7;
    public static final String[] OV_NAMES = {"Plain", "Infection", "Utilities", "Supplies", "Trust and fear", "Forces and fronts", "Deaths", "Peak infection"};
    public int inspectEnt = -1, inspectVehicle = -1;
    public float brushX = -1, brushY, brushR, brushT;
    public int brushColor;
    /** An aimed strike waiting for confirmation (world x, y and radius), or x < 0. */
    public float aimX = -1, aimY, aimR;
    /** Seconds of drawing (animations). */
    public float time;
    /** Screen pixels per dp this frame. */
    public float dp = 1;
    private float[] pts = new float[4096];
    private final float[] poly = new float[16];
    /** Buildings that have burned (drawn charred): what the view has seen, not saved. */
    private boolean[] scorched;
    /** National maps: buildings seen burning, by id (Region's or a chunk's encoded ids). */
    private final java.util.HashSet<Integer> scorchedIds = new java.util.HashSet<Integer>();

    // soft sprites, made once: glows, smoke, shadow, the vignette
    GImage glowWarm, glowRed, glowBlue, glowWhite, glowFire, smoke, shadow, vignette;
    private final SecMap infection = new SecMap(0), dark = new SecMap(1), heat = new SecMap(2);
    private final CrowdMap crowd = new CrowdMap();

    public static final int LIVING_LINE = 0xFF1C1712;
    // the people's colours (palette() switches them to a colour-blind-safe set)
    public static int DEAD_RIM = 0xFF6E1616, C_DEAD = 0xFF8FAAA6, C_DEAD_FAR = 0xFFB8483E, C_CIV = 0xFFF0DCB0, C_COP = 0xFF4C88FF,
            C_ARMY = 0xFFCDBE78;
    static boolean colourBlind;

    /**
     * The colours for the dead, the living, police and army: the usual ones, or a set that stays apart for every kind
     * of colour blindness (vermilion, white, sky blue and yellow differ in brightness as well as hue).
     */
    public static void palette(boolean cb) {
        colourBlind = cb;
        C_DEAD = cb ? 0xFFD55E00 : 0xFF8FAAA6;
        C_DEAD_FAR = cb ? 0xFFD55E00 : 0xFFB8483E;
        DEAD_RIM = cb ? 0xFF4A1800 : 0xFF6E1616;
        C_CIV = cb ? 0xFFF4F4F4 : 0xFFF0DCB0;
        C_COP = cb ? 0xFF56B4E9 : 0xFF4C88FF;
        C_ARMY = cb ? 0xFFF0E442 : 0xFFCDBE78;
    }

    /** Particle detail: 0 low, 1 normal, 2 high. */
    public int effects = 1;
    /** Names under formations' symbols. */
    public boolean unitNames = true;

    public WorldView(World w, Platform pf) {
        this.w = w;
        this.pf = pf;
        if (w.r.nation != null) {
            // a real-scale map: the national painter, its street pictures from the world's own chunks
            np = new NationPainter(worldSource(w), pf);
            nat = w.r.nation;
            map = new MapPainter(np.src, pf, np.countryImage);
            map.watch(w.r);
        } else {
            map = new MapPainter(w.r, pf, 64);
            np = null;
            nat = null;
        }
        sprites();
    }

    /**
     * Drawing a national World: a chunk the active set holds is drawn as it stands (with the game's edits: rubble,
     * barricades), else the cache's copy; missing ones are made in the background. Never touches the simulation.
     */
    static NationPainter.Source worldSource(final World w) {
        final Region r = w.r;
        final NationPainter.Source cached = NationPainter.source(r.nation, r.cache);
        return new NationPainter.Source() {
            public Nation nation() {
                return r.nation;
            }

            public Chunk chunk(int cx, int cy) {
                if (cx < 0 || cy < 0 || cx >= r.store.pw || cy >= r.store.ph) return null;
                Chunk c = r.store.chunk(cy * r.store.pw + cx);
                return c != null ? c : cached.chunk(cx, cy);
            }

            public void want(int cx, int cy) {
                cached.want(cx, cy);
            }
        };
    }

    /**
     * A view of a real-scale map with no world on it yet: the ground at every zoom (the country picture, district
     * pictures, street pictures from cached chunks), rivers, roads and railways, buildings, places and facilities.
     */
    public WorldView(NationPainter.Source src, Platform pf) {
        this.w = null;
        this.pf = pf;
        np = new NationPainter(src, pf);
        nat = src.nation();
        map = new MapPainter(src, pf, np.countryImage);
        sprites();
    }

    private void sprites() {
        glowWarm = sprite(0xFFC070, 1.6f);
        glowRed = sprite(0xFF3030, 1.6f);
        glowBlue = sprite(0x4080FF, 1.6f);
        glowWhite = sprite(0xFFFFFF, 1.4f);
        glowFire = sprite(0xFF8A20, 1.2f);
        smoke = sprite(0x3A3836, 1.1f);
        shadow = sprite(0x000000, 1.2f);
        vignette = vignetteImage();
    }

    /** A soft round sprite: the colour fading out from the middle. */
    private GImage sprite(int rgb, float power) {
        int n = 64;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) {
                float dx = (x + 0.5f - n / 2f) / (n / 2f), dy = (y + 0.5f - n / 2f) / (n / 2f);
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float a = d >= 1 ? 0 : (float) Math.pow(1 - d, power);
                px[y * n + x] = ((int) (a * 255) << 24) | rgb;
            }
        GImage img = pf.newImage(n, n);
        img.setPixels(px, n, n);
        return img;
    }

    private GImage vignetteImage() {
        int n = 64;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) {
                float dx = (x + 0.5f - n / 2f) / (n / 2f), dy = (y + 0.5f - n / 2f) / (n / 2f);
                float d = Math.max(0, (float) Math.sqrt(dx * dx * 0.8f + dy * dy * 0.8f) - 0.55f) / 0.6f;
                float a = Math.min(1, d * d) * 0.55f;
                px[y * n + x] = ((int) (a * 255) << 24) | 0x05070A;
            }
        GImage img = pf.newImage(n, n);
        img.setPixels(px, n, n);
        return img;
    }

    /** A sprite drawn centred, size in screen px, faded. */
    void glow(Gfx g, GImage img, float x, float y, float size, float alpha) {
        g.image(img, x - size / 2, y - size / 2, size, size, alpha);
    }

    /**
     * A label over the map: dp size, an outline so it reads over anything. It takes its place among this frame's
     * labels and is left out if it would land on one already there, or outside the map area the bars leave free.
     */
    public void label(Gfx g, String s, float x, float y, float sizeDp, int color, int align, boolean bold) {
        float sz = sizeDp * dp, tw = g.textWidth(s, sz, bold);
        float x0 = align == Gfx.ALIGN_CENTER ? x - tw / 2 : align == Gfx.ALIGN_RIGHT ? x - tw : x;
        float ly0 = y - sz * 0.95f, ly1 = y + sz * 0.35f;
        int k = (placedN - 1) * 4;
        boolean callers = justPlaced && placedN > 0 && x0 < placed[k + 2] && x0 + tw > placed[k] && ly0 < placed[k + 3] && ly1 > placed[k + 1];
        // unless the caller has just taken this label's place, it finds its own
        if (!callers && !place(x0 - 2 * dp, ly0, x0 + tw + 2 * dp, ly1)) return;
        labelAlways(g, s, x, y, sizeDp, color, align, bold);
    }

    /** A label drawn whatever else is there (its place already taken, or a mark that must show). */
    public void labelAlways(Gfx g, String s, float x, float y, float sizeDp, int color, int align, boolean bold) {
        justPlaced = false;
        if (noLabels) return;
        if (deferLabels && lqN < lqText.length) {
            // while the map's marks are still being drawn, the words wait, so no dot, symbol or badge lands on them
            int k = lqN++;
            lqText[k] = s;
            lqXY[k * 3] = x;
            lqXY[k * 3 + 1] = y;
            lqXY[k * 3 + 2] = sizeDp;
            lqColor[k] = color;
            lqStyle[k] = (byte) (align | (bold ? 4 : 0));
            return;
        }
        g.color(color);
        g.textHalo(s, x, y, sizeDp * dp, align, bold, 0xD0000000, Math.max(1.2f, 1.3f * dp));
    }

    // the labels placed while the overlays and the modules draw their marks, drawn over all of them at the end
    private boolean deferLabels;
    private final String[] lqText = new String[384];
    private final float[] lqXY = new float[384 * 3];
    private final int[] lqColor = new int[384];
    private final byte[] lqStyle = new byte[384];
    private int lqN;

    /** Draws the labels that waited: the first placed (the most important) last, so on top. */
    private void flushLabels(Gfx g) {
        deferLabels = false;
        float hw = Math.max(1.2f, 1.3f * dp);
        for (int k = lqN - 1; k >= 0; k--) {
            g.color(lqColor[k]);
            g.textHalo(lqText[k], lqXY[k * 3], lqXY[k * 3 + 1], lqXY[k * 3 + 2] * dp, lqStyle[k] & 3, (lqStyle[k] & 4) != 0, 0xD0000000, hw);
            lqText[k] = null;
        }
        lqN = 0;
    }

    // where labels may go this frame (the map area the bars leave free), and areas the interface keeps for itself
    private float areaX0 = -1e9f, areaY0 = -1e9f, areaX1 = 1e9f, areaY1 = 1e9f;
    private final float[] keep = new float[4 * 16];
    private int keepN;

    /** For the next frame drawn only: labels stay inside this screen rectangle. */
    public void labelArea(float x0, float y0, float x1, float y1) {
        areaX0 = x0;
        areaY0 = y0;
        areaX1 = x1;
        areaY1 = y1;
    }

    /** For the next frame drawn only: no labels in this screen rectangle (the radio, a note). */
    public void keepOut(float x0, float y0, float x1, float y1) {
        if (keepN >= 16 || x1 <= x0 || y1 <= y0) return;
        int k = keepN++ * 4;
        keep[k] = x0;
        keep[k + 1] = y0;
        keep[k + 2] = x1;
        keep[k + 3] = y1;
    }

    public void draw(Gfx g, Camera cam, float dt) {
        if (w == null) {
            drawNation(g, cam, dt);
            return;
        }
        time += dt;
        dp = cam.dp;
        map.refresh();
        float z = cam.zoom;
        g.color(MapPainter.GROUND[Tile.DEEP]);
        g.fillRect(0, 0, g.width(), g.height());
        ground(g, cam);
        float x0 = cam.x0() - 80, y0 = cam.y0() - 80, x1 = cam.x1() + 80, y1 = cam.y1() + 80;
        smoothLines(g, cam, x0, y0, x1, y1);
        if (z >= 0.3f && blood) decals(g, cam, x0, y0, x1, y1);
        if (z >= 0.3f) bodies(g, cam, x0, y0, x1, y1);
        if (z >= 0.18f && z < 0.6f) crowd.draw(g, cam);
        if (z >= 0.22f) buildings(g, cam, x0, y0, x1, y1);
        nightShade(g, cam);
        if (z >= 0.22f) vehicles(g, cam, x0, y0, x1, y1, false);
        people(g, cam, x0, y0, x1, y1);
        if (z >= 0.22f) vehicles(g, cam, x0, y0, x1, y1, true);
        else vehicleDots(g, cam);
        effects(g, cam, x0, y0, x1, y1);
        lqN = 0;
        deferLabels = true;
        overlays(g, cam);
        for (int i = 0; i < w.modules.size(); i++) w.modules.get(i).draw(g, cam, this);
        flushLabels(g);
        areaX0 = areaY0 = -1e9f;
        areaX1 = areaY1 = 1e9f;
        if (brushX >= 0 && time - brushT < 0.8f) {
            g.color(brushColor);
            float bx = cam.toScreenX(brushX), by = cam.toScreenY(brushY), br = Math.max(brushR * z, 6 * dp);
            g.strokeCircle(bx, by, br, 2 * dp);
            g.color(MapPainter.alpha(brushColor, 0.18f));
            g.fillCircle(bx, by, br);
        }
        if (aimX >= 0) reticle(g, cam);
        g.image(vignette, 0, 0, g.width(), g.height());
    }

    /** The aim of a strike waiting to be confirmed: a pulsing reticle with the blast's reach. */
    private void reticle(Gfx g, Camera cam) {
        float x = cam.toScreenX(aimX), y = cam.toScreenY(aimY);
        float r = Math.max(aimR * cam.zoom, 14 * dp), pulse = 0.5f + 0.5f * (float) Math.sin(time * 6);
        g.color(0x40FF3020);
        g.fillCircle(x, y, r);
        g.color(0xFFFF5040);
        g.strokeCircle(x, y, r, 2 * dp);
        g.strokeCircle(x, y, r * (0.35f + 0.1f * pulse), 1.5f * dp);
        float a = r + 8 * dp, b = r * 0.5f;
        g.line(x - a, y, x - b, y, 2 * dp);
        g.line(x + b, y, x + a, y, 2 * dp);
        g.line(x, y - a, x, y - b, 2 * dp);
        g.line(x, y + b, x, y + a, 2 * dp);
    }

    // ------------------------------------------------------------------ ground

    private long paintBudgetNs = 6_000_000L;
    private float lastCx, lastCy, lastZ, stillT;
    private int framesDrawn;

    private void ground(Gfx g, Camera cam) {
        if (np != null) {
            groundNation(g, cam);
            return;
        }
        float z = cam.zoom;
        float worldPx = w.r.px();
        // the whole-region picture underneath everything (and on its own when far out)
        g.image(map.regionImage, cam.toScreenX(0), cam.toScreenY(0), worldPx * z, worldPx * z);
        framesDrawn++;
        // still camera: paint more each frame (and the ring round the view)
        boolean still = Math.abs(cam.cx - lastCx) * z < 1 && Math.abs(cam.cy - lastCy) * z < 1 && Math.abs(z - lastZ) < 0.0005f;
        stillT = still ? stillT + 0.016f : 0;
        lastCx = cam.cx;
        lastCy = cam.cy;
        lastZ = z;
        if (z < 0.1f) return;
        int lvl = MapPainter.levelFor(z);
        float chunkPx = MapPainter.CHUNK * Tile.SIZE;
        int n = w.r.W / MapPainter.CHUNK;
        int cx0 = Math.max(0, (int) (cam.x0() / chunkPx)), cy0 = Math.max(0, (int) (cam.y0() / chunkPx));
        int cx1 = Math.min(n - 1, (int) (cam.x1() / chunkPx)), cy1 = Math.min(n - 1, (int) (cam.y1() / chunkPx));
        long budget = framesDrawn < 20 ? 40_000_000L : stillT > 0.25f ? 14_000_000L : paintBudgetNs;
        long t0 = System.nanoTime();
        // paint the chunks nearest the middle first
        float mx = (cx0 + cx1) / 2f, my = (cy0 + cy1) / 2f;
        int rings = Math.max(cx1 - cx0, cy1 - cy0);
        for (int ring = 0; ring <= rings; ring++)
            for (int cy = cy0; cy <= cy1; cy++)
                for (int cx = cx0; cx <= cx1; cx++) {
                    if ((int) Math.max(Math.abs(cx - mx), Math.abs(cy - my)) != ring) continue;
                    boolean may = System.nanoTime() - t0 < budget;
                    GImage img = map.chunk(cx, cy, lvl, may);
                    if (img == null) {
                        // a coarser one stands in: a middle one is cheap to paint, so make that if nothing better is there
                        for (int l = lvl - 1; l >= 0 && img == null; l--) img = map.chunk(cx, cy, l, false);
                        if ((img == null || !map.has(cx, cy, Math.min(1, lvl))) && lvl >= 1 && System.nanoTime() - t0 < budget + 4_000_000L)
                            img = map.chunk(cx, cy, 1, true);
                        if (img == null) img = map.chunk(cx, cy, 0, true);
                    }
                    if (img == null) continue;
                    float sx = cam.toScreenX(cx * chunkPx), sy = cam.toScreenY(cy * chunkPx);
                    g.image(img, sx, sy, chunkPx * z + 0.75f, chunkPx * z + 0.75f);
                }
        // with time to spare, the ring just outside the view
        if (stillT > 0.5f) {
            for (int cy = Math.max(0, cy0 - 1); cy <= Math.min(n - 1, cy1 + 1); cy++)
                for (int cx = Math.max(0, cx0 - 1); cx <= Math.min(n - 1, cx1 + 1); cx++) {
                    if (cx >= cx0 && cx <= cx1 && cy >= cy0 && cy <= cy1) continue;
                    if (System.nanoTime() - t0 > budget) return;
                    map.chunk(cx, cy, lvl, true);
                }
        }
    }

    private void decals(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        Effects fx = w.fx;
        float z = cam.zoom;
        g.color(0x9A5A0808);
        for (int k = 0; k < fx.dCount; k++) {
            float x = fx.dx[k], y = fx.dy[k];
            if (x < x0 || x > x1 || y < y0 || y > y1) continue;
            g.fillCircle(cam.toScreenX(x), cam.toScreenY(y), Math.max((2 + fx.dsize[k]) * z, 1.5f * dp));
        }
    }

    private void bodies(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        Ents e = w.e;
        float z = cam.zoom;
        float s = Math.max(4 * z, 2.6f * dp), lw = Math.max(3.2f * z, 1.8f * dp);
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.BODY) continue;
            float x = e.x[i], y = e.y[i];
            if (x < x0 || x > x1 || y < y0 || y > y1) continue;
            float sx = cam.toScreenX(x), sy = cam.toScreenY(y);
            int c = e.kind[i] == Ents.ZOMBIE ? 0xFF4E5E5C : e.kind[i] == Ents.COP ? 0xFF2A3F78 : e.kind[i] == Ents.SOLDIER ? 0xFF6A6440 : 0xFF8A7A66;
            float a = e.face[i];
            float dx = (float) Math.cos(a) * s, dy = (float) Math.sin(a) * s;
            g.color(0x70300808);
            g.fillCircle(sx + dx * 0.3f, sy + dy * 0.3f, lw * 1.1f);
            g.color(c);
            g.line(sx - dx, sy - dy, sx + dx, sy + dy, lw);
            if ((e.flags[i] & Ents.F_RISES) != 0 && e.timer[i] < 3) {
                // stirring
                g.color(0xA0D04040);
                g.strokeCircle(sx, sy, Math.max(6 * z, 5 * dp), 1.2f * dp);
            }
        }
    }

    // ------------------------------------------------------------------ buildings

    private int[] bIndex, bStart;
    private int indexed = -1, idxW;
    private int[] vis = new int[1024];

    /** Buildings by 32-tile chunk (by their top-left corner), so a frame only looks at the ones near the view. */
    private void indexBuildings() {
        Region r = w.r;
        idxW = r.W / 32 + 1;
        int cells = idxW * idxW;
        int[] count = new int[cells + 1];
        for (int b = 0; b < r.bCount; b++) count[(r.by(b) / 32) * idxW + r.bx(b) / 32]++;
        bStart = new int[cells + 1];
        for (int c = 0; c < cells; c++) bStart[c + 1] = bStart[c] + count[c];
        bIndex = new int[r.bCount];
        int[] fill = java.util.Arrays.copyOf(bStart, cells);
        for (int b = 0; b < r.bCount; b++) bIndex[fill[(r.by(b) / 32) * idxW + r.bx(b) / 32]++] = b;
        indexed = r.bCount;
        if (scorched == null || scorched.length < r.bCount) scorched = scorched == null ? new boolean[r.bCount] : java.util.Arrays.copyOf(scorched, r.bCount);
    }

    /** The buildings near the view into vis[]; returns how many. */
    private int visible(float x0, float y0, float x1, float y1) {
        Region r = w.r;
        if (indexed != r.bCount) indexBuildings();
        int tx0 = Math.max(0, (int) (x0 / 16) - 40), ty0 = Math.max(0, (int) (y0 / 16) - 40);
        int tx1 = Math.min(r.W - 1, (int) (x1 / 16) + 4), ty1 = Math.min(r.H - 1, (int) (y1 / 16) + 4);
        int n = 0;
        for (int cy = ty0 / 32; cy <= ty1 / 32; cy++)
            for (int cx = tx0 / 32; cx <= tx1 / 32; cx++) {
                int c = cy * idxW + cx;
                for (int k = bStart[c]; k < bStart[c + 1]; k++) {
                    int b = bIndex[k];
                    if (r.bfloors(b) <= 0) continue;
                    int bx = r.bx(b), by = r.by(b);
                    if (bx > tx1 || by > ty1 || bx + r.bw(b) < tx0 + 36 || by + r.bh(b) < ty0 + 36) continue;
                    if (n == vis.length) vis = java.util.Arrays.copyOf(vis, n * 2);
                    vis[n++] = b;
                }
            }
        return n;
    }

    private int visN;

    // the buildings drawn this frame: tiles, floors, roof colour, kind and an id (which picks the roof's details)
    private int[] dbx = new int[1024], dby = new int[1024], dbw = new int[1024], dbh = new int[1024], dbf = new int[1024],
            dbr = new int[1024], dbk = new int[1024], dbid = new int[1024];

    private void growDrawn(int n) {
        if (n <= dbx.length) return;
        int m = Math.max(n, dbx.length * 2);
        dbx = java.util.Arrays.copyOf(dbx, m);
        dby = java.util.Arrays.copyOf(dby, m);
        dbw = java.util.Arrays.copyOf(dbw, m);
        dbh = java.util.Arrays.copyOf(dbh, m);
        dbf = java.util.Arrays.copyOf(dbf, m);
        dbr = java.util.Arrays.copyOf(dbr, m);
        dbk = java.util.Arrays.copyOf(dbk, m);
        dbid = java.util.Arrays.copyOf(dbid, m);
    }

    private void buildings(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        int n;
        if (np != null) {
            n = visN = nationBuildings(x0, y0, x1, y1);
            if (w != null)
                for (int k = 0; k < n; k++) {
                    // burned (by encoded id: a chunk's buildings too), drawn charred from then on
                    if (w.fires.heat(dbid[k]) > 0) scorchedIds.add(dbid[k]);
                    if (!scorchedIds.isEmpty() && scorchedIds.contains(dbid[k])) dbr[k] = MapPainter.mix(dbr[k], 0xFF201A16, 0.7f);
                }
        } else {
            Region r = w.r;
            n = visN = visible(x0, y0, x1, y1);
            for (int k = 0; k < n; k++) {
                int b = vis[k];
                if (w.fires.heat(b) > 0) scorched[b] = true;
            }
            growDrawn(n);
            for (int k = 0; k < n; k++) {
                int b = vis[k];
                dbx[k] = r.bx(b);
                dby[k] = r.by(b);
                dbw[k] = r.bw(b);
                dbh[k] = r.bh(b);
                dbf[k] = r.bfloors(b);
                int roof = r.broof(b);
                if (scorched[b]) roof = MapPainter.mix(roof, 0xFF201A16, 0.7f);
                dbr[k] = roof;
                dbk[k] = r.bkind(b);
                dbid[k] = b;
            }
        }
        drawBuildings(g, cam, n);
    }

    /** Buildings standing up: soft shadows, walls leaning away from the screen's middle, roofs. */
    private void drawBuildings(Gfx g, Camera cam, int n) {
        float z = cam.zoom;
        float ccx = cam.cx, ccy = cam.cy;
        // on a real-scale map the buildings rise out of the street pictures' roofs as the zoom grows, so nothing
        // jumps where they start being drawn (today's maps keep drawing them standing from the start)
        float rise = np != null ? clamp01((z - 0.22f) / 0.12f) : 1;
        float lk = lean3d ? 0.0016f * rise : 0;
        float maxLean = 60 * rise;
        boolean detail = z > 0.45f;
        // pass 0: soft shadows (the sun in the south-west... from the top left), pass 1: walls, pass 2: roofs
        for (int pass = 0; pass < 3; pass++) {
            for (int k = 0; k < n; k++) {
                int b = dbid[k];
                int floors = dbf[k];
                float wx = dbx[k] * 16f, wy = dby[k] * 16f, ww = dbw[k] * 16f, wh = dbh[k] * 16f;
                float sx = cam.toScreenX(wx), sy = cam.toScreenY(wy), sw = ww * z, sh = wh * z;
                float height = Math.min(40, floors) * 3.2f + 4;
                if (pass == 0) {
                    if (rise <= 0) continue;
                    float off = height * 0.32f * z * rise;
                    g.color(0x22000000);
                    g.fillRect(sx + off * 0.5f, sy + off * 0.65f, sw + off * 0.5f, sh + off * 0.5f);
                    g.color(0x26000000);
                    g.fillRect(sx + off * 0.25f, sy + off * 0.35f, sw + off * 0.3f, sh + off * 0.3f);
                    continue;
                }
                float lx = (wx + ww / 2 - ccx) * lk * height * z, ly = (wy + wh / 2 - ccy) * lk * height * z;
                float ll = (float) Math.sqrt(lx * lx + ly * ly);
                if (ll > maxLean * z * 2) {
                    lx *= maxLean * z * 2 / ll;
                    ly *= maxLean * z * 2 / ll;
                }
                int roof = dbr[k];
                if (pass == 1) {
                    if (!lean3d || rise <= 0) continue;
                    int wall = MapPainter.mix(MapPainter.shade(roof, 0.58f), 0xFF3C3E44, 0.35f);
                    if (ly < 0) face(g, sx, sy, sx + sw, sy, lx, ly, MapPainter.shade(wall, 1.12f), floors, -ly, detail);
                    if (lx > 0) face(g, sx + sw, sy, sx + sw, sy + sh, lx, ly, MapPainter.shade(wall, 0.86f), floors, lx, detail);
                    if (ly > 0) face(g, sx, sy + sh, sx + sw, sy + sh, lx, ly, MapPainter.shade(wall, 0.72f), floors, ly, detail);
                    if (lx < 0) face(g, sx, sy, sx, sy + sh, lx, ly, MapPainter.shade(wall, 1.0f), floors, -lx, detail);
                } else {
                    float rx = sx + lx, ry = sy + ly;
                    roof(g, b, dbk[k], rx, ry, sw, sh, z, roof, detail);
                }
            }
        }
    }

    /** One wall between its base edge and the roof edge, with rows of windows when it shows enough. */
    private void face(Gfx g, float ax, float ay, float bx, float by, float lx, float ly, int c, int floors, float depth, boolean detail) {
        poly[0] = ax;
        poly[1] = ay;
        poly[2] = bx;
        poly[3] = by;
        poly[4] = bx + lx;
        poly[5] = by + ly;
        poly[6] = ax + lx;
        poly[7] = ay + ly;
        g.color(c);
        g.fillPoly(poly, 4);
        if (!detail || depth < 5) return;
        int rows = Math.min(floors, (int) (depth / 3.5f));
        if (rows <= 0) return;
        g.color(0x58101826);
        float lw = Math.max(1, depth / rows * 0.38f);
        float ex = (bx - ax), ey = (by - ay), el = (float) Math.sqrt(ex * ex + ey * ey);
        if (el < 4) return;
        float inset = Math.min(3, el * 0.1f) / el;
        for (int k = 0; k < rows; k++) {
            float f = (k + 0.55f) / (rows + 0.2f);
            float px = ax + lx * f, py = ay + ly * f;
            g.line(px + ex * inset, py + ey * inset, px + ex * (1 - inset), py + ey * (1 - inset), lw);
        }
    }

    private static final int SHAPE_FLAT = 0, SHAPE_PITCHED = 1;

    private void roof(Gfx g, int b, int kind, float x, float y, float rw, float rh, float z, int roof, boolean detail) {
        int shape = Data.pitched(kind) ? SHAPE_PITCHED : SHAPE_FLAT;
        if (shape == SHAPE_PITCHED) {
            // two slopes either side of the ridge, one in the sun
            boolean wide = rw >= rh;
            g.color(MapPainter.shade(roof, 1.08f));
            if (wide) g.fillRect(x, y, rw, rh / 2 + 0.5f);
            else g.fillRect(x, y, rw / 2 + 0.5f, rh);
            g.color(MapPainter.shade(roof, 0.82f));
            if (wide) g.fillRect(x, y + rh / 2, rw, rh / 2);
            else g.fillRect(x + rw / 2, y, rw / 2, rh);
            if (detail) {
                g.color(MapPainter.shade(roof, 0.66f));
                float lw = Math.max(1, z * 1.4f);
                if (wide) g.line(x + 1, y + rh / 2, x + rw - 1, y + rh / 2, lw);
                else g.line(x + rw / 2, y + 1, x + rw / 2, y + rh - 1, lw);
                if (kind == Region.B_HOUSE && (b & 3) != 0 && rw > 8) {
                    // a chimney
                    g.color(0xFF5A4A44);
                    float cs = Math.max(2, 3 * z);
                    g.fillRect(x + rw * 0.72f, y + rh * 0.2f, cs, cs);
                } else if (kind == Region.B_BARN) {
                    g.color(0x60E8E0D0);
                    g.line(x, y, x + rw, y + rh, Math.max(1, z));
                    g.line(x + rw, y, x, y + rh, Math.max(1, z));
                } else if (kind == Region.B_CHURCH) {
                    g.color(0xFFE8E0D0);
                    float cx = x + rw / 2, cy = y + rh / 2, s = Math.min(rw, rh) * 0.3f;
                    g.line(cx - s * 0.5f, cy - s * 0.2f, cx + s * 0.5f, cy - s * 0.2f, Math.max(1, z * 1.6f));
                    g.line(cx, cy - s * 0.8f, cx, cy + s * 0.8f, Math.max(1, z * 1.6f));
                }
            }
        } else {
            // a flat roof: the deck a shade darker inside a parapet, light catching the top-left edge
            g.color(roof);
            g.fillRect(x, y, rw, rh);
            float p = Math.max(1, Math.min(2.2f * z, rw * 0.12f));
            g.color(MapPainter.shade(roof, 0.9f));
            g.fillRect(x + p, y + p, rw - p * 2, rh - p * 2);
            g.color(0x18FFFFFF);
            g.fillRect(x, y, rw, p);
            g.fillRect(x, y, p, rh);
            if (detail && rw > 10 && rh > 10) rooftop(g, b, kind, x + p, y + p, rw - p * 2, rh - p * 2, z, roof);
        }
        g.color(0x50000000);
        g.strokeRect(x, y, rw, rh, 1);
    }

    /** What stands on a flat roof: units, tanks, panels, a helipad, a sign. */
    private void rooftop(Gfx g, int b, int kind, float x, float y, float rw, float rh, float z, int roof) {
        float u = Math.max(1.5f, 5 * z);
        switch (kind) {
            case Region.B_HOSPITAL: {
                float cx = x + rw / 2, cy = y + rh / 2, s = Math.min(rw, rh) * 0.34f;
                g.color(0xFFD02828);
                g.fillRect(cx - s, cy - s * 0.3f, s * 2, s * 0.6f);
                g.fillRect(cx - s * 0.3f, cy - s, s * 0.6f, s * 2);
                if (rw > 50 * z) {
                    float hx = x + rw * 0.18f, hy = y + rh * 0.25f, hr = Math.min(rw, rh) * 0.14f;
                    g.color(0xFF3A3F44);
                    g.fillCircle(hx, hy, hr);
                    g.color(0xFFE8E8E0);
                    g.strokeCircle(hx, hy, hr * 0.8f, Math.max(1, z));
                    g.text("H", hx, hy + hr * 0.4f, hr * 1.1f, Gfx.ALIGN_CENTER, true);
                }
                break;
            }
            case Region.B_POLICE:
                g.color(0xFF2B4E9A);
                g.fillRect(x, y + rh * 0.4f, rw, rh * 0.2f);
                if (rw > 26 * z) {
                    g.color(0xFFF0F0F0);
                    float ts = Math.min(rh * 0.18f, rw / 5.2f);
                    g.text("POLICE", x + rw / 2, y + rh / 2 + ts * 0.36f, ts, Gfx.ALIGN_CENTER, true);
                }
                break;
            case Region.B_FIRE:
                g.color(0xFFF0E8D0);
                g.fillRect(x, y + rh * 0.45f, rw, rh * 0.1f);
                break;
            case Region.B_SCHOOL:
            case Region.B_HALL:
            case Region.B_GOV: {
                if (kind == Region.B_GOV) {
                    g.color(MapPainter.shade(roof, 1.15f));
                    g.fillCircle(x + rw / 2, y + rh / 2, Math.min(rw, rh) * 0.3f);
                    g.color(MapPainter.shade(roof, 0.8f));
                    g.strokeCircle(x + rw / 2, y + rh / 2, Math.min(rw, rh) * 0.3f, Math.max(1, z));
                    break;
                }
                // solar panels
                g.color(0xFF243A5E);
                int cols = Math.max(1, (int) (rw / (10 * z + 1)));
                for (int i = 0; i < cols && i < 8; i++) g.fillRect(x + rw * 0.1f + i * rw * 0.8f / cols, y + rh * 0.15f, rw * 0.7f / cols, rh * 0.3f);
                break;
            }
            case Region.B_WAREHOUSE:
            case Region.B_DEPOT:
            case Region.B_HANGAR:
            case Region.B_DOCK:
            case Region.B_FOODW: {
                // corrugated sheets and skylights
                g.color(MapPainter.shade(roof, 0.82f));
                boolean wide = rw >= rh;
                int lines = (int) ((wide ? rw : rh) / Math.max(2.5f, 4 * z));
                for (int i = 1; i < lines && i < 60; i++) {
                    if (wide) g.line(x + i * rw / lines, y, x + i * rw / lines, y + rh, 1);
                    else g.line(x, y + i * rh / lines, x + rw, y + i * rh / lines, 1);
                }
                g.color(0x50C8DCE8);
                for (int i = 0; i < 3; i++) {
                    if (wide) g.fillRect(x + rw * (0.2f + i * 0.25f), y + rh * 0.3f, u, rh * 0.4f);
                    else g.fillRect(x + rw * 0.3f, y + rh * (0.2f + i * 0.25f), rw * 0.4f, u);
                }
                break;
            }
            case Region.B_WATER:
            case Region.B_POWER: {
                g.color(MapPainter.shade(roof, 1.25f));
                float s = Math.min(rw, rh) * 0.28f;
                g.fillCircle(x + rw * 0.33f, y + rh / 2, s);
                g.fillCircle(x + rw * 0.7f, y + rh / 2, s * 0.8f);
                g.color(0x60000000);
                g.strokeCircle(x + rw * 0.33f, y + rh / 2, s, 1);
                break;
            }
            case Region.B_APARTMENT:
            case Region.B_CELLBLOCK:
            case Region.B_BARRACKS:
            case Region.B_TOWER: {
                // a stair hut, a water tank, air-conditioning units
                g.color(MapPainter.shade(roof, 1.12f));
                g.fillRect(x + rw * 0.6f, y + rh * 0.55f, Math.min(rw * 0.25f, 12 * z), Math.min(rh * 0.25f, 9 * z));
                if ((b & 1) == 0 || kind == Region.B_TOWER) {
                    g.color(0xFF6A6058);
                    g.fillCircle(x + rw * 0.25f, y + rh * 0.3f, Math.max(1.5f, Math.min(rw, rh) * 0.12f));
                }
                acUnits(g, b, x, y, rw, rh, u);
                break;
            }
            default:
                acUnits(g, b, x, y, rw, rh, u);
        }
    }

    private void acUnits(Gfx g, int b, float x, float y, float rw, float rh, float u) {
        if (rw < u * 4 || rh < u * 3) return;
        int n = 1 + (b % 3);
        for (int i = 0; i < n; i++) {
            float px = x + rw * (0.15f + 0.27f * i), py = y + rh * (0.2f + 0.22f * ((b + i) % 3));
            g.color(0xFFB8B8B4);
            g.fillRect(px, py, u * 1.2f, u);
            g.color(0xFF707070);
            g.fillCircle(px + u * 0.6f, py + u * 0.5f, u * 0.32f);
        }
    }

    /**
     * Lit windows on a national map: the buildings drawn this frame (homes, hospitals and police stations), from the
     * drawn arrays, so drawing never reads a chunk the simulation hasn't got.
     */
    private void nationLights(Gfx g, Camera cam, float night) {
        float z = cam.zoom;
        float ccx = cam.cx, ccy = cam.cy;
        float lk = lean3d ? 0.0016f * clamp01((z - 0.22f) / 0.12f) : 0;
        int warm = MapPainter.alpha(0xFFFFD58A, 0.25f + night * 0.65f);
        for (int k = 0; k < visN; k++) {
            int kind = dbk[k], b = dbid[k];
            // (homes, and the hospital and police that never close: content.ini's [building] home and lit)
            if (!Data.lit(kind)) continue;
            float wx = dbx[k] * 16f, wy = dby[k] * 16f, ww = dbw[k] * 16f, wh = dbh[k] * 16f;
            if (scorchedIds.contains(b) || !w.strat.lit(w.r.sectorOfPx(wx + ww / 2, wy + wh / 2))) continue;
            float height = Math.min(40, dbf[k]) * 3.2f + 4;
            float lx = (wx + ww / 2 - ccx) * lk * height * z, ly = (wy + wh / 2 - ccy) * lk * height * z;
            float ll = (float) Math.sqrt(lx * lx + ly * ly);
            if (ll > 120 * z) {
                lx *= 120 * z / ll;
                ly *= 120 * z / ll;
            }
            float sx = cam.toScreenX(wx) + lx, sy = cam.toScreenY(wy) + ly, sw = ww * z, sh = wh * z;
            g.color(warm);
            int nx = Math.min(12, Math.max(1, (int) (sw / Math.max(3, 11 * z)))), ny = Math.min(12, Math.max(1, (int) (sh / Math.max(3, 11 * z))));
            float s = Math.max(1.2f, 2.6f * z);
            for (int j = 0; j < ny; j++)
                for (int i = 0; i < nx; i++) {
                    if (Rng.unit(w.r.seed, b * 31 + i, j) < 0.6f) continue;
                    g.fillRect(sx + (i + 0.35f) * sw / nx, sy + (j + 0.35f) * sh / ny, s, s);
                }
        }
    }

    /** Lit windows in powered places at night (drawn after the dark). */
    private void lights(Gfx g, Camera cam, float night) {
        if (np != null) {
            nationLights(g, cam, night);
            return;
        }
        Region r = w.r;
        float z = cam.zoom;
        float ccx = cam.cx, ccy = cam.cy;
        float lk = lean3d ? 0.0016f : 0;
        int n = visN;
        int warm = MapPainter.alpha(0xFFFFD58A, 0.25f + night * 0.65f);
        for (int k = 0; k < n; k++) {
            int b = vis[k];
            if (r.bres(b) <= 0 && r.bkind(b) != Region.B_HOSPITAL && r.bkind(b) != Region.B_POLICE) continue;
            float wx = r.bx(b) * 16f, wy = r.by(b) * 16f, ww = r.bw(b) * 16f, wh = r.bh(b) * 16f;
            if (scorched[b] || !w.strat.lit(r.sectorOfPx(wx + ww / 2, wy + wh / 2))) continue;
            float height = Math.min(40, r.bfloors(b)) * 3.2f + 4;
            float lx = (wx + ww / 2 - ccx) * lk * height * z, ly = (wy + wh / 2 - ccy) * lk * height * z;
            float ll = (float) Math.sqrt(lx * lx + ly * ly);
            if (ll > 120 * z) {
                lx *= 120 * z / ll;
                ly *= 120 * z / ll;
            }
            float sx = cam.toScreenX(wx) + lx, sy = cam.toScreenY(wy) + ly, sw = ww * z, sh = wh * z;
            g.color(warm);
            int nx = Math.max(1, (int) (sw / Math.max(3, 11 * z))), ny = Math.max(1, (int) (sh / Math.max(3, 11 * z)));
            nx = Math.min(nx, 12);
            ny = Math.min(ny, 12);
            float s = Math.max(1.2f, 2.6f * z);
            for (int j = 0; j < ny; j++)
                for (int i = 0; i < nx; i++) {
                    if (Rng.unit(r.seed, b * 31 + i, j) < 0.6f) continue;
                    g.fillRect(sx + (i + 0.35f) * sw / nx, sy + (j + 0.35f) * sh / ny, s, s);
                }
        }
    }

    // ------------------------------------------------------------------ night

    private void nightShade(Gfx g, Camera cam) {
        float d = w.daylight();
        float z = cam.zoom;
        if (d < 0.99f) {
            float night = 1 - d;
            // dusk and dawn warm first, then a deep blue dark
            if (d > 0.4f) {
                g.color(MapPainter.alpha(0xFFFF8A40, (1 - d) * 0.25f));
                g.fillRect(0, 0, g.width(), g.height());
            }
            g.color(MapPainter.alpha(0xFF060C22, night * 0.74f));
            g.fillRect(0, 0, g.width(), g.height());
            // places with no power go black
            if (d < 0.7f) dark.draw(g, cam, (0.7f - d) / 0.7f * 0.8f);
            // the lights: street lamps, windows, and whole towns from high up
            if (z >= 0.3f) {
                streetLights(g, cam, night);
                if (z >= 0.22f) lights(g, cam, night);
            } else if (np != null) {
                nationNightLights(g, cam, night);
            } else {
                for (Region.Settlement s : w.r.settlements) {
                    if (s.kind == Region.S_HOLDOUT || s.kind == Region.S_CAMP) continue;
                    if (s.grid >= 0 && !w.strat.gridOn[s.grid]) continue;
                    float x = cam.toScreenX(s.cx * 16f), y = cam.toScreenY(s.cy * 16f);
                    float rad = Math.max(s.radius * 16f * z * 2.6f, 10 * dp);
                    if (x < -rad || y < -rad || x > cam.sw + rad || y > cam.sh + rad) continue;
                    glow(g, glowWarm, x, y, rad * 2, night * 0.7f);
                }
            }
        }
    }

    /**
     * A national map by night from high up: each lived-in, powered sector glows by how many live there (the city as
     * a carpet of light, dark where the power is gone); from the whole country, each town as one glow its own size.
     */
    private void nationNightLights(Gfx g, Camera cam, float night) {
        float z = cam.zoom;
        Sectors sec = w.sec;
        float cs = w.r.sect * Tile.SIZE;
        if (z >= 0.004f) {
            int x0 = Math.max(0, (int) (cam.x0() / cs) - 1), y0 = Math.max(0, (int) (cam.y0() / cs) - 1);
            int x1 = Math.min(sec.sw - 1, (int) (cam.x1() / cs) + 1), y1 = Math.min(sec.sh - 1, (int) (cam.y1() / cs) + 1);
            int step = (x1 - x0 + 1) * (y1 - y0 + 1) > 6000 ? 2 : 1;
            float size = cs * z * 2.6f * step;
            for (int sy = y0; sy <= y1; sy += step)
                for (int sx = x0; sx <= x1; sx += step) {
                    int s = sy * sec.sw + sx;
                    int home = sec.home[s];
                    if (home < 60 || !w.strat.lit(s)) continue;
                    float a = night * Math.min(0.4f, 0.07f + home / 14000f);
                    glow(g, glowWarm, cam.toScreenX((sx + 0.5f) * cs), cam.toScreenY((sy + 0.5f) * cs), size, a);
                }
            return;
        }
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind == Region.S_HOLDOUT || s.kind == Region.S_CAMP) continue;
            if (s.grid >= 0 && !w.strat.gridOn[s.grid]) continue;
            float x = cam.toScreenX(s.cx * 16f), y = cam.toScreenY(s.cy * 16f);
            float rad = Math.max(s.radius * 16f * z * 1.1f, 3 * dp);
            if (x < -rad || y < -rad || x > cam.sw + rad || y > cam.sh + rad) continue;
            glow(g, glowWarm, x, y, rad * 2, night * 0.55f);
        }
    }

    /** Dry ground at (x, y) px: a national map's coarse cover (drawing never makes a chunk), else the tile. */
    private boolean dryAt(float x, float y) {
        if (w.r.nation != null) return !Nation.wet(w.r.nation.coverAt((int) (x / Tile.SIZE), (int) (y / Tile.SIZE)));
        return Tile.walkable(w.r.tileAtPx(x, y));
    }

    private void streetLights(Gfx g, Camera cam, float night) {
        RoadGraph rg = w.r.roads;
        float z = cam.zoom;
        float size = Math.max(70 * z, 10 * dp);
        float x0 = cam.x0() - 60, y0 = cam.y0() - 60, x1 = cam.x1() + 60, y1 = cam.y1() + 60;
        int drawn = 0;
        if (np != null) {
            // a national map: the junctions of the chunks in view (a country has too many to look through)
            int[] byChunk = rg.nodeChunks(ActiveSet.PX);
            int cx0 = Math.max(0, (int) (x0 / ActiveSet.PX)), cy0 = Math.max(0, (int) (y0 / ActiveSet.PX));
            int cx1 = (int) (x1 / ActiveSet.PX), cy1 = (int) (y1 / ActiveSet.PX);
            for (int cy = cy0; cy <= cy1; cy++)
                for (int cx = cx0; cx <= cx1; cx++)
                    for (int k = rg.nodeChunkFrom(cx, cy); k < rg.nodeChunkTo(cx, cy) && drawn < 400; k++) {
                        int i = byChunk[k];
                        float x = rg.nx[i], y = rg.ny[i];
                        if (x < x0 || x > x1 || y < y0 || y > y1) continue;
                        if (rg.nset[i] < 0 || !w.strat.lit(w.r.sectorOfPx(x, y))) continue;
                        glow(g, glowWarm, cam.toScreenX(x), cam.toScreenY(y), size, night * 0.55f);
                        drawn++;
                    }
            return;
        }
        for (int i = 0; i < rg.nodeCount && drawn < 400; i++) {
            float x = rg.nx[i], y = rg.ny[i];
            if (x < x0 || x > x1 || y < y0 || y > y1) continue;
            if (rg.nset[i] < 0 || !w.strat.lit(w.r.sectorOfPx(x, y))) continue;
            glow(g, glowWarm, cam.toScreenX(x), cam.toScreenY(y), size, night * 0.55f);
            drawn++;
        }
    }

    // ------------------------------------------------------------------ people

    private void people(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        Ents e = w.e;
        float z = cam.zoom;
        if (z < 0.6f) {
            // dots, batched by colour: the dead first and lighter (filled, a dark rim only from fairly close, so a
            // big crowd is a mass rather than a heap of rings), the living over them, police and soldiers on top
            // with a pale outline so they don't vanish among the dead
            float size = Math.max(2.4f * dp, Math.min(4.2f * dp, 8 * z));
            dots(g, cam, x0, y0, x1, y1, Ents.ZOMBIE, z > 0.4f ? C_DEAD : C_DEAD_FAR, z > 0.4f ? DEAD_RIM : 0, size * 0.9f);
            dots(g, cam, x0, y0, x1, y1, Ents.CIV, C_CIV, LIVING_LINE, size);
            dots(g, cam, x0, y0, x1, y1, Ents.COP, C_COP, 0xFFF2F4F8, size * 1.3f);
            dots(g, cam, x0, y0, x1, y1, Ents.SOLDIER, C_ARMY, 0xFFF2F4F8, size * 1.3f);
            if (w.playerEnt >= 0 && e.loc[w.playerEnt] == Ents.FREE) {
                g.color(0xFFFFE040);
                g.strokeCircle(cam.toScreenX(e.x[w.playerEnt]), cam.toScreenY(e.y[w.playerEnt]), 9 * dp, 2 * dp);
            }
            if (selected != null && selected.alive) {
                g.color(0xFFFFE040);
                for (int k = 0; k < selected.n; k++) {
                    int m = selected.mem[k];
                    if (e.loc[m] == Ents.FREE) g.strokeCircle(cam.toScreenX(e.x[m]), cam.toScreenY(e.y[m]), size, 1.2f * dp);
                }
            }
            return;
        }
        // close: each one drawn
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < e.top; i++) {
                if (e.loc[i] != Ents.FREE) continue;
                boolean dead = e.kind[i] == Ents.ZOMBIE;
                if ((pass == 0) != dead) continue;
                float x = e.x[i], y = e.y[i];
                if (x < x0 || x > x1 || y < y0 || y > y1) continue;
                person(g, cam, i);
            }
        if (selected != null && selected.alive) {
            g.color(0xFFFFE040);
            for (int k = 0; k < selected.n; k++) {
                int m = selected.mem[k];
                if (e.loc[m] == Ents.FREE) g.strokeCircle(cam.toScreenX(e.x[m]), cam.toScreenY(e.y[m]), Math.max(9 * z, 6 * dp), 1.5f * dp);
            }
        }
    }

    private void dots(Gfx g, Camera cam, float x0, float y0, float x1, float y1, byte kind, int color, int rim, float size) {
        Ents e = w.e;
        int n = 0;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.FREE || e.kind[i] != kind) continue;
            float x = e.x[i], y = e.y[i];
            if (x < x0 || x > x1 || y < y0 || y > y1) continue;
            if (n * 2 + 2 > pts.length) pts = java.util.Arrays.copyOf(pts, pts.length * 2);
            pts[n * 2] = cam.toScreenX(x);
            pts[n * 2 + 1] = cam.toScreenY(y);
            n++;
        }
        if (n == 0) return;
        if (rim != 0) {
            g.color(rim);
            g.points(pts, n, size + Math.max(1.5f, 1.1f * dp));
        }
        g.color(color);
        g.points(pts, n, size);
    }

    private void person(Gfx g, Camera cam, int i) {
        Ents e = w.e;
        float z = cam.zoom;
        float sx = cam.toScreenX(e.x[i]), sy = cam.toScreenY(e.y[i]);
        float rad;
        int body, head;
        int kind = e.kind[i];
        boolean dead = kind == Ents.ZOMBIE;
        if (dead) {
            Content.ZType t = w.ztypeOf(i);
            rad = t.radius;
            body = t.color;
            head = MapPainter.shade(t.color, 0.82f);
        } else {
            Content.Unit u = w.unitOf(i);
            rad = u.radius;
            body = u.color;
            head = kind == Ents.CIV ? 0xFFE6C29C : MapPainter.shade(u.color, 0.7f);
            if (kind == Ents.CIV && u.weapon == null) body = civColor(e.uid[i]);
        }
        if (colourBlind) body = dead ? C_DEAD : kind == Ents.COP ? C_COP : kind == Ents.SOLDIER ? C_ARMY : body;
        float a = e.face[i];
        float fx = (float) Math.cos(a), fy = (float) Math.sin(a);
        float r = Math.max(rad * z, rad / 4 * 1.9f * dp);
        if (dead) {
            // the dead lurch
            float wob = (float) Math.sin(time * 5 + i * 1.3f) * r * 0.18f;
            sx += -fy * wob;
            sy += fx * wob;
        }
        float ol = Math.max(1, 0.8f * dp);
        // shadow, outline, shoulders, head, and for the armed, a weapon
        g.color(0x48000000);
        g.fillCircle(sx + r * 0.35f, sy + r * 0.45f, r * 1.15f);
        if (dead) {
            // arms out in front, under the body
            g.color(DEAD_RIM);
            float aw = Math.max(1.5f, r * 0.55f);
            g.line(sx - fy * r * 0.7f, sy + fx * r * 0.7f, sx - fy * r * 0.7f + fx * r * 1.7f, sy + fx * r * 0.7f + fy * r * 1.7f, aw + ol);
            g.line(sx + fy * r * 0.7f, sy - fx * r * 0.7f, sx + fy * r * 0.7f + fx * r * 1.7f, sy - fx * r * 0.7f + fy * r * 1.7f, aw + ol);
            g.color(body);
            g.line(sx - fy * r * 0.7f, sy + fx * r * 0.7f, sx - fy * r * 0.7f + fx * r * 1.6f, sy + fx * r * 0.7f + fy * r * 1.6f, aw * 0.6f);
            g.line(sx + fy * r * 0.7f, sy - fx * r * 0.7f, sx + fy * r * 0.7f + fx * r * 1.6f, sy - fx * r * 0.7f + fy * r * 1.6f, aw * 0.6f);
        }
        g.color(dead ? DEAD_RIM : LIVING_LINE);
        g.fillCircle(sx, sy, r * 1.15f + ol);
        g.color(body);
        g.fillCircle(sx, sy, r * 1.15f);
        if (kind == Ents.COP || kind == Ents.SOLDIER || (kind == Ents.CIV && w.unitOf(i).weapon != null)) {
            g.color(0xFF141414);
            g.line(sx + fx * r * 0.3f - fy * r * 0.5f, sy + fy * r * 0.3f + fx * r * 0.5f, sx + fx * r * 2.2f - fy * r * 0.5f, sy + fy * r * 2.2f + fx * r * 0.5f, Math.max(1.2f, r * 0.38f));
        }
        g.color(head);
        g.fillCircle(sx + fx * r * 0.15f, sy + fy * r * 0.15f, r * 0.6f);
        if (dead) {
            if ((e.flags[i] & Ents.F_HELMET) != 0) {
                g.color(0xFF3A3F44);
                g.fillCircle(sx + fx * r * 0.15f, sy + fy * r * 0.15f, r * 0.5f);
            }
            // a smear of blood on the face
            g.color(0xC0901818);
            g.fillCircle(sx + fx * r * 0.45f, sy + fy * r * 0.45f, r * 0.25f);
        } else if (kind == Ents.COP) {
            g.color(0xFF1A2A50);
            g.fillCircle(sx + fx * r * 0.15f, sy + fy * r * 0.15f, r * 0.42f);
        } else if (kind == Ents.SOLDIER) {
            g.color(MapPainter.shade(body, 0.75f));
            g.fillCircle(sx + fx * r * 0.15f, sy + fy * r * 0.15f, r * 0.5f);
        }
        if (i == w.playerEnt) {
            g.color(0xFFFFE040);
            g.strokeCircle(sx, sy, r * 2.4f, 2 * dp);
        }
        if (healthBars && z > 0.9f && e.hp[i] < e.maxHp[i] && !dead) {
            float f = Math.max(0, e.hp[i] / e.maxHp[i]);
            float bw = Math.max(12 * z, 10 * dp), bh = Math.max(2 * z, 2 * dp);
            g.color(0xC0000000);
            g.fillRect(sx - bw / 2 - 1, sy - r * 1.4f - bh * 2 - 1, bw + 2, bh + 2);
            g.color(f > 0.5f ? 0xFF60D060 : f > 0.25f ? 0xFFE0C040 : 0xFFE04040);
            g.fillRect(sx - bw / 2, sy - r * 1.4f - bh * 2, bw * f, bh);
        }
        if ((e.flags[i] & Ents.F_INFECTED) != 0 && z > 0.8f) {
            g.color(0xC0D04848);
            g.strokeCircle(sx, sy, r * 1.7f, Math.max(1, 0.8f * dp));
        }
        if (i == inspectEnt) {
            g.color(0xFFFFFFFF);
            g.strokeCircle(sx, sy, r * 2.6f, 1.5f * dp);
        }
        // a marksman on a perch: a small roof-chevron over them
        if (!dead && kind != Ents.CIV && w.aai.has(i) && (w.aai.bits[i] & ArmedAI.B_PERCHED) != 0) {
            float cy = sy - r * 2.4f, cw = Math.max(5 * dp, r * 1.2f);
            g.color(0xFF0C0C0C);
            g.line(sx - cw, cy + cw * 0.55f, sx, cy, 3.2f * dp);
            g.line(sx, cy, sx + cw, cy + cw * 0.55f, 3.2f * dp);
            g.color(0xFFFFE070);
            g.line(sx - cw, cy + cw * 0.55f, sx, cy, 1.6f * dp);
            g.line(sx, cy, sx + cw, cy + cw * 0.55f, 1.6f * dp);
        }
    }

    /** Ordinary clothes: warm, muted. */
    private static final int[] CIV = {0xFFE8D2A8, 0xFFD9B38C, 0xFFC98A5E, 0xFFE6C27A, 0xFFB7795A, 0xFFF0E0C0, 0xFFCFA070, 0xFFDDA88A, 0xFFA87A5E, 0xFFE0B890};

    private static int civColor(int uid) {
        return CIV[(uid * 7) % CIV.length];
    }

    // ------------------------------------------------------------------ vehicles

    /** The colours of the city's cars: mostly greys, whites and blacks, some dark blues and reds, a few bright ones. */
    private static final int[] CAR_COLORS = {0xFFE8E8E6, 0xFFB8BCC2, 0xFF2B2D31, 0xFF8C9096, 0xFFF4F4F2, 0xFF1F3A5F, 0xFF7A1E22,
            0xFF4A4E54, 0xFFD9D4C7, 0xFF2E5E3A, 0xFFC23B22, 0xFF3D6FA8, 0xFFE0B13A, 0xFF5B3A29, 0xFF16181B, 0xFFA7ADB3};

    private void vehicles(Gfx g, Camera cam, float x0, float y0, float x1, float y1, boolean air) {
        Vehicles v = w.veh;
        float z = cam.zoom;
        boolean night = w.daylight() < 0.6f;
        for (int i = 0; i < v.top; i++) {
            if (v.state[i] == Vehicles.V_NONE) continue;
            Content.VType t = v.type(i);
            boolean inAir = t.flies && v.alt[i] > 0.05f;
            if (inAir != air) continue;
            float x = v.x[i], y = v.y[i];
            if (x < x0 - 200 || x > x1 + 200 || y < y0 - 200 || y > y1 + 200) continue;
            float sx = cam.toScreenX(x), sy = cam.toScreenY(y);
            // never smaller than a few dp: a car reads as a car
            float k = Math.max(z, 9 * dp / Math.max(10, t.length));
            float L = t.length * k, W = t.width * k;
            boolean wreck = v.state[i] == Vehicles.V_WRECK;
            int col = wreck ? 0xFF2A2624 : t.color;
            // the city's own cars: each its own colour
            if (!wreck && v.role[i] == Vehicles.R_LIFE && "car".equals(t.id)) col = CAR_COLORS[(v.uid[i] * 7 + (v.uid[i] >> 3)) % CAR_COLORS.length];
            float lift = inAir ? v.alt[i] * 26 * k : 0;
            if (inAir) {
                // shadow on the ground, the aircraft above it
                g.save();
                g.translate(sx + lift, sy + lift * 1.3f);
                g.rotate(v.head[i]);
                g.color(0x50000000);
                g.fillRect(-L / 2, -W / 4, L, W / 2);
                g.restore();
            }
            if (night && !wreck && !inAir && v.state[i] == Vehicles.V_DRIVE) {
                // headlights on the road ahead
                float hx = (float) Math.cos(v.head[i]), hy = (float) Math.sin(v.head[i]);
                glow(g, glowWarm, sx + hx * L * 1.3f, sy + hy * L * 1.3f, L * 1.9f, 0.55f);
            }
            g.save();
            g.translate(sx, sy);
            g.rotate(v.head[i]);
            if (!inAir) {
                g.color(0x50000000);
                g.fillRoundRect(-L / 2 + 2 * k, -W / 2 + 3 * k, L, W, 3 * k);
            }
            if (t.flies && t.weapon != null && t.speed > 400) {
                // a jet: a dart
                poly[0] = L / 2; poly[1] = 0; poly[2] = -L / 2; poly[3] = -W / 2; poly[4] = -L / 3; poly[5] = 0; poly[6] = -L / 2; poly[7] = W / 2;
                g.color(col);
                g.fillPoly(poly, 4);
            } else if (t.flies) {
                g.color(col);
                g.fillRoundRect(-L / 2.4f, -W / 5, L / 1.3f, W / 2.5f, W / 5);
                g.fillRect(-L / 2, -1.5f * k, L / 2, 3 * k);
                // rotor
                g.color(0x90202020);
                float ra = time * 30;
                for (int q = 0; q < 2; q++) {
                    float ax = (float) Math.cos(ra + q * 1.57f) * W * 0.6f, ay = (float) Math.sin(ra + q * 1.57f) * W * 0.6f;
                    g.line(-ax, -ay, ax, ay, 1.5f * k);
                }
            } else {
                g.color(0xFF141414);
                g.fillRoundRect(-L / 2 - 0.8f * dp * 0.5f, -W / 2 - 0.4f * dp, L + 0.8f * dp, W + 0.8f * dp, 3 * k);
                g.color(col);
                g.fillRoundRect(-L / 2, -W / 2, L, W, 3 * k);
                g.color(MapPainter.shade(col, 0.72f));
                g.fillRect(-L * 0.12f, -W / 2 + 1.5f * k, L * 0.38f, W - 3 * k); // cab / roof
                g.color(0x60B0C8E0);
                g.fillRect(L * 0.18f, -W / 2 + 1.8f * k, L * 0.1f, W - 3.6f * k); // windscreen
                if (t.stripe != 0 && !wreck) {
                    g.color(t.stripe);
                    g.fillRect(-L / 2, -1.2f * k, L, 2.4f * k);
                }
                if (t.weapon != null && !wreck) {
                    // turret and barrel
                    g.color(MapPainter.shade(col, 0.7f));
                    g.fillCircle(0, 0, W * 0.32f);
                    g.color(0xFF222222);
                    g.line(0, 0, L * (t.tracked ? 0.75f : 0.45f), 0, (t.tracked ? 2.4f : 1.4f) * k);
                }
                if (v.siren[i] && !wreck) {
                    boolean on = ((int) (time * 6) & 1) == 0;
                    g.color(on ? 0xFFFF3030 : 0xFF3050FF);
                    g.fillRect(-2 * k, -W / 2 + 1, 3 * k, W / 2 - 1);
                    g.color(on ? 0xFF3050FF : 0xFFFF3030);
                    g.fillRect(-2 * k, 0, 3 * k, W / 2 - 1);
                }
                if (wreck) {
                    g.color(0x80000000);
                    g.fillCircle(L * 0.1f, 0, W * 0.35f);
                }
            }
            g.restore();
            if (v.siren[i] && !wreck) {
                boolean on = ((int) (time * 6) & 1) == 0;
                glow(g, on ? glowRed : glowBlue, sx, sy, Math.max(L * 2.2f, 22 * dp), night ? 0.9f : 0.5f);
            }
            if (i == inspectVehicle) {
                g.color(0xFFFFFFFF);
                g.strokeCircle(sx, sy, L * 0.85f, 1.5f * dp);
            }
            if (!wreck && z > 0.5f && v.hp[i] < t.hp * 0.5f) {
                float ph = (time * 0.8f + i * 0.37f) % 1f;
                glow(g, smoke, sx + ph * 10 * k, sy - ph * 30 * k, (8 + ph * 18) * k, (1 - ph) * 0.8f);
            }
        }
    }

    private void vehicleDots(Gfx g, Camera cam) {
        Vehicles v = w.veh;
        float z = cam.zoom;
        float s = Math.max(4 * dp, Math.min(7 * dp, 30 * z));
        float x0 = -s, y0 = -s, x1 = cam.sw + s, y1 = cam.sh + s;
        for (int i = 0; i < v.top; i++) {
            if (v.state[i] == Vehicles.V_NONE || v.state[i] == Vehicles.V_PARKED || v.role[i] == Vehicles.R_LIFE) continue;
            Content.VType t = v.type(i);
            float sx = cam.toScreenX(v.x[i]), sy = cam.toScreenY(v.y[i]);
            if (sx < x0 || sy < y0 || sx > x1 || sy > y1) continue;
            boolean wreck = v.state[i] == Vehicles.V_WRECK;
            if (wreck && z < 0.12f) continue;
            int c = wreck ? 0xFF303030 : t.faction.equals("police") ? 0xFF7FA8FF : t.faction.equals("army") ? 0xFFD8CC88 : 0xFFE3B21F;
            if (v.siren[i] && !wreck) glow(g, ((int) (time * 6) & 1) == 0 ? glowRed : glowBlue, sx, sy, s * 3.5f, 0.7f);
            g.color(0xFF101010);
            g.fillRect(sx - s / 2 - 1, sy - s / 2 - 1, s + 2, s + 2);
            g.color(c);
            g.fillRect(sx - s / 2, sy - s / 2, s, s);
        }
    }

    // ------------------------------------------------------------------ effects

    private void effects(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        Effects fx = w.fx;
        float z = cam.zoom;
        for (int k = 0; k < fx.count; k++) {
            int i = (fx.head - 1 - k + Effects.MAX) % Effects.MAX;
            if (fx.age[i] >= fx.life[i]) continue;
            byte fk = fx.kind[i];
            if (effects == 0 && (fk == Effects.SMOKE && (i & 1) == 1 || fk == Effects.SPARK || fk == Effects.FLASH && (i & 1) == 1)) continue;
            float x = fx.fx[i], y = fx.fy[i];
            if (x < x0 - 300 || x > x1 + 300 || y < y0 - 300 || y > y1 + 300) continue;
            float t = fx.age[i] / fx.life[i];
            float sx = cam.toScreenX(x), sy = cam.toScreenY(y);
            switch (fx.kind[i]) {
                case Effects.TRACER:
                    if (z < (effects == 2 ? 0.12f : 0.2f)) break;
                    g.color(((int) ((1 - t) * 230) << 24) | 0xFFE890);
                    g.line(sx, sy, cam.toScreenX(fx.fx2[i]), cam.toScreenY(fx.fy2[i]), Math.max(0.8f * dp, 1.2f * z));
                    break;
                case Effects.FLASH: {
                    if (z < (effects == 2 ? 0.2f : 0.3f)) break;
                    // a muzzle star
                    float a = fx.fx2[i];
                    float mx = sx + (float) Math.cos(a) * Math.max(7 * z, 3 * dp), my = sy + (float) Math.sin(a) * Math.max(7 * z, 3 * dp);
                    float s = Math.max(4 * dp, 6 * z) * (1 - t * 0.5f);
                    glow(g, glowWarm, mx, my, s * 3, 0.8f);
                    g.color(0xF0FFF0B0);
                    g.line(mx - s * 0.5f, my, mx + s * 0.5f, my, Math.max(1, 0.7f * dp));
                    g.line(mx, my - s * 0.5f, mx, my + s * 0.5f, Math.max(1, 0.7f * dp));
                    g.fillCircle(mx, my, s * 0.22f);
                    break;
                }
                case Effects.BLAST:
                case Effects.BIGBLAST: {
                    float rad = Math.max(fx.fx2[i] * z * (0.4f + t * 0.8f), 5 * dp);
                    glow(g, glowFire, sx, sy, rad * 3.2f, 1 - t);
                    g.color(((int) ((1 - t) * 200) << 24) | 0xFF8A20);
                    g.fillCircle(sx, sy, rad);
                    g.color(((int) ((1 - t) * 240) << 24) | 0xFFE8A0);
                    g.fillCircle(sx, sy, rad * 0.5f);
                    break;
                }
                case Effects.SMOKE: {
                    float rad = Math.max(fx.fx2[i] * z * (0.3f + t * 0.9f), 3 * dp);
                    glow(g, smoke, sx + t * 30 * z, sy - t * 50 * z, rad * 2.4f, (1 - t) * 0.75f);
                    break;
                }
                case Effects.SPARK:
                    g.color(0xFFFFFFC0);
                    g.fillCircle(sx, sy, Math.max(2.5f * z, 1.2f * dp));
                    break;
                default:
            }
        }
    }

    // ------------------------------------------------------------------ the map overlays

    private void overlays(Gfx g, Camera cam) {
        float z = cam.zoom;
        // the interface's own areas are taken before any label
        for (int i = 0; i < keepN * 4; i++) placed[i] = keep[i];
        placedN = keepN;
        keepN = 0;
        layers(g, cam);
        // what just happened takes its place first; then what the simulation knows but didn't show
        callouts(g, cam);
        marks(g, cam);
        // the infection as a soft red bleed over the sectors (from far out, and where the dead are only counted)
        if (z < 0.55f && (overlay == OV_INFECTION || overlay == OV_FORCES)) {
            // close in the dead are drawn one by one: the bleed only hints at those indoors
            float a = z > 0.3f ? 0.25f + (0.55f - z) / 0.25f * 0.35f : z > 0.2f ? 0.6f + (0.3f - z) * 4 : 1;
            infection.draw(g, cam, a * (overlay == OV_FORCES ? 0.6f : 1));
        }
        // hordes on the march
        for (Strategic.Horde h : w.strat.hordes) {
            if (!h.alive) continue;
            float sx = cam.toScreenX(h.x), sy = cam.toScreenY(h.y);
            float rad = Math.max(10 * dp, (60 + (float) Math.sqrt(h.count) * 5) * z);
            if (sx < -rad * 2 || sy < -rad * 2 || sx > cam.sw + rad * 2 || sy > cam.sh + rad * 2) continue;
            glow(g, glowRed, sx, sy, rad * 3, 0.65f);
            g.color(0x70901010);
            g.fillCircle(sx, sy, rad);
            g.color(0xFFFF6060);
            g.strokeCircle(sx, sy, rad, 1.6f * dp);
            if (h.target >= 0) arrow(g, sx, sy, cam.toScreenX(h.tx), cam.toScreenY(h.ty), 0xC0FF7070, rad);
            String hl = "Horde " + fmt(h.count);
            float hw = g.textWidth(hl, 12 * dp, true) / 2 + 3 * dp;
            float lx = Math.max(Math.max(areaX0, 0) + hw, Math.min(Math.min(areaX1, cam.sw) - hw, sx));
            float ly = Math.max(Math.max(areaY0, 0) + 13 * dp, sy - rad - 5 * dp);
            label(g, hl, lx, ly, 12, 0xFFFFD0D0, Gfx.ALIGN_CENTER, true);
        }
        // refugee columns
        for (Strategic.Column c : w.strat.columns) {
            if (!c.alive) continue;
            float sx = cam.toScreenX(c.x), sy = cam.toScreenY(c.y);
            float rad = Math.max(5 * dp, 14 * z);
            g.color(0xFF0A1830);
            g.fillCircle(sx, sy, rad + dp);
            g.color(0xFFF0E6D0);
            g.fillCircle(sx, sy, rad);
            g.color(0xFF3060A0);
            g.strokeCircle(sx, sy, rad, 1.4f * dp);
        }
        // the airliner wreck
        if (w.strat.crashX >= 0) {
            float sx = cam.toScreenX(w.strat.crashX), sy = cam.toScreenY(w.strat.crashY);
            float k = Math.max(z, 0.4f * dp / 2);
            g.save();
            g.translate(sx, sy);
            g.rotate(0.6f);
            g.color(0xFFB8B8B0);
            g.fillRect(-60 * k, -6 * k, 90 * k, 12 * k);
            g.fillRect(-10 * k, -38 * k, 16 * k, 76 * k);
            g.color(0xFF303030);
            g.fillRect(30 * k, -6 * k, 40 * k, 12 * k);
            g.restore();
        }
        // squads as unit symbols when far out
        if (z < 0.3f) {
            for (Squads.Squad s : w.sq.list) {
                if (!s.alive) continue;
                float[] p = w.sq.where(s);
                float sx = cam.toScreenX(p[0]), sy = cam.toScreenY(p[1]);
                if (sx < -20 * dp || sy < -20 * dp || sx > cam.sw + 20 * dp || sy > cam.sh + 20 * dp) continue;
                if (s.order == Squads.O_IDLE && z < 0.08f) continue;
                int col = s.faction == Ents.COP ? 0xFF3B6FD8 : 0xFF8A8250;
                float hw = 6 * dp, hh = 4 * dp;
                g.color(0xFF0C0C0C);
                g.fillRect(sx - hw - dp, sy - hh - dp, hw * 2 + 2 * dp, hh * 2 + 2 * dp);
                g.color(col);
                g.fillRect(sx - hw, sy - hh, hw * 2, hh * 2);
                g.color(0xE0FFFFFF);
                g.line(sx - hw, sy - hh, sx + hw, sy + hh, Math.max(1, 0.8f * dp));
                if (s == selected) {
                    g.color(0xFFFFE040);
                    g.strokeRect(sx - hw - 3 * dp, sy - hh - 3 * dp, hw * 2 + 6 * dp, hh * 2 + 6 * dp, 1.6f * dp);
                }
            }
        }
        // calls and the cordon
        if (z < 0.55f && z > 0.04f) {
            // close in the fight itself shows; further out a pulsing ring marks each call, the dead counted there
            float pulse = 0.5f + 0.5f * (float) Math.sin(time * 4);
            for (Dispatch.Incident in : w.disp.incidents) {
                if (!in.open) continue;
                float sx = cam.toScreenX(in.x), sy = cam.toScreenY(in.y);
                if (sx < -40 * dp || sy < -40 * dp || sx > cam.sw + 40 * dp || sy > cam.sh + 40 * dp) continue;
                int col = in.army ? 0xFFE0A030 : 0xFFFF4848;
                boolean big = in.zombies > 0;
                float rr = (big ? 9 + pulse * 3 : 5 + pulse * 2) * dp;
                if (!place(sx - rr, sy - rr, sx + rr, sy + rr)) continue;
                g.color(MapPainter.alpha(col, 0.25f + pulse * 0.2f));
                g.fillCircle(sx, sy, rr);
                g.color(col);
                g.strokeCircle(sx, sy, rr, 1.8f * dp);
                if (big && z > 0.06f) labelAlways(g, in.zombies >= 1000 ? (in.zombies / 1000) + "k" : in.zombies + "", sx, sy + 4 * dp, 11, 0xFFFFFFFF, Gfx.ALIGN_CENTER, true);
            }
        }
        if (z > 0.2f) for (Dispatch.Post p : w.disp.posts) {
            float sx = cam.toScreenX(p.x), sy = cam.toScreenY(p.y);
            if (sx < -30 * dp || sy < -30 * dp || sx > cam.sw + 30 * dp || sy > cam.sh + 30 * dp) continue;
            float l = 9 * dp;
            g.color(0xFF0A1430);
            g.line(sx - p.fy * l, sy + p.fx * l, sx + p.fy * l, sy - p.fx * l, 4.5f * dp);
            g.color(p.squad >= 0 ? 0xFF5A96FF : 0xFF5070A0);
            g.line(sx - p.fy * l, sy + p.fx * l, sx + p.fy * l, sy - p.fx * l, 2.8f * dp);
        }
        // the selected squad's goal: a flag
        if (selected != null && selected.alive) {
            float[] p = w.sq.where(selected);
            float gx = cam.toScreenX(selected.gx), gy = cam.toScreenY(selected.gy);
            g.color(0xA0FFE040);
            dashedLine(g, cam.toScreenX(p[0]), cam.toScreenY(p[1]), gx, gy, 1.5f * dp, 6 * dp);
            flag(g, gx, gy, 0xFFFFE040);
        }
        // the formations' symbols (drawn later, by Forces) keep their ground: names don't go under them
        float fs = (z < 0.08f ? 6.5f : 7.5f) * dp, fhw = fs * 1.3f + 1.5f * dp, fhh = fs * 0.85f;
        for (Forces.Formation f : w.forces.formations) {
            if (!f.alive) continue;
            float sx = cam.toScreenX(f.hq.x), sy = cam.toScreenY(f.hq.y);
            if (sx < -fhw || sy < -fhh || sx > cam.sw + fhw || sy > cam.sh + fhh) continue;
            claim(sx - fhw, sy - fhh + 0.5f * dp, sx + fhw, sy + fhh + 6 * dp);
        }
        // place names: the most important first, and none on top of another
        if (names) {
            for (int pass = 0; pass < 4; pass++)
                for (Region.Settlement s : w.r.settlements) {
                    int rank = s.capital ? 0 : s.kind == Region.S_CITY ? 1 : s.kind == Region.S_TOWN || s.kind == Region.S_BASE || s.kind == Region.S_PRISON ? 2 : 3;
                    if (rank != pass) continue;
                    float sx = cam.toScreenX(s.cx * 16f), sy = cam.toScreenY(s.cy * 16f);
                    if (sx < -150 * dp || sy < -40 * dp || sx > cam.sw + 150 * dp || sy > cam.sh + 40 * dp) continue;
                    float size;
                    if (s.kind == Region.S_CITY) size = z < 0.5f ? (s.capital ? 16 : 14.5f) : 0;
                    else if (rank == 2) size = z < 0.4f && z > 0.03f ? 12 : 0;
                    else size = z < 0.4f && z > 0.07f ? 10.5f : 0;
                    if (size == 0) continue;
                    String label = s.capital ? "★ " + s.name : s.name;
                    boolean twoWant = (s.kind == Region.S_CITY || s.kind == Region.S_TOWN) && z < 0.2f && (s.kind == Region.S_CITY || z > 0.045f);
                    boolean two = twoWant;
                    float tw = g.textWidth(label, size * dp, true);
                    // where it sits: on the place; a city's name, if a fight is marked there, just above or below it
                    float sy0 = sy;
                    boolean ok = false;
                    int tries = rank <= 1 ? 5 : 1;
                    for (int k = 0; k < tries && !ok; k++) {
                        sy = sy0 + (k == 0 ? 0 : (k % 2 == 1 ? -1 : 1) * ((k + 1) / 2) * (size + 18) * dp);
                        two = twoWant;
                        if (place(sx - tw / 2 - 2 * dp, sy - size * dp, sx + tw / 2 + 2 * dp, sy + (two ? (size * 0.4f + 14) * dp : 3 * dp))) ok = true;
                        else if (two && place(sx - tw / 2 - 2 * dp, sy - size * dp, sx + tw / 2 + 2 * dp, sy + 3 * dp)) {
                            // no room for the second line: the name alone
                            two = false;
                            ok = true;
                        }
                    }
                    if (!ok) continue;
                    labelAlways(g, label, sx, sy, size, s.kind == Region.S_CITY ? 0xFFFFFFFF : 0xFFE8E8E8, Gfx.ALIGN_CENTER, true);
                    if (two) {
                        // a second line: how many live there, and how many of the dead
                        int zz = w.stats.zBySettlement[s.id];
                        String sub = people(w.stats.popBySettlement[s.id]);
                        float sy2 = sy + (size * 0.4f + 11) * dp;
                        if (w.gov.evacuating(s.id)) {
                            labelAlways(g, sub + "  evacuating", sx, sy2, 10, 0xFFA0C8FF, Gfx.ALIGN_CENTER, false);
                        } else if (zz > 0) {
                            float t1 = g.textWidth(sub + "  ", 10 * dp, false), t2 = g.textWidth(people(zz) + " dead", 10 * dp, true);
                            float left = sx - (t1 + t2) / 2;
                            labelAlways(g, sub, left, sy2, 10, 0xFFE0E0E0, Gfx.ALIGN_LEFT, false);
                            labelAlways(g, people(zz) + " dead", left + t1, sy2, 10, 0xFFFF7A70, Gfx.ALIGN_LEFT, true);
                        } else labelAlways(g, sub, sx, sy2, 10, 0xFFE0E0E0, Gfx.ALIGN_CENTER, false);
                    }
                }
        }
        // then, in falling order of importance, whatever finds room: facilities, the overlays' badges, refugee
        // columns' numbers, the states' names
        // facilities: icons, greyed when overrun
        if (z < 1.2f) {
            for (Region.Facility f : w.r.facilities) {
                if (f.kind == Region.F_HELIPAD || f.kind == Region.F_PARK) continue;
                if (f.kind == Region.F_DEPOT || f.kind == Region.F_LAB) {
                    if (z < 0.15f) continue;
                }
                if (z < 0.06f && f.kind != Region.F_POWER && f.kind != Region.F_BASE && f.kind != Region.F_PRISON) continue;
                if (z < 0.12f && (f.kind == Region.F_FIRE || f.kind == Region.F_FUEL || f.kind == Region.F_WATER || f.kind == Region.F_PHONE || f.kind == Region.F_FOODW)) continue;
                float sx = cam.toScreenX(f.x), sy = cam.toScreenY(f.y);
                if (sx < -30 * dp || sy < -30 * dp || sx > cam.sw + 30 * dp || sy > cam.sh + 30 * dp) continue;
                boolean on = w.strat.online[f.id];
                float s = (z < 0.12f ? 7.5f : 9.5f) * dp;
                // an icon only where it doesn't cover a name (or another icon)
                if (!place(sx - s - dp, sy - s - dp, sx + s + dp, sy + s + dp)) continue;
                facilityIcon(g, f.kind, sx, sy, on, s);
                if (z > 0.35f && names) {
                    String fn = on ? f.name : f.name + " (overrun)";
                    float tw = g.textWidth(fn, 10.5f * dp, false), ly = sy + s + 12 * dp;
                    if (place(sx - tw / 2, ly - 10 * dp, sx + tw / 2, ly + 3 * dp)) labelAlways(g, fn, sx, ly, 10.5f, on ? 0xFFFFFFFF : 0xFFFF8A80, Gfx.ALIGN_CENTER, false);
                }
            }
        }
        if (overlay == OV_UTIL) utilities(g, cam);
        if (overlay == OV_SUPPLY) supplies(g, cam);
        if (z < 0.6f) for (Strategic.Column c : w.strat.columns) {
            if (!c.alive) continue;
            float sx = cam.toScreenX(c.x), sy = cam.toScreenY(c.y);
            float rad = Math.max(5 * dp, 14 * z);
            label(g, people(c.count) + " refugees", sx, sy - rad - 4 * dp, 10.5f, 0xFFE8F0FF, Gfx.ALIGN_CENTER, false);
        }
        stateNames(g, cam);
    }

    /**
     * From the city's zoom outwards the ground is a few pixels a tile, so a diagonal highway or railway painted tile by
     * tile is a staircase. Over it, each runs as a smooth line along its graph: the railway thin and dark, the
     * highway in asphalt with a darker edge (a blown bridge is left out).
     */
    private void smoothLines(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        if (np != null) {
            nationLines(g, cam, x0, y0, x1, y1);
            return;
        }
        float z = cam.zoom;
        if (z >= 0.45f) return;
        RoadGraph rail = w.r.rails;
        if (rail != null && rail.edgeCount > 0) {
            float lw = Math.max(1.2f * dp, 24 * z);
            for (int pass = 0; pass < 2; pass++) {
                g.color(pass == 0 ? 0xC0201C18 : 0xFF6A6258);
                for (int e = 0; e < rail.edgeCount; e++) {
                    if (!rail.ealive[e]) continue;
                    float ax = rail.nx[rail.ea[e]], ay = rail.ny[rail.ea[e]], bx = rail.nx[rail.eb[e]], by = rail.ny[rail.eb[e]];
                    if (Math.max(ax, bx) < x0 || Math.min(ax, bx) > x1 || Math.max(ay, by) < y0 || Math.min(ay, by) > y1) continue;
                    g.line(cam.toScreenX(ax), cam.toScreenY(ay), cam.toScreenX(bx), cam.toScreenY(by), pass == 0 ? lw + dp : lw * 0.5f);
                }
            }
        }
        RoadGraph rg = w.r.roads;
        for (int pass = 0; pass < 2; pass++) {
            g.color(pass == 0 ? 0xFF2A2C30 : MapPainter.GROUND[Tile.HIGHWAY]);
            for (int e = 0; e < rg.edgeCount; e++) {
                if (rg.ekind[e] != RoadGraph.HIGHWAY || !rg.ealive[e]) continue;
                float ax = rg.nx[rg.ea[e]], ay = rg.ny[rg.ea[e]], bx = rg.nx[rg.eb[e]], by = rg.ny[rg.eb[e]];
                if (Math.max(ax, bx) < x0 || Math.min(ax, bx) > x1 || Math.max(ay, by) < y0 || Math.min(ay, by) > y1) continue;
                float lw = Math.max(1.6f * dp, rg.ehalf[e] * 2 * z);
                g.line(cam.toScreenX(ax), cam.toScreenY(ay), cam.toScreenX(bx), cam.toScreenY(by), pass == 0 ? lw + 1.2f * dp : lw);
            }
        }
    }

    // labels already placed this frame (x0, y0, x1, y1 each), so the next ones don't land on them
    private final float[] placed = new float[4 * 256];
    private boolean justPlaced;
    private int placedN;

    /** Takes a screen rectangle for a label if it's free; false if it would overlap one already placed. */
    boolean place(float x0, float y0, float x1, float y1) {
        justPlaced = false;
        if (x0 < areaX0 || y0 < areaY0 || x1 > areaX1 || y1 > areaY1) return false;
        for (int i = 0; i < placedN; i++) {
            int k = i * 4;
            if (x0 < placed[k + 2] && x1 > placed[k] && y0 < placed[k + 3] && y1 > placed[k + 1]) return false;
        }
        justPlaced = true;
        if (placedN < 256) {
            int k = placedN * 4;
            placed[k] = x0;
            placed[k + 1] = y0;
            placed[k + 2] = x1;
            placed[k + 3] = y1;
            placedN++;
        }
        return true;
    }

    // ------------------------------------------------------------------ callouts

    /** Seconds (real) a callout stays up; the last of it fades. */
    static final float CALLOUT_LIFE = 4.5f;
    private final String[] coText = new String[24];
    private final float[] coX = new float[24], coY = new float[24], coBorn = new float[24];
    private final int[] coKind = new int[24], coCount = new int[24];
    private long coSeen = -1;
    private final int[] coOrder = new int[24];

    /** How many callouts are up now (for the tests and the shots). */
    public int calloutsUp() {
        int n = 0;
        for (int k = 0; k < coText.length; k++) if (coText[k] != null && time - coBorn[k] < CALLOUT_LIFE) n++;
        return n;
    }

    /** Takes the new callouts from the records: one like it close by is counted into it rather than shown twice. */
    private void takeCallouts() {
        Records rc = w.stats.rec;
        long from = Math.max(coSeen + 1, rc.calloutSeq - rc.callouts.length);
        if (coSeen < 0) from = Math.max(0, rc.calloutSeq - 8); // a game just opened: only the latest few
        for (long q = from; q < rc.calloutSeq; q++) {
            Records.Callout c = rc.callouts[(int) (q % rc.callouts.length)];
            if (c == null || c.seq != q) continue;
            int slot = -1, oldest = 0;
            for (int k = 0; k < coText.length; k++) {
                if (coText[k] != null && time - coBorn[k] < CALLOUT_LIFE && coText[k].equals(c.text)
                        && Math.abs(coX[k] - c.x) < 260 && Math.abs(coY[k] - c.y) < 260) {
                    slot = k;
                    break;
                }
                if (coText[oldest] != null && (coText[k] == null || coBorn[k] < coBorn[oldest])) oldest = k;
            }
            if (slot >= 0) {
                coCount[slot]++;
                coBorn[slot] = time;
                continue;
            }
            coText[oldest] = c.text;
            coX[oldest] = c.x;
            coY[oldest] = c.y;
            coKind[oldest] = c.kind;
            coBorn[oldest] = time;
            coCount[oldest] = 1;
        }
        coSeen = rc.calloutSeq - 1;
    }

    /** Short floating text where things happened: the most important first, none on another label. */
    private void callouts(Gfx g, Camera cam) {
        takeCallouts();
        float z = cam.zoom;
        int n = 0;
        for (int k = 0; k < coText.length; k++) {
            if (coText[k] == null) continue;
            if (time - coBorn[k] >= CALLOUT_LIFE) {
                coText[k] = null;
                continue;
            }
            // from far out only the big things (a town, a facility, a horde, a blast)
            int prio = Records.C_PRIO[coKind[k]];
            if (z < 0.12f && prio < 3 || z < 0.03f && prio < 4) continue;
            coOrder[n++] = k;
        }
        // most important, then newest, first
        for (int a = 1; a < n; a++)
            for (int b = a; b > 0; b--) {
                int p = coOrder[b - 1], q = coOrder[b];
                int pp = Records.C_PRIO[coKind[p]], pq = Records.C_PRIO[coKind[q]];
                if (pq > pp || pq == pp && coBorn[q] > coBorn[p]) {
                    coOrder[b - 1] = q;
                    coOrder[b] = p;
                } else break;
            }
        int shown = 0;
        for (int o = 0; o < n && shown < 6; o++) {
            int k = coOrder[o];
            float age = time - coBorn[k];
            float a = Math.max(0, Math.min(1, Math.min(age * 6, (CALLOUT_LIFE - age) / 1.2f)));
            float sx = cam.toScreenX(coX[k]), sy = cam.toScreenY(coY[k]) - (16 + 10 * Math.min(1, age / CALLOUT_LIFE)) * dp;
            String t = coCount[k] > 1 ? coText[k] + " ×" + coCount[k] : coText[k];
            float ts = 11.5f * dp, tw = g.textWidth(t, ts, true), ph = 18 * dp;
            float x0 = sx - tw / 2 - 12 * dp, x1 = sx + tw / 2 + 8 * dp, y0 = sy - ph / 2, y1 = sy + ph / 2;
            if (x1 < 0 || x0 > cam.sw || y1 < 0 || y0 > cam.sh) continue;
            if (!place(x0, y0, x1, y1 + 5 * dp)) continue;
            shown++;
            int col = Records.C_COLOR[coKind[k]];
            g.color(MapPainter.alpha(0xE0140C0C, a));
            g.fillRoundRect(x0, y0, x1 - x0, ph, ph / 2);
            g.color(MapPainter.alpha(col, a));
            g.fillRoundRect(x0 + 4 * dp, y0 + 4 * dp, 3 * dp, ph - 8 * dp, 1.5f * dp);
            // a tick down to the place
            g.line(sx, y1, sx, y1 + 5 * dp, 1.4f * dp);
            labelAlways(g, t, sx - 2 * dp, sy + ts * 0.36f, 11.5f, MapPainter.alpha(0xFFFFFFFF, a), Gfx.ALIGN_CENTER, true);
        }
    }

    // ------------------------------------------------------------------ what the simulation has, drawn

    private java.util.List<float[]> safe = new java.util.ArrayList<float[]>();
    private float safeT = -9;

    /** Survivors' safe points, drones over the streets, the precinct's cordon and how long its calls have waited. */
    private void marks(Gfx g, Camera cam) {
        float z = cam.zoom;
        boolean survivor = w.mode == World.MODE_SURVIVOR || w.roles.role == Roles.SURVIVOR;
        // safe points: where a person on foot is safe (the survivor's goal; on the forces layer for everyone)
        if (survivor || overlay == OV_FORCES) {
            if (time - safeT > 1 || time < safeT) {
                safeT = time;
                safe = w.strat.safePoints();
            }
            float best = Float.MAX_VALUE;
            float[] near = null;
            int pe = w.playerEnt;
            for (float[] p : safe) {
                float sx = cam.toScreenX(p[0]), sy = cam.toScreenY(p[1]);
                if (pe >= 0) {
                    float d = (p[0] - w.e.x[pe]) * (p[0] - w.e.x[pe]) + (p[1] - w.e.y[pe]) * (p[1] - w.e.y[pe]);
                    if (d < best) {
                        best = d;
                        near = p;
                    }
                }
                if (sx < -40 * dp || sy < -40 * dp || sx > cam.sw + 40 * dp || sy > cam.sh + 40 * dp) continue;
                float rr = Math.max(9 * dp, (p[2] == Strategic.SAFE_BASE ? 700 : 300) * z);
                float pulse = 0.5f + 0.5f * (float) Math.sin(time * 2.5f);
                g.color(MapPainter.alpha(0xFF50E080, 0.12f + 0.08f * pulse));
                g.fillCircle(sx, sy, rr);
                g.color(0xFF50E080);
                g.strokeCircle(sx, sy, rr, 2 * dp);
                g.color(0xFF0C2010);
                g.fillCircle(sx, sy, 6 * dp);
                g.color(0xFF60F090);
                g.fillRect(sx - 1.2f * dp, sy - 4 * dp, 2.4f * dp, 8 * dp);
                g.fillRect(sx - 4 * dp, sy - 1.2f * dp, 8 * dp, 2.4f * dp);
                int kind = (int) p[2];
                String lab = "Safe: " + (kind >= 0 && kind < Strategic.SAFE_NAMES.length ? Strategic.SAFE_NAMES[kind] : "here");
                label(g, lab, sx, sy - 10 * dp, 11, 0xFFB8FFC8, Gfx.ALIGN_CENTER, true);
            }
            if (survivor && near != null && pe >= 0 && w.e.loc[pe] == Ents.FREE) {
                // the way to the nearest: a dashed line from you
                float px = cam.toScreenX(w.e.x[pe]), py = cam.toScreenY(w.e.y[pe]);
                g.color(0xA050E080);
                dashedLine(g, px, py, cam.toScreenX(near[0]), cam.toScreenY(near[1]), 1.6f * dp, 7 * dp);
            }
        }
        // drones: a small quad orbiting over the one flying it
        if (z > 0.08f && !w.strategic) {
            ArmedAI ai = w.aai;
            for (int k = 0; k < ai.droneN; k++) {
                float a = time * 1.3f + k * 2.1f, orbit = 34;
                float dx = ai.drones[k * 2] + (float) Math.cos(a) * orbit, dy = ai.drones[k * 2 + 1] + (float) Math.sin(a) * orbit;
                float sx = cam.toScreenX(dx), sy = cam.toScreenY(dy);
                if (sx < -20 * dp || sy < -20 * dp || sx > cam.sw + 20 * dp || sy > cam.sh + 20 * dp) continue;
                quad(g, sx, sy, Math.max(5 * dp, 9 * z), a);
            }
        }
        // the precinct: its cordon posts from further out, and how long each call in its district has waited
        if (w.roles.role == Roles.PRECINCT && z > 0.04f) {
            if (z <= 0.2f) for (Dispatch.Post p : w.disp.posts) {
                float sx = cam.toScreenX(p.x), sy = cam.toScreenY(p.y);
                if (sx < -30 * dp || sy < -30 * dp || sx > cam.sw + 30 * dp || sy > cam.sh + 30 * dp) continue;
                float l = 6 * dp;
                g.color(0xFF0A1430);
                g.line(sx - p.fy * l, sy + p.fx * l, sx + p.fy * l, sy - p.fx * l, 4 * dp);
                g.color(p.squad >= 0 ? 0xFF5A96FF : 0xFF5070A0);
                g.line(sx - p.fy * l, sy + p.fx * l, sx + p.fy * l, sy - p.fx * l, 2.4f * dp);
            }
            for (Dispatch.Incident in : w.disp.incidents) {
                if (!in.open || in.assigned > 0 || !w.roles.inDistrict(in.sector)) continue;
                float sx = cam.toScreenX(in.x), sy = cam.toScreenY(in.y);
                if (sx < -30 * dp || sy < -30 * dp || sx > cam.sw + 30 * dp || sy > cam.sh + 30 * dp) continue;
                callClock(g, sx + 14 * dp, sy - 14 * dp, (float) (w.time - in.created));
            }
        }
    }

    /** A call's waiting clock: an arc that fills over half an hour (red past it) and the minutes. */
    void callClock(Gfx g, float x, float y, float waited) {
        float r = 8 * dp, f = Math.max(0, Math.min(1, waited / 30f));
        int col = waited < 10 ? 0xFFE0E0E0 : waited < 30 ? 0xFFFFC040 : 0xFFFF5040;
        g.color(0xE0101318);
        g.fillCircle(x, y, r + 2 * dp);
        int n = 2 + (int) (f * 24);
        float[] xy = new float[(n + 1) * 2];
        for (int k = 0; k <= n; k++) {
            double a = -Math.PI / 2 + f * Math.PI * 2 * k / n;
            xy[k * 2] = x + (float) Math.cos(a) * r;
            xy[k * 2 + 1] = y + (float) Math.sin(a) * r;
        }
        g.color(col);
        if (f > 0.02f) for (int k = 1; k <= n; k++) g.line(xy[k * 2 - 2], xy[k * 2 - 1], xy[k * 2], xy[k * 2 + 1], 2.2f * dp);
        String t = (int) waited + "m";
        if (place(x - 12 * dp, y + r + 1 * dp, x + 12 * dp, y + r + 13 * dp)) labelAlways(g, t, x, y + r + 11 * dp, 10, col, Gfx.ALIGN_CENTER, true);
    }

    /** A small quadcopter seen from above: four rotors on an X. */
    void quad(Gfx g, float x, float y, float s, float a) {
        float c = (float) Math.cos(a * 0.3f), si = (float) Math.sin(a * 0.3f);
        g.color(0x50000000);
        g.fillCircle(x + s * 0.5f, y + s * 0.7f, s * 0.7f);
        for (int k = 0; k < 4; k++) {
            float ang = (float) (Math.PI / 4 + k * Math.PI / 2);
            float ex = x + (float) (Math.cos(ang) * c - Math.sin(ang) * si) * s, ey = y + (float) (Math.sin(ang) * c + Math.cos(ang) * si) * s;
            g.color(0xFF101418);
            g.line(x, y, ex, ey, Math.max(1.5f, s * 0.22f));
            g.color(0x90C8D8E8);
            g.fillCircle(ex, ey, s * 0.42f);
            g.color(0xFF101418);
            g.strokeCircle(ex, ey, s * 0.42f, Math.max(1, s * 0.08f));
        }
        g.color(0xFF2A3440);
        g.fillCircle(x, y, s * 0.3f);
        g.color(0xFFFF4040);
        g.fillCircle(x, y, s * 0.1f);
    }

    /** Takes a screen rectangle whatever is there already (a mark drawn later that labels must not cover). */
    private void claim(float x0, float y0, float x1, float y1) {
        if (placedN >= 256) return;
        int k = placedN++ * 4;
        placed[k] = x0;
        placed[k + 1] = y0;
        placed[k + 2] = x1;
        placed[k + 3] = y1;
    }

    /** An arrow from a point to another, starting outside a radius. */
    void arrow(Gfx g, float x, float y, float tx, float ty, int color, float startR) {
        float dx = tx - x, dy = ty - y, dl = (float) Math.sqrt(dx * dx + dy * dy);
        if (dl < startR + 12 * dp) return;
        float ux = dx / dl, uy = dy / dl;
        float ax = x + ux * startR, ay = y + uy * startR;
        g.color(color);
        g.line(ax, ay, tx, ty, 2 * dp);
        float ah = 8 * dp;
        poly[0] = tx;
        poly[1] = ty;
        poly[2] = tx - ux * ah - uy * ah * 0.55f;
        poly[3] = ty - uy * ah + ux * ah * 0.55f;
        poly[4] = tx - ux * ah + uy * ah * 0.55f;
        poly[5] = ty - uy * ah - ux * ah * 0.55f;
        g.fillPoly(poly, 3);
    }

    /** A little flag on a pole, planted at a point (an order's goal). */
    void flag(Gfx g, float x, float y, int color) {
        float h = 16 * dp;
        g.color(0xC0000000);
        g.fillCircle(x, y, 2.5f * dp);
        g.line(x, y, x, y - h, 2.6f * dp);
        g.color(0xFFF0F0F0);
        g.line(x, y, x, y - h, 1.4f * dp);
        poly[0] = x;
        poly[1] = y - h;
        poly[2] = x + 10 * dp;
        poly[3] = y - h + 3.5f * dp;
        poly[4] = x;
        poly[5] = y - h + 7 * dp;
        g.color(0xC0000000);
        g.fillPoly(poly, 3);
        poly[2] -= dp;
        poly[1] += dp * 0.6f;
        poly[5] -= dp * 0.6f;
        g.color(color);
        g.fillPoly(poly, 3);
    }

    // ------------------------------------------------------------------ a real-scale map

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** How far the district pictures have faded in over the country picture at a zoom (0..1). */
    static float districtFade(float z) {
        return clamp01((z / NationPainter.Z_DISTRICT - 0.85f) / 0.3f);
    }

    /** How far the street pictures have faded in over the district pictures at a zoom (0..1). */
    static float streetFade(float z) {
        return clamp01((z / NationPainter.Z_STREET - 1) / 0.25f);
    }

    /** Whether every street picture of a fine square is painted (at any level). */
    private boolean streetsAt(int fx, int fy) {
        int per = NationPainter.FINE / MapPainter.CHUNK;
        for (int cy = fy * per; cy < (fy + 1) * per; cy++)
            for (int cx = fx * per; cx < (fx + 1) * per; cx++) {
                boolean any = false;
                for (int l = 0; l < MapPainter.PPT.length && !any; l++) any = map.has(cx, cy, l);
                if (!any) return false;
            }
        return true;
    }

    /** A real-scale map with no world on it: the ground, the lines, the buildings and the names. */
    private void drawNation(Gfx g, Camera cam, float dt) {
        time += dt;
        dp = cam.dp;
        float z = cam.zoom;
        g.color(NationPainter.WATER_DEEP);
        g.fillRect(0, 0, g.width(), g.height());
        groundNation(g, cam);
        float x0 = cam.x0() - 80, y0 = cam.y0() - 80, x1 = cam.x1() + 80, y1 = cam.y1() + 80;
        nationLines(g, cam, x0, y0, x1, y1);
        if (z >= 0.22f) buildings(g, cam, x0, y0, x1, y1);
        lqN = 0;
        deferLabels = true;
        for (int i = 0; i < keepN * 4; i++) placed[i] = keep[i];
        placedN = keepN;
        keepN = 0;
        nationLabels(g, cam);
        flushLabels(g);
        areaX0 = areaY0 = -1e9f;
        areaX1 = areaY1 = 1e9f;
        g.image(vignette, 0, 0, g.width(), g.height());
    }

    /**
     * The ground of a real-scale map: the country picture under everything; district pictures fading in over it from
     * zoom 0.012; street pictures from cached chunks fading in over those from 0.2 (where a chunk isn't in memory the
     * district picture shows, and the chunk is asked for); the density tint over the country view, fading out as the
     * districts come in.
     */
    private void groundNation(Gfx g, Camera cam) {
        float z = cam.zoom, worldPx = nat.px();
        framesDrawn++;
        boolean still = Math.abs(cam.cx - lastCx) * z < 1 && Math.abs(cam.cy - lastCy) * z < 1 && Math.abs(z - lastZ) < 0.0005f;
        stillT = still ? stillT + 0.016f : 0;
        lastCx = cam.cx;
        lastCy = cam.cy;
        lastZ = z;
        long budget = framesDrawn < 20 ? 40_000_000L : stillT > 0.25f ? 14_000_000L : paintBudgetNs;
        float ox = cam.toScreenX(0), oy = cam.toScreenY(0);
        float da = districtFade(z), sa = streetFade(z);
        float dpx = NationPainter.DISTRICT * Tile.SIZE;
        int dx0 = Math.max(0, (int) (cam.x0() / dpx)), dy0 = Math.max(0, (int) (cam.y0() / dpx));
        int dx1 = Math.min(np.dw - 1, (int) (cam.x1() / dpx)), dy1 = Math.min(np.dw - 1, (int) (cam.y1() / dpx));
        float pcPx = MapPainter.CHUNK * Tile.SIZE;
        int pn = nat.size / MapPainter.CHUNK;
        int px0 = Math.max(0, (int) (cam.x0() / pcPx)), py0 = Math.max(0, (int) (cam.y0() / pcPx));
        int px1 = Math.min(pn - 1, (int) (cam.x1() / pcPx)), py1 = Math.min(pn - 1, (int) (cam.y1() / pcPx));
        // a level fully covered by the one over it isn't drawn (scaling a big picture costs, on a desktop above all)
        boolean streetsCover = sa >= 1, districtsCover = da >= 1;
        int slvl = MapPainter.levelFor(z), dlvl = NationPainter.levelFor(z);
        for (int cy = py0; cy <= py1 && streetsCover; cy++)
            for (int cx = px0; cx <= px1 && streetsCover; cx++) {
                boolean any = false;
                for (int l = slvl; l >= 0 && !any; l--) any = map.has(cx, cy, l);
                streetsCover = any;
            }
        for (int dy = dy0; dy <= dy1 && districtsCover && !streetsCover; dy++)
            for (int dx = dx0; dx <= dx1 && districtsCover; dx++) {
                boolean any = false;
                for (int l = 0; l < NationPainter.DTPP.length && !any; l++) any = np.has(dx, dy, l);
                districtsCover = any;
            }
        if (!districtsCover && !streetsCover) g.image(np.countryImage, ox, oy, worldPx * z, worldPx * z);
        long p0 = np.paintNs + map.paintNs; // the budget is for painting
        if (da > 0 && !streetsCover) {
            int lvl = dlvl;
            float mx = (dx0 + dx1) / 2f, my = (dy0 + dy1) / 2f;
            int rings = Math.max(dx1 - dx0, dy1 - dy0);
            for (int ring = 0; ring <= rings; ring++)
                for (int dy = dy0; dy <= dy1; dy++)
                    for (int dx = dx0; dx <= dx1; dx++) {
                        if ((int) Math.max(Math.abs(dx - mx), Math.abs(dy - my)) != ring) continue;
                        boolean may = (np.paintNs + map.paintNs - p0) < budget;
                        GImage img = np.district(dx, dy, lvl, may);
                        if (img == null) {
                            // another level stands in (coarser first); else a coarse one is cheap enough to paint now
                            for (int l = lvl - 1; l >= 0 && img == null; l--) img = np.district(dx, dy, l, false);
                            for (int l = lvl + 1; l < NationPainter.DTPP.length && img == null; l++) img = np.district(dx, dy, l, false);
                            if (img == null && (np.paintNs + map.paintNs - p0) < budget + 4_000_000L) img = np.district(dx, dy, 0, true);
                        }
                        if (img == null) continue;
                        float sx = cam.toScreenX(dx * dpx), sy = cam.toScreenY(dy * dpx), side = dpx * z;
                        if (da >= 1) g.image(img, sx, sy, side + 0.75f, side + 0.75f);
                        else g.image(img, sx, sy, side, side, da);
                    }
        }
        // town zoom: a tile a pixel over the district pictures, nearest the middle first, till the streets cover
        float fa = clamp01((z / NationPainter.FINE_FROM - 1) / 0.25f);
        if (fa > 0 && !streetsCover) {
            float fpx = NationPainter.FINE * Tile.SIZE;
            int fn = nat.size / NationPainter.FINE;
            int fx0 = Math.max(0, (int) (cam.x0() / fpx)), fy0 = Math.max(0, (int) (cam.y0() / fpx));
            int fx1 = Math.min(fn - 1, (int) (cam.x1() / fpx)), fy1 = Math.min(fn - 1, (int) (cam.y1() / fpx));
            float mx = (fx0 + fx1) / 2f, my = (fy0 + fy1) / 2f;
            int rings = Math.max(fx1 - fx0, fy1 - fy0);
            for (int ring = 0; ring <= rings; ring++)
                for (int fy = fy0; fy <= fy1; fy++)
                    for (int fx = fx0; fx <= fx1; fx++) {
                        if ((int) Math.max(Math.abs(fx - mx), Math.abs(fy - my)) != ring) continue;
                        // under the streets that are already in, nothing to paint
                        if (sa >= 1 && streetsAt(fx, fy)) continue;
                        boolean may = (np.paintNs + map.paintNs - p0) < budget;
                        GImage img = np.fine(fx, fy, may);
                        if (img == null) continue;
                        float sx = cam.toScreenX(fx * fpx), sy = cam.toScreenY(fy * fpx), side = fpx * z;
                        if (fa >= 1) g.image(img, sx, sy, side + 0.75f, side + 0.75f);
                        else g.image(img, sx, sy, side, side, fa);
                    }
        }
        float chunkPx = Chunk.SIZE * Tile.SIZE;
        if (z >= NationPainter.Z_STREET * 0.8f) {
            // close to the streets: the chunks in view are made in the background, the nearest first
            int cx0 = Math.max(0, (int) (cam.x0() / chunkPx)), cy0 = Math.max(0, (int) (cam.y0() / chunkPx));
            int cx1 = Math.min(nat.sw - 1, (int) (cam.x1() / chunkPx)), cy1 = Math.min(nat.sw - 1, (int) (cam.y1() / chunkPx));
            int ccx = (int) (cam.cx / chunkPx), ccy = (int) (cam.cy / chunkPx);
            int rings = Math.max(Math.max(ccx - cx0, cx1 - ccx), Math.max(ccy - cy0, cy1 - ccy)) + (sa > 0 && stillT > 0.5f ? 1 : 0);
            for (int ring = rings; ring >= 0; ring--)
                for (int cy = ccy - ring; cy <= ccy + ring; cy++)
                    for (int cx = ccx - ring; cx <= ccx + ring; cx++) {
                        if (Math.max(Math.abs(cx - ccx), Math.abs(cy - ccy)) != ring) continue;
                        if (cx < 0 || cy < 0 || cx >= nat.sw || cy >= nat.sw) continue;
                        if (ring < rings - (sa > 0 && stillT > 0.5f ? 1 : 0) && (cx < cx0 || cx > cx1 || cy < cy0 || cy > cy1)) continue;
                        if (np.src.chunk(cx, cy) == null) np.src.want(cx, cy);
                    }
        }
        if (sa > 0) {
            int lvl = slvl;
            int cx0 = px0, cy0 = py0, cx1 = px1, cy1 = py1;
            float mx = (cx0 + cx1) / 2f, my = (cy0 + cy1) / 2f;
            int rings = Math.max(cx1 - cx0, cy1 - cy0);
            for (int ring = 0; ring <= rings; ring++)
                for (int cy = cy0; cy <= cy1; cy++)
                    for (int cx = cx0; cx <= cx1; cx++) {
                        if ((int) Math.max(Math.abs(cx - mx), Math.abs(cy - my)) != ring) continue;
                        boolean may = (np.paintNs + map.paintNs - p0) < budget;
                        GImage img = map.chunk(cx, cy, lvl, may);
                        if (img == null) {
                            for (int l = lvl - 1; l >= 0 && img == null; l--) img = map.chunk(cx, cy, l, false);
                            if ((img == null || !map.has(cx, cy, Math.min(1, lvl))) && lvl >= 1 && (np.paintNs + map.paintNs - p0) < budget + 4_000_000L)
                                img = map.chunk(cx, cy, 1, true);
                            if (img == null && (np.paintNs + map.paintNs - p0) < budget + 4_000_000L) img = map.chunk(cx, cy, 0, true);
                        }
                        if (img == null) continue;
                        float sx = cam.toScreenX(cx * pcPx), sy = cam.toScreenY(cy * pcPx), side = pcPx * z;
                        if (sa >= 1) g.image(img, sx, sy, side + 0.75f, side + 0.75f);
                        else g.image(img, sx, sy, side, side, sa);
                    }
        }
        float ta = clamp01((NationPainter.Z_DISTRICT * 1.4f - z) / (NationPainter.Z_DISTRICT * 0.6f));
        if (ta > 0) g.image(np.tintImage, ox, oy, worldPx * z, worldPx * z, ta);
    }

    /**
     * Rivers and the roads between places as lines while the country picture shows (they fade as the district
     * pictures, which paint them, come in); railways and highways as smooth lines until the streets are close.
     */
    private void nationLines(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        float z = cam.zoom;
        float fade = 1 - districtFade(z);
        if (fade > 0) {
            g.color(MapPainter.alpha(NationPainter.RIVER, fade));
            for (int r = 0; r < nat.rivers.size(); r++) {
                float[] p = nat.rivers.get(r);
                float lw = Math.max(1.1f * dp, nat.riverWidth.get(r) * 16 * z);
                for (int i = 0; i + 3 < p.length; i += 2) {
                    float ax = p[i] * 16, ay = p[i + 1] * 16, bx = p[i + 2] * 16, by = p[i + 3] * 16;
                    if (Math.max(ax, bx) < x0 || Math.min(ax, bx) > x1 || Math.max(ay, by) < y0 || Math.min(ay, by) > y1) continue;
                    g.line(cam.toScreenX(ax), cam.toScreenY(ay), cam.toScreenX(bx), cam.toScreenY(by), lw);
                }
            }
            RoadGraph rg = nat.roads;
            g.color(MapPainter.alpha(MapPainter.GROUND[Tile.ROAD], fade * 0.9f));
            for (int e : np.countryRoads) {
                float ax = rg.nx[rg.ea[e]], ay = rg.ny[rg.ea[e]], bx = rg.nx[rg.eb[e]], by = rg.ny[rg.eb[e]];
                if (Math.max(ax, bx) < x0 || Math.min(ax, bx) > x1 || Math.max(ay, by) < y0 || Math.min(ay, by) > y1) continue;
                g.line(cam.toScreenX(ax), cam.toScreenY(ay), cam.toScreenX(bx), cam.toScreenY(by), Math.max(0.8f * dp, rg.ehalf[e] * 2 * z));
            }
        }
        if (z >= 0.45f) return;
        RoadGraph rail = nat.rails;
        if (rail.edgeCount > 0) {
            float lw = Math.max(1.2f * dp, 24 * z);
            for (int pass = 0; pass < 2; pass++) {
                g.color(pass == 0 ? 0xC0201C18 : 0xFF6A6258);
                for (int e = 0; e < rail.edgeCount; e++) {
                    if (!rail.ealive[e]) continue;
                    float ax = rail.nx[rail.ea[e]], ay = rail.ny[rail.ea[e]], bx = rail.nx[rail.eb[e]], by = rail.ny[rail.eb[e]];
                    if (Math.max(ax, bx) < x0 || Math.min(ax, bx) > x1 || Math.max(ay, by) < y0 || Math.min(ay, by) > y1) continue;
                    g.line(cam.toScreenX(ax), cam.toScreenY(ay), cam.toScreenX(bx), cam.toScreenY(by), pass == 0 ? lw + dp : lw * 0.5f);
                }
            }
        }
        RoadGraph rg = nat.roads;
        interchanges(g, cam, x0, y0, x1, y1);
        for (int pass = 0; pass < 2; pass++) {
            g.color(pass == 0 ? 0xFF2A2C30 : MapPainter.GROUND[Tile.HIGHWAY]);
            for (int e : np.highways) {
                if (!rg.ealive[e]) continue;
                float ax = rg.nx[rg.ea[e]], ay = rg.ny[rg.ea[e]], bx = rg.nx[rg.eb[e]], by = rg.ny[rg.eb[e]];
                if (Math.max(ax, bx) < x0 || Math.min(ax, bx) > x1 || Math.max(ay, by) < y0 || Math.min(ay, by) > y1) continue;
                float lw = Math.max(1.6f * dp, rg.ehalf[e] * 2 * z);
                g.line(cam.toScreenX(ax), cam.toScreenY(ay), cam.toScreenX(bx), cam.toScreenY(by), pass == 0 ? lw + 1.2f * dp : lw);
            }
        }
    }

    private int[] natStamp;
    private int natStampN;
    /** Interchanges: {x, y, highway dir x, dir y} where a highway meets a city street (national maps), worked out once. */
    private float[][] ichg;

    /**
     * Where a highway meets the streets of a city it passes over them: a cloverleaf of ramps in the four corners
     * and the carriageway's shadow on the road below, drawn with the highways (vectors, below street zoom).
     */
    private void interchanges(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        RoadGraph rg = nat.roads;
        if (ichg == null) {
            java.util.List<float[]> l = new java.util.ArrayList<float[]>();
            java.util.HashSet<Integer> seen = new java.util.HashSet<Integer>();
            for (int e : np.highways)
                for (int end = 0; end < 2; end++) {
                    int n = end == 0 ? rg.ea[e] : rg.eb[e];
                    if (!seen.add(n)) continue;
                    int st = nat.secSettlement[nat.sector((int) (rg.nx[n] / 16), (int) (rg.ny[n] / 16))];
                    if (st < 0 || nat.settlements.get(st).kind != Region.S_CITY) continue;
                    boolean street = false;
                    for (int k = rg.adjStart[n]; k < rg.adjStart[n + 1]; k++) if (rg.ekind[rg.adjEdge[k]] != RoadGraph.HIGHWAY) street = true;
                    if (!street) continue;
                    float dx = rg.nx[rg.eb[e]] - rg.nx[rg.ea[e]], dy = rg.ny[rg.eb[e]] - rg.ny[rg.ea[e]], d = (float) Math.sqrt(dx * dx + dy * dy) + 1e-3f;
                    // not two within a kilometre: one interchange each
                    boolean near = false;
                    for (float[] q : l) if (Math.abs(q[0] - rg.nx[n]) + Math.abs(q[1] - rg.ny[n]) < 4000) near = true;
                    if (!near) l.add(new float[]{rg.nx[n], rg.ny[n], dx / d, dy / d});
                }
            ichg = l.toArray(new float[0][]);
        }
        float z = cam.zoom;
        if (z < 0.004f) return;
        float r = 220; // a ramp's radius, px (75 m)
        float lw = Math.max(1.1f * dp, 22 * z);
        for (int pass = 0; pass < 2; pass++) {
            g.color(pass == 0 ? 0xFF2A2C30 : MapPainter.GROUND[Tile.HIGHWAY]);
            for (float[] q : ichg) {
                if (q[0] + 3 * r < x0 || q[0] - 3 * r > x1 || q[1] + 3 * r < y0 || q[1] - 3 * r > y1) continue;
                float hx = q[2], hy = q[3], px = -hy, py = hx;
                for (int a = -1; a <= 1; a += 2)
                    for (int b = -1; b <= 1; b += 2) {
                        float cx = q[0] + (hx * a + px * b) * r * 1.05f, cy = q[1] + (hy * a + py * b) * r * 1.05f;
                        float prevX = 0, prevY = 0;
                        // a loop of three quarters, open towards the crossing (where it joins the roads)
                        double toNode = Math.atan2(q[1] - cy, q[0] - cx);
                        for (int k = 0; k <= 18; k++) {
                            double ang = toNode + Math.PI / 4 + k * Math.PI * 1.5 / 18;
                            float sx = cam.toScreenX(cx + (float) Math.cos(ang) * r), sy = cam.toScreenY(cy + (float) Math.sin(ang) * r);
                            if (k > 0) g.line(prevX, prevY, sx, sy, pass == 0 ? lw + 1.2f * dp : lw);
                            prevX = sx;
                            prevY = sy;
                        }
                    }
                if (pass == 0) {
                    // the carriageway's shadow where it passes over the street
                    g.color(0x50000000);
                    float sx = cam.toScreenX(q[0] - hx * r * 1.6f) + 3 * dp, sy = cam.toScreenY(q[1] - hy * r * 1.6f) + 4 * dp;
                    float ex = cam.toScreenX(q[0] + hx * r * 1.6f) + 3 * dp, ey = cam.toScreenY(q[1] + hy * r * 1.6f) + 4 * dp;
                    g.line(sx, sy, ex, ey, Math.max(2 * dp, 90 * z));
                    g.color(0xFF2A2C30);
                }
            }
        }
    }

    /** The buildings of the cached chunks near the view (and the national buildings in them) into the drawn arrays. */
    private int nationBuildings(float x0, float y0, float x1, float y1) {
        int tx0 = Math.max(0, (int) (x0 / 16) - 40), ty0 = Math.max(0, (int) (y0 / 16) - 40);
        int tx1 = Math.min(nat.size - 1, (int) (x1 / 16) + 4), ty1 = Math.min(nat.size - 1, (int) (y1 / 16) + 4);
        if (natStamp == null || natStamp.length < nat.bCount) natStamp = new int[Math.max(1, nat.bCount)];
        natStampN++;
        long[] nb = ChunkGen.index(nat).nats;
        int n = 0;
        for (int cy = ty0 >> 6; cy <= ty1 >> 6; cy++)
            for (int cx = tx0 >> 6; cx <= tx1 >> 6; cx++) {
                Chunk c = np.src.chunk(cx, cy);
                if (c == null) continue;
                int X0 = cx * Chunk.SIZE, Y0 = cy * Chunk.SIZE;
                growDrawn(n + c.bCount);
                for (int i = 0; i < c.bCount; i++) {
                    int bx = X0 + c.bx[i], by = Y0 + c.by[i], bw = c.bw[i], bh = c.bh[i];
                    if (c.bfloors[i] <= 0 || bx > tx1 || by > ty1 || bx + bw < tx0 + 36 || by + bh < ty0 + 36) continue;
                    dbx[n] = bx;
                    dby[n] = by;
                    dbw[n] = bw;
                    dbh[n] = bh;
                    dbf[n] = c.bfloors[i];
                    dbr[n] = c.broof[i];
                    dbk[n] = c.bkind[i];
                    dbid[n] = c.id(i);
                    n++;
                }
                // the national buildings standing in it (facilities and sites), once each
                long key = (long) c.index << 32;
                int lo = 0, hi = nb.length;
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (nb[mid] < key) lo = mid + 1;
                    else hi = mid;
                }
                for (int p = lo; p < nb.length && (int) (nb[p] >>> 32) == c.index; p++) {
                    int b = (int) nb[p];
                    if (natStamp[b] == natStampN) continue;
                    natStamp[b] = natStampN;
                    int bx = nat.bx[b], by = nat.by[b], bw = nat.bw[b], bh = nat.bh[b];
                    byte[] nbf = w != null ? w.r.bfloors : nat.bfloors; // (a world's own: the ones brought down)
                    if (nbf[b] <= 0 || bx > tx1 || by > ty1 || bx + bw < tx0 + 36 || by + bh < ty0 + 36) continue;
                    growDrawn(n + 1);
                    dbx[n] = bx;
                    dby[n] = by;
                    dbw[n] = bw;
                    dbh[n] = bh;
                    dbf[n] = nbf[b];
                    dbr[n] = nat.broof[b];
                    dbk[n] = nat.bkind[b];
                    dbid[n] = b;
                    n++;
                }
            }
        return n;
    }

    /**
     * A real-scale map's names and symbols through the same collision pass as today's maps: the capital and cities
     * from the whole country, the bigger towns and the sites (bases, prisons, airfields) from the country view, all
     * towns a little closer, villages from the district view; facilities by importance as the zoom grows.
     */
    private void nationLabels(Gfx g, Camera cam) {
        float z = cam.zoom;
        if (names) {
            for (int pass = 0; pass < 4; pass++)
                for (Region.Settlement s : nat.settlements) {
                    boolean town = s.kind == Region.S_TOWN, village = s.kind == Region.S_VILLAGE, city = s.kind == Region.S_CITY;
                    if (s.kind == Region.S_HOLDOUT || s.kind == Region.S_CAMP) continue;
                    int rank = s.capital ? 0 : city ? 1 : village ? 3 : 2;
                    if (rank != pass) continue;
                    float size;
                    if (city) size = z < 0.5f ? (s.capital ? 16 : 14.5f) : 0;
                    else if (town) size = z < 0.4f && (z > 0.0045f || s.residents >= 15000) ? 12 : 0;
                    else if (village) size = z < 0.4f && z > 0.022f ? 10.5f : 0;
                    else size = z < 0.4f && z > 0.0035f ? 11 : 0;
                    if (size == 0) continue;
                    float sx = cam.toScreenX(s.cx * 16f), sy = cam.toScreenY(s.cy * 16f);
                    if (sx < -150 * dp || sy < -40 * dp || sx > cam.sw + 150 * dp || sy > cam.sh + 40 * dp) continue;
                    String label = s.capital ? "★ " + s.name : s.name;
                    boolean twoWant = (city || town) && z < 0.2f && (city || z > 0.009f);
                    boolean two = twoWant;
                    float tw = g.textWidth(label, size * dp, true);
                    float sy0 = sy;
                    boolean ok = false;
                    int tries = rank <= 1 ? 5 : 1;
                    for (int k = 0; k < tries && !ok; k++) {
                        sy = sy0 + (k == 0 ? 0 : (k % 2 == 1 ? -1 : 1) * ((k + 1) / 2) * (size + 18) * dp);
                        two = twoWant;
                        if (place(sx - tw / 2 - 2 * dp, sy - size * dp, sx + tw / 2 + 2 * dp, sy + (two ? (size * 0.4f + 14) * dp : 3 * dp))) ok = true;
                        else if (two && place(sx - tw / 2 - 2 * dp, sy - size * dp, sx + tw / 2 + 2 * dp, sy + 3 * dp)) {
                            two = false;
                            ok = true;
                        }
                    }
                    if (!ok) continue;
                    labelAlways(g, label, sx, sy, size, city ? 0xFFFFFFFF : rank == 2 && !town ? 0xFFFFD8B0 : 0xFFE8E8E8, Gfx.ALIGN_CENTER, true);
                    if (two) labelAlways(g, people(s.residents), sx, sy + (size * 0.4f + 11) * dp, 10, 0xFFE0E0E0, Gfx.ALIGN_CENTER, false);
                }
        }
        if (z >= 1.2f) return;
        for (Region.Facility f : nat.facilities) {
            int k = f.kind;
            if (k == Region.F_HELIPAD || k == Region.F_PARK) continue;
            boolean major = k == Region.F_POWER || k == Region.F_BASE || k == Region.F_PRISON || k == Region.F_AIRBASE || k == Region.F_PORT || k == Region.F_AIRPORT;
            boolean emergency = k == Region.F_POLICE || k == Region.F_HOSPITAL;
            if (z < 0.025f && !major) continue;
            if (z < 0.045f && !major && !emergency) continue;
            if (z < 0.06f && (k == Region.F_FIRE || k == Region.F_FUEL || k == Region.F_WATER || k == Region.F_PHONE || k == Region.F_FOODW
                    || k == Region.F_DEPOT || k == Region.F_LAB)) continue;
            float sx = cam.toScreenX(f.x), sy = cam.toScreenY(f.y);
            if (sx < -30 * dp || sy < -30 * dp || sx > cam.sw + 30 * dp || sy > cam.sh + 30 * dp) continue;
            float s = (z < 0.03f ? 6.5f : z < 0.12f ? 7.5f : 9.5f) * dp;
            if (!place(sx - s - dp, sy - s - dp, sx + s + dp, sy + s + dp)) continue;
            facilityIcon(g, k, sx, sy, true, s);
            if (z > 0.35f && names && f.name != null) {
                float tw = g.textWidth(f.name, 10.5f * dp, false), ly = sy + s + 12 * dp;
                if (place(sx - tw / 2, ly - 10 * dp, sx + tw / 2, ly + 3 * dp)) labelAlways(g, f.name, sx, ly, 10.5f, 0xFFFFFFFF, Gfx.ALIGN_CENTER, false);
            }
        }
    }

    // ------------------------------------------------------------------ the national map's layers

    private float[] stateX, stateY;

    /** The chosen overlay over the sectors, and state borders when far out. */
    private void layers(Gfx g, Camera cam) {
        float z = cam.zoom;
        float cs = w.r.sect * Tile.SIZE;
        Sectors sec = w.sec;
        int sx0 = Math.max(0, (int) (cam.x0() / cs)), sy0 = Math.max(0, (int) (cam.y0() / cs));
        int sx1 = Math.min(sec.sw - 1, (int) (cam.x1() / cs)), sy1 = Math.min(sec.sh - 1, (int) (cam.y1() / cs));
        if (overlay == OV_FORCES || overlay == OV_UTIL || overlay == OV_SUPPLY) {
            g.color(0x80000000);
            g.fillRect(0, 0, g.width(), g.height());
        }
        if (overlay == OV_TRUST || overlay == OV_DEATHS || overlay == OV_PEAK || overlay == OV_UTIL) {
            heat.kindFor(overlay);
            heat.draw(g, cam, 1);
        }
        // state borders from far out (their names come last, where there's room)
        if (z < 0.2f && w.r.states.size() > 1) {
            float lw = 1.4f * dp;
            for (int pass = 0; pass < 2; pass++) {
                g.color(pass == 0 ? 0x80000000 : 0xB0F0E8D8);
                for (int sy = sy0; sy <= sy1; sy++)
                    for (int sx = sx0; sx <= sx1; sx++) {
                        int s = sy * sec.sw + sx;
                        int st = w.roles.stateOfSector(s);
                        float x = cam.toScreenX(sx * cs), y = cam.toScreenY(sy * cs), len = cs * z;
                        float o = pass == 0 ? dp * 0.8f : 0;
                        if (sx + 1 < sec.sw && w.roles.stateOfSector(s + 1) != st) dashedLine(g, x + len + o, y + o, x + len + o, y + len + o, pass == 0 ? lw * 1.8f : lw, 5 * dp);
                        if (sy + 1 < sec.sh && w.roles.stateOfSector(s + sec.sw) != st) dashedLine(g, x + o, y + len + o, x + len + o, y + len + o, pass == 0 ? lw * 1.8f : lw, 5 * dp);
                    }
            }
            if (z < 0.08f) {
                if (stateX == null) {
                    int ns = w.r.states.size();
                    stateX = new float[ns];
                    stateY = new float[ns];
                    int[] cnt = new int[ns];
                    for (int s = 0; s < sec.n; s++) {
                        int st = w.roles.stateOfSector(s);
                        if (st < 0 || !dryAt(sec.cx(s), sec.cy(s))) continue;
                        stateX[st] += sec.cx(s);
                        stateY[st] += sec.cy(s);
                        cnt[st]++;
                    }
                    for (int k = 0; k < ns; k++) if (cnt[k] > 0) {
                        stateX[k] /= cnt[k];
                        stateY[k] /= cnt[k];
                    }
                }
            }
        }
    }

    /** The states' names, spaced out, from far out: the least important label, only where there's room. */
    private void stateNames(Gfx g, Camera cam) {
        if (cam.zoom >= 0.08f || stateX == null || w.r.states.size() < 2) return;
        for (Region.State st : w.r.states)
            label(g, spaced(st.name.toUpperCase()), cam.toScreenX(stateX[st.id]), cam.toScreenY(stateY[st.id]) + 34 * dp, 12, 0x90F0E8D8, Gfx.ALIGN_CENTER, true);
    }

    private final StringBuilder spacedSb = new StringBuilder();

    /** Letters spaced out, the way a map names a region. */
    private String spaced(String s) {
        spacedSb.setLength(0);
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) spacedSb.append(' ');
            spacedSb.append(s.charAt(i));
        }
        return spacedSb.toString();
    }

    static void dashedLine(Gfx g, float x1, float y1, float x2, float y2, float lw, float dash) {
        float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
        int n = Math.max(1, (int) (len / dash));
        for (int i = 0; i < n; i += 2) g.line(x1 + dx * i / n, y1 + dy * i / n, x1 + dx * (i + 1) / n, y1 + dy * (i + 1) / n, lw);
    }

    private boolean[] waterNet, phoneNet;
    private float netT = -10;

    /** Power, water and telephones for every town: a disc for each, with a ring when it's working. */
    private void utilities(Gfx g, Camera cam) {
        if (time - netT > 1 || waterNet == null) {
            waterNet = network(Region.F_WATER);
            phoneNet = network(Region.F_PHONE);
            netT = time;
        }
        float r = 7 * dp;
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
            float x = cam.toScreenX(s.cx * 16f), y = cam.toScreenY(s.cy * 16f) + 31 * dp;
            if (x < -40 * dp || y < -40 * dp || x > cam.sw + 40 * dp || y > cam.sh + 40 * dp) continue;
            if (!place(x - r * 3.6f, y - r - 2 * dp, x + r * 3.6f, y + r + 2 * dp)) continue;
            for (int k = 0; k < 3; k++) {
                boolean on = k == 0 ? s.grid < 0 || w.strat.gridOn[s.grid] : k == 1 ? s.water < 0 || s.water >= waterNet.length || waterNet[s.water]
                        : s.phone < 0 || s.phone >= phoneNet.length || phoneNet[s.phone];
                float cx = x + (k - 1) * r * 2.4f;
                g.color(0xE0000000);
                g.fillCircle(cx, y, r + 1.5f * dp);
                g.color(on ? 0xFF3A9A48 : 0xFF8A2020);
                g.fillCircle(cx, y, r);
                g.color(on ? 0xFF9AF0A0 : 0xFFFF7060);
                g.strokeCircle(cx, y, r, 1.2f * dp);
                g.color(0xFFFFFFFF);
                g.text(k == 0 ? "P" : k == 1 ? "W" : "T", cx, y + r * 0.42f, r * 1.15f, Gfx.ALIGN_CENTER, true);
            }
        }
    }

    /** Which networks of a kind (water, phones) still run: their works are working. */
    private boolean[] network(int kind) {
        int n = 0;
        for (Region.Facility f : w.r.facilities) if (f.kind == kind) n = Math.max(n, f.network + 1);
        boolean[] on = new boolean[n];
        for (Region.Facility f : w.r.facilities) if (f.kind == kind && f.network >= 0 && w.strat.online(f.id)) on[f.network] = true;
        return on;
    }

    private static final int[] SUPPLY_COL = {0xFFE0C040, 0xFF60C060, 0xFFC08040};

    /** Ammunition, food and fuel at each place that keeps them, as bars. */
    private void supplies(Gfx g, Camera cam) {
        float bw = 5 * dp, bh = 20 * dp, gap = 2 * dp;
        for (Region.Facility f : w.r.facilities) {
            float tot = f.ammo + f.food + f.fuel;
            if (tot <= 0 && f.kind != Region.F_POLICE && f.kind != Region.F_BASE) continue;
            float x = cam.toScreenX(f.x), y = cam.toScreenY(f.y);
            if (x < -30 * dp || y < -30 * dp || x > cam.sw + 30 * dp || y > cam.sh + 30 * dp) continue;
            float pw = bw * 3 + gap * 4, ph = bh + gap * 2;
            if (!place(x - pw / 2, y - ph, x + pw / 2, y)) continue;
            g.color(0xD0000000);
            g.fillRoundRect(x - pw / 2, y - ph, pw, ph, 3 * dp);
            for (int k = 0; k < 3; k++) {
                float v = k == 0 ? f.ammo : k == 1 ? f.food : f.fuel;
                float h = (float) Math.min(1, Math.log10(1 + v) / 5) * bh;
                g.color(0x40FFFFFF);
                g.fillRect(x - pw / 2 + gap + k * (bw + gap), y - gap - bh, bw, bh);
                g.color(SUPPLY_COL[k]);
                g.fillRect(x - pw / 2 + gap + k * (bw + gap), y - gap - h, bw, Math.max(1, h));
            }
            if (!w.strat.online(f.id)) {
                g.color(0xFFFF4040);
                g.line(x - pw / 2, y - ph, x + pw / 2, y, 2 * dp);
            }
        }
    }

    /** A key for the overlay at (x, y), as wide as wdt; returns its height. */
    public float legend(Gfx g, float x, float y, float d, float wdt) {
        String[] lines;
        int[] cols;
        switch (overlay) {
            case OV_INFECTION: lines = new String[]{"Infection", "the dead (counted)", "911 call: the dead there", "army called in"}; cols = new int[]{0, 0xFFC81E1E, 0xFFFF4848, 0xFFE0A030}; break;
            case OV_UTIL: lines = new String[]{"Power (P), water (W), phones (T)", "working", "lost", "dark: no power"}; cols = new int[]{0, 0xFF3A9A48, 0xFF8A2020, 0xFF000000}; break;
            case OV_SUPPLY: lines = new String[]{"Stocks at each depot", "ammunition", "food", "fuel"}; cols = new int[]{0, SUPPLY_COL[0], SUPPLY_COL[1], SUPPLY_COL[2]}; break;
            case OV_TRUST: lines = new String[]{"Public trust " + (int) w.gov.trust + "%", "calm", "fear in the streets"}; cols = new int[]{0, 0x30F08020, 0xFFF08020}; break;
            case OV_FORCES: lines = new String[]{"Forces and fronts", "police", "army", "front line"}; cols = new int[]{0, 0xFF3B6FD8, 0xFF8A8250, 0xFFFFD040}; break;
            case OV_DEATHS: lines = new String[]{"Where they died", "a few", "up to " + people((int) heat.max)}; cols = new int[]{0, 0x509030C0, 0xFF9030C0}; break;
            case OV_PEAK: lines = new String[]{"Most of the dead at once", "hundreds", "up to " + people((int) heat.max)}; cols = new int[]{0, 0xFFFFA020, 0xFFFF3010}; break;
            default: return 0;
        }
        float lh = 17 * d, pad = 8 * d;
        float h = lines.length * lh + pad;
        g.color(0xD8101318);
        g.fillRoundRect(x, y, wdt, h, 8 * d);
        for (int k = 0; k < lines.length; k++) {
            float ly = y + pad / 2 + (k + 1) * lh - 5 * d;
            if (cols[k] != 0) {
                g.color(cols[k] == 0xFF000000 ? 0xFF000000 : cols[k]);
                g.fillRoundRect(x + 8 * d, ly - 9 * d, 10 * d, 10 * d, 2 * d);
                g.color(0x60FFFFFF);
                g.strokeRect(x + 8 * d, ly - 9 * d, 10 * d, 10 * d, 1);
            }
            g.color(k == 0 ? 0xFFFFFFFF : 0xFFD0D0D0);
            String s = Ui.fit(g, lines[k], wdt - (cols[k] != 0 ? 30 : 16) * d, 12 * d, k == 0);
            g.text(s, x + (cols[k] != 0 ? 24 : 8) * d, ly, 12 * d, Gfx.ALIGN_LEFT, k == 0);
        }
        return h;
    }

    static void facilityIcon(Gfx g, int kind, float x, float y, boolean on, float s) {
        int bg;
        switch (kind) {
            case Region.F_POLICE: bg = 0xFF2B4E9A; break;
            case Region.F_HOSPITAL: bg = 0xFFE8E8E8; break;
            case Region.F_POWER: bg = 0xFFE0B020; break;
            case Region.F_FUEL: bg = 0xFFB07030; break;
            case Region.F_BASE: bg = 0xFF6E6A3A; break;
            case Region.F_ARMORY: bg = 0xFF7A7448; break;
            case Region.F_PRISON: bg = 0xFF706860; break;
            case Region.F_FIRE: bg = 0xFFB83020; break;
            case Region.F_FOB: bg = 0xFF7A7A48; break;
            case Region.F_WATER: bg = 0xFF3070C0; break;
            case Region.F_PHONE: bg = 0xFF7A60B0; break;
            default: bg = 0xFF707880;
        }
        if (!on) bg = 0xFF5A3030;
        g.color(0xE0000000);
        g.fillCircle(x, y, s * 1.16f);
        g.color(bg);
        g.fillCircle(x, y, s);
        g.color(kind == Region.F_HOSPITAL ? 0xFFD02020 : 0xFFFFFFFF);
        switch (kind) {
            case Region.F_HOSPITAL:
                g.fillRect(x - s * 0.55f, y - s * 0.18f, s * 1.1f, s * 0.36f);
                g.fillRect(x - s * 0.18f, y - s * 0.55f, s * 0.36f, s * 1.1f);
                break;
            case Region.F_POWER: {
                float[] p = BOLT;
                for (int i = 0; i < 6; i++) {
                    BOLT_XY[i * 2] = x + p[i * 2] * s;
                    BOLT_XY[i * 2 + 1] = y + p[i * 2 + 1] * s;
                }
                g.color(0xFF202020);
                g.fillPoly(BOLT_XY, 6);
                break;
            }
            case Region.F_POLICE:
                g.text("P", x, y + s * 0.42f, s * 1.2f, Gfx.ALIGN_CENTER, true);
                break;
            case Region.F_BASE:
            case Region.F_ARMORY:
            case Region.F_FOB:
                g.text("★", x, y + s * 0.42f, s * 1.2f, Gfx.ALIGN_CENTER, true);
                break;
            case Region.F_FUEL:
                g.text("F", x, y + s * 0.42f, s * 1.2f, Gfx.ALIGN_CENTER, true);
                break;
            case Region.F_FIRE:
                g.text("♨", x, y + s * 0.42f, s * 1.15f, Gfx.ALIGN_CENTER, true);
                break;
            case Region.F_WATER:
                g.text("W", x, y + s * 0.42f, s * 1.1f, Gfx.ALIGN_CENTER, true);
                break;
            case Region.F_PHONE:
                g.text("T", x, y + s * 0.42f, s * 1.1f, Gfx.ALIGN_CENTER, true);
                break;
            case Region.F_PRISON:
                for (int i = -1; i <= 1; i++) g.fillRect(x + i * s * 0.35f - s * 0.07f, y - s * 0.55f, s * 0.14f, s * 1.1f);
                break;
            default:
                g.fillCircle(x, y, s * 0.3f);
        }
    }

    private static final float[] BOLT = {0.15f, -0.7f, -0.4f, 0.1f, 0, 0.1f, -0.15f, 0.7f, 0.4f, -0.1f, 0, -0.1f};
    private static final float[] BOLT_XY = new float[12];

    /** People counts as they read best: 950, 12,400, 450k, 2.3M. */
    public static String people(long n) {
        if (n >= 1000000) return String.format("%.1fM", n / 1e6);
        if (n >= 100000) return (n / 1000) + "k";
        return fmt((int) n);
    }

    static String fmt(int n) {
        if (n >= 10000) return (n / 1000) + "k";
        if (n >= 1000) return (n / 1000) + "," + String.format("%03d", n % 1000);
        return Integer.toString(n);
    }

    // ------------------------------------------------------------------ soft maps

    /**
     * Something per sector as a small picture (two pixels a sector, blurred), scaled up over the map with smoothing:
     * the infection's bleed, the dark of places without power, heatmaps. Rebuilt twice a second.
     * On a national map (200,000 sectors) the whole map at two pixels a sector would be 3 MB a picture plus its
     * working arrays; instead it is a window of WIN x WIN sectors round the camera at two pixels a sector while the
     * view is that close, and the whole map at a pixel per 4x4 sectors (the strongest of them) from further out.
     */
    private final class SecMap {
        int kind; // 0 infection, 1 unlit, 2+ heatmap of an overlay
        int heatOf = -1;
        GImage img;
        int[] px;
        float[] v, tmp;
        int iw, ih;
        float builtAt = -100;
        float max;
        /** National: the picture's first sector, and sectors a pixel (0.5 = two pixels a sector). */
        int sx0, sy0;
        float spp = 0.5f;
        static final int WIN = 160, COARSE = 4;

        SecMap(int kind) {
            this.kind = kind;
        }

        void kindFor(int ov) {
            if (heatOf != ov) {
                heatOf = ov;
                builtAt = -100;
            }
        }

        /** The picture's layout for the camera: whole map (today's maps), or a window or the coarse map (national). */
        private void layoutFor(Camera cam) {
            Sectors sec = w.sec;
            int niw, nih;
            if (!w.national) {
                sx0 = sy0 = 0;
                spp = 0.5f;
                niw = sec.sw * 2;
                nih = sec.sh * 2;
            } else {
                float secPx = w.r.sect * Tile.SIZE;
                float span = Math.max(cam.sw, cam.sh) / cam.zoom / secPx;
                if (span <= WIN * 0.7f) {
                    spp = 0.5f;
                    sx0 = Math.max(0, Math.min(sec.sw - WIN, (int) (cam.cx / secPx) - WIN / 2));
                    sy0 = Math.max(0, Math.min(sec.sh - WIN, (int) (cam.cy / secPx) - WIN / 2));
                    niw = nih = WIN * 2;
                } else {
                    spp = COARSE;
                    sx0 = sy0 = 0;
                    niw = (sec.sw + COARSE - 1) / COARSE;
                    nih = (sec.sh + COARSE - 1) / COARSE;
                }
            }
            if (img == null || niw != iw || nih != ih) {
                if (img != null) img.recycle();
                iw = niw;
                ih = nih;
                px = new int[iw * ih];
                v = new float[iw * ih];
                tmp = new float[iw * ih];
                img = pf.newImage(iw, ih);
            }
        }

        /** Whether the camera has left the window the picture was built for. */
        private boolean stale(Camera cam) {
            if (!w.national) return false;
            float secPx = w.r.sect * Tile.SIZE;
            float span = Math.max(cam.sw, cam.sh) / cam.zoom / secPx;
            boolean wantWin = span <= WIN * 0.7f;
            if (wantWin != (spp < 1)) return true;
            if (!wantWin) return false;
            float cx = cam.cx / secPx - sx0, cy = cam.cy / secPx - sy0, half = span / 2;
            return cx - half < 0 && sx0 > 0 || cy - half < 0 && sy0 > 0 || cx + half > WIN && sx0 + WIN < w.sec.sw || cy + half > WIN && sy0 + WIN < w.sec.sh;
        }

        void build(Camera cam) {
            Sectors sec = w.sec;
            layoutFor(cam);
            max = 0;
            if (spp >= 1) java.util.Arrays.fill(v, 0);
            int s0 = 0, s1 = sec.n;
            for (int s = s0; s < s1; s++) {
                int ssx = s % sec.sw, ssy = s / sec.sw;
                if (spp < 1) {
                    // a window: only its sectors
                    if (ssy < sy0) {
                        s = sy0 * sec.sw + sx0 - 1;
                        continue;
                    }
                    if (ssy >= sy0 + iw / 2) break;
                    if (ssx < sx0) {
                        s = ssy * sec.sw + sx0 - 1;
                        continue;
                    }
                    if (ssx >= sx0 + iw / 2) {
                        s = (ssy + 1) * sec.sw + sx0 - 1;
                        continue;
                    }
                } else if (sec.home[s] <= 0 && sec.zIn[s] <= 0 && w.sp.secZ[s] <= 0 && kind < 2) continue;
                float val = 0;
                switch (kind) {
                    case 0: {
                        int zz = sec.zIn[s] + w.sp.secZ[s];
                        val = zz <= 0 ? 0 : Math.min(0.8f, (float) Math.log10(1 + zz) * 0.19f);
                        break;
                    }
                    case 1:
                        val = sec.home[s] > 0 && !w.strat.lit(s) ? 1 : 0;
                        break;
                    default:
                        if (heatOf == OV_TRUST) val = sec.home[s] > 0 ? Math.min(1, sec.panic[s]) : 0;
                        else if (heatOf == OV_DEATHS) {
                            float d = w.director.deaths[w.director.cell(s)] + sec.bodies[s];
                            max = Math.max(max, d);
                            val = d <= 0 ? 0 : Math.min(1, 0.2f + (float) Math.log10(1 + d) * 0.2f);
                        } else if (heatOf == OV_PEAK) {
                            float p = w.director.peak[w.director.cell(s)];
                            max = Math.max(max, p);
                            val = p <= 0 ? 0 : Math.min(1, 0.15f + (float) Math.log10(1 + p) * 0.2f);
                        } else if (heatOf == OV_UTIL) val = sec.home[s] > 0 && !w.strat.lit(s) ? 0.8f : 0;
                }
                if (spp >= 1) {
                    // the coarse map: the strongest of each 4x4 sectors
                    int q = (ssy / COARSE) * iw + ssx / COARSE;
                    if (val > v[q]) v[q] = val;
                    continue;
                }
                int x = (ssx - sx0) * 2, y = (ssy - sy0) * 2;
                v[y * iw + x] = v[y * iw + x + 1] = v[(y + 1) * iw + x] = v[(y + 1) * iw + x + 1] = val;
            }
            blur();
            blur();
            for (int i = 0; i < px.length; i++) px[i] = colour(v[i]);
            img.setPixels(px, iw, ih);
            builtAt = time;
        }

        /** A 3x3 box blur. */
        void blur() {
            for (int y = 0; y < ih; y++)
                for (int x = 0; x < iw; x++) {
                    float s = 0;
                    int n = 0;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= iw) continue;
                        s += v[y * iw + xx];
                        n++;
                    }
                    tmp[y * iw + x] = s / n;
                }
            for (int y = 0; y < ih; y++)
                for (int x = 0; x < iw; x++) {
                    float s = 0;
                    int n = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= ih) continue;
                        s += tmp[yy * iw + x];
                        n++;
                    }
                    v[y * iw + x] = s / n;
                }
        }

        int colour(float a) {
            if (a <= 0.004f) return 0;
            switch (kind) {
                case 0: return MapPainter.alpha(0xFFC81E1E, a);
                case 1: return MapPainter.alpha(0xFF000005, a);
                default:
                    if (heatOf == OV_TRUST) return MapPainter.alpha(0xFFF08020, a * 0.75f);
                    if (heatOf == OV_DEATHS) return MapPainter.alpha(0xFF9030C0, a * 0.85f);
                    if (heatOf == OV_PEAK) return MapPainter.alpha(MapPainter.mix(0xFFFFB020, 0xFFFF2010, Math.min(1, a * 1.3f)), a * 0.85f);
                    return MapPainter.alpha(0xFF000005, a);
            }
        }

        void draw(Gfx g, Camera cam, float alpha) {
            if (time - builtAt > 0.5f || time < builtAt || stale(cam)) build(cam);
            float cs = w.r.sect * Tile.SIZE, pxW = cs * spp;
            g.image(img, cam.toScreenX(sx0 * cs), cam.toScreenY(sy0 * cs), iw * pxW * cam.zoom, ih * pxW * cam.zoom, alpha);
        }
    }

    /**
     * Where people and the dead stand thickest, from the 64 px grid, as a soft glow under the dots at the middle
     * zooms: so a crowd of hundreds reads as a crowd, not as a speckle. A window of cells round the view.
     */
    private final class CrowdMap {
        static final int N = 224;
        GImage img;
        final int[] px = new int[N * N];
        int ox, oy; // the window's first cell
        float builtAt = -100;

        void build(Camera cam) {
            Spatial sp = w.sp;
            if (img == null) img = pf.newImage(N, N);
            int ccx = (int) (cam.cx / Spatial.CELL), ccy = (int) (cam.cy / Spatial.CELL);
            ox = ccx - N / 2;
            oy = ccy - N / 2;
            for (int y = 0; y < N; y++)
                for (int x = 0; x < N; x++) {
                    int cx = ox + x, cy = oy + y;
                    int c = 0;
                    if (cx >= 0 && cy >= 0 && cx < sp.cw && cy < sp.ch) {
                        int cell = sp.cell(cx, cy);
                        int nz = sp.zEnd[cell] - sp.zStart[cell], nl = sp.lEnd[cell] - sp.lStart[cell];
                        int n = nz + nl;
                        if (n > 1) {
                            float a = Math.min(0.8f, 0.12f + (float) Math.sqrt(n) * 0.11f);
                            c = MapPainter.alpha(MapPainter.mix(0xFFE8D4A8, 0xFFB01E1E, nz / (float) n), a);
                        }
                    }
                    px[y * N + x] = c;
                }
            img.setPixels(px, N, N);
            builtAt = time;
        }

        void draw(Gfx g, Camera cam) {
            if (time - builtAt > 0.2f || time < builtAt || Math.abs(cam.cx / Spatial.CELL - (ox + N / 2)) > N / 4 || Math.abs(cam.cy / Spatial.CELL - (oy + N / 2)) > N / 4) build(cam);
            float a = cam.zoom > 0.45f ? (0.6f - cam.zoom) / 0.15f : 1;
            float cpx = Spatial.CELL;
            g.image(img, cam.toScreenX(ox * cpx), cam.toScreenY(oy * cpx), N * cpx * cam.zoom, N * cpx * cam.zoom, a * 0.85f);
        }
    }
}
