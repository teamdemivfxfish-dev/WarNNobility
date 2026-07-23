package com.newtl.warnnobility.warmap.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.warmap.WarFrameBlockEntity;
import com.newtl.warnnobility.warmap.WarMapData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Bakes each active War Table into its own offscreen texture once per frame, then the
 * {@link WarBoardRenderer} draws that texture as a single quad on the board. Baking to a framebuffer
 * is what makes the in-world map work at all: Antique Atlas's {@code renderTiles} draws the WHOLE
 * explored map with no viewport clip, so driven straight into the world it spills everywhere and lands
 * in the wrong plane on a wall. Inside a fixed-size framebuffer the spill is simply clipped, the result
 * is a clean 2D image, and we control exactly where and how it lands (and that it is double-sided).
 */
@EventBusSubscriber(modid = WarNNobility.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class WarBoardTextures {

    private WarBoardTextures() {}

    /** Texture resolution per board (square). Higher = crisper board when viewed close. */
    public static final int FBO = 512;

    private static final Map<BlockPos, RenderTarget> TARGETS = new HashMap<>();

    /** GL texture id for a board's baked map, or -1 if it has not been baked yet. */
    public static int texture(BlockPos pos) {
        RenderTarget rt = TARGETS.get(pos);
        return rt == null ? -1 : rt.getColorTextureId();
    }

    /** Drop a board's baked texture (called when it loses its atlas). */
    public static void remove(BlockPos pos) {
        RenderTarget rt = TARGETS.remove(pos);
        if (rt != null) rt.destroyBuffers();
    }

    /** Blit a board's baked (NEAREST, crisp) texture into a GUI rect, so the planner matches the board. */
    public static void blitInto(GuiGraphics g, int tex, int x, int y, int w, int h) {
        if (tex < 0) return;
        g.flush();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, tex);
        forceNearest(tex);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();   // the quad is wound for the world; without this the GUI culls it = blank
        Matrix4f m = g.pose().last().pose();
        com.mojang.blaze3d.vertex.Tesselator tess = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder bb = tess.begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.addVertex(m, x, y, 0).setUv(0f, 1f).setColor(-1);                 // v flipped: FBO is bottom-up
        bb.addVertex(m, x, y + h, 0).setUv(0f, 0f).setColor(-1);
        bb.addVertex(m, x + w, y + h, 0).setUv(1f, 0f).setColor(-1);
        bb.addVertex(m, x + w, y, 0).setUv(1f, 1f).setColor(-1);
        com.mojang.blaze3d.vertex.MeshData mesh = bb.build();
        if (mesh != null) com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(mesh);
        RenderSystem.enableCull();
    }

    /** Force NEAREST on the bound texture. setFilterMode only takes effect through bindRead(), which we
     *  bypass by binding the raw id, so the FBO sampled LINEAR (blurry) without this. */
    public static void forceNearest(int tex) {
        RenderSystem.bindTexture(tex);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        for (BlockPos pos : new ArrayList<>(ClientWarMaps.activeBoards())) {
            if (!(mc.level.getBlockEntity(pos) instanceof WarFrameBlockEntity be) || !be.isActive()) {
                RenderTarget old = TARGETS.remove(pos);
                if (old != null) old.destroyBuffers();
                ClientWarMaps.forget(pos);
                continue;
            }
            try {
                bake(mc, pos, be);
            } catch (Throwable t) {
                // a bake failure just leaves last frame's texture; never crash the level render
            }
        }
    }

    /** Bake a board's texture right now (used by the planner so it never depends on the world having
     *  rendered the board this frame). Safe no-op if the board isn't loaded/active. */
    public static void ensureBaked(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof WarFrameBlockEntity be && be.isActive()) {
            try { bake(mc, pos, be); } catch (Throwable ignored) {}
        }
    }

    private static void bake(Minecraft mc, BlockPos pos, WarFrameBlockEntity be) {
        WarMapData d = be.data();
        AtlasBoard ab = AtlasBoard.create(mc.player, be.getLevel().dimension(),
                FBO, FBO, d.centerX, d.centerZ, d.zoom);
        RenderTarget rt = TARGETS.computeIfAbsent(pos.immutable(), k -> {
            TextureTarget t = new TextureTarget(FBO, FBO, true, Minecraft.ON_OSX);
            t.setFilterMode(org.lwjgl.opengl.GL11.GL_NEAREST);   // crisp pixel-art tiles, no blur
            return t;
        });

        RenderTarget main = mc.getMainRenderTarget();
        Matrix4f oldProj = RenderSystem.getProjectionMatrix();
        VertexSorting oldSort = RenderSystem.getVertexSorting();

        rt.setClearColor(0f, 0f, 0f, 0f);
        rt.clear(Minecraft.ON_OSX);
        rt.bindWrite(true);
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0f, FBO, FBO, 0f, -1000f, 1000f), VertexSorting.ORTHOGRAPHIC_Z);

        // Suppress the world's distance fog for the WHOLE bake. By day the fog colour is bright sky-blue
        // and it washed both the tiles AND the name-label text (which ghosted/doubled) to pale blue; the
        // bake runs mid-world-render so the fog is live. Restore it after so the rest of the world fogs.
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        WarMapDraw.renderContent(g, 0, 0, FBO, ab, d, pos);
        g.flush();

        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        main.bindWrite(true);
        RenderSystem.setProjectionMatrix(oldProj, oldSort);
    }
}
