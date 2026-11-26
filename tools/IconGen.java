import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Draws the launcher icon at every density: a region map with a red outbreak spreading from a city. */
public class IconGen {
    public static void main(String[] a) throws Exception {
        String[][] d = {{"mdpi", "48"}, {"hdpi", "72"}, {"xhdpi", "96"}, {"xxhdpi", "144"}, {"xxxhdpi", "192"}};
        for (String[] e : d) {
            int s = Integer.parseInt(e[1]);
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float k = s / 48f;
            g.setClip(new RoundRectangle2D.Float(0, 0, s, s, 12 * k, 12 * k));
            g.setColor(new Color(0x6E8F4E));
            g.fillRect(0, 0, s, s);
            g.setColor(new Color(0x3C6E9A));
            g.fill(new Ellipse2D.Float(-14 * k, 26 * k, 30 * k, 30 * k));
            g.setColor(new Color(0x3E6337));
            g.fill(new Ellipse2D.Float(30 * k, -6 * k, 24 * k, 20 * k));
            g.setColor(new Color(0x3A3A3E));
            g.setStroke(new BasicStroke(2.2f * k));
            g.draw(new Line2D.Float(0, 34 * k, 48 * k, 14 * k));
            g.draw(new Line2D.Float(24 * k, 0, 30 * k, 48 * k));
            g.setColor(new Color(0x8C8A86));
            g.fill(new Rectangle2D.Float(13 * k, 13 * k, 20 * k, 20 * k));
            g.setColor(new Color(0x4A4A4E));
            for (int i = 0; i < 3; i++) {
                g.fill(new Rectangle2D.Float(13 * k + i * 7 * k, 13 * k, 1.4f * k, 20 * k));
                g.fill(new Rectangle2D.Float(13 * k, 13 * k + i * 7 * k, 20 * k, 1.4f * k));
            }
            g.setColor(new Color(200, 24, 24, 150));
            g.fill(new Ellipse2D.Float(15 * k, 15 * k, 16 * k, 14 * k));
            g.setColor(new Color(230, 40, 30, 200));
            g.fill(new Ellipse2D.Float(19 * k, 18 * k, 8 * k, 7 * k));
            g.setColor(new Color(255, 255, 255, 230));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (14 * k)));
            g.setColor(new Color(0, 0, 0, 160));
            g.drawString("2", 35 * k + 1, 45 * k + 1);
            g.setColor(Color.WHITE);
            g.drawString("2", 35 * k, 45 * k);
            new File("res/mipmap-" + e[0]).mkdirs();
            ImageIO.write(img, "png", new File("res/mipmap-" + e[0] + "/ic_launcher.png"));
        }
    }
}
