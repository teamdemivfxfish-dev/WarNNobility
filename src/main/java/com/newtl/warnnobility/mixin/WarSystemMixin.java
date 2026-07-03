package com.newtl.warnnobility.mixin;

import com.minecolonies.api.colony.IColony;
import com.newtl.warnnobility.nobility.WarRankGuard;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * War 'n Nobility gate on War 'n Taxes' war declarations. Both public entry points that begin a war
 * ({@code WarSystem.processWageWarRequest} and its extortion variant) are intercepted at HEAD: if the
 * declarer's nobility rank is below the target colony owner's, the declaration is refused with a message
 * and suppressed (returns 0, War 'n Taxes never starts the war / creates a pending request). All checks
 * live in {@link WarRankGuard} and are fail-soft.
 *
 * <p>Targets War 'n Taxes by string with {@code remap = false} and is {@code required = false} in the
 * mixin config, so it silently no-ops when War 'n Taxes is absent or its method shape drifts. War 'n
 * Taxes' own code is never modified — this lives entirely in the War 'n Nobility jar.
 */
@Mixin(targets = "net.machiavelli.minecolonytax.WarSystem", remap = false)
public abstract class WarSystemMixin {

    @Inject(method = "processWageWarRequest", at = @At("HEAD"), cancellable = true, remap = false)
    private static void warnnobility$guardWar(ServerPlayer attacker, IColony target, CommandSourceStack src,
                                              CallbackInfoReturnable<Integer> cir) {
        warnnobility$maybeBlock(attacker, target, cir);
    }

    @Inject(method = "processWageWarRequestWithExtortion", at = @At("HEAD"), cancellable = true, remap = false)
    private static void warnnobility$guardWarExtortion(ServerPlayer attacker, IColony target,
                                                       CommandSourceStack src, int extortion,
                                                       CallbackInfoReturnable<Integer> cir) {
        warnnobility$maybeBlock(attacker, target, cir);
    }

    private static void warnnobility$maybeBlock(ServerPlayer attacker, IColony target,
                                                CallbackInfoReturnable<Integer> cir) {
        String rejection = WarRankGuard.rejection(attacker, target);
        if (rejection != null) {
            if (attacker != null) {
                attacker.sendSystemMessage(Component.literal("⛔ " + rejection).withStyle(ChatFormatting.RED));
            }
            cir.setReturnValue(0);   // suppress the declaration; 0 = command failed
        }
    }
}
