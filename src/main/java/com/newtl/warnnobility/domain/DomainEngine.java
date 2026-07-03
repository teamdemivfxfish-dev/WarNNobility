package com.newtl.warnnobility.domain;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.claim.IChunkClaimData;
import com.newtl.warnnobility.domain.net.DomainMapPayload;
import com.newtl.warnnobility.domain.net.DomainNetwork;
import com.newtl.warnnobility.nobility.NobilityManager;
import com.newtl.warnnobility.nobility.NobleData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The territory engine. Reads colonies + claims from MineColonies, maps each colony owner to their
 * War 'n Nobility rank, and pushes to each client a per-chunk record of which colony / county /
 * duchy / kingdom that chunk belongs to (walking the vassal/liege chain). The client draws thin
 * borders for the levels the player has toggled on (see {@code domain/client/DomainOverlay}).
 *
 * The melding is implicit: every claimed chunk is tagged, at each tier, with the holder it reaches
 * by walking UP the liege chain. The client then dissolves any border between two chunks that share
 * the same holder at the viewed tier. That is identical to "union the whole subtree under each
 * holder": a count's land + all his vassals meld at the county tier; under the duchy tier those same
 * vassal chunks walk up past their count to the duke, so the count's vassals show as the duke's land;
 * under the kingdom tier everything collapses to the king. The marker for each domain is the holder's
 * own (main) colony, not the geometric centre.
 *
 * MineColonies is an OPTIONAL dependency: this class is only loaded when {@code DomainEngine.recompute}
 * is first called, and the caller ({@code domain/event/DomainEvents}) wraps it in a try/catch, so when
 * MineColonies is absent the class fails to load there and the domain feature simply stays dormant
 * without crashing the rest of War 'n Nobility. Antique Atlas 4 is reached entirely client-side by
 * reflection, so this server engine has no atlas dependency at all -- it only computes and ships data.
 */
public final class DomainEngine {

    private DomainEngine() {}

    /** A name label for one domain, drawn at the holder's main colony. tier: 1=county,2=duchy,3=realm. */
    public record Label(int tier, int blockX, int blockZ, int rgb, String name) {}

    /** Recompute domains and push per-chunk membership + domain labels to clients. */
    public static int recompute(MinecraftServer server) {
        IColonyManager cm = IColonyManager.getInstance();
        NobilityManager nm = NobilityManager.get(server);
        if (cm == null || nm == null) return 0;

        Map<ResourceKey<Level>, List<IColony>> byDim = new HashMap<>();
        for (IColony c : cm.getAllColonies()) {
            byDim.computeIfAbsent(c.getDimension(), k -> new ArrayList<>()).add(c);
        }

        // Easy Factions claims are handled by FactionEngine (its own packet), fully decoupled from this
        // MineColonies recompute, so this engine only walks colony domains.
        Set<ResourceKey<Level>> dims = new HashSet<>(byDim.keySet());

        int domainCount = 0;
        for (ResourceKey<Level> dimKey : dims) {
            ServerLevel level = server.getLevel(dimKey);
            if (level == null) continue;
            List<IColony> colonies = byDim.getOrDefault(dimKey, List.of());

            // colony id -> owner / colour / colony object, owner -> colony id
            Map<Integer, UUID> colonyOwner = new HashMap<>();
            Map<UUID, Integer> ownerColony = new HashMap<>();
            Map<UUID, Integer> ownerRgb = new HashMap<>();
            Map<Integer, IColony> byId = new HashMap<>();
            for (IColony c : colonies) {
                UUID owner = c.getPermissions().getOwner();
                colonyOwner.put(c.getID(), owner);
                byId.put(c.getID(), c);
                if (owner != null) {
                    ownerColony.put(owner, c.getID());
                    ownerRgb.put(owner, rgbOf(c.getTeamColonyColor()));
                }
            }

            // colony id -> claimed chunks
            Map<Integer, Set<ChunkPos>> colonyChunks = new HashMap<>();
            Map<ChunkPos, IChunkClaimData> claims = cm.getClaimData(dimKey);
            if (claims != null) {
                for (Map.Entry<ChunkPos, IChunkClaimData> ce : claims.entrySet()) {
                    int owning = ce.getValue().getOwningColony();
                    if (owning > 0 && colonyOwner.containsKey(owning)) {
                        colonyChunks.computeIfAbsent(owning, k -> new HashSet<>()).add(ce.getKey());
                    }
                }
            }

            // per-chunk membership at all four levels -> send to clients in this dimension
            List<int[]> entries = new ArrayList<>();
            for (Map.Entry<Integer, Set<ChunkPos>> ce : colonyChunks.entrySet()) {
                int colonyId = ce.getKey();
                UUID owner = colonyOwner.get(colonyId);
                NobleData ud = owner != null ? nm.peek(owner) : null;
                int colonyRgb = owner != null ? ownerRgb.getOrDefault(owner, 0xFFFFFF) : 0xFFFFFF;
                int[] t = (ud != null) ? tiers(ud, nm, ownerRgb) : new int[]{0, -1, 0, -1, 0, -1};
                for (ChunkPos cp : ce.getValue()) {
                    entries.add(new int[]{cp.x, cp.z, colonyId, colonyRgb, t[0], t[1], t[2], t[3], t[4], t[5]});
                }
            }

            // a TOWN marker at every colony centre (independent of nobility), so settlements always show
            // on the atlas even without the minecoloniesatlas add-on. tier 0 = colony marker.
            List<Label> colonyMarkers = new ArrayList<>();
            for (IColony c : colonies) {
                UUID owner = c.getPermissions().getOwner();
                int rgb = owner != null ? ownerRgb.getOrDefault(owner, 0xFFFFFF) : 0xFFFFFF;
                BlockPos ctr = c.getCenter();
                colonyMarkers.add(new Label(0, ctr.getX(), ctr.getZ(), rgb, c.getName()));
            }

            // one label per domain holder, placed at that holder's own (main) colony centre
            List<Label> labels = new ArrayList<>();
            for (IColony c : colonies) {
                UUID owner = c.getPermissions().getOwner();
                if (owner == null) continue;
                NobleData d = nm.peek(owner);
                if (d == null || !nm.holdsDomain(d.rankIndex)) continue;
                // only the holder's MAIN colony carries the label (skip secondary colonies he owns)
                Integer main = ownerColony.get(owner);
                if (main == null || main != c.getID()) continue;

                String kind = nm.domainKind(d.rankIndex);
                int tier = switch (kind) { case "duchy" -> 2; case "realm" -> 3; default -> 1; };
                String dn = (d.domainName == null || d.domainName.isBlank())
                        ? d.name + "'s " + kind : d.domainName;
                BlockPos center = c.getCenter();
                int rgb = ownerRgb.getOrDefault(owner, 0xFFFFFF);
                labels.add(new Label(tier, center.getX(), center.getZ(), rgb, cap(kind) + ": " + dn));
                domainCount++;
            }

            DomainMapPayload payload = new DomainMapPayload(dimKey.location(), entries, labels, colonyMarkers);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.level().dimension().equals(dimKey)) DomainNetwork.toPlayer(p, payload);
            }
        }
        return domainCount;
    }

    /** Walk up the liege chain to find this noble's county/duchy/kingdom holder + that holder's
     *  colony colour. Returns [countyRegion, countyRgb, dukeRegion, dukeRgb, kingRegion, kingRgb];
     *  region 0 / rgb -1 = none at that level. */
    private static int[] tiers(NobleData start, NobilityManager nm, Map<UUID, Integer> ownerRgb) {
        int countyR = 0, countyRgb = -1, dukeR = 0, dukeRgb = -1, kingR = 0, kingRgb = -1;
        NobleData n = start;
        Set<UUID> seen = new HashSet<>();
        while (n != null && seen.add(n.id)) {
            if (nm.holdsDomain(n.rankIndex)) {
                String k = nm.domainKind(n.rankIndex);
                int rid = n.id.hashCode() == 0 ? 1 : n.id.hashCode();
                int rgb = ownerRgb.getOrDefault(n.id, 0xFFFFFF);
                if (k.equals("county") && countyR == 0) { countyR = rid; countyRgb = rgb; }
                else if (k.equals("duchy") && dukeR == 0) { dukeR = rid; dukeRgb = rgb; }
                else if (k.equals("realm") && kingR == 0) { kingR = rid; kingRgb = rgb; }
            }
            n = (n.liege != null) ? nm.peek(n.liege) : null;
        }
        return new int[]{countyR, countyRgb, dukeR, dukeRgb, kingR, kingRgb};
    }

    /** MineColonies colony colour (a ChatFormatting) -> packed 0xRRGGBB (white if it has no colour). */
    private static int rgbOf(ChatFormatting color) {
        if (color != null && color.isColor() && color.getColor() != null) return color.getColor();
        return 0xFFFFFF;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
