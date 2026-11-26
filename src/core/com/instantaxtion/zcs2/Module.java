package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A system that plugs into the world: weather, logistics, the chain of command, fires, the media... Each one is
 * stepped once a game second (or once per strategic step with a larger dt), saves and loads its own state, and
 * may draw over the map. The world keeps them in a fixed order, so the game stays deterministic.
 */
public abstract class Module {
    protected final World w;

    protected Module(World w) {
        this.w = w;
    }

    /** A short, unique, stable name (it labels the module's part of a save). */
    public abstract String id();

    /** Called once a game second, or once per strategic step; dt is the game seconds that passed. */
    public void update(float dt) {
    }

    /** Called every 1/20 s step at normal speeds (keep it cheap). */
    public void step() {
    }

    public void save(DataOutputStream o) throws IOException {
    }

    public void load(DataInputStream in) throws IOException {
    }

    /** Draws over the world (after the people and vehicles, before the HUD). */
    public void draw(Gfx g, Camera cam, WorldView view) {
    }

    /** A line for the HUD, or null. */
    public String status() {
        return null;
    }
}
