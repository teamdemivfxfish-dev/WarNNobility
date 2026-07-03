package com.newtl.warnnobility;

import com.mojang.serialization.MapCodec;
import com.newtl.warnnobility.net.ChanceryConsole;
import com.newtl.warnnobility.net.Network;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Chancery Table: where a noble's clerks draft the decrees of the realm. It is rendered as a
 * lectern bearing a book (see its block model + blockstate), faces the placer, and, on right-click,
 * opens the nobility console (see {@code client/ChanceryScreen}) instead of a crafting grid.
 */
public class ChanceryTableBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<ChanceryTableBlock> CODEC = simpleCodec(ChanceryTableBlock::new);

    // Roughly the lectern silhouette: a full base slab plus the central post (the slanted top + book
    // sit within the post's footprint), so the collision reads like a podium rather than a full cube.
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(0, 0, 0, 16, 2, 16),
            Block.box(4, 2, 4, 12, 15, 12));

    public ChanceryTableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChanceryTableBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            MinecraftServer server = sp.getServer();
            if (server != null) {
                Network.toClient(sp, ChanceryConsole.build(server, sp));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
