package com.newtl.warnnobility.atlas;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.BitSet;

/**
 * One discovered structure as it lives on the atlas: a stable id, its centre (block coords), the archetype +
 * materials line for the marker/tooltip, its worked-block size, its footprint box, and the footprint mask
 * that lets the map ink its true outline instead of a generic pin. Shared by server (survey + save) and
 * client (marker + sketch); serialises to NBT (save) and to the sync buffer (client reveal).
 */
public final class Discovery {

    public final long id;
    public int x, z;                 // centre, block coords
    public int archetype;            // StructureArchetype ordinal
    public int size;                 // worked structural block count
    public String materials;         // short materials line for the tooltip
    public int minX, minZ, maxX, maxZ;   // footprint box (block coords)
    /** Built columns within the box, indexed {@code (x-minX) * depth() + (z-minZ)}; null = outline only. */
    public BitSet mask;

    public Discovery(long id, int x, int z, int archetype, int size, String materials,
                     int minX, int minZ, int maxX, int maxZ, BitSet mask) {
        this.id = id; this.x = x; this.z = z; this.archetype = archetype; this.size = size;
        this.materials = materials == null ? "" : materials;
        this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ;
        this.mask = mask;
    }

    public StructureArchetype arch() { return StructureArchetype.byOrdinal(archetype); }

    public int width() { return maxX - minX + 1; }

    public int depth() { return maxZ - minZ + 1; }

    /** Whether a world column inside this build's box is part of it. False outside the box or with no mask. */
    public boolean built(int worldX, int worldZ) {
        if (mask == null) return false;
        if (worldX < minX || worldX > maxX || worldZ < minZ || worldZ > maxZ) return false;
        return mask.get((worldX - minX) * depth() + (worldZ - minZ));
    }

    /**
     * Display name for the marker (size-qualified archetype). The thresholds count WORKED blocks only, so a
     * cottage lands in the low hundreds and a proper keep in the high ones.
     */
    public String name() {
        StructureArchetype a = arch();
        String q = size >= 400 ? "Great " : size <= 60 ? "Small " : "";
        // Farmstead/Depot/Outpost read oddly with "Great "; keep the qualifier for the built halls/keeps.
        if (a == StructureArchetype.FARMSTEAD || a == StructureArchetype.DEPOT || a == StructureArchetype.OUTPOST) q = "";
        return q + a.display;
    }

    public int rgb() { return arch().rgb; }

    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        t.putLong("id", id);
        t.putInt("x", x); t.putInt("z", z);
        t.putInt("a", archetype); t.putInt("s", size);
        t.putString("m", materials);
        t.putInt("x0", minX); t.putInt("z0", minZ); t.putInt("x1", maxX); t.putInt("z1", maxZ);
        if (mask != null) t.putByteArray("fp", mask.toByteArray());
        return t;
    }

    public static Discovery fromNbt(CompoundTag t) {
        BitSet mask = t.contains("fp") ? BitSet.valueOf(t.getByteArray("fp")) : null;
        return new Discovery(t.getLong("id"), t.getInt("x"), t.getInt("z"), t.getInt("a"), t.getInt("s"),
                t.getString("m"), t.getInt("x0"), t.getInt("z0"), t.getInt("x1"), t.getInt("z1"), mask);
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarLong(id);
        buf.writeVarInt(x); buf.writeVarInt(z);
        buf.writeVarInt(archetype); buf.writeVarInt(size);
        buf.writeUtf(materials);
        buf.writeVarInt(minX); buf.writeVarInt(minZ); buf.writeVarInt(maxX); buf.writeVarInt(maxZ);
        byte[] fp = mask == null ? new byte[0] : mask.toByteArray();
        buf.writeByteArray(fp);
    }

    public static Discovery read(RegistryFriendlyByteBuf buf) {
        long id = buf.readVarLong();
        int x = buf.readVarInt(), z = buf.readVarInt();
        int a = buf.readVarInt(), s = buf.readVarInt();
        String m = buf.readUtf();
        int x0 = buf.readVarInt(), z0 = buf.readVarInt(), x1 = buf.readVarInt(), z1 = buf.readVarInt();
        byte[] fp = buf.readByteArray();
        return new Discovery(id, x, z, a, s, m, x0, z0, x1, z1, fp.length == 0 ? null : BitSet.valueOf(fp));
    }
}
