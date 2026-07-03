package com.newtl.warnnobility.domain;

import com.newtl.warnnobility.domain.net.DomainNetwork;
import com.newtl.warnnobility.domain.net.FactionMapPayload;
import com.newtl.warnnobility.integration.FactionClaims;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Easy Factions claim engine for the Antique Atlas overlay. This is deliberately INDEPENDENT of
 * {@link DomainEngine}: it never names a MineColonies type, so faction claims are computed and pushed
 * whether or not MineColonies is installed, present, or healthy. (Previously faction claims rode inside
 * the MineColonies recompute, so a missing/throwing colony engine silently took the faction overlay down
 * with it. This build splits them so the Factions layer stands on its own -- gated only on Easy Factions.)
 *
 * <p>Every Easy Factions reference lives inside {@link FactionClaims} and runs only after the
 * {@code easy_factions} gate, so this class loads and no-ops cleanly when Easy Factions is absent.
 */
public final class FactionEngine {

    private FactionEngine() {}

    /**
     * Compute each online player's current dimension's faction claims and push them. One payload per
     * player (keyed to their dimension); an empty list is still sent so stale claims clear when a player
     * crosses into a dimension with no claims, or after claims are removed.
     */
    public static void recompute(MinecraftServer server) {
        if (server == null || !ModList.get().isLoaded("easy_factions")) return;

        // Compute once per distinct dimension that a player is actually in, then fan out to its players.
        Map<ResourceKey<Level>, FactionMapPayload> cache = new HashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ResourceKey<Level> dim = p.level().dimension();
            FactionMapPayload payload = cache.computeIfAbsent(dim, d -> {
                List<int[]> entries = FactionClaims.entriesForDim(server, d);
                List<DomainEngine.Label> labels = FactionClaims.labelsForDim(server, d);
                return new FactionMapPayload(d.location(), entries, labels);
            });
            DomainNetwork.toPlayer(p, payload);
        }
    }
}
