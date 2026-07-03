package com.newtl.warnnobility.mixin;

import com.newtl.warnnobility.warmap.client.f4.HywF4Bridge;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While our F4 command HUD is active, swallow vanilla action-bar (overlay) messages. HYW spams the action
 * bar with transient "Send move command" / "Move command" confirmations every time you issue an order, and
 * that text lands right on our bottom-centre maneuver hints. Our HUD already makes the command state clear,
 * so this is pure noise to suppress. Only fires in HYW freecam (via {@link HywF4Bridge#active()}), so normal
 * gameplay action-bar messages are untouched.
 *
 * <p>{@code setOverlayMessage(Component, boolean)} is the single chokepoint every action-bar message (incl.
 * {@code player.displayClientMessage(text, true)}) routes through; HYW itself mixes into it, confirming the
 * name. Targeted with {@code remap = false} since NeoForge runs on Mojang names at runtime.
 */
@Mixin(Gui.class)
public class GuiOverlayMessageMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"), cancellable = true, remap = false)
    private void warnnobility$suppressWhileOurHudActive(Component message, boolean animateColor, CallbackInfo ci) {
        if (HywF4Bridge.active()) ci.cancel();
    }
}
