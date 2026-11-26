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
    /** A mode's argument after it (national: the challenge code). */
    static String extra;

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
        if (a.length > 4 && a[4].equals("director")) {
            directorShots(pf);
            return;
        }
        if (a.length > 4 && a[4].equals("coop")) {
            coop();
            return;
        }
        extra = a.length > 5 ? a[5] : null;
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
        tap("tool:town");
        frames(2);
        shot("05a-town-tool");
        tap("tool:dead");
        frames(2);
        shot("05-dead-tool");
        // paint a pack
        float[] c = {W / 2f, H / 2f};
        game.touch(Game.DOWN, 0, c[0], c[1]);
        game.touch(Game.UP, 0, c[0], c[1]);
        frames(120);
        shot("06-fight");
        // a strike, aimed and then called off
        tap("tool:strike");
        frames(1);
        game.touch(Game.DOWN, 0, c[0], c[1] - 80);
        game.touch(Game.UP, 0, c[0], c[1] - 80);
        frames(2);
        shot("06b-strike-aim");
        tap("aim:no");
        frames(1);
        // the key to the map's marks
        tap("legend");
        frames(2); // the key keeps labels out from the frame after it opens
        shot("06c-legend");
        tap("legend");
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
        System.out.println("ok: " + game.world().clock() + ", " + game.world().totalZombies() + " dead, sounds " + (pf.sounds + pf.mixer.requests));
        game.world().shutdown();
        System.exit(0);
    }

    /** A commander or survivor game. */
    /** The new screens: roles, campaigns, codes, the editor, overlays, the timelapse, replays. */
    static void directorShots(DesktopPlatform pf) throws Exception {
        tap("new");
        game.ngSize = 0;
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 5000) { frames(1); Thread.sleep(50); }
        shot("d01-new-sandbox");
        tap("mode:1");
        frames(2);
        shot("d02-campaigns");
        tap("rolesheet");
        frames(1);
        shot("d03-role");
        tap("rolepick:" + Roles.MILITARY);
        frames(1);
        pf.answers.add("ZC2-1536-0-777-hold_city-military");
        tap("code");
        frames(2);
        t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 4000) { frames(1); Thread.sleep(50); }
        shot("d04-code");
        tap("editor");
        frames(2);
        t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 3000) { frames(1); Thread.sleep(50); }
        float[] mp = game.ui.find("ed:map");
        if (mp != null) { // sideways the editor may have no room for the map
            game.touch(Game.DOWN, 0, mp[0] - 40, mp[1] + 30);
            game.touch(Game.UP, 0, mp[0] - 40, mp[1] + 30);
        }
        tap("ed:z");
        tap("ed:h");
        tap("ed:goal");
        tap("ed:role");
        frames(2);
        shot("d05-editor");
        tap("ed:save");
        frames(2);
        shot("d06-yours");
        // the campaign from the code, as military commander
        pf.answers.add("ZC2-1536-0-777-hold_city-military");
        tap("code");
        frames(1);
        tap("start");
        waitPlay();
        game.speedIdx = 2;
        frames(120);
        shot("d07-military");
        tap("tool:map");
        frames(1);
        tap("m:jump");
        frames(60);
        String[] ov = {"2", "3", "4", "5", "7"};
        for (String o : ov) {
            tap("ov:" + o);
            frames(3);
            shot("d08-overlay-" + o);
        }
        tap("ov:1");
        // a formation ordered somewhere
        World w = game.world();
        tap("tool:orders");
        frames(2);
        shot("d08b-orders");
        if (!w.forces.formations.isEmpty()) {
            Forces.Formation f = w.forces.formations.get(0);
            // bring it into view first (the whole country may be cropped to fill the screen)
            game.cam.cx = f.hq.x;
            game.cam.cy = f.hq.y;
            frames(2);
            float sx = game.cam.toScreenX(f.hq.x), sy = game.cam.toScreenY(f.hq.y);
            game.touch(Game.DOWN, 0, sx, sy);
            game.touch(Game.UP, 0, sx, sy);
            frames(1);
            Region.Settlement c = w.r.city();
            float tx = game.cam.toScreenX(c.cx * 16f + 2000), ty = game.cam.toScreenY(c.cy * 16f);
            game.touch(Game.DOWN, 0, tx, ty);
            game.touch(Game.UP, 0, tx, ty);
            frames(60);
            shot("d09-formation-ordered");
            // hand it to the general, then look at it: it can be taken back
            tap("o:frelease");
            frames(2);
            tap("tool:look");
            game.inspFormation = f;
            frames(2);
            shot("d09b-formation-released");
            tap("o:ftake");
            frames(2);
            tap("o:fdone");
            frames(1);
            // the whole army to the general
            tap("o:general");
            frames(30);
            shot("d09c-general-commands");
        }
        game.speedIdx = 6; // six hours a second: strategic
        frames(90);
        shot("d10-fast-forward");
        game.speedIdx = 2;
        frames(10);
        tap("pause");
        frames(1);
        shot("d11-pause");
        tap("timelapse");
        frames(30);
        shot("d12-timelapse");
        tap("back");
        frames(1);
        tap("savereplay");
        frames(1);
        tap("report");
        frames(2);
        shot("d13-report");
        tap("back");
        frames(1);
        tap("tomenu");
        frames(2);
        tap("replays");
        frames(2);
        shot("d14-replays");
        String[] names = pf.pref("replays", "").split(",");
        tap("rp:" + names[0]);
        waitPlay();
        frames(60);
        shot("d15-replay");
        System.out.println("ok director: " + game.world().clock() + ", replay " + (game.replay != null ? game.replay.events.size() + " events" : "over"));
        game.world().shutdown();
        System.exit(0);
    }

    /** Pass-and-play: add players, hand over, and a friend's result on the report. */
    static void coop() throws Exception {
        tap("new");
        frames(2);
        tap("start");
        waitPlay();
        frames(30);
        tap("pause");
        frames(1);
        tap("players");
        frames(1);
        tap("addpl:" + Roles.PRESIDENT);
        frames(1);
        tap("addpl:" + Roles.INCIDENT);
        frames(2);
        shot("c1-players");
        tap("back");
        frames(1);
        tap("resume");
        frames(5);
        shot("c2-hud");
        tap("handover");
        frames(2);
        shot("c3-handoff");
        tap("takeover");
        frames(40);
        shot("c4-player2");
        ((DesktopPlatform) game.pf).answers.add(Game.resultCodeFor(Game.challengeCodeOf(game.world()), "Robin", 4200, 900, true));
        tap("pause");
        frames(1);
        tap("report");
        frames(1);
        tap("addresult");
        frames(2);
        shot("c5-report");
        System.out.println("ok coop: player " + (game.world().roles.current + 1) + " of " + game.world().roles.players);
        game.world().shutdown();
        System.exit(0);
    }

    /**
     * A running game on a national map, started the hidden developer's way (a challenge code with a real-scale size),
     * at the five zooms, by night, and with a building's and a district's sheets open.
     *   UiShot outDir w h density national [code]
     */
    static void nationalShots(String code) throws Exception {
        tap("new");
        frames(2);
        if (!game.applyCode(code)) throw new IllegalStateException("code refused: " + code);
        frames(2);
        shot("n0-newgame");
        tap("start");
        long t0 = System.currentTimeMillis();
        while (game.screen() != Game.S_PLAY) {
            frames(1);
            Thread.sleep(50);
            if (System.currentTimeMillis() - t0 > 300000) throw new IllegalStateException("never started");
        }
        World w = game.world();
        System.out.println("national game: " + w.r.name + ", " + w.r.W + " tiles, built in " + (System.currentTimeMillis() - t0) + " ms");
        game.speedIdx = 2;
        frames(600);
        game.speedIdx = 0;
        float ox = Setup.outbreakX, oy = Setup.outbreakY;
        Region.Settlement cap = w.r.city();
        float[][] zooms = {{0, w.r.px() / 2f, w.r.px() / 2f}, {0.0065f, ox, oy}, {0.035f, ox, oy}, {0.11f, ox, oy}, {0.5f, ox, oy}};
        String[] names = {"n1-country", "n2-region", "n3-district", "n4-town", "n5-street"};
        for (int i = 0; i < zooms.length; i++) {
            look(zooms[i][0], zooms[i][1], zooms[i][2]);
            shot(names[i]);
            System.out.printf("%s: zoom %.4f, %d chunks active, cache %d%n", names[i], game.cam.zoom, w.act.activeCount(), w.r.cache.size());
        }
        // a building's sheet at the street zoom, a district's from the district zoom
        look(0.5f, ox, oy);
        int b = -1;
        for (int k = 0; k < 400 && b < 0; k++) b = Game.buildingAt(w, ox + (k % 20 - 10) * 24, oy + (k / 20 - 10) * 24);
        if (b >= 0) {
            game.inspBuilding = b;
            frames(3);
            shot("n6-building");
            game.closeInspector();
        }
        look(0.06f, cap.cx * 16f, cap.cy * 16f);
        game.inspDistrict = w.sec.idx.district(w.r.sectorOfPx(cap.cx * 16f, cap.cy * 16f));
        frames(3);
        shot("n7-district");
        game.closeInspector();
        // night over the capital: windows, street lamps, towns glowing from high up
        w.time += (22 - w.hour() + 24) % 24 * 60;
        look(0.4f, cap.cx * 16f, cap.cy * 16f);
        shot("n8-night-street");
        look(0.02f, cap.cx * 16f, cap.cy * 16f);
        shot("n9-night-region");
        look(0, w.r.px() / 2f, w.r.px() / 2f);
        shot("n10-night-country");
        System.out.println("ok national: " + w.clock() + ", " + w.totalZombies() + " dead");
        w.shutdown();
        System.exit(0);
    }

    /** Puts the camera at a zoom (0: the whole map) and draws until the pictures and chunks in view are in. */
    static void look(float z, float x, float y) throws Exception {
        game.cam.follow = -1;
        game.cam.zoom = z <= 0 ? game.cam.minZoom : Math.max(game.cam.minZoom, z);
        game.cam.cx = x;
        game.cam.cy = y;
        for (int k = 0; k < 40; k++) {
            frames(1);
            game.world().r.cache.drain();
            Thread.sleep(10);
        }
        game.cam.zoom = z <= 0 ? game.cam.minZoom : Math.max(game.cam.minZoom, z);
        game.cam.cx = x;
        game.cam.cy = y;
        frames(30);
    }

    static void modeShots(String mode) throws Exception {
        if (mode.equals("national")) {
            nationalShots(extra != null ? extra : "ZC2-28672-0-7-lab_leak");
            return;
        }
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
            // the stick sits in the bottom-left corner of the map, above the tools
            float[] p = {game.freeL + game.ui.dp(24) + game.ui.dp(54), game.freeB - game.ui.dp(24) - game.ui.dp(54)};
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
