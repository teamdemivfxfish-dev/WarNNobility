package com.newtl.warnnobility.integration;

import com.newtl.warnnobility.domain.DomainEngine;
import com.newtl.warnnobility.integration.easyfactions.EasyFactionsClaims;
import com.newtl.warnnobility.integration.holdfast.HoldfastClaims;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * Claimed chunks for the Domain Atlas "Factions" overlay, from Holdfast Factions or Easy Factions
 * (Holdfast wins if both are installed). Empty when neither is installed. See {@link FactionGate}.
 */
public final class FactionClaims {

    private FactionClaims() {}

    /** Per-chunk claims for one dimension: each {@code [chunkX, chunkZ, ownerRegionId, rgb]}. */
    public static List<int[]> entriesForDim(MinecraftServer server, ResourceKey<Level> dim) {
        if (server == null) return new ArrayList<>();
        if (ModList.get().isLoaded(FactionGate.HOLDFAST)) return HoldfastClaims.entriesForDim(server, dim);
        if (ModList.get().isLoaded(FactionGate.EASY_FACTIONS)) return EasyFactionsClaims.entriesForDim(server, dim);
        return new ArrayList<>();
    }

    /** One centroid name label per owner holding claims in this dimension (Label tier 4 = factions layer). */
    public static List<DomainEngine.Label> labelsForDim(MinecraftServer server, ResourceKey<Level> dim) {
        if (server == null) return new ArrayList<>();
        if (ModList.get().isLoaded(FactionGate.HOLDFAST)) return HoldfastClaims.labelsForDim(server, dim);
        if (ModList.get().isLoaded(FactionGate.EASY_FACTIONS)) return EasyFactionsClaims.labelsForDim(server, dim);
        return new ArrayList<>();
    }
}
