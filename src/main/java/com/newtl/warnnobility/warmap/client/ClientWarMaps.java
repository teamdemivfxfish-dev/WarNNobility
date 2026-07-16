package com.newtl.warnnobility.warmap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client-side scratch state for War Tables: the transient "point here" pulses. Everything persistent
 * (strokes, view, controller) is read straight off the synced anchor block entity by the renderer, so
 * the only thing that lives here is the short-lived pointer flash that is deliberately never stored.
 */
public final class ClientWarMaps {

    private ClientWarMaps() {}

    public static final long PING_LIFETIME_MS = 1600L;

    public static final class Pulse {
        public final BlockPos board;
        public final double x, z;
        public final int color;
        public final long born;
        Pulse(BlockPos board, double x, double z, int color, long born) {
            this.board = board; this.x = x; this.z = z; this.color = color; this.born = born;
        }
    }

    private static final List<Pulse> PULSES = new ArrayList<>();

    /** Anchors of active boards the renderer has seen, so the texture baker knows what to bake. */
    private static final java.util.Set<BlockPos> ACTIVE = new java.util.HashSet<>();
    /** When each board was last actually drawn (block-entity render), so the baker can skip boards the
     *  player has turned away from / walked past. The BER only runs for on-screen, in-range sections, so a
     *  fresh timestamp here == "on screen this frame"; a stale one == cull it and stop baking. */
    private static final java.util.Map<BlockPos, Long> LAST_SEEN = new java.util.HashMap<>();

    public static void markActive(BlockPos pos) {
        BlockPos k = pos.immutable();
        ACTIVE.add(k);
        LAST_SEEN.put(k, now());
    }

    /** Millis since this board was last rendered (Long.MAX_VALUE if never), for the baker's visibility cull. */
    public static long sinceSeen(BlockPos pos) {
        Long t = LAST_SEEN.get(pos);
        return t == null ? Long.MAX_VALUE : now() - t;
    }

    public static void forget(BlockPos pos) { ACTIVE.remove(pos); LAST_SEEN.remove(pos); }

    /** A board lost its atlas: forget it, drop its baked texture, and clear the block entity's state so the
     *  in-world renderer stops drawing the stale map this very frame. */
    public static void deactivate(BlockPos pos) {
        forget(pos);
        WarBoardTextures.remove(pos);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null
                && mc.level.getBlockEntity(pos) instanceof com.newtl.warnnobility.warmap.WarFrameBlockEntity be) {
            be.clientDeactivate();
        }
    }

    public static java.util.Set<BlockPos> activeBoards() { return ACTIVE; }

    public static void ping(BlockPos board, double x, double z, int color) {
        PULSES.add(new Pulse(board, x, z, color, now()));
    }

    /** Live pulses for {@code board}, oldest first; expired ones are pruned as a side effect. */
    public static List<Pulse> pulsesFor(BlockPos board) {
        long t = now();
        List<Pulse> out = new ArrayList<>();
        Iterator<Pulse> it = PULSES.iterator();
        while (it.hasNext()) {
            Pulse p = it.next();
            if (t - p.born > PING_LIFETIME_MS) { it.remove(); continue; }
            if (p.board.equals(board)) out.add(p);
        }
        return out;
    }

    private static long now() {
        return net.minecraft.Util.getMillis();
    }

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
