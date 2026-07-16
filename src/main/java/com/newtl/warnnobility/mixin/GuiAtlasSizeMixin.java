package com.newtl.warnnobility.mixin;

import com.newtl.warnnobility.atlas.client.AtlasSize;
import hunternif.mc.impl.atlas.client.texture.ITexture;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes Antique Atlas 8's book fill the window, the way Antique Atlas 4's did.
 *
 * <p>AA4 sized its book at runtime ({@code guiScaledWidth * 0.9 - 40}) and <b>stretched its background art
 * to that size</b>, so everything else followed. AA8 instead keeps {@code WIDTH=310}, {@code HEIGHT=218},
 * {@code MAP_WIDTH=276}, {@code MAP_HEIGHT=194} as {@code static final} ints with ConstantValue attributes,
 * which javac inlines into every use. There is no field to set and no config to flip.
 *
 * <p>So this does BOTH halves, and both are needed:
 * <ol>
 *   <li>{@link ModifyConstant} rewrites the inlined numbers, so {@code setSize}/{@code setCentered} place the
 *       book correctly, the map window and its scissor grow, and {@code mouseClicked} hit-tests the map you
 *       can actually see rather than the old small rectangle.</li>
 *   <li>{@link Redirect} swaps the book art's {@code ITexture.draw(g, x, y)} (which paints at the texture's
 *       own intrinsic size) for the sized {@code draw(g, x, y, w, h)} overload.</li>
 * </ol>
 *
 * <p>A first attempt did only (1), which is precisely why it failed: the constructor happily accepted the
 * new size, so {@code setCentered()} re-centred a component that believed it was 818px wide and threw the
 * book into the top-left, while the art stayed 310px because nothing in {@code render} ever reads those
 * constants. Everything that measured the book grew; the thing that drew it did not.
 *
 * <p>The 17px/11px border literals are deliberately left alone, so the frame keeps a sane thickness while
 * the page grows, matching AA4. {@code render} draws four textures through the 3-arg {@code draw}: the book,
 * its two frames, and a marker preview, so the redirect is pinned to the first three by ordinal and the
 * marker is left at its natural size.
 *
 * <p>Antique Atlas is compile-only here purely so {@code ITexture} can be named in the redirect signature;
 * the reference lives only in this class, the mixin config is {@code required: false}, and everything else
 * reaches the atlas by reflection. If the atlas is absent or reshaped, this never applies and the book is
 * simply whatever the atlas draws.
 */
@Mixin(targets = "hunternif.mc.impl.atlas.client.gui.GuiAtlas", remap = false)
public class GuiAtlasSizeMixin {

    private static final String I_TEXTURE_DRAW =
            "Lhunternif/mc/impl/atlas/client/texture/ITexture;draw(Lnet/minecraft/client/gui/GuiGraphics;II)V";

    // --- the book's own size ------------------------------------------------------------------------

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 310), require = 0)
    private int wnn$bookWidthInit(int original) { return AtlasSize.bookWidth(); }

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 218), require = 0)
    private int wnn$bookHeightInit(int original) { return AtlasSize.bookHeight(); }

    @ModifyConstant(method = "renderScaleOverlay", constant = @Constant(intValue = 310), require = 0)
    private int wnn$bookWidthScaleBar(int original) { return AtlasSize.bookWidth(); }

    // --- the map window inside it (drawing, scissor AND hit-testing all read these) -----------------

    @ModifyConstant(
            method = {"mouseClicked", "renderMarker", "renderPlayer"},
            constant = @Constant(intValue = 276), require = 0)
    private int wnn$mapWidth(int original) { return AtlasSize.mapWidth(); }

    @ModifyConstant(
            method = {"<init>", "render", "mouseClicked", "renderMarker", "renderPlayer"},
            constant = @Constant(intValue = 194), require = 0)
    private int wnn$mapHeight(int original) { return AtlasSize.mapHeight(); }

    // --- the buttons ---------------------------------------------------------------------------------
    // The constructor pins its children with offsets folded from WIDTH/HEIGHT at compile time, so they mean
    // nothing to look at: 300 is "WIDTH - 10", 283 is "WIDTH - 27", 148 is "WIDTH/2 - 7", 198 is
    // "HEIGHT - 20". Left alone they stay where the 310x218 book used to be and float in the middle of an
    // enlarged map. Each is rebuilt from the same expression against the real book size.
    // (194 needs no entry here: the bottom-centre button's offset is HEIGHT-24, which is already exactly
    // what mapHeight() computes.)

    /** The four marker buttons down the right edge (WIDTH - 10). */
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 300), require = 0)
    private int wnn$rightEdgeButtons(int original) { return AtlasSize.bookWidth() - 10; }

    /** The right-hand nav buttons (WIDTH - 27). */
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 283), require = 0)
    private int wnn$rightNavButtons(int original) { return AtlasSize.bookWidth() - 27; }

    /** The top/bottom nav buttons, horizontally centred (WIDTH/2 - 7). */
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 148), require = 0)
    private int wnn$centreXButtons(int original) { return AtlasSize.bookWidth() / 2 - 7; }

    // The left/right nav buttons sit vertically centred (HEIGHT/2 - 9). Targeted by ordinal because the
    // constructor's FIRST 100 is unrelated to layout and must not be touched.
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 100, ordinal = 1), require = 0)
    private int wnn$centreYLeftButton(int original) { return AtlasSize.bookHeight() / 2 - 9; }

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 100, ordinal = 2), require = 0)
    private int wnn$centreYRightButton(int original) { return AtlasSize.bookHeight() / 2 - 9; }

    /** The scale bar in the bottom-left corner (HEIGHT - 20). */
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 198), require = 0)
    private int wnn$scaleBar(int original) { return AtlasSize.bookHeight() - 20; }

    // --- the art itself, which is the half the first attempt missed ---------------------------------

    @Redirect(method = "render", at = @At(value = "INVOKE", target = I_TEXTURE_DRAW, ordinal = 0), require = 0)
    private void wnn$drawBook(ITexture tex, GuiGraphics g, int x, int y) {
        tex.draw(g, x, y, AtlasSize.bookWidth(), AtlasSize.bookHeight());
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = I_TEXTURE_DRAW, ordinal = 1), require = 0)
    private void wnn$drawBookFrame(ITexture tex, GuiGraphics g, int x, int y) {
        tex.draw(g, x, y, AtlasSize.bookWidth(), AtlasSize.bookHeight());
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = I_TEXTURE_DRAW, ordinal = 2), require = 0)
    private void wnn$drawBookFrameNarrow(ITexture tex, GuiGraphics g, int x, int y) {
        tex.draw(g, x, y, AtlasSize.bookWidth(), AtlasSize.bookHeight());
    }
}
