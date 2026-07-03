package com.newtl.warnnobility.warmap.client.f4;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.warmap.client.AtlasBoard;
import com.newtl.warnnobility.warmap.client.WarMapDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.List;

/**
 * The Company-of-Heroes-style command HUD that overlays HYW's freecam / F4 RTS mode.
 *
 * <p>Renders only while {@link HywF4Bridge#active()}. It is a <b>control reference</b>, not a set of
 * executors: HYW's orders are mouse + camera-ray driven, so the grid TEACHES the player's real, live keys
 * (read from HYW via {@link HywF4Bridge#keyLabel}) rather than trying to spoof them — the old spoofing
 * misfired because HYW has no callable "executeMove". Layout: a status + maneuver strip (top-centre), a live
 * Atlas minimap (bottom-left), the selected-force card and the 3x4 control grid (bottom-right). Panels are
 * near-opaque so HYW's own overlay text does not bleed through, and generously spaced.
 */
@EventBusSubscriber(modid = WarNNobility.MODID, value = Dist.CLIENT)
public final class F4CommandHud {

    private F4CommandHud() {}

    // House skin — panels kept near-opaque (0xF2 alpha) so HYW's own HUD text can't bleed through ours.
    private static final int PANEL    = 0xF2140F0A;
    private static final int PANEL2   = 0xF2231A12;
    private static final int CELL_OFF = 0xF01A1A1A;
    private static final int GOLD     = 0xFFFFE070;
    private static final int PARCH    = 0xFFE8D8C0;
    private static final int MUTED    = 0xFF8A7A6A;
    private static final int BLACK    = 0xFF000000;
    private static final int HP_GREEN = 0xFF4F9E3A;
    private static final int HP_AMBER = 0xFFD9A527;
    private static final int HP_RED   = 0xFFB83A2E;
    private static final int WHITE     = 0xFFFFFFFF;
    private static final int PARCHMENT = 0xFFE6D3A6;

    // Minimap geometry (shared by render + click handling) and mutable zoom. Roomy bottom-left margin.
    private static final int MAP_SIZE = 150, MAP_X = 8, MAP_MARGIN = 10, ZBTN = 14;
    private static int minimapZoom = 0;          // AtlasBoard zoom (+ = in), clamped
    private static final int ZOOM_MIN = -4, ZOOM_MAX = 6;

    private static int mapY(int sh) { return sh - MAP_SIZE - MAP_MARGIN; }

    // Fixed 3x4 CONTROL REFERENCE (not executors). Each tile names a real HYW command and shows the
    // player's ACTUAL bound key/mouse for it (read live via HywF4Bridge.keyLabel), so the card teaches the
    // true controls and can never "do the wrong thing". Behaviours HYW lacks are built on our own server seam.
    private static final String[][] NAMES = {
            {"Move", "Attack", "Select", "Select All"},
            {"Hold", "Cancel", "Set Facing", "Patrol"},
            {"Shield Mode", "Combat Form", "Formation", "Cmd Wheel"},
    };
    // HotKeyManager function id per tile (see HywF4Bridge.GETTER); move/attack/select use mouse-combo hints.
    private static final String[][] FUNC = {
            {"move", "attack", "select", "selectall"},
            {"hold", "cancel", "facing", "patrol"},
            {"shield", "combatform", "formation", "wheel"},
    };
    // Icon asset stem per cell (assets/warnnobility/textures/gui/orders/<stem>.png, 32x32). Matches NAMES.
    // Reuses the 7 existing icons that actually fit the function (move=boots, raid=weapons->Attack,
    // hold=planted banner, recall=arrow-home->Cancel, pursue=running->Patrol, screen=shield-wall->Shield Mode,
    // stance=ranks->Combat Form). The other 5 (select, select_all, facing, formation, wheel) are NEW icons
    // still to be generated (see Desktop\WnN-F4-control-icons-gemini-prompts.txt); until their PNG exists the
    // tile just shows name+key (no magenta) thanks to iconExists().
    private static final String[][] ICON = {
            {"move", "raid", "select", "select_all"},
            {"hold", "recall", "facing", "pursue"},
            {"screen", "stance", "formation", "wheel"},
    };
    // Hover tooltip: what each control does. Matches NAMES.
    private static final String[][] DESC = {
            {"Send the selected force to where you aim.",
             "Attack-move to where you aim: engage anything on the way.",
             "Left-click a unit to pick it; hold and drag to box-select several.",
             "Select every unit you command on the field."},
            {"Stop and stand your ground, keeping formation.",
             "Cancel the current orders and stand down.",
             "Set which way the formation faces.",
             "Patrol between the points you mark."},
            {"Raise shields and tighten up — heavy protection from arrows (testudo).",
             "Toggle the combat formation shape.",
             "Cycle the marching formation (line / wedge / column).",
             "Open HYW's full radial command wheel for everything else."},
    };

    /**
     * The control hint at the bottom of a tile: the mouse combo for move/attack/select, else the player's
     * live HYW key for that function (falling back to a sensible default so a tile never reads blank).
     */
    private static String controlHint(int r, int c) {
        String f = FUNC[r][c];
        return switch (f) {
            case "move"   -> "Right Click";
            case "select" -> "Left Click";
            case "attack" -> {
                String k = HywF4Bridge.keyLabel("attack");
                yield (k.isEmpty() ? "A" : k) + " + Left Click";
            }
            default -> {
                String k = HywF4Bridge.keyLabel(f);
                yield k.isEmpty() ? "—" : k;
            }
        };
    }

    // Resolved lazily so a missing texture only costs a text fallback, never a crash.
    private static final ResourceLocation[][] ICON_RL = new ResourceLocation[3][4];
    private static ResourceLocation iconRl(int r, int c) {
        ResourceLocation rl = ICON_RL[r][c];
        if (rl == null) {
            rl = ResourceLocation.fromNamespaceAndPath(WarNNobility.MODID, "textures/gui/orders/" + ICON[r][c] + ".png");
            ICON_RL[r][c] = rl;
        }
        return rl;
    }

    // Whether a tile's icon PNG is actually present (cached once). A not-yet-drawn icon simply shows as
    // name+key instead of the magenta missing-texture square, so referencing a planned-but-absent icon is safe.
    private static final Boolean[][] ICON_OK = new Boolean[3][4];
    private static boolean iconExists(int r, int c) {
        Boolean ok = ICON_OK[r][c];
        if (ok == null) {
            ok = Minecraft.getInstance().getResourceManager().getResource(iconRl(r, c)).isPresent();
            ICON_OK[r][c] = ok;
        }
        return ok;
    }

    // Grid geometry (shared by render + hover hit-testing). Bigger cells + gaps so it reads uncramped.
    private static final int G_CELL = 44, G_GAP = 4, G_COLS = 4, G_ROWS = 3;
    private static final int RIGHT_MARGIN = 8, BOTTOM_MARGIN = 10, CARD_GAP = 18, CARD_H = 54;

    // Selection preservation: HYW turns every freecam left-click into a select/deselect, so clicking our
    // HUD would clear the units. We keep last tick's selection and re-apply it for a couple ticks after a
    // HUD click, making the deselect invisible so the zoom buttons stay usable without losing the selection.
    private static java.util.List<Entity> lastSel = java.util.List.of();
    private static java.util.List<Entity> restoreSel = null;
    private static int restoreTicks = 0;

    private static int gridW() { return G_COLS * G_CELL + (G_COLS - 1) * G_GAP; }
    private static int gridH() { return G_ROWS * G_CELL + (G_ROWS - 1) * G_GAP; }
    private static int gridX0(int sw) { return sw - gridW() - RIGHT_MARGIN; }
    private static int gridY0(int sh) { return sh - gridH() - BOTTOM_MARGIN; }
    private static int cardY(int sh) { return gridY0(sh) - CARD_GAP - CARD_H; }

    // LOWEST priority = we render AFTER HYW's own GuiEventHandler, so our overlay draws OVER HYW's redundant
    // freecam text (which our grid + minimap replace).
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderGui(RenderGuiEvent.Post e) {
        if (!HywF4Bridge.active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;

        GuiGraphics g = e.getGuiGraphics();
        Font font = mc.font;
        int sw = g.guiWidth(), sh = g.guiHeight();

        // HYW's own freecam action list + control legend are suppressed by our client mixins
        // (HywActionListMixin / HywMouseControlInfoMixin) while this HUD is active, so nothing of HYW's draws
        // over our panels — our HUD is the single command surface.

        // Poll the mouse for clicks on our zoom buttons (the InputEvent path is eaten by HYW's mixin).
        handleMouseInput(mc, sw, sh);

        // Gather the selected combat units.
        List<Entity> sel = HywF4Bridge.selected();
        int count = 0;
        float hp = 0f, maxHp = 0f;
        int bestLevel = -1;
        String repName = null;
        boolean mixed = false;
        int teamColor = GOLD;
        for (Entity ent : sel) {
            if (!HywF4Bridge.isCombatUnit(ent) || !(ent instanceof LivingEntity le)) continue;
            count++;
            hp += Math.max(0f, le.getHealth());
            maxHp += Math.max(1f, le.getMaxHealth());
            int lv = HywF4Bridge.level(ent);
            if (lv > bestLevel) bestLevel = lv;
            String name = ent.getType().getDescription().getString();
            if (repName == null) repName = name;
            else if (!repName.equals(name)) mixed = true;
            PlayerTeam tm = (PlayerTeam) le.getTeam();
            if (tm != null && tm.getColor().getColor() != null) teamColor = 0xFF000000 | tm.getColor().getColor();
        }

        drawTopStrip(g, font, sw, count);
        drawManeuverHints(g, font, sw, sh);
        drawAtlasMinimap(g, mc, font, sh, sel);
        drawZoomButtons(g, sh);
        drawUnitCard(g, font, sw, sh, count, repName, mixed, hp, maxHp, bestLevel, teamColor);
        drawCommandGrid(g, font, sw, sh, count > 0);

        // Hover tooltip: point at any control and read what it does + how to trigger it.
        var win = mc.getWindow();
        int mx = (int) (mc.mouseHandler.xpos() * sw / (double) Math.max(1, win.getScreenWidth()));
        int my = (int) (mc.mouseHandler.ypos() * sh / (double) Math.max(1, win.getScreenHeight()));
        drawGridTooltip(g, font, sw, sh, mx, my);
    }

    /** Top-centre: just the war-status strip. (Maneuver hints live at bottom-centre, see drawManeuverHints.) */
    private static void drawTopStrip(GuiGraphics g, Font font, int sw, int count) {
        String label = count > 0 ? "WAR COMMAND · " + count + " selected" : "WAR COMMAND";
        int w = font.width(label) + 16;
        int x = (sw - w) / 2, y = 4, h = 13;
        g.fill(x, y, x + w, y + h, PANEL);
        g.renderOutline(x, y, w, h, GOLD);
        g.drawCenteredString(font, label, sw / 2, y + 3, GOLD);
    }

    /**
     * Bottom-centre maneuver reference (how to fly / select / move), sitting just ABOVE the vanilla
     * hearts/hotbar HUD. Two tidy lines on a faint backing so they read over bright terrain.
     */
    private static void drawManeuverHints(GuiGraphics g, Font font, int sw, int sh) {
        String atk = HywF4Bridge.keyLabel("attack");
        if (atk.isEmpty()) atk = "A";
        String l1 = "§7Fly: hold §eRight-Click§7 + move keys · §eMouse Wheel§7 sets speed";
        String l2 = "§7Select: §eLeft-Click§7 or drag · Move: §eRight-Click§7 · Attack: §e" + atk + " + Left-Click";
        int lh = font.lineHeight + 2;
        // Sit the block just ABOVE the vanilla health/armour row (which tops out ~50px from the bottom),
        // with a clean gap so it doesn't crowd the hearts.
        int y2 = sh - 62;              // lower line top
        int y1 = y2 - lh;              // upper line top
        int wMax = Math.max(font.width(l1), font.width(l2));
        int bx = sw / 2 - wMax / 2 - 5, bw = wMax + 10;
        g.fill(bx, y1 - 3, bx + bw, y2 + font.lineHeight + 2, 0x80000000);   // faint readability backing
        g.drawCenteredString(font, l1, sw / 2, y1, 0xFFB0A890);
        g.drawCenteredString(font, l2, sw / 2, y2, 0xFFB0A890);
    }

    /**
     * Bottom-left: live Antique Atlas minimap (CoH minimap slot), centred on the camera, with the selected
     * units plotted as gold dots and the camera as a white pip. Reuses {@link AtlasBoard}. Fully guarded.
     */
    private static void drawAtlasMinimap(GuiGraphics g, Minecraft mc, Font font, int sh, List<Entity> sel) {
        int size = MAP_SIZE, x = MAP_X, y = mapY(sh);
        // Opaque backing so HYW's redundant bottom-left action list is covered, not read through the map.
        g.fill(x - 2, y - 2, x + size + 2, y + size + 2, PANEL);
        g.renderOutline(x - 2, y - 2, size + 4, size + 4, GOLD);

        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        if (cam == null || mc.level == null || mc.player == null || !AtlasBoard.available()) {
            g.drawCenteredString(font, "§8Atlas…", x + size / 2, y + size / 2 - 4, MUTED);
            return;
        }
        try {
            // Over-render the board by `pad` on every side so AA's top/left tile-edge gap falls OUTSIDE
            // the scissor -> the visible map is full-bleed. The board centre still lands at the map centre.
            int pad = 32, bs = size + pad * 2, ox = x - pad, oy = y - pad;
            AtlasBoard ab = AtlasBoard.create(mc.player, mc.level.dimension(), bs, bs,
                    cam.getX(), cam.getZ(), minimapZoom);
            if (ab == null) { g.drawCenteredString(font, "§8Atlas…", x + size / 2, y + size / 2 - 4, MUTED); return; }
            g.enableScissor(x, y, x + size, y + size);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            g.fill(x, y, x + size, y + size, PARCHMENT);
            g.flush();
            g.pose().pushPose();
            g.pose().translate(ox, oy, 0);
            Lighting.setupForFlatItems();
            ab.renderTiles(g.pose(), g.bufferSource(), LightTexture.FULL_BRIGHT);
            g.flush();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            g.pose().popPose();

            WarMapDraw.renderOverlays(g, ab, ox, oy);

            var selSet = new java.util.HashSet<>(sel);
            for (Entity ee : mc.level.entitiesForRendering()) {
                if (!HywF4Bridge.isCombatUnit(ee) || selSet.contains(ee)) continue;
                int px = ox + (int) ab.sx(ee.getX()), py = oy + (int) ab.sy(ee.getZ());
                int col = unitDotColor(ee);
                g.fill(px - 1, py - 1, px + 1, py + 1, BLACK);
                g.fill(px - 1, py - 1, px, py, col);
            }
            for (Entity ee : sel) {
                if (!HywF4Bridge.isCombatUnit(ee)) continue;
                int px = ox + (int) ab.sx(ee.getX()), py = oy + (int) ab.sy(ee.getZ());
                g.fill(px - 1, py - 1, px + 2, py + 2, BLACK);
                g.fill(px - 1, py - 1, px + 1, py + 1, GOLD);
            }
            int cxp = ox + (int) ab.sx(cam.getX()), cyp = oy + (int) ab.sy(cam.getZ());
            g.fill(cxp - 1, cyp - 1, cxp + 2, cyp + 2, WHITE);
            g.disableScissor();
        } catch (Throwable t) {
            try { g.disableScissor(); } catch (Throwable ignored) {}
            g.drawCenteredString(font, "§8Atlas…", x + size / 2, y + size / 2 - 4, MUTED);
        }
    }

    private static int minusBtnX() { return MAP_X + MAP_SIZE - ZBTN * 2 - 4; }
    private static int plusBtnX()  { return MAP_X + MAP_SIZE - ZBTN - 3; }
    private static int zBtnY(int sh) { return mapY(sh) + 3; }

    /** Tiny +/- zoom buttons in the minimap's top-right corner (glyphs drawn as bars = crisp/clear). */
    private static void drawZoomButtons(GuiGraphics g, int sh) {
        int by = zBtnY(sh);
        drawZBtn(g, minusBtnX(), by, false);
        drawZBtn(g, plusBtnX(), by, true);
    }

    private static void drawZBtn(GuiGraphics g, int x, int y, boolean plus) {
        g.fill(x, y, x + ZBTN, y + ZBTN, PANEL);
        g.renderOutline(x, y, ZBTN, ZBTN, GOLD);
        int cx = x + ZBTN / 2, cy = y + ZBTN / 2;
        g.fill(x + 3, cy, x + ZBTN - 3, cy + 2, GOLD);            // horizontal bar (both)
        if (plus) g.fill(cx, y + 3, cx + 2, y + ZBTN - 3, GOLD);  // vertical bar (plus only)
    }

    /** Re-apply the last selection for a few ticks after a HUD click, so HYW's click-deselect never sticks. */
    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e) {
        if (HywF4Bridge.active()) {
            java.util.List<Entity> s = HywF4Bridge.selected();
            if (!s.isEmpty()) lastSel = new java.util.ArrayList<>(s);
        }
        if (restoreTicks > 0) {
            restoreTicks--;
            if (restoreSel != null) HywF4Bridge.restoreSelected(restoreSel);
            if (restoreTicks == 0) restoreSel = null;
        }
    }

    /**
     * Poll the raw mouse for clicks on our zoom buttons (NeoForge's InputEvent path is eaten by HYW's mixin
     * in freecam). Any click over our HUD also restores the selection so it can't deselect the units.
     */
    private static boolean prevLeftDown = false;
    private static void handleMouseInput(Minecraft mc, int sw, int sh) {
        long window = mc.getWindow().getWindow();
        boolean down = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT)
                == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        boolean edge = down && !prevLeftDown;
        prevLeftDown = down;
        if (!edge) return;

        var w = mc.getWindow();
        double mxd = mc.mouseHandler.xpos() * sw / (double) Math.max(1, w.getScreenWidth());
        double myd = mc.mouseHandler.ypos() * sh / (double) Math.max(1, w.getScreenHeight());
        boolean overHud = false;

        int by = zBtnY(sh);
        if (myd >= by && myd <= by + ZBTN) {
            if (mxd >= minusBtnX() && mxd <= minusBtnX() + ZBTN) { minimapZoom = Math.max(ZOOM_MIN, minimapZoom - 1); overHud = true; }
            else if (mxd >= plusBtnX() && mxd <= plusBtnX() + ZBTN) { minimapZoom = Math.min(ZOOM_MAX, minimapZoom + 1); overHud = true; }
        }
        if (!overHud && isOverHud(mxd, myd, sw, sh)) overHud = true;
        if (overHud) { restoreSel = lastSel; restoreTicks = 3; }
    }

    /** Whether the cursor is over any of our HUD panels (so a click there must not reach HYW's selection). */
    private static boolean isOverHud(double mx, double my, int sw, int sh) {
        int gx0 = gridX0(sw), gy0 = gridY0(sh);
        if (mx >= gx0 - 3 && mx <= gx0 + gridW() + 3 && my >= gy0 - 13 && my <= gy0 + gridH() + 3) return true; // grid + header
        int cy = cardY(sh);
        if (mx >= gx0 - 3 && mx <= gx0 + gridW() + 3 && my >= cy - 3 && my <= cy + CARD_H + 3) return true;      // unit card
        int size = MAP_SIZE, mp = MAP_X, myp = mapY(sh);                                                          // minimap
        return mx >= mp - 2 && mx <= mp + size + 2 && my >= myp - 2 && my <= myp + size + 2;
    }

    /** Zoom the minimap with Alt + '='/'-' (scroll is eaten by HYW in freecam). */
    @SubscribeEvent
    public static void onKey(net.neoforged.neoforge.client.event.InputEvent.Key e) {
        if (e.getAction() != org.lwjgl.glfw.GLFW.GLFW_PRESS) return;
        if ((e.getModifiers() & org.lwjgl.glfw.GLFW.GLFW_MOD_ALT) == 0) return;
        if (!HywF4Bridge.active()) return;
        int key = e.getKey();
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD) {
            minimapZoom = Math.min(ZOOM_MAX, minimapZoom + 1);
        } else if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT) {
            minimapZoom = Math.max(ZOOM_MIN, minimapZoom - 1);
        }
    }

    /** Bottom-right, above the grid: the selected-force card (name, strength, health bar, level). */
    private static void drawUnitCard(GuiGraphics g, Font font, int sw, int sh,
                                     int count, String repName, boolean mixed,
                                     float hp, float maxHp, int level, int teamColor) {
        int cw = gridW();
        int x = gridX0(sw);
        int y = cardY(sh);
        g.fill(x, y, x + cw, y + CARD_H, PANEL);
        g.renderOutline(x, y, cw, CARD_H, GOLD);

        if (count == 0) {
            g.drawCenteredString(font, "§7No units selected", x + cw / 2, y + CARD_H / 2 - 4, MUTED);
            return;
        }

        g.fill(x + 6, y + 6, x + 16, y + 16, teamColor);            // team-colour banner chip
        g.renderOutline(x + 6, y + 6, 10, 10, BLACK);

        String title = mixed ? "Mixed force" : (repName == null ? "Force" : repName);
        g.drawString(font, "§6" + title, x + 21, y + 6, GOLD, false);
        g.drawString(font, "Strength: " + count, x + 8, y + 21, PARCH, false);
        if (level >= 0) g.drawString(font, "Vet " + level, x + cw - 8 - font.width("Vet " + level), y + 21, PARCH, false);

        int bx = x + 8, by = y + 34, bw = cw - 16, bh = 7;
        float ratio = maxHp > 0 ? hp / maxHp : 0f;
        int fillC = ratio > 0.5f ? HP_GREEN : ratio > 0.25f ? HP_AMBER : HP_RED;
        g.fill(bx, by, bx + bw, by + bh, 0xFF201812);
        g.fill(bx, by, bx + (int) (bw * Math.max(0f, Math.min(1f, ratio))), by + bh, fillC);
        g.renderOutline(bx, by, bw, bh, BLACK);
        String hpTxt = (int) hp + " / " + (int) maxHp;
        g.drawString(font, hpTxt, x + cw - 8 - font.width(hpTxt), y + 43, MUTED, false);
    }

    /** Bottom-right: fixed 3x4 control reference, each tile showing its real HYW key. */
    private static void drawCommandGrid(GuiGraphics g, Font font, int sw, int sh, boolean enabled) {
        int x0 = gridX0(sw), y0 = gridY0(sh);
        g.fill(x0 - 3, y0 - 3, x0 + gridW() + 3, y0 + gridH() + 3, PANEL);
        g.renderOutline(x0 - 3, y0 - 3, gridW() + 6, gridH() + 6, GOLD);
        g.drawString(font, "§6Controls §8· your HYW keys", x0 - 3, y0 - 12, GOLD, false);

        for (int r = 0; r < G_ROWS; r++) {
            for (int c = 0; c < G_COLS; c++) {
                int x = x0 + c * (G_CELL + G_GAP), y = y0 + r * (G_CELL + G_GAP);
                g.fill(x, y, x + G_CELL, y + G_CELL, enabled ? PANEL2 : CELL_OFF);
                g.renderOutline(x, y, G_CELL, G_CELL, enabled ? GOLD : 0xFF444444);
                if (iconExists(r, c)) {
                    int ic = 18;
                    int ix = x + (G_CELL - ic) / 2, iy = y + 3;
                    RenderSystem.enableBlend();
                    RenderSystem.setShaderColor(1f, 1f, 1f, enabled ? 1f : 0.35f);
                    g.pose().pushPose();
                    g.pose().translate(ix, iy, 0);
                    g.pose().scale(ic / 32f, ic / 32f, 1f);
                    g.blit(iconRl(r, c), 0, 0, 0f, 0f, 32, 32, 32, 32);
                    g.pose().popPose();
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                }
                drawFitted(g, font, NAMES[r][c], x + G_CELL / 2, y + 28, G_CELL - 4, enabled ? PARCH : MUTED);
                drawFitted(g, font, controlHint(r, c), x + G_CELL / 2, y + 38, G_CELL - 3, enabled ? GOLD : 0xFF666666);
            }
        }
    }

    /** If the cursor is over a control cell, draw a tooltip (name + real control + plain-words help). */
    private static void drawGridTooltip(GuiGraphics g, Font font, int sw, int sh, int mx, int my) {
        int gx0 = gridX0(sw), gy0 = gridY0(sh);
        if (mx < gx0 || mx >= gx0 + gridW() || my < gy0 || my >= gy0 + gridH()) return;
        int c = (mx - gx0) / (G_CELL + G_GAP);
        int r = (my - gy0) / (G_CELL + G_GAP);
        if (r < 0 || r >= G_ROWS || c < 0 || c >= G_COLS) return;
        int cl = gx0 + c * (G_CELL + G_GAP), ct = gy0 + r * (G_CELL + G_GAP);
        if (mx > cl + G_CELL || my > ct + G_CELL) return;   // in the gap, not the cell

        String title = NAMES[r][c] + "  [" + controlHint(r, c) + "]";
        List<String> lines = wrap(font, DESC[r][c], 160);
        int tw = font.width(title);
        for (String ln : lines) tw = Math.max(tw, font.width(ln));
        int pad = 4, lh = font.lineHeight + 1;
        int bw = tw + pad * 2, bh = pad * 2 + lh * (lines.size() + 1);
        int bx = mx + 12, by = my - bh - 6;
        if (bx + bw > sw - 2) bx = mx - bw - 12;            // flip left if it would run off-screen
        if (bx < 2) bx = 2;
        if (by < 2) by = my + 14;

        g.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, 0xF00A0705);
        g.renderOutline(bx - 1, by - 1, bw + 2, bh + 2, GOLD);
        int ty = by + pad;
        g.drawString(font, "§6" + title, bx + pad, ty, GOLD, false);
        ty += lh;
        for (String ln : lines) { g.drawString(font, ln, bx + pad, ty, PARCH, false); ty += lh; }
    }

    /** A combat unit's minimap dot colour: its scoreboard-team colour if it has one, else neutral grey. */
    private static int unitDotColor(Entity e) {
        if (e instanceof LivingEntity le && le.getTeam() instanceof PlayerTeam tm && tm.getColor().getColor() != null) {
            return 0xFF000000 | tm.getColor().getColor();
        }
        return 0xFFBFBFBF;
    }

    /** Greedy word-wrap to a pixel width, so tooltip help never bleeds off the panel. */
    private static List<String> wrap(Font font, String text, int maxW) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (font.width(test) > maxW && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** Draw text centred at (cx,cy), shrinking it uniformly if it is wider than maxW so it never bleeds. */
    private static void drawFitted(GuiGraphics g, Font font, String text, int cx, int cy, int maxW, int color) {
        int w = font.width(text);
        float s = w > maxW ? (float) maxW / w : 1f;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(s, s, 1f);
        g.drawString(font, text, -w / 2, -font.lineHeight / 2, color, false);
        g.pose().popPose();
    }
}
