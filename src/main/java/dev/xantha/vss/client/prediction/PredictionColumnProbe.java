package dev.xantha.vss.client.prediction;

import java.util.function.IntPredicate;

/**
 * Bounded downward probe for the bottom of a column's solid mass. The walk
 * steps down and only refines a boundary once it meets confirmed air, so a
 * column that stays solid to the dimension floor costs one evaluation per step
 * instead of one per block. Nothing but confirmed air ends the walk: an
 * exhausted window keeps the column closed.
 */
final class PredictionColumnProbe {
    private static final int STEP = 8;

    private PredictionColumnProbe() { }

    /**
     * Lowest solid block of the mass containing {@code surfaceY - 1}, or
     * {@link ClientColumnSample#NO_SPAN} when the walk reaches {@code minY}
     * still solid (the mass may continue to the floor, so it has no measured
     * underside).
     */
    static int underside(int surfaceY, int minY, IntPredicate solid) {
        int top = surfaceY - 1;
        if (top < minY || !solid.test(top)) return ClientColumnSample.NO_SPAN;
        int solidAbove = top;
        int air = Integer.MIN_VALUE;
        for (int y = top - STEP; ; y -= STEP) {
            int probe = Math.max(y, minY);
            if (!solid.test(probe)) {
                air = probe;
                break;
            }
            solidAbove = probe;
            if (probe == minY) break;
        }
        if (air == Integer.MIN_VALUE) return ClientColumnSample.NO_SPAN;
        // air is confirmed air and solidAbove is confirmed solid; the mass
        // bottom sits between them.
        int low = air;
        int high = solidAbove;
        while (high - low > 1) {
            int middle = low + (high - low) / 2;
            if (solid.test(middle)) high = middle; else low = middle;
        }
        return high;
    }
}
