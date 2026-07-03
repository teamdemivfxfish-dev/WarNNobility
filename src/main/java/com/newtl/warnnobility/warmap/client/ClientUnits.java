package com.newtl.warnnobility.warmap.client;

import com.newtl.warnnobility.warmap.Stance;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side store + renderer for the field units and colonists shown on a War Frame, fed by the
 * server's {@link com.newtl.warnnobility.warmap.WarBoardScan} via the {@code Units} payload. Storage is
 * keyed per board ({@link BlockPos}) because units are world-positioned and two boards may look at
 * different places at once.
 *
 * <p>HYW combat units draw as a banner (allegiance-coloured flag) with soldier dots behind it; MineColonies
 * colonists draw as plain dots (they are civilians, not an army). Banners + dots are baked INTO the board
 * texture ({@link WarMapDraw#renderContent}); the planner draws only the selection highlight on top
 * ({@link #renderInteractive}).
 */
public final class ClientUnits {

    private ClientUnits() {}

    /** One field unit (an HYW formation / owned company) as the client knows it. */
    public static final class FieldUnit {
        public final UUID id;
        public final String groupKey; // server grouping key (HywServerUnits.groupKey); echoed back to order this unit
        public double x, z;         // world block coords of the banner
        public String name;
        public int count;           // living soldiers -> dot count + strength readout
        public int color;           // allegiance colour (0xRRGGBB)
        public boolean friendly;
        public Stance stance;
        public boolean aiControlled;
        public String missionNote;

        public FieldUnit(UUID id, String groupKey, double x, double z, String name, int count, int color,
                         boolean friendly, Stance stance, boolean aiControlled, String missionNote) {
            this.id = id; this.groupKey = groupKey; this.x = x; this.z = z; this.name = name; this.count = count;
            this.color = color; this.friendly = friendly; this.stance = stance;
            this.aiControlled = aiControlled; this.missionNote = missionNote;
        }
    }

    /** A colonist (or other non-army marker): just a coloured dot at a world position. */
    public record MapDot(double x, double z, int color) {}

    private static final Map<BlockPos, List<FieldUnit>> UNITS = new HashMap<>();
    private static final Map<BlockPos, List<MapDot>> DOTS = new HashMap<>();
    private static UUID selected;   // GUI-only selection (one planner open at a time)

    /** Replace a board's roster from a freshly-scanned server payload. */
    public static void set(BlockPos board, List<FieldUnit> units, List<MapDot> dots) {
        UNITS.put(board.immutable(), units != null ? units : List.of());
        DOTS.put(board.immutable(), dots != null ? dots : List.of());
    }

    public static void forget(BlockPos board) { UNITS.remove(board); DOTS.remove(board); }

    public static List<FieldUnit> all(BlockPos board) { return UNITS.getOrDefault(board, List.of()); }

    public static void select(UUID id) { selected = id; }

    public static FieldUnit selectedUnit(BlockPos board) {
        if (selected == null) return null;
        for (FieldUnit u : all(board)) if (u.id.equals(selected)) return u;
        return null;
    }

    /** Nearest unit within a few pixels of the cursor, or null. Used for click-to-select in command mode. */
    public static FieldUnit pick(AtlasBoard ab, int ox, int oy, double mx, double my, BlockPos board) {
        if (ab == null) return null;
        FieldUnit best = null; double bd = 12;
        for (FieldUnit u : all(board)) {
            double x = ox + ab.sx(u.x), y = oy + ab.sy(u.z);
            double d = Math.hypot(mx - x, my - y);
            if (d < bd) { bd = d; best = u; }
        }
        return best;
    }

    /**
     * Draw a board's colonist dots and unit banners into the box at {@code (ox,oy)}. Baked into the board
     * texture so the physical board shows the army too; the frame painted afterwards clips edge labels.
     */
    public static void renderAll(GuiGraphics g, AtlasBoard ab, int ox, int oy, Font font, BlockPos board) {
        if (ab == null) return;

        // colonists first (drawn under the banners): a small outlined dot each
        for (MapDot d : DOTS.getOrDefault(board, List.of())) {
            int x = (int) (ox + ab.sx(d.x)), y = (int) (oy + ab.sy(d.z));
            g.fill(x - 1, y - 1, x + 2, y + 2, 0xFF14100A);            // outline
            g.fill(x - 1, y - 1, x + 1, y + 1, (d.color() & 0xFFFFFF) | 0xFF000000);
        }

        for (FieldUnit u : all(board)) {
            int x = (int) (ox + ab.sx(u.x));
            int y = (int) (oy + ab.sy(u.z));
            int col = (u.color & 0xFFFFFF) | 0xFF000000;

            // tiny dots behind the banner, one per living soldier (capped so a big unit stays legible)
            int dots = Math.min(u.count, 20);
            for (int i = 0; i < dots; i++) {
                int dx = x - 8 + (i % 5) * 4;
                int dy = y + 5 + (i / 5) * 3;
                g.fill(dx, dy, dx + 2, dy + 2, col);
            }

            // banner: a pole with a coloured flag = the "large unit logo"
            g.fill(x - 1, y - 10, x, y + 5, 0xFF1A1208);   // pole
            g.fill(x, y - 10, x + 12, y - 2, col);          // flag
            g.renderOutline(x, y - 10, 12, 8, 0xFF1A1208);
            if (!u.friendly) { g.fill(x + 4, y - 9, x + 8, y - 5, 0xFF201014); }  // dark bar marks a hostile banner

            // name + strength plate above the banner
            String tag = u.name + "  (" + u.count + ")";
            int tw = font.width(tag);
            g.fill(x - tw / 2 - 1, y - 22, x + tw / 2 + 1, y - 11, 0x90000000);
            g.drawString(font, tag, x - tw / 2, y - 21, u.friendly ? 0xFFDFF0DF : 0xFFF0D0D0, true);
        }
    }

    /** Live planner-only pass: the selection ring (and delegated-unit leash) for the selected unit. */
    public static void renderInteractive(GuiGraphics g, AtlasBoard ab, int ox, int oy, boolean commandMode, BlockPos board) {
        if (ab == null) return;
        FieldUnit u = selectedUnit(board);
        if (u == null) return;
        int x = (int) (ox + ab.sx(u.x)), y = (int) (oy + ab.sy(u.z));
        ring(g, x, y, 13, commandMode ? 0xFFFFE070 : 0xFFFFFFFF);
        if (commandMode && u.aiControlled) ring(g, x, y, 26, 0x80FF6060);
    }

    private static void ring(GuiGraphics g, int cx, int cy, int r, int c) {
        double px = cx + r, py = cy;
        for (int i = 1; i <= 24; i++) {
            double a = i * 2 * Math.PI / 24, xx = cx + r * Math.cos(a), yy = cy + r * Math.sin(a);
            line(g, px, py, xx, yy, c); px = xx; py = yy;
        }
    }

    private static void line(GuiGraphics g, double x0, double y0, double x1, double y1, int c) {
        int steps = (int) Math.max(1, Math.hypot(x1 - x0, y1 - y0));
        for (int i = 0; i <= steps; i++) {
            int x = (int) Math.round(x0 + (x1 - x0) * i / steps);
            int y = (int) Math.round(y0 + (y1 - y0) * i / steps);
            g.fill(x, y, x + 1, y + 1, c);
        }
    }
}
