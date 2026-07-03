package com.newtl.warnnobility.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge payload wiring (replaces Forge's SimpleChannel). Two payloads: open/refresh the Chancery
 * console (server -> client) and act on it (client -> server).
 */
public final class Network {

    private Network() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(OpenChanceryMsg.TYPE, OpenChanceryMsg.STREAM_CODEC, OpenChanceryMsg::handleClient);
        registrar.playToClient(VassalStatusMsg.TYPE, VassalStatusMsg.STREAM_CODEC, VassalStatusMsg::handleClient);
        registrar.playToServer(ChanceryActionMsg.TYPE, ChanceryActionMsg.STREAM_CODEC, ChanceryActionMsg::handleServer);
    }

    public static void toClient(ServerPlayer player, CustomPacketPayload msg) {
        PacketDistributor.sendToPlayer(player, msg);
    }

    public static void toServer(CustomPacketPayload msg) {
        PacketDistributor.sendToServer(msg);
    }
}
