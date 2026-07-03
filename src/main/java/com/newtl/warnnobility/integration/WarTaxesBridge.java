package com.newtl.warnnobility.integration;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.newtl.warnnobility.Config;
import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.nobility.NobilityManager;
import net.machiavelli.minecolonytax.vassalization.VassalManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The seam to Machiavelli's "War 'n Taxes" (its @Mod id is {@code minecolonytax}, NOT "warntaxes").
 *
 * <p>War 'n Taxes owns the actual wars and colony vassalization; this mod owns the noble ladder. The
 * bridge runs one way: it watches War 'n Taxes' {@code VassalManager} and mirrors each NEW colony
 * vassalization into a forced seal here ({@link NobilityManager#subjugate}), so that winning a
 * vassalization war there is how you collect the same-rank seals that promote you (Count/Duke/King).
 *
 * <p>There is no list-all-vassals call in War 'n Taxes, so we enumerate MineColonies colonies and
 * probe each one. We map a colony to its owner UUID; the overlord UUID is the victor. The seal's lock
 * is pinned to War 'n Taxes' own {@code getRemainingVassalizationHours}, and when that vassalization
 * ends we release the bond. Many War 'n Taxes vassalizations are between non-peers and simply will not
 * map to a legal ladder seal; those are recorded as rejected and skipped quietly.
 *
 * <p>Every reference to a War 'n Taxes / MineColonies class lives in {@link #poll}, which only runs
 * once {@link #active()} confirms both mods are loaded, so a missing jar never links this class.
 */
public final class WarTaxesBridge {

    /** War 'n Taxes' actual mod id. The displayName is "WarNTaxes" but the @Mod id is this. */
    public static final String MODID = "minecolonytax";

    /** colonyId -> overlord UUID we have already turned into a forced seal (ingested once). */
    private static final Map<Integer, UUID> ingested = new HashMap<>();
    /** colonyId -> overlord UUID the ladder refused (non-peers); don't retry or re-log it. */
    private static final Map<Integer, UUID> rejected = new HashMap<>();

    /** Set if the War 'n Taxes API throws unexpectedly; disables the bridge for the session. */
    private static boolean broken = false;
    private static int tickCounter = 0;

    private WarTaxesBridge() {}

    /** True only when enabled in config AND both War 'n Taxes and MineColonies are installed. */
    public static boolean active() {
        return !broken && Config.TRIBUTE_ENABLED.get()
                && ModList.get().isLoaded(MODID) && ModList.get().isLoaded("minecolonies");
    }

    /** Server-tick entry; throttles itself to the configured poll interval. */
    public static void onServerTick(MinecraftServer server) {
        if (!active()) return;
        if (++tickCounter < Math.max(1, Config.TRIBUTE_POLL_SECONDS.get()) * 20) return;
        tickCounter = 0;
        try {
            poll(server);
        } catch (Throwable t) {
            broken = true;
            WarNNobility.LOGGER.error("[WarTaxesBridge] disabled for this session after a War 'n Taxes "
                    + "API error; nobility keeps running standalone.", t);
        }
    }

    private static void poll(MinecraftServer server) {
        NobilityManager mgr = NobilityManager.get(server);
        long now = System.currentTimeMillis();

        // overlord -> the colony ids that are still PROVISIONAL seals of theirs (for the book "(Provisional)" tag)
        Map<UUID, java.util.List<Integer>> provisional = new HashMap<>();

        for (IColony c : IColonyManager.getInstance().getAllColonies()) {
            int id = c.getID();
            UUID owner = c.getPermissions().getOwner();

            if (VassalManager.isColonyVassal(id)) {
                UUID ov = VassalManager.getVassalOverlordUUID(id);
                if (ov != null && owner != null && mgr.isProvisionalSeal(ov, owner))
                    provisional.computeIfAbsent(ov, k -> new java.util.ArrayList<>()).add(id);
            }

            if (!VassalManager.isColonyVassal(id)) {
                // No longer a War 'n Taxes vassal: release any bond we made, then forget it so a
                // future war re-fires. rejected is cleared too, so a re-conquest gets a fresh look.
                UUID prior = ingested.remove(id);
                rejected.remove(id);
                if (prior != null && owner != null) {
                    NobilityManager.Result r = mgr.releaseWnTBond(prior, owner);
                    if (r != null) broadcast(server, r.message());
                }
                continue;
            }

            UUID overlord = VassalManager.getVassalOverlordUUID(id);
            if (overlord == null || owner == null || overlord.equals(owner)) continue;

            // Keep our "cannot break free" window pinned to War 'n Taxes' own remaining time.
            long until = now + Math.max(0L, (long) VassalManager.getRemainingVassalizationHours(id)) * 3_600_000L;

            if (overlord.equals(ingested.get(id))) {
                mgr.setWnTSentence(overlord, owner, until);     // already ours; just refresh the timer
                continue;
            }
            if (overlord.equals(rejected.get(id))) continue;        // already refused this exact pairing

            NobilityManager.Result r = mgr.subjugate(overlord, owner);
            if (r.success()) {
                ingested.put(id, overlord);
                rejected.remove(id);
                mgr.setWnTSentence(overlord, owner, until);
                broadcast(server, r.message());
            } else {
                rejected.put(id, overlord);                         // non-peer / structural: stop retrying
                WarNNobility.LOGGER.debug("[WarTaxesBridge] {} not mirrored: {}", c.getName(), r.message());
            }
        }

        // Push each online player their provisional-seal colony ids (empty clears the tags) so the tax
        // book can mark those rows "(Provisional)". Cheap: one tiny varint list per player per poll.
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            com.newtl.warnnobility.net.Network.toClient(p,
                    new com.newtl.warnnobility.net.VassalStatusMsg(
                            provisional.getOrDefault(p.getUUID(), java.util.List.of())));
        }
    }

    private static void broadcast(MinecraftServer server, String msg) {
        WarNNobility.LOGGER.info("[WarTaxesBridge] {}", msg);
        server.getPlayerList().broadcastSystemMessage(
                Component.literal(msg).withStyle(ChatFormatting.GOLD), false);
    }
}
