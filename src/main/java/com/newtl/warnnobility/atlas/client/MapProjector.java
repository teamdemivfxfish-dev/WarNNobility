package com.newtl.warnnobility.atlas.client;

/**
 * Maps a world (block) position to a screen pixel on whatever map surface is being drawn: the opened atlas
 * book, the in-hand atlas, or the War Frame board. Every map layer takes one of these and nothing else about
 * the surface, which is what keeps the layers identical across all three and keeps them registered with each
 * other, since they are all reading the same projection.
 *
 * <p>Implementations must be linear and unrotated: {@link MapRaster} inverts the projection by probing it.
 */
@FunctionalInterface
public interface MapProjector {

    /** The screen pixel {@code {x, y}} for a world position, or null if it does not fall on this surface. */
    double[] project(double worldX, double worldZ);
}
