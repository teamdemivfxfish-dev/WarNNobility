package com.newtl.warnnobility.integration;

import com.jpreiss.easy_factions.server.claims.ClaimManager;
import com.jpreiss.easy_factions.server.claims.model.ClaimData;
import com.jpreiss.easy_factions.server.claims.model.ClaimType;
import com.newtl.warnnobility.domain.DomainEngine;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads Easy Factions / Territory-Table claimed chunks for the Domain Atlas "Factions" overlay. Every
 * Easy Factions type stays inside method bodies and is reached only after {@link FactionGate#loaded()},
 * so this is dormant (returns empty) when Easy Factions is absent.
 *
 * <p><b>Colour fix (1.0.3):</b> the overlay now colours every claim by its OWN stored colour
 * ({@link ClaimData#color}) -- the exact colour the player picked in the Territory Table -- instead of
 * the faction's single colour. So a personal claim painted green draws green, an admin claim draws its
 * admin colour, and a faction claim draws the faction colour: whatever the claim itself carries. Claims
 * melt together by OWNER (faction name / player UUID / "Admin"), so one contiguous holding reads as a
 * single bordered region. Source is {@link ClaimManager#getClaimMap()} -- the whole claim store, not just
 * faction chunks -- so personal and admin territories appear too, all on the one Factions layer.
 */
public final class FactionClaims {

    private FactionClaims() {}

    /** Per-chunk claims for one dimension: each {@code [chunkX, chunkZ, ownerRegionId, rgb]}. The colour is
     *  the claim's OWN colour; the region id melts same-owner chunks into one border. */
    public static List<int[]> entriesForDim(MinecraftServer server, ResourceKey<Level> dim) {
        List<int[]> out = new ArrayList<>();
        if (!FactionGate.loaded() || server == null) return out;
        ClaimManager cm = ClaimManager.get(server);
        Map<Long, ClaimData> dimClaims = cm.getClaimMap().get(dim);
        if (dimClaims == null) return out;
        for (Map.Entry<Long, ClaimData> en : dimClaims.entrySet()) {
            ClaimData d = en.getValue();
            if (d == null) continue;
            ChunkPos cp = new ChunkPos(en.getKey());
            out.add(new int[]{cp.x, cp.z, ownerRegion(d), d.color & 0xFFFFFF});
        }
        return out;
    }

    /** One centroid name label per owner holding claims in this dimension (Label tier 4 = factions layer). */
    public static List<DomainEngine.Label> labelsForDim(MinecraftServer server, ResourceKey<Level> dim) {
        List<DomainEngine.Label> out = new ArrayList<>();
        if (!FactionGate.loaded() || server == null) return out;
        ClaimManager cm = ClaimManager.get(server);
        Map<Long, ClaimData> dimClaims = cm.getClaimMap().get(dim);
        if (dimClaims == null || dimClaims.isEmpty()) return out;

        // group every claim by owner: accumulate centroid + colour, and keep one sample claim for the name
        Map<String, long[]> sums = new HashMap<>();        // ownerKey -> [sumChunkX, sumChunkZ, count, rgb]
        Map<String, ClaimData> sample = new HashMap<>();   // ownerKey -> a representative claim (for naming)
        for (Map.Entry<Long, ClaimData> en : dimClaims.entrySet()) {
            ClaimData d = en.getValue();
            if (d == null) continue;
            ChunkPos cp = new ChunkPos(en.getKey());
            String key = ownerKey(d);
            long[] a = sums.computeIfAbsent(key, k -> new long[4]);
            a[0] += cp.x;
            a[1] += cp.z;
            a[2] += 1;
            a[3] = d.color & 0xFFFFFF;
            sample.putIfAbsent(key, d);
        }
        for (Map.Entry<String, long[]> en : sums.entrySet()) {
            long[] a = en.getValue();
            if (a[2] == 0) continue;
            int bx = (int) (a[0] / a[2]) * 16 + 8;
            int bz = (int) (a[1] / a[2]) * 16 + 8;
            out.add(new DomainEngine.Label(4, bx, bz, (int) a[3] & 0xFFFFFF, displayName(server, sample.get(en.getKey()))));
        }
        return out;
    }

    /** A stable string identifying a claim's owner, scoped by type so different owners never collide. */
    private static String ownerKey(ClaimData d) {
        return d.type.name() + "|" + d.owner;
    }

    /** A stable non-zero region id per owner, so a contiguous same-owner holding melts into one border. */
    private static int ownerRegion(ClaimData d) {
        int h = ownerKey(d).hashCode();
        return h == 0 ? 1 : h;
    }

    /** The label to draw for an owner: faction name, the player's name for a personal claim, else "Admin". */
    private static String displayName(MinecraftServer server, ClaimData d) {
        if (d == null) return "";
        if (d.type == ClaimType.FACTION) return d.owner;          // faction name is its own label
        if (d.type == ClaimType.ADMIN) return "Admin";
        try {                                                     // CORE/personal: owner is a player UUID
            return nameOf(server, UUID.fromString(d.owner));
        } catch (IllegalArgumentException e) {
            return d.owner;
        }
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        if (id == null) return "?";
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        if (server.getProfileCache() != null) {
            var gp = server.getProfileCache().get(id);
            if (gp.isPresent()) return gp.get().getName();
        }
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }
}
