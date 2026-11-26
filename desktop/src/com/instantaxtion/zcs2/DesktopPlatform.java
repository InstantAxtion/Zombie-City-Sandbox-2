package com.instantaxtion.zcs2;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/** The desktop's files, settings and (silent) sound. */
public final class DesktopPlatform implements Platform {
    private final File dir;
    private final Properties prefs = new Properties();
    public float density = 2f;
    public int sounds;

    public DesktopPlatform(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    public GImage newImage(int w, int h) { return new DesktopGfx.Img(w, h); }
    public float density() { return density; }
    public InputStream openAsset(String name) throws IOException { return new FileInputStream(new File("assets", name)); }
    public InputStream openRead(String name) throws IOException { return new FileInputStream(new File(dir, name)); }
    public OutputStream openWrite(String name) throws IOException { return new FileOutputStream(new File(dir, name)); }
    public boolean exists(String name) { return new File(dir, name).exists(); }
    public void delete(String name) { new File(dir, name).delete(); }
    public boolean rename(String from, String to) {
        File t = new File(dir, to);
        t.delete();
        return new File(dir, from).renameTo(t);
    }
    public String[] list() {
        File[] fs = dir.listFiles();
        if (fs == null) return new String[0];
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (File f : fs) if (f.isFile()) out.add(f.getName());
        return out.toArray(new String[0]);
    }
    public String pref(String key, String def) { return prefs.getProperty(key, def); }
    public void setPref(String key, String value) { prefs.setProperty(key, value); }
    public void playSound(String name, float volume, float pan) { sounds++; }
    public void setMusic(int track, float volume) { }
    /** Silent: nothing is rendered, but the game's calls go through it (and are counted) as on a phone. */
    public Mixer mixer = new Mixer(new Synth());
    public Mixer mixer() { return mixer; }
    public void vibrate(int ms) { }
    public void shareImage(GImage img) { }
    public void exit() { }

    /** Scripted answers for tests (taken first); else a dialog when there's a screen. */
    public final java.util.ArrayDeque<String> answers = new java.util.ArrayDeque<String>();

    /** The text last offered in a dialog (for tests: e.g. a result code to share). */
    public String lastShown;

    public void askText(String title, String current, TextAnswer answer) {
        lastShown = current;
        if (!answers.isEmpty()) {
            String a = answers.poll();
            if (answer != null) answer.answer(a);
            return;
        }
        if (answer == null) return;
        if (java.awt.GraphicsEnvironment.isHeadless()) return;
        String s = javax.swing.JOptionPane.showInputDialog(null, title, current);
        if (s != null) answer.answer(s);
    }
}
