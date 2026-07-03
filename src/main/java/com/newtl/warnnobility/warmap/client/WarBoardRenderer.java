package com.newtl.warnnobility.warmap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.newtl.warnnobility.warmap.Multiblock;
import com.newtl.warnnobility.warmap.WarFrameBlockEntity;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws an assembled War Table by blitting its baked map texture (see {@link WarBoardTextures}) onto
 * the 3x3 face as one double-sided quad. Only the anchor frame draws, and it paints across all nine
 * panels. The quad is positioned/oriented for the board's facing and table rotation; drawing it
 * double-sided (cull off) means it is always visible regardless of which way Antique Atlas wound its
 * tiles, and the texture being a clipped framebuffer means nothing spills past the board.
 */
public class WarBoardRenderer implements BlockEntityRenderer<WarFrameBlockEntity> {

    public static final int BOARD_PX = Multiblock.SIZE * 64;   // board face resolution (64 px/block)
    private static final float PX = 1f / 64f;                  // pixel -> block
    private static final float PROUD = 0.0125f;                // lift off the slate (blocks)

    public WarBoardRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public boolean shouldRenderOffScreen(WarFrameBlockEntity be) {
        return true;
    }

    @Override
    public void render(WarFrameBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (!be.isActive()) return;
        ClientWarMaps.markActive(be.getBlockPos());           // tell the baker this board is live
        int tex = WarBoardTextures.texture(be.getBlockPos());
        if (tex < 0) return;                                  // not baked yet this session; slate shows

        pose.pushPose();
        try {
            faceTransform(pose, be.facing(), be.data().rotation);
            blit(pose, tex, tint(be));
        } catch (Throwable ignored) {
        } finally {
            pose.popPose();
        }
    }

    /**
     * A grey tint tracking the light in front of the board, so the map is lit like the world instead of
     * glowing at night. Reads the exposed air block on the face side ({@code getMaxLocalRawBrightness} already
     * folds in the day/night sky curve + nearby torches); a small floor keeps it legible in the dark.
     */
    private static int tint(WarFrameBlockEntity be) {
        try {
            net.minecraft.world.level.Level lvl = be.getLevel();
            if (lvl == null) return -1;
            net.minecraft.core.BlockPos face = be.getBlockPos().relative(be.facing());
            int raw = lvl.getMaxLocalRawBrightness(face);       // 0..15, already day/night adjusted
            float b = Math.max(0.28f, raw / 15f);
            int v = (int) (b * 255f);
            return 0xFF000000 | (v << 16) | (v << 8) | v;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Draw the baked texture as a double-sided quad over the board face (pixel space [0,BOARD_PX]). */
    private static void blit(PoseStack pose, int textureId, int tint) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, textureId);
        WarBoardTextures.forceNearest(textureId);   // crisp pixels, not LINEAR blur
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();   // double-sided: always faces the viewer

        Matrix4f m = pose.last().pose();
        float s = BOARD_PX;
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        // v is flipped (framebuffer textures are bottom-up): board top -> v=1. Colour = world-light tint.
        bb.addVertex(m, 0, 0, 0).setUv(0f, 1f).setColor(tint);
        bb.addVertex(m, 0, s, 0).setUv(0f, 0f).setColor(tint);
        bb.addVertex(m, s, s, 0).setUv(1f, 0f).setColor(tint);
        bb.addVertex(m, s, 0, 0).setUv(1f, 1f).setColor(tint);
        com.mojang.blaze3d.vertex.MeshData mesh = bb.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);

        RenderSystem.enableCull();
    }

    /** Map the pixel-space board (origin top-left, +x right, +y down) onto the world face. */
    private static void faceTransform(PoseStack pose, Direction facing, int rotation) {
        Vector3f u, v, w = vec(facing);
        if (Multiblock.isFlat(facing)) {
            Direction uDir = Direction.EAST, vDir = Direction.SOUTH;
            for (int i = 0; i < (rotation & 3); i++) { uDir = uDir.getClockWise(); vDir = vDir.getClockWise(); }
            u = vec(uDir);
            v = vec(vDir);
            if (facing == Direction.DOWN) v.mul(-1);
        } else {
            // The viewer stands in front of the face looking back at it, so their right hand is the
            // COUNTER-clockwise turn from the facing. Using clockwise mirrored the whole map left-right.
            u = vec(facing.getCounterClockWise());
            v = new Vector3f(0, -1, 0);
        }
        Direction ua = Multiblock.uAxis(facing), va = Multiblock.vAxis(facing);
        Vector3f centreCell = new Vector3f(
                ua.getStepX() + va.getStepX() + 0.5f,
                ua.getStepY() + va.getStepY() + 0.5f,
                ua.getStepZ() + va.getStepZ() + 0.5f);
        // The frame panel hugs the support (opposite w), so its visible face is 0.375 toward -w from the
        // cell centre; sit the map there, lifted a hair toward the viewer. (The old +w floated it ~0.7
        // blocks off the frame, which also made left-clicks miss and hit the wall behind.)
        Vector3f cFace = new Vector3f(centreCell).add(new Vector3f(w).mul(PROUD - 0.375f));

        Matrix4f mat = pose.last().pose();
        mat.translate(cFace.x, cFace.y, cFace.z);
        Matrix3f basis = new Matrix3f(u, v, w);
        mat.mul(new Matrix4f().set(basis));
        pose.last().normal().mul(basis);
        mat.scale(PX, PX, PX);
        mat.translate(-BOARD_PX / 2f, -BOARD_PX / 2f, 0f);
    }

    private static Vector3f vec(Direction d) {
        return new Vector3f(d.getStepX(), d.getStepY(), d.getStepZ());
    }
}
