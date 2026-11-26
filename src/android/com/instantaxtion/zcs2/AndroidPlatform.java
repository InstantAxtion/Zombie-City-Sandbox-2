package com.instantaxtion.zcs2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The phone: files, settings, sound (an AudioTrack fed by the game's mixer), vibration, sharing. */
final class AndroidPlatform implements Platform {
    private final Activity act;
    private final SharedPreferences prefs;
    private final Synth synth = new Synth();
    private final Mixer mixer = new Mixer(synth);
    private final short[][] tracks = new short[2][];
    private Thread audio;
    private int wantTrack = -1;
    private float wantVolume;

    AndroidPlatform(Activity act) {
        this.act = act;
        prefs = act.getSharedPreferences("zcs2", Context.MODE_PRIVATE);
        // the score takes a moment to make: do it out of the way
        new Thread(new Runnable() {
            public void run() {
                tracks[0] = synth.music(0.15f);
                tracks[1] = synth.music(0.85f);
                if (wantTrack >= 0) mixer.setMusic(tracks[wantTrack], wantVolume);
                for (String s : new String[]{"pistol", "rifle", "mg", "shotgun", "boom", "scream", "click"}) synth.get(s);
            }
        }, "synth").start();
        startAudio();
    }

    /** Each audio thread runs while the generation it was started with is current; stopping moves it on. */
    private volatile int audioGen;
    private boolean audioRunning;

    void startAudio() {
        if (audioRunning) return;
        audioRunning = true;
        // a quick pause and resume: let the old thread finish its last buffer before a new one mixes
        Thread old = audio;
        if (old != null) {
            try {
                old.join(300);
            } catch (InterruptedException ignored) {
            }
        }
        final int my = ++audioGen;
        audio = new Thread(new Runnable() {
            public void run() {
                int min = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                int frames = 1024;
                AudioTrack t;
                try {
                    t = new AudioTrack(AudioManager.STREAM_MUSIC, Synth.RATE, AudioFormat.CHANNEL_OUT_STEREO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, frames * 8), AudioTrack.MODE_STREAM);
                    t.play();
                } catch (Throwable e) {
                    return;
                }
                short[] buf = new short[frames * 2];
                try {
                    while (audioGen == my) {
                        mixer.mix(buf, frames);
                        t.write(buf, 0, buf.length);
                    }
                } finally {
                    try {
                        t.stop();
                    } catch (Throwable ignored) {
                    }
                    t.release();
                }
            }
        }, "audio");
        audio.start();
    }

    void stopAudio() {
        audioRunning = false;
        audioGen++;
    }

    public GImage newImage(int w, int h) { return new AndroidImage(w, h); }

    public float density() { return act.getResources().getDisplayMetrics().density; }

    public InputStream openAsset(String name) throws IOException { return act.getAssets().open(name); }

    public InputStream openRead(String name) throws IOException { return new FileInputStream(new File(act.getFilesDir(), name)); }

    public OutputStream openWrite(String name) throws IOException { return new FileOutputStream(new File(act.getFilesDir(), name)); }

    public boolean exists(String name) { return new File(act.getFilesDir(), name).exists(); }

    public void delete(String name) { new File(act.getFilesDir(), name).delete(); }

    public boolean rename(String from, String to) {
        File t = new File(act.getFilesDir(), to);
        t.delete();
        return new File(act.getFilesDir(), from).renameTo(t);
    }

    public String[] list() {
        File[] fs = act.getFilesDir().listFiles();
        if (fs == null) return new String[0];
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (File f : fs) if (f.isFile()) out.add(f.getName());
        return out.toArray(new String[0]);
    }

    public String pref(String key, String def) { return prefs.getString(key, def); }

    public void setPref(String key, String value) { prefs.edit().putString(key, value).apply(); }

    public void playSound(String name, float volume, float pan) { mixer.play(name, volume, pan); }

    public void setMusic(int track, float volume) {
        wantTrack = track;
        wantVolume = volume * 0.6f;
        short[] t = tracks[Math.max(0, Math.min(1, track))];
        if (t != null) mixer.setMusic(t, wantVolume);
    }

    @SuppressWarnings("deprecation")
    public void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        } catch (Throwable ignored) {
        }
    }

    /** (The game may run on the GL thread: anything that touches the UI goes to the UI thread.) */
    public void shareImage(GImage img) {
        final android.graphics.Bitmap shot = ((AndroidImage) img).bitmap;
        if (shot == null) return;
        act.runOnUiThread(new Runnable() {
            public void run() {
                share(shot);
            }
        });
    }

    private void share(android.graphics.Bitmap shot) {
        try {
            String url = MediaStore.Images.Media.insertImage(act.getContentResolver(), shot,
                    "Zombie City 2 " + System.currentTimeMillis(), "Zombie City Sandbox 2");
            if (url == null) throw new IllegalStateException();
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/jpeg");
            send.putExtra(Intent.EXTRA_STREAM, Uri.parse(url));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(Intent.createChooser(send, "Share screenshot"));
        } catch (Throwable e) {
            Toast.makeText(act, "Couldn't save the screenshot", Toast.LENGTH_SHORT).show();
        }
    }

    public void exit() {
        act.runOnUiThread(new Runnable() {
            public void run() {
                act.finish();
            }
        });
    }

    public void askText(final String title, final String current, final TextAnswer answer) {
        act.runOnUiThread(new Runnable() {
            public void run() {
                final android.widget.EditText edit = new android.widget.EditText(act);
                edit.setText(current);
                edit.setSingleLine(true);
                edit.setSelectAllOnFocus(true);
                new android.app.AlertDialog.Builder(act)
                        .setTitle(title)
                        .setView(edit)
                        .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                if (answer != null) answer.answer(edit.getText().toString());
                            }
                        })
                        .setNeutralButton("Copy", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                android.content.ClipboardManager cb = (android.content.ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cb != null) cb.setPrimaryClip(android.content.ClipData.newPlainText("Zombie City 2 code", edit.getText().toString()));
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }
}
