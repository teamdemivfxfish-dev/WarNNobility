package com.newtl.warnnobility;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Server config (config/warnnobility-common.toml). EVERYTHING about the ladder is here, so
 * server owners can rename titles, reorder ranks, and retune thresholds without code.
 *
 * <p>This mod owns the PROGRESSION of nobility, not war. Wars (subjugation, usurpation) come from
 * an external system (admin/quest now, Machiavelli's War 'n Taxes once it is on 1.21.1); their
 * OUTCOMES are fed in through /nobility subjugate and /nobility emperor ... so the politics here
 * react to a war that some other system actually fought.
 */
public final class Config {

    public static final ModConfigSpec SPEC;

    /** The ladder, bottom to top. Each entry: {@code name;REQUIREMENT;param;entryScore}. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> RANKS;

    /** Minimum days a vassal is bound to a liege before it may break free (anti-cheese sentence). */
    public static final ModConfigSpec.IntValue VASSAL_SENTENCE_DAYS;

    /** Item paid for a BUYIN promotion. Kept for config flexibility; unused by the default ladder. */
    public static final ModConfigSpec.ConfigValue<String> BUYIN_ITEM;

    /** Master switch for the War 'n Taxes outcome bridge: mirror its vassalization wars into our seals. */
    public static final ModConfigSpec.BooleanValue TRIBUTE_ENABLED;

    /** How often (seconds) the bridge polls War 'n Taxes for new/ended vassalizations. */
    public static final ModConfigSpec.IntValue TRIBUTE_POLL_SECONDS;

    /** Block declaring a War 'n Taxes war on a colony whose owner outranks the declarer ("no war on betters"). */
    public static final ModConfigSpec.BooleanValue PROTECT_HIGHER_RANK_FROM_WAR;

    /** King (CROWN) gate: require leading an Easy Factions faction. Ignored if Easy Factions is absent. */
    public static final ModConfigSpec.BooleanValue KING_REQUIRES_FACTION;

    /** King (CROWN) gate: claimed-chunk threshold the claimant's faction must hold. */
    public static final ModConfigSpec.IntValue KING_FACTION_CLAIM_THRESHOLD;

    /** King (CROWN) gate: consume the Crown on coronation (vs merely require bearing it). */
    public static final ModConfigSpec.BooleanValue KING_CONSUME_CROWN;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("ranks");
        RANKS = b.comment(
                        "The nobility ladder, lowest first.",
                        "Full format per line: name;REQUIREMENT;param;entryScore;show;advanceText",
                        "Only the first four are required; show and advanceText are optional.",
                        "REQUIREMENT (how you ENTER this rank):",
                        "  COMMAND    = just /nobility promote (hook NPCs/quests/boss kills here). This",
                        "               is how the early ranks advance; the server decides its own way",
                        "               when a player joins, takes a trade, is knighted, is made a Baron.",
                        "  SUPPORT    = param SAME-RANK peers must pledge their seal to you; on promote",
                        "               those sealers become your sentenced vassals (County/Duchy/Kingdom)",
                        "  BUYIN      = pay param of the buyin_item, consumed on promote (unused default)",
                        "  SCORE      = your score must have reached param (unused default; no war here)",
                        "  CROWN      = the King step: param same-rank (Duke) seals AND lead an Easy Factions",
                        "               faction over the claim threshold AND consume a Crown (see config below)",
                        "  HIGHEST    = the Emperor seat. The FIRST King is crowned automatically; after",
                        "               that the throne only changes through a usurpation challenge.",
                        "entryScore = score you are set to on entering (negative = leave score alone).",
                        "show = which Chancery lines appear AT this rank, comma-separated from",
                        "       score,supporters,vassals,liege. Omit for smart auto-defaults;",
                        "       include it (even empty) to override, e.g. 'Knight;COMMAND;0;0;'.",
                        "advanceText = custom 'how to advance from here' text for a questline, e.g.:",
                        "       'Knight;COMMAND;0;0;;Slay every server boss, then an NPC elevates you.'",
                        "       (admins can also set this live with /nobility directive <rank> <text>).",
                        "femaleName = feminine form of the title (Baroness, Countess, Queen...); blank =",
                        "       reuse name. A player flips identity in the Chancery or via /nobility gender.",
                        "Rename titles freely; the mechanics follow the REQUIREMENT column, not the name.",
                        "Default ladder: Outsider -> Artisan -> Knight -> Baron -> Count -> Duke -> King -> Emperor.",
                        "  Count needs 2 Baron seals, Duke needs 2 Count seals, King needs 2 Duke seals.")
                .defineListAllowEmpty(List.of("ladder"),
                        () -> List.of(
                                "Outsider;COMMAND;0;0;;;Outsider",
                                "Artisan;COMMAND;0;0;;;Artisan",
                                "Knight;COMMAND;0;0;;;Dame",
                                "Baron;COMMAND;0;0;;;Baroness",
                                "Count;SUPPORT;2;0;;;Countess",
                                "Duke;SUPPORT;2;0;;;Duchess",
                                "King;CROWN;2;0;;;Queen",
                                "Emperor;HIGHEST;0;-1;;;Empress"),
                        o -> o instanceof String s && s.contains(";"));
        b.pop();

        b.push("vassalage");
        VASSAL_SENTENCE_DAYS = b.comment(
                        "Real-world days a vassal stays bound before it may break free. Stops players",
                        "from vassal-hopping to swing different lords' promotions. Forced (conquered)",
                        "vassals are bound for the same term once the conqueror ascends.")
                .defineInRange("vassal_sentence_days", 7, 0, 3650);
        b.pop();

        b.push("economy");
        BUYIN_ITEM = b.comment("Item consumed for a BUYIN promotion. Unused by the default ladder.")
                .define("buyin_item", "minecraft:emerald");
        b.pop();

        b.push("integration");
        TRIBUTE_ENABLED = b.comment(
                        "Bridge War 'n Taxes (modid 'minecolonytax') vassalization WARS into this mod: when a",
                        "colony is vassalized there, its owner is force-sealed to the victor here, so winning",
                        "wars is how you collect the same-rank seals that promote you (Count/Duke/King). The",
                        "seal lasts as long as the War 'n Taxes vassalization and releases when it ends. Needs",
                        "War 'n Taxes + MineColonies installed; harmless to leave true when they are absent.")
                .define("tribute_enabled", true);
        TRIBUTE_POLL_SECONDS = b.comment(
                        "How often (seconds) to poll War 'n Taxes for new or ended vassalizations.")
                .defineInRange("tribute_poll_seconds", 20, 1, 600);
        PROTECT_HIGHER_RANK_FROM_WAR = b.comment(
                        "Feudal protection: forbid declaring a War 'n Taxes war on a colony whose owner holds a",
                        "HIGHER nobility rank than the declarer ('you may not make war upon your betters'). The",
                        "would-be attacker is told why and the declaration is suppressed. Equal or lower rank is",
                        "unaffected. Needs War 'n Taxes installed (a client mixin on its war-declaration path);",
                        "harmless when it is absent. Set false to let anyone declare on anyone.")
                .define("protect_higher_rank_from_war", true);

        KING_REQUIRES_FACTION = b.comment(
                        "King (CROWN) gate: the claimant must LEAD (own) an Easy Factions faction. If Easy",
                        "Factions is not installed, this half of the gate is skipped and King needs only the",
                        "Duke seals + the Crown.")
                .define("king_requires_faction", true);
        KING_FACTION_CLAIM_THRESHOLD = b.comment(
                        "King (CROWN) gate: total claimed chunks the claimant's faction must hold.")
                .defineInRange("king_faction_claim_threshold", 50, 0, 1_000_000);
        KING_CONSUME_CROWN = b.comment(
                        "King (CROWN) gate: consume the Crown at coronation. False = the King just has to bear",
                        "one (it is not eaten), letting a single Crown crown several kings over time.")
                .define("king_consume_crown", true);
        b.pop();

        SPEC = b.build();
    }

    private Config() {}
}
