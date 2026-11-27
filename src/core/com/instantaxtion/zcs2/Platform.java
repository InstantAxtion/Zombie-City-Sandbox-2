package com.instantaxtion.zcs2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** What the game needs from the phone (or the desktop): pictures, files, settings, sound. */
public interface Platform {
    GImage newImage(int w, int h);

    /** Screen density: 1 on a 160 dpi screen, about 2.5-3.5 on a modern phone. */
    float density();

    /** The most heap the app may use, in bytes: caches and pictures are sized from it. */
    long maxMemory();

    InputStream openAsset(String name) throws IOException;

    InputStream openRead(String name) throws IOException;

    OutputStream openWrite(String name) throws IOException;

    boolean exists(String name);

    void delete(String name);

    /** Renames a file in the app's own files, replacing any file already called {@code to}. */
    boolean rename(String from, String to);

    /** The names of the app's own files (not directories). */
    String[] list();

    String pref(String key, String def);

    void setPref(String key, String value);

    /** Called with the sound events of this frame; volume 0..1, pan -1..1. */
    void playSound(String name, float volume, float pan);

    /** The score's cue (see {@link Mixer#CUE_NAMES}) and its volume. */
    void setMusic(int track, float volume);

    /** The mixer the game's sound goes through, or null where there's no sound. */
    Mixer mixer();

    void vibrate(int ms);

    /** Saves a screenshot and offers to share it (may do nothing on a desktop). */
    void shareImage(GImage img);

    /** Leaves the app. */
    void exit();

    /**
     * Asks the player to type something (a challenge code). The answer comes back later, or never if cancelled,
     * and may come on another thread: the game hands it to its own thread with {@code Game.post}.
     */
    void askText(String title, String current, TextAnswer answer);

    interface TextAnswer {
        void answer(String text);
    }

    /**
     * Asks the player for a file from elsewhere on the device (a content.ini to import: Android's file picker, a
     * file chooser on a desktop). Its name and bytes come back later, maybe on another thread, or never if
     * cancelled.
     */
    void pickFile(String title, FileAnswer answer);

    /** Offers a text file to other apps (to share a content.ini: Android's share sheet). */
    void shareText(String fileName, String text);

    /**
     * Opens a file of an exported clip for writing (a numbered frame or the GIF), in a folder of its own: under the
     * app's files on a phone (clips/folder), under the game's data folder on a desktop. Called on a background thread.
     */
    OutputStream openClip(String folder, String name) throws IOException;

    /** Where an exported clip's folder is, in words for the player. */
    String clipPlace(String folder);

    /**
     * Hands a file of an exported clip to the device: share it (Android's share sheet) or keep it where photos go
     * (Pictures, through the media store, where the phone has it). Returns what happened, in words, or null if it can't.
     */
    String publishClip(String folder, String name, boolean share);

    interface FileAnswer {
        void file(String name, byte[] data);
    }
}
