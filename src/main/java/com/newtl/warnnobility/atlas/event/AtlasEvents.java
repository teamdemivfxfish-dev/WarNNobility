package com.newtl.warnnobility.atlas.event;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.atlas.DiscoveryEngine;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Game-bus driver for the player-structure atlas overlay: every half-second it surveys the ground around
 * ONE exploring player (surveys are the costly part, so it is capped to one per cycle and re-uses the
 * per-chunk cooldown), and reveals + syncs nearby discoveries to everyone. Everything is fail-soft so a
 * missing Antique Atlas / a survey hiccup never disturbs the tick loop or the rest of War 'n Nobility.
 */
@EventBusSubscriber(modid = WarNNobility.MODID)
public final class AtlasEvents {

    private static final int PERIOD = 10;   // half a second
    private static int ticks = 0;

    private AtlasEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!DiscoveryEngine.enabled()) return;
        if (++ticks % PERIOD != 0) return;
        MinecraftServer server = event.getServer();
        boolean surveyedOne = false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            try {
                if (!surveyedOne && DiscoveryEngine.surveyAround(p)) surveyedOne = true;
                DiscoveryEngine.revealAndSync(p);
            } catch (Throwable ignored) {
                // Antique Atlas / a survey read may hiccup; try again next cycle, never crash the tick.
            }
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            try { DiscoveryEngine.sendSnapshot(sp); } catch (Throwable ignored) {}
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            try { DiscoveryEngine.sendSnapshot(sp); } catch (Throwable ignored) {}
        }
    }
}
