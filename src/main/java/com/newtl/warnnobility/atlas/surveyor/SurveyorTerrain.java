package com.newtl.warnnobility.atlas.surveyor;

import net.minecraft.core.IdMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.lang.reflect.Method;
import java.util.BitSet;

/**
 * Fail-soft reflection bridge into <b>Surveyor</b>, the map-data backend that Antique Atlas 4 is built on.
 *
 * <p>Why this exists: Antique Atlas draws one stylised texture per tile chosen from the <i>biome</i>, so a
 * chunk holding a keep and a chunk of bare forest render identically and the map reads as an empty field.
 * Surveyor, underneath it, already stores what every surveyed column is actually MADE of. That is the same
 * information a vanilla map renders from, it is already persisted, already fog-of-warred per player, and
 * already synced to the client. So the raster layer needs no scan, no storage and no packets of its own: it
 * just reads what the atlas was standing on the whole time.
 *
 * <p>Surveyor is a Fabric mod loaded through Sinytra Connector, which writes a Mojang-mapped copy of it to
 * {@code mods/.connector/}. So at runtime its signatures are ordinary Mojang names ({@code of(Level)},
 * {@code get(ChunkPos)}) and the objects it hands back are real Mojang types; the intermediary names in the
 * shipped jar never appear. We still reference none of it at compile time, and every call is fail-soft: if
 * Surveyor is absent or ever changes shape, {@link #surface} simply returns null and the map falls back to
 * bare Antique Atlas tiles.
 */
public final class SurveyorTerrain {

    private SurveyorTerrain() {}

    private static final String C_WORLD_TERRAIN = "folk.sisby.surveyor.terrain.WorldTerrain";
    private static final String C_CHUNK_SUMMARY = "folk.sisby.surveyor.terrain.ChunkSummary";
    private static final String C_RAW = "folk.sisby.surveyor.terrain.LayerSummary$Raw";

    /**
     * The depth datum we hand {@code toSingleLayer}, mirroring Antique Atlas's own overworld call.
     *
     * <p>This constant is load-bearing, not arbitrary. Surveyor merges its per-layer summaries with
     * {@code depths[i] = inLayerDepth + (datum - layerKey)}, and a column's real height is
     * {@code layerKey - inLayerDepth}, so the array it returns is exactly {@code datum - Y}. Passing the
     * same datum AA passes therefore makes {@link #heightOf} an exact inverse rather than a guess, and
     * keeps us on the identical code path the atlas itself is exercising every frame.
     */
    private static final int DEPTH_DATUM = 999;

    /** Consecutive failed calls before we stop asking; a transient miss should not kill the layer forever. */
    private static final int FAIL_LIMIT = 20;

    private static boolean tried, broken;
    private static int fails;
    private static Method M_OF, M_GET, M_PALETTE, M_SINGLE_LAYER, M_EXISTS, M_BLOCKS, M_DEPTHS, M_WATER;

    /**
     * Surveyor packs a chunk's 256 columns in <b>x*16+z</b> order. This is the TRANSPOSE of vanilla's
     * familiar {@code z*16+x}, confirmed from ChunkSummary's own build loop (its two counters feed
     * {@code SectionSummary.getBlockState(x, y, z)} and index the 256-arrays as {@code x*16 + z}). Do not
     * "correct" this to the vanilla convention: doing so silently transposes every chunk on the map.
     */
    public static int index(int x, int z) { return (x & 15) * 16 + (z & 15); }

    /** A column's real world height, decoded from Surveyor's packed depth. See {@link #DEPTH_DATUM}. */
    public static int heightOf(int depth) { return DEPTH_DATUM - depth; }

    /**
     * One chunk's surveyed surface: which columns are known, the block forming each one's floor, its packed
     * depth (decode with {@link #heightOf}), and how deep any water above it is (0 = dry land).
     */
    public record Surface(BitSet known, Block[] block, int[] depth, int[] water) {
        public boolean has(int i) { return known.get(i) && block[i] != null; }
    }

    private static synchronized void init() {
        if (tried) return;
        tried = true;
        try {
            Class<?> worldTerrain = Class.forName(C_WORLD_TERRAIN);
            M_OF = worldTerrain.getMethod("of", Level.class);
            M_GET = worldTerrain.getMethod("get", ChunkPos.class);
            M_PALETTE = worldTerrain.getMethod("getBlockPalette", ChunkPos.class);
            M_SINGLE_LAYER = Class.forName(C_CHUNK_SUMMARY)
                    .getMethod("toSingleLayer", Integer.class, Integer.class, int.class);
            Class<?> raw = Class.forName(C_RAW);
            M_EXISTS = raw.getMethod("exists");
            M_BLOCKS = raw.getMethod("blocks");
            M_DEPTHS = raw.getMethod("depths");
            M_WATER = raw.getMethod("waterDepths");
        } catch (Throwable t) {
            broken = true;   // Surveyor absent or reshaped: the raster layer just never draws
        }
    }

    /** Whether the terrain layer can run at all (Surveyor present and shaped as expected). */
    public static boolean available() { init(); return !broken; }

    /**
     * The surveyed surface of one chunk, or null if Surveyor has nothing for it (never explored, outside the
     * player's fog of war, wrong dimension) or the bridge is unavailable. Safe to call every frame: this is a
     * read of already-resolved data, not a scan.
     */
    @SuppressWarnings("unchecked")
    public static Surface surface(Level level, ChunkPos cp) {
        init();
        if (broken || level == null) return null;
        try {
            Object terrain = M_OF.invoke(null, level);
            if (terrain == null) return null;
            Object summary = M_GET.invoke(terrain, cp);
            if (summary == null) return null;   // chunk not surveyed: stays fogged, nothing to draw
            Object raw = M_SINGLE_LAYER.invoke(summary, null, null, DEPTH_DATUM);
            if (raw == null) return null;
            if (!(M_PALETTE.invoke(terrain, cp) instanceof IdMap<?> palette)) return null;

            BitSet known = (BitSet) M_EXISTS.invoke(raw);
            int[] blockIds = (int[]) M_BLOCKS.invoke(raw);
            int[] depth = (int[]) M_DEPTHS.invoke(raw);
            int[] water = (int[]) M_WATER.invoke(raw);
            if (known == null || blockIds == null || depth == null || water == null) return null;

            Block[] block = new Block[256];
            for (int i = 0; i < 256; i++) {
                if (!known.get(i)) continue;
                Object b = ((IdMap<Object>) palette).byId(blockIds[i]);
                if (b instanceof Block bl) block[i] = bl;
            }
            fails = 0;
            return new Surface(known, block, depth, water);
        } catch (Throwable t) {
            if (++fails >= FAIL_LIMIT) broken = true;
            return null;
        }
    }
}
