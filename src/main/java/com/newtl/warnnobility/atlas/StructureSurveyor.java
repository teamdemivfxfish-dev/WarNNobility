package com.newtl.warnnobility.atlas;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Scans a square area of the world and picks out built structures. It reads each column near the surface,
 * sorts <b>worked</b> blocks into {@link StructureArchetype.Mat material buckets}, notes "civilisation"
 * markers (crafting table, bed, furnace...), then flood-fills the built columns into connected components:
 * one component = one building. A component counts only if it is big enough AND shows a sign of habitation,
 * so a lone wall or a bare shell never trips it.
 *
 * <p><b>Only worked blocks count, and that is the whole trick.</b> An earlier cut of this bucketed anything
 * whose name contained "stone" and read 18 blocks down from the surface, so it tallied the natural ground
 * under every build: every land column came back "built", the flood fill merged whole landscapes, the
 * material tally was dominated by bedrock-to-surface stone, and the map filled with identical "Great Stone
 * Keep" pins sitting on empty forest. Raw stone, deepslate, granite, plain sandstone, terracotta and tree
 * logs are all things the world makes by itself, so none of them are evidence of a builder. Cobblestone,
 * stone bricks, planks, polished and cut variants, stairs and slabs are. Classifying on worked blocks alone
 * means wilderness scores a flat zero and there is nothing to filter out afterwards.
 */
public final class StructureSurveyor {

    private StructureSurveyor() {}

    private static final int SCAN_DEPTH = 12;   // blocks scanned down from the surface (foundations, cellars)
    private static final int SCAN_ABOVE = 6;    // and a little above, for tall roofs
    private static final int COLUMN_MIN = 1;    // worked blocks in a column for it to count as "built"
    private static final int MIN_CIV = 1;       // distinct habitation markers a build must show
    /** Footprints wider than this in either axis skip their mask and fall back to the outline box. */
    public static final int MAX_MASK_SPAN = 160;

    /** One detected building: its centre, footprint box + mask, size, material tally, and habitation. */
    public static final class Building {
        public final int cx, cz, minX, minZ, maxX, maxZ, size, civ;
        public final int[] mat;   // indexed by StructureArchetype.Mat.ordinal()
        /** Which columns in the box are built, indexed {@code (x-minX) * depth + (z-minZ)}; null if too big. */
        public final BitSet mask;

        Building(int cx, int cz, int minX, int minZ, int maxX, int maxZ, int size, int civ, int[] mat, BitSet mask) {
            this.cx = cx; this.cz = cz; this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ;
            this.size = size; this.civ = civ; this.mat = mat; this.mask = mask;
        }
    }

    /**
     * Survey the {@code radius}-block square around {@code (centerX,centerZ)} and return the buildings found
     * that meet {@code minBlocks}. Pure read of loaded blocks; call on the server thread with a bounded
     * radius (it reads ~ (2r+1)^2 * depth block states).
     */
    public static List<Building> scan(ServerLevel level, int centerX, int centerZ, int radius, int minBlocks) {
        int n = radius * 2 + 1;
        int ox = centerX - radius, oz = centerZ - radius;
        int[] colStruct = new int[n * n];
        int[] colCiv = new int[n * n];
        int[] colMat = new int[n * n * 8];
        boolean[] built = new boolean[n * n];

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int floor = level.getMinBuildHeight();
        for (int dx = 0; dx < n; dx++) {
            for (int dz = 0; dz < n; dz++) {
                int wx = ox + dx, wz = oz + dz;
                // Only read already-loaded terrain — never force-load a chunk from the tick thread. This also
                // means you naturally survey the area loaded around you, nothing beyond.
                if (!level.hasChunk(wx >> 4, wz >> 4)) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) + SCAN_ABOVE;
                int bottom = Math.max(floor, top - SCAN_ABOVE - SCAN_DEPTH);
                int i = dx * n + dz;
                int sCount = 0, civMask = 0;
                for (int y = top; y >= bottom; y--) {
                    BlockState s = level.getBlockState(p.set(wx, y, wz));
                    if (s.isAir()) continue;
                    StructureArchetype.Mat m = matBucket(s);
                    if (m != null) { colMat[i * 8 + m.ordinal()]++; sCount++; }
                    else if (extraStructural(s)) sCount++;
                    int cc = civCategory(s);
                    if (cc >= 0) civMask |= (1 << cc);
                }
                colStruct[i] = sCount;
                colCiv[i] = civMask;
                built[i] = sCount >= COLUMN_MIN;
            }
        }

        // Dilate the built mask by one so a doorway/window gap doesn't split one house into pieces.
        boolean[] dil = new boolean[n * n];
        for (int dx = 0; dx < n; dx++) for (int dz = 0; dz < n; dz++) {
            int i = dx * n + dz;
            if (built[i]) { dil[i] = true; continue; }
            for (int ax = -1; ax <= 1 && !dil[i]; ax++) for (int az = -1; az <= 1; az++) {
                int bx = dx + ax, bz = dz + az;
                if (bx < 0 || bz < 0 || bx >= n || bz >= n) continue;
                if (built[bx * n + bz]) { dil[i] = true; break; }
            }
        }

        List<Building> out = new ArrayList<>();
        boolean[] seen = new boolean[n * n];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int start = 0; start < n * n; start++) {
            if (!dil[start] || seen[start]) continue;
            // flood the connected component, aggregating only the truly-built cells inside it
            q.clear(); q.add(start); seen[start] = true;
            List<Integer> members = new ArrayList<>();
            long sumX = 0, sumZ = 0, weight = 0;
            int size = 0, civMask = 0, minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            int[] mat = new int[8];
            while (!q.isEmpty()) {
                int i = q.poll();
                int dx = i / n, dz = i % n;
                if (built[i]) {
                    int w = colStruct[i];
                    int wx = ox + dx, wz = oz + dz;
                    sumX += (long) wx * w; sumZ += (long) wz * w; weight += w;
                    size += w; civMask |= colCiv[i];
                    for (int b = 0; b < 8; b++) mat[b] += colMat[i * 8 + b];
                    if (wx < minX) minX = wx; if (wx > maxX) maxX = wx;
                    if (wz < minZ) minZ = wz; if (wz > maxZ) maxZ = wz;
                    members.add(i);
                }
                for (int ax = -1; ax <= 1; ax++) for (int az = -1; az <= 1; az++) {
                    int bx = dx + ax, bz = dz + az;
                    if (bx < 0 || bz < 0 || bx >= n || bz >= n) continue;
                    int j = bx * n + bz;
                    if (dil[j] && !seen[j]) { seen[j] = true; q.add(j); }
                }
            }
            if (weight <= 0 || size < minBlocks) continue;
            if (Integer.bitCount(civMask) < MIN_CIV) continue;
            out.add(new Building((int) (sumX / weight), (int) (sumZ / weight),
                    minX, minZ, maxX, maxZ, size, Integer.bitCount(civMask), mat,
                    maskOf(members, n, ox, oz, minX, minZ, maxX, maxZ)));
        }
        return out;
    }

    /** The component's built columns as a footprint mask over its box, or null if the box is too big to carry. */
    private static BitSet maskOf(List<Integer> members, int n, int ox, int oz,
                                 int minX, int minZ, int maxX, int maxZ) {
        int w = maxX - minX + 1, d = maxZ - minZ + 1;
        if (w > MAX_MASK_SPAN || d > MAX_MASK_SPAN) return null;
        BitSet mask = new BitSet(w * d);
        for (int i : members) {
            int wx = ox + i / n, wz = oz + i % n;
            mask.set((wx - minX) * d + (wz - minZ));
        }
        return mask;
    }

    // --- block classification -----------------------------------------------------------------------
    // Everything here answers ONE question: did a player put this block down? Registry-path substrings (so
    // modded building blocks come along for free) plus a few tags, with the world's own materials excluded.

    /** The material bucket a WORKED structural block belongs to, or null if the world could have made it. */
    static StructureArchetype.Mat matBucket(BlockState s) {
        String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();

        // Worked wood: planks and everything milled from them. Raw logs are excluded on purpose — a tree is
        // ~5 log blocks in a column and would otherwise make every forest read as a timber homestead.
        if (s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_SLABS)
                || s.is(BlockTags.WOODEN_FENCES) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WOODEN_DOORS)
                || s.is(BlockTags.WOODEN_TRAPDOORS) || has(path, "planks", "stripped_", "bamboo_mosaic"))
            return StructureArchetype.Mat.WOOD;

        // The specific materials are tested BEFORE the general stone rule below, because that rule matches
        // shaping prefixes ("cut_", "smooth_", "chiseled_", "_pillar") which would otherwise swallow
        // cut_copper, smooth_sandstone and quartz_pillar into STONE and mis-name the building.
        if (path.contains("sandstone")
                && has(path, "cut_", "chiseled_", "smooth_", "_stairs", "_slab", "_wall"))
            return StructureArchetype.Mat.SANDSTONE;   // plain sandstone is what a desert is made of
        if ((path.contains("quartz") && !path.contains("ore"))
                || has(path, "prismarine_brick", "dark_prismarine", "purpur_", "end_stone_brick"))
            return StructureArchetype.Mat.ORNATE;
        if (has(path, "iron_block", "iron_bars", "gold_block", "netherite_block", "chain", "anvil")
                || (path.contains("copper") && !has(path, "ore", "raw_")))
            return StructureArchetype.Mat.METAL;

        // Worked stone: only shaped forms. Plain stone/deepslate/andesite/granite/diorite/tuff/basalt/
        // blackstone are simply what the ground is made of.
        if (has(path, "cobblestone", "stone_brick", "deepslate_brick", "deepslate_tile", "tuff_brick",
                "polished_", "smooth_", "chiseled_", "cut_", "mossy_", "cracked_", "_pillar",
                "stone_stairs", "stone_slab", "stone_wall")
                && !path.contains("infested_"))
            return StructureArchetype.Mat.STONE;

        // Fired clay. Plain terracotta is excluded: badlands generate it by the cliff-full.
        if (path.contains("brick") || path.contains("glazed")) return StructureArchetype.Mat.BRICK;
        if (has(path, "farmland", "hay_block", "composter", "beehive")) return StructureArchetype.Mat.FARM;
        if (has(path, "chest", "barrel", "hopper", "rail", "shulker_box", "dropper", "dispenser"))
            return StructureArchetype.Mat.STORAGE;
        return null;
    }

    /** Worked but with no material bucket (windows, decoration) — still counts toward the build's mass. */
    private static boolean extraStructural(BlockState s) {
        String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return has(path, "glass", "wool", "concrete", "carpet", "pane", "lantern", "campfire", "ladder",
                "scaffolding", "bookshelf");
    }

    /** A distinct habitation-marker category (0..15), or -1 if the block is not one. Diversity of these
     *  is what separates a lived-in build from a bare shell. */
    private static int civCategory(BlockState s) {
        if (s.is(Blocks.CRAFTING_TABLE)) return 0;
        if (s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMOKER)) return 1;
        if (s.is(Blocks.CHEST) || s.is(Blocks.BARREL) || s.is(Blocks.TRAPPED_CHEST)) return 2;
        if (s.is(BlockTags.BEDS)) return 3;
        if (s.is(Blocks.BELL)) return 4;
        if (s.is(Blocks.LECTERN) || s.is(Blocks.BOOKSHELF) || s.is(Blocks.CHISELED_BOOKSHELF)) return 5;
        if (s.is(Blocks.ANVIL) || s.is(Blocks.CHIPPED_ANVIL) || s.is(Blocks.DAMAGED_ANVIL)) return 6;
        if (s.is(Blocks.BREWING_STAND) || s.is(Blocks.CAULDRON)) return 7;
        if (s.is(Blocks.ENCHANTING_TABLE)) return 8;
        if (s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)) return 9;
        if (s.is(Blocks.LOOM)) return 10;
        if (s.is(Blocks.SMITHING_TABLE)) return 11;
        if (s.is(Blocks.CARTOGRAPHY_TABLE)) return 12;
        if (s.is(Blocks.GRINDSTONE)) return 13;
        if (s.is(Blocks.STONECUTTER)) return 14;
        if (s.is(Blocks.COMPOSTER) || s.is(Blocks.FLETCHING_TABLE)) return 15;
        return -1;
    }

    private static boolean has(String path, String... keys) {
        for (String k : keys) if (path.contains(k)) return true;
        return false;
    }
}
