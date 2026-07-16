package com.newtl.warnnobility.warmap.client;

import com.newtl.warnnobility.domain.DomainEngine;
import com.newtl.warnnobility.domain.client.ClientDomains;
import com.newtl.warnnobility.domain.client.DomainOverlay;
import com.newtl.warnnobility.warmap.MapTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.newtl.warnnobility.warmap.WarMapData;
import com.newtl.warnnobility.warmap.WarStroke;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;

/**
 * Draws a War Table's contents (atlas tiles, domain/faction borders, the drawn plan, and pointer
 * pulses) into a 2D {@link GuiGraphics} box at {@code (ox,oy)} sized {@code size}. Used both for the
 * planner preview and for baking the in-world board into an offscreen texture, so the GUI and the
 * physical board always show exactly the same thing.
 */
public final class WarMapDraw {

    private WarMapDraw() {}

    /** The atlas book's page colour (matched to AA's warm tan page). AA draws its map as hand-drawn ink
     *  symbols ON this parchment; without it the tiles read as faint scribbles on dark slate. */
    private static final int PARCHMENT = 0xFFE6D3A6;

    /**
     * The whole board in one pass (base + overlay). Kept for the planner's pre-first-bake fallback and any
     * caller that wants a single self-contained image. Live rendering splits these into two cached layers
     * ({@link #renderBase} rarely, {@link #renderOverlay} often) so a busy briefing never re-bakes the
     * expensive atlas — see {@link WarBoardTextures}.
     */
    public static void renderContent(GuiGraphics g, int ox, int oy, int size,
                                     AtlasBoard ab, WarMapData data, BlockPos board) {
        renderBase(g, ox, oy, size, ab);
        renderOverlay(g, ox, oy, size, ab, data, board);
    }

    /**
     * The STATIC layer: parchment, atlas tiles, domain/faction borders, colony + territory name labels,
     * and the wood frame. Everything here changes only when the view pans/zooms or the world map itself
     * changes, so {@link WarBoardTextures} bakes it into a cached texture and reuses it across frames.
     */
    public static void renderBase(GuiGraphics g, int ox, int oy, int size, AtlasBoard ab) {
        // 0) the parchment page behind everything (flush so it draws BEHIND the tiles, not over them)
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);   // clear any leftover tint
        g.fill(ox, oy, ox + size, oy + size, PARCHMENT);
        g.flush();

        // 1) atlas tiles (clipped to this box by the caller's scissor / framebuffer bounds). Rendered
        //    twice so the semi-transparent antique ink builds up enough contrast to read on parchment.
        if (ab != null) {
            g.pose().pushPose();
            g.pose().translate(ox, oy, 0);
            // Tiles use an entity render type that wants flat-item diffuse lighting (else dark/tinted).
            // Fog is suppressed by the caller (WarBoardTextures.bake) around the whole render, because by
            // day the world fog washed both tiles AND the label text to pale blue.
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            com.mojang.blaze3d.platform.Lighting.setupForFlatItems();
            ab.renderTiles(g.pose(), g.bufferSource(), LightTexture.FULL_BRIGHT);
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            g.pose().popPose();
        }
        if (ab != null) {
            final int fox = ox, foy = oy;
            final AtlasBoard fab = ab;
            com.newtl.warnnobility.atlas.client.MapProjector proj =
                    (wx, wz) -> new double[]{fox + fab.sx(wx), foy + fab.sy(wz)};
            int cx0 = ox + 12, cy0 = oy + 12, cx1 = ox + size - 12, cy1 = oy + size - 12;

            // 1b) the real terrain picture, straight over the atlas tiles and under everything else, so the
            //     board shows the ground the way the handheld atlas does rather than a blank biome wash.
            if (ClientDomains.isEnabled(ClientDomains.L_TERRAIN)) {
                com.newtl.warnnobility.atlas.client.MapRaster.draw(
                        g, Minecraft.getInstance().level, proj, cx0, cy0, cx1, cy1);
            }
            // 2) domain + faction borders (follow the legend toggles via DomainOverlay's edge lists)
            for (int[] e : DomainOverlay.borderEdges()) edge(g, ab, ox, oy, e);
            for (int[] e : DomainOverlay.factionBorderEdges()) edge(g, ab, ox, oy, e);
            // 5) colony markers + domain / faction name labels (matching the atlas book)
            drawMarkers(g, ab, ox, oy);
            // 5b) discovered structures: inked footprints when the board is zoomed in far enough to read
            //     them, clustered pins when it is not. Part of the base layer: it changes rarely, and its
            //     version is folded into the bake key.
            java.util.List<com.newtl.warnnobility.atlas.Discovery> discs =
                    com.newtl.warnnobility.atlas.client.ClientDiscoveries.list();
            if (!discs.isEmpty() && ClientDomains.isEnabled(ClientDomains.L_STRUCTURES)) {
                boolean sketching = ab.ppb >= com.newtl.warnnobility.atlas.client.StructureSketch.MIN_PPB;
                if (sketching) {
                    com.newtl.warnnobility.atlas.client.StructureSketch.draw(
                            g, discs, proj, cx0, cy0, cx1, cy1, ab.ppb);
                }
                com.newtl.warnnobility.atlas.client.DiscoveryMarkers.draw(
                        g, Minecraft.getInstance().font, discs, proj,
                        cx0, cy0, cx1, cy1, -1, -1, !sketching);
            }
        }
        frame(g, ox, oy, size);
    }

    /**
     * The LIVE layer: the drawn plan, pointer pulses, and field units/colonists. These change constantly
     * during a briefing (someone drawing, armies marching, pings firing) but are cheap vector marks, so
     * {@link WarBoardTextures} keeps them in a SEPARATE texture re-baked only when they actually change,
     * composited over the cached base. The wood frame is redrawn last here too, so edge marks stay clipped
     * exactly as they were when everything shared one texture.
     */
    public static void renderOverlay(GuiGraphics g, int ox, int oy, int size,
                                     AtlasBoard ab, WarMapData data, BlockPos board) {
        if (ab == null) return;

        // 3) the plan
        for (WarStroke s : data.strokes) stroke(g, ab, ox, oy, s.tool, s.color, s.label, s.pts, s.size);

        // 4) pointer pulses
        for (ClientWarMaps.Pulse p : ClientWarMaps.pulsesFor(board)) {
            float a = 1f - (float) (net.minecraft.Util.getMillis() - p.born) / ClientWarMaps.PING_LIFETIME_MS;
            int col = (Math.max(30, (int) (Math.max(0f, a) * 255)) << 24) | (p.color & 0xFFFFFF);
            circle(g, ox + ab.sx(p.x), oy + ab.sy(p.z), 3 + (1 - Math.max(0f, a)) * 8, col);
        }

        // 6) field units + colonists, so the PHYSICAL in-world board shows them too (the GUI planner draws
        //    only selection highlights on top). Skipped when the board's "show units" toggle is off.
        if (data.showUnits) ClientUnits.renderAll(g, ab, ox, oy, Minecraft.getInstance().font, board);

        frame(g, ox, oy, size);
    }

    /** The bevelled wood "Large War Frame" border (light top-left / dark bottom-right), which also clips
     *  any mark that reached the board edge. */
    private static void frame(GuiGraphics g, int ox, int oy, int size) {
        int b = 12;
        int light = 0xFF4A3724, dark = 0xFF2A1E12;
        g.fill(ox, oy, ox + size, oy + b, light);                       // top
        g.fill(ox, oy, ox + b, oy + size, light);                       // left
        g.fill(ox, oy + size - b, ox + size, oy + size, dark);          // bottom
        g.fill(ox + size - b, oy, ox + size, oy + size, dark);          // right
    }

    /**
     * Draw ONLY the domain/faction borders + name markers (no parchment, tiles, plan, or wood frame)
     * into a board box whose origin is {@code (ox,oy)}. Lets the F4 minimap show the same territory
     * overlay the War Frame does. The caller owns the scissor that clips it to the visible map.
     */
    public static void renderOverlays(GuiGraphics g, AtlasBoard ab, int ox, int oy) {
        if (ab == null) return;
        for (int[] e : DomainOverlay.borderEdges()) edge(g, ab, ox, oy, e);
        for (int[] e : DomainOverlay.factionBorderEdges()) edge(g, ab, ox, oy, e);
        drawMarkers(g, ab, ox, oy);
    }

    public static void stroke(GuiGraphics g, AtlasBoard ab, int ox, int oy,
                              MapTool t, int rgb, String label, double[] p) {
        stroke(g, ab, ox, oy, t, rgb, label, p, WarStroke.DEFAULT_SIZE);
    }

    /** Draw one mark. {@code size} is the universal brush weight: line thickness in px and text scale. */
    public static void stroke(GuiGraphics g, AtlasBoard ab, int ox, int oy,
                              MapTool t, int rgb, String label, double[] p, int size) {
        if (p.length < 2) return;
        int c = rgb | 0xFF000000;
        int th = Math.max(1, size);                         // line thickness (px)
        float sc = 1f + (Math.max(1, size) - 1) * 0.35f;    // text font scale
        switch (t) {
            case PEN, PHASE_LINE -> { for (int i = 0; i + 3 < p.length; i += 2) line(g, ab, ox, oy, p[i], p[i+1], p[i+2], p[i+3], c, th); }
            case LINE -> line(g, ab, ox, oy, p[0], p[1], p[2], p[3], c, th);
            case ARROW -> arrow(g, ab, ox, oy, p[0], p[1], p[p.length-2], p[p.length-1], c, th);
            case BOX -> box(g, ab, ox, oy, p[0], p[1], p[2], p[3], c, th);
            case CIRCLE -> circle(g, ox + ab.sx(p[0]), oy + ab.sy(p[1]),
                    dist(ab.sx(p[0]), ab.sy(p[1]), ab.sx(p[2]), ab.sy(p[3])), c, th);
            case OBJECTIVE -> { circle(g, ox + ab.sx(p[0]), oy + ab.sy(p[1]), 6, c, th); text(g, ab, ox, oy, p, label, c, sc); }
            case UNIT_FRIENDLY -> { double x = ox + ab.sx(p[0]), y = oy + ab.sy(p[1]);
                hv(g, x-5, y-4, x+5, y-4, c, th); hv(g, x-5, y+4, x+5, y+4, c, th); hv(g, x-5, y-4, x-5, y+4, c, th); hv(g, x+5, y-4, x+5, y+4, c, th);
                text(g, ab, ox, oy, p, label, c, sc); }
            case UNIT_HOSTILE -> { double x = ox + ab.sx(p[0]), y = oy + ab.sy(p[1]);
                hv(g, x-5, y-5, x+5, y+5, c, th); hv(g, x-5, y+5, x+5, y-5, c, th); text(g, ab, ox, oy, p, label, c, sc); }
            case UNIT_UNKNOWN -> { circle(g, ox + ab.sx(p[0]), oy + ab.sy(p[1]), 5, c, th);
                text(g, ab, ox, oy, p, label.isEmpty() ? "?" : label, c, sc); }
            case TEXT -> text(g, ab, ox, oy, p, label, c, sc);
            default -> { }
        }
    }

    private static void edge(GuiGraphics g, AtlasBoard ab, int ox, int oy, int[] e) {
        int cx = e[0], cz = e[1], side = e[2], rgb = e[3] | 0xFF000000;
        double x0 = ox + ab.sx(cx * 16.0), x1 = ox + ab.sx((cx + 1) * 16.0);
        double y0 = oy + ab.sy(cz * 16.0), y1 = oy + ab.sy((cz + 1) * 16.0);
        switch (side) {
            case 0 -> hv(g, x0, y0, x1, y0, rgb);
            case 1 -> hv(g, x0, y1, x1, y1, rgb);
            case 2 -> hv(g, x0, y0, x0, y1, rgb);
            default -> hv(g, x1, y0, x1, y1, rgb);
        }
    }

    private static void text(GuiGraphics g, AtlasBoard ab, int ox, int oy, double[] p, String label, int c) {
        text(g, ab, ox, oy, p, label, c, 1f);
    }

    private static void text(GuiGraphics g, AtlasBoard ab, int ox, int oy, double[] p, String label, int c, float scale) {
        if (label == null || label.isEmpty()) return;
        float x = (float) (ox + ab.sx(p[0]) + 6), y = (float) (oy + ab.sy(p[1]) - 4);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(net.minecraft.client.Minecraft.getInstance().font, label, 0, 0, c, false);
        g.pose().popPose();
    }

    private static void line(GuiGraphics g, AtlasBoard ab, int ox, int oy,
                             double wx0, double wz0, double wx1, double wz1, int c, int th) {
        hv(g, ox + ab.sx(wx0), oy + ab.sy(wz0), ox + ab.sx(wx1), oy + ab.sy(wz1), c, th);
    }

    private static void arrow(GuiGraphics g, AtlasBoard ab, int ox, int oy,
                              double wx0, double wz0, double wx1, double wz1, int c, int th) {
        double x0 = ox + ab.sx(wx0), y0 = oy + ab.sy(wz0), x1 = ox + ab.sx(wx1), y1 = oy + ab.sy(wz1);
        hv(g, x0, y0, x1, y1, c, th);
        double ang = Math.atan2(y1 - y0, x1 - x0), h = 6 + th;
        hv(g, x1, y1, x1 - h * Math.cos(ang - 0.4), y1 - h * Math.sin(ang - 0.4), c, th);
        hv(g, x1, y1, x1 - h * Math.cos(ang + 0.4), y1 - h * Math.sin(ang + 0.4), c, th);
    }

    private static void box(GuiGraphics g, AtlasBoard ab, int ox, int oy,
                            double a, double b, double c2, double d, int c, int th) {
        double x0 = ox + ab.sx(a), y0 = oy + ab.sy(b), x1 = ox + ab.sx(c2), y1 = oy + ab.sy(d);
        hv(g, x0, y0, x1, y0, c, th); hv(g, x1, y0, x1, y1, c, th); hv(g, x1, y1, x0, y1, c, th); hv(g, x0, y1, x0, y0, c, th);
    }

    private static void circle(GuiGraphics g, double cx, double cy, double r, int c) { circle(g, cx, cy, r, c, 1); }

    private static void circle(GuiGraphics g, double cx, double cy, double r, int c, int th) {
        double px = cx + r, py = cy;
        for (int i = 1; i <= 24; i++) {
            double a = i * 2 * Math.PI / 24, x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
            hv(g, px, py, x, y, c, th); px = x; py = y;
        }
    }

    private static void hv(GuiGraphics g, double x0, double y0, double x1, double y1, int c) { hv(g, x0, y0, x1, y1, c, 1); }

    /** A DDA line of thickness {@code th} via square fills (GuiGraphics has no diagonal-line primitive). */
    private static void hv(GuiGraphics g, double x0, double y0, double x1, double y1, int c, int th) {
        int steps = (int) Math.max(1, Math.hypot(x1 - x0, y1 - y0));
        int h0 = th / 2, h1 = th - h0;
        for (int i = 0; i <= steps; i++) {
            int x = (int) Math.round(x0 + (x1 - x0) * i / steps), y = (int) Math.round(y0 + (y1 - y0) * i / steps);
            g.fill(x - h0, y - h0, x + h1, y + h1, c);
        }
    }

    private static double dist(double x0, double y0, double x1, double y1) {
        return Math.hypot(x1 - x0, y1 - y0);
    }

    // --- colony markers + domain/faction name labels (reuse the atlas overlay's glyphs) -----------

    private static void drawMarkers(GuiGraphics g, AtlasBoard ab, int ox, int oy) {
        Font font = Minecraft.getInstance().font;
        java.util.List<int[]> placed = new java.util.ArrayList<>();
        // Domain labels first, recording each seat so we do NOT also stack the colony's own name there
        // (a duchy seat would otherwise show both "Duchy: X" and the colony name = the "doubling").
        java.util.Set<Long> seats = new java.util.HashSet<>();
        for (DomainEngine.Label l : ClientDomains.labels()) {
            if (!tierOn(l.tier())) continue;
            seats.add((((long) l.blockX()) << 32) | (l.blockZ() & 0xFFFFFFFFL));
            int x = (int) (ox + ab.sx(l.blockX())), y = (int) (oy + ab.sy(l.blockZ()));
            DomainOverlay.drawTierMarker(g, x, y, l.tier(), l.rgb() & 0xFFFFFF);
            namePlate(g, font, x, y + 6, l.name(), 0xFFFFFFFF, placed);
        }
        if (ClientDomains.isEnabled(ClientDomains.L_COLONIES)) {
            for (DomainEngine.Label t : ClientDomains.colonyMarkers()) {
                if (seats.contains((((long) t.blockX()) << 32) | (t.blockZ() & 0xFFFFFFFFL))) continue;
                int x = (int) (ox + ab.sx(t.blockX())), y = (int) (oy + ab.sy(t.blockZ()));
                house(g, x, y, (t.rgb() & 0xFFFFFF) | 0xFF000000);
                namePlate(g, font, x, y + 6, t.name(), 0xFFEFE6CF, placed);
            }
        }
        if (ClientDomains.isEnabled(ClientDomains.L_FACTIONS)) {
            for (DomainEngine.Label l : ClientDomains.factionLabels()) {
                int x = (int) (ox + ab.sx(l.blockX())), y = (int) (oy + ab.sy(l.blockZ()));
                DomainOverlay.drawTierMarker(g, x, y, 4, l.rgb() & 0xFFFFFF);
                namePlate(g, font, x, y + 6, l.name(), 0xFFFFFFFF, placed);
            }
        }
    }

    private static boolean tierOn(int tier) {
        return switch (tier) {
            case 1 -> ClientDomains.isEnabled(ClientDomains.L_COUNTIES);
            case 2 -> ClientDomains.isEnabled(ClientDomains.L_DUCHIES);
            case 3 -> ClientDomains.isEnabled(ClientDomains.L_KINGDOMS);
            default -> true;
        };
    }

    /** A small house glyph (colony) tinted its claim colour, matching the atlas town marker. */
    private static void house(GuiGraphics g, int x, int y, int fill) {
        int line = 0xFF1A1208;
        g.fill(x - 1, y - 4, x + 1, y - 3, line);
        g.fill(x - 2, y - 3, x + 2, y - 2, line);
        g.fill(x - 3, y - 2, x + 3, y - 1, line);
        g.fill(x - 3, y - 1, x + 3, y + 4, line);
        g.fill(x - 2, y - 1, x + 2, y + 3, fill);
        g.fill(x - 1, y + 1, x + 1, y + 4, line);
    }

    private static void namePlate(GuiGraphics g, Font font, int cx, int topY, String n, int color, java.util.List<int[]> placed) {
        if (n == null || n.isEmpty()) return;
        int tw = font.width(n);
        int x0 = cx - tw / 2 - 2, x1 = cx + tw / 2 + 2, h = 11, y = topY, guard = 0;
        boolean moved = true;
        while (moved && guard++ < 32) {     // stack overlapping plates downward so names stay readable
            moved = false;
            for (int[] r : placed) {
                if (x0 < r[2] && x1 > r[0] && y < r[3] && (y + h) > r[1]) { y = r[3] + 1; moved = true; break; }
            }
        }
        placed.add(new int[]{x0, y, x1, y + h});
        g.fill(cx - tw / 2 - 1, y, cx + tw / 2 + 1, y + 11, 0x90000000);
        g.drawString(font, n, cx - tw / 2, y + 1, color, true);
    }
}
