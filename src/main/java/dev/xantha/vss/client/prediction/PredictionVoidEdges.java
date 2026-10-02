package dev.xantha.vss.client.prediction;

import java.util.function.BooleanSupplier;

/**
 * Void edges are the one place where the exterior heightfield cannot lean on
 * its neighbour: the adjacent column holds no rock at all, so the connecting
 * wall has nothing to hide behind and the unverified fallback would extend it
 * down to the dimension floor, painting rock across open sky. This pass probes
 * the underside of the columns that face void and records it as the sample's
 * surfaceBottom, which bounds the wall to the column's own confirmed mass.
 * Interior columns keep the closed heightfield: their walls only span height
 * differences between neighbours, so they never bridge a void.
 */
final class PredictionVoidEdges {
    /** Probe budget per sampling pass; rim columns beyond it keep the closed fallback. */
    static final int MAX_PROBES = 1024;

    @FunctionalInterface
    interface Publisher {
        void publish(int blockX, int blockZ, ClientColumnSample sample);
    }

    private PredictionVoidEdges() { }

    /** Returns the number of columns that gained a confirmed underside. */
    static int enrich(ClientColumnSample[] samples, int grid, int step, int baseX, int baseZ,
                      ClientTerrainSampler sampler, BooleanSupplier valid, Publisher publisher) {
        if (sampler == null || samples == null) return 0;
        // Without a single void column the heightfield's closes are sound and
        // the probes would cost every cliff in a normal dimension for nothing.
        if (!containsVoid(samples)) return 0;
        int probes = 0;
        // A static speck far below an island pulls a wall from the island's top
        // down to the speck's surface, so the drop threshold matches the one
        // the whole-cell profile uses to call a column a cliff.
        int drop = Math.max(8, step * 2);
        for (int z = 0; z < grid; z++) {
            if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean())
                throw new java.util.concurrent.CancellationException();
            for (int x = 0; x < grid; x++) {
                int index = z * grid + x;
                ClientColumnSample sample = samples[index];
                if (sample == null || !sample.hasSurface() || sample.floating()
                        || sample.captured() || sample.volume() != null) continue;
                if (!facesVoidOrDrop(samples, grid, x, z, sample.surfaceY(), drop)) continue;
                if (probes >= MAX_PROBES) return probes;
                int blockX = baseX + (x - VssLodLayout.SAMPLE_MARGIN) * step;
                int blockZ = baseZ + (z - VssLodLayout.SAMPLE_MARGIN) * step;
                int bottom = sampler.undersideY(blockX, blockZ, sample.surfaceY());
                if (bottom == ClientColumnSample.NO_SPAN || bottom >= sample.surfaceY()) continue;
                ClientColumnSample probed = withUnderside(sample, bottom);
                samples[index] = probed;
                if (publisher != null) publisher.publish(blockX, blockZ, probed);
                probes++;
            }
        }
        return probes;
    }

    private static boolean containsVoid(ClientColumnSample[] samples) {
        for (ClientColumnSample sample : samples) {
            if (sample != null && !sample.hasSurface()) return true;
        }
        return false;
    }

    /** Sampled grids carry one margin ring, so grid coordinates map to the same block positions. */
    private static boolean facesVoidOrDrop(ClientColumnSample[] samples, int grid, int x, int z,
                                           int height, int drop) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dz == 0) continue;
            int xx = x + dx, zz = z + dz;
            if (xx < 0 || zz < 0 || xx >= grid || zz >= grid) continue;
            ClientColumnSample neighbor = samples[zz * grid + xx];
            if (neighbor == null || !neighbor.hasSurface()) return true;
            if (height - neighbor.surfaceY() >= drop) return true;
        }
        return false;
    }

    private static ClientColumnSample withUnderside(ClientColumnSample s, int bottom) {
        return new ClientColumnSample(s.surfaceY(), s.fluidY(), s.biomeIndex(), s.topBlockIndex(),
                s.structureIndex(), s.treeKind(), s.treeDensity(), s.treeHeight(), s.fluid(),
                s.flags() | ClientColumnSample.FLAG_CONFIRMED_UNDERSIDE,
                s.groundFeatureKind(), s.underBlockIndex(), s.deepBlockIndex(), bottom,
                s.lowerTop(), s.lowerBottom(), s.spanFloor(), s.volume());
    }
}
