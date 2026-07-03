package com.newtl.warnnobility.domain.net;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.domain.DomainEngine;
import com.newtl.warnnobility.domain.client.ClientDomains;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client, Easy Factions claim layer ONLY. Carried in its own packet (separate from
 * {@link DomainMapPayload}) so faction claims are computed and pushed independently of MineColonies:
 * the colony-domain engine can be absent, throwing, or simply have no colonies, and faction borders
 * still reach the atlas. One payload per dimension a player occupies; an empty list clears stale claims.
 *
 * <p>Each entry is {@code [chunkX, chunkZ, regionId, rgb]} (regionId = a stable per-faction id used only
 * to detect borders between two different factions). Each label is a centroid name for one faction
 * (tier 4 = faction), drawn when the player toggles the Factions layer on.
 */
public record FactionMapPayload(ResourceLocation dimension, List<int[]> factionEntries,
                                List<DomainEngine.Label> factionLabels)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FactionMapPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "faction_map"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FactionMapPayload> STREAM_CODEC =
            StreamCodec.ofMember(FactionMapPayload::write, FactionMapPayload::decode);

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeVarInt(factionEntries.size());
        for (int[] e : factionEntries) {
            buf.writeVarInt(e[0]);
            buf.writeVarInt(e[1]);
            buf.writeVarInt(e[2]);
            buf.writeInt(e[3]);
        }
        buf.writeVarInt(factionLabels.size());
        for (DomainEngine.Label l : factionLabels) {
            buf.writeVarInt(l.tier());
            buf.writeVarInt(l.blockX());
            buf.writeVarInt(l.blockZ());
            buf.writeInt(l.rgb());
            buf.writeUtf(l.name());
        }
    }

    public static FactionMapPayload decode(RegistryFriendlyByteBuf buf) {
        ResourceLocation dim = buf.readResourceLocation();
        int fn = buf.readVarInt();
        List<int[]> entries = new ArrayList<>(fn);
        for (int i = 0; i < fn; i++) {
            int[] e = new int[4];
            e[0] = buf.readVarInt();
            e[1] = buf.readVarInt();
            e[2] = buf.readVarInt();
            e[3] = buf.readInt();
            entries.add(e);
        }
        int ln = buf.readVarInt();
        List<DomainEngine.Label> labels = new ArrayList<>(ln);
        for (int i = 0; i < ln; i++) {
            int tier = buf.readVarInt();
            int bx = buf.readVarInt();
            int bz = buf.readVarInt();
            int rgb = buf.readInt();
            String name = buf.readUtf();
            labels.add(new DomainEngine.Label(tier, bx, bz, rgb, name));
        }
        return new FactionMapPayload(dim, entries, labels);
    }

    @Override
    public CustomPacketPayload.Type<FactionMapPayload> type() {
        return TYPE;
    }

    public static void handleClient(FactionMapPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientDomains.acceptFactions(msg));
    }
}
