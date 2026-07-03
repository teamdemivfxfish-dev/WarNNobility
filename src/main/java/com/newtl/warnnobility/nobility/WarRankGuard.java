package com.newtl.warnnobility.nobility;

import com.minecolonies.api.colony.IColony;
import com.newtl.warnnobility.Config;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Feudal gate on War 'n Taxes war declarations: a noble may not make war upon their betters. Called from
 * {@link com.newtl.warnnobility.mixin.WarSystemMixin} at the moment a war is requested; if the target
 * colony's owner outranks the declarer, the declaration is refused with an explanation. This closes the
 * seam where a lesser noble could otherwise win a War 'n Taxes war against a superior (which our ladder
 * refuses to honour anyway) — now it can't even be started.
 *
 * <p>Fail-soft: a disabled config, a missing/equal rank, or any error returns {@code null} (war allowed),
 * so this never wrongly blocks a legal war.
 */
public final class WarRankGuard {

    private WarRankGuard() {}

    /**
     * @return a rejection message if {@code attacker} may NOT wage war on {@code targetColony} because its
     *         owner holds a strictly higher nobility rank; {@code null} if the war is allowed.
     */
    @Nullable
    public static String rejection(ServerPlayer attacker, IColony targetColony) {
        try {
            if (!Config.PROTECT_HIGHER_RANK_FROM_WAR.get()) return null;
            if (attacker == null || targetColony == null) return null;
            UUID targetOwner = targetColony.getPermissions().getOwner();
            if (targetOwner == null || targetOwner.equals(attacker.getUUID())) return null;   // no self-war
            MinecraftServer server = attacker.getServer();
            if (server == null) return null;

            NobilityManager mgr = NobilityManager.get(server);
            NobleData atk = mgr.peek(attacker.getUUID());
            NobleData def = mgr.peek(targetOwner);
            int atkRank = atk != null ? atk.rankIndex : 0;
            int defRank = def != null ? def.rankIndex : 0;
            if (defRank <= atkRank) return null;   // equal or lower rank: war is allowed

            String defTitle = def != null ? mgr.rankName(def) : "noble";
            String atkTitle = atk != null ? mgr.rankName(atk) : "commoner";
            return mgr.nameOf(targetOwner) + " outranks you — a " + defTitle + " stands above a "
                    + atkTitle + ". You may not make war upon your betters.";
        } catch (Throwable t) {
            return null;   // never block a war because of our own error
        }
    }
}
