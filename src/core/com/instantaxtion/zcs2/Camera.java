package com.instantaxtion.zcs2;

/**
 * One continuous camera from a street corner to the whole region. zoom = screen px per world px. The interface's
 * bars cover the screen's edges (insets): "the whole country" fits the free area between them, and the camera keeps
 * the map inside that area.
 */
public final class Camera {
    public float cx, cy, zoom = 0.5f;
    public int sw = 1, sh = 1;
    public float minZoom = 0.02f, maxZoom = 3.2f;
    /** Screen pixels per dp: what's drawn on the map (people, symbols, labels) is sized in dp so it reads on any phone. */
    public float dp = 1;
    /** The screen edges covered by the interface, in pixels. */
    public float insetL, insetT, insetR, insetB;
    private float flyX, flyY, flyZ;
    private float flyT;
    private float worldPx = 1;
    public int follow = -1; // entity followed
    public int followVehicle = -1;

    public void setScreen(int w, int h, float worldPx) {
        sw = Math.max(1, w);
        sh = Math.max(1, h);
        this.worldPx = worldPx;
        minZoom = fitZoom() * 0.92f;
        if (zoom < minZoom) zoom = minZoom;
    }

    public void setInsets(float l, float t, float r, float b) {
        insetL = l;
        insetT = t;
        insetR = r;
        insetB = b;
    }

    /** The zoom at which the whole map just fits the free area between the bars. */
    public float fitZoom() {
        float fw = Math.max(sw * 0.3f, sw - insetL - insetR), fh = Math.max(sh * 0.3f, sh - insetT - insetB);
        return Math.min(fw, fh) / worldPx;
    }

    /** The world point at the middle of the free area, for a camera centre and zoom. */
    private float freeOffX(float z) {
        return (insetL + (sw - insetL - insetR) / 2f - sw / 2f) / z;
    }

    private float freeOffY(float z) {
        return (insetT + (sh - insetT - insetB) / 2f - sh / 2f) / z;
    }

    /** Flies to show the whole map in the free area. */
    public void showAll() {
        float z = fitZoom();
        flyTo(worldPx / 2f - freeOffX(z), worldPx / 2f - freeOffY(z), z);
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
        flyT = 2;
    }

    public void update(float dt, float worldPx) {
        this.worldPx = worldPx;
        if (flyT > 0) {
            // ease in log-zoom (a long zoom feels as quick at the far end as the near one), and land exactly
            float k = Math.min(1, dt * 4.5f);
            cx += (flyX - cx) * k;
            cy += (flyY - cy) * k;
            float lz = (float) Math.log(zoom), lt = (float) Math.log(flyZ);
            zoom = (float) Math.exp(lz + (lt - lz) * k);
            flyT -= dt;
            boolean close = Math.abs(flyX - cx) * zoom < 1 && Math.abs(flyY - cy) * zoom < 1 && Math.abs(lt - lz) < 0.004f;
            if (close || flyT <= 0) {
                cx = flyX;
                cy = flyY;
                zoom = flyZ;
                flyT = 0;
            }
        }
        // keep the map in the free area: centred there when it's smaller than the area
        float ox = freeOffX(zoom), oy = freeOffY(zoom);
        float halfW = (sw - insetL - insetR) / 2f / zoom, halfH = (sh - insetT - insetB) / 2f / zoom;
        float m = worldPx;
        float fx = cx + ox, fy = cy + oy;
        fx = halfW * 2 >= m ? m / 2 : Math.max(halfW, Math.min(m - halfW, fx));
        fy = halfH * 2 >= m ? m / 2 : Math.max(halfH, Math.min(m - halfH, fy));
        cx = fx - ox;
        cy = fy - oy;
    }

    public boolean flying() {
        return flyT > 0;
    }
}
