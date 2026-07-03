package com.newtl.warnnobility.client;

import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.net.ChanceryActionMsg;
import com.newtl.warnnobility.net.Network;
import com.newtl.warnnobility.net.OpenChanceryMsg;
import com.newtl.warnnobility.net.OpenChanceryMsg.Target;
import com.newtl.warnnobility.nobility.Rank;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * The Chancery Table console. More than a control panel: a place a noble sits to read the realm's
 * codex. Four tabs (a dark panel, a gold title, a tab strip, a persistent ladder strip along the
 * bottom so it never reads as empty):
 * <ul>
 *   <li><b>Court</b> - your standing, how to advance, the Advance button, pledging a seal, and (Count
 *       and up) naming your domain.</li>
 *   <li><b>The Ladder</b> - every rank, its colour, its icon, and how it is earned, with your place
 *       on it marked.</li>
 *   <li><b>War Manual</b> - what seals, vassalage, war, the Crown and the throne actually mean.</li>
 *   <li><b>Settings</b> - the masculine/feminine styling of your title.</li>
 * </ul>
 */
public class ChanceryScreen extends Screen {

    private int panelW, panelH;   // scaled to the window (see init), so text has room to breathe
    private static final int PAD = 22;
    private static final int ROW = 20;
    private static final int MAX_TARGETS = 5;

    private static int tab = 0;   // 0 Court, 1 Ladder, 2 Manual, 3 Settings (static: survives a refresh)

    private final OpenChanceryMsg data;

    private int left, top;
    private int standingY = -1, hintY = -1, sealLabelY = -1, sealEmptyY = -1, domainLabelY = -1, settingsY = -1;
    private EditBox domainBox;

    // ---- the default ladder, for the Ladder tab + the bottom strip (client-side, default names) ----
    private record Rung(String name, String fem, Item icon, String advance) {}
    private static final Rung[] LADDER = {
            new Rung("Outsider", "Outsider", Items.LEATHER_BOOTS,  "Granted by the realm (just arrived)"),
            new Rung("Artisan",  "Artisan",  Items.IRON_PICKAXE,   "Granted: take up a trade or craft"),
            new Rung("Knight",   "Dame",     Items.IRON_SWORD,     "Granted: be knighted (a boss, a deed)"),
            new Rung("Baron",    "Baroness", Items.SHIELD,         "Granted: be made a Baron by the realm"),
            new Rung("Count",    "Countess", Items.GOLD_INGOT,     "2 seals from fellow Barons"),
            new Rung("Duke",     "Duchess",  Items.DIAMOND,        "2 seals from fellow Counts"),
            new Rung("King",     "Queen",    Items.GOLDEN_HELMET,  "2 Duke seals + lead a faction + a Crown"),
            new Rung("Emperor",  "Empress",  Items.NETHER_STAR,    "First King is crowned; after, usurp it"),
    };

    private record Section(Item icon, String header, String body) {}
    private static final Section[] MANUAL = {
            new Section(Items.PAPER, "Seals",
                    "A seal is a same-rank peer pledging their support to you. Gather the seals your "
                    + "next rank needs and you rise. You may back only one peer at a time."),
            new Section(Items.LEAD, "Vassalage",
                    "When you rise, every sealer becomes your sworn vassal, forming your county, duchy or "
                    + "kingdom. A new vassal is bound for a sentence and cannot leave early; losing vassals "
                    + "never costs you your title."),
            new Section(Items.IRON_SWORD, "Winning Seals by War",
                    "Win a War 'n Taxes vassalization war against a same-rank peer and that victory becomes "
                    + "a forced seal here automatically. Cross-rank wins do not count toward your rise."),
            new Section(Items.GOLDEN_HELMET, "The Crown",
                    "A Duke becomes King only with 2 Duke seals, leadership of a faction holding enough "
                    + "claimed chunks, and a Crown forged from a Shard of the Crown of Unification."),
            new Section(Items.NETHER_STAR, "The Throne",
                    "The first King to rise is crowned Emperor unopposed. After that the crown is taken only "
                    + "by usurpation: vassalize every imperial duchy, hold the throne, and win the war."),
    };

    public ChanceryScreen(OpenChanceryMsg data) {
        super(Component.literal("Chancery Table"));
        this.data = data;
    }

    private int contentTop() { return top + 64; }
    private int stripTop()   { return top + panelH - 46; }   // the bottom ladder strip

    @Override
    protected void init() {
        // Fill most of the window (like the Worship screen), capped so it stays tidy on huge monitors.
        panelW = Math.min(this.width - 60, 940);
        panelH = Math.min(this.height - 60, 540);
        left = (this.width - panelW) / 2;
        top = (this.height - panelH) / 2;
        int x = left + PAD;

        String[] tabs = {"Court", "The Ladder", "War Manual", "Settings"};
        int gap = 6;
        int tabW = (panelW - 2 * PAD - (tabs.length - 1) * gap) / tabs.length;
        for (int i = 0; i < tabs.length; i++) tabButton(x + i * (tabW + gap), tabW, tabs[i], i);

        if (tab == 0) initCourt(x);
        else if (tab == 3) initSettings(x);
        // Ladder + Manual tabs are read-only (text + icons), no widgets

        addRenderableWidget(Button.builder(Component.literal("Close").withStyle(ChatFormatting.BOLD), b -> onClose())
                .bounds(left + panelW - PAD - 80, top + panelH - 24, 80, 18).build());
    }

    private void tabButton(int x, int w, String name, int id) {
        addRenderableWidget(Button.builder(
                        Component.literal(tab == id ? "[" + name + "]" : name).withStyle(ChatFormatting.BOLD),
                        b -> { tab = id; rebuildWidgets(); })
                .bounds(x, top + 26, w, ROW).build());
    }

    // ===== COURT =====
    private void initCourt(int x) {
        int w = panelW - 2 * PAD;
        int y = contentTop() + 12;   // below the "Your Standing" header

        standingY = y;
        y += data.standing().size() * 10 + 6;

        hintY = y;
        int hintLines = this.font.split(Component.literal(data.advanceHint()), w - 4).size();
        y += hintLines * 10 + 4;

        if (data.canAdvance()) {
            addRenderableWidget(Button.builder(Component.literal(data.advanceLabel()),
                            b -> send(ChanceryActionMsg.ADVANCE, self(), ""))
                    .bounds(x, y, w, ROW).build());
            y += ROW + 6;
        }

        sealLabelY = y;
        y += 12;
        if (data.sealTargets().isEmpty()) {
            sealEmptyY = y;
            y += 12;
        } else {
            int n = 0;
            for (Target t : data.sealTargets()) {
                if (n++ >= MAX_TARGETS) break;
                addRenderableWidget(Button.builder(Component.literal("Pledge a seal to " + t.name()),
                                b -> send(ChanceryActionMsg.SEAL, t.id(), ""))
                        .bounds(x, y, w, 16).build());
                y += 18;
            }
        }

        if (data.holdsDomain()) {
            domainLabelY = y;
            y += 11;
            int boxW = w - 74;
            domainBox = new EditBox(this.font, x, y, boxW, 16, Component.literal("Domain name"));
            domainBox.setMaxLength(32);
            domainBox.setValue(data.domainName());
            addRenderableWidget(domainBox);
            addRenderableWidget(Button.builder(Component.literal("Name " + cap(data.domainKind())),
                            b -> send(ChanceryActionMsg.DOMAIN, self(), domainBox.getValue()))
                    .bounds(x + boxW + 4, y, 70, 16).build());
        }
    }

    private void initSettings(int x) {
        int w = panelW - 2 * PAD;
        settingsY = contentTop();
        addRenderableWidget(Button.builder(
                        Component.literal("Identify as: " + (data.female() ? "Woman" : "Man") + "   (tap to switch)"),
                        b -> send(ChanceryActionMsg.GENDER, self(), ""))
                .bounds(x, settingsY + 16, w, ROW).build());
    }

    // ===== render =====
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + panelW, top + panelH, 0xCC18120A);
        g.renderOutline(left, top, panelW, panelH, 0xFFB9924A);

        super.render(g, mouseX, mouseY, partialTick);   // tabs + buttons under the text

        int x = left + PAD;
        int w = panelW - 2 * PAD;
        g.drawCenteredString(this.font, Component.literal("Chancery Table")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), this.width / 2, top + 10, 0xFFFFFF);

        if (tab == 0) renderCourt(g, x, w);
        else if (tab == 1) renderLadder(g, x, w);
        else if (tab == 2) renderManual(g, x, w);
        else renderSettings(g, x, w);

        renderLadderStrip(g, x, w);   // persistent on every tab
    }

    private void renderCourt(GuiGraphics g, int x, int w) {
        icon(g, WarNNobility.CROWN.get(), x, contentTop() - 2);
        label(g, "Your Standing", x + 20, contentTop(), 0xFFD7C28A);
        int y = standingY;
        for (String line : data.standing()) {
            int col = line.startsWith("Title:") ? (0xFF000000 | curColor()) : 0xFFE8DCC0;
            g.drawString(this.font, line, x, y, col);
            y += 10;
        }
        int hy = hintY;
        for (FormattedCharSequence seq : this.font.split(Component.literal("Advance: " + data.advanceHint()), w - 4)) {
            g.drawString(this.font, seq, x, hy, 0xC9B98C);
            hy += 10;
        }
        if (sealLabelY >= 0) {
            icon(g, Items.PAPER, x, sealLabelY - 3);
            label(g, "Pledge a Seal", x + 20, sealLabelY, 0xFF7FD7FF);
        }
        if (sealEmptyY >= 0) {
            g.drawString(this.font, "No same-rank peers are gathering seals right now.", x, sealEmptyY, 0xFF9A8C70);
        }
        if (domainLabelY >= 0) {
            label(g, "Name your " + cap(data.domainKind()), x, domainLabelY, 0xFF8FE08F);
        }
    }

    private void renderLadder(GuiGraphics g, int x, int w) {
        label(g, "The Ladder of Rank", x, contentTop() - 4, 0xFFD7C28A);
        int cur = curIndex();
        int y = contentTop() + 9;
        int rowH = 26;
        for (int i = 0; i < LADDER.length; i++) {
            Rung r = LADDER[i];
            boolean here = i == cur;
            if (here) {
                g.fill(x - 3, y - 2, x + w + 1, y + rowH - 3, 0x33FFFFFF);
                g.drawString(this.font, ">", x - 9, y + 4, 0xFFFFE066, false);
            }
            icon(g, r.icon(), x, y - 1);
            int rc = 0xFF000000 | Rank.colorFor(r.name());
            String nm = r.name() + (here ? "  (you)" : "");
            g.drawString(this.font, Component.literal(nm).withStyle(ChatFormatting.BOLD), x + 20, y, rc, false);
            g.drawString(this.font, r.advance(), x + 92, y + (this.font.lineHeight - 8) / 2, 0xFFB7A98C, false);
            y += rowH;
        }
    }

    private void renderManual(GuiGraphics g, int x, int w) {
        label(g, "War Manual", x, contentTop() - 4, 0xFFD7C28A);
        int y = contentTop() + 9;
        for (Section s : MANUAL) {
            icon(g, s.icon(), x, y - 1);
            g.drawString(this.font, Component.literal(s.header()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    x + 20, y, 0xFFFFFF, false);
            int by = y + 11;
            for (FormattedCharSequence seq : this.font.split(Component.literal(s.body()), w - 22)) {
                g.drawString(this.font, seq, x + 20, by, 0xFFC9B98C, false);
                by += 9;
            }
            y = by + 5;
        }
    }

    private void renderSettings(GuiGraphics g, int x, int w) {
        label(g, "Identity", x, settingsY, 0xFFD7C28A);
        int sy = settingsY + 16 + ROW + 6;
        for (FormattedCharSequence seq : this.font.split(Component.literal(
                "How your title is styled. As a woman it shows its feminine form "
                + "(Dame, Baroness, Countess, Duchess, Queen, Empress)."), w)) {
            g.drawString(this.font, seq, x, sy, 0xC9B98C);
            sy += 10;
        }
        sy += 8;
        label(g, "Special Titles", x, sy, 0xFFD7C28A);
        sy += 12;
        for (FormattedCharSequence seq : this.font.split(Component.literal(
                "Bespoke honours beyond the ladder (Lord Protector, Hand of the King, Marshal) are still "
                + "being drafted in the Chancery. Coming soon."), w)) {
            g.drawString(this.font, seq, x, sy, 0xFFB7A98C);
            sy += 10;
        }
    }

    /** A persistent strip of all eight ranks along the bottom, the current one boxed. */
    private void renderLadderStrip(GuiGraphics g, int x, int w) {
        int y = stripTop();
        g.fill(left + 6, y - 4, left + panelW - 6, y + 20, 0x40000000);
        g.drawString(this.font, "Your path:", x, y - 2, 0xFF8A7A52, false);
        int cur = curIndex();
        int cellW = w / LADDER.length;
        int sy = y + 9;
        for (int i = 0; i < LADDER.length; i++) {
            int cx = x + i * cellW;
            int col = 0xFF000000 | Rank.colorFor(LADDER[i].name());
            g.fill(cx, sy, cx + 8, sy + 8, col);                       // colour pip
            boolean here = i == cur;
            if (here) g.renderOutline(cx - 2, sy - 2, 12, 12, 0xFFFFE066);
            String ab = LADDER[i].name().substring(0, Math.min(3, LADDER[i].name().length()));
            g.drawString(this.font, ab, cx + 11, sy, here ? 0xFFFFFFFF : 0xFF9A8C70, false);
        }
    }

    // ---- helpers ----
    private int curIndex() {
        String t = data.title();
        for (int i = 0; i < LADDER.length; i++) {
            if (LADDER[i].name().equalsIgnoreCase(t) || LADDER[i].fem().equalsIgnoreCase(t)) return i;
        }
        return -1;
    }

    private int curColor() {
        int i = curIndex();
        return i >= 0 ? Rank.colorFor(LADDER[i].name()) : 0xFFFFFF;
    }

    private void icon(GuiGraphics g, Item item, int x, int y) {
        g.renderItem(new ItemStack(item), x, y);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private UUID self() {
        return Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getUUID() : new UUID(0L, 0L);
    }

    private void send(byte action, UUID target, String text) {
        Network.toServer(new ChanceryActionMsg(action, target, text));
    }

    private void label(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(this.font, Component.literal(s).withStyle(ChatFormatting.BOLD), x, y, color);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
