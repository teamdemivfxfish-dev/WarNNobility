package com.newtl.warnnobility.net;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.client.ClientVassalStatus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client. Tells the viewer which of their War 'n Taxes vassal colonies are, in nobility terms,
 * still only PROVISIONAL seals (won by conquest but not yet sworn into vassalage, because the holder has
 * not ascended). The client caches these ids in {@link ClientVassalStatus}; a mixin on War 'n Taxes'
 * VassalsPage then appends " (Provisional)" to those rows in the tax book. Refreshed every bridge poll,
 * so an empty list clears the tags once a hold converts or lapses.
 */
public record VassalStatusMsg(List<Integer> provisionalColonyIds) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<VassalStatusMsg> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "vassal_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VassalStatusMsg> STREAM_CODEC =
            StreamCodec.ofMember(VassalStatusMsg::write, VassalStatusMsg::decode);

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(provisionalColonyIds.size());
        for (int id : provisionalColonyIds) buf.writeVarInt(id);
    }

    public static VassalStatusMsg decode(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Integer> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(buf.readVarInt());
        return new VassalStatusMsg(ids);
    }

    @Override
    public CustomPacketPayload.Type<VassalStatusMsg> type() {
        return TYPE;
    }

    public static void handleClient(VassalStatusMsg msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientVassalStatus.set(msg.provisionalColonyIds()));
    }
}
