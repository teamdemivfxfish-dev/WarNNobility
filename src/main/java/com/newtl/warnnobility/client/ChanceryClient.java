package com.newtl.warnnobility.client;

import com.newtl.warnnobility.net.OpenChanceryMsg;
import net.minecraft.client.Minecraft;

/** Client-only entry point, invoked via DistExecutor from the message handler. */
public final class ChanceryClient {

    private ChanceryClient() {}

    public static void openConsole(OpenChanceryMsg msg) {
        Minecraft.getInstance().setScreen(new ChanceryScreen(msg));
    }
}
