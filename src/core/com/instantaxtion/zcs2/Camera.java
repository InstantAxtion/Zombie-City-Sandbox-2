package com.instantaxtion.zcs2;

/** One continuous camera from a street corner to the whole region. zoom = screen px per world px. */
public final class Camera {
    public float cx, cy, zoom = 0.5f;
    public int sw = 1, sh = 1;
    public float minZoom = 0.02f, maxZoom = 3.2f;
    private float flyX, flyY, flyZ;
    private float flyT;
    public int follow = -1; // entity followed
    public int followVehicle = -1;

    public void setScreen(int w, int h, float worldPx) {
        sw = Math.max(1, w);
        sh = Math.max(1, h);
        minZoom = Math.min(sw, sh) / worldPx * 0.95f;
        if (zoom < minZoom) zoom = minZoom;
    }

    public float toScreenX(float wx) {
        return (wx - cx) * zoom + sw / 2f;
    }

    public float toScreenY(float wy) {
        return (wy - cy) * zoom + sh / 2f;
    }

    public float toWorldX(float sx) {
        return (sx - sw / 2f) / zoom + cx;
    }

    public float toWorldY(float sy) {
        return (sy - sh / 2f) / zoom + cy;
    }

    public float x0() {
        return cx - sw / 2f / zoom;
    }

    public float y0() {
        return cy - sh / 2f / zoom;
    }

    public float x1() {
        return cx + sw / 2f / zoom;
    }

    public float y1() {
        return cy + sh / 2f / zoom;
    }

    /** Zooms by factor about a screen point (pinch, wheel). */
    public void zoomAt(float factor, float sx, float sy) {
        float wx = toWorldX(sx), wy = toWorldY(sy);
        zoom = Math.max(minZoom, Math.min(maxZoom, zoom * factor));
        cx = wx - (sx - sw / 2f) / zoom;
        cy = wy - (sy - sh / 2f) / zoom;
        flyT = 0;
    }

    public void pan(float dxScreen, float dyScreen) {
        cx -= dxScreen / zoom;
        cy -= dyScreen / zoom;
        flyT = 0;
        follow = -1;
        followVehicle = -1;
    }

    /** Glides to a place (and zoom) over about a second. */
    public void flyTo(float x, float y, float z) {
        flyX = x;
        flyY = y;
        flyZ = Math.max(minZoom, Math.min(maxZoom, z));
        flyT = 1;
    }

    public void update(float dt, float worldPx) {
        if (flyT > 0) {
            float k = Math.min(1, dt * 4);
            cx += (flyX - cx) * k;
            cy += (flyY - cy) * k;
            zoom += (flyZ - zoom) * k;
            flyT -= dt;
            if (Math.abs(flyX - cx) < 2 && Math.abs(flyY - cy) < 2 && Math.abs(flyZ - zoom) < 0.001f) flyT = 0;
        }
        float halfW = sw / 2f / zoom, halfH = sh / 2f / zoom;
        float m = worldPx;
        cx = Math.max(Math.min(halfW, m / 2), Math.min(Math.max(m - halfW, m / 2), cx));
        cy = Math.max(Math.min(halfH, m / 2), Math.min(Math.max(m - halfH, m / 2), cy));
    }

    public boolean flying() {
        return flyT > 0;
    }
}
