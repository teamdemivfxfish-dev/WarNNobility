package com.newtl.warnnobility.nobility;

import javax.annotation.Nullable;

/**
 * A toggleable line in the Chancery console. A rank's visible set is auto-derived from the ladder
 * by default, but a server owner can override it per rank in the config (the 5th field of a rank
 * line), so e.g. an Outsider never sees "Vassals".
 */
public enum Element {
    SCORE,
    SUPPORTERS,
    VASSALS,
    LIEGE;

    @Nullable
    public static Element parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
