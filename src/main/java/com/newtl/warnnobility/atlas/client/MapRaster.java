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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
 * <p><b>Baked in REGIONS of {@value #REGION_CHUNKS}x{@value #REGION_CHUNKS} chunks, not per chunk.</b> That
 * is a correctness matter, not a micro-optimisation. A zoomed-out atlas shows several thousand chunks; one
 * texture each meant thousands of cache entries and thousands of immediate-mode blits per frame, and once
 * the visible set outgrew the cache the LRU evicted the chunks it was about to need again, so the page
 * filled in halfway and stopped — the far half stayed bare no matter how long you looked at it. Regions cut
 * both counts by 64x, so the working set fits and the picture converges. Eviction is by distance from the
 * view rather than by recency, so drawing left-to-right can never evict the part it is about to reach.
 *
 * <p>Regions refresh on a TTL under a per-frame build budget, so a busy map never hitches; the fog of war is
 * Surveyor's, so we never reveal a chunk the player has not explored.
 */
@EventBusSubscriber(modid = WarNNobility.MODID, value = Dist.CLIENT)
public final class MapRaster {

    private MapRaster() {}

    /** Hand every cached texture back on the way out, so leaving a world does not leak them. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) forget();
    }

    /** Chunks per side in one baked region. 8 keeps the texture at a modest 128x128 while cutting the
     *  cache-entry and draw-call counts by 64x against baking per chunk. */
    private static final int REGION_CHUNKS = 8;
    private static final int REGION_BLOCKS = REGION_CHUNKS * 16;   // 128 px texture, 64KB of pixels

    /** Cached regions. 384 covers ~24k chunks (a ~2900-block-wide view) for about 24MB of pixels. */
    private static final int CACHE_MAX = 384;
    /** How long a region's picture may go stale before we redraw it (someone is building down there). */
    private static final long TTL_MS = 20_000L;
    /** Regions (re)built per draw, so filling a fresh view spreads over a few frames instead of hitching. */
    private static final int BUILD_BUDGET = 4;
    /** Hard cap on regions considered in one draw, guarding against a pathological projector. */
    private static final int MAX_REGIONS_PER_DRAW = 4096;

    private record Tile(ResourceLocation rl, DynamicTexture tex, long built) {}

    private static final Map<Long, Tile> CACHE = new HashMap<>();
    private static ResourceLocation cachedDim;
    private static int builtThisDraw;

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
        builtThisDraw = 0;
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

        int minRX = Math.floorDiv((int) Math.floor((clipX0 - origin[0]) / pxPerBlockX), REGION_BLOCKS);
        int maxRX = Math.floorDiv((int) Math.ceil((clipX1 - origin[0]) / pxPerBlockX), REGION_BLOCKS);
        int minRZ = Math.floorDiv((int) Math.floor((clipY0 - origin[1]) / pxPerBlockZ), REGION_BLOCKS);
        int maxRZ = Math.floorDiv((int) Math.ceil((clipY1 - origin[1]) / pxPerBlockZ), REGION_BLOCKS);
        long span = (long) (maxRX - minRX + 1) * (maxRZ - minRZ + 1);
        if (span <= 0 || span > MAX_REGIONS_PER_DRAW) return;

        // Build outward from the middle of the view, so what you are looking at resolves first and a fast
        // drag fills toward the edges rather than from an arbitrary corner.
        int midRX = (minRX + maxRX) >> 1, midRZ = (minRZ + maxRZ) >> 1;
        List<long[]> order = new ArrayList<>();
        for (int rx = minRX; rx <= maxRX; rx++) {
            for (int rz = minRZ; rz <= maxRZ; rz++) {
                long d2 = (long) (rx - midRX) * (rx - midRX) + (long) (rz - midRZ) * (rz - midRZ);
                order.add(new long[]{d2, rx, rz});
            }
        }
        order.sort((a, b) -> Long.compare(a[0], b[0]));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        for (long[] e : order) {
            int rx = (int) e[1], rz = (int) e[2];
            Tile t = region(level, rx, rz);
            if (t == null) continue;
            // Round the far edge from the projector too, so neighbouring regions share an exact seam
            // instead of leaving hairline gaps at fractional zoom.
            int x0 = (int) Math.round(origin[0] + (double) rx * REGION_BLOCKS * pxPerBlockX);
            int x1 = (int) Math.round(origin[0] + (double) (rx + 1) * REGION_BLOCKS * pxPerBlockX);
            int y0 = (int) Math.round(origin[1] + (double) rz * REGION_BLOCKS * pxPerBlockZ);
            int y1 = (int) Math.round(origin[1] + (double) (rz + 1) * REGION_BLOCKS * pxPerBlockZ);
            if (x1 <= x0 || y1 <= y0) continue;
            if (x1 < clipX0 || x0 > clipX1 || y1 < clipY0 || y0 > clipY1) continue;
            g.blit(t.rl(), x0, y0, x1 - x0, y1 - y0, 0f, 0f, REGION_BLOCKS, REGION_BLOCKS,
                    REGION_BLOCKS, REGION_BLOCKS);
        }
        RenderSystem.disableBlend();
        evictFarFrom(midRX, midRZ);
    }

    /** The cached picture of one region, building or refreshing it within this draw's budget. */
    private static Tile region(Level level, int rx, int rz) {
        long key = ChunkPos.asLong(rx, rz);
        Tile cached = CACHE.get(key);
        boolean stale = cached != null && System.currentTimeMillis() - cached.built() > TTL_MS;
        if (cached != null && !stale) return cached;
        if (builtThisDraw >= BUILD_BUDGET) return cached;   // keep last frame's picture until its turn

        builtThisDraw++;
        Tile fresh = bake(level, rx, rz, cached);
        if (fresh == null) {
            // Nothing surveyed anywhere in the region: drop any stale picture so fog re-closes over it.
            if (cached != null) { release(cached); CACHE.remove(key); }
            return null;
        }
        CACHE.put(key, fresh);
        return fresh;
    }

    /** Paint one region's picture, reusing the existing texture object when we are just refreshing it. */
    private static Tile bake(Level level, int rx, int rz, Tile reuse) {
        DynamicTexture tex = reuse != null ? reuse.tex() : new DynamicTexture(REGION_BLOCKS, REGION_BLOCKS, false);
        NativeImage img = tex.getPixels();
        if (img == null) return null;

        int alpha = Config.ATLAS_TERRAIN_ALPHA.get();
        float trueColour = Config.ATLAS_TERRAIN_SATURATION.get() / 100f;
        boolean any = false;
        for (int lcx = 0; lcx < REGION_CHUNKS; lcx++) {
            for (int lcz = 0; lcz < REGION_CHUNKS; lcz++) {
                int cx = rx * REGION_CHUNKS + lcx, cz = rz * REGION_CHUNKS + lcz;
                SurveyorTerrain.Surface s = SurveyorTerrain.surface(level, new ChunkPos(cx, cz));
                if (s == null) { clearChunk(img, lcx, lcz); continue; }
                any = true;
                // The chunk to the north supplies the heights that shade this chunk's top row, so relief
                // runs continuously across seams instead of drawing a flat line every 16 blocks.
                SurveyorTerrain.Surface north = SurveyorTerrain.surface(level, new ChunkPos(cx, cz - 1));
                paintChunk(img, lcx, lcz, s, north, alpha, trueColour);
            }
        }
        if (!any) {
            if (reuse == null) tex.close();
            return null;
        }
        tex.upload();
        if (reuse != null) return new Tile(reuse.rl(), tex, System.currentTimeMillis());

        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                WarNNobility.MODID, "raster/" + coord(rx) + "_" + coord(rz));
        Minecraft.getInstance().getTextureManager().register(rl, tex);
        return new Tile(rl, tex, System.currentTimeMillis());
    }

    private static void paintChunk(NativeImage img, int lcx, int lcz, SurveyorTerrain.Surface s,
                                   SurveyorTerrain.Surface north, int alpha, float trueColour) {
        int ox = lcx * 16, oz = lcz * 16;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int i = SurveyorTerrain.index(x, z);
                if (!s.has(i)) { img.setPixelRGBA(ox + x, oz + z, 0); continue; }
                Block block = s.block()[i];
                MapColor mc = block.defaultMapColor();
                if (mc == null || mc == MapColor.NONE || mc.col == 0) {
                    img.setPixelRGBA(ox + x, oz + z, 0);
                    continue;
                }
                int height = SurveyorTerrain.heightOf(s.depth()[i]);
                int rgb = mc.calculateRGBColor(shade(s, north, x, z, height));
                if (s.water()[i] > 0) rgb = drown(rgb, s.water()[i]);
                img.setPixelRGBA(ox + x, oz + z, abgr(parchment(rgb, trueColour), alpha));
            }
        }
    }

    private static void clearChunk(NativeImage img, int lcx, int lcz) {
        int ox = lcx * 16, oz = lcz * 16;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) img.setPixelRGBA(ox + x, oz + z, 0);
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

    /** Texture paths may not contain '-', so negative region coords are spelled with an 'n'. */
    private static String coord(int v) { return v < 0 ? "n" + (-v) : Integer.toString(v); }

    /**
     * Trim the cache down by throwing away whatever is FURTHEST from the view centre. Recency ordering was
     * the trap here: a draw walks the view in a fixed order, so an LRU always evicts the regions it is about
     * to revisit next frame, and an over-large view never converges. Distance never lies about what is worth
     * keeping.
     */
    private static void evictFarFrom(int midRX, int midRZ) {
        if (CACHE.size() <= CACHE_MAX) return;
        List<Map.Entry<Long, Tile>> all = new ArrayList<>(CACHE.entrySet());
        all.sort((a, b) -> Long.compare(dist2(b.getKey(), midRX, midRZ), dist2(a.getKey(), midRX, midRZ)));
        for (int i = 0; i < all.size() && CACHE.size() > CACHE_MAX; i++) {
            release(all.get(i).getValue());
            CACHE.remove(all.get(i).getKey());
        }
    }

    private static long dist2(long key, int midRX, int midRZ) {
        int rx = (int) (key & 0xFFFFFFFFL), rz = (int) (key >>> 32);
        return (long) (rx - midRX) * (rx - midRX) + (long) (rz - midRZ) * (rz - midRZ);
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
