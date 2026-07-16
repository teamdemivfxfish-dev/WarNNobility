package com.newtl.warnnobility.warmap.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.atlas.client.ClientDiscoveries;
import com.newtl.warnnobility.domain.client.ClientDomains;
import com.newtl.warnnobility.warmap.WarFrameBlockEntity;
import com.newtl.warnnobility.warmap.WarMapData;
import com.newtl.warnnobility.warmap.WarStroke;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Bakes each active War Table into offscreen textures the {@link WarBoardRenderer} draws as quads on the
 * board. Baking to a framebuffer is what makes the in-world map work at all: Antique Atlas's
 * {@code renderTiles} draws the WHOLE explored map with no viewport clip, so driven straight into the world
 * it spills everywhere; inside a fixed-size framebuffer the spill is simply clipped.
 *
 * <p><b>Two layers, cached independently.</b> A room full of officers at one board used to tank FPS because
 * every client re-baked the ENTIRE board (atlas tiles + borders + labels + plan + units) every single
 * frame. Now each board keeps two textures:
 * <ul>
 *   <li>a <b>base</b> (parchment + tiles + borders + labels) that is expensive but changes rarely, re-baked
 *       only when the view pans/zooms, the domain data changes, or a slow timer catches newly-explored
 *       tiles; and</li>
 *   <li>an <b>overlay</b> (the drawn plan + pings + live units) that is cheap and re-baked only when it
 *       actually changes (a stroke synced, a ping alive, units moved) so watching someone draw stays
 *       real-time without dragging the atlas along.</li>
 * </ul>
 * On top of that the baker only touches boards actually rendered this frame and within {@link #BAKE_RANGE}
 * blocks, so a war frame you have walked away from or turned your back on costs nothing.
 */
@EventBusSubscriber(modid = WarNNobility.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class WarBoardTextures {

    private WarBoardTextures() {}

    /** Texture resolution per board (square). Higher = crisper board when viewed close. */
    public static final int FBO = 512;

    /** Only bake boards this close to the camera (blocks). Past this you can't read the map anyway, so an
     *  out-of-range board just keeps its last texture and stops costing anything. */
    private static final double BAKE_RANGE = 64.0;
    /** A board not rendered within this many millis is treated as off-screen and skipped by the baker. */
    private static final long SEEN_TIMEOUT_MS = 500L;
    /** Force a base re-bake at least this often, to fold in newly-explored atlas tiles (which change the
     *  map without any view/domain change to key off). */
    private static final long EXPLORE_REFRESH_MS = 1500L;

    private static final Map<BlockPos, RenderTarget> BASE = new HashMap<>();
    private static final Map<BlockPos, RenderTarget> OVERLAY = new HashMap<>();
    private static final Map<BlockPos, AtlasBoard> BOARDS = new HashMap<>();   // cached mapping (shared by both layers)
    private static final Map<BlockPos, Long> BASE_KEY = new HashMap<>();
    private static final Map<BlockPos, Long> OVERLAY_KEY = new HashMap<>();
    private static final Map<BlockPos, Long> BASE_TIME = new HashMap<>();
    private static final Map<BlockPos, Boolean> PING_LAST = new HashMap<>();

    /** GL texture id for a board's baked BASE map (tiles/borders/labels), or -1 if not baked yet. */
    public static int baseTexture(BlockPos pos) {
        RenderTarget rt = BASE.get(pos);
        return rt == null ? -1 : rt.getColorTextureId();
    }

    /** GL texture id for a board's baked OVERLAY (plan/pings/units), or -1 if not baked yet. */
    public static int overlayTexture(BlockPos pos) {
        RenderTarget rt = OVERLAY.get(pos);
        return rt == null ? -1 : rt.getColorTextureId();
    }

    /** Drop a board's baked textures (called when it loses its atlas). */
    public static void remove(BlockPos pos) {
        RenderTarget b = BASE.remove(pos);
        if (b != null) b.destroyBuffers();
        RenderTarget o = OVERLAY.remove(pos);
        if (o != null) o.destroyBuffers();
        BOARDS.remove(pos);
        BASE_KEY.remove(pos);
        OVERLAY_KEY.remove(pos);
        BASE_TIME.remove(pos);
        PING_LAST.remove(pos);
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

        Camera cam = mc.gameRenderer.getMainCamera();
        double camX = cam.getPosition().x, camY = cam.getPosition().y, camZ = cam.getPosition().z;
        long now = net.minecraft.Util.getMillis();

        for (BlockPos pos : new ArrayList<>(ClientWarMaps.activeBoards())) {
            if (!(mc.level.getBlockEntity(pos) instanceof WarFrameBlockEntity be) || !be.isActive()) {
                remove(pos);
                ClientWarMaps.forget(pos);
                continue;
            }
            // Visibility + distance cull: don't bake a board we aren't looking at or are too far to read.
            // Its textures persist untouched, so looking back at it resumes instantly.
            if (ClientWarMaps.sinceSeen(pos) > SEEN_TIMEOUT_MS) continue;
            double dx = pos.getX() + 0.5 - camX, dy = pos.getY() + 0.5 - camY, dz = pos.getZ() + 0.5 - camZ;
            if (dx * dx + dy * dy + dz * dz > BAKE_RANGE * BAKE_RANGE) continue;
            try {
                bakeIfDirty(mc, pos, be, now);
            } catch (Throwable t) {
                // a bake failure just leaves last frame's texture; never crash the level render
            }
        }
    }

    /** Force a board's textures up to date right now (used by the planner so it never depends on the world
     *  having rendered the board this frame). Safe no-op if the board isn't loaded/active. */
    public static void ensureBaked(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof WarFrameBlockEntity be && be.isActive()) {
            try { bakeIfDirty(mc, pos, be, net.minecraft.Util.getMillis()); } catch (Throwable ignored) {}
        }
    }

    /** Re-bake only the layers whose inputs changed since last frame. */
    private static void bakeIfDirty(Minecraft mc, BlockPos pos, WarFrameBlockEntity be, long now) {
        WarMapData d = be.data();
        ResourceKey<Level> dim = be.getLevel().dimension();

        long bKey = baseKey(d, dim);
        boolean baseDirty = !BASE.containsKey(pos)
                || bKey != BASE_KEY.getOrDefault(pos, Long.MIN_VALUE)
                || (now - BASE_TIME.getOrDefault(pos, 0L)) > EXPLORE_REFRESH_MS;
        if (baseDirty) {
            AtlasBoard ab = AtlasBoard.create(mc.player, dim, FBO, FBO, d.centerX, d.centerZ, d.zoom);
            BOARDS.put(pos.immutable(), ab);
            renderLayer(mc, target(BASE, pos), d, pos, ab, true);
            BASE_KEY.put(pos.immutable(), bKey);
            BASE_TIME.put(pos.immutable(), now);
        }

        // A live ping animates (fades) every frame, so keep the overlay dirty while any pulse lives, plus one
        // final bake the frame after the last one dies (so it clears). Everything else keys off content.
        boolean pingNow = !ClientWarMaps.pulsesFor(pos).isEmpty();
        long oKey = overlayKey(d, pos);
        boolean overlayDirty = baseDirty                                             // mapping moved -> marks reposition
                || !OVERLAY.containsKey(pos)
                || oKey != OVERLAY_KEY.getOrDefault(pos, Long.MIN_VALUE)
                || pingNow || PING_LAST.getOrDefault(pos, false);
        if (overlayDirty) {
            AtlasBoard ab = BOARDS.get(pos);
            if (ab == null) {
                ab = AtlasBoard.create(mc.player, dim, FBO, FBO, d.centerX, d.centerZ, d.zoom);
                BOARDS.put(pos.immutable(), ab);
            }
            renderLayer(mc, target(OVERLAY, pos), d, pos, ab, false);
            OVERLAY_KEY.put(pos.immutable(), oKey);
            PING_LAST.put(pos.immutable(), pingNow);
        }
    }

    /** Bake key for the static layer: anything that changes the tiles, borders, or labels. */
    private static long baseKey(WarMapData d, ResourceKey<Level> dim) {
        long h = Double.doubleToLongBits(d.centerX);
        h = h * 1000003 + Double.doubleToLongBits(d.centerZ);
        h = h * 1000003 + d.zoom;
        h = h * 1000003 + dim.location().hashCode();
        h = h * 1000003 + ClientDomains.version();   // borders + labels + colony markers + legend toggles
        h = h * 1000003 + ClientDiscoveries.version();   // revealed player-structure markers
        return h;
    }

    /** Bake key for the live layer: the plan, the show-units toggle, and the unit roster revision. */
    private static long overlayKey(WarMapData d, BlockPos pos) {
        long h = d.showUnits ? 1 : 0;
        h = h * 1000003 + ClientUnits.rev(pos);
        long s = d.strokes.size();
        for (WarStroke k : d.strokes) {
            s = s * 1000003 + (k.tool == null ? 0 : k.tool.ordinal());
            s = s * 31 + k.color;
            s = s * 31 + k.size;
            s = s * 31 + (k.pts == null ? 0 : k.pts.length);
            s = s * 31 + (k.label == null ? 0 : k.label.hashCode());
        }
        return h * 1000003 + s;
    }

    private static RenderTarget target(Map<BlockPos, RenderTarget> into, BlockPos pos) {
        return into.computeIfAbsent(pos.immutable(), k -> {
            TextureTarget t = new TextureTarget(FBO, FBO, true, Minecraft.ON_OSX);
            t.setFilterMode(org.lwjgl.opengl.GL11.GL_NEAREST);   // crisp pixel-art tiles, no blur
            return t;
        });
    }

    /** Render one layer (base or overlay) into its framebuffer. Shares the FBO bind / ortho / fog-suppress
     *  plumbing the single-texture bake used to do; only the content call differs. */
    private static void renderLayer(Minecraft mc, RenderTarget rt, WarMapData d, BlockPos pos,
                                    AtlasBoard ab, boolean baseLayer) {
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
        if (baseLayer) WarMapDraw.renderBase(g, 0, 0, FBO, ab);
        else WarMapDraw.renderOverlay(g, 0, 0, FBO, ab, d, pos);
        g.flush();

        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        main.bindWrite(true);
        RenderSystem.setProjectionMatrix(oldProj, oldSort);
    }
}
