package com.newtl.warnnobility.atlas;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-dimension store of discovered player structures, plus which player has revealed which (the atlas
 * fog-of-war). Persisted with the world so a mapped realm survives restart. Surveyed-chunk bookkeeping is
 * kept in memory only — re-surveying after a restart just re-finds the same builds and merges them.
 */
public final class DiscoveryData extends SavedData {

    private static final String NAME = "warnnobility_discoveries";

    /**
     * Bumped when a change makes previously-saved discoveries wrong rather than merely stale, so they are
     * dropped on load instead of lingering forever.
     *
     * <p>v2: the surveyor used to bucket raw stone, deepslate and tree logs as building material and read 18
     * blocks down from the surface, so it was tallying the natural ground under every build. Every land
     * column came back "built", whole landscapes flooded into one component, and the stone in the earth
     * outvoted the actual walls: the map filled with identical "Great Stone Keep" pins sitting on empty
     * forest. Those entries describe terrain, not buildings, and nothing short of discarding them fixes a
     * saved world — a re-survey only updates a discovery it can still match, so the bogus ones would sit
     * there for good.
     */
    private static final int DATA_VERSION = 2;
    /** Re-scans of the same building land within this many blocks of its last centre, so they update it
     *  in place instead of spawning a duplicate. Distinct nearby builds stay distinct (cluster on display). */
    private static final int MERGE_DIST = 12;

    private final Map<Long, Discovery> discoveries = new HashMap<>();
    private final Map<UUID, Set<Long>> known = new HashMap<>();
    private long nextId = 1L;
    /** chunkKey -> gametime last surveyed (transient). */
    private final Map<Long, Long> surveyed = new HashMap<>();

    public static DiscoveryData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DiscoveryData::new, DiscoveryData::load, null), NAME);
    }

    public Collection<Discovery> all() { return discoveries.values(); }

    // --- surveying -------------------------------------------------------------------------------

    public boolean chunkNeedsSurvey(int chunkX, int chunkZ, long gameTime, long cooldownTicks) {
        Long t = surveyed.get(chunkKey(chunkX, chunkZ));
        return t == null || gameTime - t >= cooldownTicks;
    }

    public void markChunkSurveyed(int chunkX, int chunkZ, long gameTime) {
        surveyed.put(chunkKey(chunkX, chunkZ), gameTime);
    }

    /**
     * Fold a freshly-scanned building into the store: update the existing discovery it belongs to, or add a
     * new one. Returns the discovery (its id is stable across re-surveys), or null if unchanged enough to
     * skip a resync.
     */
    public Discovery addOrMerge(StructureSurveyor.Building b) {
        StructureArchetype a = StructureArchetype.classify(b.mat, b.civ, b.size);
        String materials = StructureArchetype.materialsLine(b.mat);
        Discovery match = null;
        for (Discovery d : discoveries.values()) {
            boolean near = Math.abs(d.x - b.cx) <= MERGE_DIST && Math.abs(d.z - b.cz) <= MERGE_DIST;
            boolean overlap = b.minX <= d.maxX && b.maxX >= d.minX && b.minZ <= d.maxZ && b.maxZ >= d.minZ;
            if (near || overlap) { match = d; break; }
        }
        if (match != null) {
            boolean changed = match.archetype != a.ordinal() || Math.abs(match.size - b.size) > 8
                    || Math.abs(match.x - b.cx) > 2 || Math.abs(match.z - b.cz) > 2;
            match.x = b.cx; match.z = b.cz; match.archetype = a.ordinal(); match.size = b.size;
            match.materials = materials;
            match.minX = b.minX; match.minZ = b.minZ; match.maxX = b.maxX; match.maxZ = b.maxZ;
            match.mask = b.mask;
            if (changed) setDirty();
            return changed ? match : null;
        }
        long id = nextId++;
        Discovery d = new Discovery(id, b.cx, b.cz, a.ordinal(), b.size, materials,
                b.minX, b.minZ, b.maxX, b.maxZ, b.mask);
        discoveries.put(id, d);
        setDirty();
        return d;
    }

    // --- reveal (fog of war) ---------------------------------------------------------------------

    /** Reveal to a player every discovery within {@code radius} blocks of {@code (px,pz)}; return true if any
     *  were newly revealed (so the caller resyncs them). */
    public boolean revealNear(UUID player, int px, int pz, int radius) {
        Set<Long> set = known.computeIfAbsent(player, k -> new HashSet<>());
        long r2 = (long) radius * radius;
        boolean added = false;
        for (Discovery d : discoveries.values()) {
            long dx = d.x - px, dz = d.z - pz;
            if (dx * dx + dz * dz <= r2 && set.add(d.id)) added = true;
        }
        if (added) setDirty();
        return added;
    }

    public List<Discovery> knownTo(UUID player) {
        Set<Long> set = known.get(player);
        if (set == null || set.isEmpty()) return List.of();
        List<Discovery> out = new ArrayList<>(set.size());
        for (long id : set) { Discovery d = discoveries.get(id); if (d != null) out.add(d); }
        return out;
    }

    private static long chunkKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    // --- persistence -----------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("Version", DATA_VERSION);
        tag.putLong("NextId", nextId);
        ListTag list = new ListTag();
        for (Discovery d : discoveries.values()) list.add(d.toNbt());
        tag.put("Discoveries", list);
        ListTag knownList = new ListTag();
        for (Map.Entry<UUID, Set<Long>> e : known.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag k = new CompoundTag();
            k.putUUID("p", e.getKey());
            long[] ids = e.getValue().stream().mapToLong(Long::longValue).toArray();
            k.putLongArray("ids", ids);
            knownList.add(k);
        }
        tag.put("Known", knownList);
        return tag;
    }

    public static DiscoveryData load(CompoundTag tag, HolderLookup.Provider provider) {
        DiscoveryData d = new DiscoveryData();
        // Anything written by a version that mistook the ground for architecture is discarded wholesale; the
        // world is simply re-surveyed from scratch as players move, which costs one pass and tells the truth.
        if (tag.getInt("Version") < DATA_VERSION) {
            d.setDirty();
            return d;
        }
        d.nextId = Math.max(1L, tag.getLong("NextId"));
        ListTag list = tag.getList("Discoveries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Discovery disc = Discovery.fromNbt(list.getCompound(i));
            d.discoveries.put(disc.id, disc);
            if (disc.id >= d.nextId) d.nextId = disc.id + 1;
        }
        ListTag knownList = tag.getList("Known", Tag.TAG_COMPOUND);
        for (int i = 0; i < knownList.size(); i++) {
            CompoundTag k = knownList.getCompound(i);
            Set<Long> set = new HashSet<>();
            for (long id : k.getLongArray("ids")) set.add(id);
            d.known.put(k.getUUID("p"), set);
        }
        return d;
    }
}
