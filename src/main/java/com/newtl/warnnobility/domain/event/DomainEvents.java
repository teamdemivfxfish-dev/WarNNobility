package com.newtl.warnnobility.domain.event;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.domain.DomainCommand;
import com.newtl.warnnobility.domain.DomainEngine;
import com.newtl.warnnobility.domain.FactionEngine;
import com.newtl.warnnobility.domain.net.DomainNetwork;
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
    private static boolean soon;

    private DomainEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        DomainCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++ticks % REFRESH_TICKS != 0 && !soon) return;
        soon = false;
        recomputeSafe(event.getServer());
    }

    /**
     * A player who joins or changes dimension needs the borders now. Only THAT player is resent: the
     * rebuild happens once on the next tick however many players joined meanwhile, and everybody else
     * is skipped because their payload has not changed (DomainNetwork keeps what each was last sent).
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            DomainNetwork.forget(sp.getUUID());
            soon = true;
        }
    }

    /** And again on a dimension change (the data is per-dimension). */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            DomainNetwork.forget(sp.getUUID());
            soon = true;
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        DomainNetwork.forget(event.getEntity().getUUID());
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
        // Faction claim borders. Fully independent path (no MineColonies), gated only on the faction mod, so
        // faction claims reach the atlas even with MineColonies absent or erroring.
        try {
            FactionEngine.recompute(server);
        } catch (Throwable ignored) {
            // no faction mod, or not ready yet; try again next cycle
        }
    }
}
