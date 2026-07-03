package com.newtl.warnnobility.warmap;

/**
 * The commander's kit: the set of marks an officer can leave on the War Table. Ordinals are the wire
 * id (sent in the draw payload and stored in NBT), so only ever APPEND to this list, never reorder.
 *
 * <p>Most tools are a poly-line of world points ({@link WarStroke#pts}); the token/objective/text tools
 * use a single anchor point plus a {@link WarStroke#label}. {@link #POINTER} is the transient "point
 * here" pulse: it is broadcast live but never stored, so the board never fills up with stale pings.
 */
public enum MapTool {
    PEN,            // freehand grease pencil
    LINE,           // single straight line
    ARROW,          // axis-of-advance (fat arrowhead on the last point)
    PHASE_LINE,     // multi-point front trace / boundary
    BOX,            // rectangle zone (two corner points)
    CIRCLE,         // circle zone (centre + edge point)
    OBJECTIVE,      // labelled objective ring (anchor point + label)
    UNIT_FRIENDLY,  // friendly unit token (anchor + callsign)
    UNIT_HOSTILE,   // hostile unit token
    UNIT_UNKNOWN,   // unknown/contact token
    TEXT,           // free text label (anchor + text)
    POINTER,        // transient laser pulse - broadcast, never persisted
    ERASE;          // eraser - removes marks under the cursor, never itself a mark

    private static final MapTool[] VALUES = values();

    public static MapTool byId(int id) {
        return (id >= 0 && id < VALUES.length) ? VALUES[id] : PEN;
    }

    /** A single anchor point + a {@link WarStroke#label}, rather than a drawn poly-line. */
    public boolean isPlaced() {
        return this == OBJECTIVE || this == TEXT
                || this == UNIT_FRIENDLY || this == UNIT_HOSTILE || this == UNIT_UNKNOWN;
    }

    /** Broadcast live / acts immediately, but never written to the board's saved stroke list. */
    public boolean isTransient() {
        return this == POINTER || this == ERASE;
    }
}
