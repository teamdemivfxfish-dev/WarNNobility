package com.newtl.warnnobility;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Render-only block entity for the Chancery Table: it holds no data, it exists purely so a
 * {@link com.newtl.warnnobility.client.ChanceryTableRenderer} can draw a real open book on the
 * lectern (the same way vanilla's lectern shows its book), instead of a flat item texture.
 */
public class ChanceryTableBlockEntity extends BlockEntity {

    public ChanceryTableBlockEntity(BlockPos pos, BlockState state) {
        super(WarNNobility.CHANCERY_TABLE_BE.get(), pos, state);
    }
}
