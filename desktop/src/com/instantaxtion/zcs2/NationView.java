package com.instantaxtion.zcs2;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Drives the real WorldView and MapPainter over a real-scale nation (NationGen) with a chunk cache, as the game will
 * draw it: the five zooms (country, region, district, town, street) on a phone-sized screen, a smooth zoom from the
 * whole country down to a street corner, frame times at each zoom and the cost of painting district pictures.
 *   java ... NationView outDir [width height density] [seed] [size]
 */
public final class NationView {
    static int W, H;
    static float density;
    static WorldView view;
    static Camera cam;
    static ChunkCache cache;
    static Nation n;

    public static void main(String[] a) throws Exception {
        File out = new File(a.length > 0 ? a[0] : "build/nationview");
        out.mkdirs();
        W = a.length > 1 ? Integer.parseInt(a[1]) : 1080;
        H = a.length > 2 ? Integer.parseInt(a[2]) : 2400;
        density = a.length > 3 ? Float.parseFloat(a[3]) : 2.75f;
        long seed = a.length > 4 ? Long.parseLong(a[4]) : 3;
        int size = a.length > 5 ? Integer.parseInt(a[5]) : 28672;
        long t0 = System.currentTimeMillis();
        n = NationGen.generate(size, RegionGen.COAST, seed, 2);
        System.out.println(NationShot.summary(n) + " (" + (System.currentTimeMillis() - t0) + " ms)");
        DesktopPlatform pf = new DesktopPlatform(new File(System.getProperty("java.io.tmpdir"), "nationview"));
        pf.density = density;
        cache = new ChunkCache(n, ChunkCache.capacityFor(pf.maxMemory()));
        long v0 = System.nanoTime();
        view = new WorldView(NationPainter.source(n, cache), pf);
        System.out.printf("view: country picture %dx%d and tint built in %.0f ms%n", view.np.countryImage.width(), view.np.countryImage.height(), (System.nanoTime() - v0) / 1e6);
        cam = new Camera();
        cam.dp = density;
        cam.setScreen(W, H, n.px());
        districtCost();
        long p0 = System.nanoTime();
        int[] pv = NationPainter.preview(n, 768);
        BufferedImage pimg = new BufferedImage(768, 768, BufferedImage.TYPE_INT_ARGB);
        pimg.setRGB(0, 0, 768, 768, pv, 0, 768);
        System.out.printf("New Game preview from the coarse layer: %.0f ms%n", (System.nanoTime() - p0) / 1e6);
        save(out, "0_preview", pimg);

        Region.Settlement cap = n.capital();
        Region.Settlement town = null;
        for (Region.Settlement s : n.settlements)
            if (s.kind == Region.S_TOWN && (town == null || s.residents > town.residents)) town = s;
        float capX = cap.cx * 16f, capY = cap.cy * 16f;
        float[][] zooms = {
                {cam.minZoom, n.px() / 2f, n.px() / 2f},
                {0.0065f, capX, capY},
                {0.035f, capX, capY},
                {0.11f, town.cx * 16f, town.cy * 16f},
                {0.5f, capX + 300, capY + 200},
        };
        String[] names = {"1_country", "2_region", "3_district", "4_town", "5_street"};
        System.out.println("zoom          frame ms (median of 20, everything painted)   pictures painted on the way");
        for (int i = 0; i < zooms.length; i++) {
            place(zooms[i][0], zooms[i][1], zooms[i][2]);
            int[] painted = settle();
            double ms = frameMs(20);
            System.out.printf("%-10s %.4f  %6.1f ms   (district %d, street %d, chunks made %d)%n", names[i], cam.zoom, ms, painted[0], painted[1], painted[2]);
            save(out, names[i], frame(0));
        }
        // the smooth zoom: the whole country down to a street corner of the capital, every frame fully painted
        float z0 = cam.minZoom, z1 = 0.6f;
        int steps = 48;
        BufferedImage sheet = new BufferedImage(8 * W / 6, 6 * H / 6, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = sheet.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        File seq = new File(out, "zoom");
        seq.mkdirs();
        for (int k = 0; k < steps; k++) {
            float u = k / (steps - 1f);
            float z = (float) (z0 * Math.pow(z1 / z0, u));
            // the centre glides from the map's middle onto the capital as the zoom closes in
            float c = (float) Math.min(1, Math.pow(u * 1.6f, 0.7f));
            place(z, n.px() / 2f + (capX + 300 - n.px() / 2f) * c, n.px() / 2f + (capY + 200 - n.px() / 2f) * c);
            settle();
            BufferedImage img = frame(0);
            ImageIO.write(img, "png", new File(seq, String.format("z%02d_%.4f.png", k, cam.zoom)));
            sg.drawImage(img, (k % 8) * W / 6, (k / 8) * H / 6, W / 6, H / 6, null);
            sg.setColor(Color.WHITE);
            sg.setFont(new Font("SansSerif", Font.BOLD, 14));
            sg.drawString(String.format("%.4f", cam.zoom), (k % 8) * W / 6 + 4, (k / 8) * H / 6 + 16);
        }
        sg.dispose();
        ImageIO.write(sheet, "png", new File(out, "zoom_sheet.png"));
        flight(out, capX + 300, capY + 200);
        // the bands where one level fades into the next, side by side at the same place
        transitions(out, capX + 300, capY + 200);
        System.out.printf("chunk cache: %d chunks, %d made, %.1f MB; district pictures %.1f MB%n", cache.size(), cache.generated, cache.memoryBytes() / 1048576.0,
                view.np.cacheBytes() / 1048576.0);
        cache.shutdown();
    }

    /** The cost of painting district pictures at each level, over districts with towns in them and without. */
    static void districtCost() {
        NationPainter np = view.np;
        Region.Settlement cap = n.capital();
        int cdx = cap.cx / NationPainter.DISTRICT, cdy = cap.cy / NationPainter.DISTRICT;
        for (int round = 0; round < 4; round++) // the first rounds warm the JIT up; the last is reported
        for (int lvl = 0; lvl < NationPainter.DTPP.length; lvl++) {
            int tpp = NationPainter.DTPP[lvl], side = NationPainter.DISTRICT / tpp;
            // warm up, then time the capital's 5x5 districts and 25 spread over the country
            long t = System.nanoTime();
            int cnt = 0;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++) {
                    np.paint((cdx + dx) * 512, (cdy + dy) * 512, tpp, side);
                    cnt++;
                }
            double city = (System.nanoTime() - t) / 1e6 / cnt;
            t = System.nanoTime();
            cnt = 0;
            for (int k = 0; k < 25; k++) {
                np.paint((k * 7 % np.dw) * 512, (k * 13 % np.dw) * 512, tpp, side);
                cnt++;
            }
            double country = (System.nanoTime() - t) / 1e6 / cnt;
            if (round == 3) System.out.printf("district picture %dx%d (%d tiles a pixel): %.2f ms in the capital, %.2f ms elsewhere%n", side, side, tpp, city, country);
        }
    }

    static void place(float z, float x, float y) {
        cam.zoom = Math.max(cam.minZoom, z);
        cam.cx = x;
        cam.cy = y;
        cam.update(0, n.px());
    }

    /** Draws frames until nothing more is painted or made; returns the pictures painted and chunks made on the way. */
    static int[] settle() {
        int d0 = view.np.painted, s0 = view.map.painted;
        long g0 = cache.generated;
        int quiet = 0;
        for (int f = 0; f < 400 && quiet < 3; f++) {
            int a = view.np.painted, b = view.map.painted;
            long c = cache.generated;
            frame(0.016f);
            cache.drain();
            if (view.np.painted == a && view.map.painted == b && cache.generated == c) quiet++;
            else quiet = 0;
        }
        return new int[]{view.np.painted - d0, view.map.painted - s0, (int) (cache.generated - g0)};
    }

    static double frameMs(int frames) {
        double[] t = new double[frames];
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < frames; i++) {
            Graphics2D g = img.createGraphics();
            long t0 = System.nanoTime();
            view.draw(new DesktopGfx(g, W, H), cam, 0.016f);
            t[i] = (System.nanoTime() - t0) / 1e6;
            g.dispose();
        }
        java.util.Arrays.sort(t);
        return t[frames / 2];
    }

    static BufferedImage frame(float dt) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        view.draw(new DesktopGfx(g, W, H), cam, dt);
        g.dispose();
        return img;
    }

    static void save(File out, String name, BufferedImage img) throws Exception {
        File f = new File(out, name + ".png");
        ImageIO.write(img, "png", f);
        System.out.println("  " + f);
    }

    /** A flight from a street corner of the capital to the farthest town, as the camera flies it at 30 frames a second. */
    static void flight(File out, float x, float y) throws Exception {
        place(0.5f, x, y);
        settle();
        Region.Settlement far = null;
        float best = 0;
        for (Region.Settlement s : n.settlements) {
            float d = (s.cx * 16f - x) * (s.cx * 16f - x) + (s.cy * 16f - y) * (s.cy * 16f - y);
            if (s.kind == Region.S_TOWN && d > best) {
                best = d;
                far = s;
            }
        }
        cam.flyTo(far.cx * 16f, far.cy * 16f, 0.4f);
        BufferedImage sheet = new BufferedImage(8 * W / 6, 2 * H / 6, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = sheet.createGraphics();
        float minZ = 9, t = 0;
        int f = 0, shot = 0;
        double worst = 0;
        while (cam.flying() && f < 400) {
            cam.update(1 / 30f, n.px());
            minZ = Math.min(minZ, cam.zoom);
            long t0 = System.nanoTime();
            BufferedImage img = frame(1 / 30f);
            worst = Math.max(worst, (System.nanoTime() - t0) / 1e6);
            if (f % 7 == 0 && shot < 16) {
                sg.drawImage(img, (shot % 8) * W / 6, (shot / 8) * H / 6, W / 6, H / 6, null);
                sg.setColor(Color.WHITE);
                sg.drawString(String.format("%.2fs z %.4f", f / 30f, cam.zoom), (shot % 8) * W / 6 + 4, (shot / 8) * H / 6 + 14);
                shot++;
            }
            f++;
        }
        settle();
        sg.dispose();
        save(out, "flight_sheet", sheet);
        save(out, "flight_end", frame(0));
        System.out.printf("flight of %.0f km from a street corner: %.2f s, lowest zoom %.4f, slowest frame %.0f ms (painting on the way)%n",
                Math.sqrt(best) / 16 / Nation.KM, f / 30f, minZ, worst);
    }

    /** Crops of the screen's middle on either side of each band and inside it, at the same place. */
    static void transitions(File out, float x, float y) throws Exception {
        float[][] bands = {
                {0.0098f, 0.0112f, 0.0122f, 0.0132f, 0.0145f},
                {0.0185f, 0.0205f, 0.043f, 0.047f, 0.06f},
                {0.19f, 0.205f, 0.22f, 0.245f, 0.27f},
        };
        String[] names = {"t1_country_district", "t2_district_levels", "t3_district_street"};
        int cw = W / 2, ch = H / 4;
        for (int b = 0; b < bands.length; b++) {
            BufferedImage strip = new BufferedImage(cw * bands[b].length, ch + 24, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = strip.createGraphics();
            for (int k = 0; k < bands[b].length; k++) {
                place(bands[b][k], x, y);
                settle();
                BufferedImage img = frame(0);
                g.drawImage(img.getSubimage((W - cw) / 2, (H - ch) / 2, cw, ch), k * cw, 24, null);
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 18));
                g.drawString(String.format("zoom %.4f", bands[b][k]), k * cw + 6, 19);
            }
            g.dispose();
            save(out, names[b], strip);
        }
    }
}
