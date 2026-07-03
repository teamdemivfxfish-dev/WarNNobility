package com.newtl.warnnobility.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.newtl.warnnobility.ChanceryTableBlockEntity;
import net.minecraft.client.model.BookModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.EnchantTableRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renders a real open book on the Chancery Table's lectern, exactly the way vanilla's lectern shows
 * its book (the enchanting-table book model + texture), so it reads as "a book placed on a lectern"
 * instead of a flat item texture. The static book element was removed from the block model.
 */
public class ChanceryTableRenderer implements BlockEntityRenderer<ChanceryTableBlockEntity> {

    private final BookModel book;

    public ChanceryTableRenderer(BlockEntityRendererProvider.Context ctx) {
        this.book = new BookModel(ctx.bakeLayer(ModelLayers.BOOK));
    }

    @Override
    public void render(ChanceryTableBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(0.5F, 1.0625F, 0.5F);
        BlockState state = be.getBlockState();
        float yaw = state.getValue(HorizontalDirectionalBlock.FACING).getClockWise().toYRot();
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.mulPose(Axis.ZP.rotationDegrees(67.5F));
        pose.translate(0.0F, -0.125F, 0.0F);
        this.book.setupAnim(0.0F, 0.1F, 0.9F, 1.0F);   // 4th arg = open amount; 0 was a CLOSED book
        VertexConsumer vc = EnchantTableRenderer.BOOK_LOCATION.buffer(buffers, RenderType::entitySolid);
        this.book.render(pose, vc, light, OverlayTexture.NO_OVERLAY, -1);
        pose.popPose();
    }
}
