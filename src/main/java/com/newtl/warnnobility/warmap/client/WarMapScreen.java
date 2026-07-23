package com.newtl.warnnobility.warmap.client;

import com.newtl.warnnobility.domain.client.ClientDomains;
import com.newtl.warnnobility.warmap.CommandTool;
import com.newtl.warnnobility.warmap.MapTool;
import com.newtl.warnnobility.warmap.Multiblock;
import com.newtl.warnnobility.warmap.WarFrameBlockEntity;
import com.newtl.warnnobility.warmap.WarMapData;
import com.newtl.warnnobility.warmap.WarStroke;
import com.newtl.warnnobility.warmap.net.WarMapNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The commander's planner: the "take the floor" screen. A switch at the top of the kit flips between two
 * modes over the SAME board:
 * <ul>
 *   <li><b>MAP</b> - the war-room drawing kit (grease pencil, arrows, zones, unit tokens, text) for marking
 *       up the map.</li>
 *   <li><b>COMMAND</b> - the troop kit (select, march, stance, mission, delegate, rally) for ordering units.</li>
 * </ul>
 * The overlay (border) toggles and view controls live in a persistent strip at the bottom of the kit, so
 * borders stay readable and toggleable in BOTH modes for logistics mid-battle. Field units render in both
 * modes too, so flipping back to MAP never hides the army.
 */
public class WarMapScreen extends Screen {

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new WarMapScreen(pos));
    }

    private enum Mode { MAP, COMMAND }

    private static final int[] PALETTE = {
            0xE03030, 0x3070E0, 0x30B030, 0xE0C030, 0xE0E0E0, 0x202020,
            0xE08020, 0x30C0C0, 0xC040C0, 0x8B5A2B };

    // mode switch geometry (top of the right-hand kit)
    private static final int SW_Y = 8, SW_H = 18;

    private final BlockPos pos;
    private double centerX, centerZ;
    private int zoom, rotation;

    private Mode mode = Mode.MAP;
    private MapTool tool = MapTool.PEN;
    private CommandTool cmdTool = CommandTool.SELECT;
    private int color;
    private EditBox labelField;

    // Universal brush weight (line thickness / text font scale), set by the slider left of the kit.
    private static final int SIZE_MIN = 1, SIZE_MAX = 6;
    private int brushSize = WarStroke.DEFAULT_SIZE;
    private boolean draggingSize;

    private int mapX, mapY, mapW, mapH;
    private final List<Btn> palette = new ArrayList<>();

    // in-progress drawing / panning
    private List<Double> draftPts;
    private boolean panning;
    private long lastViewSend, lastPointSend, lastPenPoint, lastErase;

    public WarMapScreen(BlockPos pos) {
        super(Component.literal("War Table"));
        this.pos = pos;
    }

    private WarFrameBlockEntity be() {
        return Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockEntity(pos) instanceof WarFrameBlockEntity be ? be : null;
    }

    private boolean hasFloor() {
        WarFrameBlockEntity be = be();
        return be != null && be.isActive() && be.data() != null
                && be.data().controlledBy(Minecraft.getInstance().player.getUUID());
    }

    @Override
    protected void init() {
        WarFrameBlockEntity be = be();
        if (be == null || !be.isActive()) { onClose(); return; }
        ClientWarMaps.markActive(pos);   // make sure this board gets baked so we can blit its crisp texture
        WarMapData d = be.data();
        centerX = d.centerX; centerZ = d.centerZ; zoom = d.zoom; rotation = d.rotation;
        color = PALETTE[Math.floorMod(minecraft.player.getUUID().hashCode(), PALETTE.length)];

        // A square preview, centred in the area left of the palette. Native scale keeps it crisp.
        int side = Math.min(Math.min(width - 130, height - 16), 768);
        mapW = side; mapH = side;
        mapX = Math.max(8, (width - 116 - side) / 2);
        mapY = (height - side) / 2;

        labelField = new EditBox(font, width - 108, 28, 100, 16, Component.literal("label"));
        labelField.setHint(Component.literal("token / text label"));
        labelField.setMaxLength(40);
        addRenderableWidget(labelField);

        buildPalette();
    }

    // --- palette (rebuilt on every mode / tool change) --------------------------------------------

    private void buildPalette() {
        palette.clear();
        int px = width - 112, w = 104;
        if (labelField != null) {
            labelField.visible = labelField.active = (mode == Mode.MAP);
            if (mode == Mode.MAP) labelField.setPosition(px + 4, 28);
        }
        if (mode == Mode.MAP) buildMapTools(px, w);
        else buildCommandTools(px, w);
        buildSharedStrip(px, w);
    }

    private void buildMapTools(int px, int w) {
        int h = 12, y = 48;
        MapTool[] tools = {
                MapTool.PEN, MapTool.LINE, MapTool.ARROW, MapTool.PHASE_LINE, MapTool.BOX, MapTool.CIRCLE,
                MapTool.OBJECTIVE, MapTool.UNIT_FRIENDLY, MapTool.UNIT_HOSTILE, MapTool.UNIT_UNKNOWN,
                MapTool.TEXT, MapTool.POINTER, MapTool.ERASE };
        String[] names = {
                "Grease Pencil", "Line", "Advance Arrow", "Phase Line", "Zone Box", "Zone Circle",
                "Objective", "Friendly Unit", "Hostile Unit", "Contact (?)", "Text Label", "Point Here",
                "Eraser" };
        for (int i = 0; i < tools.length; i++) {
            MapTool t = tools[i];
            palette.add(new Btn(px, y, w, h, names[i], () -> tool = t, () -> tool == t, -1));
            y += h + 1;
        }
        // colour swatches (two rows of 5)
        y += 4;
        for (int i = 0; i < PALETTE.length; i++) {
            int sx = px + (i % 5) * 21, sy = y + (i / 5) * 16;
            int c = PALETTE[i];
            palette.add(new Btn(sx, sy, 19, 14, "", () -> color = c, () -> color == c, c));
        }
    }

    private void buildCommandTools(int px, int w) {
        int h = 14, y = 30;
        for (CommandTool t : CommandTool.values()) {
            palette.add(new Btn(px, y, w, h, t.label, () -> onCommand(t), () -> cmdTool == t, -1));
            y += h + 1;
        }
    }

    /** The persistent bottom strip: border overlays + view + board actions, shown in BOTH modes. */
    private void buildSharedStrip(int px, int w) {
        int h = 14;
        int sy = height - 148;   // room for the extra "Show Units" row above zoom/rotate/clear
        int[] layers = { ClientDomains.L_COLONIES, ClientDomains.L_COUNTIES, ClientDomains.L_DUCHIES,
                ClientDomains.L_KINGDOMS, ClientDomains.L_FACTIONS };
        String[] lnames = { "Colonies", "Counties", "Duchies", "Kingdoms", "Factions" };
        for (int i = 0; i < layers.length; i++) {
            int layer = layers[i];
            palette.add(new Btn(px, sy, w, h, lnames[i],
                    () -> ClientDomains.toggle(layer), () -> ClientDomains.isEnabled(layer), -1));
            sy += h + 1;
        }
        // Show-units toggle: off = the board is "just a map" (armies/colonists hidden).
        palette.add(new Btn(px, sy, w, h, "Show Units",
                () -> WarMapNetwork.sendAction(WarMapNetwork.A_UNITS, base()),
                () -> { WarFrameBlockEntity b = be(); return b != null && b.data() != null && b.data().showUnits; }, -1));
        sy += h + 1;
        sy += 4;
        palette.add(new Btn(px, sy, 50, h, "Zoom +", () -> changeZoom(1), null, -1));
        palette.add(new Btn(px + 54, sy, 50, h, "Zoom -", () -> changeZoom(-1), null, -1)); sy += h + 1;
        palette.add(new Btn(px, sy, 50, h, "Rotate", this::rotate, null, -1));
        palette.add(new Btn(px + 54, sy, 50, h, "Undo", () -> WarMapNetwork.sendAction(WarMapNetwork.A_UNDO, base()), null, -1)); sy += h + 1;
        palette.add(new Btn(px, sy, 50, h, "Clear", () -> WarMapNetwork.sendAction(WarMapNetwork.A_CLEAR, base()), null, -1));
        palette.add(new Btn(px + 54, sy, 50, h, "Done", this::releaseAndClose, null, -1));
    }

    /** Command-kit actions. SELECT/MARCH/ATTACK are map-click tools; HOLD/STOP fire on the selection now. */
    private void onCommand(CommandTool t) {
        cmdTool = t;
        if (t.isImmediate()) {
            ClientUnits.FieldUnit u = ClientUnits.selectedUnit(pos);
            if (u != null) issue(u, t, u.x, u.z);
        }
    }

    /** Send one HYW order for a unit to the server, and echo it in the unit card for instant feedback. */
    private void issue(ClientUnits.FieldUnit u, CommandTool t, double wx, double wz) {
        if (!hasFloor() || u == null || !u.friendly) return;   // only your own troops, only while you hold the floor
        WarMapNetwork.sendCommand(pos, u.groupKey, t.order, wx, wz);
        u.missionNote = switch (t) {
            case MARCH  -> String.format("March to %.0f, %.0f", wx, wz);
            case ATTACK -> String.format("Attack toward %.0f, %.0f", wx, wz);
            case HOLD   -> "Holding position";
            case STOP   -> "Stopped";
            default      -> u.missionNote;
        };
    }

    // --- rendering --------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        WarFrameBlockEntity be = be();
        if (be == null || !be.isActive()) { onClose(); return; }
        renderBackground(g, mouseX, mouseY, partialTick);

        // map panel - same content renderer as the in-world board, so they always match
        g.fill(mapX - 1, mapY - 1, mapX + mapW + 1, mapY + mapH + 1, 0xFF2A2018);
        AtlasBoard ab = AtlasBoard.create(minecraft.player, minecraft.level.dimension(), mapW, mapH, centerX, centerZ, zoom);
        int tex = WarBoardTextures.texture(pos);   // blit the world-baked board
        g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        if (tex >= 0) {
            WarBoardTextures.blitInto(g, tex, mapX, mapY, mapW, mapH);          // the crisp baked board
        } else {
            WarMapDraw.renderContent(g, mapX, mapY, mapW, ab, be.data(), pos);  // until the first bake
        }
        // in MAP mode, show the stroke being drawn (the active drawing tracks the cursor 1:1 = live board)
        if (mode == Mode.MAP && draftPts != null && draftPts.size() >= 4 && ab != null) {
            double[] p = new double[draftPts.size()];
            for (int i = 0; i < p.length; i++) p[i] = draftPts.get(i);
            WarMapDraw.stroke(g, ab, mapX, mapY, tool, color, labelField.getValue(), p, brushSize);
        }
        g.disableScissor();

        // Unit banners are now baked INTO the board texture (WarMapDraw.renderContent), so both the GUI
        // and the physical board show the army, always in lock-step with the tiles and clipped by the
        // frame. Here we only overlay the live selection highlight, on the same be.data() board the bake
        // uses so the ring sits exactly on the baked banner. Clipped inside the wood frame.
        WarMapData vd = be.data();
        // "Show Units" also hides this live selection layer; otherwise the last-scanned banners would
        // linger on the GUI after the toggle (the server stops scanning but the client store is stale),
        // and the point of the toggle is a clean planning map with no soldiers on it.
        if (vd.showUnits) {
            AtlasBoard unitBoard = AtlasBoard.create(minecraft.player, minecraft.level.dimension(),
                    mapW, mapH, vd.centerX, vd.centerZ, vd.zoom);
            if (unitBoard == null) unitBoard = ab;
            int frame = tex >= 0 ? Math.max(1, Math.round(12f * mapW / (float) WarBoardTextures.FBO)) : 12;
            g.enableScissor(mapX + frame, mapY + frame, mapX + mapW - frame, mapY + mapH - frame);
            ClientUnits.renderInteractive(g, unitBoard, mapX, mapY, mode == Mode.COMMAND, pos);
            g.disableScissor();
        }

        // eraser: show the brush footprint so the player knows exactly what a click will rub out
        if (mode == Mode.MAP && tool == MapTool.ERASE && inMap(mouseX, mouseY)) {
            drawEraserCursor(g, mouseX, mouseY);
        }

        // the mode switch, the kit, the size slider, and the selected-unit card
        renderModeSwitch(g);
        for (Btn b : palette) b.render(g, mouseX, mouseY);
        renderSizeSlider(g);
        if (mode == Mode.COMMAND) renderUnitCard(g);

        // status line
        String status = hasFloor() ? "§aYou have the floor" : "§cYou no longer hold the marker";
        g.drawString(font, Component.literal(status), width - 112, height - 12, 0xFFFFFF, false);
        String hint = mode == Mode.MAP ? "Tool: " + tool : "Command: " + cmdTool.label;
        g.drawString(font, Component.literal(hint), mapX + 2, mapY + 2, 0xFFE8D8, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    /** A hollow ring + crosshair marking the eraser's reach, clamped to stay inside the map frame. */
    private void drawEraserCursor(GuiGraphics g, int cx, int cy) {
        int r = (int) Math.round(eraserScreenRadius());
        int seg = Math.max(16, r);
        int prevX = cx + r, prevY = cy;
        for (int i = 1; i <= seg; i++) {
            double a = (Math.PI * 2 * i) / seg;
            int x = cx + (int) Math.round(Math.cos(a) * r);
            int y = cy + (int) Math.round(Math.sin(a) * r);
            drawThinLine(g, prevX, prevY, x, y, 0xE0FF5555);
            prevX = x; prevY = y;
        }
        g.fill(cx - 4, cy, cx + 5, cy + 1, 0xC0FFFFFF);   // crosshair
        g.fill(cx, cy - 4, cx + 1, cy + 5, 0xC0FFFFFF);
    }

    /** 1px Bresenham-ish line via single-pixel fills; only used for the tiny eraser cursor. */
    private void drawThinLine(GuiGraphics g, int x0, int y0, int x1, int y1, int argb) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, argb);
            if (x0 == x1 && y0 == y1) break;
            int e2 = err << 1;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 <  dx) { err += dx; y0 += sy; }
        }
    }

    // --- universal size/thickness slider (left of the kit, shared by every tool) -------------------

    private int sizeSliderX()  { return width - 128; }
    private int sizeSliderY0() { return 34; }
    private int sizeSliderY1() { return height - 140; }
    private static final int SIZE_SLIDER_W = 10;

    /** Vertical slider that sets {@link #brushSize} for ALL tools: line thickness, and text font size. */
    private void renderSizeSlider(GuiGraphics g) {
        int sx = sizeSliderX(), y0 = sizeSliderY0(), y1 = sizeSliderY1(), sw = SIZE_SLIDER_W;
        if (y1 <= y0 + 8) return;
        g.fill(sx, y0, sx + sw, y1, 0xFF141414);
        g.renderOutline(sx, y0, sw, y1 - y0, 0xFF000000);
        g.drawString(font, "Sz", sx, y0 - 11, 0xFFBFBFBF, false);

        float f = (float) (brushSize - SIZE_MIN) / (SIZE_MAX - SIZE_MIN);
        int knobY = (int) (y1 - f * (y1 - y0));
        // live thickness preview swatch to the left of the knob, in the current pen colour
        int th = Math.max(1, brushSize), col = 0xFF000000 | color;
        g.fill(sx - 26, knobY - th / 2, sx - 6, knobY - th / 2 + th, col);
        // knob + value
        g.fill(sx - 1, knobY - 3, sx + sw + 1, knobY + 3, 0xFFFFE070);
        g.renderOutline(sx - 1, knobY - 3, sw + 2, 6, 0xFF000000);
        g.drawString(font, String.valueOf(brushSize), sx, y1 + 2, 0xFFFFE070, false);
    }

    private boolean overSizeSlider(double mx, double my) {
        int sx = sizeSliderX(), y0 = sizeSliderY0(), y1 = sizeSliderY1();
        return mx >= sx - 28 && mx <= sx + SIZE_SLIDER_W + 2 && my >= y0 - 2 && my <= y1 + 2;
    }

    private void setSizeFromY(double my) {
        int y0 = sizeSliderY0(), y1 = sizeSliderY1();
        double f = Math.max(0, Math.min(1, (y1 - my) / (double) (y1 - y0)));
        brushSize = SIZE_MIN + (int) Math.round(f * (SIZE_MAX - SIZE_MIN));
    }

    private void renderModeSwitch(GuiGraphics g) {
        int sx = width - 112, sw = 104, half = sw / 2;
        boolean mapOn = mode == Mode.MAP;
        g.fill(sx, SW_Y, sx + sw, SW_Y + SW_H, 0xFF141414);
        int knobX = sx + (mapOn ? 0 : half);
        g.fill(knobX, SW_Y, knobX + half, SW_Y + SW_H, mapOn ? 0xFF33557A : 0xFF7A3327);   // blue=map, red=command
        g.renderOutline(knobX, SW_Y, half, SW_H, 0xFFFFE070);
        g.renderOutline(sx, SW_Y, sw, SW_H, 0xFF000000);
        g.drawCenteredString(font, "MAP", sx + half / 2, SW_Y + 5, mapOn ? 0xFFFFFFFF : 0xFF7A7A7A);
        g.drawCenteredString(font, "CMD", sx + half + half / 2, SW_Y + 5, mapOn ? 0xFF7A7A7A : 0xFFFFFFFF);
    }

    private void renderUnitCard(GuiGraphics g) {
        ClientUnits.FieldUnit u = ClientUnits.selectedUnit(pos);
        if (u == null) {
            g.drawString(font, Component.literal("§7Select a unit on the map"), mapX + 6, mapY + mapH - 16, 0xFFBBBBBB, false);
            return;
        }
        int cw = 190, ch = 56, cx = mapX + 6, cy = mapY + mapH - ch - 6;
        g.fill(cx, cy, cx + cw, cy + ch, 0xC0140F0A);
        g.renderOutline(cx, cy, cw, ch, 0xFFFFE070);
        g.drawString(font, "§6" + u.name, cx + 6, cy + 5, 0xFFFFE070, false);
        g.drawString(font, "Strength: " + u.count + "   " + (u.friendly ? "§aAllied" : "§cEnemy"), cx + 6, cy + 18, 0xFFE8D8C0, false);
        if (u.friendly) {
            g.drawString(font, "Order: " + (u.missionNote == null ? "—" : u.missionNote), cx + 6, cy + 30, 0xFFBFD8BF, false);
            g.drawString(font, "§8March/Attack: pick tool, click ground", cx + 6, cy + 42, 0xFF9A8A7A, false);
        } else {
            g.drawString(font, "§7You cannot command enemy troops.", cx + 6, cy + 30, 0xFFBFBFBF, false);
        }
    }

    // --- input ------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // the mode switch, always first
        int sx = width - 112, half = 52;
        if (mx >= sx && mx < sx + 104 && my >= SW_Y && my < SW_Y + SW_H) {
            Mode nm = (mx < sx + half) ? Mode.MAP : Mode.COMMAND;
            if (nm != mode) { mode = nm; buildPalette(); }
            return true;
        }
        // the universal size slider (grab + drag)
        if (button == 0 && overSizeSlider(mx, my)) { setFocused(null); draggingSize = true; setSizeFromY(my); return true; }
        for (Btn b : palette) if (b.hit(mx, my)) { setFocused(null); b.action.run(); buildPalette(); return true; }
        // Clicking the label box must make it the screen's FOCUSED widget, else charTyped/keyPressed
        // (which route to getFocused()) never reach it and you can't type a token/text label.
        if (mode == Mode.MAP && labelField.mouseClicked(mx, my, button)) { setFocused(labelField); return true; }

        if (inMap(mx, my)) {
            setFocused(null);
            if (mode == Mode.MAP) return mapClick(mx, my, button);
            return commandClick(mx, my, button);
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean mapClick(double mx, double my, int button) {
        if (!hasFloor()) return true;
        if (button == 1) { panning = true; return true; }    // right-drag pans
        AtlasBoard ab = currentBoard();
        if (ab == null) return true;
        double wx = ab.wx(mx - mapX), wz = ab.wz(my - mapY);
        if (tool == MapTool.ERASE) { sendErase(wx, wz, ab); lastErase = net.minecraft.Util.getMillis(); return true; }
        if (tool == MapTool.POINTER) { sendPoint(wx, wz); return true; }
        // TEXT: hold to preview the label on the map (it follows the cursor), commit only on release.
        if (tool == MapTool.TEXT) {
            draftPts = new ArrayList<>();
            draftPts.add(wx); draftPts.add(wz); draftPts.add(wx); draftPts.add(wz);
            return true;
        }
        if (tool.isPlaced()) { sendStroke(new double[]{wx, wz}); return true; }
        draftPts = new ArrayList<>();
        draftPts.add(wx); draftPts.add(wz);
        if (!isFreehand(tool)) { draftPts.add(wx); draftPts.add(wz); }   // 2nd point tracks the cursor
        return true;
    }

    private boolean commandClick(double mx, double my, int button) {
        if (button == 1) { panning = true; return true; }    // pan works in command mode too
        AtlasBoard ab = currentBoard();
        ClientUnits.FieldUnit hit = ClientUnits.pick(ab, mapX, mapY, mx, my, pos);
        // Clicking a banner always (re)selects it, in any tool.
        if (hit != null) { ClientUnits.select(hit.id); return true; }
        // A targeted tool (March / Attack) sends the selected unit to the clicked world point.
        if (cmdTool.isTargeted()) {
            ClientUnits.FieldUnit sel = ClientUnits.selectedUnit(pos);
            if (sel != null && ab != null) issue(sel, cmdTool, ab.wx(mx - mapX), ab.wz(my - mapY));
            return true;
        }
        if (cmdTool == CommandTool.SELECT) ClientUnits.select(null);   // click empty ground clears selection
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingSize) { setSizeFromY(my); return true; }
        if (panning) {
            AtlasBoard ab = currentBoard();
            if (ab != null) { centerX -= dx / ab.ppb; centerZ -= dy / ab.ppb; sendViewThrottled(); }
            return true;
        }
        if (mode == Mode.MAP && tool == MapTool.ERASE && button == 0) {
            long now = net.minecraft.Util.getMillis();
            if (now - lastErase > 30 && inMap(mx, my) && hasFloor()) {
                AtlasBoard ab = currentBoard();
                if (ab != null) { sendErase(ab.wx(mx - mapX), ab.wz(my - mapY), ab); lastErase = now; }
            }
            return true;
        }
        if (mode == Mode.MAP && draftPts != null) {
            AtlasBoard ab = currentBoard();
            if (ab == null) return true;
            double wx = ab.wx(mx - mapX), wz = ab.wz(my - mapY);
            if (tool == MapTool.TEXT) {
                draftPts.set(0, wx); draftPts.set(1, wz);   // the label preview follows the cursor while held
            } else if (isFreehand(tool)) {
                long now = net.minecraft.Util.getMillis();
                if (now - lastPenPoint > 25) { draftPts.add(wx); draftPts.add(wz); lastPenPoint = now; }
            } else {
                draftPts.set(draftPts.size() - 2, wx);
                draftPts.set(draftPts.size() - 1, wz);
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingSize) { draggingSize = false; return true; }
        if (panning) { panning = false; sendView(); return true; }
        if (mode == Mode.MAP && draftPts != null) {
            double[] p = new double[draftPts.size()];
            for (int i = 0; i < p.length; i++) p[i] = draftPts.get(i);
            draftPts = null;
            // TEXT commits on release (that's the "paste"); skip it if the label box is empty.
            if (tool == MapTool.TEXT && labelField.getValue().isBlank()) return true;
            if (p.length >= 4) sendStroke(p);
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (mode == Mode.MAP && tool == MapTool.POINTER && inMap(mx, my) && hasFloor()) {
            long now = net.minecraft.Util.getMillis();
            if (now - lastPointSend > 60) {
                AtlasBoard ab = currentBoard();
                if (ab != null) { sendPoint(ab.wx(mx - mapX), ab.wz(my - mapY)); lastPointSend = now; }
            }
        }
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dxs, double dys) {
        if (inMap(mx, my)) { changeZoom(dys > 0 ? 1 : -1); return true; }
        return super.mouseScrolled(mx, my, dxs, dys);
    }

    // --- actions ----------------------------------------------------------------------------------

    private boolean isFreehand(MapTool t) { return t == MapTool.PEN || t == MapTool.PHASE_LINE; }
    private boolean inMap(double mx, double my) { return mx >= mapX && mx < mapX + mapW && my >= mapY && my < mapY + mapH; }
    private AtlasBoard currentBoard() {
        return AtlasBoard.create(minecraft.player, minecraft.level.dimension(), mapW, mapH, centerX, centerZ, zoom);
    }

    private CompoundTag base() { return WarMapNetwork.base(pos); }

    private void sendStroke(double[] worldPts) {
        if (!hasFloor()) return;
        CompoundTag t = base();
        t.putInt("Tool", tool.ordinal());
        t.putInt("Color", color);
        t.putInt("Size", brushSize);
        if (tool.isPlaced()) t.putString("Label", labelField.getValue());
        WarMapNetwork.putPts(t, worldPts);
        WarMapNetwork.sendAction(WarMapNetwork.A_DRAW, t);
    }

    private void sendPoint(double wx, double wz) {
        CompoundTag t = base();
        t.putDouble("X", wx); t.putDouble("Z", wz); t.putInt("Color", color);
        WarMapNetwork.sendAction(WarMapNetwork.A_POINT, t);
    }

    /**
     * Rub out every stroke passing under the eraser at ({@code wx},{@code wz}). The brush radius is set
     * in screen pixels (so it feels the same size at any zoom) then converted to world units for the
     * server-side hit test in {@link WarFrameBlockEntity}.
     */
    private void sendErase(double wx, double wz, AtlasBoard ab) {
        if (!hasFloor()) return;
        double screenR = eraserScreenRadius();
        double worldR = (ab != null && ab.ppb > 0) ? screenR / ab.ppb : 4.0;
        CompoundTag t = base();
        t.putDouble("X", wx); t.putDouble("Z", wz); t.putDouble("R", worldR);
        WarMapNetwork.sendAction(WarMapNetwork.A_ERASE, t);
    }

    /** Eraser radius in board pixels, scaled by the shared brush-size slider so it can be widened. */
    private double eraserScreenRadius() { return 4.0 + brushSize * 1.5; }

    private void changeZoom(int d) {
        zoom = Math.max(WarMapData.ZOOM_MIN, Math.min(WarMapData.ZOOM_MAX, zoom + d));
        sendView();
    }

    private void rotate() { rotation = (rotation + 1) & 3; sendView(); }

    private void sendView() {
        CompoundTag t = base();
        t.putDouble("CenterX", centerX); t.putDouble("CenterZ", centerZ);
        t.putInt("Zoom", zoom); t.putInt("Rotation", rotation);
        WarMapNetwork.sendAction(WarMapNetwork.A_VIEW, t);
        lastViewSend = net.minecraft.Util.getMillis();
    }

    private void sendViewThrottled() {
        if (net.minecraft.Util.getMillis() - lastViewSend > 80) sendView();
    }

    private void releaseAndClose() {
        WarMapNetwork.sendAction(WarMapNetwork.A_RELEASE, base());
        onClose();
    }

    @Override public boolean isPauseScreen() { return false; }

    /** Skip the vanilla menu blur so the planner preview stays crisp. */
    @Override protected void renderBlurredBackground(float partialTick) { }

    // --- a tiny manual button ---------------------------------------------------------------------

    private final class Btn {
        final int x, y, w, h;
        final String label;
        final Runnable action;
        final java.util.function.BooleanSupplier active;
        final int swatch;
        Btn(int x, int y, int w, int h, String label, Runnable action,
            java.util.function.BooleanSupplier active, int swatch) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.label = label;
            this.action = action; this.active = active; this.swatch = swatch;
        }
        boolean hit(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
        void render(GuiGraphics g, int mx, int my) {
            boolean on = active != null && active.getAsBoolean();
            boolean hov = hit(mx, my);
            int bg = swatch >= 0 ? (0xFF000000 | swatch) : (on ? 0xFF4A6B3A : hov ? 0xFF3A3A3A : 0xFF222222);
            g.fill(x, y, x + w, y + h, bg);
            int border = on ? 0xFFFFE070 : 0xFF000000;
            g.renderOutline(x, y, w, h, border);
            if (!label.isEmpty()) {
                int col = on ? 0xFFFFFF : 0xCFCFCF;
                int tw = font.width(label), maxW = w - 6;
                if (tw <= maxW) {
                    g.drawString(font, label, x + 3, y + 3, col, false);
                } else {
                    // shrink the label to fit the button so long labels never bleed off the panel/screen
                    float s = (float) maxW / tw;
                    g.pose().pushPose();
                    g.pose().translate(x + 3, y + 3 + (1 - s) * font.lineHeight / 2f, 0);
                    g.pose().scale(s, s, 1f);
                    g.drawString(font, label, 0, 0, col, false);
                    g.pose().popPose();
                }
            }
        }
    }
}
