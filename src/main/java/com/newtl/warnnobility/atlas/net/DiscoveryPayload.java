package com.newtl.warnnobility.atlas.net;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.atlas.Discovery;
import com.newtl.warnnobility.atlas.client.ClientDiscoveries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client. The full set of discovered player structures this player has revealed, for one
 * dimension (fog-of-war: the server only ever sends what the player has come near). The client replaces
 * its store and redraws the markers on the atlas + War Frame.
 */
public record DiscoveryPayload(ResourceLocation dimension, List<Discovery> discoveries)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DiscoveryPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "structure_discoveries"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DiscoveryPayload> STREAM_CODEC =
            StreamCodec.ofMember(DiscoveryPayload::write, DiscoveryPayload::decode);

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeVarInt(discoveries.size());
        for (Discovery d : discoveries) d.write(buf);
    }

    public static DiscoveryPayload decode(RegistryFriendlyByteBuf buf) {
        ResourceLocation dim = buf.readResourceLocation();
        int n = buf.readVarInt();
        List<Discovery> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(Discovery.read(buf));
        return new DiscoveryPayload(dim, list);
    }

    @Override
    public CustomPacketPayload.Type<DiscoveryPayload> type() { return TYPE; }

    public static void handleClient(DiscoveryPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientDiscoveries.accept(msg));
    }
}
