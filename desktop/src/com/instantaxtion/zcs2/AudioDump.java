package com.instantaxtion.zcs2;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedOutputStream;

/**
 * Writes the game's sounds to WAV files to listen to: every effect and bed on its own, each music cue, the cues
 * cross-fading into each other, and a street scene mixed as the game would.
 *   java -cp build/test com.instantaxtion.zcs2.AudioDump out/dir
 */
public final class AudioDump {
    public static void main(String[] args) throws IOException {
        File dir = new File(args.length > 0 ? args[0] : "build/audio");
        dir.mkdirs();
        Synth s = new Synth();
        long t0 = System.nanoTime();
        s.prerenderAll();
        System.out.printf("rendered everything in %.1f s%n", (System.nanoTime() - t0) / 1e9);
        long bytes = 0;
        for (int id = 0; id < Synth.COUNT; id++) {
            short[] b = s.get(id);
            bytes += b.length * 2L;
            int rate = Synth.halfRate(id) ? Synth.RATE / 2 : Synth.RATE;
            if (Synth.isLoop(id)) {
                // a loop played three times over, so the joins can be heard
                short[] l = new short[Math.min(b.length * 3, rate * 20)];
                for (int i = 0; i < l.length; i++) l[i] = b[i % b.length];
                b = l;
            }
            mono(new File(dir, "fx_" + Synth.NAMES[id] + ".wav"), b, rate);
        }
        for (int k = 0; k < Synth.STEMS; k++) bytes += s.makeStem(k).length * 2L;
        System.out.printf("buffers: %.1f MB%n", bytes / 1048576.0);
        Mixer m = new Mixer(s);
        m.setVolumes(1, 0.8f);
        for (int c = 0; c < Mixer.CUES; c++) {
            m.setMusic(c, 1);
            mix(m, 1); // let it settle
            stereo(new File(dir, "music_" + c + "_" + Mixer.CUE_NAMES[c] + ".wav"), mix(m, 26));
        }
        // the cues one after another, cross-fading
        int[] seq = {Mixer.CUE_CALM, Mixer.CUE_TENSION, Mixer.CUE_BATTLE, Mixer.CUE_AFTERMATH, Mixer.CUE_STRATEGIC};
        short[] all = new short[Synth.RATE * 2 * 15 * seq.length];
        for (int k = 0; k < seq.length; k++) {
            m.setCue(seq[k]);
            short[] part = mix(m, 15);
            System.arraycopy(part, 0, all, k * part.length, part.length);
        }
        stereo(new File(dir, "music_crossfades.wav"), all);
        stereo(new File(dir, "scene_street_fight.wav"), scene(s));
        stereo(new File(dir, "scene_game_capture.wav"), capture(s));
        System.out.println("wrote " + dir);
    }

    /**
     * The real thing: a game with an outbreak in the city, heard through the director as the player would, the
     * camera close in for 40 seconds, then pulling out to the whole country.
     */
    private static short[] capture(Synth s) throws IOException {
        Content c = Content.load(new java.io.FileInputStream("assets/content.ini"));
        World w = Setup.newGame(c, 1024, RegionGen.COAST, 8, c.scenario("sandbox"), 1);
        Region.Settlement city = w.r.city();
        float x = city.cx * 16f + 300, y = city.cy * 16f + 200;
        DesktopPlatform pf = new DesktopPlatform(new File("build/audio-tmp"));
        pf.mixer = new Mixer(s);
        Settings st = new Settings();
        st.captions = true;
        AudioDirector ad = new AudioDirector(pf);
        ad.applySettings(st);
        Camera cam = new Camera();
        cam.setScreen(540, 1170, w.r.px());
        cam.cx = x;
        cam.cy = y;
        cam.zoom = 0.9f;
        Tools.paintZombies(w, x, y, 30, 120, w.c.ztype("walker").index);
        int secs = 60;
        short[] out = new short[secs * Synth.RATE * 2];
        short[] buf = new short[2400];
        int at = 0;
        String lastCap = null;
        int lastCue = -1;
        for (int k = 0; k < secs * 20; k++) {
            float t = k / 20f;
            if (t > 40) cam.zoom = Math.max(cam.fitZoom(), cam.zoom * 0.96f);
            w.camX0 = cam.x0();
            w.camY0 = cam.y0();
            w.camX1 = cam.x1();
            w.camY1 = cam.y1();
            w.camDetail = cam.zoom >= 0.3f;
            w.step();
            ad.update(w, cam, st, World.DT, false, 1);
            int frames = (k % 2 == 0) ? 1102 : 1103;
            pf.mixer.mix(buf, frames);
            System.arraycopy(buf, 0, out, at, Math.min(frames * 2, out.length - at));
            at += frames * 2;
            String cap = ad.latestCaption();
            if (cap != null && !cap.equals(lastCap)) System.out.printf("  %5.1fs caption: %s%n", t, cap);
            lastCap = cap;
            if (ad.cue() != lastCue) System.out.printf("  %5.1fs cue: %s%n", t, Mixer.CUE_NAMES[ad.cue()]);
            lastCue = ad.cue();
        }
        System.out.println("  game capture: " + pf.mixer.requests + " sounds asked for");
        w.shutdown();
        return out;
    }

    private static short[] mix(Mixer m, float seconds) {
        int frames = (int) (seconds * Synth.RATE);
        short[] out = new short[frames * 2];
        short[] buf = new short[1024 * 2];
        for (int f = 0; f < frames; f += 1024) {
            int n = Math.min(1024, frames - f);
            m.mix(buf, n);
            System.arraycopy(buf, 0, out, f * 2, n * 2);
        }
        return out;
    }

    /** A night street: the town's hum, crickets, a police car passing with its siren, a fight, a blast, the dead. */
    private static short[] scene(Synth s) {
        Mixer m = new Mixer(s);
        m.setVolumes(0.9f, 0.5f);
        m.setMusic(Mixer.CUE_TENSION, 1);
        m.setLoop(AudioDirector.SL_CITY, Synth.L_CITY, 0.25f, 0, 1);
        m.setLoop(AudioDirector.SL_CRICKETS, Synth.L_CRICKETS, 0.18f, 0, 1);
        Rng r = new Rng(3);
        int secs = 24, block = Synth.RATE / 20;
        short[] out = new short[secs * Synth.RATE * 2];
        short[] buf = new short[block * 2];
        for (int b = 0; b < secs * 20; b++) {
            float t = b / 20f;
            // the siren sweeps left to right between 2 and 12 seconds, its pitch falling as it passes
            if (t >= 2 && t < 12) {
                float u = (t - 2) / 10;
                float env = (float) Math.sin(Math.PI * u);
                m.setLoop(AudioDirector.SL_POLICE, Synth.L_SIREN_POLICE, 0.5f * env * env, -0.8f + 1.6f * u, u < 0.5f ? 1.04f : 0.96f);
                m.setLoop(AudioDirector.SL_ENGINE, Synth.L_ENGINE, 0.3f * env * env, -0.8f + 1.6f * u, 1.1f);
            } else {
                m.setLoop(AudioDirector.SL_POLICE, Synth.L_SIREN_POLICE, 0, 0, 1);
                m.setLoop(AudioDirector.SL_ENGINE, Synth.L_ENGINE, 0, 0, 1);
            }
            if (t > 6) m.setLoop(AudioDirector.SL_BATTLE, Synth.L_BATTLE, 0.35f, 0.6f, 1);
            if (t > 9) m.setLoop(AudioDirector.SL_HORDE, Synth.L_HORDE, Math.min(0.5f, (t - 9) * 0.1f), -0.4f, 1);
            if (t > 10 && t < 20 && r.chance(0.25f)) m.play(r.chance(0.6f) ? Synth.RIFLE : Synth.PISTOL, r.range(0.3f, 0.6f), r.range(-0.3f, 0.5f), r.range(0.94f, 1.06f), 4, 4);
            if (t > 12 && t < 18 && r.chance(0.12f)) m.play(Synth.MG, 0.4f, 0.3f, 1, 4, 3);
            if (b == 14 * 20) m.play(Synth.WHISTLE, 0.6f, 0.2f, 1, 8, 3);
            if (b == 15 * 20 - 2) m.play(Synth.BOOM, 0.95f, 0.2f, 1, 8, 4);
            if (b == 16 * 20) m.play(Synth.CARALARM, 0.35f, 0.5f, 1, 3, 1);
            if (r.chance(0.04f)) m.play(Synth.GROAN + r.nextInt(4), r.range(0.25f, 0.45f), r.range(-0.6f, 0.2f), r.range(0.85f, 1.15f), 2, 3);
            if (b == 11 * 20 || b == 17 * 20) m.play(Synth.SCREAM, 0.45f, -0.3f, 1, 6, 3);
            if (b == 19 * 20) m.play(Synth.RADIO, 0.25f, 0.25f, 1, 3, 1);
            m.mix(buf, block);
            System.arraycopy(buf, 0, out, b * block * 2, block * 2);
        }
        return out;
    }

    static void mono(File f, short[] s, int rate) throws IOException {
        write(f, s, rate, 1);
    }

    static void stereo(File f, short[] s) throws IOException {
        write(f, s, Synth.RATE, 2);
    }

    private static void write(File f, short[] s, int rate, int ch) throws IOException {
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)));
        int data = s.length * 2;
        o.writeBytes("RIFF");
        le32(o, 36 + data);
        o.writeBytes("WAVEfmt ");
        le32(o, 16);
        le16(o, 1);
        le16(o, ch);
        le32(o, rate);
        le32(o, rate * ch * 2);
        le16(o, ch * 2);
        le16(o, 16);
        o.writeBytes("data");
        le32(o, data);
        for (short v : s) le16(o, v);
        o.close();
    }

    private static void le32(DataOutputStream o, int v) throws IOException {
        o.write(v & 255);
        o.write((v >> 8) & 255);
        o.write((v >> 16) & 255);
        o.write((v >> 24) & 255);
    }

    private static void le16(DataOutputStream o, int v) throws IOException {
        o.write(v & 255);
        o.write((v >> 8) & 255);
    }
}
