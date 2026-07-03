package com.newtl.warnnobility.warmap;

import com.newtl.warnnobility.WarNNobility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for a War Frame. Only the board's ANCHOR (min-corner) carries the live
 * {@link WarMapData}; the other eight frames are dumb panels that, at render time, look up their
 * anchor and draw their slice. Transient dismantle-hit bookkeeping lives on every frame's anchor.
 *
 * <p>The anchor pushes its whole {@link WarMapData} to clients via the standard block-entity update
 * tag, which is enough for the renderer; live, low-latency drawing rides a separate payload (added
 * with the sync layer). State persists in {@link #saveAdditional}, so a briefing survives a restart.
 */
public class WarFrameBlockEntity extends BlockEntity {

    /** Non-null only on an active anchor frame. */
    private WarMapData data;

    // transient grief-guard counter (not persisted)
    int breakHits = 0;
    long lastHitTick = 0L;

    public WarFrameBlockEntity(BlockPos pos, BlockState state) {
        super(WarNNobility.WAR_FRAME_BE.get(), pos, state);
    }

    public boolean isActive() {
        return data != null && data.hasAtlas();
    }

    public WarMapData data() {
        return data;
    }

    public Direction facing() {
        return getBlockState().getValue(WarFrameBlock.FACING);
    }

    // --- activation / lifecycle -------------------------------------------------------------------

    /** Mount an atlas and bring the board to life. Called on the anchor BE, server-side. */
    public void activate(ItemStack atlas) {
        data = new WarMapData();
        data.atlas = atlas;
        BlockPos centre = Multiblock.cell(getBlockPos(), facing(), 1, 1);
        data.centerX = centre.getX() + 0.5;
        data.centerZ = centre.getZ() + 0.5;
        sync();
    }

    /** Knock the mounted atlas loose (like punching an item frame): drop the atlas and go back to an
     *  empty 3x3 of frames, which can then be mined apart normally. */
    public void ejectAtlas() {
        if (level == null || data == null || !data.hasAtlas()) return;
        Block.popResource(level, getBlockPos(), data.atlas);
        data = null;                 // deactivate; the frames remain
        breakHits = 0;
        sync();
        if (level instanceof ServerLevel sl) {
            // belt-and-suspenders: explicitly tell nearby clients to drop the board, so the map can't
            // linger if the block-entity sync raced with a creative break.
            com.newtl.warnnobility.warmap.net.WarMapNetwork.broadcastDeactivate(sl, getBlockPos());
        }
        level.playSound(null, getBlockPos(), SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.9f, 1.0f);
    }

    /** Client-side: clear the board's state when the server says its atlas was knocked off. */
    public void clientDeactivate() {
        data = null;
    }

    // --- control lock ("the marker") --------------------------------------------------------------

    public void takeTheFloor(ServerPlayer sp) {
        if (data == null) return;
        long now = sp.level().getGameTime();
        if (data.controller == null || isStale(now) || holderOffline()) {
            data.controller = sp.getUUID();
            data.lastController = sp.getUUID();   // remembered for allegiance colouring after you step away
            data.controllerActiveTick = now;
            sync();
            sp.displayClientMessage(Component.literal("§aYou have the floor."), true);
            openPlanner(sp);
        } else if (data.controlledBy(sp.getUUID())) {
            data.controllerActiveTick = now;
            openPlanner(sp);
        } else {
            sp.displayClientMessage(Component.literal("§e" + holderName() + " has the floor."), true);
        }
    }

    public void rotateBoard(ServerPlayer sp) {
        if (data == null) return;
        if (data.controller != null && !data.controlledBy(sp.getUUID()) && !isStale(sp.level().getGameTime())
                && !holderOffline()) {
            sp.displayClientMessage(Component.literal("§e" + holderName() + " has the floor."), true);
            return;
        }
        data.rotation = (data.rotation + 1) & 3;
        if (data.controlledBy(sp.getUUID())) data.controllerActiveTick = sp.level().getGameTime();
        sync();
        sp.displayClientMessage(Component.literal("§6Board rotated."), true);
    }

    /** Open the commander's planner GUI on the holder's client. */
    private void openPlanner(ServerPlayer sp) {
        com.newtl.warnnobility.warmap.net.WarMapNetwork.openFor(sp, getBlockPos());
    }

    private static final int MAX_STROKES = 4000;

    /** Apply a validated controller command from {@link com.newtl.warnnobility.warmap.net.WarMapNetwork}. */
    public void applyAction(int action, net.minecraft.nbt.CompoundTag t, ServerPlayer sp) {
        if (data == null || !data.controlledBy(sp.getUUID())) return; // only the marker-holder may act
        data.controllerActiveTick = sp.level().getGameTime();
        switch (action) {
            case com.newtl.warnnobility.warmap.net.WarMapNetwork.A_DRAW -> {
                WarStroke s = com.newtl.warnnobility.warmap.net.WarMapNetwork.strokeFromTag(t, sp.getUUID());
                if (!s.tool.isTransient() && s.pts.length >= 2 && data.strokes.size() < MAX_STROKES) {
                    data.strokes.add(s);
                    sync();
                }
            }
            case com.newtl.warnnobility.warmap.net.WarMapNetwork.A_VIEW -> {
                data.centerX = t.getDouble("CenterX");
                data.centerZ = t.getDouble("CenterZ");
                data.zoom = t.getInt("Zoom");
                data.rotation = t.getInt("Rotation");
                data.clampView();
                sync();
            }
            case com.newtl.warnnobility.warmap.net.WarMapNetwork.A_UNDO -> {
                for (int i = data.strokes.size() - 1; i >= 0; i--) {
                    if (sp.getUUID().equals(data.strokes.get(i).author)) { data.strokes.remove(i); break; }
                }
                sync();
            }
            case com.newtl.warnnobility.warmap.net.WarMapNetwork.A_CLEAR -> {
                data.strokes.clear();
                sync();
            }
            case com.newtl.warnnobility.warmap.net.WarMapNetwork.A_ERASE -> {
                double ex = t.getDouble("X"), ez = t.getDouble("Z"), r = t.getDouble("R");
                double r2 = r * r;
                data.strokes.removeIf(s -> nearAny(s, ex, ez, r2));
                sync();
            }
            case com.newtl.warnnobility.warmap.net.WarMapNetwork.A_POINT -> {
                BlockPos centre = Multiblock.cell(getBlockPos(), facing(), 1, 1);
                com.newtl.warnnobility.warmap.net.WarMapNetwork.broadcastPing(
                        sp, centre, t.getDouble("X"), t.getDouble("Z"), t.getInt("Color"));
            }
            case com.newtl.warnnobility.warmap.net.WarMapNetwork.A_UNITS -> {
                data.showUnits = !data.showUnits;
                sync();
            }
            case com.newtl.warnnobility.warmap.net.WarMapNetwork.A_COMMAND -> {
                if (level instanceof ServerLevel sl) {
                    String key = t.getString("Key");
                    int order = t.getInt("Order");
                    java.util.List<net.minecraft.world.entity.LivingEntity> group =
                            WarBoardScan.collectGroup(sl, data, key, sp.getUUID());
                    if (!group.isEmpty()) {
                        int tx = (int) Math.floor(t.getDouble("X"));
                        int tz = (int) Math.floor(t.getDouble("Z"));
                        int ty = sl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, tx, tz);
                        BlockPos target = new BlockPos(tx, ty, tz);
                        switch (order) {
                            case com.newtl.warnnobility.warmap.net.WarMapNetwork.ORD_MOVE   -> HywServerCommand.move(group, target, false);
                            case com.newtl.warnnobility.warmap.net.WarMapNetwork.ORD_ATTACK -> HywServerCommand.attackMove(group, target, false);
                            case com.newtl.warnnobility.warmap.net.WarMapNetwork.ORD_HOLD   -> HywServerCommand.hold(group);
                            case com.newtl.warnnobility.warmap.net.WarMapNetwork.ORD_STOP   -> HywServerCommand.stop(group);
                            default -> { }
                        }
                    }
                }
            }
            case com.newtl.warnnobility.warmap.net.WarMapNetwork.A_RELEASE -> {
                data.controller = null;
                sync();
            }
            default -> { }
        }
    }

    /** True if the erase point (x,z) is within radius (r2 = r²) of the stroke, testing SEGMENTS not just
     *  vertices so a click on the middle of a line/arrow/zone edge erases it, not only near its endpoints. */
    private static boolean nearAny(WarStroke s, double x, double z, double r2) {
        double[] p = s.pts;
        if (p.length == 2) {                                   // single anchor (token / text / point)
            double dx = p[0] - x, dz = p[1] - z;
            return dx * dx + dz * dz <= r2;
        }
        for (int i = 0; i + 3 < p.length; i += 2) {
            if (segDist2(p[i], p[i + 1], p[i + 2], p[i + 3], x, z) <= r2) return true;
        }
        return false;
    }

    /** Squared distance from point (px,pz) to segment (ax,az)-(bx,bz). */
    private static double segDist2(double ax, double az, double bx, double bz, double px, double pz) {
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 <= 1e-9 ? 0.0 : ((px - ax) * dx + (pz - az) * dz) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = ax + t * dx, cz = az + t * dz, ex = px - cx, ez = pz - cz;
        return ex * ex + ez * ez;
    }

    private boolean isStale(long now) {
        return now - data.controllerActiveTick > WarMapData.IDLE_RELEASE_TICKS;
    }

    private boolean holderOffline() {
        return level == null || level.getServer() == null
                || level.getServer().getPlayerList().getPlayer(data.controller) == null;
    }

    private String holderName() {
        if (level != null && level.getServer() != null && data.controller != null) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(data.controller);
            if (p != null) return p.getGameProfile().getName();
        }
        return "Someone";
    }

    /** Server tick (anchor only): free the marker when the holder leaves, logs off, or goes idle. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, WarFrameBlockEntity be) {
        if (be.data == null) return;
        long now = level.getGameTime();
        // Live-unit sweep: while the board is active and units are enabled, scan the field ~once a second
        // and push it to nearby clients (independent of who, if anyone, holds the floor).
        if (be.isActive() && be.data.showUnits && (now % 20L) == 0L && level instanceof ServerLevel sl) {
            BlockPos c = Multiblock.cell(pos, be.facing(), 1, 1);
            try { WarBoardScan.scanAndSend(sl, pos, c, be.data); } catch (Throwable ignored) {}
        }
        if (be.data.controller == null) return;
        if ((now & 7L) != 0L) return; // ~every 8 ticks is plenty
        boolean release = be.holderOffline() || be.isStale(now);
        if (!release) {
            ServerPlayer p = ((ServerLevel) level).getServer().getPlayerList().getPlayer(be.data.controller);
            BlockPos c = Multiblock.cell(pos, be.facing(), 1, 1);
            if (p == null || p.distanceToSqr(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5)
                    > WarMapData.CONTROL_RANGE * WarMapData.CONTROL_RANGE) {
                release = true;
            }
        }
        if (release) {
            be.data.controller = null;
            be.sync();
        }
    }

    // --- sync + persistence -----------------------------------------------------------------------

    /** Mark dirty and push the new state to watching clients. */
    public void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (data != null) tag.put("WarMap", data.toNbt(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        data = tag.contains("WarMap") ? WarMapData.fromNbt(tag.getCompound("WarMap"), registries) : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
