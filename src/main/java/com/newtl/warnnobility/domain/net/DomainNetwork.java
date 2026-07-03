package com.newtl.warnnobility.domain.net;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Payload wiring: the server pushes per-dimension domain data to clients for the border overlay. */
public final class DomainNetwork {

    private DomainNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(DomainMapPayload.TYPE, DomainMapPayload.STREAM_CODEC, DomainMapPayload::handleClient);
        registrar.playToClient(FactionMapPayload.TYPE, FactionMapPayload.STREAM_CODEC, FactionMapPayload::handleClient);
    }

    public static void toPlayer(ServerPlayer player, DomainMapPayload msg) {
        PacketDistributor.sendToPlayer(player, msg);
    }

    public static void toPlayer(ServerPlayer player, FactionMapPayload msg) {
        PacketDistributor.sendToPlayer(player, msg);
    }
}
