package com.newtl.warnnobility.atlas;

/**
 * The kind of a discovered player build, chosen from the materials it is made of. Purely a
 * classification + presentation table (name, map tint) so the survey stays "smart" data, not a pile of
 * bespoke rules — {@link #classify} maps a material tally to one of these.
 *
 * <p>{@link Mat} is the material bucket every structural block is sorted into (by registry-path
 * substring, so it works for modded building blocks too). The tally of buckets, the diversity of
 * "civilisation" markers (crafting table, bed, furnace...), and the raw size pick the archetype.
 */
public enum StructureArchetype {
    HOMESTEAD("Timber Homestead", 0xB5824E),
    KEEP("Stone Keep", 0x9AA3B0),
    MANOR("Brick Manor", 0xC0563E),
    HALL("Sandstone Hall", 0xDCC98C),
    TEMPLE("Grand Temple", 0xEDE7DA),
    FARMSTEAD("Farmstead", 0x86A64F),
    DEPOT("Trade Depot", 0xB37F35),
    OUTPOST("Outpost", 0x948E80);

    /** Material buckets a structural block is sorted into; ordinal indexes the tally array. */
    public enum Mat { WOOD, STONE, BRICK, SANDSTONE, ORNATE, FARM, STORAGE, METAL }

    public final String display;
    public final int rgb;

    StructureArchetype(String display, int rgb) {
        this.display = display;
        this.rgb = rgb;
    }

    /**
     * Pick an archetype from a build's material tally ({@code counts} indexed by {@link Mat#ordinal()}),
     * how many distinct civilisation markers it has, and its structural block count.
     */
    public static StructureArchetype classify(int[] counts, int civDiversity, int size) {
        int wood = counts[Mat.WOOD.ordinal()];
        int stone = counts[Mat.STONE.ordinal()];
        int brick = counts[Mat.BRICK.ordinal()];
        int sand = counts[Mat.SANDSTONE.ordinal()];
        int ornate = counts[Mat.ORNATE.ordinal()];
        int farm = counts[Mat.FARM.ordinal()];
        int storage = counts[Mat.STORAGE.ordinal()];
        int metal = counts[Mat.METAL.ordinal()];
        int build = wood + stone + brick + sand + ornate;

        // Special uses read from function, not just the dominant wall material.
        if (farm > 0 && farm >= build) return FARMSTEAD;
        if (storage + metal > 0 && storage + metal >= build && storage + metal >= 16) return DEPOT;
        if (build <= 0) return OUTPOST;

        // Otherwise the dominant wall material names the place.
        int best = wood; StructureArchetype pick = HOMESTEAD;
        if (stone > best) { best = stone; pick = KEEP; }
        if (brick > best) { best = brick; pick = MANOR; }
        if (sand > best) { best = sand; pick = HALL; }
        if (ornate > best) { best = ornate; pick = TEMPLE; }
        return pick;
    }

    private static final String[] MAT_WORD =
            {"wood", "stone", "brick", "sandstone", "ornate stone", "farmland", "storage", "metal"};

    /** A short human materials line ("stone, oak, glass"-style) = the top 3 buckets present, for tooltips. */
    public static String materialsLine(int[] counts) {
        // simple top-3 by count
        Integer[] idx = {0, 1, 2, 3, 4, 5, 6, 7};
        java.util.Arrays.sort(idx, (a, b) -> Integer.compare(counts[b], counts[a]));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i : idx) {
            if (counts[i] <= 0 || shown >= 3) continue;
            if (shown++ > 0) sb.append(", ");
            sb.append(MAT_WORD[i]);
        }
        return sb.length() == 0 ? "mixed" : sb.toString();
    }

    public static StructureArchetype byOrdinal(int o) {
        StructureArchetype[] v = values();
        return (o < 0 || o >= v.length) ? OUTPOST : v[o];
    }
}
