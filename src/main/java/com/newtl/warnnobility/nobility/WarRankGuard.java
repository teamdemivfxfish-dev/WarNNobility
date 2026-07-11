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
        if (!Config.PROTECT_HIGHER_RANK_FROM_WAR.get()) return null;
        return higherRankRejection(attacker, targetColony, "make war upon");
    }

    /**
     * Besiege equivalent of {@link #rejection}: a noble may not lay siege to a strictly higher-ranked
     * colony owner either. War 'n Taxes treats besieging as the way to challenge a stronger power, but
     * under the noble ladder no seal is won by it, so War 'n Nobility forbids it at the source.
     *
     * @return a rejection message if {@code attacker} may NOT besiege {@code targetColony}; {@code null} otherwise.
     */
    @Nullable
    public static String besiegeRejection(ServerPlayer attacker, IColony targetColony) {
        if (!Config.PROTECT_HIGHER_RANK_FROM_BESIEGE.get()) return null;
        String base = higherRankRejection(attacker, targetColony, "lay siege to");
        return base == null ? null : base + " You would gain no seal by it.";
    }

    /**
     * Shared feudal check: is {@code targetColony}'s owner strictly higher ranked than {@code attacker}?
     * {@code verbPhrase} tailors the message ("make war upon", "lay siege to"). Fail-soft: any missing
     * data, equal/lower rank, or error returns {@code null} (the action is allowed).
     */
    @Nullable
    private static String higherRankRejection(ServerPlayer attacker, IColony targetColony, String verbPhrase) {
        try {
            if (attacker == null || targetColony == null) return null;
            UUID targetOwner = targetColony.getPermissions().getOwner();
            if (targetOwner == null || targetOwner.equals(attacker.getUUID())) return null;   // no self-action
            MinecraftServer server = attacker.getServer();
            if (server == null) return null;

            NobilityManager mgr = NobilityManager.get(server);
            NobleData atk = mgr.peek(attacker.getUUID());
            NobleData def = mgr.peek(targetOwner);
            int atkRank = atk != null ? atk.rankIndex : 0;
            int defRank = def != null ? def.rankIndex : 0;
            if (defRank <= atkRank) return null;   // equal or lower rank: allowed

            String defTitle = def != null ? mgr.rankName(def) : "noble";
            String atkTitle = atk != null ? mgr.rankName(atk) : "commoner";
            return mgr.nameOf(targetOwner) + " outranks you — a " + defTitle + " stands above a "
                    + atkTitle + ". You may not " + verbPhrase + " your betters.";
        } catch (Throwable t) {
            return null;   // never block an action because of our own error
        }
    }
}
