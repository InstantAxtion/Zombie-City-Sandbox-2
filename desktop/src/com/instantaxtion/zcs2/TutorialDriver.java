package com.instantaxtion.zcs2;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * Drives the tutorial with scripted taps, as a player would (the tests, and UiShot's "tutorial" and "clip" shots):
 * every step done the way its card asks, Back, Skip, stopping and picking it up again, then checks that nothing of the
 * tour is left behind. With a folder to shoot into, each step is saved as a PNG.
 */
final class TutorialDriver {
    final Game game;
    final DesktopPlatform pf;
    final int W, H;
    private final File shots;
    private final BufferedImage img;
    private BufferedImage last;

    TutorialDriver(File data, int w, int h, float density, File shots) {
        UiShot.deleteAll(data);
        pf = new DesktopPlatform(data);
        pf.density = density;
        W = w;
        H = h;
        this.shots = shots;
        if (shots != null) shots.mkdirs();
        img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        game = new Game(pf);
        frames(3);
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    void frames(int n) {
        for (int i = 0; i < n; i++) {
            DesktopGfx g = new DesktopGfx(img.createGraphics(), W, H);
            game.frame(g, 1 / 30f);
            g.g.dispose();
        }
        last = img;
    }

    void shot(String name) throws Exception {
        if (shots == null) return;
        ImageIO.write(last, "png", new File(shots, name + ".png"));
    }

    void tap(String id) {
        float[] p = game.ui.find(id);
        if (p == null) throw new IllegalStateException("no button " + id + " on screen " + game.screen() + " (tutorial step " + game.tut.step + ")");
        tapAt(p[0], p[1]);
    }

    void tapAt(float x, float y) {
        game.touch(Game.DOWN, 0, x, y);
        game.touch(Game.UP, 0, x, y);
        frames(1);
    }

    void drag(float x0, float y0, float x1, float y1, int n) {
        game.touch(Game.DOWN, 0, x0, y0);
        frames(1);
        for (int k = 1; k <= n; k++) {
            game.touch(Game.MOVE, 0, x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n);
            frames(1);
        }
        game.touch(Game.UP, 0, x1, y1);
        frames(1);
    }

    /** Two fingers closing in (out = false: zoom out) or spreading (zoom in) round the middle of the map. */
    void pinch(boolean in) {
        float cx = (game.freeL + game.freeR) / 2, cy = (game.freeT + game.freeB) / 2, a = in ? 40 : 200, b = in ? 200 : 40;
        game.touch(Game.DOWN, 0, cx - a, cy - a);
        game.touch(Game.DOWN, 1, cx + a, cy + a);
        frames(1);
        for (int k = 1; k <= 8; k++) {
            float d = a + (b - a) * k / 8;
            game.touch(Game.MOVE, 0, cx - d, cy - d);
            game.touch(Game.MOVE, 1, cx + d, cy + d);
            frames(1);
        }
        game.touch(Game.UP, 1, cx + b, cy + b);
        game.touch(Game.UP, 0, cx - b, cy - b);
        frames(1);
    }

    void waitPlay() throws Exception {
        long t0 = System.currentTimeMillis();
        while (game.screen() != Game.S_PLAY) {
            frames(1);
            Thread.sleep(15);
            if (System.currentTimeMillis() - t0 > 60000) throw new IllegalStateException("the tutorial's game never started");
        }
        frames(4);
    }

    /** Waits for the tour to move on from step s (it does by itself a moment after the step is done). */
    void waitPast(int s) {
        for (int k = 0; k < 200 && game.tut.active && game.tut.step == s; k++) frames(1);
        check(!game.tut.active || game.tut.step > s, "step " + s + " (" + Tutorial.TITLES[s] + ") never moved on: \"" + game.tut.text() + "\"");
    }

    void expect(int s) {
        check(game.tut.active, "the tutorial stopped before step " + s);
        check(game.tut.step == s, "on step " + game.tut.step + ", not " + s);
    }

    /** The prefs, less the tour's own and the tips (which are what it's meant to leave), and with defaults filled in. */
    Map<String, String> prefs() {
        Map<String, String> m = new TreeMap<String, String>(pf.prefsCopy());
        java.util.Iterator<String> it = m.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith("tut.") || k.startsWith("tip.")) it.remove();
        }
        if (!m.containsKey("statsOpen")) m.put("statsOpen", "false");
        return m;
    }

    String files() {
        String[] f = pf.list();
        Arrays.sort(f);
        return Arrays.toString(f);
    }

    /** A person in the free part of the map, below the card, to tap: {x, y}, or null. */
    float[] personOnScreen(int skip) {
        World w = game.world();
        Camera cam = game.cam;
        float top = game.freeT + (game.freeB - game.freeT) * 0.35f, bot = game.freeB - 30;
        for (int i = 0; i < w.e.top; i++) {
            if (w.e.loc[i] != Ents.FREE || w.e.kind[i] != Ents.CIV) continue;
            float x = cam.toScreenX(w.e.x[i]), y = cam.toScreenY(w.e.y[i]);
            if (x < game.freeL + 30 || x > game.freeR - 30 || y < top || y > bot) continue;
            if (skip-- > 0) continue;
            return new float[]{x, y};
        }
        return null;
    }

    private float placedX, placedY;

    float mapX() {
        return (game.freeL + game.freeR) / 2;
    }

    float mapY() {
        return game.freeT + (game.freeB - game.freeT) * 0.62f;
    }

    // ------------------------------------------------------------------ the whole tour

    void runAll() throws Exception {
        // the first time the game is opened, the menu offers the tour
        check(game.screen() == Game.S_MENU, "not on the menu");
        check(game.ui.find("tut:offer:no") != null, "no offer of the tutorial on first launch");
        shot("t00-first-launch-offer");
        Map<String, String> prefsBefore = prefs();
        String filesBefore = files(), stateBefore = game.tutorialFingerprint();
        tap("tut:start");
        check(game.screen() == Game.S_LOADING, "the tour didn't start building its game");
        waitPlay();
        frames(6);
        expect(Tutorial.WELCOME);
        shot("t01-welcome");
        tap("tut:next");
        frames(8);
        expect(Tutorial.PAN);
        shot("t02-move-round");
        float half = game.ui.dp(90);
        drag(mapX() + half, mapY(), mapX() - half, mapY() - half / 3, 10);
        waitPast(Tutorial.PAN);
        frames(6);
        expect(Tutorial.ZOOM_OUT);
        shot("t03-zoom-out");
        for (int k = 0; k < 4 && !game.tut.met && game.tut.step == Tutorial.ZOOM_OUT; k++) pinch(false);
        for (int k = 0; k < 40 && !game.tut.met && game.tut.step == Tutorial.ZOOM_OUT; k++) {
            game.wheel(-1, mapX(), mapY());
            frames(1);
        }
        frames(10);
        shot("t03b-the-whole-country");
        waitPast(Tutorial.ZOOM_OUT);
        frames(6);
        expect(Tutorial.ZOOM_IN);
        shot("t04-zoom-in");
        for (int k = 0; k < 4 && !game.tut.met && game.tut.step == Tutorial.ZOOM_IN; k++) pinch(true);
        for (int k = 0; k < 60 && !game.tut.met && game.tut.step == Tutorial.ZOOM_IN; k++) {
            game.wheel(1, mapX(), mapY());
            frames(1);
        }
        waitPast(Tutorial.ZOOM_IN);
        // Back goes back a step, and the step is asked again
        tap("tut:back");
        frames(2);
        expect(Tutorial.ZOOM_IN);
        check(!game.tut.met || game.cam.zoom >= 0.55f, "back");
        waitPast(Tutorial.ZOOM_IN);
        frames(20);
        expect(Tutorial.INSPECT);
        shot("t05-tap-to-look");
        for (int k = 0; k < 12 && !game.inspectorOpen(); k++) {
            float[] p = personOnScreen(k);
            if (p == null) {
                frames(10);
                continue;
            }
            tapAt(p[0], p[1]);
            frames(2);
        }
        check(game.inspectorOpen(), "couldn't tap anyone");
        frames(4);
        shot("t05b-a-sheet");
        waitPast(Tutorial.INSPECT);
        game.closeInspector();
        frames(4);
        expect(Tutorial.PAINT);
        shot("t06-paint-the-dead");
        tap("tool:dead");
        frames(3);
        shot("t06b-paint-the-dead-tool");
        tap("b:50");
        drag(mapX() - 120, mapY(), mapX() + 120, mapY() + 20, 8);
        frames(6);
        shot("t06c-painted");
        waitPast(Tutorial.PAINT);
        frames(4);
        expect(Tutorial.POLICE);
        shot("t07-police");
        tap("tool:police");
        frames(3);
        shot("t07b-police-tool");
        // somewhere they can stand (not in a building)
        float u = game.ui.dp(30);
        float[][] spots = {{-2, -1}, {0, 0}, {2, 1}, {-1, 2}, {1, -2}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}};
        for (int k = 0; k < spots.length && !game.tut.met; k++) {
            placedX = mapX() + spots[k][0] * u;
            placedY = mapY() + spots[k][1] * u;
            tapAt(placedX, placedY);
            frames(1);
        }
        waitPast(Tutorial.POLICE);
        frames(4);
        expect(Tutorial.ORDER);
        shot("t08-order");
        tap("tool:orders");
        frames(3);
        // the squad put down: the one nearest where the tap was
        Squads.Squad sq = null;
        float bx = game.cam.toWorldX(placedX), by = game.cam.toWorldY(placedY), bd = Float.MAX_VALUE;
        for (Squads.Squad s : game.world().sq.list) {
            if (!s.alive || s.n == 0) continue;
            int e = s.leader(game.world().e);
            float dx = game.world().e.x[e] - bx, dy = game.world().e.y[e] - by;
            if (dx * dx + dy * dy < bd) {
                bd = dx * dx + dy * dy;
                sq = s;
            }
        }
        check(sq != null, "no squad was placed");
        for (int k = 0; k < 6 && game.inspSquad == null; k++) {
            int e = sq.leader(game.world().e);
            tapAt(game.cam.toScreenX(game.world().e.x[e]), game.cam.toScreenY(game.world().e.y[e]));
            frames(2);
        }
        check(game.inspSquad != null, "couldn't pick the squad");
        frames(2);
        shot("t08b-squad-picked");
        // somewhere they can go (a building isn't): along their own street
        int lead = sq.leader(game.world().e);
        float lx = game.cam.toScreenX(game.world().e.x[lead]), ly = game.cam.toScreenY(game.world().e.y[lead]);
        float[][] offs = {{90, 0}, {-90, 0}, {0, 90}, {0, -90}, {150, 0}, {-150, 0}, {0, 150}, {45, 0}, {0, 45}};
        for (int k = 0; k < offs.length && !game.tut.met; k++) {
            tapAt(lx + offs[k][0], ly + offs[k][1]);
            frames(1);
        }
        frames(4);
        shot("t08c-ordered");
        waitPast(Tutorial.ORDER);
        frames(4);
        expect(Tutorial.STRIKE);
        shot("t09-strike");
        tap("tool:strike");
        frames(3);
        tapAt(mapX(), mapY());
        frames(3);
        check(game.aimShown(), "no aim");
        shot("t09b-strike-aimed");
        tap("aim:go");
        frames(6);
        shot("t09c-strike");
        waitPast(Tutorial.STRIKE);
        frames(4);
        expect(Tutorial.FAST);
        shot("t10-fast-forward");
        for (int k = 0; k < 8 && Game.SPEEDS[game.speedIdx] < 60; k++) tap("speed");
        frames(10);
        shot("t10b-fast-forwarding");
        waitPast(Tutorial.FAST);
        frames(4);
        expect(Tutorial.SLOW);
        shot("t11-auto-slow");
        tap("tut:next");
        frames(4);
        check(game.speedIdx == 0, "still fast-forwarding after the slow-down step");
        expect(Tutorial.LAYERS);
        shot("t12-map-layers");
        tap("tool:map");
        frames(3);
        shot("t12b-map-tool");
        tap("ov:5");
        frames(6);
        shot("t12c-forces-layer");
        waitPast(Tutorial.LAYERS);
        frames(4);
        expect(Tutorial.ROLES);
        shot("t13-roles");
        tap("tut:role:7");
        frames(3);
        shot("t13b-a-role");
        tap("tut:next");
        frames(4);
        expect(Tutorial.NUMBERS);
        shot("t14-numbers");
        tap("statsp");
        frames(4);
        shot("t14b-numbers-open");
        waitPast(Tutorial.NUMBERS);
        frames(4);
        expect(Tutorial.REPORT);
        shot("t15-report");
        tap("pause");
        frames(4);
        shot("t15b-pause");
        tap("report");
        frames(4);
        waitPast(Tutorial.REPORT);
        frames(4);
        expect(Tutorial.DONE);
        shot("t16-done");
        tap("tut:next");
        frames(4);
        check(!game.tut.active, "the tour didn't end");
        check(game.screen() == Game.S_MENU && game.world() == null, "not back on the menu with no game");
        // nothing of it left: no save, no prefs but its own, the player's choices as they were
        check(files().equals(filesBefore), "files left behind: " + files() + " (were " + filesBefore + ")");
        check(prefs().equals(prefsBefore), "prefs changed: " + prefs() + " (were " + prefsBefore + ")");
        check(game.tutorialFingerprint().equals(stateBefore), "the game's choices changed: " + game.tutorialFingerprint() + " (were " + stateBefore + ")");
        check("true".equals(pf.pref("tut.done", null)), "the tour isn't marked done");
        check(game.tut.menuLabel().equals("Learn to play"), "menu label " + game.tut.menuLabel());
        frames(2);
        shot("t17-menu-after");

        // stopped part way, it picks up where it left off; Skip goes through every step
        tap("tut:start");
        waitPlay();
        frames(2);
        expect(Tutorial.WELCOME);
        tap("tut:next");
        for (int k = 0; k < 3; k++) tap("tut:skip");
        expect(Tutorial.INSPECT);
        tap("tut:back");
        expect(Tutorial.ZOOM_IN);
        tap("tut:leave");
        frames(2);
        check(!game.tut.active && game.screen() == Game.S_MENU && game.world() == null, "leaving didn't go back to the menu");
        check("3".equals(pf.pref("tut.step", null)), "where it stopped wasn't kept: " + pf.pref("tut.step", null));
        check(game.tut.menuLabel().startsWith("Resume"), "the menu doesn't offer to resume: " + game.tut.menuLabel());
        shot("t18-menu-resume");
        tap("tut:start");
        waitPlay();
        frames(2);
        expect(Tutorial.ZOOM_IN);
        for (int s = Tutorial.ZOOM_IN; s < Tutorial.DONE; s++) {
            expect(s);
            tap("tut:skip");
            frames(1);
        }
        expect(Tutorial.DONE);
        tap("tut:next");
        frames(2);
        check(!game.tut.active && game.world() == null, "skipping through didn't end it");
        // a load cancelled with back stops it cleanly too
        tap("tut:start");
        check(game.screen() == Game.S_LOADING, "not loading");
        game.back();
        frames(3);
        check(!game.tut.active && game.screen() == Game.S_MENU, "cancelling the load left the tour on");
        Thread.sleep(1500); // (the cancelled build finishes and is thrown away)
        frames(2);
        check(game.world() == null, "a cancelled tour's game was kept");
        check(files().equals(filesBefore), "files left behind: " + files());
        check(prefs().equals(prefsBefore), "prefs changed: " + prefs());
        check(game.tutorialFingerprint().equals(stateBefore), "the game's choices changed");
    }

    // ------------------------------------------------------------------ tips

    void tips() throws Exception {
        pf.setPref("tut.offered", "true");
        frames(2);
        tap("new");
        frames(2);
        game.ngNational = 768;
        game.ngMode = 0;
        tap("scen:1");
        frames(1);
        tap("start");
        waitPlay();
        frames(3);
        String first = game.tool;
        check(game.tut.tipT > 0 && Tutorial.tipFor(first).equals(game.tut.tipText), "no tip for the first tool (" + first + "): " + game.tut.tipText);
        shot("tip-first-tool");
        check("seen".equals(pf.pref("tip." + first, null)), "the tip wasn't remembered");
        String other = first.equals("look") ? "dead" : "look";
        tap("tool:" + other);
        frames(2);
        check(Tutorial.tipFor(other).equals(game.tut.tipText) && game.tut.tipT > 0, "no tip for the " + other + " tool");
        game.tut.tipT = 0;
        tap("tool:" + first);
        frames(2);
        check(game.tut.tipT <= 0, "a tip shown twice");
        // off in Settings: no more
        game.settings.tips = false;
        tap("tool:police");
        frames(2);
        check(game.tut.tipT <= 0 && pf.pref("tip.police", null) == null, "a tip with tips off");
        game.world().shutdown();
    }

    // ------------------------------------------------------------------ a clip, exported through the replay bar

    /** A short game, saved as a replay, played, and a stretch exported from the replay bar; the GIF copied to out. */
    void clipDemo(File out) throws Exception {
        pf.setPref("tut.offered", "true");
        pf.setPref("tip.look", "seen");
        frames(2);
        tap("new");
        frames(2);
        game.ngNational = 1536;
        game.ngMode = 0;
        tap("scen:1");
        frames(1);
        tap("start");
        waitPlay();
        World w = game.world();
        game.cam.cx = Setup.originX;
        game.cam.cy = Setup.originY;
        game.cam.zoom = 0.9f;
        frames(30);
        tap("tool:dead");
        tap("b:250");
        frames(1);
        drag(mapX() - 150, mapY() - 60, mapX() + 150, mapY() - 40, 6);
        tap("tool:police");
        tapAt(mapX() - 80, mapY() + 40);
        game.speedIdx = 4;
        for (int k = 0; k < 300; k++) frames(1);
        tap("pause");
        frames(1);
        tap("savereplay");
        frames(1);
        tap("tomenu");
        frames(1);
        tap("replays");
        frames(2);
        String name = null;
        for (String n : pf.list()) if (n.endsWith(".zcr")) name = n;
        check(name != null, "no replay saved");
        tap("rp:" + name);
        waitPlay();
        game.paused = true;
        game.seekTo(game.replay.endTick / 4);
        for (int k = 0; k < 600 && game.seekTarget >= 0; k++) {
            frames(1);
            Thread.sleep(10);
        }
        game.paused = true;
        game.cam.cx = Setup.originX;
        game.cam.cy = Setup.originY;
        game.cam.zoom = 0.75f;
        frames(10);
        tap("rb:clip");
        frames(2);
        tap("cx:span:0");
        tap("cx:secs:1");
        tap("cx:fps:1");
        tap("cx:w:2");
        tap("cx:shape:0");
        frames(2);
        shot("c01-clip-sheet");
        tap("cx:go");
        frames(2);
        shot("c02-clip-exporting");
        long t0 = System.currentTimeMillis();
        while (game.dir.clip != null && !game.dir.clip.finished() && System.currentTimeMillis() - t0 < 300000) {
            frames(1);
            Thread.sleep(30);
        }
        frames(2);
        check(game.dir.clip != null && game.dir.clip.phase == ClipExport.DONE, "the clip didn't export: " + (game.dir.clip != null ? game.dir.clip.status() : "none"));
        shot("c03-clip-done");
        File gif = new File(pf.clipDir(game.dir.clip.folder), "clip.gif");
        if (out != null) java.nio.file.Files.copy(gif.toPath(), new File(out, "sample-clip.gif").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        File frame = new File(pf.clipDir(game.dir.clip.folder), ClipExport.frameName(0));
        if (out != null) java.nio.file.Files.copy(frame.toPath(), new File(out, "sample-clip-frame_0001.png").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("clip: " + gif + " (" + gif.length() / 1024 + " KB)");
        game.world().shutdown();
    }
}
