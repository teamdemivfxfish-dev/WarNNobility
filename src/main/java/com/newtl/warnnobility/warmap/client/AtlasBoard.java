package com.newtl.warnnobility.warmap.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.newtl.warnnobility.WarNNobility;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Drives Antique Atlas's own tile renderer onto an arbitrary surface. AA only renders the atlas in
 * GUI/world space through an {@code AtlasRenderer}; its {@code renderTiles(PoseStack, MultiBufferSource,
 * int)} default method paints the biome tiles using whatever the renderer's accessors report. So we
 * supply our OWN {@code AtlasRenderer} (a dynamic proxy) reporting the board's pan/zoom/size, and AA
 * paints the map into a {@link com.newtl.warnnobility.warmap.client.WarBoardRenderer}'s pose - no
 * framebuffer needed.
 *
 * <p>All of AA's types are reached reflectively so this NeoForge mod never compiles against its
 * Fabric/Yarn classes, mirroring {@link com.newtl.warnnobility.domain.client.HandheldDomainOverlay}.
 * Every call is guarded: if AA is absent or its API drifts, tile rendering simply no-ops and the board
 * falls back to a parchment backdrop while strokes and borders still draw via our own mapping.
 */
public final class AtlasBoard {

    private static final String ATLAS_RENDERER = "folk.sisby.antique_atlas.gui.AtlasRenderer";
    private static final String HANDHELD = "folk.sisby.antique_atlas.gui.HandheldAtlasRenderer";
    private static final String WORLD_DATA = "folk.sisby.antique_atlas.WorldAtlasData";

    private static Boolean available;
    private static Class<?> rendererItf;
    private static Method mRenderTiles;

    private final Object proxy;          // an AtlasRenderer
    private final Map<String, Object> vals;
    public final double centerX, centerZ, ppb;
    public final int bookX, bookY, mapW, mapH;
    private boolean ok = true;

    private AtlasBoard(Object proxy, Map<String, Object> vals,
                       double centerX, double centerZ, double ppb,
                       int bookX, int bookY, int mapW, int mapH) {
        this.proxy = proxy; this.vals = vals;
        this.centerX = centerX; this.centerZ = centerZ; this.ppb = ppb;
        this.bookX = bookX; this.bookY = bookY; this.mapW = mapW; this.mapH = mapH;
    }

    public static boolean available() {
        if (available == null) {
            available = ModList.get().isLoaded("antique_atlas");
            if (available) {
                try {
                    rendererItf = Class.forName(ATLAS_RENDERER);
                    mRenderTiles = rendererItf.getMethod("renderTiles",
                            PoseStack.class, MultiBufferSource.class, int.class);
                } catch (Throwable t) {
                    available = false;
                    WarNNobility.LOGGER.warn("[WarMap] Antique Atlas tile API not found; boards show a plain backdrop.", t);
                }
            }
        }
        return available;
    }

    /**
     * Build a renderer for one board view. {@code mapW/mapH} are the board's pixel size; {@code zoom}
     * scales the tiles; {@code centerX/centerZ} are the world coords shown at the board centre.
     */
    public static AtlasBoard create(Player viewer, ResourceKey<Level> dim,
                                    int mapW, int mapH, double centerX, double centerZ, int zoom) {
        if (!available()) return null;
        try {
            // Pull valid base values (tilePixels/tileChunks/mapScale/guiScale + the world data) from AA's
            // own factory, so we render at a scale AA actually has tiles for, then override view + size.
            Class<?> handheld = Class.forName(HANDHELD);
            Object template = handheld.getMethod("fromContext", Player.class).invoke(null, viewer);
            int baseTileChunks = (int) handheld.getMethod("tileChunks").invoke(template);
            int mapScale = (int) handheld.getMethod("mapScale").invoke(template);
            double guiScale = (double) handheld.getMethod("guiScale").invoke(template);

            Class<?> worldDataCls = Class.forName(WORLD_DATA);
            Object worldData = worldDataCls.getMethod("getOrCreate", ResourceKey.class).invoke(null, dim);

            // Show a fixed world span across the surface (both the board and the GUI show the SAME area),
            // zoomed in enough that AA's terrain reads as a map rather than faint specks. Crispness comes
            // from NEAREST filtering on the bake, not from the scale.
            double span = 128.0 * Math.pow(2.0, -zoom * 0.5);   // blocks across the surface (~8 chunks)
            double ppb = mapW / span;

            // Coarsening ladder for deep zoom-out: as the span grows, one AA tile at the base chunk-step
            // shrinks toward a single pixel (speck soup). AA's TileRenderIterator.setStep(tileChunks)
            // samples FEWER chunks into LARGER tiles, so we grow tileChunks in powers of two until each
            // rendered tile is at least ~10px wide again, capped so we never over-coarsen a close view.
            int tileChunks = baseTileChunks;
            final double TARGET_TILE_PX = 10.0;
            final int MAX_TILE_CHUNKS = 64;
            while (tileChunks < MAX_TILE_CHUNKS && ppb * tileChunks * 16.0 < TARGET_TILE_PX) {
                tileChunks <<= 1;
            }
            int tilePixels = Math.max(1, (int) Math.round(ppb * tileChunks * 16.0));

            Map<String, Object> vals = new HashMap<>();
            vals.put("bookX", 0);
            vals.put("bookY", 0);
            vals.put("bookWidth", mapW);
            vals.put("bookHeight", mapH);
            vals.put("mapWidth", mapW);
            vals.put("mapHeight", mapH);
            vals.put("tilePixels", tilePixels);
            vals.put("tileChunks", tileChunks);
            vals.put("mapScale", mapScale);
            vals.put("guiScale", guiScale);
            vals.put("getPixelsPerBlock", ppb);
            vals.put("mapOffsetX", 0.0);
            vals.put("mapOffsetY", 0.0);
            vals.put("player", viewer);
            vals.put("worldAtlasData", worldData);
            vals.put("dim", dim);

            InvocationHandler h = (proxy, method, args) -> dispatch(vals, proxy, method, args);
            Object proxy = Proxy.newProxyInstance(rendererItf.getClassLoader(),
                    new Class[]{rendererItf}, h);

            AtlasBoard board = new AtlasBoard(proxy, vals, centerX, centerZ, ppb, 0, 0, mapW, mapH);
            // Calibrate the pan so the chosen world centre lands at the board centre, using AA's own
            // worldXToScreenX so tiles and our strokes share one mapping (slope of mapOffset is +1).
            double s0x = board.sx(centerX), s0y = board.sy(centerZ);
            vals.put("mapOffsetX", (double) vals.get("mapOffsetX") + (mapW / 2.0 - s0x));
            vals.put("mapOffsetY", (double) vals.get("mapOffsetY") + (mapH / 2.0 - s0y));
            return board;
        } catch (Throwable t) {
            WarNNobility.LOGGER.warn("[WarMap] could not build the atlas board renderer (AA API drift).", t);
            return null;
        }
    }

    private static Object dispatch(Map<String, Object> vals, Object proxy, Method method, Object[] args)
            throws Throwable {
        String n = method.getName();
        if (vals.containsKey(n)) return vals.get(n);
        switch (n) {
            case "equals" -> { return proxy == (args != null ? args[0] : null); }
            case "hashCode" -> { return System.identityHashCode(proxy); }
            case "toString" -> { return "WarMapAtlasRenderer"; }
            default -> { }
        }
        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
        // Unknown abstract accessor we didn't seed: return a sane zero so AA never NPEs.
        Class<?> rt = method.getReturnType();
        if (rt == int.class) return 0;
        if (rt == double.class) return 0.0;
        if (rt == float.class) return 0f;
        if (rt == boolean.class) return false;
        return null;
    }

    /** Paint the atlas tiles for this view into {@code pose}/{@code buffers}. No-op on any failure. */
    public void renderTiles(PoseStack pose, MultiBufferSource buffers, int light) {
        if (!ok) return;
        try {
            mRenderTiles.invoke(proxy, pose, buffers, light);
        } catch (Throwable t) {
            ok = false; // draw nothing rather than spam; strokes/borders still render via sx/sy
        }
    }

    /** World X to board pixel X (uses AA's own mapping so tiles and marks line up). */
    public double sx(double worldX) {
        try {
            return (double) proxy.getClass()
                    .getMethod("worldXToScreenX", double.class).invoke(proxy, worldX);
        } catch (Throwable t) {
            return mapW / 2.0 + (worldX - centerX) * ppb;  // our own consistent fallback
        }
    }

    public double sy(double worldZ) {
        try {
            return (double) proxy.getClass()
                    .getMethod("worldZToScreenY", double.class).invoke(proxy, worldZ);
        } catch (Throwable t) {
            return mapH / 2.0 + (worldZ - centerZ) * ppb;
        }
    }

    /** Board pixel X back to world X (exact inverse of {@link #sx}, used for drawing input). */
    public double wx(double px) {
        return centerX + (px - mapW / 2.0) / ppb;
    }

    public double wz(double py) {
        return centerZ + (py - mapH / 2.0) / ppb;
    }
}
