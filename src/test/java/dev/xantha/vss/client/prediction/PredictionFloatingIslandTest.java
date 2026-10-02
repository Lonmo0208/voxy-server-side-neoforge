package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A dimension whose terrain hangs in the void, the way the reported Aether
 * islands do. Both prediction paths must keep the void: the near field covers
 * whole cells with a footprint union whose lower range used to be assumed
 * solid, and the coarse field draws connecting walls from the heightfield.
 */
class PredictionFloatingIslandTest {
    private static final int ISLAND_BOTTOM = 64;
    private static final int ISLAND_TOP = 97;
    /** A sparse low speck, the way the Aether's density still returns rock below its islands. */
    private static final int SPECK_BOTTOM = 26;
    private static final int SPECK_TOP = 34;
    private static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.withDefaultNamespace("overworld"), -64, 256,
            "noise", "minecraft:overworld", 0L);

    @BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    /** Solid only where a flat noise mask is positive; the highest mask also seeds low specks. */
    private static ClientTerrainSampler sampler() {
        var lookup = VanillaRegistries.createLookup();
        var mask = DensityFunctions.noise(
                lookup.lookupOrThrow(Registries.NOISE).getOrThrow(net.minecraft.world.level.levelgen.Noises.CONTINENTALNESS),
                1.0D / 48.0D, 0.0D);
        DensityFunction islands = DensityFunctions.max(
                speckSlab(mask, 0.0D, ISLAND_BOTTOM, ISLAND_TOP),
                speckSlab(mask, 0.2D, SPECK_BOTTOM, SPECK_TOP));
        var zero = DensityFunctions.zero();
        var router = new NoiseRouter(zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero,
                islands, zero, zero, zero);
        var settings = new NoiseGeneratorSettings(NoiseSettings.create(-64, 256, 1, 2),
                Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), router,
                SurfaceRules.state(Blocks.STONE.defaultBlockState()), List.of(), -64, false, false, false, false);
        var source = new FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
        var generator = new NoiseBasedChunkGenerator(source, Holder.direct(settings));
        var random = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 4242L);
        return new ClientTerrainSampler(4242L, PROFILE, generator, random,
                LevelHeightAccessor.create(-64, 256), 63, List.of(), null, null);
    }

    private static DensityFunction speckSlab(DensityFunction mask, double threshold, int bottom, int top) {
        var slab = DensityFunctions.rangeChoice(
                DensityFunctions.yClampedGradient(-64, 256, -64, 256), bottom, top,
                DensityFunctions.constant(1.0D), DensityFunctions.constant(-1.0D));
        return DensityFunctions.rangeChoice(mask, threshold, 1.0E9D, slab, DensityFunctions.constant(-1.0D));
    }

    @Test
    void islandsBesideLowSpecksDoNotHangWallsIntoTheVoid() {
        var sampler = sampler();
        int step = 4;
        int speckX = findSpeckNextToIsland(sampler, step);
        int islandX = sampler.sampleSurface(speckX - step, 0).surfaceY() >= ISLAND_TOP - 1
                ? speckX - step : speckX + step;
        int baseX = Math.min(islandX, speckX) - 2 * step;
        int baseZ = -2 * step;
        int islandCell = islandX < speckX ? 3 : 4;
        var samples = sample(sampler, baseX, baseZ, step);
        var island = samples[grid() * 3 + islandCell];
        var speck = samples[grid() * 3 + (islandCell == 3 ? 4 : 3)];
        assertTrue(island.surfaceY() >= ISLAND_TOP - 1, "the pair must hold island rock");
        assertTrue(speck.surfaceY() <= SPECK_TOP + 1, "the pair must hold a low speck");
        assertEquals(Arrays.asList(new PredictionLodSeams.HeightSpan(speck.surfaceY(), island.surfaceY())),
                PredictionWallEvidence.intervals(island, speck.surfaceY(), island.surfaceY(), step),
                "unmeasured island rock still hangs down to the speck");

        PredictionVoidEdges.enrich(samples, grid(), step, baseX, baseZ, sampler, () -> true, null);
        island = samples[grid() * 3 + islandCell];
        assertTrue(island.confirmedUnderside(), "the drop to a speck must trigger the probe");
        assertEquals(ISLAND_BOTTOM, island.surfaceBottom());
        assertEquals(Arrays.asList(new PredictionLodSeams.HeightSpan(ISLAND_BOTTOM, ISLAND_TOP)),
                PredictionWallEvidence.intervals(island, speck.surfaceY(), island.surfaceY(), step),
                "the island wall stops at its own bottom instead of the speck");
    }

    private static int grid() { return 7; }

    private static ClientColumnSample[] sample(ClientTerrainSampler sampler, int baseX, int baseZ, int step) {
        int grid = grid();
        var samples = new ClientColumnSample[grid * grid];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                int blockX = baseX + (x - VssLodLayout.SAMPLE_MARGIN) * step;
                int blockZ = baseZ + (z - VssLodLayout.SAMPLE_MARGIN) * step;
                samples[z * grid + x] = sampler.sampleSurface(blockX, blockZ);
            }
        }
        return samples;
    }

    /** A speck column that touches island rock: its low surface pulls a wall off the island. */
    private static int findSpeckNextToIsland(ClientTerrainSampler sampler, int step) {
        for (int x = 64; x < 8192; x += step) {
            if (sampler.sampleSurface(x, 0).surfaceY() > SPECK_TOP + step) continue;
            for (int d = -step; d <= step; d += step) {
                if (d == 0) continue;
                if (sampler.sampleSurface(x + d, 0).surfaceY() >= ISLAND_TOP - 1) return x;
            }
        }
        throw new IllegalStateException("the synthetic world has no speck beside an island");
    }

    @Test
    void voidColumnsCarryNoSurfaceAndIslandsKnowTheirBottom() {
        var sampler = sampler();
        int boundary = findBoundary(sampler);
        boolean islandsFirst = sampler.sampleSurface(0, 0).hasSurface();
        int islandX = islandsFirst ? boundary - 1 : boundary + 1;
        var island = sampler.sampleSurface(islandX, 0);

        assertTrue(island.hasSurface(), "island column must keep its surface");
        assertEquals(ISLAND_TOP, island.surfaceY());
        assertFalse(sampler.sampleSurface(boundary, 0).hasSurface(),
                "a column with no solid at all is void, not a floor plane");
        assertEquals(ISLAND_BOTTOM, sampler.undersideY(islandX, 0, island.surfaceY()));
    }

    @Test
    void nearFieldCellsThatFaceVoidStopAtTheIslandBottom() {
        var sampler = sampler();
        int baseX = findBoundary(sampler) - 2 * 4;
        var closed = meshed(sampler, baseX, 4, false);
        var measured = meshed(sampler, baseX, 4, true);
        assertEquals(ISLAND_BOTTOM, lowestWallY(measured),
                "a measured underside must bound the near-field wall");
        assertTrue(lowestWallY(closed) < ISLAND_BOTTOM,
                "without the measurement the whole-cell profile still walls off the void below it");
    }

    @Test
    void coarseTilesThatFaceVoidStopAtTheIslandBottom() {
        var sampler = sampler();
        int baseX = findBoundary(sampler) - 2 * 16;
        var closed = meshed(sampler, baseX, 16, false);
        var measured = meshed(sampler, baseX, 16, true);
        assertEquals(ISLAND_BOTTOM, lowestWallY(measured),
                "the heightfield fallback must stop at the measured island bottom");
        assertTrue(lowestWallY(closed) < ISLAND_BOTTOM,
                "unmeasured coarse tiles still wall off the void below them");
    }

    /** Samples a small tile with the tile manager's margin convention and meshes it. */
    private static PredictionMesh meshed(ClientTerrainSampler sampler, int baseX, int step, boolean probeVoidEdges) {
        int grid = 7;
        var samples = new ClientColumnSample[grid * grid];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                int blockX = baseX + (x - VssLodLayout.SAMPLE_MARGIN) * step;
                int blockZ = (z - VssLodLayout.SAMPLE_MARGIN) * step;
                samples[z * grid + x] = sampler.sampleSurface(blockX, blockZ);
            }
        }
        if (probeVoidEdges)
            PredictionVoidEdges.enrich(samples, grid, step, baseX, 0, sampler, () -> true, null);
        PredictionExteriorColumns.enrich(samples, grid, step, baseX, 0, sampler, () -> true);
        return PredictionMeshBuilder.build(samples, null, 63, 0, step, grid, false);
    }

    /** First column whose surface state differs from the previous one: an island rim. */
    private static int findBoundary(ClientTerrainSampler sampler) {
        boolean first = sampler.sampleSurface(0, 0).hasSurface();
        for (int x = 4; x < 8192; x += 4) {
            if (sampler.sampleSurface(x, 0).hasSurface() == first) continue;
            for (int fine = x - 4; fine <= x; fine++) {
                if (sampler.sampleSurface(fine, 0).hasSurface() != first) return fine;
            }
        }
        throw new IllegalStateException("the synthetic world has no island rim");
    }

    /** Lowest Y of vertical (wall) geometry: horizontal faces such as an assumed
     * basement are not what paints the void shut. */
    private static float lowestWallY(PredictionMesh mesh) {
        float lowest = Float.MAX_VALUE;
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            if (Math.abs(mesh.normalY(vertex)) > 0.5F) continue;
            lowest = Math.min(lowest, mesh.y(vertex));
        }
        return lowest;
    }
}
