package com.newtl.warnnobility.net;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.nobility.NobilityManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Client -> server. A button press in the Chancery console. The server re-validates everything
 * (the same logic the /nobility commands run), reports the result in chat, then refreshes the open
 * console so the player sees the new state. A NeoForge {@link CustomPacketPayload}.
 */
public class ChanceryActionMsg implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ChanceryActionMsg> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "chancery_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChanceryActionMsg> STREAM_CODEC =
            StreamCodec.ofMember(ChanceryActionMsg::write, ChanceryActionMsg::decode);

    public static final byte ADVANCE = 0;
    public static final byte SEAL = 1;
    public static final byte GENDER = 2;   // toggle masculine/feminine identity
    public static final byte DOMAIN = 3;   // name the holder's county/duchy/realm (text)

    private final byte action;
    private final UUID target;      // used by SEAL
    private final String text;      // used by DOMAIN

    public ChanceryActionMsg(byte action, UUID target, String text) {
        this.action = action;
        this.target = target == null ? new UUID(0L, 0L) : target;
        this.text = text == null ? "" : text;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeByte(action);
        buf.writeUUID(target);
        buf.writeUtf(text);
    }

    public static ChanceryActionMsg decode(RegistryFriendlyByteBuf buf) {
        return new ChanceryActionMsg(buf.readByte(), buf.readUUID(), buf.readUtf());
    }

    @Override
    public CustomPacketPayload.Type<ChanceryActionMsg> type() {
        return TYPE;
    }

    /** Runs on the server (playToServer): apply the action, then refresh the console. */
    public static void handleServer(ChanceryActionMsg m, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;

            NobilityManager mgr = NobilityManager.get(server);
            NobilityManager.Result result;

            switch (m.action) {
                case ADVANCE -> result = mgr.promoteSelf(player);
                case SEAL -> {
                    ServerPlayer t = server.getPlayerList().getPlayer(m.target);
                    result = t == null ? NobilityManager.Result.fail("That noble is no longer online.")
                            : mgr.seal(player, t);
                }
                case GENDER -> result = mgr.setGender(player, !mgr.getOrCreate(player).female);
                case DOMAIN -> result = mgr.setDomainName(player, m.text);
                default -> result = NobilityManager.Result.fail("Unknown action.");
            }

            player.sendSystemMessage(Component.literal(result.message())
                    .withStyle(result.success() ? ChatFormatting.GOLD : ChatFormatting.RED));
            Network.toClient(player, ChanceryConsole.build(server, player));
        });
    }
}
