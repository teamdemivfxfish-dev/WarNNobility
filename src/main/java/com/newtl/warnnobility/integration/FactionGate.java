package com.newtl.warnnobility.integration;

import com.newtl.warnnobility.integration.easyfactions.EasyFactionsGate;
import com.newtl.warnnobility.integration.holdfast.HoldfastGate;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.util.UUID;

/**
 * Read-only seam into the faction mod for the King ({@code CROWN}) gate and War Frame allegiance.
 *
 * <p>Works with either faction mod: <b>Holdfast Factions</b> (modid {@code holdfast_factions}, Xmat5's
 * rewrite) or the older <b>Easy Factions</b> (modid {@code easy_factions}). Holdfast wins if both are
 * installed. Their APIs are the same apart from the package, so each has a twin class under
 * {@code integration.holdfast} / {@code integration.easyfactions}; this class names neither mod's types,
 * so it is safe to load when no faction mod is installed, and only the class for the mod actually present
 * ever loads. Every result here is JDK-typed (boolean / int / String).
 */
public final class FactionGate {

    public static final String HOLDFAST = "holdfast_factions";
    public static final String EASY_FACTIONS = "easy_factions";

    private FactionGate() {}

    private static boolean holdfast() {
        return ModList.get().isLoaded(HOLDFAST);
    }

    /** Easy Factions is used only when Holdfast is not installed. */
    private static boolean easyFactions() {
        return !holdfast() && ModList.get().isLoaded(EASY_FACTIONS);
    }

    /** True if either faction mod is installed. */
    public static boolean loaded() {
        return holdfast() || ModList.get().isLoaded(EASY_FACTIONS);
    }

    /** Which faction mod is in use, for logs and messages. */
    public static String provider() {
        return holdfast() ? "Holdfast Factions" : easyFactions() ? "Easy Factions" : "none";
    }

    /** True if {@code player} OWNS (leads) a faction. Always false when no faction mod is installed. */
    public static boolean isFactionLeader(MinecraftServer server, UUID player) {
        if (server == null) return false;
        if (holdfast()) return HoldfastGate.isFactionLeader(server, player);
        if (easyFactions()) return EasyFactionsGate.isFactionLeader(server, player);
        return false;
    }

    /** Total claimed chunks held by the faction the player leads (0 if not a leader / no faction mod). */
    public static int factionClaimCount(MinecraftServer server, UUID player) {
        if (server == null) return 0;
        if (holdfast()) return HoldfastGate.factionClaimCount(server, player);
        if (easyFactions()) return EasyFactionsGate.factionClaimCount(server, player);
        return 0;
    }

    /**
     * True if two players are allied for the War Frame allegiance test: the same player, or members of the
     * same faction. False (i.e. treat as hostile/other) when no faction mod is installed or either has no
     * faction. Used to colour units friendly vs hostile on the board.
     */
    public static boolean sameFaction(MinecraftServer server, UUID a, UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        if (server == null) return false;
        if (holdfast()) return HoldfastGate.sameFaction(server, a, b);
        if (easyFactions()) return EasyFactionsGate.sameFaction(server, a, b);
        return false;
    }

    /** The name of the faction the player leads, or "(none)" if not a leader / no faction mod. */
    public static String factionName(MinecraftServer server, UUID player) {
        if (server == null) return "(none)";
        if (holdfast()) return HoldfastGate.factionName(server, player);
        if (easyFactions()) return EasyFactionsGate.factionName(server, player);
        return "(none)";
    }
}
