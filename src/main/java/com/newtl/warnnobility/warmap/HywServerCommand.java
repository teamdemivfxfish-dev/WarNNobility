package com.newtl.warnnobility.warmap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side seam that issues real Hundred Years War orders to a set of HYW combat units, so the War Frame
 * wall map can command troops (the in-world F4 HUD drives HYW client-side via freecam, which a GUI screen
 * cannot reuse). Mirrors {@link HywServerUnits}: no client references, reflection-only, fail-soft.
 *
 * <p>We call HYW's own {@code FormationManager} statics, the exact same entry points its move/hold packets
 * hit server-side, so a map order behaves identically to one given in freecam (formation pathing, road use,
 * arrival spacing all handled by HYW). Verified against HundredYearsWar-0.6.4r-1.21.1-neoforge:
 * <ul>
 *   <li>{@code FormationManager.moveByFormationGroups(List, BlockPos, int, boolean)} - the call
 *       {@code MoveCommandPacket.handlePacket} makes; the int is the constant command-priority 1.</li>
 *   <li>{@code FormationManager.hold(List)} - stand-ground.</li>
 *   <li>{@code BaseCombatEntity.stopActiveMovementIntent()} / {@code clearCommandedGoals()} /
 *       {@code setStartAttacking(boolean)} - per-unit stop and attack-move flag.</li>
 * </ul>
 * If HYW is absent or its shape shifts, every call returns false and the wall map simply cannot command
 * (the units still render; only order execution goes dark).
 */
public final class HywServerCommand {

    private HywServerCommand() {}

    private static final boolean LOADED = ModList.get().isLoaded("hundred_years_war");
    private static boolean broken = false, init = false;

    private static Method mMove;              // FormationManager.moveByFormationGroups(List, BlockPos, int, boolean)
    private static Method mHold;              // FormationManager.hold(List)
    private static Method mStopIntent;        // BaseCombatEntity.stopActiveMovementIntent()
    private static Method mClearCommanded;    // BaseCombatEntity.clearCommandedGoals()
    private static Method mClearPatrol;       // BaseCombatEntity.clearPatrolPoints()
    private static Method mSetAttacking;      // BaseCombatEntity.setStartAttacking(boolean)

    private static synchronized void ensure() {
        if (init || broken || !LOADED) return;
        try {
            Class<?> fm = Class.forName("ydmsama.hundred_years_war.main.entity.utils.FormationManager");
            mMove = fm.getMethod("moveByFormationGroups", List.class, BlockPos.class, int.class, boolean.class);
            mHold = fm.getMethod("hold", List.class);
            Class<?> bce = Class.forName("ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity");
            // per-unit hooks are best-effort: any missing one just drops out of stop()/attack-move.
            mStopIntent    = optional(bce, "stopActiveMovementIntent");
            mClearCommanded = optional(bce, "clearCommandedGoals");
            mClearPatrol   = optional(bce, "clearPatrolPoints");
            mSetAttacking  = optional(bce, "setStartAttacking", boolean.class);
            init = true;
        } catch (Throwable t) {
            broken = true;
        }
    }

    private static Method optional(Class<?> c, String name, Class<?>... args) {
        try { return c.getMethod(name, args); } catch (Throwable t) { return null; }
    }

    /** Reflection needs the raw element type HYW declares; the entities really are BaseCombatEntity. */
    private static List<Object> asList(List<? extends Entity> units) {
        return new ArrayList<>(units);
    }

    /** March the formation(s) to {@code target}, exactly as HYW's move command does. */
    public static boolean move(List<? extends Entity> units, BlockPos target, boolean queue) {
        ensure();
        if (broken || mMove == null || units == null || units.isEmpty()) return false;
        try { mMove.invoke(null, asList(units), target, 1, queue); return true; }
        catch (Throwable t) { return false; }
    }

    /** Attack-move: march to {@code target} but with the units flagged to engage anything on the way. */
    public static boolean attackMove(List<? extends Entity> units, BlockPos target, boolean queue) {
        ensure();
        if (broken || units == null || units.isEmpty()) return false;
        if (mSetAttacking != null) {
            for (Entity e : units) { try { mSetAttacking.invoke(e, true); } catch (Throwable ignored) {} }
        }
        return move(units, target, queue);
    }

    /** Stand ground at the current position (HYW hold), keeping formation. */
    public static boolean hold(List<? extends Entity> units) {
        ensure();
        if (broken || mHold == null || units == null || units.isEmpty()) return false;
        try { mHold.invoke(null, asList(units)); return true; }
        catch (Throwable t) { return false; }
    }

    /** Cancel current orders: halt movement, drop commanded goals + patrol, hand back to default AI. */
    public static boolean stop(List<? extends Entity> units) {
        ensure();
        if (broken || units == null || units.isEmpty()) return false;
        boolean any = false;
        for (Entity e : units) {
            any |= invoke(mStopIntent, e);
            invoke(mClearCommanded, e);
            invoke(mClearPatrol, e);
            if (mSetAttacking != null) { try { mSetAttacking.invoke(e, false); } catch (Throwable ignored) {} }
        }
        return any;
    }

    private static boolean invoke(Method m, Object on) {
        if (m == null) return false;
        try { m.invoke(on); return true; } catch (Throwable t) { return false; }
    }
}
