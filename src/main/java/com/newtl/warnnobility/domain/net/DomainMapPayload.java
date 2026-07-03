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
 * Server -> client. For one dimension: the per-chunk domain membership at all four levels
 * (colonies, counties, duchies, kingdoms), plus a name label per domain anchored at the holder's
 * main colony. The client edge-detects the chunk data and draws borders + labels for the levels the
 * player has toggled on.
 *
 * Each chunk entry is {@code [chunkX, chunkZ, r0,c0, r1,c1, r2,c2, r3,c3]} where rN = a region id at
 * level N (0 = none) and cN = a packed colour (-1 = none), level order = colonies/counties/duchies/
 * kingdoms. Each label is {@code (tier, blockX, blockZ, rgb, name)} with tier 1=county/2=duchy/3=realm.
 *
 * <p>Easy Factions claims are NOT carried here: they ride their own {@code FactionMapPayload} so the
 * faction overlay is fully independent of MineColonies (see {@code domain/FactionEngine}).
 */
public record DomainMapPayload(ResourceLocation dimension, List<int[]> entries, List<DomainEngine.Label> labels,
                               List<DomainEngine.Label> colonyMarkers)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DomainMapPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "domain_map"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DomainMapPayload> STREAM_CODEC =
            StreamCodec.ofMember(DomainMapPayload::write, DomainMapPayload::decode);

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeVarInt(entries.size());
        for (int[] e : entries) {
            buf.writeVarInt(e[0]);
            buf.writeVarInt(e[1]);
            for (int i = 2; i < 10; i++) buf.writeVarInt(e[i]);
        }
        writeLabels(buf, labels);
        writeLabels(buf, colonyMarkers);
    }

    private static void writeLabels(RegistryFriendlyByteBuf buf, List<DomainEngine.Label> labels) {
        buf.writeVarInt(labels.size());
        for (DomainEngine.Label l : labels) {
            buf.writeVarInt(l.tier());
            buf.writeVarInt(l.blockX());
            buf.writeVarInt(l.blockZ());
            buf.writeInt(l.rgb());
            buf.writeUtf(l.name());
        }
    }

    public static DomainMapPayload decode(RegistryFriendlyByteBuf buf) {
        ResourceLocation dim = buf.readResourceLocation();
        int n = buf.readVarInt();
        List<int[]> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int[] e = new int[10];
            e[0] = buf.readVarInt();
            e[1] = buf.readVarInt();
            for (int j = 2; j < 10; j++) e[j] = buf.readVarInt();
            entries.add(e);
        }
        List<DomainEngine.Label> labels = readLabels(buf);
        List<DomainEngine.Label> colonyMarkers = readLabels(buf);

        return new DomainMapPayload(dim, entries, labels, colonyMarkers);
    }

    private static List<DomainEngine.Label> readLabels(RegistryFriendlyByteBuf buf) {
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
        return labels;
    }

    @Override
    public CustomPacketPayload.Type<DomainMapPayload> type() {
        return TYPE;
    }

    public static void handleClient(DomainMapPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientDomains.accept(msg));
    }
}
