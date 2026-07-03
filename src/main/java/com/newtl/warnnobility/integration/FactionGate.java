package com.newtl.warnnobility.integration;

import com.jpreiss.easy_factions.server.claims.ClaimManager;
import com.jpreiss.easy_factions.server.faction.Faction;
import com.jpreiss.easy_factions.server.faction.FactionStateManager;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.util.UUID;

/**
 * Read-only seam into Easy Factions (modid {@code easy_factions}) for the King ({@code CROWN}) gate.
 *
 * <p>Only exposes JDK-typed results (boolean / int / String) so that callers never name an Easy
 * Factions class; every reference to one lives inside a method body here and runs only after
 * {@link #loaded()} confirms the mod is present. That keeps the King gate dormant, not crashing, when
 * Easy Factions is absent (same optional-dependency discipline the domain map uses for MineColonies).
 */
public final class FactionGate {

    public static final String MODID = "easy_factions";

    private FactionGate() {}

    public static boolean loaded() {
        return ModList.get().isLoaded(MODID);
    }

    /** True if {@code player} OWNS (leads) a faction. Always false when Easy Factions is absent. */
    public static boolean isFactionLeader(MinecraftServer server, UUID player) {
        if (!loaded() || server == null) return false;
        return FactionStateManager.get(server).playerOwnsFaction(player);
    }

    /** Total claimed chunks held by the faction the player leads (0 if not a leader / mod absent). */
    public static int factionClaimCount(MinecraftServer server, UUID player) {
        if (!loaded() || server == null) return 0;
        Faction f = FactionStateManager.get(server).getFactionByPlayer(player);
        if (f == null || !f.getOwner().equals(player)) return 0;
        return ClaimManager.get(server).getFactionClaimCount(f.getName());
    }

    /**
     * True if two players are allied for the War Frame allegiance test: the same player, or members of the
     * same Easy Factions faction. False (i.e. treat as hostile/other) when EF is absent or either has no
     * faction. Used to colour units friendly vs hostile on the board.
     */
    public static boolean sameFaction(MinecraftServer server, UUID a, UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        if (!loaded() || server == null) return false;
        Faction fa = FactionStateManager.get(server).getFactionByPlayer(a);
        Faction fb = FactionStateManager.get(server).getFactionByPlayer(b);
        return fa != null && fb != null && fa.getName().equals(fb.getName());
    }

    /** The name of the faction the player leads, or "(none)" if not a leader / mod absent. */
    public static String factionName(MinecraftServer server, UUID player) {
        if (!loaded() || server == null) return "(none)";
        Faction f = FactionStateManager.get(server).getFactionByPlayer(player);
        return (f != null && f.getOwner().equals(player)) ? f.getName() : "(none)";
    }
}
