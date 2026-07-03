package com.newtl.warnnobility.warmap;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The authoritative state of one assembled War Table, held only by the anchor block entity. Covers the
 * inserted atlas, the shared view (pan centre, zoom, table rotation), the single-controller "who holds
 * the marker" lock, and the persistent list of drawn marks. Serialised whole into the anchor BE's NBT,
 * so a finished briefing survives relog, restart, and everyone logging off.
 */
public final class WarMapData {

    /** Auto-release the marker after this many ticks with no drawing/pan action (30s). */
    public static final long IDLE_RELEASE_TICKS = 600L;
    /** The controller must stay within this many blocks of the board or the marker frees. */
    public static final double CONTROL_RANGE = 8.0;

    public static final int ZOOM_MIN = -10, ZOOM_MAX = 4;

    // --- inserted prop ---------------------------------------------------------------------------
    public ItemStack atlas = ItemStack.EMPTY;

    // --- shared view (all officers see the same board) -------------------------------------------
    public double centerX, centerZ;   // world block coords shown at the board centre
    public int zoom = 0;              // ZOOM_MIN..ZOOM_MAX, derived into AA's mapScale at render time
    public int rotation = 0;         // 0..3 quarter-turns, table/floor only

    // --- the marker (control lock) ---------------------------------------------------------------
    public UUID controller = null;
    public long controllerActiveTick = 0L;
    // The last player to hold the floor. Allegiance colouring anchors to this when nobody currently holds
    // the marker, so units don't all flash red the moment you step back (>CONTROL_RANGE) to view the board.
    public UUID lastController = null;

    // --- the plan ---------------------------------------------------------------------------------
    public final List<WarStroke> strokes = new ArrayList<>();

    // Whether the board surfaces live field units/colonists. Off = "just a map" (e.g. showing off a town).
    public boolean showUnits = true;

    public boolean hasAtlas() {
        return !atlas.isEmpty();
    }

    public boolean controlledBy(UUID who) {
        return controller != null && controller.equals(who);
    }

    /** Who the board's allegiance colouring is judged against: the live controller, else the last holder. */
    public UUID allegianceAnchor() {
        return controller != null ? controller : lastController;
    }

    public void clampView() {
        if (zoom < ZOOM_MIN) zoom = ZOOM_MIN;
        if (zoom > ZOOM_MAX) zoom = ZOOM_MAX;
        rotation &= 3;
    }

    public CompoundTag toNbt(HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        if (hasAtlas()) t.put("Atlas", atlas.saveOptional(registries));
        t.putDouble("CenterX", centerX);
        t.putDouble("CenterZ", centerZ);
        t.putInt("Zoom", zoom);
        t.putInt("Rotation", rotation);
        t.putBoolean("ShowUnits", showUnits);
        if (controller != null) {
            t.putUUID("Controller", controller);
            t.putLong("ControllerTick", controllerActiveTick);
        }
        if (lastController != null) t.putUUID("LastController", lastController);
        ListTag list = new ListTag();
        for (WarStroke s : strokes) list.add(s.toNbt());
        t.put("Strokes", list);
        return t;
    }

    public static WarMapData fromNbt(CompoundTag t, HolderLookup.Provider registries) {
        WarMapData d = new WarMapData();
        d.atlas = t.contains("Atlas")
                ? ItemStack.parseOptional(registries, t.getCompound("Atlas")) : ItemStack.EMPTY;
        d.centerX = t.getDouble("CenterX");
        d.centerZ = t.getDouble("CenterZ");
        d.zoom = t.getInt("Zoom");
        d.rotation = t.getInt("Rotation");
        d.showUnits = !t.contains("ShowUnits") || t.getBoolean("ShowUnits");
        if (t.hasUUID("Controller")) {
            d.controller = t.getUUID("Controller");
            d.controllerActiveTick = t.getLong("ControllerTick");
        }
        if (t.hasUUID("LastController")) d.lastController = t.getUUID("LastController");
        ListTag list = t.getList("Strokes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) d.strokes.add(WarStroke.fromNbt(list.getCompound(i)));
        d.clampView();
        return d;
    }
}
