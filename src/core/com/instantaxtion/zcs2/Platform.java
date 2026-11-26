package com.instantaxtion.zcs2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** What the game needs from the phone (or the desktop): pictures, files, settings, sound. */
public interface Platform {
    GImage newImage(int w, int h);

    /** Screen density: 1 on a 160 dpi screen, about 2.5-3.5 on a modern phone. */
    float density();

    InputStream openAsset(String name) throws IOException;

    InputStream openRead(String name) throws IOException;

    OutputStream openWrite(String name) throws IOException;

    boolean exists(String name);

    void delete(String name);

    String pref(String key, String def);

    void setPref(String key, String value);

    /** Called with the sound events of this frame; volume 0..1, pan -1..1. */
    void playSound(String name, float volume, float pan);

    void setMusic(int track, float volume);

    void vibrate(int ms);

    /** Saves a screenshot and offers to share it (may do nothing on a desktop). */
    void shareImage(GImage img);

    /** Leaves the app. */
    void exit();

    /** Asks the player to type something (a challenge code). The answer comes back later, or never if cancelled. */
    void askText(String title, String current, TextAnswer answer);

    interface TextAnswer {
        void answer(String text);
    }
}
