package com.newtl.warnnobility.mixin;

import com.newtl.warnnobility.warmap.client.f4.HywF4Bridge;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses Hundred Years War's own bottom-left freecam "action list" (Attack/Hold/Cancel/Patrol/…) while
 * our F4 command HUD is active, so our HUD replaces it cleanly instead of HYW's text drawing over our panels.
 * Our HUD already teaches these controls with the player's live keys, so this is a straight replacement.
 *
 * <p>Targeted by string ({@code remap = false}) because HYW is an optional, reflection-only dependency not on
 * our compile classpath; the mixin config is {@code required:false} so a missing HYW just skips it.
 */
@Mixin(targets = "ydmsama.hundred_years_war.client.freecam.ui.ActionDisplayHandler", remap = false)
public class HywActionListMixin {

    @Inject(method = "renderActionList", at = @At("HEAD"), cancellable = true, remap = false)
    private void warnnobility$hideWhenOurHudActive(GuiGraphics graphics, CallbackInfo ci) {
        if (HywF4Bridge.active()) ci.cancel();
    }
}
