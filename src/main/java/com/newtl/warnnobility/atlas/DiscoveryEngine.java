package com.newtl.warnnobility.atlas;

import com.newtl.warnnobility.Config;
import com.newtl.warnnobility.atlas.net.AtlasNetwork;
import com.newtl.warnnobility.atlas.net.DiscoveryPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Drives the player-structure survey: scans the ground around players as they explore, folds what it finds
 * into {@link DiscoveryData}, reveals nearby discoveries (fog of war), and syncs each player their own known
 * set. All cross-mod-free and self-contained; gated by {@link Config#ATLAS_STRUCTURES_ENABLED}.
 */
public final class DiscoveryEngine {

    private DiscoveryEngine() {}

    public static boolean enabled() { return Config.ATLAS_STRUCTURES_ENABLED.get(); }

    /**
     * If the player's current chunk is due for a survey, scan the area and log any buildings. Returns true
     * if a scan actually ran (so the caller can cap surveys to one per cycle — a scan is the costly part).
     */
    public static boolean surveyAround(ServerPlayer p) {
        if (!enabled()) return false;
        ServerLevel level = p.serverLevel();
        DiscoveryData data = DiscoveryData.get(level);
        int cx = p.getBlockX() >> 4, cz = p.getBlockZ() >> 4;
        long cooldown = Math.max(1, Config.STRUCTURE_RESURVEY_MINUTES.get()) * 60L * 20L;
        if (!data.chunkNeedsSurvey(cx, cz, level.getGameTime(), cooldown)) return false;

        int radius = Config.STRUCTURE_SCAN_RADIUS.get();
        int minBlocks = Config.STRUCTURE_MIN_BLOCKS.get();
        List<StructureSurveyor.Building> found =
                StructureSurveyor.scan(level, p.getBlockX(), p.getBlockZ(), radius, minBlocks);
        for (StructureSurveyor.Building b : found) data.addOrMerge(b);

        // Mark every chunk the scan covered as surveyed, so we don't re-scan it until the cooldown lapses.
        int cr = (radius >> 4) + 1;
        long now = level.getGameTime();
        for (int x = cx - cr; x <= cx + cr; x++)
            for (int z = cz - cr; z <= cz + cr; z++)
                data.markChunkSurveyed(x, z, now);
        return true;
    }

    /** Reveal discoveries the player is now near and, if any were newly revealed, resync their atlas. */
    public static void revealAndSync(ServerPlayer p) {
        if (!enabled()) return;
        ServerLevel level = p.serverLevel();
        DiscoveryData data = DiscoveryData.get(level);
        int radius = Config.STRUCTURE_REVEAL_RADIUS.get();
        if (data.revealNear(p.getUUID(), p.getBlockX(), p.getBlockZ(), radius)) sendSnapshot(p, data);
    }

    /** Push the player their full known set for the dimension they are in (login / dimension change). */
    public static void sendSnapshot(ServerPlayer p) {
        if (!enabled()) return;
        sendSnapshot(p, DiscoveryData.get(p.serverLevel()));
    }

    private static void sendSnapshot(ServerPlayer p, DiscoveryData data) {
        AtlasNetwork.toPlayer(p, new DiscoveryPayload(
                p.level().dimension().location(), data.knownTo(p.getUUID())));
    }
}
