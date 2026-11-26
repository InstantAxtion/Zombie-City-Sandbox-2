package com.instantaxtion.zcs2;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import javax.imageio.ImageIO;

/** Renders the world at a few zooms to PNG files (for checking how it looks without a phone). */
public final class Shot {
    public static void main(String[] a) throws Exception {
        String out = a.length > 0 ? a[0] : "build/shots";
        new File(out).mkdirs();
        Content c = Content.load(new FileInputStream("assets/content.ini"));
        World w = Setup.newGame(c, 1024, RegionGen.COAST, 7, c.scenario("lab_leak"), 4);
        DesktopPlatform pf = new DesktopPlatform(new File("build/desktop"));
        WorldView view = new WorldView(w, pf);
        Camera cam = new Camera();
        int W = 1080 / 2, H = 2100 / 2;
        cam.setScreen(W, H, w.r.px());
        Tools.paintZombies(w, Setup.originX + 150, Setup.originY, 300, 250, -1);
        cam.cx = Setup.originX;
        cam.cy = Setup.originY;
        cam.zoom = 0.9f;
        for (int s = 0; s < 45 * 20; s++) {
            w.camX0 = cam.x0(); w.camY0 = cam.y0(); w.camX1 = cam.x1(); w.camY1 = cam.y1(); w.camDetail = true;
            w.step();
        }
        float[] zooms = {1.6f, 0.9f, 0.4f, 0.15f, cam.minZoom};
        for (float z : zooms) {
            cam.zoom = z;
            cam.cx = Setup.originX;
            cam.cy = Setup.originY;
            cam.update(0, w.r.px());
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            DesktopGfx g = new DesktopGfx(img.createGraphics(), W, H);
            for (int k = 0; k < 40; k++) view.draw(g, cam, 0.05f); // let the chunks paint
            ImageIO.write(img, "png", new File(out, "z" + z + ".png"));
        }
        w.shutdown();
    }
}
