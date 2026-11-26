package com.instantaxtion.zcs2;

import java.util.List;

/**
 * Decides what the player hears. Each frame it reads the world (never changes it, and rolls its own dice, so
 * sound can't touch the simulation or a replay): the sound events the simulation left in {@link Effects}, the
 * vehicles near the camera, and every quarter second the people, hordes, fires, weather and the ground under the
 * camera. From that it plays one-shots placed by distance and direction from the camera (quieter and darker the
 * further out the camera is), keeps the looping beds at the right levels (the town's hum, wind, birds or crickets,
 * rain, a fight a few streets off, a horde's rumble), picks the score's cue, and leaves captions, vibration and
 * screen shake for the game to use.
 *
 * Nothing here allocates per frame: the tables are made once (the vehicle classes once per world).
 */
final class AudioDirector {
    private final Platform pf;
    private final Mixer mx;
    /** The sound's own dice: never the world's. */
    private final Rng rng = new Rng(0x50D);

    // loop slots
    static final int SL_POLICE = 0, SL_FIRE = 1, SL_AMB = 2, SL_ENGINE = 3, SL_TRUCK = 4, SL_TRACKS = 5, SL_ROTOR = 6,
            SL_AIR = 7, SL_CITY = 8, SL_WIND = 9, SL_BIRDS = 10, SL_CRICKETS = 11, SL_RAIN = 12, SL_STORM = 13,
            SL_FIREBED = 14, SL_CROWD = 15, SL_HORDE = 16, SL_BATTLE = 17, SLOTS = 18;
    private static final int[] SLOT_SOUND = {Synth.L_SIREN_POLICE, Synth.L_SIREN_FIRE, Synth.L_SIREN_AMB, Synth.L_ENGINE,
            Synth.L_TRUCK, Synth.L_TRACKS, Synth.L_ROTOR, Synth.L_AIRCRAFT, Synth.L_CITY, Synth.L_WIND, Synth.L_BIRDS,
            Synth.L_CRICKETS, Synth.L_RAIN, Synth.L_STORM, Synth.L_FIRE, Synth.L_CROWD, Synth.L_HORDE, Synth.L_BATTLE};
    private final float[] slotVol = new float[SLOTS], slotPan = new float[SLOTS], slotPitch = new float[SLOTS];

    // how each one-shot carries: reach (world px; 0 = everywhere), volume, priority, copies at once
    private static final float[] REACH = new float[Synth.COUNT], VOL = new float[Synth.COUNT];
    private static final int[] PRIO = new int[Synth.COUNT], SAME = new int[Synth.COUNT];
    private static final String[] CAPTION = new String[Synth.COUNT];

    private static void def(int id, float reach, float vol, int prio, int same, String caption) {
        REACH[id] = reach;
        VOL[id] = vol;
        PRIO[id] = prio;
        SAME[id] = same;
        CAPTION[id] = caption;
    }

    static {
        def(Synth.PISTOL, 1800, 0.5f, 4, 4, "Gunfire");
        def(Synth.RIFLE, 2100, 0.55f, 4, 4, "Gunfire");
        def(Synth.MG, 2200, 0.45f, 4, 3, "Machine gun");
        def(Synth.SHOTGUN, 1800, 0.6f, 4, 3, "Shotgun");
        def(Synth.SNIPER, 2800, 0.65f, 5, 2, "Rifle shot");
        def(Synth.CANNON, 7000, 0.85f, 7, 2, "Tank gun");
        def(Synth.BOOM, 6500, 0.95f, 8, 4, "Explosion");
        def(Synth.BIGBOOM, 11000, 1f, 9, 3, "Huge explosion");
        def(Synth.SCREAM, 1000, 0.5f, 6, 3, "Scream");
        def(Synth.SCREAM2, 1000, 0.5f, 6, 3, "Scream");
        def(Synth.HURT, 550, 0.45f, 5, 3, null);
        def(Synth.BITE, 450, 0.6f, 6, 2, null);
        def(Synth.GROAN, 520, 0.42f, 2, 3, null);
        def(Synth.GROAN2, 520, 0.42f, 2, 3, null);
        def(Synth.GROAN3, 520, 0.42f, 2, 3, null);
        def(Synth.MOAN, 520, 0.42f, 2, 3, null);
        def(Synth.JET, 10000, 0.8f, 7, 2, "Jet overhead");
        def(Synth.NEWS, 0, 0.4f, 10, 1, null);
        def(Synth.CLICK, 0, 0.4f, 10, 2, null);
        def(Synth.WHISTLE, 3800, 0.7f, 8, 3, "Incoming shell");
        def(Synth.THUNDER, 0, 0.55f, 7, 2, "Thunder");
        def(Synth.THUNDER2, 0, 0.8f, 7, 2, "Thunder");
        def(Synth.RADIO, 0, 0.22f, 3, 1, null);
        def(Synth.RADIO2, 0, 0.22f, 3, 1, null);
        def(Synth.CARALARM, 1700, 0.4f, 3, 1, "Car alarm");
        def(Synth.HORN, 2600, 0.55f, 5, 1, "Fire engine horn");
    }

    // this frame's listener
    private float lx, ly, lh, zoom, halfW, halfH;
    private float panOut;
    private World world;
    private int seen;

    // sound events this frame, merged by sound
    private static final int CAND = 48;
    private final int[] cId = new int[CAND];
    private final float[] cVol = new float[CAND], cPan = new float[CAND], cX = new float[CAND], cY = new float[CAND];
    private int cN;

    // sounds held back a moment (a blast after its shell's whistle, an alarm after a blast)
    private static final int DELAYED = 24;
    private final int[] dId = new int[DELAYED];
    private final float[] dX = new float[DELAYED], dY = new float[DELAYED], dT = new float[DELAYED];
    private int dN;
    private float shellX, shellY, shellT;

    // what's going on round the camera (from the quarter-second scan)
    private float scanT;
    private int zSeen, fleeing;
    private float zCX, zCY, fleeCX, fleeCY;
    private final int[] zPick = new int[8];
    private int zPickN;
    private float hordeLevel, hordePan, fireLevel, firePan, urban, hum;
    private float battle, battlePanAcc, combat, battleCool;
    private float groanT, thunderT = 8, radioT, alarmT, hornT = 10, ambT = -1, ambNext = 40, ambDir = 1;
    private int newsVersion = -1;
    private int lastPlayer = -1;
    private float lastHp;
    private boolean playerWasDead;

    // music
    private int cueNow = Mixer.CUE_CALM, cueWant = Mixer.CUE_CALM;
    private float cueWantT, cueHeldT, afterT, sinceBattle = 999;

    // feedback for the game
    private float shake, shakePh, vibT;
    float shakeX, shakeY;

    // captions
    private static final int CAPS = 4;
    private final String[] capText = new String[CAPS];
    private final float[] capAng = new float[CAPS], capAge = new float[CAPS];
    private boolean captionsOn;

    // vehicle classes, per vehicle type of this world
    private static final byte VC_NONE = 0, VC_CAR = 1, VC_TRUCK = 2, VC_TANK = 3, VC_HELI = 4, VC_PLANE = 5;
    private byte[] vClass = new byte[0];
    private boolean[] vFire = new boolean[0];
    private float[] vMax = new float[0];
    private Content vContent;

    private Settings settings;
    private float time;

    AudioDirector(Platform pf) {
        this.pf = pf;
        this.mx = pf.mixer();
        for (int k = 0; k < CAPS; k++) capAge[k] = 99;
    }

    Mixer mixer() {
        return mx;
    }

    void applySettings(Settings s) {
        settings = s;
        captionsOn = s.captions;
        if (mx == null) return;
        mx.setVolumes(s.sound / 100f, s.music / 100f * 0.6f);
        mx.setMono(s.mono);
        mx.setMusicVolume(1);
        if (mx.cue() < 0) mx.setCue(Mixer.CUE_CALM);
    }

    /** Off the play screen: the beds fade, the calm cue plays. */
    void idle(float dt) {
        if (mx == null) return;
        mx.stopLoops();
        for (int k = 0; k < SLOTS; k++) slotVol[k] = 0;
        if (mx.cue() != Mixer.CUE_CALM) mx.setCue(Mixer.CUE_CALM);
        cueNow = cueWant = Mixer.CUE_CALM;
        decay(dt);
    }

    private void decay(float dt) {
        shake *= (float) Math.exp(-dt * 7);
        if (shake < 0.05f) shake = 0;
        shakePh += dt;
        shakeX = shake * (Synth.sn(shakePh * 23.1f) * 0.7f + Synth.sn(shakePh * 37.7f + 0.3f) * 0.3f);
        shakeY = shake * (Synth.sn(shakePh * 27.3f + 0.6f) * 0.7f + Synth.sn(shakePh * 41.9f) * 0.3f);
        vibT -= dt;
        for (int k = 0; k < CAPS; k++) capAge[k] += dt;
    }

    // ------------------------------------------------------------------ the frame

    /**
     * Called each frame on the play screen. speed: game seconds per real second (from 60 up the world is in
     * fast-forward and nothing is heard person by person).
     */
    void update(World w, Camera cam, Settings s, float dt, boolean paused, int speed) {
        settings = s;
        captionsOn = s.captions;
        time += dt;
        decay(dt);
        Effects fx = w.fx;
        if (w != world) {
            world = w;
            seen = fx.sHead;
            dN = 0;
            lastPlayer = -1;
            newsVersion = w.news.version;
            battle = combat = 0;
            scanT = 0;
            classify(w);
        }
        int n = (fx.sHead - seen + Effects.SOUNDS) % Effects.SOUNDS;
        if (n > Effects.SOUNDS / 2) n = Effects.SOUNDS / 2;
        seen = fx.sHead;
        if (mx == null) return;
        if (paused) {
            for (int k = 0; k < SLOTS; k++) slotVol[k] *= Math.max(0, 1 - dt * 3);
            pushLoops();
            return;
        }
        boolean fast = speed >= 60 || w.strategic;
        listener(cam);
        // what the simulation asked for this frame, oldest first (a shell's whistle comes before its blast)
        cN = 0;
        for (int k = n - 1; k >= 0; k--) {
            int i = (fx.sHead - 1 - k + Effects.SOUNDS) % Effects.SOUNDS;
            String name = fx.sName[i];
            if (name == null) continue;
            event(w, Synth.id(name), fx.sx[i], fx.sy[i], fast);
        }
        // held-back sounds whose moment has come
        for (int k = 0; k < dN; ) {
            dT[k] -= dt;
            if (dT[k] <= 0) {
                candidate(dId[k], dX[k], dY[k], true);
                dN--;
                dId[k] = dId[dN];
                dX[k] = dX[dN];
                dY[k] = dY[dN];
                dT[k] = dT[dN];
            } else k++;
        }
        flush();
        shellT -= dt;
        battle *= (float) Math.exp(-dt / 3f);
        combat *= (float) Math.exp(-dt / 6f);
        battleCool -= dt;
        scanT -= dt;
        if (scanT <= 0) {
            scanT = 0.25f;
            scan(w, cam, fast);
        }
        vehicles(w, fast);
        player(w);
        timers(w, s, dt, fast, speed);
        beds(w, fast);
        music(w, cam, dt, fast);
        pushLoops();
    }

    private void listener(Camera cam) {
        lx = cam.cx;
        ly = cam.cy;
        zoom = Math.max(0.005f, cam.zoom);
        lh = 140 / zoom;
        halfW = cam.sw * 0.5f / zoom;
        halfH = cam.sh * 0.5f / zoom;
    }

    /** How loud a sound at (x, y) that carries `reach` is here (0..1); leaves the pan in panOut. */
    private float gainAt(float x, float y, float reach) {
        float dx = x - lx, dy = y - ly;
        float d = (float) Math.sqrt(dx * dx + dy * dy + lh * lh);
        panOut = Math.max(-0.85f, Math.min(0.85f, dx / (Math.abs(dx) + lh * 0.6f + 120)));
        if (reach <= 0) {
            panOut = 0;
            return 1;
        }
        float g = 1 - d / reach;
        if (g <= 0) return 0;
        return (float) Math.pow(g, 1.6f);
    }

    private boolean onScreen(float x, float y) {
        return Math.abs(x - lx) < halfW && Math.abs(y - ly) < halfH;
    }

    private void event(World w, int id, float x, float y, boolean fast) {
        if (id < 0) return;
        if (id == Synth.WHISTLE) {
            shellX = x;
            shellY = y;
            shellT = 0.5f;
            candidate(id, x, y, false);
            return;
        }
        boolean blast = id == Synth.BOOM || id == Synth.BIGBOOM;
        boolean gun = id <= Synth.SNIPER || id == Synth.CANNON;
        if (blast && shellT > 0 && dN < DELAYED) {
            float dx = x - shellX, dy = y - shellY;
            if (dx * dx + dy * dy < 600 * 600) {
                delay(id, x, y, 0.95f);
                return;
            }
        }
        if (gun || blast) {
            float dx = x - lx, dy = y - ly, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (!onScreen(x, y) && d < 6000) {
                // a fight out of sight: it feeds the distant battle bed instead
                float wgt = (blast ? 0.25f : 0.06f) * (1 - d / 6000);
                battle = Math.min(1.5f, battle + wgt);
                battlePanAcc += (dx / (Math.abs(dx) + 400) - battlePanAcc) * Math.min(1, wgt * 4);
            }
            // how much fighting the player is hearing, for the score: weighed by how loud it is here
            combat += gainAt(x, y, REACH[id]) * (blast ? 2.5f : 0.7f);
            if (blast && !fast) alarmNear(w, x, y);
        }
        candidate(id, x, y, false);
    }

    private void delay(int id, float x, float y, float t) {
        if (dN >= DELAYED) return;
        dId[dN] = id;
        dX[dN] = x;
        dY[dN] = y;
        dT[dN] = t;
        dN++;
    }

    /** Adds a sound to this frame's list, merging it with one of the same already there (the louder wins). */
    private void candidate(int id, float x, float y, boolean delayed) {
        float g = x < 0 ? 1 : gainAt(x, y, REACH[id]);
        float pan = x < 0 ? 0 : panOut;
        if (g < 0.02f) return;
        int sid = id;
        if (x >= 0 && Synth.far(id) != id) {
            float dx = x - lx, dy = y - ly;
            float d = (float) Math.sqrt(dx * dx + dy * dy + lh * lh);
            if (d > REACH[id] * 0.38f) sid = Synth.far(id);
        }
        float v = g * VOL[id];
        for (int k = 0; k < cN; k++) {
            if (cId[k] != sid) continue;
            if (v > cVol[k]) {
                cVol[k] = v + cVol[k] * 0.2f;
                cPan[k] = pan;
                cX[k] = x;
                cY[k] = y;
            } else cVol[k] += v * 0.2f;
            return;
        }
        if (cN == CAND) return;
        cId[cN] = sid;
        cVol[cN] = v;
        cPan[cN] = pan;
        cX[cN] = x;
        cY[cN] = y;
        cN++;
    }

    /** Plays the frame's sounds, the most important and loudest first, a few at most. */
    private void flush() {
        int played = 0;
        while (cN > 0 && played < 7) {
            int best = 0;
            float bs = -1;
            for (int k = 0; k < cN; k++) {
                int base = base(cId[k]);
                float sc = PRIO[base] + cVol[k] * 4;
                if (sc > bs) {
                    bs = sc;
                    best = k;
                }
            }
            int id = cId[best], base = base(id);
            float v = Math.min(1.2f, cVol[best]), pan = cPan[best], x = cX[best], y = cY[best];
            cN--;
            cId[best] = cId[cN];
            cVol[best] = cVol[cN];
            cPan[best] = cPan[cN];
            cX[best] = cX[cN];
            cY[best] = cY[cN];
            float pitch = base <= Synth.SNIPER ? rng.range(0.94f, 1.06f) : base >= Synth.GROAN && base <= Synth.MOAN
                    ? rng.range(0.85f, 1.15f) : base == Synth.SCREAM || base == Synth.SCREAM2 ? rng.range(0.92f, 1.1f)
                    : base == Synth.BOOM || base == Synth.BIGBOOM ? rng.range(0.9f, 1.05f) : 1;
            mx.play(id, v, pan, pitch, PRIO[base], SAME[base]);
            played++;
            react(base, v, x, y);
        }
        cN = 0;
    }

    /** A far variant's own sound. */
    private static int base(int id) {
        return id >= Synth.PISTOL_FAR && id <= Synth.BIGBOOM_FAR ? id - 8 : id;
    }

    /** What a sound does besides being heard: captions, the phone shaking, the screen shaking. */
    private void react(int base, float v, float x, float y) {
        if (CAPTION[base] != null && v > 0.1f) {
            boolean gun = base <= Synth.SNIPER;
            // gunfire on screen speaks for itself; out of sight it's worth a line
            if (!gun || x < 0 || !onScreen(x, y)) caption(CAPTION[base], x, y);
        }
        if (base == Synth.BOOM || base == Synth.BIGBOOM || base == Synth.CANNON) {
            float strength = base == Synth.BIGBOOM ? 1 : base == Synth.BOOM ? 0.6f : 0.3f;
            if (settings != null && settings.shake) shake = Math.max(shake, v * strength * 14);
            if (v > 0.35f) vibrate((int) (30 + 110 * v * strength));
        }
        if (base == Synth.JET && v > 0.5f) vibrate(60);
    }

    private void vibrate(int ms) {
        if (settings == null || !settings.vibrate || vibT > 0) return;
        vibT = 0.35f;
        pf.vibrate(Math.max(15, Math.min(300, ms)));
    }

    private void caption(String text, float x, float y) {
        if (!captionsOn) return;
        float ang = Float.NaN;
        if (x >= 0) {
            float dx = x - lx, dy = y - ly;
            if (Math.abs(dx) > halfW * 0.3f || Math.abs(dy) > halfH * 0.3f) ang = (float) Math.atan2(dy, dx);
        }
        int slot = -1;
        for (int k = 0; k < CAPS; k++) if (capText[k] == text) slot = k;
        if (slot < 0) {
            slot = 0;
            for (int k = 1; k < CAPS; k++) if (capAge[k] > capAge[slot]) slot = k;
        }
        capText[slot] = text;
        capAng[slot] = ang;
        capAge[slot] = 0;
    }

    /** A blast near parked cars sets an alarm off. */
    private void alarmNear(World w, float x, float y) {
        if (alarmT > 0) return;
        Vehicles v = w.veh;
        for (int i = 0; i < v.top; i++) {
            if (v.state[i] != Vehicles.V_PARKED && v.state[i] != Vehicles.V_WRECK) continue;
            float dx = v.x[i] - x, dy = v.y[i] - y;
            if (dx * dx + dy * dy < 380 * 380 && vClassOf(v, i) == VC_CAR) {
                delay(Synth.CARALARM, v.x[i], v.y[i], rng.range(0.3f, 0.8f));
                alarmT = 14;
                return;
            }
        }
    }

    // ------------------------------------------------------------------ vehicles

    private void classify(World w) {
        if (vContent == w.c && vClass.length == w.c.vtypes.size()) return;
        vContent = w.c;
        int n = w.c.vtypes.size();
        vClass = new byte[n];
        vFire = new boolean[n];
        vMax = new float[n];
        for (int k = 0; k < n; k++) {
            Content.VType t = w.c.vtypes.get(k);
            String id = t.id == null ? "" : t.id;
            byte c;
            if (id.equals("destroyer")) c = VC_NONE;
            else if (t.flies) c = id.contains("heli") || id.equals("gunship") ? VC_HELI : VC_PLANE;
            else if (t.tracked) c = VC_TANK;
            else if ("army".equals(t.faction) || t.seats >= 7 || t.supply || id.equals("fire_engine") || id.equals("bus")) c = VC_TRUCK;
            else c = VC_CAR;
            vClass[k] = c;
            vFire[k] = id.equals("fire_engine");
            vMax[k] = Math.max(1, t.speed);
        }
    }

    private int vClassOf(Vehicles v, int i) {
        int t = v.type[i];
        return t >= 0 && t < vClass.length ? vClass[t] : VC_NONE;
    }

    private void vehicles(World w, boolean fast) {
        for (int k = SL_POLICE; k <= SL_AIR; k++) {
            if (k != SL_AMB) slotVol[k] = 0;
        }
        if (fast) return;
        Vehicles v = w.veh;
        float polBest = 0, fireBest = 0;
        float[] best = slotVol;
        for (int i = 0; i < v.top; i++) {
            byte st = v.state[i];
            if (st == Vehicles.V_NONE || st == Vehicles.V_WRECK) continue;
            int t = v.type[i];
            if (t < 0 || t >= vClass.length) continue;
            int c = vClass[t];
            if (c == VC_NONE) continue;
            float x = v.x[i], y = v.y[i];
            boolean air = c == VC_HELI || c == VC_PLANE;
            if (air && v.alt[i] < 0.05f) continue;
            if (v.siren[i]) {
                float g = gainAt(x, y, 2800);
                if (g > 0.01f) {
                    float pitch = doppler(v, i);
                    int slot = vFire[t] ? SL_FIRE : SL_POLICE;
                    float prev = vFire[t] ? fireBest : polBest;
                    if (g > prev) {
                        if (vFire[t]) fireBest = g;
                        else polBest = g;
                        slotPan[slot] = panOut;
                        slotPitch[slot] = pitch;
                    }
                    best[slot] = Math.max(best[slot], g) + Math.min(g, best[slot]) * 0.15f;
                }
            }
            if (!air && (st == Vehicles.V_PARKED || v.spd[i] < 3)) continue;
            int slot;
            float reach;
            switch (c) {
                case VC_CAR: slot = SL_ENGINE; reach = 550; break;
                case VC_TRUCK: slot = SL_TRUCK; reach = 900; break;
                case VC_TANK: slot = SL_TRACKS; reach = 1800; break;
                case VC_HELI: slot = SL_ROTOR; reach = 4500; break;
                default: slot = SL_AIR; reach = 7500;
            }
            float g = gainAt(x, y, reach);
            if (g <= 0.01f) continue;
            if (g > best[slot]) {
                slotPan[slot] = panOut;
                slotPitch[slot] = air ? doppler(v, i) : 0.85f + 0.35f * Math.min(1, v.spd[i] / vMax[t]);
            }
            best[slot] = Math.max(best[slot], g) + Math.min(g, best[slot]) * 0.15f;
        }
        slotVol[SL_POLICE] = Math.min(0.55f, slotVol[SL_POLICE] * 0.5f);
        slotVol[SL_FIRE] = Math.min(0.55f, slotVol[SL_FIRE] * 0.5f);
        slotVol[SL_ENGINE] = Math.min(0.4f, slotVol[SL_ENGINE] * 0.35f);
        slotVol[SL_TRUCK] = Math.min(0.45f, slotVol[SL_TRUCK] * 0.4f);
        slotVol[SL_TRACKS] = Math.min(0.55f, slotVol[SL_TRACKS] * 0.55f);
        slotVol[SL_ROTOR] = Math.min(0.6f, slotVol[SL_ROTOR] * 0.55f);
        slotVol[SL_AIR] = Math.min(0.45f, slotVol[SL_AIR] * 0.45f);
        if (polBest > 0.15f) caption("Police siren", slotPan[SL_POLICE]);
        if (fireBest > 0.15f) caption("Fire engine siren", slotPan[SL_FIRE]);
        if (slotVol[SL_ROTOR] > 0.12f) caption("Helicopter", slotPan[SL_ROTOR]);
        if (slotVol[SL_TRACKS] > 0.15f) caption("Tank tracks", slotPan[SL_TRACKS]);
        if (fireBest > 0.3f && hornT <= 0) {
            hornT = rng.range(14, 30);
            mx.play(Synth.HORN, VOL[Synth.HORN] * fireBest, slotPan[SL_FIRE], 1, PRIO[Synth.HORN], 1);
            caption(CAPTION[Synth.HORN], slotPan[SL_FIRE]);
        }
    }

    /** A caption for a loop, its direction from its pan. Repeats only refresh it. */
    private void caption(String text, float pan) {
        if (!captionsOn) return;
        for (int k = 0; k < CAPS; k++)
            if (capText[k] == text && capAge[k] < 6) {
                capAge[k] = Math.min(capAge[k], 2.4f);
                capAng[k] = pan > 0.25f ? 0 : pan < -0.25f ? (float) Math.PI : Float.NaN;
                return;
            }
        caption(text, pan > 0.25f ? lx + halfW : pan < -0.25f ? lx - halfW : lx, ly);
    }

    /** Pitch from a vehicle's speed towards or away from the camera (the siren's rise and fall). */
    private float doppler(Vehicles v, int i) {
        float vx = (float) Math.cos(v.head[i]) * v.spd[i], vy = (float) Math.sin(v.head[i]) * v.spd[i];
        float dx = lx - v.x[i], dy = ly - v.y[i];
        float d = (float) Math.sqrt(dx * dx + dy * dy) + 1;
        float radial = (vx * dx + vy * dy) / d;
        return Math.max(0.93f, Math.min(1.07f, 1 + radial / 1400f * 0.6f));
    }

    // ------------------------------------------------------------------ the scan

    private void scan(World w, Camera cam, boolean fast) {
        Ents e = w.e;
        zSeen = fleeing = 0;
        zPickN = 0;
        float sx = 0, sy = 0, fx = 0, fy = 0;
        float x0 = lx - halfW * 1.3f, x1 = lx + halfW * 1.3f, y0 = ly - halfH * 1.3f, y1 = ly + halfH * 1.3f;
        if (!fast) {
            for (int i = 0; i < e.top; i++) {
                if (e.loc[i] != Ents.FREE) continue;
                float x = e.x[i], y = e.y[i];
                if (x < x0 || x > x1 || y < y0 || y > y1) continue;
                if (e.kind[i] == Ents.ZOMBIE) {
                    zSeen++;
                    sx += x;
                    sy += y;
                    if (zPickN < zPick.length) zPick[zPickN++] = i;
                    else {
                        int j = rng.nextInt(zSeen);
                        if (j < zPick.length) zPick[j] = i;
                    }
                } else if (e.kind[i] == Ents.CIV && (e.flags[i] & Ents.F_FLEEING) != 0) {
                    fleeing++;
                    fx += x;
                    fy += y;
                }
            }
        }
        if (zSeen > 0) {
            zCX = sx / zSeen;
            zCY = sy / zSeen;
        }
        if (fleeing > 0) {
            fleeCX = fx / fleeing;
            fleeCY = fy / fleeing;
        }
        // the dead counted by the sector (indoors and folded into numbers), and hordes on the march
        Sectors sec = w.sec;
        float cs = Region.SECT * Tile.SIZE;
        float weight = zSeen * gainAt(zSeen > 0 ? zCX : lx, zSeen > 0 ? zCY : ly, 3000) * 1.5f, panAcc = zSeen > 0 ? panOut * weight : 0;
        int s0x = Math.max(0, (int) ((lx - halfW - 3000) / cs)), s1x = Math.min(sec.sw - 1, (int) ((lx + halfW + 3000) / cs));
        int s0y = Math.max(0, (int) ((ly - halfH - 3000) / cs)), s1y = Math.min(sec.sh - 1, (int) ((ly + halfH + 3000) / cs));
        int stride = Math.max(1, (int) Math.sqrt((s1x - s0x + 1) * (s1y - s0y + 1) / 900f));
        float urb = 0, lit = 0, dead = 0;
        int samples = 0;
        for (int syi = s0y; syi <= s1y; syi += stride)
            for (int sxi = s0x; sxi <= s1x; sxi += stride) {
                int s = syi * sec.sw + sxi;
                float cx = sec.cx(s), cy = sec.cy(s);
                int zin = sec.zIn[s];
                if (zin > 0) {
                    float g = gainAt(cx, cy, 6000);
                    weight += zin * g * stride * stride * 0.6f;
                    panAcc += panOut * zin * g;
                }
                if (Math.abs(cx - lx) < halfW + cs && Math.abs(cy - ly) < halfH + cs) {
                    samples++;
                    int st = sec.settlement[s];
                    if (st >= 0) {
                        int kind = w.r.settlements.get(st).kind;
                        float u = kind == Region.S_CITY ? 1 : kind == Region.S_TOWN ? 0.75f : kind == Region.S_VILLAGE ? 0.35f : 0.5f;
                        urb += u;
                        if (w.strat.lit(s)) lit += u;
                        float pop = sec.popIn[s] + 1;
                        dead += u * Math.min(1, (zin + w.sp.secZ[s]) / pop);
                    }
                }
            }
        List<Strategic.Horde> hs = w.strat.hordes;
        for (int k = 0; k < hs.size(); k++) {
            Strategic.Horde h = hs.get(k);
            if (!h.alive || h.count <= 0) continue;
            float g = gainAt(h.x, h.y, 16000);
            if (g <= 0) continue;
            weight += h.count * g;
            panAcc += panOut * h.count * g;
        }
        hordeLevel = clamp01(((float) Math.log10(1 + weight) - 1.4f) / 2.2f);
        hordePan = weight > 0 ? Math.max(-0.8f, Math.min(0.8f, panAcc / weight)) : 0;
        urban = samples > 0 ? urb / samples : 0;
        hum = urb > 0 ? (lit / urb) * (1 - 0.75f * Math.min(1, dead / urb)) : 0;
        // fires in view
        fireLevel = 0;
        float fpan = 0;
        if (!fast && zoom > 0.12f && w.secBuildings != null) {
            int a0x = Math.max(0, (int) ((lx - halfW - 600) / cs)), a1x = Math.min(sec.sw - 1, (int) ((lx + halfW + 600) / cs));
            int a0y = Math.max(0, (int) ((ly - halfH - 600) / cs)), a1y = Math.min(sec.sh - 1, (int) ((ly + halfH + 600) / cs));
            if ((a1x - a0x + 1) * (a1y - a0y + 1) <= 400) {
                float[] heat = w.fires.heat;
                Region r = w.r;
                for (int syi = a0y; syi <= a1y; syi++)
                    for (int sxi = a0x; sxi <= a1x; sxi++) {
                        int[] bs = w.secBuildings[syi * sec.sw + sxi];
                        if (bs == null) continue;
                        for (int b : bs) {
                            if (b < 0 || b >= heat.length || heat[b] <= 0) continue;
                            float g = gainAt((r.bx[b] + r.bw[b] * 0.5f) * Tile.SIZE, (r.by[b] + r.bh[b] * 0.5f) * Tile.SIZE, 1900);
                            fireLevel += heat[b] * g;
                            fpan += panOut * heat[b] * g;
                        }
                    }
            }
        }
        firePan = fireLevel > 0 ? fpan / fireLevel : 0;
        fireLevel = clamp01(fireLevel * 0.9f);
        if (fireLevel > 0.25f) caption("Fire crackling", firePan);
        // fights reported a few streets away that we can't see
        List<Dispatch.Incident> inc = w.disp.incidents;
        for (int k = 0; k < inc.size(); k++) {
            Dispatch.Incident in = inc.get(k);
            if (!in.open || in.zombies <= 0 || in.assigned <= 0 || onScreen(in.x, in.y)) continue;
            float g = gainAt(in.x, in.y, 5000);
            if (g <= 0) continue;
            battle = Math.min(1.5f, battle + g * 0.05f);
            battlePanAcc += (panOut - battlePanAcc) * 0.2f;
        }
        if (battle > 0.35f && battleCool <= 0) {
            battleCool = 12;
            caption("Distant fighting", battlePanAcc);
        }
        if (hordeLevel > 0.45f) caption("The dead, many of them", hordePan);
        if (fleeing > 12 && zoom > 0.25f) caption("Crowd panicking", fleeing > 0 ? gainPan(fleeCX, fleeCY) : 0);
    }

    private float gainPan(float x, float y) {
        gainAt(x, y, 1000);
        return panOut;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    // ------------------------------------------------------------------ the player, timers, beds

    /** The player's own person: bitten or shot, and turned. */
    private void player(World w) {
        int p = w.playerEnt;
        if (p != lastPlayer) {
            lastPlayer = p;
            if (p >= 0) lastHp = w.e.hp[p];
            playerWasDead = false;
            return;
        }
        if (p < 0) return;
        Ents e = w.e;
        boolean dead = e.kind[p] == Ents.ZOMBIE || e.loc[p] == Ents.BODY || e.loc[p] == Ents.NONE;
        if (dead) {
            if (!playerWasDead) {
                playerWasDead = true;
                mx.play(Synth.SCREAM2, 0.8f, 0, 0.95f, 9, 2);
                vibrate(400);
                if (settings != null && settings.shake) shake = Math.max(shake, 8);
            }
            return;
        }
        float hp = e.hp[p];
        if (hp < lastHp - Math.max(0.5f, e.maxHp[p] * 0.02f)) {
            float hurt = Math.min(1, (lastHp - hp) / Math.max(1, e.maxHp[p]) * 4);
            boolean bitten = w.sp.zombiesNear(e, e.x[p], e.y[p], 40, 1) > 0;
            if (bitten) mx.play(Synth.BITE, 0.6f + 0.3f * hurt, 0, rng.range(0.9f, 1.1f), 8, 2);
            mx.play(Synth.HURT, 0.55f + 0.3f * hurt, 0, rng.range(0.95f, 1.1f), 8, 2);
            vibrate(bitten ? 90 : 50);
            if (settings != null && settings.shake) shake = Math.max(shake, 3 + 5 * hurt);
            caption(bitten ? "You're bitten" : "You're hit", -1, -1);
        }
        lastHp = hp;
    }

    private void timers(World w, Settings s, float dt, boolean fast, int speed) {
        alarmT -= dt;
        hornT -= dt;
        radioT -= dt;
        groanT -= dt;
        // the dead nearby, one at a time
        if (!fast && zoom > 0.28f && zPickN > 0 && groanT <= 0) {
            float rate = Math.min(2.2f, 0.3f + zSeen * 0.05f);
            groanT = -(float) Math.log(1 - rng.nextFloat() * 0.95f) / rate;
            int i = zPick[rng.nextInt(zPickN)];
            if (i < w.e.top && w.e.kind[i] == Ents.ZOMBIE && w.e.loc[i] == Ents.FREE) {
                int id = Synth.GROAN + rng.nextInt(4);
                candidate(id, w.e.x[i], w.e.y[i], false);
                flush();
            }
        }
        // the radio: a squelch and a few words when a message comes in
        if (w.news.version != newsVersion) {
            newsVersion = w.news.version;
            List<News.Item> log = w.news.log;
            boolean routine = !log.isEmpty() && log.get(log.size() - 1).kind != News.HEADLINE;
            if (s.radio && routine && radioT <= 0 && !fast && speed <= 4) {
                radioT = rng.range(6, 11);
                mx.play(rng.chance(0.6f) ? Synth.RADIO : Synth.RADIO2, VOL[Synth.RADIO], 0.25f, rng.range(0.96f, 1.04f), 3, 1);
            }
        }
        // thunder in a storm
        if (w.weather.state == Weather.STORM) {
            thunderT -= dt;
            if (thunderT <= 0) {
                thunderT = rng.range(7, 24);
                boolean near = rng.chance(0.35f);
                int id = near ? Synth.THUNDER2 : Synth.THUNDER;
                float pan = rng.range(-0.7f, 0.7f);
                mx.play(id, VOL[id] * rng.range(0.7f, 1f), pan, rng.range(0.9f, 1.05f), 7, 2);
                caption(CAPTION[id], pan);
                if (near) vibrate(70);
            }
        }
        // an ambulance passing a few streets away, in a town with trouble in it
        boolean trouble = w.sp.zCount > 0 || w.gov.level >= 1;
        if (ambT >= 0) {
            ambT += dt;
            float u = ambT / 12f;
            if (u >= 1) {
                ambT = -1;
                slotVol[SL_AMB] = 0;
            } else {
                float env = (float) Math.sin(Math.PI * u);
                slotVol[SL_AMB] = 0.22f * env * env * closeness(0.15f, 0.5f) * (fast ? 0 : 1);
                slotPan[SL_AMB] = ambDir * (-0.8f + 1.6f * u);
                slotPitch[SL_AMB] = u < 0.5f ? 1.03f : 0.97f;
            }
        } else {
            slotVol[SL_AMB] = 0;
            ambNext -= dt;
            if (ambNext <= 0) {
                ambNext = rng.range(45, 120);
                if (trouble && urban > 0.3f && !fast && zoom > 0.15f) {
                    ambT = 0;
                    ambDir = rng.chance(0.5f) ? 1 : -1;
                    caption("Ambulance siren", -ambDir);
                }
            }
        }
    }

    /** 0 below lo zoom, 1 above hi. */
    private float closeness(float lo, float hi) {
        return clamp01((zoom - lo) / (hi - lo));
    }

    private void beds(World w, boolean fast) {
        Weather wt = w.weather;
        float close = closeness(0.12f, 0.45f), town = closeness(0.05f, 0.25f);
        float day = w.daylight();
        boolean wet = wt.wet(), snow = wt.state == Weather.SNOW;
        float temp = wt.temperature();
        float rural = 1 - Math.min(1, urban * 1.3f);
        boolean dayCycles = !fast; // at a day a second, birds and crickets would flicker
        slotVol[SL_CITY] = 0.32f * urban * (0.25f + 0.75f * hum) * town * (fast ? 0.5f : 1);
        slotPan[SL_CITY] = 0;
        float altitude = 1 - close;
        slotVol[SL_WIND] = (0.07f + 0.2f * altitude + 0.12f * rural + (wt.state == Weather.CLOUDY ? 0.08f : 0) + (snow ? 0.12f : 0))
                * (wt.state == Weather.STORM ? 0.4f : 1);
        slotPan[SL_WIND] = 0;
        float birds = dayCycles && !wet && !snow && temp > 2 && wt.state != Weather.FOG ? clamp01((day - 0.5f) * 2.5f) : 0;
        float h = w.hour();
        float dawn = h > 5 && h < 8.5f ? 1.4f : 1;
        slotVol[SL_BIRDS] = 0.3f * birds * dawn * (0.25f + 0.75f * rural) * close * (1 - 0.6f * hordeLevel);
        float crickets = dayCycles && !wet && temp > 9 ? clamp01((0.35f - day) * 3) : 0;
        slotVol[SL_CRICKETS] = 0.26f * crickets * (0.3f + 0.7f * rural) * close;
        slotVol[SL_RAIN] = wt.state == Weather.RAIN ? 0.42f * (0.55f + 0.45f * close) : wt.state == Weather.STORM ? 0.2f : 0;
        slotVol[SL_STORM] = wt.state == Weather.STORM ? 0.48f * (0.6f + 0.4f * close) : 0;
        slotVol[SL_FIREBED] = fast ? 0 : 0.5f * fireLevel;
        slotPan[SL_FIREBED] = firePan;
        float crowd = fast ? 0 : clamp01(fleeing / 30f) * closeness(0.2f, 0.5f);
        slotVol[SL_CROWD] = 0.38f * crowd;
        slotPan[SL_CROWD] = fleeing > 0 ? gainPan(fleeCX, fleeCY) : 0;
        slotVol[SL_HORDE] = 0.55f * hordeLevel;
        slotPan[SL_HORDE] = hordePan;
        slotVol[SL_BATTLE] = fast ? 0 : 0.5f * clamp01(battle);
        slotPan[SL_BATTLE] = battlePanAcc;
        for (int k = SL_CITY; k < SLOTS; k++) slotPitch[k] = 1;
    }

    private void pushLoops() {
        for (int k = 0; k < SLOTS; k++)
            mx.setLoop(k, SLOT_SOUND[k], slotVol[k], slotPan[k], slotPitch[k] <= 0 ? 1 : slotPitch[k]);
    }

    // ------------------------------------------------------------------ the score

    private void music(World w, Camera cam, float dt, boolean fast) {
        int want;
        sinceBattle += dt;
        boolean danger = false;
        if (w.playerEnt >= 0 && w.e.kind[w.playerEnt] != Ents.ZOMBIE && w.e.loc[w.playerEnt] == Ents.FREE)
            danger = w.sp.zombiesNear(w.e, w.e.x[w.playerEnt], w.e.y[w.playerEnt], 160, 3) >= 2;
        boolean contained = w.roles.containedAt >= 0 && w.sp.zCount == 0;
        boolean strategicView = fast || cam.zoom < Math.max(0.06f, cam.fitZoom() * 1.6f);
        if (w.ended || contained) want = Mixer.CUE_AFTERMATH;
        else if (!fast && (combat > (strategicView ? 12 : 4) || danger)) want = Mixer.CUE_BATTLE;
        else if (strategicView) want = Mixer.CUE_STRATEGIC;
        else if (sinceBattle < 50 && combat < 1.5f) want = Mixer.CUE_AFTERMATH;
        else if (zSeen > 8 || hordeLevel > 0.3f || w.gov.level >= 2 || w.sp.zCount > 200 || combat > 2) want = Mixer.CUE_TENSION;
        else want = Mixer.CUE_CALM;
        if (want == Mixer.CUE_BATTLE) sinceBattle = 0;
        if (want != cueWant) {
            cueWant = want;
            cueWantT = 0;
        }
        cueWantT += dt;
        cueHeldT += dt;
        float need = want == Mixer.CUE_BATTLE ? 1f : 4f;
        if (cueWant != cueNow && cueWantT >= need && (cueHeldT >= 10 || want == Mixer.CUE_BATTLE)) {
            cueNow = cueWant;
            cueHeldT = 0;
        }
        if (mx.cue() != cueNow) mx.setCue(cueNow);
    }

    int cue() {
        return cueNow;
    }

    // ------------------------------------------------------------------ captions

    /** Short lines for the important sounds, newest at the bottom, each with an arrow to where it came from. */
    void drawCaptions(Gfx g, float d, float cx, float bottom) {
        if (!captionsOn) return;
        float ts = 12.5f * d, lh = 22 * d;
        float y = bottom - 6 * d;
        for (int pass = 0; pass < CAPS; pass++) {
            // the newest first, going up
            int k = -1;
            float age = 99;
            for (int j = 0; j < CAPS; j++) {
                if (capText[j] == null || capAge[j] >= 3.2f) continue;
                boolean done = false;
                for (int q = 0; q < pass; q++) if (drawnSlot[q] == j) done = true;
                if (done) continue;
                if (capAge[j] < age) {
                    age = capAge[j];
                    k = j;
                }
            }
            if (k < 0) break;
            drawnSlot[pass] = k;
            float a = Math.min(1, (3.2f - capAge[k]) / 0.6f);
            String s = capText[k];
            float tw = g.textWidth(s, ts, true);
            boolean arrow = !Float.isNaN(capAng[k]);
            float bw = tw + 16 * d + (arrow ? 20 * d : 0);
            float bx = cx - bw / 2;
            g.color(((int) (a * 0xB8)) << 24 | 0x0A0D12);
            g.fillRoundRect(bx, y - lh, bw, lh - 3 * d, 6 * d);
            g.color(((int) (a * 0xFF)) << 24 | 0xF2F2F2);
            g.text(s, bx + 8 * d + (arrow ? 20 * d : 0), y - lh * 0.33f - 1.5f * d, ts, Gfx.ALIGN_LEFT, true);
            if (arrow) {
                float ax = bx + 16 * d, ay = y - lh / 2 - 1.5f * d, r = 6 * d;
                float c = (float) Math.cos(capAng[k]), sn = (float) Math.sin(capAng[k]);
                g.color(((int) (a * 0xFF)) << 24 | 0xFFD060);
                g.line(ax - c * r, ay - sn * r, ax + c * r, ay + sn * r, 2 * d);
                float hx = ax + c * r, hy = ay + sn * r;
                float c1 = (float) Math.cos(capAng[k] + 2.5f), s1 = (float) Math.sin(capAng[k] + 2.5f);
                float c2 = (float) Math.cos(capAng[k] - 2.5f), s2 = (float) Math.sin(capAng[k] - 2.5f);
                g.line(hx, hy, hx + c1 * r * 0.75f, hy + s1 * r * 0.75f, 2 * d);
                g.line(hx, hy, hx + c2 * r * 0.75f, hy + s2 * r * 0.75f, 2 * d);
            }
            y -= lh;
        }
    }

    private final int[] drawnSlot = new int[CAPS];

    /** The caption showing now, newest (for tests), or null. */
    String latestCaption() {
        int k = -1;
        for (int j = 0; j < CAPS; j++) if (capText[j] != null && capAge[j] < 3.2f && (k < 0 || capAge[j] < capAge[k])) k = j;
        return k < 0 ? null : capText[k];
    }
}
