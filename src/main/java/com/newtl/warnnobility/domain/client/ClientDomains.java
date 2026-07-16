package com.newtl.warnnobility.domain.client;

import com.newtl.warnnobility.domain.DomainEngine;
import com.newtl.warnnobility.domain.net.DomainMapPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Client store: the latest per-chunk domain data + labels + which levels the player has toggled on. */
public final class ClientDomains {

    private ClientDomains() {}

    public static final int L_COLONIES = 0;
    public static final int L_COUNTIES = 1;
    public static final int L_DUCHIES = 2;
    public static final int L_KINGDOMS = 3;
    public static final int L_FACTIONS = 4;   // Easy Factions claims (separate layer)
    public static final int L_TERRAIN = 5;    // the real per-block terrain picture
    public static final int L_STRUCTURES = 6; // inked footprints / markers for discovered builds

    private static ResourceLocation dimension;
    private static Map<Long, int[]> data = new HashMap<>();
    private static List<DomainEngine.Label> labels = new ArrayList<>();
    private static Map<Long, int[]> factionData = new HashMap<>();   // [x,z,regionId,rgb]
    private static List<DomainEngine.Label> factionLabels = new ArrayList<>();
    private static List<DomainEngine.Label> colonyMarkers = new ArrayList<>();   // a town marker per colony
    private static final Set<Integer> enabled = new HashSet<>(Set.of(L_COUNTIES, L_TERRAIN, L_STRUCTURES));
    private static int version = 0;   // bumped on any data or toggle change, so the overlay rebuilds

    public static void accept(DomainMapPayload msg) {
        dimension = msg.dimension();
        Map<Long, int[]> m = new HashMap<>();
        for (int[] e : msg.entries()) m.put(ChunkPos.asLong(e[0], e[1]), e);
        data = m;
        labels = msg.labels();
        colonyMarkers = msg.colonyMarkers();
        version++;
    }

    /** Easy Factions claims arrive separately (FactionMapPayload) so they are independent of MineColonies. */
    public static void acceptFactions(com.newtl.warnnobility.domain.net.FactionMapPayload msg) {
        Map<Long, int[]> fm = new HashMap<>();
        for (int[] e : msg.factionEntries()) fm.put(ChunkPos.asLong(e[0], e[1]), e);
        factionData = fm;
        factionLabels = msg.factionLabels();
        version++;
    }

    public static Map<Long, int[]> data() { return data; }

    public static List<DomainEngine.Label> labels() { return labels; }

    public static Map<Long, int[]> factionData() { return factionData; }

    public static List<DomainEngine.Label> factionLabels() { return factionLabels; }

    public static List<DomainEngine.Label> colonyMarkers() { return colonyMarkers; }

    public static int version() { return version; }

    public static boolean isEnabled(int level) { return enabled.contains(level); }

    /** Map-detail layers (terrain, structures) are about what the PAGE shows, not who owns what, so they sit
     *  outside the border layers' mutual exclusion and are never cleared by it. */
    private static boolean isMapDetail(int level) { return level == L_TERRAIN || level == L_STRUCTURES; }

    /**
     * Toggle a layer. The four nobility tiers (Colonies/Counties/Duchies/Kingdoms) can be on together --
     * the overlay renders them by hierarchy (highest tier wins per chunk). The Factions layer is a SEPARATE
     * claim system and is mutually exclusive with all four: turning Factions on clears the nobility tiers,
     * and turning any nobility tier on clears Factions. Map-detail layers are independent of all of that.
     */
    public static void toggle(int level) {
        boolean turningOn = !enabled.contains(level);
        if (isMapDetail(level)) {
            if (turningOn) enabled.add(level); else enabled.remove(level);
            version++;
            return;
        }
        // Border layers below. Remember the map-detail layers across the Factions clear, so switching to
        // faction borders never silently blanks the terrain out from under them.
        boolean terrain = enabled.contains(L_TERRAIN), structures = enabled.contains(L_STRUCTURES);
        if (level == L_FACTIONS) {
            enabled.clear();
            if (turningOn) enabled.add(L_FACTIONS);
        } else {
            enabled.remove(L_FACTIONS);
            if (turningOn) enabled.add(level); else enabled.remove(level);
        }
        if (terrain) enabled.add(L_TERRAIN);
        if (structures) enabled.add(L_STRUCTURES);
        version++;
    }

    /** region id for an entry at a level (0 = not in a region at that level). */
    public static int region(int[] e, int level) { return e[2 + level * 2]; }

    /** packed 0xRRGGBB colour for an entry at a level (-1 = none). */
    public static int rgb(int[] e, int level) { return e[3 + level * 2]; }
}
