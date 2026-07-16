package com.newtl.warnnobility.atlas.client;

import com.newtl.warnnobility.atlas.Discovery;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws discovered player structures as markers on a map surface (the opened atlas book, the in-hand
 * atlas, and the War Frame), with <b>zoom-aware clustering</b>: markers within {@link #COLLIDE_PX} screen
 * pixels of each other collapse into one aggregate "N structures" pin, and split back into individual
 * building markers as you zoom in (their pixel spacing grows). One shared renderer keeps every surface
 * identical.
 *
 * <p>The surface only differs by how a world coordinate maps to a screen pixel, so callers pass a
 * {@link MapProjector}; everything else (clustering, glyphs, names, hover tooltip) is common here.
 */
public final class DiscoveryMarkers {

    private DiscoveryMarkers() {}

    /** Two markers closer than this (screen px) collapse into a cluster. Constant in px = zoom-aware. */
    private static final double COLLIDE_PX = 11.0;
    private static final int HOVER_PX = 8;

    /**
     * Cluster + draw the discoveries onto {@code g}. {@code mouseX/mouseY} enable a hover tooltip (pass a
     * negative value where there is no cursor, e.g. the War Frame bake). Marks are clipped to the box.
     */
    public static void draw(GuiGraphics g, Font font, List<Discovery> discs, MapProjector proj,
                            int clipX0, int clipY0, int clipX1, int clipY1, int mouseX, int mouseY) {
        draw(g, font, discs, proj, clipX0, clipY0, clipX1, clipY1, mouseX, mouseY, true);
    }

    /**
     * As above, but {@code glyphs} may be false to suppress the little house pins while keeping the names
     * and tooltips. That is what the map does once it is zoomed in far enough for {@link StructureSketch} to
     * ink each build's real footprint: a generic pin sitting on top of the actual traced outline is just
     * clutter, but you still want to know what the place is called.
     */
    public static void draw(GuiGraphics g, Font font, List<Discovery> discs, MapProjector proj,
                            int clipX0, int clipY0, int clipX1, int clipY1, int mouseX, int mouseY,
                            boolean glyphs) {
        if (discs.isEmpty()) return;

        // project everything inside the map box, biggest first so the largest build seeds each cluster
        List<P> pts = new ArrayList<>();
        for (Discovery d : discs) {
            double[] s = proj.project(d.x, d.z);
            if (s == null) continue;
            if (s[0] < clipX0 || s[0] > clipX1 || s[1] < clipY0 || s[1] > clipY1) continue;
            pts.add(new P(s[0], s[1], d));
        }
        if (pts.isEmpty()) return;
        pts.sort((a, b) -> Integer.compare(b.d.size, a.d.size));

        boolean[] used = new boolean[pts.size()];
        List<int[]> placedNames = new ArrayList<>();
        Discovery hovered = null; int hovX = 0, hovY = 0; boolean hovCluster = false; int hovCount = 0;

        for (int i = 0; i < pts.size(); i++) {
            if (used[i]) continue;
            used[i] = true;
            P seed = pts.get(i);
            int count = 1;
            for (int j = i + 1; j < pts.size(); j++) {
                if (used[j]) continue;
                if (Math.hypot(pts.get(j).sx - seed.sx, pts.get(j).sy - seed.sy) <= COLLIDE_PX) {
                    used[j] = true;
                    count++;
                }
            }
            int x = (int) Math.round(seed.sx), y = (int) Math.round(seed.sy);
            boolean hover = mouseX >= 0 && Math.abs(mouseX - x) <= HOVER_PX && Math.abs(mouseY - y) <= HOVER_PX;

            if (count == 1) {
                if (glyphs) houseGlyph(g, x, y, (seed.d.rgb() & 0xFFFFFF) | 0xFF000000);
                nameplate(g, font, x, y + 6, seed.d.name(), 0xFFEFE6CF, placedNames, clipX0, clipY0, clipX1, clipY1);
                if (hover) { hovered = seed.d; hovX = x; hovY = y; }
            } else {
                clusterGlyph(g, x, y, count, (seed.d.rgb() & 0xFFFFFF) | 0xFF000000);
                nameplate(g, font, x, y + 8, count + " structures", 0xFFFFE7B0, placedNames, clipX0, clipY0, clipX1, clipY1);
                if (hover) { hovCluster = true; hovX = x; hovY = y; hovCount = count; }
            }
        }

        // hover tooltip (opened book only; the War Frame passes a negative mouse). Drawn last, on top.
        if (hovered != null) {
            tooltip(g, font, hovX, hovY, hovered.name(), hovered.materials + "  ·  " + hovered.size + " blocks");
        } else if (hovCluster) {
            tooltip(g, font, hovX, hovY, hovCount + " structures", "zoom in to separate them");
        }
    }

    private record P(double sx, double sy, Discovery d) {}

    /** A small stylised building glyph (roof + tinted body + door), matching the atlas town markers. */
    private static void houseGlyph(GuiGraphics g, int x, int y, int fill) {
        int line = 0xFF1A1208;
        g.fill(x - 1, y - 5, x + 1, y - 4, line);
        g.fill(x - 3, y - 4, x + 3, y - 3, line);         // roof
        g.fill(x - 4, y - 3, x + 4, y - 2, line);
        g.fill(x - 4, y - 2, x + 4, y + 4, line);         // body outline
        g.fill(x - 3, y - 2, x + 3, y + 3, fill);         // tinted body
        g.fill(x - 1, y + 1, x + 1, y + 4, line);         // door
    }

    /** An aggregate pin: a tinted diamond with a dark ring and the count centered on it. */
    private static void clusterGlyph(GuiGraphics g, int x, int y, int count, int fill) {
        int line = 0xFF120C06;
        for (int r = 5; r >= 0; r--) {
            int col = (r == 5) ? line : fill;
            int w = 5 - r;
            g.fill(x - w, y - r, x + w + 1, y - r + 1, col);   // top half of a diamond
            g.fill(x - w, y + r, x + w + 1, y + r + 1, col);   // bottom half
        }
        String s = count > 99 ? "99+" : Integer.toString(count);
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        g.drawString(font, s, x - font.width(s) / 2 + 1, y - 3, 0xFF241608, false);
    }

    private static void nameplate(GuiGraphics g, Font font, int cx, int topY, String n, int color,
                                  List<int[]> placed, int cx0, int cy0, int cx1, int cy1) {
        if (n == null || n.isEmpty()) return;
        int tw = font.width(n);
        int x0 = cx - tw / 2 - 1, x1 = cx + tw / 2 + 1, h = 11, y = topY, guard = 0;
        boolean moved = true;
        while (moved && guard++ < 32) {
            moved = false;
            for (int[] r : placed) {
                if (x0 < r[2] && x1 > r[0] && y < r[3] && (y + h) > r[1]) { y = r[3] + 1; moved = true; break; }
            }
        }
        placed.add(new int[]{x0, y, x1, y + h});
        if (x0 < cx0 || x1 > cx1 || y < cy0 || y + h > cy1) return;   // keep names inside the map box
        g.fill(x0, y, x1, y + 11, 0x90000000);
        g.drawString(font, n, cx - tw / 2, y + 1, color, true);
    }

    private static void tooltip(GuiGraphics g, Font font, int x, int y, String title, String sub) {
        int w = Math.max(font.width(title), font.width(sub)) + 8;
        int h = 22;
        int tx = x + 8, ty = y - 8;
        g.fill(tx - 1, ty - 1, tx + w + 1, ty + h + 1, 0xF0110C06);
        g.fill(tx, ty, tx + w, ty + h, 0xF02A1E12);
        g.drawString(font, title, tx + 4, ty + 3, 0xFFFFE7B0, false);
        g.drawString(font, sub, tx + 4, ty + 12, 0xFFCFC8B8, false);
    }
}
