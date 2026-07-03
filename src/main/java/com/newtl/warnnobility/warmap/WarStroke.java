package com.newtl.warnnobility.warmap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/**
 * One mark on the War Table. All geometry is stored in WORLD coordinates ({@code x,z} block-space),
 * not screen pixels, so a mark stays glued to the terrain it was drawn over when the board is panned,
 * zoomed, or (on a table) rotated. {@link #pts} is a flat array {@code [x0,z0, x1,z1, ...]}.
 *
 * <p>The {@link #color} is the drawing officer's chosen pen colour (0xRRGGBB); {@link #author} lets the
 * board show who marked what. {@link #label} carries the text for {@link MapTool#isPlaced()} tools.
 */
public final class WarStroke {

    /** Default brush weight for strokes saved before the size slider existed (and the slider's mid value). */
    public static final int DEFAULT_SIZE = 2;

    public final MapTool tool;
    public final int color;       // 0xRRGGBB
    public final UUID author;
    public final String label;    // "" unless the tool is a placed/text mark
    public final double[] pts;    // flat world coords: x0,z0,x1,z1,...
    public final int size;        // universal brush weight: line thickness / text font scale (1..6)

    public WarStroke(MapTool tool, int color, UUID author, String label, double[] pts) {
        this(tool, color, author, label, pts, DEFAULT_SIZE);
    }

    public WarStroke(MapTool tool, int color, UUID author, String label, double[] pts, int size) {
        this.tool = tool;
        this.color = color;
        this.author = author;
        this.label = label == null ? "" : label;
        this.pts = pts;
        this.size = size;
    }

    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        t.putInt("Tool", tool.ordinal());
        t.putInt("Color", color);
        t.putUUID("Author", author);
        if (!label.isEmpty()) t.putString("Label", label);
        t.putInt("Size", size);
        ListTag list = new ListTag();
        for (double v : pts) list.add(DoubleTag.valueOf(v));
        t.put("Pts", list);
        return t;
    }

    public static WarStroke fromNbt(CompoundTag t) {
        MapTool tool = MapTool.byId(t.getInt("Tool"));
        int color = t.getInt("Color");
        UUID author = t.hasUUID("Author") ? t.getUUID("Author") : new UUID(0L, 0L);
        String label = t.getString("Label");
        int size = t.contains("Size") ? t.getInt("Size") : DEFAULT_SIZE;
        ListTag list = t.getList("Pts", Tag.TAG_DOUBLE);
        double[] pts = new double[list.size()];
        for (int i = 0; i < pts.length; i++) pts[i] = list.getDouble(i);
        return new WarStroke(tool, color, author, label, pts, size);
    }
}
