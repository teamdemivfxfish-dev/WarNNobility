package com.newtl.warnnobility.warmap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Geometry for the 3x3 War Table. A War Frame mounts on a block face like an item frame, so its
 * {@code FACING} is the direction it points away from the wall/table. The board lies in the plane
 * perpendicular to FACING; the two in-plane axes are {@link #uAxis}/{@link #vAxis}.
 *
 * <p>The grid is a fixed 3x3 (never larger), so the "anchor" is simply the unique min-corner cell.
 * Validation is stateless: given any clicked frame we slide a 3x3 window over the nine possible
 * placements and return the corner of the first complete grid that contains it.
 */
public final class Multiblock {

    public static final int SIZE = 3;

    private Multiblock() {}

    /** The horizontal/first in-plane axis for a board facing {@code f}. */
    public static Direction uAxis(Direction f) {
        return switch (f) {
            case UP, DOWN -> Direction.EAST;        // table: u = +X
            case NORTH, SOUTH -> Direction.EAST;    // wall:  u = +X
            case EAST, WEST -> Direction.SOUTH;     // wall:  u = +Z
        };
    }

    /** The vertical/second in-plane axis for a board facing {@code f}. */
    public static Direction vAxis(Direction f) {
        return switch (f) {
            case UP, DOWN -> Direction.SOUTH;       // table: v = +Z
            default -> Direction.UP;                // wall:  v = +Y
        };
    }

    /** True when the board lies flat (table/floor or ceiling) and may be rotated. */
    public static boolean isFlat(Direction f) {
        return f == Direction.UP || f == Direction.DOWN;
    }

    /**
     * Find the min-corner of a complete 3x3 of like-facing War Frames containing {@code clicked},
     * or {@code null} if {@code clicked} is not part of a full grid. Independent of which of the
     * nine cells was clicked.
     */
    public static BlockPos findAnchor(BlockGetter level, BlockPos clicked, Direction facing) {
        Direction u = uAxis(facing), v = vAxis(facing);
        for (int ci = 0; ci < SIZE; ci++) {
            for (int cj = 0; cj < SIZE; cj++) {
                BlockPos anchor = clicked.relative(u, -ci).relative(v, -cj);
                if (isComplete(level, anchor, facing, u, v)) return anchor;
            }
        }
        return null;
    }

    /** All nine cells from {@code anchor} are War Frames facing {@code facing}. */
    public static boolean isComplete(BlockGetter level, BlockPos anchor, Direction facing,
                                     Direction u, Direction v) {
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                BlockState s = level.getBlockState(anchor.relative(u, i).relative(v, j));
                if (!(s.getBlock() instanceof WarFrameBlock)) return false;
                if (s.getValue(WarFrameBlock.FACING) != facing) return false;
            }
        }
        return true;
    }

    /** World position of grid cell {@code (i,j)} (0..2 each) of the board anchored at {@code anchor}. */
    public static BlockPos cell(BlockPos anchor, Direction facing, int i, int j) {
        return anchor.relative(uAxis(facing), i).relative(vAxis(facing), j);
    }
}
