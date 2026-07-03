package com.newtl.warnnobility.warmap.net;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.warmap.MapTool;
import com.newtl.warnnobility.warmap.WarFrameBlockEntity;
import com.newtl.warnnobility.warmap.WarStroke;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Networking for the War Table. Three payloads:
 * <ul>
 *   <li>{@link Open} (S2C) - tell a client to open the planner for a given board.</li>
 *   <li>{@link Action} (C2S) - a controller's command (draw / set-view / undo / clear / erase / point /
 *       release), carried as a tagged {@link CompoundTag} so we need only one codec.</li>
 *   <li>{@link Ping} (S2C) - broadcast a transient "point here" pulse to everyone near the board.</li>
 * </ul>
 * The board's heavy state (strokes, view, who holds the marker) rides the block entity's own update
 * tag, so an Action just mutates the anchor BE and calls {@code sync()}; the pulse is the only thing
 * that must be pushed directly because it is deliberately never stored.
 */
public final class WarMapNetwork {

    private WarMapNetwork() {}

    // action ids
    public static final int A_DRAW = 0, A_VIEW = 1, A_UNDO = 2, A_CLEAR = 3, A_ERASE = 4,
            A_POINT = 5, A_RELEASE = 6, A_UNITS = 7, A_COMMAND = 8;

    // order ids for A_COMMAND (carried in the Action tag as "Order"); APPEND only.
    public static final int ORD_MOVE = 0, ORD_ATTACK = 1, ORD_HOLD = 2, ORD_STOP = 3;

    /**
     * Issue a troop order from the wall map: {@code order} is one of ORD_*, {@code key} the target unit's
     * group key (from {@link com.newtl.warnnobility.warmap.client.ClientUnits.FieldUnit#groupKey}); MOVE and
     * ATTACK carry a world destination, HOLD and STOP ignore it.
     */
    public static void sendCommand(BlockPos board, String key, int order, double wx, double wz) {
        CompoundTag t = base(board);
        t.putString("Key", key);
        t.putInt("Order", order);
        t.putDouble("X", wx);
        t.putDouble("Z", wz);
        sendAction(A_COMMAND, t);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1").optional();
        r.playToClient(Open.TYPE, Open.CODEC, Open::onClient);
        r.playToClient(Ping.TYPE, Ping.CODEC, Ping::onClient);
        r.playToClient(Deactivate.TYPE, Deactivate.CODEC, Deactivate::onClient);
        r.playToClient(Units.TYPE, Units.CODEC, Units::onClient);
        r.playToServer(Action.TYPE, Action.CODEC, Action::onServer);
    }

    /** Broadcast a board's freshly-scanned units/colonists to every player near enough to see the board. */
    public static void sendUnitsNear(ServerLevel level, BlockPos boardCentre, Units payload) {
        PacketDistributor.sendToPlayersNear(level, null,
                boardCentre.getX() + 0.5, boardCentre.getY() + 0.5, boardCentre.getZ() + 0.5, 96.0, payload);
    }

    /** Force every nearby client to drop a board that just lost its atlas (clears the stale map even if the
     *  block-entity sync raced with a creative break). */
    public static void broadcastDeactivate(net.minecraft.server.level.ServerLevel level, BlockPos board) {
        PacketDistributor.sendToPlayersNear(level, null,
                board.getX() + 0.5, board.getY() + 0.5, board.getZ() + 0.5, 64.0, new Deactivate(board));
    }

    public static void sendAction(int action, CompoundTag data) {
        PacketDistributor.sendToServer(new Action(action, data));
    }

    public static void openFor(ServerPlayer sp, BlockPos anchor) {
        PacketDistributor.sendToPlayer(sp, new Open(anchor));
    }

    // --- S2C: open the planner --------------------------------------------------------------------

    public record Open(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "warmap_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Open::pos, Open::new);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        static void onClient(Open msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.newtl.warnnobility.warmap.client.WarMapScreen.open(msg.pos));
        }
    }

    // --- S2C: a transient pointer pulse -----------------------------------------------------------

    public record Ping(BlockPos pos, double x, double z, int color) implements CustomPacketPayload {
        public static final Type<Ping> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "warmap_ping"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ping> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeBlockPos(p.pos); buf.writeDouble(p.x); buf.writeDouble(p.z); buf.writeVarInt(p.color); },
                buf -> new Ping(buf.readBlockPos(), buf.readDouble(), buf.readDouble(), buf.readVarInt()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        static void onClient(Ping msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.newtl.warnnobility.warmap.client.ClientWarMaps.ping(msg.pos, msg.x, msg.z, msg.color));
        }
    }

    // --- S2C: a board lost its atlas, drop it -----------------------------------------------------

    public record Deactivate(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Deactivate> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "warmap_deactivate"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Deactivate> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Deactivate::pos, Deactivate::new);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        static void onClient(Deactivate msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.newtl.warnnobility.warmap.client.ClientWarMaps.deactivate(msg.pos));
        }
    }

    // --- S2C: the live units + colonists for a board ----------------------------------------------

    public record Units(BlockPos pos, List<UnitEntry> units, List<DotEntry> dots) implements CustomPacketPayload {
        /** A grouped combat unit: {@code key} is a stable group id (for selection), colour is allegiance. */
        public record UnitEntry(String key, String name, double x, double z, int count, int color, boolean friendly) {}
        /** A colonist / civilian marker: just a coloured dot. */
        public record DotEntry(double x, double z, int color) {}

        public static final Type<Units> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "warmap_units"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Units> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBlockPos(p.pos);
                    buf.writeVarInt(p.units.size());
                    for (UnitEntry u : p.units) {
                        buf.writeUtf(u.key); buf.writeUtf(u.name);
                        buf.writeDouble(u.x); buf.writeDouble(u.z);
                        buf.writeVarInt(u.count); buf.writeInt(u.color); buf.writeBoolean(u.friendly);
                    }
                    buf.writeVarInt(p.dots.size());
                    for (DotEntry d : p.dots) { buf.writeDouble(d.x); buf.writeDouble(d.z); buf.writeInt(d.color); }
                },
                buf -> {
                    BlockPos pos = buf.readBlockPos();
                    int n = buf.readVarInt();
                    List<UnitEntry> units = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) units.add(new UnitEntry(
                            buf.readUtf(), buf.readUtf(), buf.readDouble(), buf.readDouble(),
                            buf.readVarInt(), buf.readInt(), buf.readBoolean()));
                    int m = buf.readVarInt();
                    List<DotEntry> dots = new ArrayList<>(m);
                    for (int i = 0; i < m; i++) dots.add(new DotEntry(buf.readDouble(), buf.readDouble(), buf.readInt()));
                    return new Units(pos, units, dots);
                });

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        static void onClient(Units msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                List<com.newtl.warnnobility.warmap.client.ClientUnits.FieldUnit> us = new ArrayList<>();
                for (UnitEntry u : msg.units) {
                    us.add(new com.newtl.warnnobility.warmap.client.ClientUnits.FieldUnit(
                            UUID.nameUUIDFromBytes(u.key().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            u.key(), u.x(), u.z(), u.name(), u.count(), u.color(), u.friendly(),
                            com.newtl.warnnobility.warmap.Stance.HOLD, false, null));
                }
                List<com.newtl.warnnobility.warmap.client.ClientUnits.MapDot> ds = new ArrayList<>();
                for (DotEntry d : msg.dots) {
                    ds.add(new com.newtl.warnnobility.warmap.client.ClientUnits.MapDot(d.x(), d.z(), d.color()));
                }
                com.newtl.warnnobility.warmap.client.ClientUnits.set(msg.pos, us, ds);
            });
        }
    }

    // --- C2S: a controller's command --------------------------------------------------------------

    public record Action(int action, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "warmap_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeVarInt(p.action); buf.writeNbt(p.data); },
                buf -> new Action(buf.readVarInt(), readTag(buf)));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        static void onServer(Action msg, IPayloadContext ctx) {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            ctx.enqueueWork(() -> apply(msg, sp));
        }
    }

    private static CompoundTag readTag(RegistryFriendlyByteBuf buf) {
        CompoundTag t = buf.readNbt();
        return t == null ? new CompoundTag() : t;
    }

    // --- server-side application ------------------------------------------------------------------

    private static void apply(Action msg, ServerPlayer sp) {
        CompoundTag t = msg.data();
        BlockPos pos = BlockPos.of(t.getLong("Pos"));
        if (sp.level().getBlockEntity(pos) instanceof WarFrameBlockEntity be) {
            be.applyAction(msg.action(), t, sp);
        }
    }

    /** Broadcast a pulse to everyone near the board (called from the BE after a validated A_POINT). */
    public static void broadcastPing(ServerPlayer from, BlockPos boardCentre, double x, double z, int color) {
        PacketDistributor.sendToPlayersNear((net.minecraft.server.level.ServerLevel) from.level(), null,
                boardCentre.getX() + 0.5, boardCentre.getY() + 0.5, boardCentre.getZ() + 0.5, 48.0,
                new Ping(boardCentre, x, z, color));
    }

    // --- tag helpers shared with the client GUI ---------------------------------------------------

    public static CompoundTag base(BlockPos pos) {
        CompoundTag t = new CompoundTag();
        t.putLong("Pos", pos.asLong());
        return t;
    }

    public static WarStroke strokeFromTag(CompoundTag t, UUID author) {
        MapTool tool = MapTool.byId(t.getInt("Tool"));
        int color = t.getInt("Color");
        String label = t.getString("Label");
        int size = t.contains("Size") ? t.getInt("Size") : WarStroke.DEFAULT_SIZE;
        ListTag list = t.getList("Pts", Tag.TAG_DOUBLE);
        double[] pts = new double[list.size()];
        for (int i = 0; i < pts.length; i++) pts[i] = list.getDouble(i);
        return new WarStroke(tool, color, author, label, pts, size);
    }

    public static void putPts(CompoundTag t, double[] pts) {
        ListTag list = new ListTag();
        for (double v : pts) list.add(DoubleTag.valueOf(v));
        t.put("Pts", list);
    }

    static BlockEntity be(ServerPlayer sp, BlockPos pos) {
        return sp.level().getBlockEntity(pos);
    }
}
