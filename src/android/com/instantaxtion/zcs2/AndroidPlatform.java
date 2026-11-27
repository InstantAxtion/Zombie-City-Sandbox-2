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
    private Thread audio;

    AndroidPlatform(Activity act) {
        this.act = act;
        prefs = act.getSharedPreferences("zcs2", Context.MODE_PRIVATE);
        // every sound and the score's stems are made once, out of the way (what's heard first, first); until a
        // sound is ready the mixer simply skips it
        Thread t = new Thread(new Runnable() {
            public void run() {
                synth.prerenderAll();
            }
        }, "synth");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
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
    public long maxMemory() { return Runtime.getRuntime().maxMemory(); }

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

    public void setMusic(int track, float volume) { mixer.setMusic(track, volume); }

    public Mixer mixer() { return mixer; }

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

    /** The request code of a file picked for the game, and who's waiting for it. */
    static final int PICK_FILE = 4711;
    private FileAnswer picking;

    /** Android's own file picker (ACTION_GET_CONTENT): any file, read through the content resolver when it comes back. */
    public void pickFile(final String title, final FileAnswer answer) {
        act.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    picking = answer;
                    Intent get = new Intent(Intent.ACTION_GET_CONTENT);
                    get.setType("*/*");
                    get.addCategory(Intent.CATEGORY_OPENABLE);
                    act.startActivityForResult(Intent.createChooser(get, title), PICK_FILE);
                } catch (Throwable e) {
                    picking = null;
                    Toast.makeText(act, "There's no app here to pick a file with", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** MainActivity hands the picker's answer here. */
    void onPicked(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_FILE) return;
        final FileAnswer a = picking;
        picking = null;
        if (a == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream in = act.getContentResolver().openInputStream(uri);
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int k;
                    while ((k = in.read(buf)) > 0 && bo.size() < (8 << 20)) bo.write(buf, 0, k);
                    in.close();
                    String name = uri.getLastPathSegment();
                    a.file(name == null ? "content.ini" : name, bo.toByteArray());
                } catch (Throwable e) {
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(act, "Couldn't read that file", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }, "pick").start();
    }

    /** The share sheet (ACTION_SEND) with the file's text. */
    public void shareText(final String fileName, final String text) {
        act.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, "Zombie City 2: " + fileName);
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    act.startActivity(Intent.createChooser(send, "Share " + fileName));
                } catch (Throwable e) {
                    Toast.makeText(act, "Couldn't share it", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    // ------------------------------------------------------------------ exported clips

    private File clipDir(String folder) {
        return new File(new File(act.getFilesDir(), "clips"), folder);
    }

    public OutputStream openClip(String folder, String name) throws IOException {
        File d = clipDir(folder);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("couldn't make " + d);
        return new java.io.BufferedOutputStream(new FileOutputStream(new File(d, name)), 1 << 16);
    }

    public String clipPlace(String folder) {
        return "the app's files: clips/" + folder;
    }

    /**
     * Share: the share sheet with the file through ClipProvider (read access granted to the app chosen). Save: on
     * Android 10 and newer into Pictures/Zombie City 2 through the media store (no permission needed); before that
     * into the public Pictures folder, which needs the storage permission (asked for on 6-9 the first time).
     */
    public String publishClip(final String folder, final String name, boolean share) {
        final File f = new File(clipDir(folder), name);
        if (!f.isFile()) return null;
        final String mime = name.endsWith(".gif") ? "image/gif" : "image/png";
        if (share) {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Uri uri = ClipProvider.uriFor(folder, name);
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType(mime);
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.setClipData(android.content.ClipData.newRawUri(name, uri));
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        Intent chooser = Intent.createChooser(send, "Share the clip");
                        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        act.startActivity(chooser);
                    } catch (Throwable e) {
                        Toast.makeText(act, "Couldn't share it", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            return "Choose where to send it";
        }
        final String display = folder + (name.endsWith(".gif") ? ".gif" : "_" + name);
        if (android.os.Build.VERSION.SDK_INT < 29 && android.os.Build.VERSION.SDK_INT >= 23
                && act.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    act.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4712);
                }
            });
            return "Allow saving to your photos, then tap Save to Pictures again";
        }
        new Thread(new Runnable() {
            public void run() {
                String said;
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        android.content.ContentResolver cr = act.getContentResolver();
                        android.content.ContentValues v = new android.content.ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, display);
                        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                        v.put("relative_path", "Pictures/Zombie City 2"); // (MediaColumns.RELATIVE_PATH, API 29)
                        v.put("is_pending", 1); // (MediaColumns.IS_PENDING, API 29)
                        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                        if (uri == null) throw new IOException("the media store said no");
                        OutputStream o = cr.openOutputStream(uri);
                        if (o == null) throw new IOException("couldn't write it");
                        copy(f, o);
                        v.clear();
                        v.put("is_pending", 0);
                        cr.update(uri, v, null, null);
                    } else {
                        @SuppressWarnings("deprecation")
                        File pics = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES);
                        File dir = new File(pics, "Zombie City 2");
                        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("couldn't make " + dir);
                        File out = new File(dir, display);
                        copy(f, new FileOutputStream(out));
                        android.media.MediaScannerConnection.scanFile(act, new String[]{out.getAbsolutePath()}, new String[]{mime}, null);
                    }
                    said = "Saved to Pictures/Zombie City 2";
                } catch (Throwable e) {
                    said = "Couldn't save to Pictures: use Share instead";
                }
                final String s = said;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "saveclip").start();
        return "Saving to Pictures/Zombie City 2...";
    }

    private static void copy(File from, OutputStream o) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally {
            in.close();
            o.close();
        }
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
