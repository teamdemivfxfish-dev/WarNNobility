package com.newtl.warnnobility.mixin;

import com.newtl.warnnobility.client.ClientVassalStatus;
import net.machiavelli.minecolonytax.gui.data.VassalIncomeData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * War 'n Nobility overlay on War 'n Taxes' tax-book vassal page. War 'n Taxes owns the page and its
 * vassal list; we only annotate it. This wraps the page's own {@code vassalSupplier} so that any colony
 * the local player holds as a still-PROVISIONAL nobility seal (won by conquest but not yet sworn in,
 * because they have not ascended) renders with a " (Provisional)" suffix on its name. War 'n Taxes then
 * draws it with its normal layout, so no War 'n Taxes code is touched.
 *
 * <p>Targeted by string with {@code remap = false} (War 'n Taxes is optional / not Mojang-mapped), and
 * declared {@code required = false} in the mixin config, so it silently no-ops if War 'n Taxes is absent
 * or its field shape drifts. The provisional set comes from {@link ClientVassalStatus}, synced by
 * {@link com.newtl.warnnobility.net.VassalStatusMsg}.
 */
@Mixin(targets = "net.machiavelli.minecolonytax.gui.book.VassalsPage", remap = false)
public abstract class VassalsPageMixin {

    @Shadow @Final @Mutable
    private Supplier<List<VassalIncomeData>> vassalSupplier;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void warnnobility$tagProvisional(CallbackInfo ci) {
        final Supplier<List<VassalIncomeData>> original = this.vassalSupplier;
        if (original == null) return;
        this.vassalSupplier = () -> {
            List<VassalIncomeData> base = original.get();
            if (base == null) return null;
            List<VassalIncomeData> out = new ArrayList<>(base.size());
            for (VassalIncomeData v : base) {
                if (v != null
                        && ClientVassalStatus.isProvisional(v.getVassalColonyId())
                        && !v.getVassalColonyName().contains("(Provisional)")) {
                    out.add(new VassalIncomeData(
                            v.getVassalColonyId(),
                            v.getVassalColonyName() + " (Provisional)",
                            v.getTributeRate(), v.getTributeOwed(), v.getLastTribute(),
                            v.getLastPayment(), v.canClaim()));
                } else {
                    out.add(v);
                }
            }
            return out;
        };
    }
}
