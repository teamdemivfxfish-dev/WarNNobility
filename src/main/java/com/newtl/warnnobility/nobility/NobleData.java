package com.newtl.warnnobility.nobility;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Per-player nobility record. Pure data plus NBT, owned by {@link NobilityManager}. */
public class NobleData {

    public final UUID id;
    public String name = "?";          // last-known name, for display of lieges/vassals

    public int rankIndex = 0;
    public int score = 0;              // dormant by default; no war in this mod to earn it

    /** Identity, so the title shows its feminine form (Baroness, Queen...). */
    public boolean female = false;

    /** Name a Count/Duke/King gives their domain (County/Duchy/Kingdom). Blank = unnamed. */
    public String domainName = "";

    // ---- fealty (whom this noble serves) ------------------------------------

    @Nullable public UUID liege;       // who this noble is sworn to
    /** Epoch millis until which this noble is BOUND to its liege (the sentence). 0 = not bound. */
    public long liegeUntil = 0L;

    // ---- power (who serves this noble) --------------------------------------

    public final Set<UUID> vassals = new LinkedHashSet<>();      // sworn, sentenced followers
    public final Set<UUID> supporters = new LinkedHashSet<>();   // peers who have pledged me their seal

    // ---- this noble's own outgoing seal -------------------------------------

    @Nullable public UUID pledgedTo;   // the peer whose claim this noble is backing (one at a time)
    /** True when the pledge was taken by CONQUEST and cannot be withdrawn until it is consumed. */
    public boolean pledgeForced = false;
    /**
     * Epoch millis until which a CONQUERED seal's subjugation sentence runs (mirrored from War 'n Taxes).
     * Held here while still a provisional seal so that, when the backer ascends and this seal converts into
     * full vassalage, the REMAINING sentence carries over into {@link #liegeUntil} instead of resetting.
     * 0 = no timed sentence (e.g. a voluntary seal).
     */
    public long pledgeUntil = 0L;

    public NobleData(UUID id) {
        this.id = id;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("name", name);
        tag.putInt("rank", rankIndex);
        tag.putInt("score", score);
        tag.putBoolean("female", female);
        tag.putString("domainName", domainName);
        if (liege != null) tag.putUUID("liege", liege);
        tag.putLong("liegeUntil", liegeUntil);
        tag.put("vassals", uuidList(vassals));
        tag.put("supporters", uuidList(supporters));
        if (pledgedTo != null) tag.putUUID("pledgedTo", pledgedTo);
        tag.putBoolean("pledgeForced", pledgeForced);
        tag.putLong("pledgeUntil", pledgeUntil);
        return tag;
    }

    public static NobleData load(CompoundTag tag) {
        NobleData d = new NobleData(tag.getUUID("id"));
        d.name = tag.getString("name");
        d.rankIndex = tag.getInt("rank");
        d.score = tag.getInt("score");
        d.female = tag.getBoolean("female");
        d.domainName = tag.getString("domainName");
        if (tag.hasUUID("liege")) d.liege = tag.getUUID("liege");
        d.liegeUntil = tag.getLong("liegeUntil");
        readUuids(tag.getList("vassals", Tag.TAG_INT_ARRAY), d.vassals);
        readUuids(tag.getList("supporters", Tag.TAG_INT_ARRAY), d.supporters);
        if (tag.hasUUID("pledgedTo")) d.pledgedTo = tag.getUUID("pledgedTo");
        d.pledgeForced = tag.getBoolean("pledgeForced");
        d.pledgeUntil = tag.getLong("pledgeUntil");
        return d;
    }

    private static ListTag uuidList(Set<UUID> set) {
        ListTag list = new ListTag();
        for (UUID u : set) list.add(net.minecraft.nbt.NbtUtils.createUUID(u));
        return list;
    }

    private static void readUuids(ListTag list, Set<UUID> into) {
        into.clear();
        for (int i = 0; i < list.size(); i++) {
            into.add(net.minecraft.nbt.NbtUtils.loadUUID(list.get(i)));
        }
    }
}
