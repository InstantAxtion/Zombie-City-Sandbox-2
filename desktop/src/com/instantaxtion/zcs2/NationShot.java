package com.instantaxtion.zcs2;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Pictures of a coarse nation (NationGen): the whole country at one pixel per 16-tile cell (cover, the district
 * raster, rivers, roads, railways, settlements with their radii), the capital at 4 tiles per pixel with every street,
 * and its centre at one tile per pixel with blocks, facilities and national buildings.
 *   java ... NationShot outDir [size] [preset] [seed]...
 */
public final class NationShot {
    static final int[] COVER = {0xFF2C5880, 0xFF3C6E9A, 0xFFD8C890, 0xFF7E9A58, 0xFF3E6337, 0xFFB4AE6A, 0xFF8A857C,
            0xFF9A8E82, 0xFF5F9A4A, 0xFF8E8A6A, 0xFF9C9C90};
    static final int[] DISTRICT = {0, 0xFF5A4A52, 0xFF7A6658, 0xFF967E68, 0xFFB8A488, 0xFF7E7E8A, 0xFF5F9A4A, 0xFFA88E70, 0xFFB4AA84};

    public static void main(String[] a) throws Exception {
        File out = new File(a.length > 0 ? a[0] : "build/nation");
        out.mkdirs();
        int size = a.length > 1 ? Integer.parseInt(a[1]) : 28672;
        int preset = a.length > 2 ? Integer.parseInt(a[2]) : RegionGen.COAST;
        long[] seeds = new long[Math.max(1, a.length - 3)];
        seeds[0] = 1;
        for (int i = 3; i < a.length; i++) seeds[i - 3] = Long.parseLong(a[i]);
        for (long seed : seeds) {
            Nation n = NationGen.generate(size, preset, seed, 2);
            String base = "nation_" + size + "_" + Data.presetName(preset).replace(' ', '_') + "_" + seed;
            ImageIO.write(country(n), "png", new File(out, base + ".png"));
            ImageIO.write(capital(n, 4, 1.25f), "png", new File(out, base + "_capital.png"));
            ImageIO.write(centre(n), "png", new File(out, base + "_centre.png"));
            System.out.println(base + ": " + summary(n));
        }
    }

    static String summary(Nation n) {
        int towns = 0, villages = 0, cities = 0;
        long townPop = 0;
        for (Region.Settlement s : n.settlements) {
            if (s.kind == Region.S_CITY) cities++;
            if (s.kind == Region.S_TOWN) {
                towns++;
                townPop += s.residents;
            }
            if (s.kind == Region.S_VILLAGE) villages++;
        }
        return String.format("%s, %d people (capital %,d), %d cities, %d towns (%,d), %d villages, roads %d junctions %d edges (level 1: %d), %d facilities, built in %d ms, %.1f MB",
                n.name, n.totalResidents(), n.capital().residents, cities, towns, townPop, villages, n.roads.nodeCount, n.roads.edgeCount,
                n.roads.l1Count, n.facilities.size(), n.buildMs, n.memoryBytes() / 1048576.0);
    }

    static BufferedImage country(Nation n) {
        int s = n.cw;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++)
            for (int x = 0; x < s; x++) {
                int c = y * s + x, k = n.cover[c];
                int col = COVER[k];
                float h = n.cellHeight(c);
                if (!Nation.wet(k)) {
                    // light from the north-west
                    float slope = n.cellHeight(Math.max(0, y - 1) * s + Math.max(0, x - 1)) - n.cellHeight(Math.min(s - 1, y + 1) * s + Math.min(s - 1, x + 1));
                    col = shade(col, 1.02f - Math.max(0, h - 0.5f) * 0.25f + Math.max(-0.2f, Math.min(0.2f, slope * 8)));
                }
                int sec = (y / 4) * n.sw + x / 4;
                if (n.built(sec) && !Nation.wet(k)) col = shade(DISTRICT[n.district[sec]], 1.15f - (n.density[sec] & 15) / 40f);
                img.setRGB(x, y, col);
            }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float k = 1f / Nation.CELL; // px per tile
        drawRivers(g, n, k, 0, 0);
        RoadGraph r = n.roads;
        // country roads, then highways on top
        for (int pass = 0; pass < 2; pass++)
            for (int e = 0; e < r.edgeCount; e++) {
                boolean hw = r.ekind[e] == RoadGraph.HIGHWAY;
                if (pass == 0 ? !(r.ekind[e] == RoadGraph.AVENUE && r.elen[e] > 70 * 16) : !hw) continue;
                g.setColor(hw ? new Color(0xE8A040) : new Color(0xE8DCA8));
                g.setStroke(new BasicStroke(hw ? 2.6f : 1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                line(g, r, e, k / 16, 0, 0);
            }
        g.setColor(new Color(0x3A2A20));
        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{4, 3}, 0));
        for (int e = 0; e < n.rails.edgeCount; e++) line(g, n.rails, e, k / 16, 0, 0);
        // settlements: their radii and names
        g.setFont(new Font("SansSerif", Font.BOLD, Math.max(11, s / 110)));
        for (Region.Settlement st : n.settlements) {
            float x = st.cx * k, y = st.cy * k, rad = st.radius * k;
            boolean big = st.kind == Region.S_CITY;
            g.setStroke(new BasicStroke(big ? 1.6f : 1f));
            g.setColor(st.kind == Region.S_CITY ? new Color(0xF0F0F0) : st.kind == Region.S_TOWN ? new Color(0xFFE0A0) : st.kind == Region.S_VILLAGE ? new Color(0xC8C8A0)
                    : new Color(0xFF6060));
            if (st.kind != Region.S_VILLAGE || rad > 2) g.draw(new Ellipse2D.Float(x - rad, y - rad, rad * 2, rad * 2));
            if (st.kind == Region.S_CITY || st.kind == Region.S_TOWN && st.residents >= 15000 || !(st.kind == Region.S_TOWN || st.kind == Region.S_VILLAGE)) {
                String label = st.name + (st.residents > 0 && (st.kind == Region.S_CITY || st.kind == Region.S_TOWN) ? " " + fmt(st.residents) : "");
                g.setColor(new Color(0, 0, 0, 150));
                g.drawString(label, x + rad + 3, y + 5);
                g.setColor(st.kind == Region.S_CITY ? Color.WHITE : st.kind == Region.S_TOWN ? new Color(0xFFF0C0) : new Color(0xFFB0B0));
                g.drawString(label, x + rad + 2, y + 4);
            }
        }
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(12, s / 90)));
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, 0, s, Math.max(18, s / 70) + 8);
        g.setColor(Color.WHITE);
        g.drawString(Data.presetName(n.preset) + " seed " + n.seed + ", " + n.size + " tiles (" + (int) (n.size / Nation.KM) + " km): " + summary(n), 6, Math.max(14, s / 90) + 4);
        g.dispose();
        return img;
    }

    static String fmt(int p) {
        return p >= 1000000 ? String.format("%.2fM", p / 1e6) : p >= 1000 ? (p / 1000) + "k" : String.valueOf(p);
    }

    static int shade(int c, float f) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * f)), gg = Math.min(255, (int) (((c >> 8) & 255) * f)), b = Math.min(255, (int) ((c & 255) * f));
        return 0xFF000000 | r << 16 | gg << 8 | b;
    }

    static void line(Graphics2D g, RoadGraph r, int e, float k, float ox, float oy) {
        g.draw(new Line2D.Float(r.nx[r.ea[e]] * k - ox, r.ny[r.ea[e]] * k - oy, r.nx[r.eb[e]] * k - ox, r.ny[r.eb[e]] * k - oy));
    }

    static void drawRivers(Graphics2D g, Nation n, float k, float ox, float oy) {
        g.setColor(new Color(COVER[Nation.C_WATER]));
        for (int i = 0; i < n.rivers.size(); i++) {
            float[] p = n.rivers.get(i);
            g.setStroke(new BasicStroke(Math.max(1f, n.riverWidth.get(i) * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int j = 2; j < p.length; j += 2) g.draw(new Line2D.Float(p[j - 2] * k - ox, p[j - 1] * k - oy, p[j] * k - ox, p[j + 1] * k - oy));
        }
    }

    /** The capital at 'tpp' tiles per pixel: cover, districts, every street at its width, facilities. */
    static BufferedImage capital(Nation n, int tpp, float margin) {
        Region.Settlement c = n.capital();
        int half = (int) (c.radius * 1.6f * margin);
        return window(n, c.cx - half, c.cy - half, half * 2, tpp, true);
    }

    static BufferedImage centre(Nation n) {
        Region.Settlement c = n.capital();
        return window(n, c.cx - 600, c.cy - 600, 1200, 1, false);
    }

    static BufferedImage window(Nation n, int x0, int y0, int side, int tpp, boolean districts) {
        int s = side / tpp;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++)
            for (int x = 0; x < s; x++) {
                int tx = x0 + x * tpp, ty = y0 + y * tpp;
                int k = n.coverAt(tx, ty), col = COVER[k];
                if (tx >= 0 && ty >= 0 && tx < n.size && ty < n.size) {
                    int sec = n.sector(tx, ty);
                    if (n.built(sec) && !Nation.wet(k))
                        col = districts ? shade(DISTRICT[n.district[sec]], 1.15f - (n.density[sec] & 15) / 40f) : 0xFFB8B0A0;
                }
                img.setRGB(x, y, col);
            }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float k = 1f / tpp, ox = x0 * k, oy = y0 * k;
        drawRivers(g, n, k, ox, oy);
        // site rectangles
        for (int i = 0; i < n.siteOpCount; i++) {
            int o = i * Nation.OP_INTS;
            int t = n.siteOps[o + 6];
            g.setColor(new Color(Tile.MAP_COLOR[t]));
            g.fillRect((int) (n.siteOps[o + 2] * k - ox), (int) (n.siteOps[o + 3] * k - oy), Math.max(1, (int) ((n.siteOps[o + 4] - n.siteOps[o + 2] + 1) * k)),
                    Math.max(1, (int) ((n.siteOps[o + 5] - n.siteOps[o + 3] + 1) * k)));
        }
        RoadGraph r = n.roads;
        for (int pass = 0; pass < 3; pass++)
            for (int e = 0; e < r.edgeCount; e++) {
                int kind = r.ekind[e];
                if (kind != pass) continue;
                g.setColor(r.ebridge[e] ? new Color(0x8A7060) : kind == RoadGraph.HIGHWAY ? new Color(0xE8A040) : kind == RoadGraph.AVENUE ? new Color(0x3A3A3E) : new Color(0x55555A));
                g.setStroke(new BasicStroke(Math.max(tpp > 2 ? 0.6f : 1f, r.ehalf[e] * 2 / 16 * k), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND));
                line(g, r, e, k / 16, ox, oy);
            }
        g.setColor(new Color(0x3A2A20));
        g.setStroke(new BasicStroke(Math.max(1f, 2 * k)));
        for (int e = 0; e < n.rails.edgeCount; e++) line(g, n.rails, e, k / 16, ox, oy);
        // national buildings and facility blocks
        for (Region.Facility f : n.facilities) {
            if (f.bx1 > f.bx0 && f.kind != Region.F_BASE && f.kind != Region.F_PRISON && f.kind < Region.F_AIRBASE) {
                g.setColor(new Color(0x80FFFFFF & 0xFFFFFF | 0x50000000, true));
                g.setStroke(new BasicStroke(1f));
                g.drawRect((int) (f.bx0 * k - ox), (int) (f.by0 * k - oy), (int) ((f.bx1 - f.bx0 + 1) * k), (int) ((f.by1 - f.by0 + 1) * k));
            }
        }
        for (int b = 0; b < n.bCount; b++) {
            g.setColor(new Color(n.broof[b]));
            g.fillRect((int) (n.bx[b] * k - ox), (int) (n.by[b] * k - oy), Math.max(1, (int) (n.bw[b] * k)), Math.max(1, (int) (n.bh[b] * k)));
        }
        for (Region.Facility f : n.facilities) {
            int col;
            switch (f.kind) {
                case Region.F_POLICE: col = 0xFF3050FF; break;
                case Region.F_HOSPITAL: col = 0xFFFFFFFF; break;
                case Region.F_FIRE: col = 0xFFFF3020; break;
                default: col = 0xFFFFE040;
            }
            g.setColor(new Color(col));
            float x = f.x / 16 * k - ox, y = f.y / 16 * k - oy, rr = Math.max(2.5f, 6 * k);
            g.fill(new Ellipse2D.Float(x - rr, y - rr, rr * 2, rr * 2));
        }
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, 0, s, 22);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString(n.capital().name + " (" + fmt(n.capital().residents) + "), " + side + " tiles = " + String.format("%.1f", side / Nation.KM) + " km across, " + tpp + " tiles per pixel", 6, 16);
        g.dispose();
        return img;
    }
}
