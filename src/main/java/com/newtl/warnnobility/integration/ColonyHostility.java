package com.newtl.warnnobility.integration;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.colony.permissions.Rank;
import com.newtl.warnnobility.WarNNobility;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.util.UUID;

/**
 * Read-write seam into MineColonies' colony permissions, used only for the "renounce = open hostility"
 * rule: when a vassal rebels, every colony their ex-liege owns is told to treat the rebel as HOSTILE, so
 * the liege's guards turn on them and the liege has cause to march a fresh subjugation war.
 *
 * <p>All MineColonies references live inside method bodies that only run once {@link #loaded()} confirms the
 * mod is present, so a missing MineColonies never links this class (the same optional-dependency discipline
 * as {@link FactionGate}). Every call is wrapped fail-soft: if the API drifts, hostility simply does not
 * apply and the renounce still goes through.
 */
public final class ColonyHostility {

    private ColonyHostility() {}

    public static boolean loaded() {
        return ModList.get().isLoaded("minecolonies");
    }

    /**
     * Mark {@code rebel} as HOSTILE in every colony owned by {@code exLiege}. No-op when MineColonies is
     * absent, the ex-liege owns no colony, or the API throws.
     *
     * @return the number of colonies in which the rebel was set hostile.
     */
    public static int makeHostile(MinecraftServer server, UUID exLiege, UUID rebel) {
        if (server == null || exLiege == null || rebel == null || !loaded()) return 0;
        int count = 0;
        try {
            for (IColony c : IColonyManager.getInstance().getAllColonies()) {
                IPermissions perms = c.getPermissions();
                if (perms == null || !exLiege.equals(perms.getOwner())) continue;
                Rank hostile = perms.getRankHostile();
                if (hostile == null) continue;
                if (perms.setPlayerRank(rebel, hostile, c.getWorld())) count++;
            }
        } catch (Throwable t) {
            WarNNobility.LOGGER.warn("[ColonyHostility] could not set a renouncer hostile (MineColonies API drift); "
                    + "the renounce still stands.", t);
        }
        return count;
    }
}
