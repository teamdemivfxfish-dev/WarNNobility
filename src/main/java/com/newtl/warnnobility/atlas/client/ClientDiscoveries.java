package com.newtl.warnnobility.atlas.client;

import com.newtl.warnnobility.atlas.Discovery;
import com.newtl.warnnobility.atlas.net.DiscoveryPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Client store of the player-structure discoveries the server has revealed to us, for the dimension we are
 * in. Fed by {@link DiscoveryPayload}; read by the atlas + War Frame marker layer. A {@link #version()}
 * counter lets the War Frame's cached bake know when the markers changed.
 */
public final class ClientDiscoveries {

    private ClientDiscoveries() {}

    private static ResourceLocation dimension;
    private static List<Discovery> list = List.of();
    private static int version = 0;

    public static void accept(DiscoveryPayload msg) {
        dimension = msg.dimension();
        list = msg.discoveries() != null ? msg.discoveries() : List.of();
        version++;
    }

    /** Discoveries to draw, or empty if we have none or have moved to a different dimension than they cover. */
    public static List<Discovery> list() {
        if (dimension == null || list.isEmpty()) return List.of();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().equals(dimension)) return List.of();
        return list;
    }

    public static int version() { return version; }
}
