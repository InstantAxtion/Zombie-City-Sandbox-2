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

    // where the land is, coarsely (landN x landN cells over the map), for framing the whole country
    private boolean[] land;
    private int landN, landCount;
    private float landCX, landCY;

    /** Tells the camera where the land is (true for a cell with land in it), for {@link #countryZoom()}. */
    public void setLand(boolean[] cells, int n) {
        land = cells;
        landN = n;
        landCount = 0;
        double sx = 0, sy = 0;
        for (int i = 0; i < n * n; i++) {
            if (!cells[i]) continue;
            landCount++;
            sx += i % n + 0.5;
            sy += i / n + 0.5;
        }
        landCX = landCount > 0 ? (float) (sx / landCount / n) : 0.5f;
        landCY = landCount > 0 ? (float) (sy / landCount / n) : 0.5f;
    }

    private float viewX, viewY;

    /**
     * The zoom for "the whole country". Sideways, or on a squarish screen, that's the whole map. On a tall screen the
     * square map would leave bands of nothing above and below it, so the zoom goes up as far as fitting the map's
     * height when that frames the land better: more of the screen is land, for a little of the country left to pan
     * to. Also leaves where to look in viewX, viewY (the free area's middle, in the world).
     */
    public float countryZoom() {
        float fw = Math.max(sw * 0.3f, sw - insetL - insetR), fh = Math.max(sh * 0.3f, sh - insetT - insetB);
        float zFit = fitZoom();
        viewX = viewY = worldPx / 2f;
        // upright only: sideways the rail and the sheets already frame the square map well enough, and cropping
        // half the country off the top and bottom would hide too much of it
        if (land == null || landCount == 0 || fh < fw * 1.1f) return zFit;
        float zTall = fh / worldPx;
        float best = zFit, bestScore = -1, bestX = viewX;
        for (int k = 0; k <= 8; k++) {
            float z = (float) (zFit * Math.pow(zTall / zFit, k / 8.0));
            float hw = Math.min(worldPx, fw / z) / worldPx / 2, hh = Math.min(worldPx, fh / z) / worldPx / 2; // in map units
            float cx = Math.max(hw, Math.min(1 - hw, landCX)), cy = Math.max(hh, Math.min(1 - hh, landCY));
            int seen = 0;
            int x0 = (int) Math.floor((cx - hw) * landN), x1 = (int) Math.ceil((cx + hw) * landN);
            int y0 = (int) Math.floor((cy - hh) * landN), y1 = (int) Math.ceil((cy + hh) * landN);
            for (int y = Math.max(0, y0); y < Math.min(landN, y1); y++)
                for (int x = Math.max(0, x0); x < Math.min(landN, x1); x++) if (land[y * landN + x]) seen++;
            // how much of the screen is land, and how much of the country is on the screen
            float screenCells = (fw / z / worldPx * landN) * (fh / z / worldPx * landN);
            float cover = Math.min(1, seen / screenCells), share = seen / (float) landCount;
            float score = cover * (float) Math.sqrt(share);
            if (score > bestScore + 1e-4f) {
                bestScore = score;
                best = z;
                bestX = cx * worldPx;
                viewY = cy * worldPx;
            }
        }
        viewX = bestX;
        return best;
    }

    /** Flies to show the whole country in the free area (see {@link #countryZoom()}); it can be panned from there. */
    public void showAll() {
        float z = countryZoom();
        flyTo(viewX - freeOffX(z), viewY - freeOffY(z), z);
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
