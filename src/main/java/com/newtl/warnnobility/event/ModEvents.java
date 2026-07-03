package com.newtl.warnnobility.event;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.command.NobilityCommand;
import com.newtl.warnnobility.integration.WarTaxesBridge;
import com.newtl.warnnobility.nobility.NobilityManager;
import com.newtl.warnnobility.nobility.NobleData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Game-bus listeners: command registration and the rank prefix on player names (chat + tab). */
@EventBusSubscriber(modid = WarNNobility.MODID)
public final class ModEvents {

    private ModEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        NobilityCommand.register(event.getDispatcher());
    }

    /** Drive the War 'n Taxes outcome bridge (it self-throttles to the configured poll interval). */
    private static boolean taxBridgeDead = false;

    /** Last title (rank+gender+colour) we pushed to the tab list per player, so we only resend on change. */
    private static final Map<UUID, String> lastTitle = new HashMap<>();
    private static int nameTick = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // Keep the TAB LIST title in sync: NeoForge caches the tab display name, so a promotion only
        // shows in chat (rebuilt per message) until we re-push it. Check ~1x/sec and resend on change.
        refreshTabTitles(server);

        if (taxBridgeDead) return;
        try {
            WarTaxesBridge.onServerTick(server);
        } catch (Throwable t) {
            // War 'n Taxes / MineColonies may be absent or not classloadable; stay dormant, don't retry.
            taxBridgeDead = true;
            WarNNobility.LOGGER.error("[WarTaxesBridge] disabled: could not run the War 'n Taxes bridge "
                    + "(its mod or MineColonies is likely absent). Nobility runs standalone.", t);
        }
    }

    /** Resend UPDATE_DISPLAY_NAME for any player whose title changed, so the tab list updates at once. */
    private static void refreshTabTitles(MinecraftServer server) {
        if (++nameTick < 20) return;   // ~once a second
        nameTick = 0;
        NobilityManager m = NobilityManager.get(server);
        List<ServerPlayer> changed = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            NobleData d = m.getOrCreate(p);
            String key = m.rankName(d) + "|" + d.female + "|" + m.rankColor(d);
            if (!key.equals(lastTitle.put(p.getUUID(), key))) changed.add(p);
        }
        if (!changed.isEmpty()) {
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), changed));
        }
    }

    /**
     * The reliable rank prefix in CHAT. NeoForge's chat path does not route through the NameFormat
     * event the way Forge did, so we rebuild the line here: cancel the vanilla broadcast and send
     * "[Title] Name: message" to everyone. Uses the raw name (getName) so it never double-prefixes.
     */
    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer sp = event.getPlayer();
        MinecraftServer server = sp.getServer();
        if (server == null) return;
        NobilityManager m = NobilityManager.get(server);
        NobleData d = m.getOrCreate(sp);
        Component line = Component.empty()
                .append(Component.literal("[" + m.rankName(d) + "] ")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(m.rankColor(d)))))
                .append(sp.getName())
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(event.getMessage());
        event.setCanceled(true);
        server.getPlayerList().broadcastSystemMessage(line, false);
    }

    /** Prefix the player's title in chat (vanilla chat uses the display name). Server-side only. */
    @SubscribeEvent
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            event.setDisplayname(decorate(sp, event.getDisplayname()));
        }
    }

    /** Prefix the player's title in the TAB player list. Server-side only. */
    @SubscribeEvent
    public static void onTabName(PlayerEvent.TabListNameFormat event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            Component base = event.getDisplayName();
            if (base == null) base = sp.getName();
            event.setDisplayName(decorate(sp, base));
        }
    }

    /** "[Title] <name>" with the holder's current (gender-aware) rank. */
    private static Component decorate(ServerPlayer sp, Component base) {
        MinecraftServer server = sp.getServer();
        if (server == null) return base;
        NobilityManager m = NobilityManager.get(server);
        NobleData d = m.getOrCreate(sp);
        return Component.empty()
                .append(Component.literal("[" + m.rankName(d) + "] ")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(m.rankColor(d)))))
                .append(base);
    }
}
