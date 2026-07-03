package com.newtl.warnnobility.mixin;

import com.newtl.warnnobility.warmap.client.f4.HywF4Bridge;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses Hundred Years War's bottom-right freecam control legend (Camera Control / Box Select / Move /
 * Send / Fly Speed) while our F4 command HUD is active. That legend drew over our command grid; our top-strip
 * maneuver hints + the grid's per-tile keys teach the same thing, so this replaces it cleanly.
 *
 * <p>See {@link HywActionListMixin} for why this targets HYW by string with {@code remap = false}.
 */
@Mixin(targets = "ydmsama.hundred_years_war.client.freecam.ui.MouseControlInfoHandler", remap = false)
public class HywMouseControlInfoMixin {

    @Inject(method = "renderMouseControlInfo", at = @At("HEAD"), cancellable = true, remap = false)
    private void warnnobility$hideWhenOurHudActive(GuiGraphics graphics, CallbackInfo ci) {
        if (HywF4Bridge.active()) ci.cancel();
    }
}
