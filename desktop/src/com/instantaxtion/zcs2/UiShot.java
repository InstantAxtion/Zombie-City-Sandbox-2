package com.instantaxtion.zcs2;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Drives the whole game with scripted taps on a desktop and saves screenshots: the menus, a new game, the HUD,
 * the tools, the pause menu, the report. A smoke test of the interface as well as a way to look at it.
 *   java ... UiShot outDir [width height density]
 */
public final class UiShot {
    static Game game;
    static int W, H;
    static String out;

    public static void main(String[] a) throws Exception {
        out = a.length > 0 ? a[0] : "build/uishots";
        W = a.length > 1 ? Integer.parseInt(a[1]) : 540;
        H = a.length > 2 ? Integer.parseInt(a[2]) : 1170;
        float density = a.length > 3 ? Float.parseFloat(a[3]) : 1.5f;
        new File(out).mkdirs();
        File dir = new File("build/uishot-data");
        deleteAll(dir);
        DesktopPlatform pf = new DesktopPlatform(dir);
        pf.density = density;
        game = new Game(pf);
        frames(3);
        if (a.length > 4) {
            modeShots(a[4]);
            return;
        }
        shot("01-menu");
        tap("new");
        frames(2);
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 4000) { frames(1); Thread.sleep(50); }
        shot("02-new-game");
        tap("mode:1");
        frames(2);
        shot("03-new-commander");
        tap("mode:0");
        frames(1);
        tap("scen:1"); // lab leak
        frames(1);
        tap("start");
        waitPlay();
        game.speedIdx = 2;
        frames(200);
        shot("04-play");
        // tools
        tap("tool:dead");
        frames(2);
        shot("05-dead-tool");
        // paint a pack
        float[] c = {W / 2f, H / 2f};
        game.touch(Game.DOWN, 0, c[0], c[1]);
        game.touch(Game.UP, 0, c[0], c[1]);
        frames(120);
        shot("06-fight");
        tap("tool:look");
        frames(1);
        // inspect whoever is in the middle
        game.touch(Game.DOWN, 0, c[0] + 10, c[1] + 10);
        game.touch(Game.UP, 0, c[0] + 10, c[1] + 10);
        frames(2);
        shot("07-inspect");
        // zoom out to the city and the region
        for (int k = 0; k < 14; k++) { game.wheel(-1, W / 2f, H / 2f); frames(1); }
        frames(30);
        shot("08-city");
        tap("tool:map");
        frames(60);
        shot("09-region");
        tap("pause");
        frames(2);
        shot("10-pause");
        tap("report");
        frames(2);
        shot("11-report");
        tap("back");
        frames(1);
        tap("radio");
        frames(2);
        shot("12-radio");
        tap("back");
        frames(1);
        tap("save");
        frames(1);
        tap("slot:slot1");
        frames(1);
        tap("settings");
        frames(2);
        shot("13-settings");
        tap("back");
        frames(1);
        tap("tomenu");
        frames(2);
        shot("14-menu-again");
        tap("load");
        frames(1);
        tap("slot:slot1");
        waitPlay();
        frames(10);
        shot("15-loaded");
        System.out.println("ok: " + game.world().clock() + ", " + game.world().totalZombies() + " dead, sounds " + pf.sounds);
        game.world().shutdown();
        System.exit(0);
    }

    /** A commander or survivor game. */
    static void modeShots(String mode) throws Exception {
        tap("new");
        frames(2);
        tap(mode.equals("commander") ? "mode:1" : "mode:2");
        frames(1);
        tap("scen:0");
        frames(1);
        tap("start");
        waitPlay();
        game.speedIdx = 2;
        frames(150);
        shot(mode + "-1");
        if (mode.equals("survivor")) {
            // push the stick right for a while
            float[] p = {game.ui.dp(24) + game.ui.dp(54), H - game.ui.dp(54) - game.ui.dp(24) - game.ui.dp(54)};
            game.touch(Game.DOWN, 0, p[0], p[1]);
            game.touch(Game.MOVE, 0, p[0] + 60, p[1] - 10);
            frames(120);
            shot(mode + "-2");
            game.touch(Game.UP, 0, p[0] + 60, p[1] - 10);
        } else {
            for (int k = 0; k < 6; k++) { game.wheel(-1, W / 2f, H / 2f); frames(1); }
            frames(20);
            // pick the squad nearest the middle, send it somewhere
            Squads.Squad s = Tools.squadAt(game.world(), game.cam.cx, game.cam.cy, 3000);
            game.inspSquad = s;
            frames(2);
            shot(mode + "-2");
            tap("tool:town");
            frames(1);
            game.touch(Game.DOWN, 0, W / 2f, H / 2f);
            game.touch(Game.UP, 0, W / 2f, H / 2f);
            frames(2);
            shot(mode + "-3");
        }
        System.out.println("ok " + mode + ": " + game.world().clock() + ", " + game.world().totalZombies() + " dead");
        game.world().shutdown();
        System.exit(0);
    }

    static void deleteAll(File f) {
        if (f.isDirectory()) for (File c : f.listFiles()) deleteAll(c);
        f.delete();
    }

    static void waitPlay() throws Exception {
        long t0 = System.currentTimeMillis();
        while (game.screen() != Game.S_PLAY) {
            frames(1);
            Thread.sleep(20);
            if (System.currentTimeMillis() - t0 > 60000) throw new IllegalStateException("never started");
        }
    }

    static BufferedImage last;

    static void frames(int n) {
        for (int i = 0; i < n; i++) {
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            DesktopGfx g = new DesktopGfx(img.createGraphics(), W, H);
            game.frame(g, 1 / 30f);
            last = img;
        }
    }

    static void tap(String id) {
        float[] p = game.ui.find(id);
        if (p == null) throw new IllegalStateException("no button " + id + " on screen " + game.screen());
        game.touch(Game.DOWN, 0, p[0], p[1]);
        game.touch(Game.UP, 0, p[0], p[1]);
    }

    static void shot(String name) throws Exception {
        ImageIO.write(last, "png", new File(out, name + ".png"));
    }
}
