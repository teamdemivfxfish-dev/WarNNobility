package com.newtl.warnnobility.atlas.net;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Payload wiring for the player-structure atlas overlay (folded into War 'n Nobility). */
public final class AtlasNetwork {

    private AtlasNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(DiscoveryPayload.TYPE, DiscoveryPayload.STREAM_CODEC, DiscoveryPayload::handleClient);
    }

    public static void toPlayer(ServerPlayer player, DiscoveryPayload msg) {
        PacketDistributor.sendToPlayer(player, msg);
    }
}
