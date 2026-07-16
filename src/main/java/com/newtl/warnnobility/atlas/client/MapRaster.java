package com.newtl.warnnobility.atlas.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.newtl.warnnobility.Config;
import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.atlas.surveyor.SurveyorTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The terrain layer: a real, per-block picture of the surveyed world, graded onto the atlas parchment.
 *
 * <p>This is what makes the map legible. Antique Atlas picks one stylised texture per tile from the biome,
 * so builds, roads and farms are invisible on it. Here we read what each column is actually made of (see
 * {@link SurveyorTerrain}), take that block's vanilla {@link MapColor} with vanilla's own north-facing
 * relief shading, and wash it toward parchment ink. The result reads like a filled-in cartographer's survey
 * rather than a blank field, and because the colours are the same ones a vanilla map uses, a cobble keep
 * reads grey against grass exactly as it does on the map in your hand.
 *
 * <p>Drawn as one 16x16 texture per chunk, cached and blitted, so a screenful is a few hundred draw calls
 * instead of tens of thousands of rectangles. Chunks refresh on a TTL under a per-frame budget, so a busy
 * map never hitches; the fog of war is Surveyor's, so we never reveal a chunk the player has not explored.
 */
@EventBusSubscriber(modid = WarNNobility.MODID, value = Dist.CLIENT)
public final class MapRaster {

    private MapRaster() {}

    /** Hand every cached chunk texture back on the way out, so leaving a world does not leak them. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) forget();
    }

    /** Cached chunk textures. Generous: a wide atlas view is ~1-2k chunks, each costing 1KB of pixels. */
    private static final int CACHE_MAX = 2048;
    /** How long a chunk's picture may go stale before we redraw it (someone is building down there). */
    private static final long TTL_MS = 8_000L;
    /** Chunk textures (re)built per frame, so filling a fresh view spreads over a few frames. */
    private static final int BUILD_BUDGET = 24;
    /** Hard cap on chunks considered in one draw, guarding against a pathological projector. */
    private static final int MAX_CHUNKS_PER_DRAW = 8192;

    private record Tile(ResourceLocation rl, DynamicTexture tex, long built) {}

    private static final Map<Long, Tile> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Tile> eldest) {
            if (size() <= CACHE_MAX) return false;
            release(eldest.getValue());
            return true;
        }
    };
    private static ResourceLocation cachedDim;
    private static int builtThisFrame;

    /** Whether the layer can draw at all (Surveyor present). Lets callers hide a toggle that would do nothing. */
    public static boolean available() { return SurveyorTerrain.available(); }

    /**
     * Draw the terrain raster for everything visible in the clip box. {@code proj} must be the same
     * world-to-screen mapping the caller's other map layers use, so borders and markers line up exactly.
     */
    public static void draw(GuiGraphics g, Level level, MapProjector proj,
                            int clipX0, int clipY0, int clipX1, int clipY1) {
        if (level == null || !Config.ATLAS_TERRAIN_ENABLED.get() || !SurveyorTerrain.available()) return;
        forgetIfDimensionChanged(level);
        builtThisFrame = 0;
        // Blits draw straight to the GPU while fills batch into the buffer source, so anything still queued
        // has to go down BEFORE us or it would flush later and land on top of the terrain we are about to
        // paint. Push it out first; every layer drawn after us then correctly sits above the ground.
        g.flush();

        // Invert the projector by sampling it: both surfaces (the atlas book and the War Frame board) map
        // world to screen linearly and unrotated, so two probes give the scale and origin exactly.
        double[] origin = proj.project(0, 0);
        double[] alongX = proj.project(64, 0);
        double[] alongZ = proj.project(0, 64);
        if (origin == null || alongX == null || alongZ == null) return;
        double pxPerBlockX = (alongX[0] - origin[0]) / 64.0;
        double pxPerBlockZ = (alongZ[1] - origin[1]) / 64.0;
        if (!(pxPerBlockX > 1e-6) || !(pxPerBlockZ > 1e-6)) return;   // degenerate or rotated: skip quietly

        int minCX = Math.floorDiv((int) Math.floor((clipX0 - origin[0]) / pxPerBlockX), 16);
        int maxCX = Math.floorDiv((int) Math.ceil((clipX1 - origin[0]) / pxPerBlockX), 16);
        int minCZ = Math.floorDiv((int) Math.floor((clipY0 - origin[1]) / pxPerBlockZ), 16);
        int maxCZ = Math.floorDiv((int) Math.ceil((clipY1 - origin[1]) / pxPerBlockZ), 16);
        long span = (long) (maxCX - minCX + 1) * (maxCZ - minCZ + 1);
        if (span <= 0 || span > MAX_CHUNKS_PER_DRAW) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Tile t = tile(level, cx, cz);
                if (t == null) continue;
                // Round the far edge from the projector too, so neighbouring chunks share an exact seam
                // instead of leaving hairline gaps at fractional zoom.
                int x0 = (int) Math.round(origin[0] + cx * 16 * pxPerBlockX);
                int x1 = (int) Math.round(origin[0] + (cx + 1) * 16 * pxPerBlockX);
                int y0 = (int) Math.round(origin[1] + cz * 16 * pxPerBlockZ);
                int y1 = (int) Math.round(origin[1] + (cz + 1) * 16 * pxPerBlockZ);
                if (x1 <= x0 || y1 <= y0) continue;
                if (x1 < clipX0 || x0 > clipX1 || y1 < clipY0 || y0 > clipY1) continue;
                g.blit(t.rl(), x0, y0, x1 - x0, y1 - y0, 0f, 0f, 16, 16, 16, 16);
            }
        }
        RenderSystem.disableBlend();
    }

    /** The cached picture of one chunk, building or refreshing it within this frame's budget. */
    private static Tile tile(Level level, int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        Tile cached = CACHE.get(key);
        long now = System.currentTimeMillis();
        boolean stale = cached != null && now - cached.built() > TTL_MS;
        if (cached != null && !stale) return cached;
        if (builtThisFrame >= BUILD_BUDGET) return cached;   // keep last frame's picture until its turn

        SurveyorTerrain.Surface surface = SurveyorTerrain.surface(level, new ChunkPos(cx, cz));
        if (surface == null) {
            // Unexplored (or unreadable): drop any stale picture so fog re-closes over it.
            if (cached != null) { release(cached); CACHE.remove(key); }
            return null;
        }
        builtThisFrame++;
        // The chunk to the north supplies the heights that shade this chunk's top row, so relief runs
        // continuously across chunk seams instead of drawing a flat line every 16 blocks.
        SurveyorTerrain.Surface north = SurveyorTerrain.surface(level, new ChunkPos(cx, cz - 1));
        Tile fresh = bake(level, cx, cz, surface, north, cached);
        if (fresh != null) CACHE.put(key, fresh);
        return fresh;
    }

    /** Paint one chunk's 16x16 picture, reusing the existing texture object when we are just refreshing it. */
    private static Tile bake(Level level, int cx, int cz, SurveyorTerrain.Surface s,
                             SurveyorTerrain.Surface north, Tile reuse) {
        DynamicTexture tex = reuse != null ? reuse.tex() : new DynamicTexture(16, 16, false);
        NativeImage img = tex.getPixels();
        if (img == null) return null;

        int alpha = Config.ATLAS_TERRAIN_ALPHA.get();
        float trueColour = Config.ATLAS_TERRAIN_SATURATION.get() / 100f;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int i = SurveyorTerrain.index(x, z);
                if (!s.has(i)) { img.setPixelRGBA(x, z, 0); continue; }
                Block block = s.block()[i];
                MapColor mc = block.defaultMapColor();
                if (mc == null || mc == MapColor.NONE || mc.col == 0) { img.setPixelRGBA(x, z, 0); continue; }

                int height = SurveyorTerrain.heightOf(s.depth()[i]);
                int rgb = mc.calculateRGBColor(shade(s, north, x, z, height));
                if (s.water()[i] > 0) rgb = drown(rgb, s.water()[i]);
                img.setPixelRGBA(x, z, abgr(parchment(rgb, trueColour), alpha));
            }
        }
        tex.upload();
        if (reuse != null) return new Tile(reuse.rl(), tex, System.currentTimeMillis());

        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                WarNNobility.MODID, "raster/" + (cx < 0 ? "n" + (-cx) : "" + cx) + "_" + (cz < 0 ? "n" + (-cz) : "" + cz));
        Minecraft.getInstance().getTextureManager().register(rl, tex);
        return new Tile(rl, tex, System.currentTimeMillis());
    }

    /**
     * Vanilla's relief trick: compare a column against its northern neighbour and light it accordingly, so
     * slopes and walls cast the same readable step-shading a real map has. The neighbour comes from the
     * chunk to the north when we are on the top row.
     */
    private static MapColor.Brightness shade(SurveyorTerrain.Surface s, SurveyorTerrain.Surface north,
                                             int x, int z, int height) {
        int ni = SurveyorTerrain.index(x, z == 0 ? 15 : z - 1);
        SurveyorTerrain.Surface src = z == 0 ? north : s;
        if (src == null || !src.has(ni)) return MapColor.Brightness.NORMAL;
        int nh = SurveyorTerrain.heightOf(src.depth()[ni]);
        if (height > nh) return MapColor.Brightness.HIGH;
        if (height < nh) return MapColor.Brightness.LOW;
        return MapColor.Brightness.NORMAL;
    }

    /** Sink a submerged colour toward ink-blue, deeper water reading darker, as a chart would show it. */
    private static int drown(int rgb, int depth) {
        float t = Math.min(1f, 0.35f + depth * 0.06f);
        int r = (int) (((rgb >> 16) & 255) * (1 - t) + 0x2E * t);
        int g = (int) (((rgb >> 8) & 255) * (1 - t) + 0x5A * t);
        int b = (int) ((rgb & 255) * (1 - t) + 0x8C * t);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Grade a true map colour onto the atlas: mix it with a warm ink ramp keyed off its own brightness, so
     * the page still reads as aged parchment while wood, stone and water stay tellable apart.
     * {@code trueColour} is how much of the real hue survives (0 = pure sepia plate, 1 = a vanilla map).
     */
    private static int parchment(int rgb, float trueColour) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float lum = 0.299f * r + 0.587f * g + 0.114f * b;
        float ir = 0.24f + (0.91f - 0.24f) * lum;   // deep umber to parchment highlight
        float ig = 0.17f + (0.83f - 0.17f) * lum;
        float ib = 0.10f + (0.65f - 0.10f) * lum;
        int fr = clamp255((ir * (1 - trueColour) + r * trueColour) * 255f);
        int fg = clamp255((ig * (1 - trueColour) + g * trueColour) * 255f);
        int fb = clamp255((ib * (1 - trueColour) + b * trueColour) * 255f);
        return (fr << 16) | (fg << 8) | fb;
    }

    private static int clamp255(float v) { return v <= 0 ? 0 : v >= 255 ? 255 : (int) v; }

    /** NativeImage stores pixels little-endian, so an ARGB colour goes in as 0xAABBGGRR. */
    private static int abgr(int rgb, int alpha) {
        return ((alpha & 255) << 24) | ((rgb & 255) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 255);
    }

    private static void forgetIfDimensionChanged(Level level) {
        ResourceLocation dim = level.dimension().location();
        if (dim.equals(cachedDim)) return;
        cachedDim = dim;
        forget();
    }

    /** Drop every cached picture and its GPU texture (dimension change, disconnect, or a settings change). */
    public static void forget() {
        for (Iterator<Tile> it = CACHE.values().iterator(); it.hasNext(); ) { release(it.next()); it.remove(); }
    }

    private static void release(Tile t) {
        try { Minecraft.getInstance().getTextureManager().release(t.rl()); } catch (Throwable ignored) {}
        try { t.tex().close(); } catch (Throwable ignored) {}
    }
}
