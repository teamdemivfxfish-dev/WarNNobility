package com.newtl.warnnobility.mixin;

import com.minecolonies.api.colony.IColony;
import com.newtl.warnnobility.nobility.WarRankGuard;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * War 'n Nobility gate on War 'n Taxes' BESIEGE declarations — the sibling of {@link WarSystemMixin}.
 * The single besiege-declaration funnel ({@code BesiegeManager.startBesiege(IColony, ServerPlayer)}) is
 * intercepted at HEAD: if the declarer's nobility rank is below the target colony owner's, the besiege is
 * refused with a message and suppressed (returns {@code false}, so War 'n Taxes never declares/launches the
 * siege). War 'n Taxes normally treats a besiege as the way to punch UP at a stronger power, but under the
 * noble ladder besieging your better wins no seal, so with War 'n Nobility installed it is forbidden here.
 *
 * <p>All checks live in {@link WarRankGuard#besiegeRejection} and are fail-soft. Targets War 'n Taxes by
 * string with {@code remap = false} and is {@code required = false} in the mixin config, so it silently
 * no-ops when War 'n Taxes is absent or the method shape drifts. War 'n Taxes' own code is never modified —
 * the whole rule lives in the War 'n Nobility jar, so it only exists when this mod is on the server.
 */
@Mixin(targets = "net.machiavelli.minecolonytax.besiege.BesiegeManager", remap = false)
public abstract class BesiegeSystemMixin {

    @Inject(method = "startBesiege", at = @At("HEAD"), cancellable = true, remap = false)
    private static void warnnobility$guardBesiege(IColony colony, ServerPlayer besieger,
                                                  CallbackInfoReturnable<Boolean> cir) {
        String rejection = WarRankGuard.besiegeRejection(besieger, colony);
        if (rejection != null) {
            if (besieger != null) {
                besieger.sendSystemMessage(Component.literal("⛔ " + rejection).withStyle(ChatFormatting.RED));
            }
            cir.setReturnValue(false);   // suppress the besiege declaration; false = it did not start
        }
    }
}
