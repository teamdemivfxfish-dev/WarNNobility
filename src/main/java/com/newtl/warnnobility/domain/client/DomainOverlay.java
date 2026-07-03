package com.newtl.warnnobility.domain.client;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.domain.DomainEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws the domain borders, the name labels, and the View toggles ON the Antique Atlas 4 screen, via
 * NeoForge screen events (no mixin).
 *
 * Antique Atlas 4 (folk.sisby.antique_atlas) is an architectury jar with intermediary mappings, so we
 * never reference its types at compile time: we recognise its screen by class name and call its
 * <b>public</b> coordinate helpers ({@code worldXToScreenX/worldZToScreenY}, {@code bookX/bookY},
 * {@code mapWidth/mapHeight}) reflectively. At runtime those return ordinary numbers and the
 * GuiGraphics is the real (Mojang-mapped) one, so all drawing stays normal.
 *
 * Border edges are precomputed and rebuilt only when the data or toggles change, so the screen does
 * not lag. Toggle clicks are consumed so the atlas's own input never fights them.
 */
@EventBusSubscriber(modid = WarNNobility.MODID, value = Dist.CLIENT)
public final class DomainOverlay {

    private DomainOverlay() {}

    private static final String ATLAS_SCREEN = "folk.sisby.antique_atlas.gui.AtlasScreen";
    private static final String ATLAS_RENDERER = "folk.sisby.antique_atlas.gui.AtlasRenderer";

    private static final String[] NAMES = {"Colonies", "Counties", "Duchies", "Kingdoms", "Factions"};
    // A per-tier legend swatch colour (the map key); the real borders use each lord's own colony colour.
    private static final int[] SWATCH = {0xFFE8DCC0, 0xFF80C880, 0xFF6FA8FF, 0xFFE6C24E, 0xFFE0705A};
    private static final int LEGEND_W = 98;   // legend card inner width
    private static final int ROW_H = 14;      // legend row height
    private static final int HEADER_H = 15;   // height reserved for the "Borders" title
    private static final int SECTION_H = 13;  // height reserved for a "Domain Types" / "Faction Claims" header

    // reflective handles into Antique Atlas 4, resolved once from the live screen
    private static boolean reflectTried = false;
    private static Method M_W2SX, M_W2SY, M_BOOKX, M_BOOKY, M_MAPW, M_MAPH, M_BOOKW;
    private static int mapBorderW = 11, mapBorderH = 11;   // AtlasRenderer.MAP_BORDER_* (fallbacks)

    // cached border edges {chunkX, chunkZ, side(0=N,1=S,2=W,3=E), rgb}, rebuilt only when needed
    private static final List<int[]> EDGES = new ArrayList<>();
    private static final List<int[]> FACTION_EDGES = new ArrayList<>();   // Easy Factions claim borders
    private static int builtVersion = -1;

    // The screen-space rect where Antique Atlas draws its hover tooltip this frame (null = not hovering the
    // map); our label text yields this zone so it never smears over AA's tooltip.
    private static int[] CURSOR_EXCL = null;

    private static boolean isAtlas(Screen s) {
        return s != null && s.getClass().getName().equals(ATLAS_SCREEN);
    }

    private static void initReflection(Screen atlas) {
        if (reflectTried) return;
        reflectTried = true;
        try {
            Class<?> c = atlas.getClass();
            M_W2SX = c.getMethod("worldXToScreenX", double.class);
            M_W2SY = c.getMethod("worldZToScreenY", double.class);
            M_BOOKX = c.getMethod("bookX");
            M_BOOKY = c.getMethod("bookY");
            M_MAPW = c.getMethod("mapWidth");
            M_MAPH = c.getMethod("mapHeight");
            M_BOOKW = c.getMethod("bookWidth");
            Class<?> rend = Class.forName(ATLAS_RENDERER);
            try { mapBorderW = rend.getField("MAP_BORDER_WIDTH").getInt(null); } catch (Exception ignored) {}
            try { mapBorderH = rend.getField("MAP_BORDER_HEIGHT").getInt(null); } catch (Exception ignored) {}
        } catch (Exception e) {
            M_W2SX = null;   // atlas API not as expected; overlay disables itself quietly
        }
    }

    private static double w2sx(Screen a, double worldX) {
        try { return (Double) M_W2SX.invoke(a, worldX); } catch (Exception e) { return Double.NaN; }
    }

    private static double w2sy(Screen a, double worldZ) {
        try { return (Double) M_W2SY.invoke(a, worldZ); } catch (Exception e) { return Double.NaN; }
    }

    private static int ri(Method m, Screen a) {
        try { return (Integer) m.invoke(a); } catch (Exception e) { return 0; }
    }

    /** Click a toggle button directly and consume it, so it always responds and the atlas never reacts. */
    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0 || !isAtlas(event.getScreen())) return;
        initReflection(event.getScreen());
        if (M_W2SX == null) return;
        Screen atlas = event.getScreen();
        int idx = legendAt(atlas, event.getMouseX(), event.getMouseY());
        if (idx >= 0) {
            ClientDomains.toggle(idx);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!isAtlas(event.getScreen())) return;
        Screen atlas = event.getScreen();
        initReflection(atlas);
        if (M_W2SX == null) return;
        GuiGraphics g = event.getGuiGraphics();

        // Antique Atlas renders the map with a raw RenderSystem scissor and, on hover, queues a tooltip
        // that leaves a TRANSLATED pose on the stack. Because we draw on Render.Post (after AA), that
        // residual transform would shift our whole overlay -- piling labels into a smear, or sliding the
        // borders clean out of their scissor box so the entire overlay vanishes when you hover your icon.
        // Draw under a forced-identity pose so we are immune to whatever transform AA left active.
        g.pose().pushPose();
        g.pose().last().pose().identity();
        g.pose().last().normal().identity();
        try {
            int bx = ri(M_BOOKX, atlas), by = ri(M_BOOKY, atlas);
            int mapW = ri(M_MAPW, atlas), mapH = ri(M_MAPH, atlas);

            Map<Long, int[]> data = ClientDomains.data();
            Map<Long, int[]> fdata = ClientDomains.factionData();
            List<DomainEngine.Label> towns = ClientDomains.colonyMarkers();
            // Town/colony markers follow the Colonies toggle -- a legend the map ignores is pointless.
            boolean coloniesOn = ClientDomains.isEnabled(ClientDomains.L_COLONIES);
            if (!data.isEmpty() || !fdata.isEmpty() || (!towns.isEmpty() && coloniesOn)) {
                rebuildIfNeeded();
                int sx0 = bx + mapBorderW, sy0 = by + mapBorderH;
                g.enableScissor(sx0, sy0, sx0 + mapW, sy0 + mapH);

                drawEdges(g, atlas, EDGES);
                drawEdges(g, atlas, FACTION_EDGES);

                // All map text goes through ONE de-collision pass so nameplates never pile into a smear.
                var font = Minecraft.getInstance().font;
                List<int[]> placed = new ArrayList<>();   // occupied nameplate rects {x0, y0, x1, y1}
                // Keep our label text out of the zone where AA draws its OWN hover tooltip (player /
                // landmark name) so the two never overlap; the marker dots/houses still draw.
                int mxp = (int) event.getMouseX(), myp = (int) event.getMouseY();
                CURSOR_EXCL = (mxp >= sx0 && mxp <= sx0 + mapW && myp >= sy0 && myp <= sy0 + mapH)
                        ? new int[]{mxp - 70, myp - 22, mxp + 160, myp + 36} : null;
                // colony markers (only when Colonies is on), then domain labels for the toggled-on tiers
                if (coloniesOn) for (DomainEngine.Label t : towns) drawTownMarker(g, atlas, font, t, placed);
                for (DomainEngine.Label l : ClientDomains.labels()) {
                    if (!ClientDomains.isEnabled(l.tier())) continue;  // 1=county,2=duchy,3=realm
                    drawLabel(g, atlas, font, l, placed);
                }
                // faction name labels (one per owner, centroid of its claims)
                if (ClientDomains.isEnabled(ClientDomains.L_FACTIONS)) {
                    for (DomainEngine.Label l : ClientDomains.factionLabels()) drawLabel(g, atlas, font, l, placed);
                }
                g.disableScissor();
            }

            drawLegend(g, atlas, (int) event.getMouseX(), (int) event.getMouseY());
        } finally {
            g.pose().popPose();
        }
    }

    private static void drawEdges(GuiGraphics g, Screen atlas, List<int[]> edges) {
        for (int[] e : edges) {
            int cx = e[0], cz = e[1], side = e[2], rgb = e[3];
            int x0 = (int) Math.round(w2sx(atlas, cx * 16)), x1 = (int) Math.round(w2sx(atlas, (cx + 1) * 16));
            int y0 = (int) Math.round(w2sy(atlas, cz * 16)), y1 = (int) Math.round(w2sy(atlas, (cz + 1) * 16));
            switch (side) {
                case 0 -> pencilH(g, x0, x1, y0, cx, cz, 0, rgb);
                case 1 -> pencilH(g, x0, x1, y1 - 1, cx, cz, 1, rgb);
                case 2 -> pencilV(g, y0, y1, x0, cx, cz, 2, rgb);
                default -> pencilV(g, y0, y1, x1 - 1, cx, cz, 3, rgb);
            }
        }
    }

    private static void drawLabel(GuiGraphics g, Screen atlas, net.minecraft.client.gui.Font font,
                                  DomainEngine.Label l, List<int[]> placed) {
        int lx = (int) Math.round(w2sx(atlas, l.blockX()));
        int ly = (int) Math.round(w2sy(atlas, l.blockZ()));
        // marker = the tier's heraldic glyph (keep / shield / crown), tinted the holder's colour, so the
        // map markers match the legend key. Faction labels (tier 4) get the claim square.
        drawTierMarker(g, lx, ly, l.tier(), l.rgb() & 0xFFFFFF);
        int tw = font.width(l.name());
        if (inCursorZone(lx - tw / 2 - 2, ly + 5, lx + tw / 2 + 2, ly + 16)) return;  // yield AA's tooltip
        int ty = placeText(placed, lx, tw, ly + 5);             // de-collided nameplate top
        g.fill(lx - tw / 2 - 2, ty, lx + tw / 2 + 2, ty + 11, 0x90000000);
        g.drawString(font, l.name(), lx - tw / 2, ty + 1, 0xFFFFFFFF, true);
    }

    /** A small house glyph (town) at a colony centre, tinted the colony colour, with its name beneath. */
    private static void drawTownMarker(GuiGraphics g, Screen atlas, net.minecraft.client.gui.Font font,
                                       DomainEngine.Label l, List<int[]> placed) {
        int x = (int) Math.round(w2sx(atlas, l.blockX()));
        int y = (int) Math.round(w2sy(atlas, l.blockZ()));
        int fill = (l.rgb() & 0xFFFFFF) | 0xFF000000;
        int line = 0xFF1A1208;
        // roof (stepped triangle)
        g.fill(x - 1, y - 4, x + 1, y - 3, line);
        g.fill(x - 2, y - 3, x + 2, y - 2, line);
        g.fill(x - 3, y - 2, x + 3, y - 1, line);
        // body (outline + colony-tinted fill)
        g.fill(x - 3, y - 1, x + 3, y + 4, line);
        g.fill(x - 2, y - 1, x + 2, y + 3, fill);
        // doorway
        g.fill(x - 1, y + 1, x + 1, y + 4, line);
        // name plate (de-collided so stacked towns/labels stay readable)
        String n = l.name();
        if (n != null && !n.isEmpty()) {
            int tw = font.width(n);
            if (inCursorZone(x - tw / 2 - 1, y + 5, x + tw / 2 + 1, y + 16)) return;  // yield AA's tooltip
            int ty = placeText(placed, x, tw, y + 5);
            g.fill(x - tw / 2 - 1, ty, x + tw / 2 + 1, ty + 11, 0x90000000);
            g.drawString(font, n, x - tw / 2, ty + 1, 0xFFEFE6CF, true);
        }
    }

    /** Whether a nameplate rect overlaps the cursor's hover-tooltip zone (so we should not draw its text). */
    private static boolean inCursorZone(int x0, int y0, int x1, int y1) {
        int[] z = CURSOR_EXCL;
        return z != null && x0 < z[2] && x1 > z[0] && y0 < z[3] && y1 > z[1];
    }

    /** Pick a nameplate top Y at or below {@code desiredY}, centred on {@code cx} for text width {@code tw},
     *  that does not overlap any already-placed nameplate; record it and return it. This stacks labels that
     *  would otherwise draw on top of each other (e.g. a domain label over its seat's town marker). */
    private static int placeText(List<int[]> placed, int cx, int tw, int desiredY) {
        int x0 = cx - tw / 2 - 2, x1 = cx + tw / 2 + 2;
        int h = 11, y = desiredY, guard = 0;
        boolean moved = true;
        while (moved && guard++ < 32) {
            moved = false;
            for (int[] r : placed) {
                if (x0 < r[2] && x1 > r[0] && y < r[3] && (y + h) > r[1]) {
                    y = r[3] + 1;   // drop just below the blocking plate
                    moved = true;
                    break;
                }
            }
        }
        placed.add(new int[]{x0, y, x1, y + h});
        return y;
    }

    /** The current toggled-on domain border edges, rebuilt as needed. Shared with the in-hand overlay. */
    public static List<int[]> borderEdges() { rebuildIfNeeded(); return EDGES; }

    /** The current Easy Factions claim border edges (only populated while the Factions layer is on). */
    public static List<int[]> factionBorderEdges() { rebuildIfNeeded(); return FACTION_EDGES; }

    private static void rebuildIfNeeded() {
        if (builtVersion == ClientDomains.version()) return;
        builtVersion = ClientDomains.version();
        // Nobility tiers, drawn by HIERARCHY: each chunk resolves to its highest enabled tier that actually
        // has a region (Kingdom > Duchy > County > Colony), and a border is drawn only where the EFFECTIVE
        // domain changes. So a county inside a kingdom melts into the kingdom border, a standalone county
        // still draws its own, and same-domain chunks never draw an internal line.
        Map<Long, int[]> data = ClientDomains.data();
        EDGES.clear();
        for (int[] e : data.values()) {
            int tier = topTier(e);
            if (tier < 0) continue;
            int rgb = ClientDomains.rgb(e, tier);
            if (rgb < 0) continue;
            rgb &= 0xFFFFFF;
            int cx = e[0], cz = e[1];
            long myKey = domainKey(e, tier);
            if (neighKey(data, cx, cz - 1) != myKey) EDGES.add(new int[]{cx, cz, 0, rgb});
            if (neighKey(data, cx, cz + 1) != myKey) EDGES.add(new int[]{cx, cz, 1, rgb});
            if (neighKey(data, cx - 1, cz) != myKey) EDGES.add(new int[]{cx, cz, 2, rgb});
            if (neighKey(data, cx + 1, cz) != myKey) EDGES.add(new int[]{cx, cz, 3, rgb});
        }

        // Easy Factions claims: their own chunk map, region = faction id at index 2, rgb at index 3.
        FACTION_EDGES.clear();
        if (ClientDomains.isEnabled(ClientDomains.L_FACTIONS)) {
            Map<Long, int[]> fd = ClientDomains.factionData();
            for (int[] e : fd.values()) {
                int region = e[2];
                if (region == 0) continue;
                int rgb = e[3] & 0xFFFFFF;
                int cx = e[0], cz = e[1];
                if (nFactionRegion(fd, cx, cz - 1) != region) FACTION_EDGES.add(new int[]{cx, cz, 0, rgb});
                if (nFactionRegion(fd, cx, cz + 1) != region) FACTION_EDGES.add(new int[]{cx, cz, 1, rgb});
                if (nFactionRegion(fd, cx - 1, cz) != region) FACTION_EDGES.add(new int[]{cx, cz, 2, rgb});
                if (nFactionRegion(fd, cx + 1, cz) != region) FACTION_EDGES.add(new int[]{cx, cz, 3, rgb});
            }
        }
    }

    private static int nFactionRegion(Map<Long, int[]> fd, int cx, int cz) {
        int[] e = fd.get(ChunkPos.asLong(cx, cz));
        return e == null ? 0 : e[2];
    }

    /** The top-Y of each legend row (indices 0..3 = the four domain tiers, 4 = Factions), accounting for
     *  the two section headers ("Domain Types" then "Faction Claims"). Shared by draw + hit-test. */
    private static int[] rowYs(int ly) {
        int[] r = new int[NAMES.length];
        int y = ly + HEADER_H + SECTION_H;            // past the "Borders" title + "Domain Types" header
        for (int i = 0; i < 4; i++) { r[i] = y; y += ROW_H; }
        y += SECTION_H;                               // the "Faction Claims" header
        r[4] = y;
        return r;
    }

    /** A parchment legend card pinned inside the top-left of the map. The four nobility tiers sit under a
     *  "Domain Types" heading, each with its OWN heraldic glyph (house / tower / shield / crown) so they
     *  read at a glance; the separate-system Factions layer sits under its own "Faction Claims" heading. */
    private static void drawLegend(GuiGraphics g, Screen atlas, int mx, int my) {
        int[] pos = legendPos(atlas);
        int lx = pos[0], ly = pos[1];
        int[] rowY = rowYs(ly);
        int cardH = rowY[4] + ROW_H + 4 - ly;
        var font = Minecraft.getInstance().font;

        // card: translucent dark parchment with the house double outline
        g.fill(lx - 5, ly - 4, lx + LEGEND_W + 5, ly + cardH, 0xE0140F0A);
        g.renderOutline(lx - 5, ly - 4, LEGEND_W + 10, cardH + 4, 0xFF120D08);
        g.renderOutline(lx - 4, ly - 3, LEGEND_W + 8, cardH + 2, 0xFF8A6A3C);
        g.drawString(font, "Borders", lx, ly, 0xFFE6C87A, false);

        // group headings
        drawSection(g, font, "Domain Types", lx, ly + HEADER_H);
        drawSection(g, font, "Faction Claims", lx, rowY[3] + ROW_H);

        for (int i = 0; i < NAMES.length; i++) {
            int ry = rowY[i];
            boolean on = ClientDomains.isEnabled(i);
            boolean hover = mx >= lx - 3 && mx < lx + LEGEND_W + 5 && my >= ry - 1 && my < ry + ROW_H - 1;
            if (on)         g.fill(lx - 3, ry - 1, lx + LEGEND_W + 5, ry + ROW_H - 1, 0x33FFFFFF);
            else if (hover) g.fill(lx - 3, ry - 1, lx + LEGEND_W + 5, ry + ROW_H - 1, 0x22FFFFFF);
            // key icon: a distinct glyph per domain tier, a plain square for the Factions claim layer
            drawSwatch(g, i, lx, ry, on);
            // checkbox glyph + tier name
            g.drawString(font, on ? "☑" : "☐", lx + 17, ry + 2, on ? 0xFFFFFFFF : 0xFF9A8C70, false);
            g.drawString(font, NAMES[i], lx + 29, ry + 2, on ? 0xFFFFFFFF : 0xFFB7A98C, false);
        }
    }

    /** A small group heading inside the legend, with a thin rule beneath it. */
    private static void drawSection(GuiGraphics g, net.minecraft.client.gui.Font font, String text, int lx, int y) {
        g.drawString(font, text, lx, y + 2, 0xFFC9A95E, false);
        g.fill(lx, y + 12, lx + LEGEND_W, y + 13, 0x55C9A95E);
    }

    /** The legend key tile for a row: a heraldic glyph for the four domain tiers, a plain colour square for
     *  the Factions layer. Dimmed when the layer is toggled off. */
    private static void drawSwatch(GuiGraphics g, int i, int lx, int ry, boolean on) {
        int sx = lx, sy = ry + 1;
        int fill = on ? SWATCH[i] : 0xFF564E42;
        g.fill(sx, sy, sx + 13, sy + 12, 0xFF000000);          // dark icon tile
        if (i == ClientDomains.L_FACTIONS) {
            g.fill(sx + 2, sy + 2, sx + 11, sy + 10, fill);    // a plain claim square (separate system)
            return;
        }
        switch (i) {
            case 0 -> colonyGlyph(g, sx, sy, fill);            // a homestead
            case 1 -> countyGlyph(g, sx, sy, fill);            // a battlemented keep
            case 2 -> duchyGlyph(g, sx, sy, fill);             // a shield
            default -> kingdomGlyph(g, sx, sy, fill);          // a crown
        }
    }

    private static void colonyGlyph(GuiGraphics g, int sx, int sy, int c) {
        g.fill(sx + 5, sy + 2, sx + 8, sy + 3, c);             // roof peak
        g.fill(sx + 4, sy + 3, sx + 9, sy + 4, c);
        g.fill(sx + 3, sy + 4, sx + 10, sy + 5, c);
        g.fill(sx + 4, sy + 5, sx + 9, sy + 10, c);            // body
        g.fill(sx + 6, sy + 7, sx + 7, sy + 10, 0xFF000000);  // doorway
    }

    private static void countyGlyph(GuiGraphics g, int sx, int sy, int c) {
        g.fill(sx + 3, sy + 2, sx + 4, sy + 4, c);             // merlons
        g.fill(sx + 6, sy + 2, sx + 7, sy + 4, c);
        g.fill(sx + 9, sy + 2, sx + 10, sy + 4, c);
        g.fill(sx + 3, sy + 4, sx + 10, sy + 10, c);           // tower body
        g.fill(sx + 6, sy + 7, sx + 7, sy + 10, 0xFF000000);  // gate
    }

    private static void duchyGlyph(GuiGraphics g, int sx, int sy, int c) {
        g.fill(sx + 3, sy + 2, sx + 10, sy + 6, c);            // shield head
        g.fill(sx + 4, sy + 6, sx + 9, sy + 8, c);
        g.fill(sx + 5, sy + 8, sx + 8, sy + 9, c);
        g.fill(sx + 6, sy + 9, sx + 7, sy + 10, c);            // point
    }

    private static void kingdomGlyph(GuiGraphics g, int sx, int sy, int c) {
        g.fill(sx + 3, sy + 4, sx + 4, sy + 8, c);             // left spike
        g.fill(sx + 6, sy + 3, sx + 7, sy + 8, c);             // centre spike (tallest)
        g.fill(sx + 9, sy + 4, sx + 10, sy + 8, c);            // right spike
        g.fill(sx + 4, sy + 6, sx + 6, sy + 8, c);             // valleys
        g.fill(sx + 7, sy + 6, sx + 9, sy + 8, c);
        g.fill(sx + 3, sy + 8, sx + 10, sy + 10, c);           // band
    }

    /** A domain label's map marker: the tier's heraldic glyph (county keep / duchy shield / kingdom crown,
     *  faction square) tinted the holder's colour, with a dark pixel outline so it reads on the parchment. */
    public static void drawTierMarker(GuiGraphics g, int cx, int cy, int tier, int rgb) {
        int sx = cx - 6, sy = cy - 7;
        int outline = 0xFF1A1208;
        glyphFor(g, tier, sx - 1, sy, outline);
        glyphFor(g, tier, sx + 1, sy, outline);
        glyphFor(g, tier, sx, sy - 1, outline);
        glyphFor(g, tier, sx, sy + 1, outline);
        glyphFor(g, tier, sx, sy, 0xFF000000 | rgb);
    }

    private static void glyphFor(GuiGraphics g, int tier, int sx, int sy, int c) {
        switch (tier) {
            case 2 -> duchyGlyph(g, sx, sy, c);                     // duchy = shield
            case 3 -> kingdomGlyph(g, sx, sy, c);                   // realm/kingdom = crown
            case 4 -> g.fill(sx + 2, sy + 2, sx + 11, sy + 10, c);  // faction = claim square
            default -> countyGlyph(g, sx, sy, c);                   // county (tier 1) = keep
        }
    }

    private static int legendAt(Screen atlas, double mx, double my) {
        int[] pos = legendPos(atlas);
        int lx = pos[0], ly = pos[1];
        int[] rowY = rowYs(ly);
        for (int i = 0; i < NAMES.length; i++) {
            int ry = rowY[i];
            if (mx >= lx - 3 && mx < lx + LEGEND_W + 5 && my >= ry - 1 && my < ry + ROW_H - 1) return i;
        }
        return -1;
    }

    /** Legend anchor in SCREEN-pixel space (matches GuiGraphics), NOT the atlas's scaled book coords.
     *  A fixed inset into the upper-left parchment corner: stable, on-screen, off the circular map. */
    private static int[] legendPos(Screen atlas) {
        return new int[]{ Math.max(8, atlas.width / 10), Math.max(8, atlas.height / 9) };
    }

    // ---- colored-pencil strokes ----------------------------------------------
    // A faint continuous base plus grainy darker dots with small jitter, varied opacity and the odd
    // gap, so the border reads like a hand-drawn pencil line. Seeded by chunk+offset = stable, no flicker.

    private static void pencilH(GuiGraphics g, int xs, int xe, int y, int cx, int cz, int side, int rgb) {
        if (xe <= xs) return;
        g.fill(xs, y - 1, xe, y + 2, (0x30 << 24) | rgb);   // ~3px soft base
        for (int i = 0; i < xe - xs; i++) {
            int h = hash(cx, cz, side, i);
            if ((h & 15) == 0) continue;                // ~1/16 grain gaps
            int jit = (h >> 3) % 3 - 1;                 // -1..1 perpendicular wobble
            int a = 160 + (h >> 5) % 96;                // 160..255 opacity
            int px = xs + i, py = y + jit, col = (a << 24) | rgb;
            g.fill(px, py - 1, px + 1, py + 2, col);    // 3px-tall stroke
            if (((h >> 12) & 3) == 3) g.fill(px, py + 2, px + 1, py + 3, col);   // occasional extra px
        }
    }

    private static void pencilV(GuiGraphics g, int ys, int ye, int x, int cx, int cz, int side, int rgb) {
        if (ye <= ys) return;
        g.fill(x - 1, ys, x + 2, ye, (0x30 << 24) | rgb);
        for (int i = 0; i < ye - ys; i++) {
            int h = hash(cx, cz, side, i);
            if ((h & 15) == 0) continue;
            int jit = (h >> 3) % 3 - 1;
            int a = 160 + (h >> 5) % 96;
            int px = x + jit, py = ys + i, col = (a << 24) | rgb;
            g.fill(px - 1, py, px + 2, py + 1, col);    // 3px-wide stroke
            if (((h >> 12) & 3) == 3) g.fill(px + 2, py, px + 3, py + 1, col);
        }
    }

    private static int hash(int a, int b, int c, int d) {
        int h = a * 374761393 + b * 668265263 + (c * 31 + d) * 0x85EBCA77;
        h = (h ^ (h >>> 15)) * 0x7FE7A56D;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    /** The highest enabled nobility tier (0..3) for which this chunk actually has a region, else -1. */
    private static int topTier(int[] e) {
        for (int level = 3; level >= 0; level--) {
            if (ClientDomains.isEnabled(level) && ClientDomains.region(e, level) != 0) return level;
        }
        return -1;
    }

    /** A key uniquely identifying a chunk's effective (tier, region) domain so same-domain chunks melt
     *  and a change in EITHER the tier or the region draws a border. */
    private static long domainKey(int[] e, int tier) {
        return ((long) (tier + 1) << 32) | (ClientDomains.region(e, tier) & 0xFFFFFFFFL);
    }

    /** The effective domain key of a neighbouring chunk (0 = no chunk / no enabled tier there). */
    private static long neighKey(Map<Long, int[]> data, int cx, int cz) {
        int[] e = data.get(ChunkPos.asLong(cx, cz));
        if (e == null) return 0L;
        int tier = topTier(e);
        return tier < 0 ? 0L : domainKey(e, tier);
    }
}
