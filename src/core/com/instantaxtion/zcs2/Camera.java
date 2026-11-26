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
        arc = false;
    }

    public void pan(float dxScreen, float dyScreen) {
        cx -= dxScreen / zoom;
        cy -= dyScreen / zoom;
        flyT = 0;
        arc = false;
        follow = -1;
        followVehicle = -1;
    }

    /** Maps at least this wide (px; 8,192 tiles) are real-scale: their long flights rise and come down again. */
    public static final float ARC_PX = 8192 * 16f;
    // an arc flight: from (arcX0, arcY0, arcZ0), rising to arcZm half way, over arcDur seconds
    private float arcX0, arcY0, arcZ0, arcZm, arcU, arcDur;
    private boolean arc;

    /**
     * Glides to a place (and zoom) over about a second. On a real-scale map a flight further than the screen shows
     * rises (zooms out) so that both ends are in view half way, then comes down, taking longer the further it goes:
     * across the country from a street corner is about three seconds, and the ground passing under is always one
     * the far pictures can show.
     */
    public void flyTo(float x, float y, float z) {
        flyX = x;
        flyY = y;
        flyZ = Math.max(minZoom, Math.min(maxZoom, z));
        flyT = 2;
        arc = false;
        if (worldPx < ARC_PX) return;
        float dist = (float) Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
        float zLow = Math.min(zoom, flyZ), span = Math.min(sw, sh) * 0.8f;
        float zMid = Math.max(minZoom, Math.min(zLow, span / Math.max(1, dist)));
        float rise = (float) Math.log(zLow / zMid), change = Math.abs((float) Math.log(flyZ / zoom));
        if (rise < 0.7f && change < 2.5f) return; // a short hop: the usual glide
        arc = true;
        arcX0 = cx;
        arcY0 = cy;
        arcZ0 = zoom;
        arcZm = zMid;
        arcU = 0;
        arcDur = Math.min(3.2f, 0.8f + 0.28f * (rise * 2 + change));
        arcTable();
        flyT = arcDur + 0.5f;
    }

    /** The arc's zoom at u (0..1): log-zoom straight from end to end, minus a bump down to the low point half way. */
    private float arcZoom(float u) {
        float s = u * u * (3 - 2 * u);
        float l0 = (float) Math.log(arcZ0), l1 = (float) Math.log(flyZ), lm = (float) Math.log(arcZm);
        float mid = (l0 + l1) / 2, bump = Math.max(0, mid - lm) * 4 * s * (1 - s);
        return (float) Math.exp(l0 + (l1 - l0) * s - bump);
    }

    /** The share of the way covered by u: the ground moves at an even pace on the screen (fast high, slow low). */
    private final float[] arcWay = new float[33];

    private void arcTable() {
        float sum = 0;
        arcWay[0] = 0;
        for (int i = 1; i <= 32; i++) {
            float u = (i - 0.5f) / 32;
            sum += 1 / arcZoom(u);
            arcWay[i] = sum;
        }
        for (int i = 1; i <= 32; i++) arcWay[i] /= sum;
    }

    private void arcAt(float u) {
        zoom = arcZoom(u);
        float f = u * 32;
        int i = Math.min(31, (int) f);
        float k = arcWay[i] + (arcWay[i + 1] - arcWay[i]) * (f - i);
        cx = arcX0 + (flyX - arcX0) * k;
        cy = arcY0 + (flyY - arcY0) * k;
    }

    public void update(float dt, float worldPx) {
        this.worldPx = worldPx;
        if (flyT > 0 && arc) {
            arcU = Math.min(1, arcU + dt / arcDur);
            arcAt(arcU);
            flyT -= dt;
            if (arcU >= 1 || flyT <= 0) {
                cx = flyX;
                cy = flyY;
                zoom = flyZ;
                flyT = 0;
                arc = false;
            }
        } else if (flyT > 0) {
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
