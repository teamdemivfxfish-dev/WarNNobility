package com.newtl.warnnobility.warmap.client.f4;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reflection bridge into Hundred Years War's client freecam ("F4") RTS mode.
 *
 * <p>HYW is an "All Rights Reserved" mod with no public API, so, exactly like the Antique Atlas hook in
 * {@link com.newtl.warnnobility.domain.client.HandheldDomainOverlay}, we reach its internals by reflection
 * and fail soft: if HYW is absent or its shape changes, every call returns a safe default and the F4 HUD
 * simply never draws. Method handles are resolved once and cached.
 *
 * <p><b>We do not try to fire HYW's orders programmatically.</b> HYW commands are mouse + camera-ray driven
 * (RMB = move, {@code attack key} + LMB = attack-move, and action keys modify clicks); there is no clean
 * "executeMove" to call, so faking it misfires. Instead our HUD is a live <i>control reference</i>: it reads
 * the player's ACTUAL bound keys from {@code HotKeyManager} ({@link #keyLabel}) and teaches them, so the tile
 * hints always match whatever the player has configured. Behaviours HYW lacks are built on our own server
 * seam ({@link com.newtl.warnnobility.warmap.HywServerCommand}), not spoofed here.
 *
 * <p>Confirmed against HundredYearsWar-0.6.4r-1.21.1-neoforge.
 */
public final class HywF4Bridge {

    private HywF4Bridge() {}

    private static final boolean LOADED = ModList.get().isLoaded("hundred_years_war");
    private static boolean broken = false;

    private static Method mIsInFreecam;   // FreecamStateManager.isPlayerInFreecam(UUID)
    private static Method mGetInstance;    // SelectionHandler.getInstance()
    private static Method mGetSelected;    // SelectionHandler#getSelectedEntities()
    private static Method mSetSelected;    // SelectionHandler#setSelectedEntities(List)
    private static Method mGetLevel;       // BaseCombatEntity#getLevel() (optional)
    private static boolean init = false;

    private static synchronized void ensureInit() {
        if (init || broken || !LOADED) return;
        try {
            Class<?> fsm = Class.forName("ydmsama.hundred_years_war.main.utils.FreecamStateManager");
            mIsInFreecam = fsm.getMethod("isPlayerInFreecam", UUID.class);
            Class<?> sh = Class.forName("ydmsama.hundred_years_war.client.freecam.selection.SelectionHandler");
            mGetInstance = sh.getMethod("getInstance");
            mGetSelected = sh.getMethod("getSelectedEntities");
            try { mSetSelected = sh.getMethod("setSelectedEntities", List.class); }
            catch (Throwable ignored) { mSetSelected = null; } // selection-restore is best-effort
            try {
                Class<?> bce = Class.forName("ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity");
                mGetLevel = bce.getMethod("getLevel");
            } catch (Throwable ignored) {
                mGetLevel = null; // level readout is best-effort
            }
            init = true;
        } catch (Throwable t) {
            broken = true;
        }
    }

    /** True while the local player is in HYW's freecam / F4 RTS mode. */
    public static boolean active() {
        if (!LOADED || broken) return false;
        ensureInit();
        if (broken) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return false;
        // Primary: HYW's authoritative freecam-state map (populated in singleplayer / synced host).
        try {
            Object r = mIsInFreecam.invoke(null, mc.player.getUUID());
            if (r instanceof Boolean b && b) return true;
        } catch (Throwable ignored) {}
        // Fallback: the camera has been swapped to HYW's FreeCamera entity.
        Entity cam = mc.getCameraEntity();
        return cam != null && cam.getClass().getName()
                .equals("ydmsama.hundred_years_war.client.freecam.util.FreeCamera");
    }

    /** The entities currently box-/click-selected in F4 mode. Never null. */
    @SuppressWarnings("unchecked")
    public static List<Entity> selected() {
        if (!LOADED || broken) return List.of();
        ensureInit();
        if (broken) return List.of();
        try {
            Object handler = mGetInstance.invoke(null);
            Object out = mGetSelected.invoke(handler);
            if (out instanceof List<?> l) return (List<Entity>) l;
        } catch (Throwable ignored) {}
        return List.of();
    }

    /**
     * Re-apply a selection snapshot. HYW treats any freecam left-click as a select/deselect, so clicking
     * our on-screen buttons would clear the units; restoring the snapshot right after makes that deselect
     * invisible, letting the HUD be moused without losing the selection.
     */
    public static void restoreSelected(java.util.List<Entity> sel) {
        if (!LOADED || broken || mSetSelected == null || sel == null) return;
        try {
            Object handler = mGetInstance.invoke(null);
            mSetSelected.invoke(handler, new java.util.ArrayList<>(sel));
        } catch (Throwable ignored) {}
    }

    // --- live key reference (HYW's real, player-configured binds) ----------------------------------
    // We read HYW's own KeyMapping objects so the HUD teaches the player's ACTUAL keys, and keeps matching
    // if they rebind. Function id -> HotKeyManager getter. All getters are static and return KeyMapping.

    private static final Map<String, String> GETTER = new HashMap<>();
    static {
        GETTER.put("move", "getMoveCommandKey");
        GETTER.put("attack", "getAttackCommandKey");
        GETTER.put("select", "getSelectCommandKey");
        GETTER.put("selectall", "getSelectAllUnitsKey");
        GETTER.put("hold", "getHoldCommandKey");
        GETTER.put("cancel", "getCancelCommandKey");
        GETTER.put("facing", "getFormationFacingKey");
        GETTER.put("patrol", "getPatrolToggleKey");
        GETTER.put("shield", "getShieldModeToggleKey");
        GETTER.put("combatform", "getCombatFormToggleKey");
        GETTER.put("formation", "getFormationToggleKey");
        GETTER.put("wheel", "getCommandWheelKey");
        GETTER.put("dismount", "getDismountKey");
        GETTER.put("attackmode", "getArcherAttackModeToggleKey");
        GETTER.put("queue", "getQueueModeCommandKey");
    }
    private static Class<?> hotKeyManager;
    private static final Map<String, Method> keyGetters = new HashMap<>();

    /**
     * The human-readable bound key for one HYW command (e.g. {@code "R"}, {@code "Middle Button"}), read live
     * from HYW's HotKeyManager. Empty string if HYW is absent or the getter is unknown, so callers can fall
     * back to a hint.
     */
    public static String keyLabel(String func) {
        if (!LOADED || broken) return "";
        ensureInit();
        if (broken) return "";
        try {
            if (hotKeyManager == null) {
                hotKeyManager = Class.forName("ydmsama.hundred_years_war.client.freecam.config.keys.HotKeyManager");
            }
            Method m = keyGetters.get(func);
            if (m == null) {
                String g = GETTER.get(func);
                if (g == null) return "";
                m = hotKeyManager.getMethod(g);
                keyGetters.put(func, m);
            }
            if (m.invoke(null) instanceof KeyMapping km) {
                return km.getTranslatedKeyMessage().getString();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** Best-effort HYW troop level, or -1 if unavailable / not a combat entity. */
    public static int level(Entity e) {
        if (mGetLevel == null || e == null) return -1;
        try {
            if (mGetLevel.invoke(e) instanceof Integer i) return i;
        } catch (Throwable ignored) {}
        return -1;
    }

    /** True if the entity is one of HYW's puppet/combat units (vs a stray selected mob). */
    public static boolean isCombatUnit(Entity e) {
        if (!(e instanceof LivingEntity)) return false;
        for (Class<?> c = e.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity")) return true;
        }
        for (Class<?> i : e.getClass().getInterfaces()) {
            if (i.getName().equals("ydmsama.hundred_years_war.main.entity.entities.puppets.IPuppet")) return true;
        }
        return false;
    }
}
