package com.instantaxtion.zcs2;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The game drawn on the GPU. GLSurfaceView runs its own thread: the game lives on it (frames, touches, the back
 * button, saving on pause), and the UI thread only hands things over with queueEvent. The first frame is a
 * self-test; if the GPU gets it wrong, or anything throws in the first frames, the activity switches to the
 * Canvas renderer for good.
 */
final class GLGameView extends GLSurfaceView implements GLSurfaceView.Renderer {
    interface Fallback {
        /** Called on the GL thread when the GPU renderer can't be trusted; the activity swaps views on the UI thread. */
        void gpuFailed(String why);
    }

    final Game game;
    private final GLGfx gfx = new GLGfx();
    private final Fallback fallback;
    private long last;
    private int frames;
    private int w, h;
    private volatile boolean dead;
    private boolean tested;

    GLGameView(Context ctx, Game game, Fallback fallback) {
        super(ctx);
        this.game = game;
        this.fallback = fallback;
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setKeepScreenOn(true);
    }

    // ------------------------------------------------------------------ the GL thread

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        if (dead) return;
        try {
            gfx.contextCreated();
        } catch (Throwable t) {
            fail("the GPU couldn't set up: " + t.getMessage());
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        w = width;
        h = height;
        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (dead || w <= 0 || h <= 0) return;
        if (!tested) {
            tested = true;
            boolean ok;
            try {
                ok = gfx.selfTest(w, h);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                fail("the GPU didn't draw the test pattern correctly");
                return;
            }
        }
        long now = System.nanoTime();
        float dt = last == 0 ? 1 / 60f : (now - last) / 1e9f;
        last = now;
        // the first frames are watched: an exception there means this GPU (or driver) isn't for us
        if (frames < 120) {
            frames++;
            try {
                gfx.begin(w, h);
                game.frame(gfx, dt);
                gfx.end();
                int err = GLES20.glGetError();
                if (err != GLES20.GL_NO_ERROR) fail("GL error " + err);
            } catch (Throwable t) {
                fail(t.toString());
            }
            return;
        }
        try {
            gfx.begin(w, h);
            game.frame(gfx, dt);
            gfx.end();
        } catch (Throwable t) {
            // never take the app down from a frame: the game shows what went wrong and offers the menu
            game.crashed(t);
        }
    }

    private void fail(String why) {
        if (dead) return;
        dead = true;
        fallback.gpuFailed(why);
    }

    boolean failed() {
        return dead;
    }

    // ------------------------------------------------------------------ from the UI thread

    void pauseGame() {
        last = 0;
        // save on the GL thread (where the game lives), and wait for it before the thread pauses
        final CountDownLatch done = new CountDownLatch(1);
        queueEvent(new Runnable() {
            public void run() {
                try {
                    game.onPause();
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            // the save is quick (a fraction of a second); don't hold the UI thread long if the GL thread is busy
            done.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        onPause();
    }

    void resumeGame() {
        last = 0;
        onResume();
    }

    /** The back button: the game decides on its own thread; if it says "leave", the activity finishes. */
    void back(final Runnable leave) {
        queueEvent(new Runnable() {
            public void run() {
                if (dead) return;
                if (!game.back()) post(leave);
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        int idx = e.getActionIndex();
        // copy what we need: the event is recycled once this returns
        final int n;
        final int[] ids;
        final float[] xs, ys;
        final int kind;
        final boolean reset = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_CANCEL;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                kind = Game.DOWN;
                n = 1;
                ids = new int[]{e.getPointerId(idx)};
                xs = new float[]{e.getX(idx)};
                ys = new float[]{e.getY(idx)};
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                kind = Game.UP;
                n = 1;
                ids = new int[]{e.getPointerId(idx)};
                xs = new float[]{e.getX(idx)};
                ys = new float[]{e.getY(idx)};
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_CANCEL:
                kind = action == MotionEvent.ACTION_MOVE ? Game.MOVE : Game.CANCEL;
                n = e.getPointerCount();
                ids = new int[n];
                xs = new float[n];
                ys = new float[n];
                for (int i = 0; i < n; i++) {
                    ids[i] = e.getPointerId(i);
                    xs[i] = e.getX(i);
                    ys[i] = e.getY(i);
                }
                break;
            default:
                return true;
        }
        queueEvent(new Runnable() {
            public void run() {
                if (dead) return; // the game is moving to the Canvas view, which resets on its first touch
                // the first finger (or a cancel): whatever the game thinks is still down (a lost UP) isn't
                if (reset && kind == Game.DOWN) game.resetTouches();
                for (int i = 0; i < n; i++) game.touch(kind, ids[i], xs[i], ys[i]);
                if (reset && kind == Game.CANCEL) game.resetTouches();
            }
        });
        return true;
    }
}
