package com.newtl.warnnobility.domain.event;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.domain.DomainCommand;
import com.newtl.warnnobility.domain.DomainEngine;
import com.newtl.warnnobility.domain.FactionEngine;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Game-bus listeners for the optional nobility-domain map feature: register the /domains command, and
 * recompute domains periodically. The cross-mod calls are wrapped so that when MineColonies (or Antique
 * Atlas) is absent, the domain feature simply stays dormant and never crashes the tick loop or the rest
 * of War 'n Nobility.
 */
@EventBusSubscriber(modid = WarNNobility.MODID)
public final class DomainEvents {

    private static final int REFRESH_TICKS = 60;   // 3 seconds; self-updates so the atlas is near-current
    private static int ticks = 0;

    private DomainEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        DomainCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++ticks % REFRESH_TICKS != 0) return;
        recomputeSafe(event.getServer());
    }

    /** Push fresh borders the moment a player joins, so their atlas is current before they open it. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) recomputeSafe(sp.getServer());
    }

    /** And again on a dimension change (the data is per-dimension). */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) recomputeSafe(sp.getServer());
    }

    private static void recomputeSafe(MinecraftServer server) {
        if (server == null) return;
        // MineColonies colony-domain borders. Wrapped on its own so a missing/throwing MineColonies can
        // NOT take the faction overlay down with it.
        try {
            DomainEngine.recompute(server);
        } catch (Throwable ignored) {
            // MineColonies / Antique Atlas may not be installed or ready yet; try again next cycle
        }
        // Easy Factions claim borders. Fully independent path (no MineColonies), gated only on EF, so
        // faction claims reach the atlas even with MineColonies absent or erroring.
        try {
            FactionEngine.recompute(server);
        } catch (Throwable ignored) {
            // Easy Factions absent or not ready yet; try again next cycle
        }
    }
}
