package com.newtl.warnnobility.client;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.domain.client.HandheldDomainOverlay;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client mod-bus setup: the Chancery Table's book renderer + the in-hand atlas border overlay. */
@EventBusSubscriber(modid = WarNNobility.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WnNClientSetup {

    private WnNClientSetup() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(WarNNobility.CHANCERY_TABLE_BE.get(), ChanceryTableRenderer::new);
        event.registerBlockEntityRenderer(WarNNobility.WAR_FRAME_BE.get(),
                com.newtl.warnnobility.warmap.client.WarBoardRenderer::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Hook our domain borders into Antique Atlas's in-hand (held) map view, if AA is present.
        event.enqueueWork(HandheldDomainOverlay::register);
    }
}
