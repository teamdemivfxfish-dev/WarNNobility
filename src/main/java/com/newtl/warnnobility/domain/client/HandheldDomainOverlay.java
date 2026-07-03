package com.newtl.warnnobility.domain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.domain.DomainEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Draws the toggled-on domain borders on the IN-HAND atlas (the small map Antique Atlas 4 shows while
 * you merely hold the atlas), not just the opened full-screen book.
 *
 * <p>AA4 exposes an overlay API: {@code AtlasRenderer.registerOverlay(id, overlay)} whose
 * {@code onRender(AtlasRenderContext)} hook fires for the generic {@code AtlasRenderer}, and the
 * handheld view's {@code HandheldAtlasRenderer} implements that same interface, so one hook covers
 * both. We register reflectively (a dynamic proxy of {@code AtlasOverlay}) so this NeoForge mod never
 * compiles against AA's Fabric/Yarn types, and we draw only when the renderer is the handheld one
 * (the open book is handled by {@link DomainOverlay}'s screen hook).
 *
 * <p>The toggles live only in the opened book ({@link ClientDomains#enabled}); holding the atlas just
 * reads that same set, so the in-hand view shows whatever the player last switched on. Everything is
 * wrapped so a mismatch with a future AA version simply draws nothing and never breaks AA's render.
 */
public final class HandheldDomainOverlay {

    private HandheldDomainOverlay() {}

    private static final String ATLAS_RENDERER = "folk.sisby.antique_atlas.gui.AtlasRenderer";
    private static final String ATLAS_OVERLAY = "folk.sisby.antique_atlas.gui.AtlasOverlay";
    private static final String HANDHELD = "folk.sisby.antique_atlas.gui.HandheldAtlasRenderer";

    private static boolean registered = false;
    // resolved from the live context/renderer on first render
    private static Method M_RENDERER, M_MATRICES, M_BUFFERS, M_W2SX, M_W2SY;
    private static Method M_BOOKX, M_BOOKY, M_MAPW, M_MAPH;
    private static int mapBorderW = 11, mapBorderH = 11;   // AtlasRenderer.MAP_BORDER_* (fallbacks)

    /** Register our overlay with Antique Atlas, once, if it is present. Safe no-op otherwise. */
    public static void register() {
        if (registered || !ModList.get().isLoaded("antique_atlas")) return;
        registered = true;
        try {
            Class<?> rendererCls = Class.forName(ATLAS_RENDERER);
            Class<?> overlayItf = Class.forName(ATLAS_OVERLAY);
            Object proxy = Proxy.newProxyInstance(overlayItf.getClassLoader(),
                    new Class[]{overlayItf}, new Handler());
            Method reg = rendererCls.getMethod("registerOverlay", ResourceLocation.class, overlayItf);
            reg.invoke(null, ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "domain_borders"), proxy);
            WarNNobility.LOGGER.info("[Domain] registered the in-hand atlas border overlay.");
        } catch (Throwable t) {
            WarNNobility.LOGGER.warn("[Domain] could not register the in-hand atlas overlay (AA API drift); "
                    + "borders will still show in the opened book.", t);
        }
    }

    /** Dispatches AtlasOverlay's methods; only onRender does work, the rest are no-ops. */
    private static final class Handler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "onRender" -> { try { onRender(args[0]); } catch (Throwable ignored) {} }
                case "equals" -> { return proxy == (args != null ? args[0] : null); }
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "toString" -> { return "WarNNobilityDomainBorders"; }
                default -> { /* onScreenInit / onScreenRender: handled by the screen hook */ }
            }
            return null;
        }
    }

    private static void onRender(Object ctx) throws Exception {
        if (ctx == null) return;
        if (M_RENDERER == null) {
            Class<?> c = ctx.getClass();
            M_RENDERER = c.getMethod("renderer");
            M_MATRICES = c.getMethod("matrices");
            M_BUFFERS = c.getMethod("vertexConsumers");
        }
        Object renderer = M_RENDERER.invoke(ctx);
        if (renderer == null || !renderer.getClass().getName().equals(HANDHELD)) return;   // in-hand only
        if (M_W2SX == null) {
            Class<?> rc = renderer.getClass();
            M_W2SX = rc.getMethod("worldXToScreenX", double.class);
            M_W2SY = rc.getMethod("worldZToScreenY", double.class);
            M_BOOKX = rc.getMethod("bookX");
            M_BOOKY = rc.getMethod("bookY");
            M_MAPW = rc.getMethod("mapWidth");
            M_MAPH = rc.getMethod("mapHeight");
            try {
                Class<?> ar = Class.forName(ATLAS_RENDERER);
                mapBorderW = ar.getField("MAP_BORDER_WIDTH").getInt(null);
                mapBorderH = ar.getField("MAP_BORDER_HEIGHT").getInt(null);
            } catch (Throwable ignored) { /* keep the 11px fallbacks */ }
        }
        PoseStack pose = (PoseStack) M_MATRICES.invoke(ctx);
        MultiBufferSource buffers = (MultiBufferSource) M_BUFFERS.invoke(ctx);
        Matrix4f mat = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.gui());

        // The visible map box (local pixel space, same coords worldXToScreenX returns), so we can clip
        // everything to it instead of letting it spill past the atlas frame like the screen scissor does.
        int bx = (Integer) M_BOOKX.invoke(renderer), by = (Integer) M_BOOKY.invoke(renderer);
        int mw = (Integer) M_MAPW.invoke(renderer), mh = (Integer) M_MAPH.invoke(renderer);
        float cx0 = bx + mapBorderW, cy0 = by + mapBorderH, cx1 = cx0 + mw, cy1 = cy0 + mh;

        for (int[] e : DomainOverlay.borderEdges()) drawEdge(mat, vc, renderer, e, cx0, cy0, cx1, cy1);
        for (int[] e : DomainOverlay.factionBorderEdges()) drawEdge(mat, vc, renderer, e, cx0, cy0, cx1, cy1);

        // town markers + names follow the Colonies toggle (matching the opened book), only those whose
        // centre lies inside the visible map. Names go through a de-collision pass so overlapping
        // settlements don't garble into one another.
        Font font = Minecraft.getInstance().font;
        List<float[]> placedNames = new java.util.ArrayList<>();
        if (ClientDomains.isEnabled(ClientDomains.L_COLONIES))
        for (DomainEngine.Label l : ClientDomains.colonyMarkers()) {
            float x = f(M_W2SX.invoke(renderer, (double) l.blockX()));
            float y = f(M_W2SY.invoke(renderer, (double) l.blockZ()));
            if (x < cx0 || x > cx1 || y < cy0 || y > cy1) continue;
            drawTown(mat, vc, x, y, (l.rgb() & 0xFFFFFF) | 0xFF000000, cx0, cy0, cx1, cy1);
            String name = l.name();
            if (name != null && !name.isEmpty()) {
                float tw = font.width(name);
                float nx = x - tw / 2f;
                float ny = placeName(placedNames, nx, tw, y + 6f, font.lineHeight + 1f);
                if (nx >= cx0 && nx + tw <= cx1 && ny >= cy0 && ny + font.lineHeight <= cy1) {
                    font.drawInBatch(name, nx, ny, 0xFFEFE6CF, true, mat, buffers,
                            Font.DisplayMode.NORMAL, 0, 0xF000F0);
                }
            }
        }
    }

    /** Pick a name Y at or below {@code desiredY} that does not overlap an already-placed name; record
     *  and return it, so overlapping settlement names stack instead of smearing together. */
    private static float placeName(List<float[]> placed, float x0, float tw, float desiredY, float step) {
        float x1 = x0 + tw, y = desiredY;
        boolean moved = true;
        int guard = 0;
        while (moved && guard++ < 32) {
            moved = false;
            for (float[] r : placed) {
                if (x0 < r[2] && x1 > r[0] && y < r[3] && (y + step) > r[1]) {
                    y = r[3] + 1f;
                    moved = true;
                    break;
                }
            }
        }
        placed.add(new float[]{x0, y, x1, y + step});
        return y;
    }

    private static float f(Object boxed) { return (float) (double) (Double) boxed; }

    /** Lift the overlay a hair toward the camera so its thin quads stop z-fighting (flickering) against
     *  Antique Atlas's coplanar map. The handheld view is seen from the -z side (a +z offset hid the
     *  border BEHIND the map), so the toward-camera direction is NEGATIVE z. */
    private static final float Z = -1f;

    private static void drawEdge(Matrix4f mat, VertexConsumer vc, Object r, int[] e,
                                 float cx0, float cy0, float cx1, float cy1) throws Exception {
        int cx = e[0], cz = e[1], side = e[2];
        int rgb = e[3] & 0xFFFFFF;
        float x0 = f(M_W2SX.invoke(r, (double) (cx * 16)));
        float x1 = f(M_W2SX.invoke(r, (double) ((cx + 1) * 16)));
        float y0 = f(M_W2SY.invoke(r, (double) (cz * 16)));
        float y1 = f(M_W2SY.invoke(r, (double) ((cz + 1) * 16)));
        // Same hand-drawn coloured-pencil stroke as the opened book: a faint soft base plus grainy,
        // jittered dots seeded by chunk so it reads as a sketched line rather than a solid neon wire.
        switch (side) {
            case 0 -> pencilH(mat, vc, x0, x1, y0, cx, cz, 0, rgb, cx0, cy0, cx1, cy1);   // north
            case 1 -> pencilH(mat, vc, x0, x1, y1, cx, cz, 1, rgb, cx0, cy0, cx1, cy1);   // south
            case 2 -> pencilV(mat, vc, y0, y1, x0, cx, cz, 2, rgb, cx0, cy0, cx1, cy1);   // west
            default -> pencilV(mat, vc, y0, y1, x1, cx, cz, 3, rgb, cx0, cy0, cx1, cy1);  // east
        }
    }

    private static void pencilH(Matrix4f mat, VertexConsumer vc, float xs, float xe, float y,
                                int cx, int cz, int side, int rgb, float cx0, float cy0, float cx1, float cy1) {
        if (xe <= xs) return;
        rect(mat, vc, xs, y - 1, xe, y + 1, (0x30 << 24) | rgb, cx0, cy0, cx1, cy1);   // soft base
        int len = Math.round(xe - xs);
        for (int i = 0; i < len; i++) {
            int h = hash(cx, cz, side, i);
            if ((h & 15) == 0) continue;                       // ~1/16 grain gaps
            int jit = (h >> 3) % 3 - 1;                         // -1..1 perpendicular wobble
            int a = 160 + (h >> 5) % 96;                        // 160..255 opacity
            float px = xs + i, py = y + jit;
            int col = (a << 24) | rgb;
            rect(mat, vc, px, py - 1, px + 1, py + 1, col, cx0, cy0, cx1, cy1);
            if (((h >> 12) & 3) == 3) rect(mat, vc, px, py + 1, px + 1, py + 2, col, cx0, cy0, cx1, cy1);
        }
    }

    private static void pencilV(Matrix4f mat, VertexConsumer vc, float ys, float ye, float x,
                                int cx, int cz, int side, int rgb, float cx0, float cy0, float cx1, float cy1) {
        if (ye <= ys) return;
        rect(mat, vc, x - 1, ys, x + 1, ye, (0x30 << 24) | rgb, cx0, cy0, cx1, cy1);
        int len = Math.round(ye - ys);
        for (int i = 0; i < len; i++) {
            int h = hash(cx, cz, side, i);
            if ((h & 15) == 0) continue;
            int jit = (h >> 3) % 3 - 1;
            int a = 160 + (h >> 5) % 96;
            float px = x + jit, py = ys + i;
            int col = (a << 24) | rgb;
            rect(mat, vc, px - 1, py, px + 1, py + 1, col, cx0, cy0, cx1, cy1);
            if (((h >> 12) & 3) == 3) rect(mat, vc, px + 1, py, px + 2, py + 1, col, cx0, cy0, cx1, cy1);
        }
    }

    private static int hash(int a, int b, int c, int d) {
        int h = a * 374761393 + b * 668265263 + (c * 31 + d) * 0x85EBCA77;
        h = (h ^ (h >>> 15)) * 0x7FE7A56D;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    /** A small house glyph (town) at a colony centre, tinted the colony colour, all quads clipped. */
    private static void drawTown(Matrix4f mat, VertexConsumer vc, float x, float y, int fill,
                                 float cx0, float cy0, float cx1, float cy1) {
        int line = 0xFF1A1208;
        rect(mat, vc, x - 1, y - 4, x + 1, y - 3, line, cx0, cy0, cx1, cy1);   // roof
        rect(mat, vc, x - 2, y - 3, x + 2, y - 2, line, cx0, cy0, cx1, cy1);
        rect(mat, vc, x - 3, y - 2, x + 3, y - 1, line, cx0, cy0, cx1, cy1);
        rect(mat, vc, x - 3, y - 1, x + 3, y + 4, line, cx0, cy0, cx1, cy1);   // body outline
        rect(mat, vc, x - 2, y - 1, x + 2, y + 3, fill, cx0, cy0, cx1, cy1);   // body fill
        rect(mat, vc, x - 1, y + 1, x + 1, y + 4, line, cx0, cy0, cx1, cy1);   // door
    }

    /** Emit a colour quad clamped to the map box, so nothing draws past the atlas frame. */
    private static void rect(Matrix4f mat, VertexConsumer vc, float x0, float y0, float x1, float y1, int argb,
                             float cx0, float cy0, float cx1, float cy1) {
        float ax0 = Math.max(x0, cx0), ay0 = Math.max(y0, cy0);
        float ax1 = Math.min(x1, cx1), ay1 = Math.min(y1, cy1);
        if (ax1 <= ax0 || ay1 <= ay0) return;
        vc.addVertex(mat, ax0, ay0, Z).setColor(argb);
        vc.addVertex(mat, ax0, ay1, Z).setColor(argb);
        vc.addVertex(mat, ax1, ay1, Z).setColor(argb);
        vc.addVertex(mat, ax1, ay0, Z).setColor(argb);
    }
}
