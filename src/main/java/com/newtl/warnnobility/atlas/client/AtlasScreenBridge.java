package com.newtl.warnnobility.atlas.client;

import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;

/**
 * The single seam between War 'n Nobility and the Antique Atlas GUI.
 *
 * <p>Everything we draw on the atlas (borders, labels, colony markers, the terrain raster, structure
 * footprints) needs exactly two things from it: where a world position lands on screen, and which rectangle
 * to clip to. This class answers both and is the ONLY place that knows an atlas exists. The last swap taught
 * us why that matters: Atlas 4 reflection was spread across four files, so replacing it meant touching all
 * of them. Point this at a new atlas and the layers follow.
 *
 * <p><b>Targets Antique Atlas 8</b> ({@code hunternif.mc.impl.atlas}), a native NeoForge mod, whose
 * {@code GuiAtlas} maps the world to the screen linearly:
 * {@code screenX = worldX * mapScale + screen.width/2 + mapOffsetX} (and the same on Z with height and
 * mapOffsetY). It exposes {@code worldXToScreenX(int)}, but we read the three fields and do the arithmetic
 * ourselves in double precision instead: its method rounds to int, and {@link MapRaster} recovers the
 * projection's scale by probing two points, so integer rounding there would quantise the zoom.
 *
 * <p>All reflection is fail-soft. If the atlas is absent or reshaped, {@link #isAtlas} stays false and every
 * layer quietly draws nothing rather than breaking the screen.
 */
public final class AtlasScreenBridge {

    private AtlasScreenBridge() {}

    private static final String GUI_ATLAS = "hunternif.mc.impl.atlas.client.gui.GuiAtlas";

    /** The book-to-map inset. AA8 inlines its geometry, so these mirror it (310-276 = 34, 218-194 = 24). */
    private static final int MAP_BORDER_W = 17, MAP_BORDER_H = 11;
    private static final int MAP_INSET_W = 34, MAP_INSET_H = 24;

    private static boolean tried, broken;
    private static Field F_MAP_SCALE, F_OFFSET_X, F_OFFSET_Y;
    private static java.lang.reflect.Method M_GUI_X, M_GUI_Y, M_WIDTH, M_HEIGHT;

    public static boolean isAtlas(Screen s) {
        return s != null && s.getClass().getName().equals(GUI_ATLAS);
    }

    private static synchronized void init() {
        if (tried) return;
        tried = true;
        try {
            Class<?> c = Class.forName(GUI_ATLAS);
            F_MAP_SCALE = c.getDeclaredField("mapScale");
            F_OFFSET_X = c.getDeclaredField("mapOffsetX");
            F_OFFSET_Y = c.getDeclaredField("mapOffsetY");
            F_MAP_SCALE.setAccessible(true);
            F_OFFSET_X.setAccessible(true);
            F_OFFSET_Y.setAccessible(true);
            // Ask the book where it actually IS and how big it actually is, rather than recomputing its
            // centring ourselves. Duplicating that math is what put our overlay on the grass: the atlas
            // clamps and centres by its own rules, and any copy of them is a guess that can disagree.
            Class<?> component = Class.forName("hunternif.mc.impl.atlas.client.gui.core.GuiComponent");
            M_GUI_X = component.getMethod("getGuiX");
            M_GUI_Y = component.getMethod("getGuiY");
            M_WIDTH = component.getDeclaredMethod("getWidth");
            M_HEIGHT = component.getDeclaredMethod("getHeight");
            M_WIDTH.setAccessible(true);
            M_HEIGHT.setAccessible(true);
        } catch (Throwable t) {
            broken = true;
        }
    }

    /** Whether we can read the atlas's projection at all. False = every layer stands down. */
    public static boolean available() { init(); return !broken; }

    /** Screen pixels per world block, straight from the atlas's own zoom. */
    public static double pixelsPerBlock(Screen atlas) {
        init();
        if (broken) return 0;
        try { return F_MAP_SCALE.getDouble(atlas); } catch (Throwable t) { return 0; }
    }

    /**
     * The atlas's world-to-screen mapping, in double precision. Returns null if it cannot be read, which
     * every layer treats as "draw nothing".
     */
    public static MapProjector projector(Screen atlas) {
        init();
        if (broken) return null;
        try {
            double scale = F_MAP_SCALE.getDouble(atlas);
            double offX = F_OFFSET_X.getInt(atlas);
            double offY = F_OFFSET_Y.getInt(atlas);
            // The atlas anchors its map on the SCREEN centre, not the book's, so these use the screen size.
            double cx = atlas.width / 2.0, cy = atlas.height / 2.0;
            return (wx, wz) -> new double[]{wx * scale + cx + offX, wz * scale + cy + offY};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The map window inside the book, as {@code {x0, y0, x1, y1}} screen pixels: the rectangle every layer
     * scissors to so nothing spills onto the page margins or the buttons.
     */
    public static int[] mapBox(Screen atlas) {
        init();
        // Read the book's REAL position and size from the atlas itself. Everything else is derived from
        // those, so the overlay clips to exactly the rectangle the book occupies, whatever size it ends up.
        int bookX, bookY, bookW, bookH;
        try {
            bookX = (Integer) M_GUI_X.invoke(atlas);
            bookY = (Integer) M_GUI_Y.invoke(atlas);
            bookW = (Integer) M_WIDTH.invoke(atlas);
            bookH = (Integer) M_HEIGHT.invoke(atlas);
        } catch (Throwable t) {
            bookW = AtlasSize.bookWidth();
            bookH = AtlasSize.bookHeight();
            bookX = (atlas.width - bookW) / 2;
            bookY = (atlas.height - bookH) / 2;
        }
        int x0 = bookX + MAP_BORDER_W, y0 = bookY + MAP_BORDER_H;
        return new int[]{x0, y0, x0 + bookW - MAP_INSET_W, y0 + bookH - MAP_INSET_H};
    }
}
