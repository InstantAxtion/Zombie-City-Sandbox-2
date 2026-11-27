package com.instantaxtion.zcs2;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Tests of the guided first game (Tutorial: every step driven by scripted taps, nothing left behind) and of exporting
 * a replay as a clip (the GIF encoder read back by javax.imageio, the PNG frames, the replay left exactly as it was).
 */
final class GuideTests {
    private GuideTests() {
    }

    static void check(boolean ok, String what) {
        Tests.check(ok, what);
    }

    static void define() {
        Tests.add("clip: the GIF encoder's frames decode back exactly with javax.imageio", new Tests.T() {
            public void run() throws Exception {
                gifRoundTrip();
            }
        });
        Tests.add("clip: exporting a replay's stretch leaves the replay's hashes as they were", new Tests.T() {
            public void run() throws Exception {
                clipKeepsReplay();
            }
        });
        Tests.add("tutorial: scripted taps go through every step, and it leaves nothing behind", new Tests.T() {
            public void run() throws Exception {
                tutorialThrough();
            }
        });
        Tests.add("tutorial: first-time tips show once per tool, and can be turned off", new Tests.T() {
            public void run() throws Exception {
                tutorialTips();
            }
        });
    }

    // ------------------------------------------------------------------ the GIF encoder

    /**
     * Every frame of a GIF as it is shown, ARGB, read by the JDK's own decoder: each frame's rectangle drawn over the
     * picture so far where it isn't transparent (the encoder's frames leave the one before in place); also the delays.
     */
    static int[][] decodeGif(byte[] gif, int w, int h, java.util.List<Integer> delays) throws Exception {
        ImageReader rd = ImageIO.getImageReadersByFormatName("gif").next();
        ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(gif));
        rd.setInput(in, false);
        int n = rd.getNumImages(true);
        int[][] out = new int[n][];
        int[] canvas = new int[w * h];
        for (int k = 0; k < n; k++) {
            BufferedImage img = rd.read(k);
            org.w3c.dom.Node root = rd.getImageMetadata(k).getAsTree("javax_imageio_gif_image_1.0");
            int fx = 0, fy = 0;
            String disposal = "none";
            for (org.w3c.dom.Node c = root.getFirstChild(); c != null; c = c.getNextSibling()) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) c;
                if (c.getNodeName().equals("ImageDescriptor")) {
                    fx = Integer.parseInt(e.getAttribute("imageLeftPosition"));
                    fy = Integer.parseInt(e.getAttribute("imageTopPosition"));
                } else if (c.getNodeName().equals("GraphicControlExtension")) {
                    disposal = e.getAttribute("disposalMethod");
                    if (delays != null) delays.add(Integer.parseInt(e.getAttribute("delayTime")));
                }
            }
            check(disposal.equals("doNotDispose"), "frame " + k + " disposal " + disposal);
            int fw = img.getWidth(), fh = img.getHeight();
            check(fx >= 0 && fy >= 0 && fx + fw <= w && fy + fh <= h, "frame " + k + " is outside the picture");
            if (k == 0) check(fw == w && fh == h, "the first frame isn't whole");
            int[] px = img.getRGB(0, 0, fw, fh, null, 0, fw);
            for (int y = 0; y < fh; y++)
                for (int x = 0; x < fw; x++) {
                    int c = px[y * fw + x];
                    if ((c >>> 24) != 0) canvas[(fy + y) * w + fx + x] = c | 0xFF000000;
                }
            out[k] = canvas.clone();
        }
        rd.dispose();
        return out;
    }

    static void gifRoundTrip() throws Exception {
        Random rnd = new Random(7);
        // frames of 255 colours or fewer come back exactly: an odd size, a gradient, noise that fills the LZW table
        // (and so needs a clear code), two colours, one colour, the same again, a square moving over a still
        // picture, and new colours the first frame hadn't (a table of their own)
        int w = 211, h = 157, n = 11;
        int[][] frames = new int[n][w * h];
        int[] pal = new int[255];
        for (int k = 0; k < 255; k++) pal[k] = 0xFF000000 | rnd.nextInt(1 << 24);
        for (int i = 0; i < w * h; i++) {
            int x = i % w, y = i / w;
            frames[0][i] = pal[(x + y) % 255];
            frames[1][i] = pal[rnd.nextInt(255)];
            frames[2][i] = (x / 8 + y / 8) % 2 == 0 ? 0xFF000000 : 0xFFFFFFFF;
            frames[3][i] = 0xFF336699;
            frames[4][i] = pal[(x * x + y * 3) % 37];
            frames[5][i] = frames[4][i];
            for (int f = 6; f <= 8; f++) {
                int sx = 30 + (f - 6) * 9;
                frames[f][i] = x >= sx && x < sx + 20 && y >= 50 && y < 70 ? 0xFFE0453A : frames[4][i];
            }
            frames[9][i] = x > 100 && y > 100 ? 0xFF000000 | ((x * 7 + y) % 200) << 8 : frames[8][i];
            frames[10][i] = rnd.nextInt(9) == 0 ? pal[rnd.nextInt(255)] : pal[(x / 20) * 7 % 255];
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        GifEncoder enc = new GifEncoder(bo, w, h);
        for (int[] f : frames) enc.addFrame(f, 100.0 / 15);
        enc.finish();
        java.util.List<Integer> delays = new java.util.ArrayList<Integer>();
        int[][] back = decodeGif(bo.toByteArray(), w, h, delays);
        check(back.length == n, back.length + " frames, not " + n);
        for (int k = 0; k < n; k++)
            for (int i = 0; i < w * h; i++)
                check((back[k][i] & 0xFFFFFF) == (frames[k][i] & 0xFFFFFF), "frame " + k + " pixel " + i + ": "
                        + Integer.toHexString(back[k][i]) + " for " + Integer.toHexString(frames[k][i]));
        int sum = 0;
        for (int d : delays) sum += d;
        check(delays.size() == n && Math.abs(sum - n * 100 / 15.0) <= 1, "delays " + delays);
        // a still picture with a little moving in it costs little a frame: only the changed rectangle is kept
        bo.reset();
        enc = new GifEncoder(bo, w, h);
        enc.addFrame(frames[0], 10);
        int first = bo.size();
        for (int f = 0; f < 20; f++) {
            int[] fr = frames[0].clone();
            for (int y = 60; y < 70; y++) for (int x = 10 + f * 5; x < 20 + f * 5; x++) fr[y * w + x] = 0xFFFFFFFF;
            enc.addFrame(fr, 10);
        }
        enc.finish();
        check(bo.size() < first + 20 * 600, "20 frames of a small moving square took " + (bo.size() - first) + " bytes");
        int[][] moved = decodeGif(bo.toByteArray(), w, h, null);
        check((moved[20][65 * w + 10 + 19 * 5] & 0xFFFFFF) == 0xFFFFFF && (moved[20][65 * w + 12] & 0xFFFFFF) == (frames[0][65 * w + 12] & 0xFFFFFF),
                "the moving square came back wrong");
        // frames with thousands of colours: the median cut keeps them close, and changes in them follow
        int[] smooth = new int[w * h], smooth2 = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int x = i % w, y = i / w;
            smooth[i] = 0xFF000000 | (x * 255 / w) << 16 | (y * 255 / h) << 8 | ((x + y) * 255 / (w + h));
            smooth2[i] = x < w / 2 ? smooth[i] : 0xFF000000 | (y * 255 / h) << 16 | (x * 255 / w) << 8 | 40;
        }
        smooth[w * h / 2] = 0xFFFF0000; // one vivid dot
        bo.reset();
        enc = new GifEncoder(bo, w, h);
        enc.addFrame(smooth, 10);
        enc.addFrame(smooth2, 10);
        enc.finish();
        int[][] got = decodeGif(bo.toByteArray(), w, h, null);
        int[][] want = {smooth, smooth2};
        for (int f = 0; f < 2; f++) {
            long err = 0;
            int worst = 0;
            for (int i = 0; i < w * h; i++)
                for (int sh = 0; sh <= 16; sh += 8) {
                    int e = Math.abs(((got[f][i] >> sh) & 0xFF) - ((want[f][i] >> sh) & 0xFF));
                    err += e;
                    worst = Math.max(worst, e);
                }
            double mean = err / (3.0 * w * h);
            check(mean < 8 && worst <= 48, "frame " + f + ": the colours are too far off: mean " + mean + ", worst " + worst);
        }
        check(back[2][0] == 0xFF000000 && back[3][5] == 0xFF336699, "colours");
        // many-colour footage over many frames (the lossy coder and still pixels): the error doesn't build up
        bo.reset();
        enc = new GifEncoder(bo, w, h);
        int[] cur = null;
        for (int f = 0; f < 16; f++) {
            cur = smooth.clone();
            for (int i = 0; i < w * h; i++) {
                int x = i % w, y = i / w, dx = x - 20 - f * 10, dy = y - 70;
                if (dx * dx + dy * dy < 400) cur[i] = 0xFF000000 | (200 - dx * dx / 4) << 16 | 40 << 8 | (60 + f * 8);
                if ((x + f) % 17 == 0) cur[i] = cur[i] ^ 0x030303; // shimmer
            }
            enc.addFrame(cur, 10);
        }
        enc.finish();
        int[][] film = decodeGif(bo.toByteArray(), w, h, null);
        long err = 0;
        for (int i = 0; i < w * h; i++)
            for (int sh = 0; sh <= 16; sh += 8) err += Math.abs(((film[15][i] >> sh) & 0xFF) - ((cur[i] >> sh) & 0xFF));
        check(err / (3.0 * w * h) < 8, "the last of 16 many-colour frames is off by " + err / (3.0 * w * h));
    }

    // ------------------------------------------------------------------ clip export

    static void clipKeepsReplay() throws Exception {
        Content content = Tests.content();
        World w = Tests.seekSession(768, 93);
        long end = w.tick, finalHash = Audit.hash(w);
        Replay r = Replay.of(content, w, 2);
        r.runTo(end / 2);
        long at = r.w.tick, hashBefore = Audit.hash(r.w);
        int snapsBefore = r.snaps.size();
        File dir = new File("build/test-clips");
        UiShot.deleteAll(dir);
        DesktopPlatform pf = new DesktopPlatform(dir);
        // a still camera over the outbreak, then the same stretch along a camera path
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                r.path.add(at, Setup.originX, Setup.originY, 0.8f);
                r.path.add(at + 900, Setup.originX + 400, Setup.originY + 100, 0.4f);
            }
            ClipExport ce = new ClipExport(pf, r, at, at + 900, 120, 68, 4, 2, true, Setup.originX, Setup.originY, 0.7f, 540, pass == 1, 2f, null);
            ce.run();
            check(ce.phase == ClipExport.DONE, "the export didn't finish: " + ce.status());
            check(ce.frames == 8 && ce.framesDone == 8, "frames " + ce.framesDone + " of " + ce.frames);
            // the replay is where it was, unchanged
            check(r.w.tick == at && Audit.hash(r.w) == hashBefore, "exporting moved or changed the replay");
            check(r.snaps.size() == snapsBefore, "exporting added snapshots to the replay");
            // the frames: numbered PNGs that read back at the size asked for; the GIF has them all
            File fd = pf.clipDir(ce.folder);
            BufferedImage last = null;
            for (int k = 0; k < ce.frames; k++) {
                File f = new File(fd, ClipExport.frameName(k));
                check(f.isFile(), "no " + f);
                last = ImageIO.read(f);
                check(last != null && last.getWidth() == 120 && last.getHeight() == 68, "frame " + k + " didn't read back");
            }
            byte[] gif = java.nio.file.Files.readAllBytes(new File(fd, "clip.gif").toPath());
            int[][] gf = decodeGif(gif, 120, 68, null);
            check(gf.length == 8, "the GIF has " + gf.length + " frames");
            // the GIF's last frame is the last PNG's, within the GIF's colours
            int[] png = last.getRGB(0, 0, 120, 68, null, 0, 120);
            long err = 0;
            for (int i = 0; i < png.length; i++) err += Math.abs(((png[i] >> 8) & 0xFF) - ((gf[7][i] >> 8) & 0xFF));
            check(err / (double) png.length < 12, "the GIF's last frame isn't the last PNG: " + err / (double) png.length);
            // something was drawn (not one flat colour)
            java.util.HashSet<Integer> cols = new java.util.HashSet<Integer>();
            for (int c : png) cols.add(c);
            check(cols.size() > 20, "a flat frame: " + cols.size() + " colours");
            // the copy it rendered from ran exactly as the replay does
            Replay straight = Replay.of(content, w, 2);
            straight.runTo(ce.endTick);
            check(Audit.hash(straight.w) == ce.endHash, "rendering changed what the copy did");
            straight.w.shutdown();
            // stopped part way, it says so and leaves nothing running
            ClipExport stop = new ClipExport(pf, r, at, at + 900, 64, 64, 4, 2, false, Setup.originX, Setup.originY, 0.7f, 540, false, 2f, null);
            stop.cancel = true;
            stop.run();
            check(stop.phase == ClipExport.CANCELLED, "a cancelled export: " + stop.status());
        }
        // and the replay plays on to the end exactly as before
        r.runTo(end);
        check(Audit.hash(r.w) == finalHash, "the replay came out different after exporting from it");
        r.w.shutdown();
        w.shutdown();
    }

    // ------------------------------------------------------------------ the tutorial

    static void tutorialThrough() throws Exception {
        TutorialDriver d = new TutorialDriver(new File("build/test-tutorial"), 540, 1170, 1.5f, null);
        d.runAll();
    }

    static void tutorialTips() throws Exception {
        TutorialDriver d = new TutorialDriver(new File("build/test-tips"), 540, 1170, 1.5f, null);
        d.tips();
    }
}
