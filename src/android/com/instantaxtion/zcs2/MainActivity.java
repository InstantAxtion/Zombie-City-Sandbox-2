package com.instantaxtion.zcs2;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

public class MainActivity extends Activity {
    private GameView view;
    private AndroidPlatform platform;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        recordCrashes();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        platform = new AndroidPlatform(this);
        view = new GameView(this, platform);
        setContentView(view);
        immersive();
        showLastCrash();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        if (Build.VERSION.SDK_INT >= 19) {
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.pause();
        view.game.onPause();
        platform.stopAudio();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.resume();
        platform.startAudio();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!view.game.back()) super.onBackPressed();
    }

    private File crashFile() {
        return new File(getFilesDir(), "last_crash.txt");
    }

    /** If the game ever closes unexpectedly, what went wrong is written down to show next time. */
    private void recordCrashes() {
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        final File file = crashFile();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println("Zombie City Sandbox 2 " + Texts.VERSION);
                    pw.println("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL);
                    Runtime r = Runtime.getRuntime();
                    pw.println("Memory: " + ((r.totalMemory() - r.freeMemory()) >> 20) + " of " + (r.maxMemory() >> 20) + " MB");
                    pw.println("Thread: " + t.getName());
                    e.printStackTrace(pw);
                    pw.flush();
                    FileWriter out = new FileWriter(file);
                    out.write(sw.toString());
                    out.close();
                } catch (Throwable ignored) {
                }
                if (before != null) before.uncaughtException(t, e);
            }
        });
    }

    private void showLastCrash() {
        File file = crashFile();
        if (!file.exists()) return;
        StringBuilder sb = new StringBuilder();
        try {
            java.io.BufferedReader in = new java.io.BufferedReader(new FileReader(file));
            String line;
            for (int n = 0; (line = in.readLine()) != null && n < 60; n++) sb.append(line).append('\n');
            in.close();
        } catch (java.io.IOException ignored) {
        }
        file.delete();
        if (sb.length() == 0) return;
        TextView text = new TextView(this);
        text.setText(sb.toString());
        text.setTextIsSelectable(true);
        text.setTextSize(11);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad / 2, pad, pad / 2);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this)
                .setTitle("The game closed unexpectedly last time")
                .setMessage("This is what went wrong (you can select and copy it).")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }
}
