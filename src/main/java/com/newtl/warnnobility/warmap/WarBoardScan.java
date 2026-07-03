package com.newtl.warnnobility.warmap;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.newtl.warnnobility.integration.FactionGate;
import com.newtl.warnnobility.warmap.net.WarMapNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side sweep that feeds a War Frame its live overlay: every HYW combat unit and MineColonies
 * colonist in the board's mapped area, grouped and tagged by allegiance, pushed to nearby clients via the
 * {@code Units} payload (which {@link com.newtl.warnnobility.warmap.client.ClientUnits} then draws/bakes).
 *
 * <p>HYW units are grouped by formation (fallback: owner, then individual) into one banner each; colonists
 * are plain civilian dots. Allegiance is <b>owner + Easy Factions</b> relative to the board's current
 * controller (the physical board is one shared image, so it is anchored to whoever holds the floor); with
 * no controller everything reads as "other". HYW/EF are reached fail-soft, so this degrades to colonists
 * only (or nothing) when those mods are absent.
 */
public final class WarBoardScan {

    private WarBoardScan() {}

    private static final double RADIUS = 480.0;
    private static final int MAX_UNITS = 200, MAX_DOTS = 500;
    private static final int C_FRIENDLY = 0x4A90E0, C_HOSTILE = 0xD84040, C_CITIZEN = 0xCFC8B8;

    public static void scanAndSend(ServerLevel level, BlockPos anchor, BlockPos centre, WarMapData data) {
        MinecraftServer server = level.getServer();
        double cx = data.centerX, cz = data.centerZ;
        AABB box = new AABB(cx - RADIUS, level.getMinBuildHeight(), cz - RADIUS,
                cx + RADIUS, level.getMaxBuildHeight(), cz + RADIUS);
        UUID controller = data.allegianceAnchor();   // live holder, else the last one, so units stay friendly
                                                      // after you step back from the board (no floor lock)

        List<LivingEntity> living = level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive);

        Map<String, double[]> acc = new HashMap<>();      // group key -> [sumX, sumZ, count]
        Map<String, UUID> owner = new HashMap<>();        // group key -> commanding player
        Map<String, LivingEntity> sample = new HashMap<>();
        List<WarMapNetwork.Units.DotEntry> dots = new ArrayList<>();

        for (LivingEntity e : living) {
            if (HywServerUnits.isCombatUnit(e)) {
                UUID o = HywServerUnits.ownerUUID(e);
                String key = HywServerUnits.groupKey(e);
                double[] a = acc.computeIfAbsent(key, k -> new double[3]);
                a[0] += e.getX(); a[1] += e.getZ(); a[2] += 1;
                if (o != null) owner.putIfAbsent(key, o);
                sample.putIfAbsent(key, e);
            } else if (e instanceof AbstractEntityCitizen && dots.size() < MAX_DOTS) {
                dots.add(new WarMapNetwork.Units.DotEntry(e.getX(), e.getZ(), C_CITIZEN));
            }
        }

        List<WarMapNetwork.Units.UnitEntry> units = new ArrayList<>();
        for (Map.Entry<String, double[]> en : acc.entrySet()) {
            if (units.size() >= MAX_UNITS) break;
            double[] a = en.getValue();
            int count = (int) a[2];
            if (count <= 0) continue;
            String key = en.getKey();
            UUID o = owner.get(key);
            boolean friendly = controller != null && o != null && FactionGate.sameFaction(server, o, controller);
            units.add(new WarMapNetwork.Units.UnitEntry(
                    key, unitName(server, key, o, sample.get(key)),
                    a[0] / count, a[1] / count, count,
                    friendly ? C_FRIENDLY : C_HOSTILE, friendly));
        }

        WarMapNetwork.sendUnitsNear(level, centre, new WarMapNetwork.Units(anchor, units, dots));
    }

    /**
     * Collect the live HYW combat units that make up one on-map unit (the group whose {@link
     * HywServerUnits#groupKey} equals {@code key}), for a wall-map order. Only units the commander may
     * lawfully command are returned: owned by them or an ally (Easy Factions), never wild/enemy troops.
     * Uses the same board area + key derivation as {@link #scanAndSend}, so what you see is what you command.
     */
    public static List<LivingEntity> collectGroup(ServerLevel level, WarMapData data, String key, UUID commander) {
        double cx = data.centerX, cz = data.centerZ;
        AABB box = new AABB(cx - RADIUS, level.getMinBuildHeight(), cz - RADIUS,
                cx + RADIUS, level.getMaxBuildHeight(), cz + RADIUS);
        MinecraftServer server = level.getServer();
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (!HywServerUnits.isCombatUnit(e)) continue;
            if (!key.equals(HywServerUnits.groupKey(e))) continue;
            UUID o = HywServerUnits.ownerUUID(e);
            if (o == null || !FactionGate.sameFaction(server, o, commander)) continue; // only your own / allied troops
            out.add(e);
        }
        return out;
    }

    private static String unitName(MinecraftServer server, String key, UUID owner, LivingEntity sample) {
        // Squad key "S:<owner>:<idx>" -> a numbered company, so each HYW squad reads as its own banner.
        if (key != null && key.startsWith("S:")) {
            int idx = 0;
            try { idx = Integer.parseInt(key.substring(key.lastIndexOf(':') + 1)); } catch (Exception ignored) {}
            return "Company " + (idx + 1);
        }
        if (owner != null && server != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            if (p != null) return p.getGameProfile().getName();
            return "Company";
        }
        return sample != null ? sample.getType().getDescription().getString() : "Force";
    }
}
