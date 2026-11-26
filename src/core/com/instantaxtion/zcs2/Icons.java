package com.instantaxtion.zcs2;

/** Small pictograms drawn with the Gfx primitives (no image files): the tools, the HUD's numbers, the weather. */
final class Icons {
    private static final float[] P = new float[24];
    private static final float[] K0 = {0, -0.85f, 0.7f, -0.55f, 0.65f, 0.15f, 0, 0.85f, -0.65f, 0.15f, -0.7f, -0.55f};
    private static final float[] K1 = {-0.5f, -0.85f, 0.75f, -0.5f, -0.5f, -0.1f};
    private static final float[] K2 = {0.85f, -0.85f, 0.2f, -0.75f, 0.75f, -0.2f};
    private static final float[] K3 = {0, -0.85f, 0.85f, -0.05f, 0.6f, -0.05f, 0.6f, 0.8f, -0.6f, 0.8f, -0.6f, -0.05f, -0.85f, -0.05f};
    private static final float[] K4 = {-0.85f, 0.25f, 0.2f, -0.8f, 0.85f, -0.15f, -0.2f, 0.85f};
    private static final float[] K5 = {-0.85f, -0.6f, -0.3f, -0.8f, 0.3f, -0.6f, 0.85f, -0.8f, 0.85f, 0.6f, 0.3f, 0.8f, -0.3f, 0.6f, -0.85f, 0.8f};
    private static final float[] K6 = {-0.45f, -0.7f, 0.7f, 0, -0.45f, 0.7f};
    private static final float[] K7 = {0.05f, 0.35f, -0.25f, 0.7f, 0, 0.7f, -0.15f, 1.0f, 0.3f, 0.55f, 0.05f, 0.55f};

    private Icons() {
    }

    /** Draws the icon centred at (x, y), s pixels across, in one colour. */
    static void draw(Gfx g, String name, float x, float y, float s, int c) {
        float h = s / 2, lw = Math.max(1.2f, s * 0.11f);
        g.color(c);
        switch (name) {
            case "look": // a magnifying glass
                g.strokeCircle(x - h * 0.2f, y - h * 0.2f, h * 0.55f, lw);
                g.line(x + h * 0.2f, y + h * 0.2f, x + h * 0.8f, y + h * 0.8f, lw * 1.4f);
                break;
            case "dead": { // a skull
                g.fillCircle(x, y - h * 0.15f, h * 0.7f);
                g.fillRect(x - h * 0.4f, y + h * 0.2f, h * 0.8f, h * 0.55f);
                g.color(0xFF101418);
                g.fillCircle(x - h * 0.28f, y - h * 0.15f, h * 0.18f);
                g.fillCircle(x + h * 0.28f, y - h * 0.15f, h * 0.18f);
                g.fillRect(x - h * 0.06f, y + h * 0.42f, h * 0.12f, h * 0.33f);
                g.fillRect(x - h * 0.3f, y + h * 0.42f, h * 0.1f, h * 0.33f);
                g.fillRect(x + h * 0.2f, y + h * 0.42f, h * 0.1f, h * 0.33f);
                break;
            }
            case "police": { // a shield badge
                pts(x, y, h, K0);
                g.fillPoly(P, 6);
                g.color(0xFF101418);
                g.fillCircle(x, y - h * 0.05f, h * 0.22f);
                break;
            }
            case "army": // a star
                star(g, x, y, h * 0.85f, h * 0.36f);
                break;
            case "strike": // a crosshair
            case "fire":
                g.strokeCircle(x, y, h * 0.6f, lw);
                g.line(x - h * 0.95f, y, x - h * 0.3f, y, lw);
                g.line(x + h * 0.3f, y, x + h * 0.95f, y, lw);
                g.line(x, y - h * 0.95f, x, y - h * 0.3f, lw);
                g.line(x, y + h * 0.3f, x, y + h * 0.95f, lw);
                if (name.equals("fire")) g.fillCircle(x, y, h * 0.18f);
                break;
            case "orders": { // a flag
                g.line(x - h * 0.55f, y + h * 0.85f, x - h * 0.55f, y - h * 0.85f, lw);
                pts(x, y, h, K1);
                g.fillPoly(P, 3);
                break;
            }
            case "send": { // an arrow out of a box
                g.strokeRect(x - h * 0.8f, y - h * 0.2f, h * 0.9f, h * 0.9f, lw);
                g.line(x - h * 0.2f, y + h * 0.15f, x + h * 0.7f, y - h * 0.7f, lw);
                pts(x, y, h, K2);
                g.fillPoly(P, 3);
                break;
            }
            case "town": { // a house
                pts(x, y, h, K3);
                g.fillPoly(P, 7);
                g.color(0xFF101418);
                g.fillRect(x - h * 0.15f, y + h * 0.3f, h * 0.3f, h * 0.5f);
                break;
            }
            case "erase": { // an eraser
                pts(x, y, h, K4);
                g.fillPoly(P, 4);
                g.color(0xFF101418);
                g.line(x - h * 0.3f, y - h * 0.3f, x + h * 0.3f, y + h * 0.3f, lw);
                g.color(c);
                g.line(x - h * 0.2f, y + h * 0.85f, x + h * 0.85f, y + h * 0.85f, lw * 0.8f);
                break;
            }
            case "map": { // a folded map
                pts(x, y, h, K5);
                g.fillPoly(P, 8);
                g.color(0xFF101418);
                g.line(x - h * 0.3f, y - h * 0.75f, x - h * 0.3f, y + h * 0.6f, lw * 0.7f);
                g.line(x + h * 0.3f, y - h * 0.6f, x + h * 0.3f, y + h * 0.75f, lw * 0.7f);
                break;
            }
            case "build": { // a brick wall
                float bw = h * 0.8f, bh = h * 0.42f, gp = Math.max(1, h * 0.1f);
                for (int r = 0; r < 3; r++) {
                    float off = (r % 2) * bw * 0.5f;
                    for (int k = -1; k < 3; k++) {
                        float x0 = x - h * 0.95f + off + k * (bw + gp), x1 = x0 + bw;
                        x0 = Math.max(x0, x - h * 0.95f);
                        x1 = Math.min(x1, x + h * 0.95f);
                        if (x1 - x0 > gp) g.fillRect(x0, y - h * 0.75f + r * (bh + gp), x1 - x0, bh);
                    }
                }
                break;
            }
            case "people": // a person
                g.fillCircle(x, y - h * 0.45f, h * 0.32f);
                g.fillRoundRect(x - h * 0.5f, y - h * 0.05f, h, h * 0.85f, h * 0.35f);
                break;
            case "menu":
                for (int i = -1; i <= 1; i++) g.fillRoundRect(x - h * 0.75f, y + i * h * 0.5f - lw / 2, h * 1.5f, lw, lw / 2);
                break;
            case "play":
                pts(x, y, h, K6);
                g.fillPoly(P, 3);
                break;
            case "pause":
                g.fillRect(x - h * 0.5f, y - h * 0.65f, h * 0.35f, h * 1.3f);
                g.fillRect(x + h * 0.15f, y - h * 0.65f, h * 0.35f, h * 1.3f);
                break;
            case "radio":
                g.fillRoundRect(x - h * 0.8f, y - h * 0.3f, h * 1.6f, h * 1.05f, h * 0.2f);
                g.line(x - h * 0.4f, y - h * 0.3f, x + h * 0.5f, y - h * 0.9f, lw);
                g.color(0xFF101418);
                g.fillCircle(x - h * 0.3f, y + h * 0.22f, h * 0.25f);
                break;
            case "clear": // the sun
                g.fillCircle(x, y, h * 0.42f);
                for (int i = 0; i < 8; i++) {
                    float a = i * 0.785f;
                    float cx = (float) Math.cos(a), cy = (float) Math.sin(a);
                    g.line(x + cx * h * 0.6f, y + cy * h * 0.6f, x + cx * h * 0.9f, y + cy * h * 0.9f, lw);
                }
                break;
            case "cloud":
            case "rain":
            case "storm":
            case "snow":
                g.fillCircle(x - h * 0.35f, y - h * 0.05f, h * 0.38f);
                g.fillCircle(x + h * 0.1f, y - h * 0.25f, h * 0.48f);
                g.fillCircle(x + h * 0.5f, y + h * 0.02f, h * 0.32f);
                g.fillRect(x - h * 0.35f, y - h * 0.05f, h * 0.85f, h * 0.38f);
                if (name.equals("rain")) {
                    for (int i = -1; i <= 1; i++) g.line(x + i * h * 0.35f, y + h * 0.5f, x + i * h * 0.35f - h * 0.15f, y + h * 0.9f, lw * 0.8f);
                } else if (name.equals("snow")) {
                    for (int i = -1; i <= 1; i++) g.fillCircle(x + i * h * 0.35f, y + h * 0.72f, lw * 0.8f);
                } else if (name.equals("storm")) {
                    g.color(0xFFFFD040);
                    pts(x, y, h, K7);
                    g.fillPoly(P, 6);
                }
                break;
            case "fog":
                for (int i = -1; i <= 1; i++) g.fillRoundRect(x - h * 0.85f + (i & 1) * h * 0.2f, y + i * h * 0.45f - lw / 2, h * 1.5f, lw * 1.1f, lw / 2);
                break;
            case "help":
                g.strokeCircle(x, y, h * 0.85f, lw);
                g.text("?", x, y + h * 0.4f, h * 1.2f, Gfx.ALIGN_CENTER, true);
                break;
            default:
                g.fillCircle(x, y, h * 0.4f);
        }
    }

    private static void pts(float x, float y, float h, float[] xy) {
        for (int i = 0; i < xy.length; i += 2) {
            P[i] = x + xy[i] * h;
            P[i + 1] = y + xy[i + 1] * h;
        }
    }

    static void star(Gfx g, float x, float y, float r1, float r2) {
        for (int i = 0; i < 10; i++) {
            float a = (float) (-Math.PI / 2 + i * Math.PI / 5);
            float r = (i & 1) == 0 ? r1 : r2;
            P[i * 2] = x + (float) Math.cos(a) * r;
            P[i * 2 + 1] = y + (float) Math.sin(a) * r;
        }
        g.fillPoly(P, 10);
    }

    /** The weather's icon name. */
    static String weather(int state) {
        switch (state) {
            case Weather.RAIN: return "rain";
            case Weather.STORM: return "storm";
            case Weather.SNOW: return "snow";
            case Weather.FOG: return "fog";
            case Weather.CLEAR: return "clear";
            default: return "cloud";
        }
    }
}
