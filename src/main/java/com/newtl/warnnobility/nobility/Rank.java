package com.newtl.warnnobility.nobility;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * One rank on the ladder. The {@code requirement}/{@code param} pair gates ENTRY into this rank.
 *
 * Config string form (fields 5, 6, 7 optional):
 * {@code name;REQUIREMENT;param;entryScore;show;advanceText;femaleName}
 *
 * @param name         display title, server-renamable (the masculine/neutral form)
 * @param requirement  how a player enters this rank
 * @param param        SUPPORT=supporter count, BUYIN=item amount, SCORE=threshold; else ignored
 * @param entryScore   score set on entering (negative = leave score untouched, for the Emperor)
 * @param explicitShow which console lines are visible at this rank; {@code null} = auto-derive
 * @param advanceText  custom "how to advance from here" text (for questline servers); blank = auto
 * @param femaleName   feminine form of the title (Baroness, Countess, Queen...); blank = use {@code name}
 */
public record Rank(String name, RequirementType requirement, int param, int entryScore,
                   @Nullable Set<Element> explicitShow, String advanceText, String femaleName) {

    public static Rank parse(String spec) {
        String[] p = spec.split(";", -1);   // -1 keeps trailing empties so "show" can be blank
        String name = p.length > 0 && !p[0].isBlank() ? p[0].trim() : "Rank";
        RequirementType req = p.length > 1 ? RequirementType.parse(p[1]) : RequirementType.COMMAND;
        int param = p.length > 2 ? parseInt(p[2], 0) : 0;
        int entry = p.length > 3 ? parseInt(p[3], 0) : 0;
        Set<Element> show = p.length > 4 ? parseShow(p[4]) : null;   // field present => explicit
        String advance = p.length > 5 ? p[5].trim() : "";
        String female = p.length > 6 ? p[6].trim() : "";
        return new Rank(name, req, param, entry, show, advance, female);
    }

    /** The title to show for a holder of a given identity: feminine form if set and requested. */
    public String displayName(boolean female) {
        return female && femaleName != null && !femaleName.isBlank() ? femaleName : name;
    }

    /**
     * The title's display colour (packed 0xRRGGBB), used on the [Title] prefix in chat + the tab list.
     * Keyed by the (masculine) rank name so it tracks the default ladder even on an already-generated
     * config; an unrecognised/renamed rank falls back to white. Tune the palette here.
     */
    public static int colorFor(String rankName) {
        return switch (rankName == null ? "" : rankName.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "outsider" -> 0xFFFFFF;   // white
            case "artisan"  -> 0x54C254;   // green
            case "knight"   -> 0xFF9D2E;   // orange
            case "baron"    -> 0xE8443B;   // red
            case "count"    -> 0xB05CE6;   // violet
            case "duke"     -> 0x3C8DFF;   // blue (NewtL left Duke unspecified; blue sits between violet and gold)
            case "king"     -> 0xFFC83D;   // gold
            case "emperor"  -> 0x8A4BE0;   // royal purple
            default          -> 0xFFFFFF;
        };
    }

    private static Set<Element> parseShow(String s) {
        Set<Element> out = EnumSet.noneOf(Element.class);
        for (String token : s.split(",")) {
            Element e = Element.parse(token);
            if (e != null) out.add(e);
        }
        return out;   // possibly empty: an explicit "show nothing extra"
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
