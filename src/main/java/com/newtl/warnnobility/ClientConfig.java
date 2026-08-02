package com.newtl.warnnobility;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config (config/warnnobility-client.toml). Purely cosmetic, per-player choices that a server
 * has no business dictating — so they live here rather than in {@link Config}, and a player on someone
 * else's server can still turn them off.
 *
 * <p>Right now this holds the one switch for the Hundred Years War command overlay. That overlay both
 * DRAWS our HUD and SUPPRESSES three pieces of HYW's own freecam UI (its action list, its mouse-control
 * legend, and the action-bar order spam), so a player who prefers HYW's stock interface needs a single
 * lever that undoes all four at once — see {@link com.newtl.warnnobility.warmap.client.f4.HywF4Bridge#active()},
 * which is the chokepoint every one of those paths already asks.
 */
public final class ClientConfig {

    public static final ModConfigSpec SPEC;

    /** Master switch for the HYW freecam command overlay (our HUD + our suppression of HYW's own UI). */
    public static final ModConfigSpec.BooleanValue HYW_COMMAND_HUD;

    /** Whether Alt+H inside HYW freecam flips {@link #HYW_COMMAND_HUD} live. */
    public static final ModConfigSpec.BooleanValue HYW_COMMAND_HUD_HOTKEY;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("hud");
        HYW_COMMAND_HUD = b.comment(
                        "Draw the War 'n Nobility command overlay while you are in Hundred Years War's freecam",
                        "(F4) RTS mode: the status strip, the Atlas minimap, the selected-force card and the",
                        "control grid. Turning this OFF also stops us hiding HYW's own freecam UI, so you get",
                        "HYW's stock action list, its mouse-control legend and its action-bar order messages",
                        "back exactly as they were — this one switch undoes the whole overlay. Has no effect",
                        "when Hundred Years War is not installed.")
                .define("hyw_command_hud", true);
        HYW_COMMAND_HUD_HOTKEY = b.comment(
                        "Let Alt+H toggle the overlay above without leaving the game. The key only listens",
                        "while you are actually in HYW freecam, so it never clashes with normal play, and the",
                        "new state is written straight back to this file. Set false if Alt+H is in your way.")
                .define("hyw_command_hud_hotkey", true);
        b.pop();

        SPEC = b.build();
    }

    /**
     * The overlay switch, read safely. Config values throw when touched before the file is loaded (early
     * mixin/render paths can beat config load), so an unloaded spec answers with the default: overlay on.
     */
    public static boolean hywCommandHud() {
        try {
            return !SPEC.isLoaded() || HYW_COMMAND_HUD.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /** True if Alt+H should flip the overlay. Same load-safety as {@link #hywCommandHud()}. */
    public static boolean hywCommandHudHotkey() {
        try {
            return !SPEC.isLoaded() || HYW_COMMAND_HUD_HOTKEY.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Flip the overlay switch and persist it to the toml. No-op if the config has not loaded yet. */
    public static boolean toggleHywCommandHud() {
        if (!SPEC.isLoaded()) return true;
        boolean now = !HYW_COMMAND_HUD.get();
        HYW_COMMAND_HUD.set(now);
        HYW_COMMAND_HUD.save();
        return now;
    }

    private ClientConfig() {}
}
