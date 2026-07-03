package com.newtl.warnnobility.warmap;

/**
 * How a unit fights once it has an order. The stance shapes HOW a command verb is carried out (a Pin in
 * MISSILE_DEFENSE is a durable tar-pit; a Pin in SEARCH_DESTROY is aggressive worrying). Ordinals are a
 * wire id once units are networked, so only ever APPEND, never reorder.
 */
public enum Stance {
    HOLD("Hold"),
    SEARCH_DESTROY("Search & Destroy"),
    RAID("Raid"),
    PILLAGE("Pillage"),
    MISSILE_DEFENSE("Missile Defense");

    public final String label;
    Stance(String label) { this.label = label; }

    private static final Stance[] VALUES = values();
    public Stance next() { return VALUES[(ordinal() + 1) % VALUES.length]; }
    public static Stance byId(int id) { return (id >= 0 && id < VALUES.length) ? VALUES[id] : HOLD; }
}
