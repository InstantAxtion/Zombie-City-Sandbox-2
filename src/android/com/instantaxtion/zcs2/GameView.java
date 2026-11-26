package com.instantaxtion.zcs2;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

/** The game's one view: draws a frame, asks for the next, and passes touches on. All on the UI thread. */
final class GameView extends View {
    final Game game;
    private final AndroidGfx gfx = new AndroidGfx();
    private long last;
    private boolean running = true;

    GameView(Context ctx, Platform pf) {
        super(ctx);
        game = new Game(pf);
        setFocusable(true);
        setKeepScreenOn(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = last == 0 ? 1 / 60f : (now - last) / 1e9f;
        last = now;
        game.frame(gfx.set(canvas, getWidth(), getHeight()), dt);
        if (running) postInvalidateOnAnimation();
    }

    void pause() {
        running = false;
        last = 0;
    }

    void resume() {
        running = true;
        last = 0;
        postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                game.touch(Game.DOWN, e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) game.touch(Game.MOVE, e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                game.touch(Game.UP, e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < e.getPointerCount(); i++) game.touch(Game.CANCEL, e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            default:
        }
        return true;
    }
}
