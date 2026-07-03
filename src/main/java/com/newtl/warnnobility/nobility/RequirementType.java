package com.newtl.warnnobility.nobility;

/**
 * How a player qualifies to ENTER a given rank (i.e. the gate on the step into it).
 *
 * <ul>
 *   <li>{@link #COMMAND} - no condition; an op, NPC, or quest reward runs /nobility promote. This
 *       is how the early ranks work (Outsider -&gt; Artisan -&gt; Knight -&gt; Baron): the server
 *       decides, its own way, when a player joins the civilization, takes up a trade, is knighted
 *       after a boss, and is made a Baron. The mod just moves them rank A -&gt; rank B.</li>
 *   <li>{@link #SUPPORT} - needs {@code param} SAME-RANK peers to have pledged their seal. On the
 *       promotion those sealers become this noble's sworn, sentenced vassals (the County/Duchy/
 *       Kingdom forming up). Seals may be given freely or forced via a subjugation outcome.</li>
 *   <li>{@link #BUYIN}   - the player must pay {@code param} of the configured buy-in item. Kept
 *       for config flexibility; not used by the default ladder.</li>
 *   <li>{@link #SCORE}   - the player's score must have reached {@code param}. Kept for config
 *       flexibility; the default ladder has no score gates and no war to earn score.</li>
 *   <li>{@link #CROWN}   - the King step. Like SUPPORT ({@code param} same-rank seals, i.e. Dukes),
 *       but ALSO gated: the claimant must lead an Easy Factions faction with enough claimed chunks
 *       and consume a Crown (crafted from a Shard of the Crown of Unification). The faction half is
 *       skipped if Easy Factions is absent, so the ladder still works standalone.</li>
 *   <li>{@link #HIGHEST} - never promoted manually; this is the Emperor seat. The FIRST King is
 *       crowned automatically; afterwards the throne only changes through a usurpation.</li>
 * </ul>
 */
public enum RequirementType {
    COMMAND,
    SUPPORT,
    BUYIN,
    SCORE,
    CROWN,
    HIGHEST;

    public static RequirementType parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return COMMAND;
        }
    }
}
