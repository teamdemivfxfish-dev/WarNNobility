package com.newtl.warnnobility.warmap;

/**
 * The commander's kit in COMMAND mode, the counterpart to {@link MapTool}'s drawing kit in MAP mode. Each
 * tool issues a real Hundred Years War order to the selected on-map unit (see
 * {@link com.newtl.warnnobility.warmap.net.WarMapNetwork.ORD_MOVE} et al):
 * <ul>
 *   <li>{@link #SELECT} - click a banner to select its unit (no order).</li>
 *   <li>{@link #MARCH} / {@link #ATTACK} - click the board to send the unit there (attack-move engages
 *       on the way); both are map-click tools.</li>
 *   <li>{@link #HOLD} / {@link #STOP} - immediate orders on the selected unit (stand ground / cancel).</li>
 * </ul>
 */
public enum CommandTool {
    SELECT("Select", -1),
    MARCH("March Here", com.newtl.warnnobility.warmap.net.WarMapNetwork.ORD_MOVE),
    ATTACK("Attack-Move Here", com.newtl.warnnobility.warmap.net.WarMapNetwork.ORD_ATTACK),
    HOLD("Hold Position", com.newtl.warnnobility.warmap.net.WarMapNetwork.ORD_HOLD),
    STOP("Stop / Cancel", com.newtl.warnnobility.warmap.net.WarMapNetwork.ORD_STOP);

    public final String label;
    /** The ORD_* order this tool issues, or -1 for SELECT (no order). */
    public final int order;
    /** MARCH / ATTACK target the board on click; SELECT / HOLD / STOP act immediately on the selection. */
    public boolean isTargeted() { return this == MARCH || this == ATTACK; }
    public boolean isImmediate() { return this == HOLD || this == STOP; }

    CommandTool(String label, int order) { this.label = label; this.order = order; }
}
