package com.instantaxtion.zcs2;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The game in a desktop window (for playing and testing without a phone). Mouse = one finger; the wheel zooms;
 * space pauses, 1-5 set the speed, M toggles the region map, Escape is the phone's back button.
 *   ./play.sh [width height]
 */
public final class DesktopMain extends JPanel {
    private final Game game;
    private BufferedImage frame;
    private long last = System.nanoTime();

    DesktopMain(int w, int h) {
        setPreferredSize(new Dimension(w, h));
        DesktopPlatform pf = new DesktopPlatform(new File(System.getProperty("user.home"), ".zcs2"));
        pf.density = 1.25f;
        game = new Game(pf);
        MouseAdapter m = new MouseAdapter() {
            public void mousePressed(MouseEvent e) { game.touch(Game.DOWN, 0, e.getX(), e.getY()); }
            public void mouseDragged(MouseEvent e) { game.touch(Game.MOVE, 0, e.getX(), e.getY()); }
            public void mouseReleased(MouseEvent e) { game.touch(Game.UP, 0, e.getX(), e.getY()); }
            public void mouseWheelMoved(MouseWheelEvent e) { game.wheel((float) -e.getPreciseWheelRotation(), e.getX(), e.getY()); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
        setFocusable(true);
        addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    if (!game.back()) System.exit(0);
                } else game.key(Character.toLowerCase(e.getKeyChar()));
            }
        });
        new Timer(16, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                repaint();
            }
        }).start();
    }

    @Override
    protected void paintComponent(Graphics g) {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        if (frame == null || frame.getWidth() != w || frame.getHeight() != h) frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        long now = System.nanoTime();
        float dt = (now - last) / 1e9f;
        last = now;
        game.frame(new DesktopGfx(frame.createGraphics(), w, h), dt);
        g.drawImage(frame, 0, 0, null);
    }

    public static void main(final String[] a) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                int w = a.length > 1 ? Integer.parseInt(a[0]) : 540, h = a.length > 1 ? Integer.parseInt(a[1]) : 960;
                JFrame f = new JFrame("Zombie City Sandbox 2");
                DesktopMain p = new DesktopMain(w, h);
                f.setContentPane(p);
                f.pack();
                f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                f.setVisible(true);
                p.requestFocusInWindow();
            }
        });
    }
}
