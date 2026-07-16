package com.newtl.warnnobility.atlas.client;

import com.newtl.warnnobility.Config;
import net.minecraft.client.Minecraft;

/**
 * How big the atlas book should be. One source of truth, read by both the mixin that resizes Antique Atlas 8
 * and by {@link AtlasScreenBridge}, so our overlay always clips to the same rectangle the atlas draws.
 *
 * <p><b>The formula is lifted from Antique Atlas 4</b> ({@code AtlasScreen.init}), which sized its book to
 * {@code guiScaledWidth * 0.9 - 40} by {@code guiScaledHeight * 0.9 - 10} and fell back to the classic
 * 310x218 otherwise. That is why the AA4 book filled the window while AA8's sits as a stamp in the middle:
 * AA8 keeps its size as {@code static final} constants, which javac inlines everywhere, so there is no field
 * to set and no config to flip. The mixin rewrites those inlined literals instead.
 *
 * <p>The borders are deliberately NOT scaled: AA8's map window is the book inset by 17px horizontally and
 * 24px vertically ({@code 310-276} and {@code 218-194}), and keeping those fixed while the book grows is
 * exactly what AA4 did, so the frame art stays a sane thickness instead of ballooning.
 */
public final class AtlasSize {

    private AtlasSize() {}

    /** Antique Atlas 8's own book size, as inlined in its class file. The fallback when we leave it alone. */
    public static final int VANILLA_BOOK_W = 310, VANILLA_BOOK_H = 218;
    /** The book-to-map inset, derived from AA8's own constants (310-276 = 34, 218-194 = 24). */
    private static final int MAP_INSET_W = 34, MAP_INSET_H = 24;

    public static boolean enlarged() {
        try { return Config.ATLAS_BIG_BOOK.get(); } catch (Throwable t) { return false; }
    }

    public static int bookWidth() {
        if (!enlarged()) return VANILLA_BOOK_W;
        int w = (int) (Minecraft.getInstance().getWindow().getGuiScaledWidth() * 0.9 - 40.0);
        return Math.max(VANILLA_BOOK_W, w);   // never end up smaller than the book we replaced
    }

    public static int bookHeight() {
        if (!enlarged()) return VANILLA_BOOK_H;
        int h = (int) (Minecraft.getInstance().getWindow().getGuiScaledHeight() * 0.9 - 10.0);
        return Math.max(VANILLA_BOOK_H, h);
    }

    public static int mapWidth() { return bookWidth() - MAP_INSET_W; }

    public static int mapHeight() { return bookHeight() - MAP_INSET_H; }
}
