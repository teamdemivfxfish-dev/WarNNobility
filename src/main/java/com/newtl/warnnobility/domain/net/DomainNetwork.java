package com.newtl.warnnobility.domain.net;

import com.newtl.warnnobility.domain.DomainEngine;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    // What each player was last sent, as a hash of the payload. The borders are rebuilt every few
    // seconds but almost never change, and sending every claimed chunk to every player each time
    // was steady traffic for nothing (Xmat5's report). Now a player is sent a payload only when it
    // differs from the last one they got.
    private static final Map<UUID, Long> DOMAIN_SENT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FACTION_SENT = new ConcurrentHashMap<>();

    public static void toPlayerIfChanged(ServerPlayer player, DomainMapPayload msg, long hash) {
        Long last = DOMAIN_SENT.put(player.getUUID(), hash);
        if (last == null || last != hash) toPlayer(player, msg);
    }

    public static void toPlayerIfChanged(ServerPlayer player, FactionMapPayload msg, long hash) {
        Long last = FACTION_SENT.put(player.getUUID(), hash);
        if (last == null || last != hash) toPlayer(player, msg);
    }

    /** The player has nothing current any more (joined, or changed dimension), so send in full next time. */
    public static void forget(UUID player) {
        DOMAIN_SENT.remove(player);
        FACTION_SENT.remove(player);
    }

    /**
     * A hash of a payload's content that ignores order, because the entries come out of hash maps and
     * the same borders can be listed in a different order from one rebuild to the next.
     */
    @SafeVarargs
    public static long hash(ResourceLocation dimension, List<int[]> entries, List<DomainEngine.Label>... labels) {
        long h = dimension.hashCode();
        long sum = 0;
        for (int[] e : entries) sum += mix(Arrays.hashCode(e));
        h = h * 31 + sum + entries.size();
        for (List<DomainEngine.Label> list : labels) {
            long ls = 0;
            for (DomainEngine.Label l : list) ls += mix(l.hashCode());
            h = h * 31 + ls + list.size();
        }
        return h;
    }

    private static long mix(long x) {
        x *= 0x9E3779B97F4A7C15L;
        return x ^ (x >>> 29);
    }
}
