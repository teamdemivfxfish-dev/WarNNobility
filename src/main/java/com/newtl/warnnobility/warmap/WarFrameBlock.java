package com.newtl.warnnobility.warmap;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A single panel of the War Table. Mounts on a block face like an item frame ({@code FACING} points
 * away from the support). Nine like-facing frames in a 3x3 form a board; inserting an
 * {@code antique_atlas:atlas} into a complete grid activates it (see {@link Multiblock}).
 *
 * <p>Interaction routes to the board's anchor: right-click = take the floor (open the planner / claim
 * the marker), Shift+right-click on a flat table = rotate, atlas-in-hand on an inactive grid = mount.
 * A complete grid is unbreakable by mining and instead takes three hits to dismantle, so nobody nukes
 * a briefing mid-sentence.
 */
public class WarFrameBlock extends Block implements EntityBlock {

    public static final MapCodec<WarFrameBlock> CODEC = simpleCodec(WarFrameBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** Window in ticks within which dismantle hits must land, else the counter resets. */
    private static final long HIT_WINDOW = 100L;
    private static final int HITS_TO_REMOVE = 3;

    public WarFrameBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction face = ctx.getClickedFace();
        BlockState state = defaultBlockState().setValue(FACING, face);
        return state.canSurvive(ctx.getLevel(), ctx.getClickedPos()) ? state : null;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos support = pos.relative(facing.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, facing);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        // A thin plate hugging the support block on the side opposite FACING.
        return switch (state.getValue(FACING)) {
            case DOWN -> Block.box(0, 14, 0, 16, 16, 16);
            case UP -> Block.box(0, 0, 0, 16, 2, 16);
            case NORTH -> Block.box(0, 0, 14, 16, 16, 16);
            case SOUTH -> Block.box(0, 0, 0, 16, 16, 2);
            case WEST -> Block.box(14, 0, 0, 16, 16, 16);
            case EAST -> Block.box(0, 0, 0, 2, 16, 16);
        };
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WarFrameBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide || type != com.newtl.warnnobility.WarNNobility.WAR_FRAME_BE.get()) return null;
        return (lvl, pos, st, be) -> WarFrameBlockEntity.serverTick(lvl, pos, st, (WarFrameBlockEntity) be);
    }

    // --- interaction ------------------------------------------------------------------------------

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        Direction facing = state.getValue(FACING);

        if (isAtlasStack(stack) && !player.isShiftKeyDown()) {
            WarFrameBlockEntity anchor = anchorBE(level, pos, facing);
            if (anchor == null) {
                if (!level.isClientSide)
                    player.displayClientMessage(Component.literal("§cNeeds a complete 3x3 of War Frames."), true);
                return ItemInteractionResult.SUCCESS;
            }
            if (!anchor.isActive()) {
                if (!level.isClientSide) {
                    anchor.activate(stack.copyWithCount(1));
                    if (!player.getAbilities().instabuild) stack.shrink(1);
                    level.playSound(null, anchor.getBlockPos(), SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.8f, 1.0f);
                    player.displayClientMessage(Component.literal("§6Atlas mounted on the war table."), true);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        // Anything else falls through to the empty-hand path (take the floor / rotate).
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        Direction facing = state.getValue(FACING);
        WarFrameBlockEntity anchor = anchorBE(level, pos, facing);
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;

        if (anchor == null) {
            sp.displayClientMessage(Component.literal("§7Build a 3x3 of War Frames, then mount an atlas."), true);
            return InteractionResult.SUCCESS;
        }
        if (!anchor.isActive()) {
            sp.displayClientMessage(Component.literal("§7Mount an atlas on the war table to use it."), true);
            return InteractionResult.SUCCESS;
        }

        // Shift+right-click on a flat (table/floor) board rotates the shared view a quarter-turn.
        if (player.isShiftKeyDown() && Multiblock.isFlat(facing)) {
            anchor.rotateBoard(sp);
            return InteractionResult.SUCCESS;
        }
        anchor.takeTheFloor(sp);
        return InteractionResult.SUCCESS;
    }

    // --- grief guard: a complete grid is mining-proof and takes 3 hits to dismantle ---------------

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (level instanceof Level lvl) {
            WarFrameBlockEntity a = anchorBE(lvl, pos, state.getValue(FACING));
            if (a != null && a.isActive()) return 0.0f; // active board: knock the atlas out, don't mine
        }
        return super.getDestroyProgress(state, player, level, pos); // empty/lone frames mine normally
    }

    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        if (level.isClientSide) return;
        WarFrameBlockEntity anchor = anchorBE(level, pos, state.getValue(FACING));
        if (anchor == null || !anchor.isActive()) return; // empty/lone frame: normal mining handles it
        if (!(player instanceof ServerPlayer sp)) return;
        // 3-hit counter governs removal for BOTH survival and creative (creative's instant-break is
        // cancelled by WarMapEvents so it can't bypass this).

        // Like punching an item frame, but it takes three hits to knock the mounted atlas loose.
        long now = level.getGameTime();
        if (now - anchor.lastHitTick > HIT_WINDOW) anchor.breakHits = 0;
        anchor.lastHitTick = now;
        anchor.breakHits++;
        int remaining = HITS_TO_REMOVE - anchor.breakHits;
        if (remaining > 0) {
            level.playSound(null, pos, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 0.7f, 1.2f);
            sp.displayClientMessage(Component.literal(
                    "§eWar table: §c" + remaining + "§e more hit" + (remaining == 1 ? "" : "s")
                            + " to knock the atlas loose."), true);
        } else {
            anchor.ejectAtlas();
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    // AA's atlas is EITHER the antique_atlas:atlas item OR a vanilla book carrying AA's display name, so a
    // plain item-id check misses most real atlases. Mirror AA's own AntiqueAtlas.isHandheldAtlas(stack)
    // reflectively (works on the integrated server where AA is loaded), and fall back to a name heuristic
    // when AA is absent. NOTE: Antique Atlas is a CLIENT-ONLY mod (it has only a `client` entrypoint), so on
    // a dedicated server `antique_atlas` is NOT loaded and the reflection is unavailable -- the heuristic is
    // the ONLY thing that runs server-side, and useItemOn's mount runs server-side, so the heuristic must be
    // right on its own. AA names the atlas two different ways: the creative/`getHandheldAtlas()` atlas sets
    // DataComponents.ITEM_NAME, while an anvil-renamed book sets CUSTOM_NAME. The old heuristic only checked
    // CUSTOM_NAME, so a creative-grabbed atlas was rejected with "need an atlas" even while held. Accept a
    // book bearing EITHER name component (getHoverName resolves both, which is exactly what AA matches on).
    private static java.lang.reflect.Method M_IS_ATLAS;
    private static boolean atlasMethodResolved;

    static boolean isAtlasStack(ItemStack stack) {
        if (stack.isEmpty()) return false;
        net.minecraft.world.item.Item atlasItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("antique_atlas", "atlas"));
        if (atlasItem != net.minecraft.world.item.Items.AIR && stack.is(atlasItem)) return true;
        try {
            if (!atlasMethodResolved) {
                atlasMethodResolved = true;
                if (net.neoforged.fml.ModList.get().isLoaded("antique_atlas")) {
                    M_IS_ATLAS = Class.forName("folk.sisby.antique_atlas.AntiqueAtlas")
                            .getMethod("isHandheldAtlas", ItemStack.class);
                }
            }
            if (M_IS_ATLAS != null && M_IS_ATLAS.invoke(null, stack) instanceof Boolean b && b) return true;
        } catch (Throwable ignored) { /* AA API drift -> fall through to the heuristic */ }
        return stack.is(net.minecraft.world.item.Items.BOOK)
                && (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)
                    || stack.has(net.minecraft.core.component.DataComponents.ITEM_NAME));
    }

    /** The anchor block entity of the complete board containing {@code clicked}, or null. */
    static WarFrameBlockEntity anchorBE(Level level, BlockPos clicked, Direction facing) {
        BlockPos a = Multiblock.findAnchor(level, clicked, facing);
        if (a == null) return null;
        return level.getBlockEntity(a) instanceof WarFrameBlockEntity be ? be : null;
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
