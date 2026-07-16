package com.newtl.warnnobility.atlas.client;

import com.newtl.warnnobility.atlas.Discovery;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Inks a discovered build's TRUE footprint onto the map, the way a cartographer would draw an estate plan:
 * the walls traced as a dark outline, the interior washed in the archetype's colour and cross-hatched.
 *
 * <p>This is the layer that makes a structure read as a deliberate place rather than a coloured smudge in
 * the terrain raster underneath it. It needs a real shape, so it draws only from {@link Discovery#mask} (the
 * surveyed footprint); a build too large to carry a mask falls back to its outline box.
 *
 * <p>Only worth drawing when a block is at least a couple of pixels across — below {@link #MIN_PPB} the
 * outline collapses into noise, so the caller shows clustered markers instead. Nothing here is cached: the
 * mark set on screen at sketch zoom is small, and staying uncached means an edit shows the instant it syncs.
 */
public final class StructureSketch {

    private StructureSketch() {}

    /** Below this many screen pixels per block, a footprint is too small to read: use markers instead. */
    public static final double MIN_PPB = 1.6;

    private static final int INK = 0xE01A1208;

    /** Draw every visible discovery's footprint. {@code ppb} is screen pixels per world block. */
    public static void draw(GuiGraphics g, List<Discovery> discs, MapProjector proj,
                            int clipX0, int clipY0, int clipX1, int clipY1, double ppb) {
        if (discs.isEmpty() || ppb < MIN_PPB) return;
        int stroke = ppb >= 3.0 ? 2 : 1;
        for (Discovery d : discs) {
            double[] a = proj.project(d.minX, d.minZ);
            double[] b = proj.project(d.maxX + 1.0, d.maxZ + 1.0);
            if (a == null || b == null) continue;
            if (b[0] < clipX0 || a[0] > clipX1 || b[1] < clipY0 || a[1] > clipY1) continue;   // off-screen
            if (d.mask == null) outlineBox(g, a, b, stroke);
            else sketch(g, d, proj, clipX0, clipY0, clipX1, clipY1, stroke);
        }
    }

    /** Wash + hatch the built columns, then ink every edge where the build meets open ground. */
    private static void sketch(GuiGraphics g, Discovery d, MapProjector proj,
                               int clipX0, int clipY0, int clipX1, int clipY1, int stroke) {
        int tint = (d.rgb() & 0xFFFFFF) | 0x70000000;
        int hatch = (darken(d.rgb(), 0.55f) & 0xFFFFFF) | 0x55000000;
        for (int wx = d.minX; wx <= d.maxX; wx++) {
            for (int wz = d.minZ; wz <= d.maxZ; wz++) {
                if (!d.built(wx, wz)) continue;
                double[] p0 = proj.project(wx, wz);
                double[] p1 = proj.project(wx + 1.0, wz + 1.0);
                if (p0 == null || p1 == null) continue;
                int x0 = (int) Math.round(p0[0]), y0 = (int) Math.round(p0[1]);
                int x1 = (int) Math.round(p1[0]), y1 = (int) Math.round(p1[1]);
                if (x1 < clipX0 || x0 > clipX1 || y1 < clipY0 || y0 > clipY1) continue;
                if (x1 <= x0) x1 = x0 + 1;
                if (y1 <= y0) y1 = y0 + 1;

                g.fill(x0, y0, x1, y1, tint);
                // A diagonal weave over the wash: enough to read as drawn hatching, cheap enough to be free.
                if (((wx + wz) & 3) == 0) g.fill(x0, y0, x1, y1, hatch);

                // Ink only the sides facing open ground, so the trace follows the real wall line.
                if (!d.built(wx, wz - 1)) g.fill(x0, y0, x1, y0 + stroke, INK);
                if (!d.built(wx, wz + 1)) g.fill(x0, y1 - stroke, x1, y1, INK);
                if (!d.built(wx - 1, wz)) g.fill(x0, y0, x0 + stroke, y1, INK);
                if (!d.built(wx + 1, wz)) g.fill(x1 - stroke, y0, x1, y1, INK);
            }
        }
    }

    /** Fallback for a build too sprawling to carry a mask: just rule its extent. */
    private static void outlineBox(GuiGraphics g, double[] a, double[] b, int stroke) {
        int x0 = (int) Math.round(a[0]), y0 = (int) Math.round(a[1]);
        int x1 = (int) Math.round(b[0]), y1 = (int) Math.round(b[1]);
        if (x1 <= x0 || y1 <= y0) return;
        g.fill(x0, y0, x1, y0 + stroke, INK);
        g.fill(x0, y1 - stroke, x1, y1, INK);
        g.fill(x0, y0, x0 + stroke, y1, INK);
        g.fill(x1 - stroke, y0, x1, y1, INK);
    }

    private static int darken(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), b = (int) ((rgb & 255) * f);
        return (r << 16) | (g << 8) | b;
    }
}
