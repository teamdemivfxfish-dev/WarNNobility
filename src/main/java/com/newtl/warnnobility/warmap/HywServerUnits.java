package com.newtl.warnnobility.warmap;

import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Server-side reflection seam into Hundred Years War's combat units, mirroring the client
 * {@link com.newtl.warnnobility.warmap.client.f4.HywF4Bridge} but with no client references so it is safe
 * on a dedicated server. HYW is All-Rights-Reserved with no API, so we reach {@code BaseCombatEntity} by
 * reflection and fail soft: if HYW is absent or its shape changes, {@link #isCombatUnit} returns false and
 * the scanner simply finds no HYW units.
 */
public final class HywServerUnits {

    private HywServerUnits() {}

    private static final boolean LOADED = ModList.get().isLoaded("hundred_years_war");
    private static boolean broken = false, init = false;
    private static Class<?> baseCombat;
    private static Method mOwnerUUID;         // BaseCombatEntity#getOwnerUUID()
    private static Method mFormationGroupId;  // BaseCombatEntity#getFormationGroupId()
    private static Method mGetSquads;         // SelectionSystem.getSquads() : Map<UUID, List<Squad>>
    private static Method mSquadUUIDs;        // SelectionSystem$Squad#getEntityUUIDs() : List<UUID>

    private static synchronized void ensure() {
        if (init || broken || !LOADED) return;
        try {
            baseCombat = Class.forName("ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity");
            mOwnerUUID = baseCombat.getMethod("getOwnerUUID");
            try { mFormationGroupId = baseCombat.getMethod("getFormationGroupId"); }
            catch (Throwable ignored) { mFormationGroupId = null; }
            // Squads (HYW's Ctrl+number control groups) are the player's "companies"; best-effort.
            try {
                Class<?> ss = Class.forName("ydmsama.hundred_years_war.main.selection.SelectionSystem");
                mGetSquads = ss.getMethod("getSquads");
                Class<?> sq = Class.forName("ydmsama.hundred_years_war.main.selection.SelectionSystem$Squad");
                mSquadUUIDs = sq.getMethod("getEntityUUIDs");
            } catch (Throwable ignored) { mGetSquads = null; mSquadUUIDs = null; }
            init = true;
        } catch (Throwable t) {
            broken = true;
        }
    }

    /** True if the entity is an HYW combat unit (puppet/soldier). */
    public static boolean isCombatUnit(Entity e) {
        if (!LOADED || broken || e == null) return false;
        ensure();
        return !broken && baseCombat != null && baseCombat.isInstance(e);
    }

    /** The commanding player's UUID for this unit, or null if unowned / unavailable. */
    public static UUID ownerUUID(Entity e) {
        ensure();
        if (mOwnerUUID == null) return null;
        try { return mOwnerUUID.invoke(e) instanceof UUID u ? u : null; }
        catch (Throwable t) { return null; }
    }

    /** The unit's formation-group id (units in one formation share it), or null. Used to group soldiers. */
    public static UUID formationGroupId(Entity e) {
        ensure();
        if (mFormationGroupId == null) return null;
        try { return mFormationGroupId.invoke(e) instanceof UUID u ? u : null; }
        catch (Throwable t) { return null; }
    }

    /**
     * The index of the HYW squad (Ctrl+number control group) this unit belongs to, or -1 if none. Squads are
     * the player's "companies", so they take priority when grouping units on the War Frame. Read from HYW's
     * server-side {@code SelectionSystem.getSquads()} map, keyed by the unit's owner.
     */
    public static int squadIndex(Entity e) {
        ensure();
        if (mGetSquads == null || mSquadUUIDs == null) return -1;
        UUID owner = ownerUUID(e);
        if (owner == null) return -1;
        try {
            Object map = mGetSquads.invoke(null);
            if (!(map instanceof java.util.Map<?, ?> squads)) return -1;
            Object list = squads.get(owner);
            if (!(list instanceof java.util.List<?> squadList)) return -1;
            UUID id = e.getUUID();
            for (int i = 0; i < squadList.size(); i++) {
                if (mSquadUUIDs.invoke(squadList.get(i)) instanceof java.util.List<?> members && members.contains(id)) {
                    return i;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * Stable grouping key for a combat unit, shared by the scan (which sends it to the client as the unit's
     * identity) and the command handler (which re-derives it to re-find the same group). Priority: HYW squad
     * (the player's companies) &gt; formation group &gt; owner &gt; the individual entity. MUST stay identical
     * on both sides.
     */
    public static String groupKey(Entity e) {
        int sq = squadIndex(e);
        if (sq >= 0) return "S:" + ownerUUID(e) + ":" + sq;
        UUID f = formationGroupId(e);
        if (f != null) return "F:" + f;
        UUID o = ownerUUID(e);
        if (o != null) return "O:" + o;
        return "E:" + e.getId();
    }
}
