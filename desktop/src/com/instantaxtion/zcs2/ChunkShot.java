package com.instantaxtion.zcs2;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Street-level pictures of generated chunks (ChunkGen) on a Country-size nation, drawn by MapPainter exactly as the
 * game draws streets: the chunks round a place are stitched into a small Region (tiles, buildings, the road edges that
 * cross it) with one chunk of margin, and the painter's 32-tile pictures are laid side by side.
 *   java ... ChunkShot outDir [seed] [level 1-3] [chunks across]
 */
public final class ChunkShot {
    public static void main(String[] a) throws Exception {
        File out = new File(a.length > 0 ? a[0] : "build/chunks");
        out.mkdirs();
        long seed = a.length > 1 ? Long.parseLong(a[1]) : 3;
        int lvl = a.length > 2 ? Integer.parseInt(a[2]) : 1;
        int across = a.length > 3 ? Integer.parseInt(a[3]) : 4;
        Nation n = NationGen.generate(28672, RegionGen.COAST, seed, 2);
        System.out.println(NationShot.summary(n));
        Map<String, int[]> places = places(n);
        for (Map.Entry<String, int[]> e : places.entrySet()) {
            int[] p = e.getValue();
            BufferedImage img = shot(n, p[0], p[1], across, across * 3 / 4 + 1, lvl, e.getKey());
            File f = new File(out, e.getKey() + ".png");
            ImageIO.write(img, "png", f);
            System.out.println(f + "  (chunk " + p[0] + "," + p[1] + ")");
        }
    }

    /** Named places worth looking at, as the chunk at their middle. */
    static Map<String, int[]> places(Nation n) {
        Map<String, int[]> m = new java.util.LinkedHashMap<String, int[]>();
        Region.Settlement cap = n.capital();
        m.put("01_capital_core", new int[]{cap.cx / 64, cap.cy / 64});
        m.put("02_dense_residential", best(n, cap.id, Nation.D_DENSE, true));
        m.put("03_suburb", best(n, cap.id, Nation.D_SUBURB, false));
        m.put("04_industrial", best(n, -1, Nation.D_INDUSTRIAL, false));
        Region.Settlement town = null, village = null;
        for (Region.Settlement s : n.settlements) {
            if (s.kind == Region.S_TOWN && (town == null || s.residents > town.residents)) town = s;
            if (s.kind == Region.S_VILLAGE && (village == null || s.residents > village.residents)) village = s;
        }
        if (town != null) m.put("05_town_centre", new int[]{town.cx / 64, town.cy / 64});
        if (village != null) m.put("06_village_farms", new int[]{village.cx / 64, village.cy / 64});
        // a bridge: an avenue over water in a city
        RoadGraph g = n.roads;
        float bestLen = 0;
        int[] bridge = null;
        for (int e = 0; e < g.edgeCount; e++) {
            if (!g.ebridge[e] || g.ekind[e] == RoadGraph.HIGHWAY) continue;
            float mx = (g.nx[g.ea[e]] + g.nx[g.eb[e]]) / 32, my = (g.ny[g.ea[e]] + g.ny[g.eb[e]]) / 32;
            int s = n.sector((int) mx, (int) my);
            if (!n.built(s) || n.settlements.get(n.secSettlement[s]).kind != Region.S_CITY) continue;
            if (!nearRiver(n, mx, my)) continue;
            float len = g.elen[e];
            if (len > bestLen && len < 64 * 16) {
                bestLen = len;
                bridge = new int[]{(int) mx / 64, (int) my / 64};
            }
        }
        if (bridge != null) m.put("07_river_bridge", bridge);
        m.put("08_coast", coast(n));
        for (Region.Settlement s : n.settlements) {
            if (s.kind == Region.S_BASE && !m.containsKey("09_army_base")) m.put("09_army_base", new int[]{s.cx / 64, s.cy / 64});
            if (s.kind == Region.S_AIRPORT) m.put("10_airport", new int[]{s.cx / 64, s.cy / 64 + 1});
        }
        m.put("11_highway_interchange", interchange(n));
        return m;
    }

    static boolean nearRiver(Nation n, float x, float y) {
        for (int r = 0; r < n.rivers.size(); r++) {
            float[] p = n.rivers.get(r);
            float h = n.riverWidth.get(r) / 2 + 10;
            for (int i = 0; i < p.length; i += 2) if ((p[i] - x) * (p[i] - x) + (p[i + 1] - y) * (p[i + 1] - y) < h * h) return true;
        }
        return false;
    }

    /** The sector of district type d (in settlement set, or any city) with the most like it round about. */
    static int[] best(Nation n, int set, int d, boolean dense) {
        int bs = -1;
        float bv = -1;
        for (int s = 0; s < n.sw * n.sw; s++) {
            if (n.district[s] != d || !n.built(s)) continue;
            int st = n.secSettlement[s];
            if (set >= 0 ? st != set : n.settlements.get(st).kind != Region.S_CITY) continue;
            int x = s % n.sw, y = s / n.sw, same = 0;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++) {
                    int q = (y + dy) * n.sw + x + dx;
                    if (q >= 0 && q < n.sw * n.sw && n.district[q] == d) same++;
                }
            float v = same + (dense ? n.density[s] * 0.5f : 0) + Rng.unit(1, s, 0) * 0.1f;
            if (v > bv) {
                bv = v;
                bs = s;
            }
        }
        return new int[]{bs % n.sw, bs / n.sw};
    }

    static int[] coast(Nation n) {
        int bs = -1;
        float bv = -1;
        for (int s = 0; s < n.sw * n.sw; s++) {
            int w = n.sum(Nation.SUM_WATER, s), deep = n.sum(Nation.SUM_DEEP, s);
            if (w < 50 || w > 190) continue;
            int x = s % n.sw, y = s / n.sw, sea = 0;
            for (int dy = -12; dy <= 12; dy += 2)
                for (int dx = -12; dx <= 12; dx += 2) {
                    int q = (y + dy) * n.sw + x + dx;
                    if (q >= 0 && q < n.sw * n.sw) sea += n.sum(Nation.SUM_DEEP, q);
                }
            if (sea < 255 * 20) continue;
            float v = (n.built(s) ? 20 + n.density[s] : 0) + Rng.unit(2, s, 0);
            if (v > bv) {
                bv = v;
                bs = s;
            }
        }
        return new int[]{bs % n.sw, bs / n.sw};
    }

    static int[] interchange(Nation n) {
        RoadGraph g = n.roads;
        for (int node = 0; node < g.nodeCount; node++) {
            int hw = 0, av = 0;
            for (int k = g.adjStart[node]; k < g.adjStart[node + 1]; k++) {
                int e = g.adjEdge[k];
                if (g.ekind[e] == RoadGraph.HIGHWAY) hw++;
                else if (g.ekind[e] == RoadGraph.AVENUE && g.elen[e] > 24 * 16) av++;
            }
            int s = n.sector((int) (g.nx[node] / 16), (int) (g.ny[node] / 16));
            if (hw >= 2 && av >= 1 && n.built(s) && n.settlements.get(n.secSettlement[s]).kind == Region.S_CITY && n.density[s] >= 6)
                return new int[]{s % n.sw, s / n.sw};
        }
        return new int[]{n.capital().cx / 64 + 8, n.capital().cy / 64};
    }

    /** cw x ch chunks centred on chunk (ccx, ccy), at painter level lvl. */
    static BufferedImage shot(Nation n, int ccx, int ccy, int cw, int ch, int lvl, String title) {
        int c0x = ccx - cw / 2 - 1, c0y = ccy - ch / 2 - 1, W = (cw + 2) * 64, H = (ch + 2) * 64;
        Region r = stitch(n, c0x, c0y, cw + 2, ch + 2);
        MapPainter mp = new MapPainter(r, new DesktopPlatform(new File(System.getProperty("java.io.tmpdir"))), 64);
        int ppt = MapPainter.PPT[lvl];
        int iw = cw * 64 * ppt, ih = ch * 64 * ppt;
        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        for (int py = 2; py < H / 32 - 2; py++)
            for (int px = 2; px < W / 32 - 2; px++) {
                GImage gi = mp.chunk(px, py, lvl, true);
                g.drawImage(((DesktopGfx.Img) gi).img, (px - 2) * 32 * ppt, (py - 2) * 32 * ppt, null);
            }
        // buildings as WorldView draws them flat: soft shadows by height, then roofs (pitched or flat)
        float z = ppt / 16f;
        for (int pass = 0; pass < 2; pass++)
            for (int b = 0; b < r.bCount; b++) {
                float sx = (r.bx[b] - 64) * ppt, sy = (r.by[b] - 64) * ppt, sw = r.bw[b] * ppt, sh = r.bh[b] * ppt;
                if (sx > iw || sy > ih || sx + sw < -60 || sy + sh < -60) continue;
                float height = Math.min(60, r.bfloors[b]) * 3.2f + 4;
                if (pass == 0) {
                    float off = height * 0.32f * z;
                    g.setColor(new Color(0, 0, 0, 0x22));
                    g.fill(new java.awt.geom.Rectangle2D.Float(sx + off * 0.5f, sy + off * 0.65f, sw + off * 0.5f, sh + off * 0.5f));
                    g.setColor(new Color(0, 0, 0, 0x26));
                    g.fill(new java.awt.geom.Rectangle2D.Float(sx + off * 0.25f, sy + off * 0.35f, sw + off * 0.3f, sh + off * 0.3f));
                    continue;
                }
                int roof = r.broof[b], kind = r.bkind[b];
                boolean pitched = kind == Region.B_HOUSE || kind == Region.B_FARMHOUSE || kind == Region.B_BARN || kind == Region.B_CHURCH;
                g.setColor(new Color(MapPainter.shade(roof, 0.8f)));
                g.fill(new java.awt.geom.Rectangle2D.Float(sx, sy, sw, sh));
                if (pitched) {
                    boolean wide = sw >= sh;
                    g.setColor(new Color(MapPainter.shade(roof, 1.08f)));
                    if (wide) g.fill(new java.awt.geom.Rectangle2D.Float(sx + 1, sy + 1, sw - 2, sh / 2 - 1));
                    else g.fill(new java.awt.geom.Rectangle2D.Float(sx + 1, sy + 1, sw / 2 - 1, sh - 2));
                    g.setColor(new Color(MapPainter.shade(roof, 0.86f)));
                    if (wide) g.fill(new java.awt.geom.Rectangle2D.Float(sx + 1, sy + sh / 2, sw - 2, sh / 2 - 1));
                    else g.fill(new java.awt.geom.Rectangle2D.Float(sx + sw / 2, sy + 1, sw / 2 - 1, sh - 2));
                } else {
                    // lighter the taller, with a parapet and roof plant on big roofs
                    g.setColor(new Color(MapPainter.shade(roof, 0.95f + Math.min(25, r.bfloors[b]) * 0.008f)));
                    g.fill(new java.awt.geom.Rectangle2D.Float(sx + 1.5f, sy + 1.5f, sw - 3, sh - 3));
                    if (r.bw[b] >= 4 && r.bh[b] >= 4) {
                        g.setColor(new Color(MapPainter.shade(roof, 0.72f)));
                        g.fill(new java.awt.geom.Rectangle2D.Float(sx + sw * 0.3f, sy + sh * 0.3f, Math.max(2, sw * 0.18f), Math.max(2, sh * 0.14f)));
                    }
                }
            }
        // chunk seams, faintly
        g.setColor(new Color(255, 255, 255, 40));
        for (int k = 1; k < cw; k++) g.drawLine(k * 64 * ppt, 0, k * 64 * ppt, ih);
        for (int k = 1; k < ch; k++) g.drawLine(0, k * 64 * ppt, iw, k * 64 * ppt);
        // what's there
        int sc = ccy * n.sw + ccx, set = n.secSettlement[sc];
        int buildings = 0, people = 0;
        int[] floors = new int[61];
        for (int y = 1; y <= ch; y++)
            for (int x = 1; x <= cw; x++) {
                Chunk c = ChunkGen.generate(n, c0x + x, c0y + y);
                buildings += c.bCount;
                people += c.residents();
                for (int b = 0; b < c.bCount; b++) floors[c.bfloors[b]]++;
            }
        int med = 0, seen = 0;
        for (int f = 0; f < 61; f++) {
            seen += floors[f];
            if (seen * 2 >= buildings) {
                med = f;
                break;
            }
        }
        int maxF = 0;
        for (int f = 0; f < 61; f++) if (floors[f] > 0) maxF = f;
        String label = title.substring(3).replace('_', ' ') + ": " + (set >= 0 ? n.settlements.get(set).name + ", " : "") + Nation.DISTRICT_NAMES[n.district[sc]]
                + " density " + n.density[sc] + ". " + cw + "x" + ch + " chunks (" + cw * 256 + " m across): " + buildings + " buildings, "
                + people + " residents, floors median " + med + " max " + maxF;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, 0, iw, 26);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g.drawString(label, 8, 19);
        g.dispose();
        return img;
    }

    /** A Region of w x h chunks from chunk (c0x, c0y): their tiles, buildings and the road edges crossing them. */
    static Region stitch(Nation n, int c0x, int c0y, int w, int h) {
        Region r = new Region(w * 64, h * 64, n.seed, n.preset);
        int X0 = c0x * 64, Y0 = c0y * 64;
        List<Integer> nats = new ArrayList<Integer>();
        for (int cy = 0; cy < h; cy++)
            for (int cx = 0; cx < w; cx++) {
                int gx = c0x + cx, gy = c0y + cy;
                if (gx < 0 || gy < 0 || gx >= n.sw || gy >= n.sw) continue;
                Chunk c = ChunkGen.generate(n, gx, gy);
                for (int y = 0; y < 64; y++)
                    for (int x = 0; x < 64; x++) {
                        r.raw(cx * 64 + x, cy * 64 + y, c.tile[y * 64 + x]);
                        int v = c.bld[y * 64 + x];
                        if (v < 0 && !nats.contains(-v - 1)) nats.add(-v - 1);
                    }
                for (int b = 0; b < c.bCount; b++)
                    r.addBuilding(cx * 64 + c.bx[b], cy * 64 + c.by[b], c.bw[b], c.bh[b], c.bkind[b], c.bfloors[b], c.settlement, c.bres[b], c.broof[b]);
            }
        for (int b : nats) {
            int x0 = Math.max(0, n.bx[b] - X0), y0 = Math.max(0, n.by[b] - Y0);
            int x1 = Math.min(r.W - 1, n.bx[b] + n.bw[b] - 1 - X0), y1 = Math.min(r.H - 1, n.by[b] + n.bh[b] - 1 - Y0);
            if (x1 >= x0 && y1 >= y0) r.addBuilding(x0, y0, x1 - x0 + 1, y1 - y0 + 1, n.bkind[b], n.bfloors[b], n.bset[b], 0, n.broof[b]);
        }
        RoadGraph g = n.roads;
        Map<Integer, Integer> nodeMap = new HashMap<Integer, Integer>();
        java.util.Set<Integer> done = new java.util.HashSet<Integer>();
        for (int cy = 0; cy < h; cy++)
            for (int cx = 0; cx < w; cx++) {
                int gx = c0x + cx, gy = c0y + cy;
                if (gx < 0 || gy < 0 || gx >= n.sw || gy >= n.sw) continue;
                int c = gy * g.chunkW + gx;
                for (int k = g.chunkEdgeStart[c]; k < g.chunkEdgeStart[c + 1]; k++) {
                    int e = g.chunkEdge[k];
                    if (!done.add(e)) continue;
                    int a = node(r, g, nodeMap, g.ea[e], X0, Y0), b = node(r, g, nodeMap, g.eb[e], X0, Y0);
                    int ne = r.roads.addEdge(a, b, g.ekind[e], g.ehalf[e]);
                    if (ne >= 0) r.roads.ebridge[ne] = g.ebridge[e];
                }
            }
        r.roads.finish(r.px());
        r.rebuildNav(0, 0, r.W - 1, r.H - 1);
        return r;
    }

    private static int node(Region r, RoadGraph g, Map<Integer, Integer> m, int id, int X0, int Y0) {
        Integer v = m.get(id);
        if (v == null) {
            v = r.roads.addNode(g.nx[id] - X0 * 16, g.ny[id] - Y0 * 16, g.nset[id]);
            m.put(id, v);
        }
        return v;
    }
}
