package com.instantaxtion.zcs2;

/**
 * Draws the world at any zoom. Close in: every person, vehicle and shot, buildings standing up with height that
 * leans away from the screen's middle. Further out: people as dots, the ground from cached chunks. Right out:
 * the region map with the infection as a stain, hordes, refugee columns, squads as unit symbols and town names.
 */
public final class WorldView {
    private final World w;
    public final MapPainter map;
    public boolean lean3d = true, blood = true, healthBars = true, names = true;
    public Squads.Squad selected;
    public Forces.Formation selectedFormation;
    /** The map overlay shown (the national map's layers). */
    public int overlay = OV_INFECTION;
    public static final int OV_NONE = 0, OV_INFECTION = 1, OV_UTIL = 2, OV_SUPPLY = 3, OV_TRUST = 4, OV_FORCES = 5, OV_DEATHS = 6, OV_PEAK = 7;
    public static final String[] OV_NAMES = {"Plain", "Infection", "Utilities", "Supplies", "Trust and fear", "Forces and fronts", "Deaths", "Peak infection"};
    public int inspectEnt = -1, inspectVehicle = -1;
    public float brushX = -1, brushY, brushR;
    public int brushColor;
    public float time;
    private float[] pts = new float[4096];
    private final float[] poly = new float[16];

    public WorldView(World w, Platform pf) {
        this.w = w;
        map = new MapPainter(w.r, pf, 64);
    }

    public void draw(Gfx g, Camera cam, float dt) {
        time += dt;
        map.refresh();
        float z = cam.zoom;
        g.color(Tile.MAP_COLOR[Tile.DEEP]);
        g.fillRect(0, 0, g.width(), g.height());
        ground(g, cam);
        float x0 = cam.x0() - 80, y0 = cam.y0() - 80, x1 = cam.x1() + 80, y1 = cam.y1() + 80;
        if (z >= 0.3f && blood) decals(g, cam, x0, y0, x1, y1);
        if (z >= 0.3f) bodies(g, cam, x0, y0, x1, y1);
        if (z >= 0.22f) buildings(g, cam, x0, y0, x1, y1);
        nightShade(g, cam);
        if (z >= 0.22f) vehicles(g, cam, x0, y0, x1, y1, false);
        people(g, cam, x0, y0, x1, y1);
        if (z >= 0.22f) vehicles(g, cam, x0, y0, x1, y1, true);
        else vehicleDots(g, cam);
        effects(g, cam, x0, y0, x1, y1);
        overlays(g, cam);
        for (int i = 0; i < w.modules.size(); i++) w.modules.get(i).draw(g, cam, this);
        if (brushX >= 0) {
            g.color(brushColor);
            g.strokeCircle(cam.toScreenX(brushX), cam.toScreenY(brushY), brushR * z, 2);
        }
    }

    // ------------------------------------------------------------------ ground

    private void ground(Gfx g, Camera cam) {
        float z = cam.zoom;
        float worldPx = w.r.px();
        // the whole-region picture underneath everything (and on its own when far out)
        g.image(map.regionImage, cam.toScreenX(0), cam.toScreenY(0), worldPx * z, worldPx * z);
        if (z < 0.1f) return;
        int lvl = z < 0.45f ? 0 : 1;
        float chunkPx = MapPainter.CHUNK * Tile.SIZE;
        int cx0 = Math.max(0, (int) (cam.x0() / chunkPx)), cy0 = Math.max(0, (int) (cam.y0() / chunkPx));
        int n = w.r.W / MapPainter.CHUNK;
        int cx1 = Math.min(n - 1, (int) (cam.x1() / chunkPx)), cy1 = Math.min(n - 1, (int) (cam.y1() / chunkPx));
        int budget = 3;
        // paint the chunks nearest the middle first
        float mx = (cx0 + cx1) / 2f, my = (cy0 + cy1) / 2f;
        for (int ring = 0; ring <= Math.max(cx1 - cx0, cy1 - cy0); ring++)
            for (int cy = cy0; cy <= cy1; cy++)
                for (int cx = cx0; cx <= cx1; cx++) {
                    if ((int) Math.max(Math.abs(cx - mx), Math.abs(cy - my)) != ring) continue;
                    GImage img = map.chunk(cx, cy, lvl, budget > 0);
                    if (img == null && lvl == 1) img = map.chunk(cx, cy, 0, false);
                    if (img == null) continue;
                    if (map.painted > 0) {
                        budget--;
                        map.painted = 0;
                    }
                    float sx = cam.toScreenX(cx * chunkPx), sy = cam.toScreenY(cy * chunkPx);
                    g.image(img, sx, sy, chunkPx * z + 0.6f, chunkPx * z + 0.6f);
                }
    }

    private void decals(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        Effects fx = w.fx;
        float z = cam.zoom;
        g.color(0x9A6A0A0A);
        for (int k = 0; k < fx.dCount; k++) {
            float x = fx.dx[k], y = fx.dy[k];
            if (x < x0 || x > x1 || y < y0 || y > y1) continue;
            g.fillCircle(cam.toScreenX(x), cam.toScreenY(y), (2 + fx.dsize[k]) * z);
        }
    }

    private void bodies(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        Ents e = w.e;
        float z = cam.zoom;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.BODY) continue;
            float x = e.x[i], y = e.y[i];
            if (x < x0 || x > x1 || y < y0 || y > y1) continue;
            float sx = cam.toScreenX(x), sy = cam.toScreenY(y);
            int c = e.kind[i] == Ents.ZOMBIE ? 0xFF4A5A38 : e.kind[i] == Ents.COP ? 0xFF2A3F78 : e.kind[i] == Ents.SOLDIER ? 0xFF4A5532 : 0xFF8A7A66;
            g.color(c);
            float a = e.face[i];
            float dx = (float) Math.cos(a) * 4 * z, dy = (float) Math.sin(a) * 4 * z;
            g.line(sx - dx, sy - dy, sx + dx, sy + dy, 3.2f * z);
            if ((e.flags[i] & Ents.F_RISES) != 0 && e.timer[i] < 3) {
                // stirring
                g.color(0x6690C060);
                g.strokeCircle(sx, sy, 6 * z, 1);
            }
        }
    }

    // ------------------------------------------------------------------ buildings

    private void buildings(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        Region r = w.r;
        float z = cam.zoom;
        float ccx = cam.cx, ccy = cam.cy;
        boolean dark = w.daylight() < 0.5f;
        int tx0 = Math.max(0, (int) (x0 / 16) - 4), ty0 = Math.max(0, (int) (y0 / 16) - 4);
        int tx1 = Math.min(r.W - 1, (int) (x1 / 16) + 4), ty1 = Math.min(r.H - 1, (int) (y1 / 16) + 4);
        float lk = lean3d ? 0.0016f : 0;
        float maxLean = 60;
        // pass 1: walls (dark sides), pass 2: roofs
        for (int pass = 0; pass < 2; pass++) {
            for (int b = 0; b < r.bCount; b++) {
                int bx = r.bx[b], by = r.by[b];
                if (bx > tx1 || by > ty1 || bx + r.bw[b] < tx0 || by + r.bh[b] < ty0) continue;
                int floors = r.bfloors[b];
                if (floors <= 0) continue;
                float wx = bx * 16f, wy = by * 16f, ww = r.bw[b] * 16f, wh = r.bh[b] * 16f;
                float sx = cam.toScreenX(wx), sy = cam.toScreenY(wy), sw = ww * z, sh = wh * z;
                float height = Math.min(40, floors) * 3.2f + 4;
                float lx = (wx + ww / 2 - ccx) * lk * height * z, ly = (wy + wh / 2 - ccy) * lk * height * z;
                float ll = (float) Math.sqrt(lx * lx + ly * ly);
                if (ll > maxLean * z * 2) {
                    lx *= maxLean * z * 2 / ll;
                    ly *= maxLean * z * 2 / ll;
                }
                int roof = r.broof[b];
                if (pass == 0) {
                    if (!lean3d) {
                        g.color(0x50000000);
                        g.fillRect(sx + 2 * z, sy + 3 * z, sw, sh);
                        continue;
                    }
                    // shadow, then the four walls as quads between base and roof
                    g.color(0x38000000);
                    g.fillRect(sx + height * 0.25f * z, sy + height * 0.3f * z, sw, sh);
                    int wall = MapPainter.shade(roof, 0.62f);
                    wallQuad(g, sx, sy, sx + sw, sy, lx, ly, MapPainter.shade(wall, 1.1f));
                    wallQuad(g, sx + sw, sy, sx + sw, sy + sh, lx, ly, MapPainter.shade(wall, 0.85f));
                    wallQuad(g, sx, sy + sh, sx + sw, sy + sh, lx, ly, MapPainter.shade(wall, 0.75f));
                    wallQuad(g, sx, sy, sx, sy + sh, lx, ly, wall);
                } else {
                    float rx = sx + lx, ry = sy + ly;
                    g.color(roof);
                    g.fillRect(rx, ry, sw, sh);
                    if (z > 0.5f) roofDetail(g, b, rx, ry, sw, sh, z, roof);
                    g.color(MapPainter.shade(roof, 0.75f));
                    g.strokeRect(rx, ry, sw, sh, Math.max(1, z));
                    if (dark && w.strat.lit(r.sectorOfPx(wx + ww / 2, wy + wh / 2)) && r.bres[b] > 0) windows(g, b, rx, ry, sw, sh, z);
                }
            }
        }
    }

    private void wallQuad(Gfx g, float ax, float ay, float bx, float by, float lx, float ly, int c) {
        poly[0] = ax;
        poly[1] = ay;
        poly[2] = bx;
        poly[3] = by;
        poly[4] = bx + lx;
        poly[5] = by + ly;
        poly[6] = ax + lx;
        poly[7] = ay + ly;
        g.color(c);
        g.fillPoly(poly, 4);
    }

    private void roofDetail(Gfx g, int b, float x, float y, float w2, float h2, float z, int roof) {
        int k = w.r.bkind[b];
        switch (k) {
            case Region.B_HOUSE:
            case Region.B_FARMHOUSE:
                // a ridge
                g.color(MapPainter.shade(roof, 0.82f));
                if (w2 >= h2) g.line(x + 2, y + h2 / 2, x + w2 - 2, y + h2 / 2, Math.max(1, z * 1.5f));
                else g.line(x + w2 / 2, y + 2, x + w2 / 2, y + h2 - 2, Math.max(1, z * 1.5f));
                break;
            case Region.B_HOSPITAL:
                g.color(0xFFC03030);
                g.fillRect(x + w2 / 2 - 6 * z, y + h2 / 2 - 2 * z, 12 * z, 4 * z);
                g.fillRect(x + w2 / 2 - 2 * z, y + h2 / 2 - 6 * z, 4 * z, 12 * z);
                break;
            case Region.B_POLICE:
                g.color(0xFFE0E0E0);
                g.text("POLICE", x + w2 / 2, y + h2 / 2 + 3 * z, 8 * z, Gfx.ALIGN_CENTER, true);
                break;
            case Region.B_BARN:
                g.color(0xFFE8E0D0);
                g.line(x, y, x + w2, y + h2, z);
                g.line(x + w2, y, x, y + h2, z);
                break;
            default:
                // vents and units on flat roofs
                if (w2 > 30 * z && h2 > 30 * z) {
                    g.color(MapPainter.shade(roof, 0.85f));
                    int n = 1 + (b % 3);
                    for (int i = 0; i < n; i++) {
                        float px = x + w2 * (0.2f + 0.25f * i), py = y + h2 * (0.25f + 0.2f * ((b + i) % 3));
                        g.fillRect(px, py, 6 * z, 5 * z);
                    }
                }
        }
    }

    private void windows(Gfx g, int b, float x, float y, float w2, float h2, float z) {
        g.color(0xCCFFD98A);
        int nx = Math.max(1, (int) (w2 / (12 * z))), ny = Math.max(1, (int) (h2 / (12 * z)));
        for (int j = 0; j < ny; j++)
            for (int i = 0; i < nx; i++) {
                if (Rng.unit(w.r.seed, b * 31 + i, j) < 0.55f) continue;
                g.fillRect(x + (i + 0.35f) * w2 / nx, y + (j + 0.35f) * h2 / ny, 3 * z, 3 * z);
            }
    }

    // ------------------------------------------------------------------ night

    private void nightShade(Gfx g, Camera cam) {
        float d = w.daylight();
        if (d >= 0.99f) return;
        int a = (int) ((1 - d) * 120);
        g.color(a << 24 | 0x0A1030);
        g.fillRect(0, 0, g.width(), g.height());
        // places with no power are darker still
        if (d < 0.6f) {
            float cs = Region.SECT * Tile.SIZE;
            int sx0 = Math.max(0, (int) (cam.x0() / cs)), sy0 = Math.max(0, (int) (cam.y0() / cs));
            int sx1 = Math.min(w.sec.sw - 1, (int) (cam.x1() / cs)), sy1 = Math.min(w.sec.sh - 1, (int) (cam.y1() / cs));
            int a2 = (int) ((0.6f - d) * 140);
            g.color(a2 << 24);
            for (int sy = sy0; sy <= sy1; sy++)
                for (int sx = sx0; sx <= sx1; sx++) {
                    int s = sy * w.sec.sw + sx;
                    if (w.strat.lit(s)) continue;
                    g.fillRect(cam.toScreenX(sx * cs), cam.toScreenY(sy * cs), cs * cam.zoom + 1, cs * cam.zoom + 1);
                }
        }
    }

    // ------------------------------------------------------------------ people

    private void people(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        Ents e = w.e;
        float z = cam.zoom;
        if (z < 0.6f) {
            // dots, batched by colour: the dead first, units drawn over them
            float size = Math.max(1.6f, 7 * z);
            dots(g, cam, x0, y0, x1, y1, Ents.ZOMBIE, 0xFF8FB04A, size);
            dots(g, cam, x0, y0, x1, y1, Ents.CIV, 0xFFE8DCC0, size);
            dots(g, cam, x0, y0, x1, y1, Ents.COP, 0xFF4F8CFF, size * 1.25f);
            dots(g, cam, x0, y0, x1, y1, Ents.SOLDIER, 0xFF9CC060, size * 1.25f);
            if (w.playerEnt >= 0 && e.loc[w.playerEnt] == Ents.FREE) {
                g.color(0xFFFFE040);
                g.strokeCircle(cam.toScreenX(e.x[w.playerEnt]), cam.toScreenY(e.y[w.playerEnt]), 10, 2);
            }
            return;
        }
        // close: each one drawn
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < e.top; i++) {
                if (e.loc[i] != Ents.FREE) continue;
                boolean dead = e.kind[i] == Ents.ZOMBIE;
                if ((pass == 0) != dead) continue;
                float x = e.x[i], y = e.y[i];
                if (x < x0 || x > x1 || y < y0 || y > y1) continue;
                person(g, cam, i);
            }
        if (selected != null && selected.alive) {
            g.color(0xFFFFE040);
            for (int k = 0; k < selected.n; k++) {
                int m = selected.mem[k];
                if (e.loc[m] == Ents.FREE) g.strokeCircle(cam.toScreenX(e.x[m]), cam.toScreenY(e.y[m]), 9 * z, 1.5f);
            }
        }
    }

    private void dots(Gfx g, Camera cam, float x0, float y0, float x1, float y1, byte kind, int color, float size) {
        Ents e = w.e;
        int n = 0;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.FREE || e.kind[i] != kind) continue;
            float x = e.x[i], y = e.y[i];
            if (x < x0 || x > x1 || y < y0 || y > y1) continue;
            if (n * 2 + 2 > pts.length) pts = java.util.Arrays.copyOf(pts, pts.length * 2);
            pts[n * 2] = cam.toScreenX(x);
            pts[n * 2 + 1] = cam.toScreenY(y);
            n++;
        }
        if (n == 0) return;
        g.color(0xFF000000 | MapPainter.shade(color, 0.45f));
        g.points(pts, n, size + 1.2f);
        g.color(color);
        g.points(pts, n, size);
    }

    private void person(Gfx g, Camera cam, int i) {
        Ents e = w.e;
        float z = cam.zoom;
        float sx = cam.toScreenX(e.x[i]), sy = cam.toScreenY(e.y[i]);
        float rad;
        int body, head;
        int kind = e.kind[i];
        if (kind == Ents.ZOMBIE) {
            Content.ZType t = w.ztypeOf(i);
            rad = t.radius;
            body = t.color;
            head = MapPainter.shade(t.color, 0.8f);
        } else {
            Content.Unit u = w.unitOf(i);
            rad = u.radius;
            body = u.color;
            head = kind == Ents.CIV ? 0xFFE0B894 : MapPainter.shade(u.color, 0.7f);
            if (kind == Ents.CIV) body = civColor(e.uid[i]);
        }
        float a = e.face[i];
        float fx = (float) Math.cos(a), fy = (float) Math.sin(a);
        float r = rad * z;
        // shadow, shoulders, head, and for the armed, a weapon
        g.color(0x40000000);
        g.fillCircle(sx + r * 0.3f, sy + r * 0.4f, r * 1.1f);
        g.color(body);
        g.fillCircle(sx, sy, r * 1.15f);
        if (kind == Ents.COP || kind == Ents.SOLDIER || (kind == Ents.CIV && w.unitOf(i).weapon != null)) {
            g.color(0xFF202020);
            g.line(sx + fx * r * 0.3f - fy * r * 0.5f, sy + fy * r * 0.3f + fx * r * 0.5f, sx + fx * r * 2.1f - fy * r * 0.5f, sy + fy * r * 2.1f + fx * r * 0.5f, Math.max(1, r * 0.35f));
        }
        if (kind == Ents.ZOMBIE) {
            // arms out in front
            g.color(body);
            g.line(sx - fy * r * 0.7f, sy + fx * r * 0.7f, sx - fy * r * 0.7f + fx * r * 1.6f, sy + fx * r * 0.7f + fy * r * 1.6f, Math.max(1, r * 0.4f));
            g.line(sx + fy * r * 0.7f, sy - fx * r * 0.7f, sx + fy * r * 0.7f + fx * r * 1.6f, sy - fx * r * 0.7f + fy * r * 1.6f, Math.max(1, r * 0.4f));
        }
        g.color(head);
        g.fillCircle(sx + fx * r * 0.15f, sy + fy * r * 0.15f, r * 0.62f);
        if (kind == Ents.ZOMBIE && (e.flags[i] & Ents.F_HELMET) != 0) {
            g.color(0xFF3A3F44);
            g.fillCircle(sx + fx * r * 0.15f, sy + fy * r * 0.15f, r * 0.5f);
        }
        if (i == w.playerEnt) {
            g.color(0xFFFFE040);
            g.strokeCircle(sx, sy, r * 2.4f, 2);
        }
        if (healthBars && z > 0.9f && e.hp[i] < e.maxHp[i] && kind != Ents.ZOMBIE) {
            float f = Math.max(0, e.hp[i] / e.maxHp[i]);
            g.color(0xAA000000);
            g.fillRect(sx - 6 * z, sy - r - 6 * z, 12 * z, 2 * z);
            g.color(f > 0.5f ? 0xFF60D060 : f > 0.25f ? 0xFFE0C040 : 0xFFE04040);
            g.fillRect(sx - 6 * z, sy - r - 6 * z, 12 * z * f, 2 * z);
        }
        if ((e.flags[i] & Ents.F_INFECTED) != 0 && z > 0.8f) {
            g.color(0xAA80C040);
            g.strokeCircle(sx, sy, r * 1.6f, 1);
        }
        if (i == inspectEnt) {
            g.color(0xFFFFFFFF);
            g.strokeCircle(sx, sy, r * 2.6f, 1.5f);
        }
    }

    private static int civColor(int uid) {
        int[] c = {0xFFB85450, 0xFF4F7FB8, 0xFFD8C060, 0xFF6FA86A, 0xFF8E6AB0, 0xFFE09050, 0xFFDDDDDD, 0xFF555555, 0xFF46A0A0, 0xFFC07890};
        return c[(uid * 7) % c.length];
    }

    // ------------------------------------------------------------------ vehicles

    private void vehicles(Gfx g, Camera cam, float x0, float y0, float x1, float y1, boolean air) {
        Vehicles v = w.veh;
        float z = cam.zoom;
        for (int i = 0; i < v.top; i++) {
            if (v.state[i] == Vehicles.V_NONE) continue;
            Content.VType t = v.type(i);
            boolean inAir = t.flies && v.alt[i] > 0.05f;
            if (inAir != air) continue;
            float x = v.x[i], y = v.y[i];
            if (x < x0 - 200 || x > x1 + 200 || y < y0 - 200 || y > y1 + 200) continue;
            float sx = cam.toScreenX(x), sy = cam.toScreenY(y);
            g.save();
            float lift = inAir ? v.alt[i] * 26 * z : 0;
            if (inAir) {
                // shadow on the ground, the aircraft above it
                g.save();
                g.translate(sx + lift, sy + lift * 1.3f);
                g.rotate(v.head[i]);
                g.color(0x50000000);
                g.fillRect(-t.length / 2 * z, -t.width / 4 * z, t.length * z, t.width / 2 * z);
                g.restore();
            }
            g.translate(sx, sy);
            g.rotate(v.head[i]);
            float L = t.length * z, W = t.width * z;
            boolean wreck = v.state[i] == Vehicles.V_WRECK;
            int col = wreck ? 0xFF2E2A28 : t.color;
            if (!inAir) {
                g.color(0x45000000);
                g.fillRect(-L / 2 + 2 * z, -W / 2 + 3 * z, L, W);
            }
            if (t.flies && t.weapon != null && t.speed > 400) {
                // a jet: a dart
                poly[0] = L / 2; poly[1] = 0; poly[2] = -L / 2; poly[3] = -W / 2; poly[4] = -L / 3; poly[5] = 0; poly[6] = -L / 2; poly[7] = W / 2;
                g.color(col);
                g.fillPoly(poly, 4);
            } else if (t.flies) {
                g.color(col);
                g.fillRoundRect(-L / 2.4f, -W / 5, L / 1.3f, W / 2.5f, W / 5);
                g.fillRect(-L / 2, -1.5f * z, L / 2, 3 * z);
                // rotor
                g.color(0x90202020);
                float ra = time * 30;
                for (int k = 0; k < 2; k++) {
                    float ax = (float) Math.cos(ra + k * 1.57f) * W * 0.6f, ay = (float) Math.sin(ra + k * 1.57f) * W * 0.6f;
                    g.line(-ax, -ay, ax, ay, 1.5f * z);
                }
            } else {
                g.color(col);
                g.fillRoundRect(-L / 2, -W / 2, L, W, 3 * z);
                g.color(MapPainter.shade(col, 0.78f));
                g.fillRect(-L * 0.15f, -W / 2 + 1.5f * z, L * 0.4f, W - 3 * z); // cab / roof
                if (t.stripe != 0 && !wreck) {
                    g.color(t.stripe);
                    g.fillRect(-L / 2, -1.2f * z, L, 2.4f * z);
                }
                if (t.weapon != null && !wreck) {
                    // turret and barrel
                    g.color(MapPainter.shade(col, 0.7f));
                    g.fillCircle(0, 0, W * 0.32f);
                    g.color(0xFF222222);
                    g.line(0, 0, L * (t.tracked ? 0.75f : 0.45f), 0, (t.tracked ? 2.4f : 1.4f) * z);
                }
                if (v.siren[i] && !wreck) {
                    boolean on = ((int) (time * 6) & 1) == 0;
                    g.color(on ? 0xFFFF3030 : 0xFF3050FF);
                    g.fillRect(-2 * z, -W / 2 + 1, 3 * z, W / 2 - 1);
                    g.color(on ? 0xFF3050FF : 0xFFFF3030);
                    g.fillRect(-2 * z, 0, 3 * z, W / 2 - 1);
                }
                if (v.state[i] == Vehicles.V_DRIVE && w.daylight() < 0.5f) {
                    g.color(0x40FFF0C0);
                    poly[0] = L / 2; poly[1] = -W / 3; poly[2] = L / 2 + 40 * z; poly[3] = -W; poly[4] = L / 2 + 40 * z; poly[5] = W; poly[6] = L / 2; poly[7] = W / 3;
                    g.fillPoly(poly, 4);
                }
            }
            g.restore();
            if (i == inspectVehicle) {
                g.color(0xFFFFFFFF);
                g.strokeCircle(sx, sy, L * 0.8f, 1.5f);
            }
            if (!wreck && z > 0.5f && v.hp[i] < t.hp * 0.5f) {
                g.color(0x60606060);
                g.fillCircle(sx + (float) Math.sin(time * 3 + i) * 3, sy - 8 * z - (time * 10 % 10) * z, 5 * z);
            }
        }
    }

    private void vehicleDots(Gfx g, Camera cam) {
        Vehicles v = w.veh;
        float z = cam.zoom;
        float s = Math.max(2.5f, 30 * z);
        for (int i = 0; i < v.top; i++) {
            if (v.state[i] == Vehicles.V_NONE || v.state[i] == Vehicles.V_PARKED) continue;
            Content.VType t = v.type(i);
            g.color(v.state[i] == Vehicles.V_WRECK ? 0xFF303030 : t.faction.equals("police") ? 0xFF7FA8FF : t.faction.equals("army") ? 0xFFC8D890 : 0xFFE3B21F);
            float sx = cam.toScreenX(v.x[i]), sy = cam.toScreenY(v.y[i]);
            g.fillRect(sx - s / 2, sy - s / 2, s, s);
        }
    }

    // ------------------------------------------------------------------ effects

    private void effects(Gfx g, Camera cam, float x0, float y0, float x1, float y1) {
        Effects fx = w.fx;
        float z = cam.zoom;
        for (int k = 0; k < fx.count; k++) {
            int i = (fx.head - 1 - k + Effects.MAX) % Effects.MAX;
            if (fx.age[i] >= fx.life[i]) continue;
            float x = fx.fx[i], y = fx.fy[i];
            if (x < x0 - 300 || x > x1 + 300 || y < y0 - 300 || y > y1 + 300) continue;
            float t = fx.age[i] / fx.life[i];
            float sx = cam.toScreenX(x), sy = cam.toScreenY(y);
            switch (fx.kind[i]) {
                case Effects.TRACER:
                    if (z < 0.25f) break;
                    g.color(((int) ((1 - t) * 220) << 24) | 0xFFE890);
                    g.line(sx, sy, cam.toScreenX(fx.fx2[i]), cam.toScreenY(fx.fy2[i]), Math.max(1, 1.2f * z));
                    break;
                case Effects.FLASH:
                    if (z < 0.4f) break;
                    g.color(0xDDFFE070);
                    float a = fx.fx2[i];
                    g.fillCircle(sx + (float) Math.cos(a) * 7 * z, sy + (float) Math.sin(a) * 7 * z, 3 * z);
                    break;
                case Effects.BLAST:
                case Effects.BIGBLAST: {
                    float rad = fx.fx2[i] * z * (0.4f + t * 0.8f);
                    g.color(((int) ((1 - t) * 200) << 24) | 0xFF8A20);
                    g.fillCircle(sx, sy, Math.max(3, rad));
                    g.color(((int) ((1 - t) * 230) << 24) | 0xFFE080);
                    g.fillCircle(sx, sy, Math.max(2, rad * 0.5f));
                    break;
                }
                case Effects.SMOKE: {
                    float rad = fx.fx2[i] * z * (0.3f + t * 0.9f);
                    g.color(((int) ((1 - t) * 90) << 24) | 0x505050);
                    g.fillCircle(sx + t * 30 * z, sy - t * 50 * z, Math.max(2, rad * 0.6f));
                    break;
                }
                case Effects.SPARK:
                    g.color(0xFFFFFFC0);
                    g.fillCircle(sx, sy, 2.5f * z);
                    break;
                default:
            }
        }
    }

    // ------------------------------------------------------------------ the map overlays

    private void overlays(Gfx g, Camera cam) {
        float z = cam.zoom;
        float cs = Region.SECT * Tile.SIZE;
        layers(g, cam);
        // the infection as a stain over the sectors (from far out, and where the dead are only counted)
        if (z < 0.5f && (overlay == OV_INFECTION || overlay == OV_FORCES)) {
            int sx0 = Math.max(0, (int) (cam.x0() / cs)), sy0 = Math.max(0, (int) (cam.y0() / cs));
            int sx1 = Math.min(w.sec.sw - 1, (int) (cam.x1() / cs)), sy1 = Math.min(w.sec.sh - 1, (int) (cam.y1() / cs));
            for (int sy = sy0; sy <= sy1; sy++)
                for (int sx = sx0; sx <= sx1; sx++) {
                    int s = sy * w.sec.sw + sx;
                    int zz = w.sec.zIn[s] + (z < 0.2f ? w.sp.secZ[s] : 0);
                    if (zz <= 0) continue;
                    float a = Math.min(0.62f, 0.12f + (float) Math.log10(1 + zz) * 0.16f);
                    g.color(((int) (a * 255) << 24) | 0xC01818);
                    g.fillRect(cam.toScreenX(sx * cs), cam.toScreenY(sy * cs), cs * z + 1, cs * z + 1);
                }
        }
        // hordes on the march
        for (Strategic.Horde h : w.strat.hordes) {
            if (!h.alive) continue;
            float sx = cam.toScreenX(h.x), sy = cam.toScreenY(h.y);
            float rad = Math.max(8, (60 + (float) Math.sqrt(h.count) * 5) * z);
            g.color(0x88B01818);
            g.fillCircle(sx, sy, rad);
            g.color(0xFFFF6060);
            g.strokeCircle(sx, sy, rad, 2);
            if (h.target >= 0) {
                g.color(0x80FF6060);
                g.line(sx, sy, cam.toScreenX(h.tx), cam.toScreenY(h.ty), 1.5f);
            }
            g.color(0xFFFFFFFF);
            g.text("Horde " + fmt(h.count), sx, sy - rad - 4, 12, Gfx.ALIGN_CENTER, true);
        }
        // refugee columns
        for (Strategic.Column c : w.strat.columns) {
            if (!c.alive) continue;
            float sx = cam.toScreenX(c.x), sy = cam.toScreenY(c.y);
            g.color(0xFFF0F0F0);
            g.fillCircle(sx, sy, Math.max(3, 14 * z));
            g.color(0xFF3060A0);
            g.strokeCircle(sx, sy, Math.max(3, 14 * z), 1.5f);
            if (z < 0.6f) {
                g.color(0xFFFFFFFF);
                g.text(fmt(c.count) + " refugees", sx, sy - 8, 10, Gfx.ALIGN_CENTER, false);
            }
        }
        // the airliner wreck
        if (w.strat.crashX >= 0) {
            float sx = cam.toScreenX(w.strat.crashX), sy = cam.toScreenY(w.strat.crashY);
            g.save();
            g.translate(sx, sy);
            g.rotate(0.6f);
            g.color(0xFFB8B8B0);
            g.fillRect(-60 * z, -6 * z, 90 * z, 12 * z);
            g.fillRect(-10 * z, -38 * z, 16 * z, 76 * z);
            g.color(0xFF303030);
            g.fillRect(30 * z, -6 * z, 40 * z, 12 * z);
            g.restore();
        }
        // facilities: icons, greyed when overrun
        if (z < 1.2f) {
            for (Region.Facility f : w.r.facilities) {
                if (f.kind == Region.F_HELIPAD || f.kind == Region.F_PARK) continue;
                if (f.kind == Region.F_DEPOT || f.kind == Region.F_LAB) {
                    if (z < 0.15f) continue;
                }
                if (z < 0.06f && f.kind != Region.F_POWER && f.kind != Region.F_BASE && f.kind != Region.F_PRISON) continue;
                float sx = cam.toScreenX(f.x), sy = cam.toScreenY(f.y);
                if (sx < -30 || sy < -30 || sx > cam.sw + 30 || sy > cam.sh + 30) continue;
                boolean on = w.strat.online[f.id];
                facilityIcon(g, f.kind, sx, sy, on, z < 0.12f ? 9 : 12);
                if (z > 0.35f && names) {
                    g.color(on ? 0xEEFFFFFF : 0xEEFF7070);
                    g.text(f.name + (on ? "" : " (overrun)"), sx, sy + 22, 10, Gfx.ALIGN_CENTER, false);
                }
            }
        }
        // squads as unit symbols when far out
        if (z < 0.3f) {
            for (Squads.Squad s : w.sq.list) {
                if (!s.alive) continue;
                float[] p = w.sq.where(s);
                float sx = cam.toScreenX(p[0]), sy = cam.toScreenY(p[1]);
                if (sx < -20 || sy < -20 || sx > cam.sw + 20 || sy > cam.sh + 20) continue;
                if (s.order == Squads.O_IDLE && z < 0.08f) continue;
                int col = s.faction == Ents.COP ? 0xFF3B6FD8 : 0xFF5D7040;
                g.color(0xFF101010);
                g.fillRect(sx - 6, sy - 4, 12, 8);
                g.color(col);
                g.fillRect(sx - 5, sy - 3, 10, 6);
                if (s == selected) {
                    g.color(0xFFFFE040);
                    g.strokeRect(sx - 8, sy - 6, 16, 12, 2);
                }
            }
        }
        // calls and the cordon
        if (z < 0.9f && z > 0.05f) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(time * 4);
            for (Dispatch.Incident in : w.disp.incidents) {
                if (!in.open) continue;
                float sx = cam.toScreenX(in.x), sy = cam.toScreenY(in.y);
                if (sx < -40 || sy < -40 || sx > cam.sw + 40 || sy > cam.sh + 40) continue;
                g.color(((int) (120 + pulse * 120) << 24) | (in.army ? 0x80C040 : 0xFF4040));
                g.strokeCircle(sx, sy, 10 + pulse * 4, 2);
                if (z > 0.06f) {
                    g.color(0xFFFFFFFF);
                    g.text(in.zombies + "", sx, sy + 4, 10, Gfx.ALIGN_CENTER, true);
                }
            }
        }
        if (z > 0.06f) for (Dispatch.Post p : w.disp.posts) {
            float sx = cam.toScreenX(p.x), sy = cam.toScreenY(p.y);
            g.color(p.squad >= 0 ? 0xFF4F8CFF : 0x884F8CFF);
            g.line(sx - p.fy * 14, sy + p.fx * 14, sx + p.fy * 14, sy - p.fx * 14, 3);
        }
        // the selected squad's goal
        if (selected != null && selected.alive) {
            float[] p = w.sq.where(selected);
            g.color(0xCCFFE040);
            g.line(cam.toScreenX(p[0]), cam.toScreenY(p[1]), cam.toScreenX(selected.gx), cam.toScreenY(selected.gy), 1.5f);
            g.strokeCircle(cam.toScreenX(selected.gx), cam.toScreenY(selected.gy), 8, 2);
        }
        // place names
        if (names) {
            for (Region.Settlement s : w.r.settlements) {
                float sx = cam.toScreenX(s.cx * 16f), sy = cam.toScreenY(s.cy * 16f);
                if (sx < -100 || sy < -40 || sx > cam.sw + 100 || sy > cam.sh + 40) continue;
                float size;
                if (s.kind == Region.S_CITY) size = z < 0.5f ? 22 : 0;
                else if (s.kind == Region.S_TOWN || s.kind == Region.S_BASE || s.kind == Region.S_PRISON) size = z < 0.4f ? 15 : 0;
                else size = z < 0.4f && z > 0.05f ? 11 : 0;
                if (size == 0) continue;
                String label = s.name;
                if (s.kind == Region.S_CITY || s.kind == Region.S_TOWN) {
                    int zz = w.stats.zBySettlement[s.id];
                    if (z < 0.2f && (s.kind == Region.S_CITY || z > 0.04f)) label += " " + people(w.stats.popBySettlement[s.id]);
                    if (w.gov.evacuating(s.id)) label += "  (evacuating)";
                    else if (zz > 0 && z < 0.2f) label += "  (" + people(zz) + " dead)";
                }
                if (s.capital) label = "\u2605 " + label;
                g.color(0xA0000000);
                g.text(label, sx + 1.5f, sy + 1.5f, size, Gfx.ALIGN_CENTER, true);
                g.color(0xFFFFFFFF);
                g.text(label, sx, sy, size, Gfx.ALIGN_CENTER, true);
            }
        }
    }

    // ------------------------------------------------------------------ the national map's layers

    private float[] stateX, stateY;

    /** The chosen overlay over the sectors, and state borders when far out. */
    private void layers(Gfx g, Camera cam) {
        float z = cam.zoom;
        float cs = Region.SECT * Tile.SIZE;
        Sectors sec = w.sec;
        int sx0 = Math.max(0, (int) (cam.x0() / cs)), sy0 = Math.max(0, (int) (cam.y0() / cs));
        int sx1 = Math.min(sec.sw - 1, (int) (cam.x1() / cs)), sy1 = Math.min(sec.sh - 1, (int) (cam.y1() / cs));
        if (overlay == OV_FORCES) {
            g.color(0x70000000);
            g.fillRect(0, 0, g.width(), g.height());
        }
        if (overlay == OV_TRUST || overlay == OV_DEATHS || overlay == OV_PEAK || overlay == OV_UTIL) {
            for (int sy = sy0; sy <= sy1; sy++)
                for (int sx = sx0; sx <= sx1; sx++) {
                    int s = sy * sec.sw + sx;
                    int c = 0;
                    if (overlay == OV_TRUST) {
                        float p = sec.panic[s];
                        if (p > 0.02f && sec.home[s] > 0) c = ((int) (40 + p * 170) << 24) | 0xF08020;
                    } else if (overlay == OV_DEATHS) {
                        float d = w.director.deaths[s] + sec.bodies[s];
                        if (d > 0) c = ((int) Math.min(220, 50 + Math.log10(1 + d) * 45) << 24) | 0x9030C0;
                    } else if (overlay == OV_PEAK) {
                        float p = w.director.peak[s];
                        if (p > 0) c = ((int) Math.min(220, 50 + Math.log10(1 + p) * 45) << 24) | (p > 1000 ? 0xFF3010 : 0xFFA020);
                    } else if (sec.home[s] > 0) {
                        boolean lit = w.strat.lit(s);
                        c = lit ? 0x3040C040 : 0x90100808;
                    }
                    if (c != 0) {
                        g.color(c);
                        g.fillRect(cam.toScreenX(sx * cs), cam.toScreenY(sy * cs), cs * z + 1, cs * z + 1);
                    }
                }
        }
        if (overlay == OV_UTIL) utilities(g, cam);
        if (overlay == OV_SUPPLY) supplies(g, cam);
        // state borders and names from far out
        if (z < 0.2f && w.r.states.size() > 1) {
            g.color(0x90FFFFFF);
            for (int sy = sy0; sy <= sy1; sy++)
                for (int sx = sx0; sx <= sx1; sx++) {
                    int s = sy * sec.sw + sx;
                    int st = w.roles.stateOfSector(s);
                    float x = cam.toScreenX(sx * cs), y = cam.toScreenY(sy * cs), len = cs * z;
                    if (sx + 1 < sec.sw && w.roles.stateOfSector(s + 1) != st) dashed(g, x + len, y, x + len, y + len);
                    if (sy + 1 < sec.sh && w.roles.stateOfSector(s + sec.sw) != st) dashed(g, x, y + len, x + len, y + len);
                }
            if (z < 0.08f) {
                if (stateX == null) {
                    int ns = w.r.states.size();
                    stateX = new float[ns];
                    stateY = new float[ns];
                    int[] cnt = new int[ns];
                    for (int s = 0; s < sec.n; s++) {
                        int st = w.roles.stateOfSector(s);
                        if (st < 0 || !Tile.walkable(w.r.tileAtPx(sec.cx(s), sec.cy(s)))) continue;
                        stateX[st] += sec.cx(s);
                        stateY[st] += sec.cy(s);
                        cnt[st]++;
                    }
                    for (int k = 0; k < ns; k++) if (cnt[k] > 0) {
                        stateX[k] /= cnt[k];
                        stateY[k] /= cnt[k];
                    }
                }
                for (Region.State st : w.r.states) {
                    g.color(0x70FFFFFF);
                    g.text(st.name.toUpperCase(), cam.toScreenX(stateX[st.id]), cam.toScreenY(stateY[st.id]) + 30, 16, Gfx.ALIGN_CENTER, true);
                }
            }
        }
    }

    private static void dashed(Gfx g, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
        int n = Math.max(1, (int) (len / 6));
        for (int i = 0; i < n; i += 2) g.line(x1 + dx * i / n, y1 + dy * i / n, x1 + dx * (i + 1) / n, y1 + dy * (i + 1) / n, 1.5f);
    }

    /** Power, water and telephones for every town: a coloured dot for each. */
    private void utilities(Gfx g, Camera cam) {
        boolean[] water = network(Region.F_WATER), phone = network(Region.F_PHONE);
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) continue;
            float x = cam.toScreenX(s.cx * 16f), y = cam.toScreenY(s.cy * 16f) + 14;
            boolean[] on = {s.grid < 0 || w.strat.gridOn[s.grid], s.water < 0 || s.water >= water.length || water[s.water], s.phone < 0 || s.phone >= phone.length || phone[s.phone]};
            String[] l = {"P", "W", "T"};
            for (int k = 0; k < 3; k++) {
                float cx = x + (k - 1) * 16;
                g.color(0xD0000000);
                g.fillCircle(cx, y, 8);
                g.color(on[k] ? 0xFF40C040 : 0xFFE03030);
                g.fillCircle(cx, y, 6.5f);
                g.color(0xFF000000);
                g.text(l[k], cx, y + 3.5f, 9, Gfx.ALIGN_CENTER, true);
            }
        }
    }

    /** Which networks of a kind (water, phones) still run: their works are working. */
    private boolean[] network(int kind) {
        int n = 0;
        for (Region.Facility f : w.r.facilities) if (f.kind == kind) n = Math.max(n, f.network + 1);
        boolean[] on = new boolean[n];
        for (Region.Facility f : w.r.facilities) if (f.kind == kind && f.network >= 0 && w.strat.online(f.id)) on[f.network] = true;
        return on;
    }

    /** Ammunition, food and fuel at each place that keeps them, as bars. */
    private void supplies(Gfx g, Camera cam) {
        for (Region.Facility f : w.r.facilities) {
            float tot = f.ammo + f.food + f.fuel;
            if (tot <= 0 && f.kind != Region.F_POLICE && f.kind != Region.F_BASE) continue;
            float x = cam.toScreenX(f.x), y = cam.toScreenY(f.y);
            if (x < -30 || y < -30 || x > cam.sw + 30 || y > cam.sh + 30) continue;
            float[] v = {f.ammo, f.food, f.fuel};
            int[] col = {0xFFE0C040, 0xFF60C060, 0xFFB07030};
            g.color(0xC0000000);
            g.fillRect(x - 14, y - 26, 28, 22);
            for (int k = 0; k < 3; k++) {
                float h = (float) Math.min(1, Math.log10(1 + v[k]) / 5) * 18;
                g.color(col[k]);
                g.fillRect(x - 11 + k * 8, y - 6 - h, 6, Math.max(1, h));
            }
            if (!w.strat.online(f.id)) {
                g.color(0xFFFF4040);
                g.line(x - 14, y - 26, x + 14, y - 4, 2);
            }
        }
    }

    /** A small key for the overlay, at (x, y). */
    public void legend(Gfx g, float x, float y, float dp) {
        String[] lines;
        int[] cols;
        switch (overlay) {
            case OV_UTIL: lines = new String[]{"Power, water, phones", "working", "lost"}; cols = new int[]{0, 0xFF40C040, 0xFFE03030}; break;
            case OV_SUPPLY: lines = new String[]{"Stocks", "ammunition", "food", "fuel"}; cols = new int[]{0, 0xFFE0C040, 0xFF60C060, 0xFFB07030}; break;
            case OV_TRUST: lines = new String[]{"Public trust " + (int) w.gov.trust + "%", "fear in the streets"}; cols = new int[]{0, 0xFFF08020}; break;
            case OV_FORCES: lines = new String[]{"Forces and fronts", "police", "army", "front line"}; cols = new int[]{0, 0xFF3B6FD8, 0xFF6E8A48, 0xFFFFD040}; break;
            case OV_DEATHS: lines = new String[]{"Where they died", "people and the dead"}; cols = new int[]{0, 0xFF9030C0}; break;
            case OV_PEAK: lines = new String[]{"Peak infection", "hundreds", "thousands"}; cols = new int[]{0, 0xFFFFA020, 0xFFFF3010}; break;
            default: return;
        }
        float lh = 15 * dp, wdt = 150 * dp;
        g.color(0xB0000000);
        g.fillRoundRect(x, y, wdt, lines.length * lh + 8 * dp, 6 * dp);
        for (int k = 0; k < lines.length; k++) {
            float ly = y + 4 * dp + (k + 1) * lh - 4 * dp;
            if (cols[k] != 0) {
                g.color(cols[k]);
                g.fillRect(x + 8 * dp, ly - 8 * dp, 9 * dp, 9 * dp);
            }
            g.color(k == 0 ? 0xFFFFFFFF : 0xFFD0D0D0);
            g.text(lines[k], x + (cols[k] != 0 ? 22 : 8) * dp, ly, 11 * dp, Gfx.ALIGN_LEFT, k == 0);
        }
    }


    static void facilityIcon(Gfx g, int kind, float x, float y, boolean on, float s) {
        int bg;
        switch (kind) {
            case Region.F_POLICE: bg = 0xFF2B4E9A; break;
            case Region.F_HOSPITAL: bg = 0xFFE8E8E8; break;
            case Region.F_POWER: bg = 0xFFE0B020; break;
            case Region.F_FUEL: bg = 0xFFB07030; break;
            case Region.F_BASE: bg = 0xFF55663A; break;
            case Region.F_ARMORY: bg = 0xFF6E7A48; break;
            case Region.F_PRISON: bg = 0xFF706860; break;
            default: bg = 0xFF808080;
        }
        if (!on) bg = 0xFF5A3030;
        g.color(0xC0000000);
        g.fillCircle(x, y, s + 1.5f);
        g.color(bg);
        g.fillCircle(x, y, s);
        g.color(kind == Region.F_HOSPITAL ? 0xFFD02020 : 0xFFFFFFFF);
        switch (kind) {
            case Region.F_HOSPITAL:
                g.fillRect(x - s * 0.55f, y - s * 0.18f, s * 1.1f, s * 0.36f);
                g.fillRect(x - s * 0.18f, y - s * 0.55f, s * 0.36f, s * 1.1f);
                break;
            case Region.F_POWER: {
                float[] p = {x + s * 0.15f, y - s * 0.7f, x - s * 0.4f, y + s * 0.1f, x, y + s * 0.1f, x - s * 0.15f, y + s * 0.7f, x + s * 0.4f, y - s * 0.1f, x, y - s * 0.1f};
                g.color(0xFF202020);
                g.fillPoly(p, 6);
                break;
            }
            case Region.F_POLICE:
                g.text("P", x, y + s * 0.42f, s * 1.2f, Gfx.ALIGN_CENTER, true);
                break;
            case Region.F_BASE:
            case Region.F_ARMORY:
                g.text("★", x, y + s * 0.42f, s * 1.2f, Gfx.ALIGN_CENTER, true);
                break;
            case Region.F_FUEL:
                g.text("F", x, y + s * 0.42f, s * 1.2f, Gfx.ALIGN_CENTER, true);
                break;
            default:
                g.fillCircle(x, y, s * 0.3f);
        }
    }

    /** People counts as they read best: 950, 12,400, 450k, 2.3M. */
    public static String people(long n) {
        if (n >= 1000000) return String.format("%.1fM", n / 1e6);
        if (n >= 100000) return (n / 1000) + "k";
        return fmt((int) n);
    }

    static String fmt(int n) {
        if (n >= 10000) return (n / 1000) + "k";
        if (n >= 1000) return (n / 1000) + "," + String.format("%03d", n % 1000);
        return Integer.toString(n);
    }
}
