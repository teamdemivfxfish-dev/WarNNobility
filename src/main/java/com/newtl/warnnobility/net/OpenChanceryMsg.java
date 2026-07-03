package com.newtl.warnnobility.net;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.client.ChanceryClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server -> client. Opens (or refreshes) the Chancery Table console with the viewer's filtered
 * standing, the advance option, and the peers they may seal. Everything is computed server-side;
 * the client only renders and clicks. A NeoForge {@link CustomPacketPayload}.
 */
public class OpenChanceryMsg implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenChanceryMsg> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "open_chancery"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenChanceryMsg> STREAM_CODEC =
            StreamCodec.ofMember(OpenChanceryMsg::write, OpenChanceryMsg::decode);

    public record Target(UUID id, String name) {}

    private final String title;
    private final List<String> standing;
    private final boolean canAdvance;
    private final String advanceLabel;
    private final String advanceHint;
    private final List<Target> sealTargets;
    private final boolean female;        // current identity, for the toggle label
    private final boolean holdsDomain;   // Count+ : show the domain-name field
    private final String domainName;     // current domain name (prefill)
    private final String domainKind;     // "county" / "duchy" / "realm"

    public OpenChanceryMsg(String title, List<String> standing, boolean canAdvance,
                           String advanceLabel, String advanceHint, List<Target> sealTargets,
                           boolean female, boolean holdsDomain, String domainName, String domainKind) {
        this.title = title;
        this.standing = standing;
        this.canAdvance = canAdvance;
        this.advanceLabel = advanceLabel;
        this.advanceHint = advanceHint;
        this.sealTargets = sealTargets;
        this.female = female;
        this.holdsDomain = holdsDomain;
        this.domainName = domainName;
        this.domainKind = domainKind;
    }

    public String title() { return title; }
    public List<String> standing() { return standing; }
    public boolean canAdvance() { return canAdvance; }
    public String advanceLabel() { return advanceLabel; }
    public String advanceHint() { return advanceHint; }
    public List<Target> sealTargets() { return sealTargets; }
    public boolean female() { return female; }
    public boolean holdsDomain() { return holdsDomain; }
    public String domainName() { return domainName; }
    public String domainKind() { return domainKind; }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(title);
        buf.writeVarInt(standing.size());
        for (String s : standing) buf.writeUtf(s);
        buf.writeBoolean(canAdvance);
        buf.writeUtf(advanceLabel);
        buf.writeUtf(advanceHint, 32767);
        buf.writeVarInt(sealTargets.size());
        for (Target t : sealTargets) {
            buf.writeUUID(t.id());
            buf.writeUtf(t.name());
        }
        buf.writeBoolean(female);
        buf.writeBoolean(holdsDomain);
        buf.writeUtf(domainName);
        buf.writeUtf(domainKind);
    }

    public static OpenChanceryMsg decode(RegistryFriendlyByteBuf buf) {
        String title = buf.readUtf();
        int sn = buf.readVarInt();
        List<String> standing = new ArrayList<>(sn);
        for (int i = 0; i < sn; i++) standing.add(buf.readUtf());
        boolean canAdvance = buf.readBoolean();
        String label = buf.readUtf();
        String hint = buf.readUtf(32767);
        int tn = buf.readVarInt();
        List<Target> seals = new ArrayList<>(tn);
        for (int i = 0; i < tn; i++) seals.add(new Target(buf.readUUID(), buf.readUtf()));
        boolean female = buf.readBoolean();
        boolean holdsDomain = buf.readBoolean();
        String domainName = buf.readUtf();
        String domainKind = buf.readUtf();
        return new OpenChanceryMsg(title, standing, canAdvance, label, hint, seals,
                female, holdsDomain, domainName, domainKind);
    }

    @Override
    public CustomPacketPayload.Type<OpenChanceryMsg> type() {
        return TYPE;
    }

    /** Runs on the client (playToClient): open/refresh the console screen. */
    public static void handleClient(OpenChanceryMsg msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChanceryClient.openConsole(msg));
    }
}
