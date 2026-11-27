package com.instantaxtion.zcs2;

import java.io.OutputStream;
import java.util.Locale;

/**
 * Exports a stretch of a replay as pictures: numbered PNG frames at a chosen size and frame rate, and an animated
 * GIF of the same frames. It runs on a background thread (the game shows its progress and can cancel it) and
 * renders from a fork of the replay ({@link Replay#fork}): a world of its own, loaded from the replay's snapshot
 * before the stretch and played on with the replay's inputs. The replay being watched, its world and the live game
 * are never touched, so the replay's hashes stay as they were. The camera is the replay's camera path when it
 * follows one, else the view as it was when the export began. Nothing here needs a screen: the frames are drawn
 * into an off-screen picture through Gfx and encoded in plain Java, so it runs headless.
 */
final class ClipExport implements Runnable {
    /** The choices the clip sheet offers. */
    static final float[] SPAN_MIN = {1, 5, 30, 180, 720};
    static final String[] SPAN_NAMES = {"1 min", "5 min", "30 min", "3 h", "12 h"};
    static final int[] SECONDS = {3, 6, 12};
    static final int[] FPS = {10, 15, 24};
    static final int[] WIDTHS = {360, 540, 720, 1080};
    /** Height over width: wide (16:9), square, or the shape of the map on the screen (0). */
    static final float[] SHAPES = {9 / 16f, 1, 0};
    static final String[] SHAPE_NAMES = {"Wide", "Square", "Screen"};

    static final int LOADING = 0, SEEKING = 1, RENDERING = 2, DONE = 3, FAILED = 4, CANCELLED = 5;

    private final Platform pf;
    private final Replay src;
    private final Replay.Snap snap;
    final long t0, t1;
    final int width, height, fps, frames;
    final boolean gif;
    /** A fixed camera {cx, cy, zoom at the clip's size}, or null to follow the path. */
    private final float[] fixed;
    private final Replay.CamPath path = new Replay.CamPath();
    /** The path's zoom is for the screen: times this for the clip's size. */
    private final float pathScale;
    private final float dp;
    private final int overlay;
    private final boolean names, unitNames, lean3d, blood;
    final String folder;

    volatile int phase = LOADING;
    volatile int framesDone;
    volatile float progress;
    volatile boolean cancel;
    volatile String error;
    /** The fork's state hash after the last frame (for the tests: rendering mustn't change what the replay does). */
    volatile long endHash;
    volatile long endTick;

    /**
     * Sets up an export (on the game's thread: the snapshot and the camera path are read here, not later). from and
     * to are replay ticks; cam is the view the player has (its centre and zoom, the screen's width it was for).
     */
    ClipExport(Platform pf, Replay src, long from, long to, int width, int height, int fps, int seconds, boolean gif,
               float camX, float camY, float camZoom, int screenW, boolean followPath, float dp, WorldView look) {
        this.pf = pf;
        this.src = src;
        t0 = Math.max(0, Math.min(from, src.endTick));
        t1 = Math.max(t0, Math.min(to, src.endTick));
        this.width = Math.max(16, width & ~1);
        this.height = Math.max(16, height & ~1);
        this.fps = fps;
        frames = Math.max(1, seconds * fps);
        this.gif = gif;
        snap = src.nearest(t0);
        float scale = this.width / (float) Math.max(1, screenW);
        pathScale = scale;
        if (followPath && src.path.size() > 0) {
            fixed = null;
            path.keys.addAll(src.path.keys);
            path.ticks.addAll(src.path.ticks);
        } else fixed = new float[]{camX, camY, camZoom * scale};
        this.dp = Math.max(1f, dp * scale);
        overlay = look != null ? look.overlay : WorldView.OV_INFECTION;
        names = look == null || look.names;
        unitNames = look == null || look.unitNames;
        lean3d = look == null || look.lean3d;
        blood = look == null || look.blood;
        folder = "clip_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
    }

    static String frameName(int k) {
        return String.format(Locale.US, "frame_%04d.png", k + 1);
    }

    /** What it's doing, for the progress bar. */
    String status() {
        switch (phase) {
            case LOADING: return "Loading the replay's snapshot...";
            case SEEKING: return "Getting to the start... " + (int) (progress * 100) + "%";
            case RENDERING: return "Frame " + framesDone + " of " + frames;
            case DONE: return "Exported " + frames + (frames == 1 ? " frame" : " frames") + (gif ? " and a GIF" : "");
            case CANCELLED: return "Cancelled";
            default: return "Couldn't export it: " + error;
        }
    }

    boolean finished() {
        return phase >= DONE;
    }

    /** The whole share done, 0..1: getting there counts for a fifth, the frames for the rest. */
    float overall() {
        if (phase == LOADING) return 0;
        if (phase == SEEKING) return progress * 0.2f;
        if (phase == RENDERING) return 0.2f + 0.8f * framesDone / frames;
        return 1;
    }

    public void run() {
        Replay f = null;
        GImage img = null;
        OutputStream gifOut = null;
        try {
            f = src.fork(snap);
            phase = SEEKING;
            long from = f.w.tick;
            while (f.w.tick < t0 && !f.done()) {
                if (cancel) throw new InterruptedException();
                f.step();
                progress = (f.w.tick - from) / (float) Math.max(1, t0 - from);
            }
            phase = RENDERING;
            img = pf.newImage(width, height);
            Gfx g = img.gfx();
            int[] px = new int[width * height];
            WorldView view = new WorldView(f.w, pf);
            view.offline = true;
            view.overlay = overlay;
            view.names = names;
            view.unitNames = unitNames;
            view.lean3d = lean3d;
            view.blood = blood;
            Camera cam = new Camera();
            cam.setScreen(width, height, f.w.r.px());
            cam.dp = dp;
            GifEncoder enc = null;
            if (gif) {
                gifOut = pf.openClip(folder, "clip.gif");
                enc = new GifEncoder(gifOut, width, height);
            }
            for (int k = 0; k < frames; k++) {
                long tk = frames == 1 ? t0 : t0 + (t1 - t0) * k / (frames - 1);
                while (f.w.tick < tk && !f.done()) {
                    if (cancel) throw new InterruptedException();
                    f.step();
                }
                if (cancel) throw new InterruptedException();
                if (fixed != null) {
                    cam.cx = fixed[0];
                    cam.cy = fixed[1];
                    cam.zoom = fixed[2];
                } else {
                    float[] p = path.at(f.w.tick);
                    cam.cx = p[0];
                    cam.cy = p[1];
                    cam.zoom = p[2] * pathScale;
                }
                // the first frame paints every picture in view (on a national map the chunks arrive from a
                // background thread, so it waits a little for them); later ones only what the camera newly sees
                int passes = k == 0 ? 6 : 1;
                for (int p = 0; p < passes; p++) {
                    view.draw(g, cam, p == passes - 1 ? 1f / fps : 0);
                    if (k == 0 && f.w.r.nation != null) Thread.sleep(40);
                }
                img.getPixels(px);
                OutputStream o = pf.openClip(folder, frameName(k));
                try {
                    PngEncoder.write(px, width, height, o);
                } finally {
                    o.close();
                }
                if (enc != null) enc.addFrame(px, 100.0 / fps);
                framesDone = k + 1;
            }
            if (enc != null) {
                enc.finish();
                gifOut.close();
                gifOut = null;
            }
            endHash = Audit.hash(f.w);
            endTick = f.w.tick;
            phase = DONE;
        } catch (InterruptedException e) {
            phase = CANCELLED;
        } catch (Throwable t) {
            error = t.getMessage() != null ? t.getMessage() : t.toString();
            phase = FAILED;
        } finally {
            if (gifOut != null) {
                try {
                    gifOut.close();
                } catch (Exception ignored) {
                }
            }
            if (img != null) img.recycle();
            if (f != null && f.w != null) f.w.shutdown();
        }
    }

    /** Starts it on a background thread (low priority: the game keeps drawing). */
    void start() {
        Thread t = new Thread(this, "clip");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }
}
