package com.newtl.warnnobility.integration.holdfast;

import dev.xmat5.holdfast.server.claims.ClaimManager;
import dev.xmat5.holdfast.server.faction.Faction;
import dev.xmat5.holdfast.server.faction.FactionStateManager;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Holdfast Factions (modid {@code holdfast_factions}) behind {@link com.newtl.warnnobility.integration.FactionGate}.
 * Only ever called after the gate has confirmed Holdfast Factions is installed, so this class (and the
 * Holdfast Factions types it names) never loads without it.
 */
public final class HoldfastGate {


    private HoldfastGate() {}


    /** True if {@code player} OWNS (leads) a faction. Always false when Holdfast Factions is absent. */
    public static boolean isFactionLeader(MinecraftServer server, UUID player) {
        if (server == null) return false;
        return FactionStateManager.get(server).playerOwnsFaction(player);
    }

    /** Total claimed chunks held by the faction the player leads (0 if not a leader / mod absent). */
    public static int factionClaimCount(MinecraftServer server, UUID player) {
        if (server == null) return 0;
        Faction f = FactionStateManager.get(server).getFactionByPlayer(player);
        if (f == null || !f.getOwner().equals(player)) return 0;
        return ClaimManager.get(server).getFactionClaimCount(f.getName());
    }

    /**
     * True if two players are allied for the War Frame allegiance test: the same player, or members of the
     * same Holdfast Factions faction. False (i.e. treat as hostile/other) when the mod is absent or either has no
     * faction. Used to colour units friendly vs hostile on the board.
     */
    public static boolean sameFaction(MinecraftServer server, UUID a, UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        if (server == null) return false;
        Faction fa = FactionStateManager.get(server).getFactionByPlayer(a);
        Faction fb = FactionStateManager.get(server).getFactionByPlayer(b);
        return fa != null && fb != null && fa.getName().equals(fb.getName());
    }

    /** The name of the faction the player leads, or "(none)" if not a leader / mod absent. */
    public static String factionName(MinecraftServer server, UUID player) {
        if (server == null) return "(none)";
        Faction f = FactionStateManager.get(server).getFactionByPlayer(player);
        return (f != null && f.getOwner().equals(player)) ? f.getName() : "(none)";
    }
}
