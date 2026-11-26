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
        if (a.length > 4 && a[4].equals("director14")) {
            director14Shots(pf);
            return;
        }
        if (a.length > 4 && a[4].equals("tools")) {
            toolShots();
            return;
        }
        if (a.length > 4 && a[4].equals("legible")) {
            legibleShots();
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

    /** A tap on the editor's map at (fx, fy), fractions of the map's side. */
    static void edMap(float fx, float fy) {
        DirectorScreens d = game.dir;
        tapAt(d.mapX + fx * d.mapS, d.mapY + fy * d.mapS);
    }

    /** Where a town is on the editor's map, as fractions. */
    static float[] edTown(int id) {
        java.util.List<Region.Settlement> l = game.previewSettlements();
        int side = game.previewSide();
        Region.Settlement s = l.get(id);
        return new float[]{s.cx / (float) side, s.cy / (float) side};
    }

    static void waitSeek() throws Exception {
        long t0 = System.currentTimeMillis();
        while (game.seekTarget >= 0 || game.seekProgress() >= 0) {
            frames(1);
            Thread.sleep(5);
            if (System.currentTimeMillis() - t0 > 120000) throw new IllegalStateException("the seek never finished");
        }
        frames(2);
    }

    /** 1.4: the editor's tabs (places, rules, goals, triggers), a scripted game, the replay's scrubber and camera path. */
    static void tp(String id) {
        frames(1);
        tap(id);
    }

    static void director14Shots(DesktopPlatform pf) throws Exception {
        tp("new");
        game.ngSize = 0;
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 4000) { frames(1); Thread.sleep(50); }
        tp("editor");
        t0 = System.currentTimeMillis();
        while ((game.previewSide() == 0 || System.currentTimeMillis() - t0 < 1500) && System.currentTimeMillis() - t0 < 30000) { frames(1); Thread.sleep(50); }
        frames(2);
        boolean map = game.ui.find("ed:map") != null;
        float[] cap = edTown(0);
        if (map) edMap(cap[0] + 0.02f, cap[1]);
        tp("ed:strain");
        tp("ed:z");
        frames(2);
        shot("e01-editor-start");
        tp("ed:tab:1");
        frames(1);
        if (map) {
            edMap(cap[0] - 0.12f, cap[1] - 0.08f);
            tp("ed:plw");
            edMap(cap[0] + 0.1f, cap[1] + 0.1f);
            tp("ed:plk");
            frames(1);
            edMap(cap[0] + 0.03f, cap[1] + 0.02f);
            tp("ed:plw");
            frames(1);
            edMap(cap[0] + 0.05f, cap[1] + 0.02f);
            tp("ed:plk");
            frames(1);
            edMap(cap[0] + 0.04f, cap[1] + 0.04f);
            tp("ed:plw");
            tp("ed:plw");
            frames(1);
            edMap(cap[0] + 0.045f, cap[1] + 0.045f);
        }
        frames(2);
        shot("e02-editor-places");
        tp("ed:tab:2");
        tp("ed:rule:0");
        tp("ed:rule:0");
        tp("ed:rule:6");
        tp("ed:rule:6");
        tp("ed:rule:6");
        frames(2);
        shot("e03-editor-rules");
        tp("ed:tab:3");
        frames(1);
        tp("ed:gdays");
        tp("ed:gdays");
        tp("ed:gdays");
        if (map) edMap(cap[0], cap[1]);
        frames(1);
        tp("ed:gpct");
        tp("ed:gloss");
        tp("ed:gloss");
        tp("ed:gloss");
        frames(2);
        shot("e04-editor-goals");
        tp("ed:tab:4");
        frames(1);
        // 1: after 3 hours, a horde of 2,000 out in the fields
        if (map) edMap(cap[0] - 0.15f, cap[1] + 0.12f);
        tp("ed:tadd");
        frames(1);
        // 2: more than 1,000 dead in the capital: an air strike on it
        tp("ed:tw");
        frames(1);
        tp("ed:ttown");
        if (map) edMap(cap[0], cap[1]);
        tp("ed:tdo");
        frames(1);
        tp("ed:tplace");
        if (map) edMap(cap[0] + 0.01f, cap[1] - 0.01f);
        tp("ed:tadd");
        frames(1);
        // 3: the capital falls: the weather turns to storm
        tp("ed:tw");
        frames(1);
        tp("ed:tdo");
        tp("ed:tdo");
        frames(1);
        tp("ed:tadd");
        frames(1);
        // 4: after 30 minutes, a message
        tp("ed:tw");
        tp("ed:tw");
        frames(1);
        for (int k = 0; k < 2; k++) {
            tp("ed:tt");
            frames(1);
        }
        for (int k = 0; k < 7; k++) tp("ed:tt");
        tp("ed:tdo");
        frames(1);
        pf.answers.add("Reports of the dead in the hills");
        tp("ed:tmsg");
        frames(2);
        tp("ed:tadd");
        frames(2);
        shot("e05-editor-triggers");
        tp("ed:save");
        frames(2);
        shot("e06-yours");
        tp("start");
        waitPlay();
        game.speedIdx = 4;
        frames(150);
        shot("e07-scripted-game");
        game.speedIdx = 6;
        frames(40);
        game.speedIdx = 4;
        frames(60);
        tp("pause");
        frames(1);
        tp("savereplay");
        frames(1);
        tp("tomenu");
        frames(2);
        tp("replays");
        frames(2);
        String[] names = pf.pref("replays", "").split(",");
        tp("rp:" + names[0]);
        waitPlay();
        frames(30);
        shot("e08-replay-bar");
        // to two thirds of the way along (played on fast), then back to a quarter (from a snapshot)
        DirectorScreens d = game.dir;
        float[] sc = game.ui.find("rb:scrub");
        tapAt(d.rbX0 + (d.rbX1 - d.rbX0) * 0.66f, sc[1]);
        waitSeek();
        shot("e09-replay-seeked");
        tapAt(d.rbX0 + (d.rbX1 - d.rbX0) * 0.25f, sc[1]);
        frames(1);
        shot("e10-replay-going-back");
        waitSeek();
        // a camera path: three keyframes at different times and places
        World w = game.world();
        Region.Settlement c = w.r.city();
        game.paused = true;
        game.seekTo(game.replay.endTick / 5);
        waitSeek();
        game.cam.cx = c.cx * 16f;
        game.cam.cy = c.cy * 16f;
        game.cam.zoom = 0.12f;
        frames(2);
        tp("rb:key");
        game.seekTo(game.world().tick + game.replay.endTick / 4);
        waitSeek();
        game.cam.cx = c.cx * 16f + 1500;
        game.cam.zoom = 0.5f;
        frames(2);
        tp("rb:key");
        game.seekTo(game.world().tick + game.replay.endTick / 4);
        waitSeek();
        game.cam.cx = c.cx * 16f + 600;
        game.cam.cy = c.cy * 16f + 800;
        game.cam.zoom = 1.0f;
        frames(2);
        tp("rb:key");
        frames(2);
        shot("e11-camera-keyframes");
        tp("rb:path");
        tapAt(d.rbX0 + (d.rbX1 - d.rbX0) * 0.3f, sc[1]);
        waitSeek();
        tp("rb:play");
        frames(45);
        shot("e12-camera-path-playing");
        tp("rb:cut");
        frames(2);
        tp("pause");
        frames(1);
        tp("tomenu");
        frames(2);
        tp("replays");
        frames(2);
        shot("e13-replays-with-cut");
        tp("back");
        frames(1);
        tp("settings");
        frames(1);
        pf.files.add(new String[]{"my_mod.ini", "[weapon pistol]\nrange = far\n"});
        tp("mod:import");
        frames(2);
        game.scroll = 100000;
        frames(2);
        shot("e14-settings-mods-import-error");
        System.out.println("ok director 1.4: replay " + (game.replay != null ? game.replay.snaps.size() + " snapshots" : "over"));
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

    /**
     * A normal day (Life): a calm morning and a rush hour, in the capital's centre and in a suburb, then the first
     * minutes of an outbreak with the traffic fleeing. The weather is set clear for the pictures.
     *   UiShot outDir w h density life [code] [only]
     */
    static void lifeShots(String code) throws Exception {
        tap("new");
        frames(2);
        if (code.startsWith("ZC2-") && !game.applyCode(code)) throw new IllegalStateException("code refused: " + code);
        frames(2);
        tap("start");
        long t0 = System.currentTimeMillis();
        while (game.screen() != Game.S_PLAY) {
            frames(1);
            Thread.sleep(50);
            if (System.currentTimeMillis() - t0 > 300000) throw new IllegalStateException("never started");
        }
        World w = game.world();
        System.out.println("game: " + w.r.name + ", " + w.r.W + " tiles");
        game.speedIdx = 0;
        game.tool = "look";
        float[] centre = ScaleProbe.lifeSpot(w, "centre"), suburb = ScaleProbe.lifeSpot(w, "suburb");
        String[] names = {"l1-morning-centre", "l2-morning-suburb", "l3-rush-centre", "l4-rush-suburb"};
        float[] hours = {10.6f, 10.7f, 17.5f, 17.6f};
        for (int k = 0; k < 4; k++) {
            float[] at = k % 2 == 0 ? centre : suburb;
            toHour(w, hours[k]);
            w.weather.state = Weather.CLEAR;
            look(0.5f, at[0], at[1]);
            run(w, 40);
            shot(names[k]);
            lifeLine(names[k], w);
        }
        // the outbreak's first minutes in the centre, at the evening rush
        toHour(w, 17.3f);
        look(0.5f, centre[0], centre[1]);
        run(w, 20);
        Tools.paintZombies(w, centre[0] + 300, centre[1] - 900, 300, 260, -1);
        run(w, 30);
        shot("l5-outbreak-1min");
        lifeLine("l5-outbreak-1min", w);
        run(w, 60);
        shot("l6-outbreak-2min");
        lifeLine("l6-outbreak-2min", w);
        look(0.32f, centre[0], centre[1] - 400);
        shot("l7-outbreak-wide");
        lifeLine("l7-outbreak-wide", w);
        w.shutdown();
        System.exit(0);
    }

    /** Game seconds at 1x with the camera where it is (stepped without drawing), then a few frames drawn. */
    static void run(World w, float secs) {
        for (int k = 0; k < secs * 20; k++) w.step();
        frames(4);
    }

    /** Squads out on patrol (in their cars or on foot). */
    static int patrols(World w) {
        int n = 0;
        for (Squads.Squad s : w.sq.list) if (s.alive && s.order == Squads.O_PATROL) n++;
        return n;
    }

    static void toHour(World w, float h) {
        w.time += ((h - w.hour()) % 24 + 24) % 24 * 60;
    }

    static void lifeLine(String name, World w) {
        Life l = w.life;
        int civ = 0, cops = 0, sol = 0;
        for (int i = 0; i < w.e.top; i++)
            if (w.e.loc[i] == Ents.FREE) {
                if (w.e.kind[i] == Ents.CIV) civ++;
                else if (w.e.kind[i] == Ents.COP) cops++;
                else if (w.e.kind[i] == Ents.SOLDIER) sol++;
            }
        System.out.printf("%s: %s cars %d parked %d buses %d abandoned %d trains %d, on foot %d, police %d, soldiers %d, patrols %d, dead %d, ledger %d%n", name, w.clock(), l.cars,
                l.parked, l.buses, l.abandoned, l.trains(), civ, cops, sol, patrols(w), w.totalZombies(), Audit.ledger(w));
    }

    static void modeShots(String mode) throws Exception {
        if (mode.equals("life")) {
            lifeShots(extra != null ? extra : "ZC2-28672-0-7-sandbox");
            return;
        }
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

    /** What makes the outbreak legible: the numbers panel, callouts, the inspector's tracer, a town's fall, an alert, the report. */
    static void legibleShots() throws Exception {
        tap("new");
        frames(2);
        tap("mode:0");
        frames(1);
        tap("scen:1"); // lab leak
        frames(1);
        tap("start");
        waitPlay();
        World w = game.world();
        game.speedIdx = 2;
        frames(150);
        // a fight in the middle, and police into it
        float cx = game.cam.cx, cy = game.cam.cy;
        Tools.paintZombies(w, cx + 60, cy, 120, 120, -1);
        frames(240);
        // the numbers, open
        if (!game.statsOpen) tap("statsp");
        // a few things happening on screen at once (the real ones are rarer than a screenshot needs)
        Records rc = w.stats.rec;
        rc.callout("Barricade broken", cx - 120, cy - 160, Records.C_BREACH);
        rc.callout("Horde sighted", cx + 140, cy + 200, Records.C_HORDE);
        rc.callout("Officer down", cx - 60, cy + 80, Records.C_LOSS);
        rc.callout("Officer down", cx - 50, cy + 90, Records.C_LOSS);
        rc.callout("Bridge blown", cx + 100, cy - 60, Records.C_BLAST);
        frames(8);
        shot("L1-hud-callouts");
        // an armed person, with their last ten seconds
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < w.e.top; i++) {
            if (w.e.loc[i] != Ents.FREE || !w.e.armed(i) || rc.traceLength(i) < 5) continue;
            float d = Math.abs(w.e.x[i] - cx) + Math.abs(w.e.y[i] - cy);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        if (best >= 0) {
            game.inspEnt = best;
            game.cam.cx = w.e.x[best];
            game.cam.cy = w.e.y[best];
            frames(3);
            shot("L2-inspect-unit");
        }
        // one who fell, and why (if nobody has yet, one is left with no ammunition among the dead)
        if (best >= 0 && w.stats.fallen.isEmpty()) {
            w.e.ammo[best] = 0;
            w.e.hp[best] = Math.min(w.e.hp[best], 10);
            Tools.paintZombies(w, w.e.x[best], w.e.y[best], 30, 30, w.c.ztype("walker").index);
            for (int k = 0; k < 600 && w.e.loc[best] == Ents.FREE; k++) w.step();
            frames(2);
        }
        int fell = -1;
        for (int i = 0; i < w.e.top; i++)
            if (w.e.loc[i] == Ents.BODY && (w.e.kind[i] == Ents.COP || w.e.kind[i] == Ents.SOLDIER) && rc.deathReason(w.e.uid[i]).length() > 0) fell = i;
        if (fell >= 0) {
            game.inspEnt = fell;
            frames(3);
            shot("L3-inspect-fallen");
        }
        game.closeInspector();
        // a town that has been given up
        Region.Settlement town = null;
        for (Region.Settlement t : w.r.settlements) if (t.kind == Region.S_TOWN && !t.capital && (town == null || t.residents > town.residents)) town = t;
        if (town != null) {
            Tools.paintZombies(w, town.cx * 16f, town.cy * 16f, 300, 300, -1);
            frames(60);
            w.command.abandon(null, town.id);
            frames(200);
            game.inspSettle = town.id;
            game.cam.flyTo(town.cx * 16f, town.cy * 16f, 0.15f);
            frames(60);
            shot("L4-inspect-town");
            game.closeInspector();
        }
        // fast-forward stopped for a big event
        game.speedIdx = 4;
        frames(5);
        rc.alert("A horde of 2,400 is near " + (town != null ? town.name : "the city"), cx, cy, Records.A_HORDE);
        frames(4);
        shot("L5-alert");
        // the report: the top, then the story further down
        tap("pause");
        frames(2);
        tap("report");
        frames(2);
        shot("L6-report");
        for (int k = 1; k <= 4; k++) {
            game.scroll = k * H * 0.8f;
            frames(2);
            shot("L6-report-" + k);
        }
        System.out.println("ok legible: " + w.clock() + ", callouts " + rc.calloutSeq + ", alerts " + rc.alertSeq + ", fallen " + w.stats.fallen.size());
        w.shutdown();
        System.exit(0);
    }

    static void deleteAll(File f) {
        if (f.isDirectory()) for (File c : f.listFiles()) deleteAll(c);
        f.delete();
    }

    /** The player's tools: the palettes from content.ini, Build and Clear, orders, possession and the rules sheet. */
    static void toolShots() throws Exception {
        tap("new");
        frames(2);
        tap("mode:0");
        frames(1);
        tap("scen:1"); // lab leak, as God
        frames(1);
        tap("rules");
        frames(1);
        tap("rule:0");
        tap("rule:6");
        tap("rule:6");
        frames(1);
        shot("t01-rules-new-game");
        tap("rules:close");
        frames(1);
        shot("t02-new-game-rules-changed");
        tap("start");
        waitPlay();
        game.speedIdx = 0;
        frames(60);
        float cx = W / 2f, cy = H / 2f;
        tap("tool:dead");
        frames(2);
        shot("t03-dead-palette");
        tap("b:250000");
        frames(1);
        shot("t03b-dead-250k");
        tap("b:10");
        tap("tool:police");
        frames(2);
        shot("t04-police-officers");
        tap("cat:police:veh");
        frames(2);
        shot("t05-police-vehicles");
        tap("cat:police:sq");
        frames(2);
        shot("t06-police-squads");
        tap("tool:army");
        frames(1);
        tap("cat:army:sq");
        frames(2);
        tapAt(cx - 60, cy + 60);
        frames(2);
        shot("t07-army-squads-placed");
        tap("cat:army:units");
        frames(2);
        shot("t08-army-soldiers");
        tap("tool:build");
        frames(2);
        shot("t09-build-defences");
        game.cam.follow = -1;
        game.cam.zoom = 1.5f;
        frames(3);
        String[] kinds = {"bd:mg", "bd:sandbags", "bd:wire", "bd:wire", "bd:ammo", "bd:medkit", "bd:post"};
        float[][] at = {{40, -20}, {40, 30}, {150, -40}, {150, 40}, {-40, 60}, {0, 90}, {-60, -80}};
        for (int k = 0; k < kinds.length; k++) {
            tap(kinds[k]);
            float[] p = walkAt(cx + at[k][0] * 2, cy + at[k][1] * 2);
            tapAt(p[0], p[1]);
        }
        tap("bd:barricade");
        game.touch(Game.DOWN, 0, cx + 150, cy - 90);
        for (int k = 0; k < 8; k++) game.touch(Game.MOVE, 0, cx + 150, cy - 90 + k * 22);
        game.touch(Game.UP, 0, cx + 150, cy + 80);
        frames(30);
        shot("t10-defences-placed");
        game.cam.zoom = 0.75f;
        frames(2);
        tap("cat:build:ground");
        frames(2);
        shot("t11-build-ground");
        tap("cat:build:people");
        frames(2);
        shot("t12-build-people");
        tap("tool:erase");
        frames(2);
        shot("t13-clear-palette");
        // orders: the squad placed above, its order modes and stance
        tap("tool:orders");
        frames(1);
        tapAt(cx - 60, cy + 60);
        frames(2);
        tap("om:route");
        tapAt(walkAt(cx - 120, cy + 140));
        tapAt(walkAt(cx + 20, cy + 180));
        tapAt(walkAt(cx + 120, cy + 120));
        frames(2);
        shot("t14-orders-route");
        tap("o:go");
        frames(20);
        tap("om:cordon");
        tapAt(walkAt(cx - 100, cy - 120));
        frames(2);
        shot("t15-orders-cordon-first-end");
        tapAt(walkAt(cx + 60, cy - 140));
        frames(30);
        shot("t16-orders-cordon");
        // a vehicle: a gunship placed and sent to orbit
        tap("tool:army");
        frames(1);
        tap("cat:army:veh");
        frames(1);
        tap("v:gunship");
        tapAt(cx, cy - 150);
        frames(10);
        tap("tool:look");
        frames(1);
        int v = -1;
        for (int i = 0; i < game.w.veh.top; i++)
            if (game.w.veh.usable(i) && game.w.veh.type(i).id.equals("gunship") && (v < 0 || game.w.veh.uid[i] > game.w.veh.uid[v])) v = i;
        if (v >= 0) {
            game.inspVeh = v;
            frames(2);
            shot("t17-vehicle-inspector");
            tap("i:vorders");
            frames(2);
            tap("vo:2");
            tapAt(cx + 100, cy + 40);
            frames(20);
            shot("t18-vehicle-orbit");
            tap("vo:drive");
            frames(20);
            shot("t19-take-the-wheel");
            tap("letgo");
            frames(2);
            tap("vo:done");
        }
        // a person: control them with the stick
        tap("tool:look");
        frames(1);
        int who = -1;
        for (int i = 0; i < game.w.e.top; i++)
            if (game.w.e.loc[i] == Ents.FREE && game.w.e.armed(i)) {
                who = i;
                break;
            }
        if (who >= 0) {
            game.inspEnt = who;
            frames(2);
            shot("t20-person-inspector");
            tap("i:possess");
            frames(40);
            shot("t21-possessed");
            tap("letgo");
            frames(2);
        }
        frames(150);
        tap("pause");
        frames(2);
        shot("t22-pause-rules-item");
        tap("rules");
        frames(2);
        shot("t23-rules-in-game");
        tap("rules:close");
        frames(1);
        tap("resume");
        frames(2);
        // a formation's postures, from the whole country
        tap("tool:orders");
        frames(1);
        Forces.Formation fm = null;
        for (Forces.Formation f : game.w.forces.formations) if (f.alive) { fm = f; break; }
        if (fm != null) {
            game.inspFormation = fm;
            game.cam.flyTo(fm.hq.x, fm.hq.y, 0.08f);
            frames(60);
            shot("t24-formation-orders");
        }
    }

    /** A screen point near (sx, sy) on open ground (not a building). */
    static float[] walkAt(float sx, float sy) {
        Camera c = game.cam;
        for (int r = 0; r < 40; r++)
            for (int a = 0; a < 12; a++) {
                float x = sx + (float) Math.cos(a * 0.5236) * r * 6, y = sy + (float) Math.sin(a * 0.5236) * r * 6;
                float wx = c.toWorldX(x), wy = c.toWorldY(y);
                if (game.w.r.walkablePx(wx, wy) && game.w.r.walkablePx(wx + 12, wy) && game.w.r.walkablePx(wx - 12, wy)) return new float[]{x, y};
            }
        return new float[]{sx, sy};
    }

    static void tapAt(float[] p) {
        tapAt(p[0], p[1]);
    }

    static void tapAt(float x, float y) {
        game.touch(Game.DOWN, 0, x, y);
        game.touch(Game.UP, 0, x, y);
        frames(1);
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
