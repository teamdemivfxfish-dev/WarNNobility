package com.newtl.warnnobility.warmap;

import com.newtl.warnnobility.WarNNobility;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Server-side guard for the War Table. Any attempt to BREAK a frame that belongs to an active board
 * (notably a creative-mode insta-break, which ignores {@code getDestroyProgress}) is cancelled and
 * instead knocks the mounted atlas loose - so you never punch through and break the wall behind, and
 * the board is only ever taken apart once the atlas is out. Survival relies on the 3-hit counter in
 * {@link WarFrameBlock#attack}.
 */
@EventBusSubscriber(modid = WarNNobility.MODID)
public final class WarMapEvents {

    private WarMapEvents() {}

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getState().getBlock() instanceof WarFrameBlock)) return;
        if (!(event.getLevel() instanceof Level level) || level.isClientSide) return;
        Direction facing = event.getState().getValue(WarFrameBlock.FACING);
        WarFrameBlockEntity anchor = WarFrameBlock.anchorBE(level, event.getPos(), facing);
        if (anchor != null && anchor.isActive()) {
            // Never let a break go through (notably a creative insta-break). Removal is governed entirely
            // by the 3-hit counter in WarFrameBlock.attack, which ejects the atlas on the third hit.
            event.setCanceled(true);
        }
    }
}
