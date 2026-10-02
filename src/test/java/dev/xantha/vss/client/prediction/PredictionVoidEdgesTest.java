package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.Arrays;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Void-facing walls: the reported artifact was a floating island whose rim
 * walls ran down to the dimension floor, so the wall evidence has to stop at
 * the column's own confirmed mass.
 */
class PredictionVoidEdgesTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.withDefaultNamespace("overworld"), -64, 384,
            "noise", "minecraft:overworld", 1L);

    @BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test
    void probeFindsTheBottomOfTheFloatingMass() {
        // Solid only inside [80, 100]: an island hanging in the void.
        assertEquals(80, PredictionColumnProbe.underside(101, -64, y -> y >= 80 && y <= 100));
    }

    @Test
    void probeRefusesToInventAnUndersideAboveTheDimensionFloor() {
        assertEquals(ClientColumnSample.NO_SPAN,
                PredictionColumnProbe.underside(101, -64, y -> true));
    }

    @Test
    void probeKeepsThinAndLayeredMassesExact() {
        assertEquals(100, PredictionColumnProbe.underside(101, -64, y -> y == 100));
        // Rock below the island is a second mass with air between it and the
        // surface one, so the surface mass still ends at 99.
        assertEquals(99, PredictionColumnProbe.underside(101, -64, y -> y == 95 || y >= 99 && y <= 100));
    }

    @Test
    void measuredUndersideBoundsTheWallWithoutOpeningTheStrata() {
        var rim = measured(PredictionSimpleVegetationTest.sample(120), 100);
        assertEquals(Arrays.asList(new PredictionLodSeams.HeightSpan(100, 120)),
                PredictionWallEvidence.intervals(rim, -64, 120, 8),
                "confirmed air below the mass must not be painted over");
        // Inside the mass the wall stays closed: no hole is carved between the
        // surface and the measured bottom.
        assertEquals(Arrays.asList(new PredictionLodSeams.HeightSpan(100, 110)),
                PredictionWallEvidence.intervals(rim, -64, 110, 8));
    }

    @Test
    void unverifiedSpansKeepTheClosedHeightfield() {
        var unmeasured = spansOnly(PredictionSimpleVegetationTest.sample(120), 100);
        assertEquals(Arrays.asList(new PredictionLodSeams.HeightSpan(-64, 120)),
                PredictionWallEvidence.intervals(unmeasured, -64, 120, 8),
                "a bottom that no probe measured must not change geometry");
    }

    @Test
    void capturedFloatingMassBoundsItsOwnWallAtAnySpacing() {
        // The reported Aether case: a real captured column holding a single
        // floating island. Nothing below it was captured, so the interior rule
        // never applies and the wall used to fall to the neighbour's height.
        var island = PredictionWallEvidence.inspectCaptured(captured(120), -64, y -> y >= 100);
        assertEquals(100, island.surfaceBottom());
        for (int spacing : new int[] {1, 2, 4, 8, 16, 32}) {
            assertEquals(Arrays.asList(new PredictionLodSeams.HeightSpan(100, 120)),
                    PredictionWallEvidence.intervals(island, -64, 120, spacing),
                    "a classified capture bounds its own wall at spacing " + spacing);
        }
        // A capture that also recorded rock below keeps its hole closed: the
        // wall would otherwise lose the lower mass it carries.
        var layered = PredictionWallEvidence.inspectCaptured(captured(120), -64, y -> y >= 100 || y < 60);
        assertEquals(Arrays.asList(new PredictionLodSeams.HeightSpan(-64, 120)),
                PredictionWallEvidence.intervals(layered, -64, 120, 8),
                "a layered capture keeps its closed wall");
    }

    @Test
    void legacyCapturesKeepTheClosedHeightfield() {
        var verified = PredictionWallEvidence.inspectCaptured(captured(120), -64, y -> y >= 100);
        var legacy = PredictionCaveInteriorTest.withFlags(verified,
                verified.flags() & ~PredictionWallEvidence.CAPTURED_OCCUPANCY);
        assertEquals(Arrays.asList(new PredictionLodSeams.HeightSpan(-64, 120)),
                PredictionWallEvidence.intervals(legacy, -64, 120, 1),
                "an unclassified capture must not bound geometry");
    }

    @Test
    void wallAgainstVoidStopsAtTheMeasuredUnderside() {
        var rim = measured(PredictionSimpleVegetationTest.sample(120), 100);
        var voidColumn = voidSample(0);
        assertEquals(Arrays.asList(new PredictionLodSeams.HeightSpan(100, 120)),
                PredictionWallEvidence.exposed(rim, voidColumn, 120, 0, 8));
        // Without a measurement the heightfield still closes the wall, which is
        // how the void floor used to be painted over.
        assertEquals(Arrays.asList(new PredictionLodSeams.HeightSpan(0, 120)),
                PredictionWallEvidence.exposed(PredictionSimpleVegetationTest.sample(120), voidColumn, 120, 0, 8));
    }

    @Test
    void voidEdgePassMeasuresOnlyColumnsThatFaceVoid() {
        var samples = new ClientColumnSample[9];
        Arrays.fill(samples, PredictionSimpleVegetationTest.sample(120));
        samples[0] = voidSample(0);
        var sampler = new StubSampler();
        int probes = PredictionVoidEdges.enrich(samples, 3, 8, -8, -8, sampler, () -> true, null);
        // Grid (1,0), (0,1) and the diagonal (1,1) touch the void column;
        // the cells behind them keep their closed heightfield.
        assertEquals(3, probes);
        assertEquals(100, samples[1].surfaceBottom());
        assertEquals(100, samples[3].surfaceBottom());
        assertEquals(100, samples[4].surfaceBottom());
        assertEquals(ClientColumnSample.NO_SPAN, samples[8].surfaceBottom());
    }

    @Test
    void meshClosesVoidFacingColumnsAtTheirUnderside() {
        var measured = new ClientColumnSample[9];
        var unmeasured = new ClientColumnSample[9];
        Arrays.fill(measured, PredictionSimpleVegetationTest.sample(120));
        Arrays.fill(unmeasured, PredictionSimpleVegetationTest.sample(120));
        measured[0] = voidSample(0);
        unmeasured[0] = voidSample(0);
        var sampler = new StubSampler();
        int probes = PredictionVoidEdges.enrich(measured, 3, 8, -8, -8, sampler, () -> true, null);

        var closed = PredictionMeshBuilder.build(unmeasured, null, 63, 0, 8, 3, false);
        var bounded = PredictionMeshBuilder.build(measured, null, 63, 0, 8, 3, false);

        assertEquals(3, probes);
        assertEquals(0.0F, lowestVertexY(closed), "unmeasured rim wall reaches the void floor");
        assertEquals(100.0F, lowestVertexY(bounded), "measured rim wall stops at the island bottom");
        // Each measured rim cell gains exactly its underside face (two
        // triangles) on top of the geometry of the closed heightfield.
        assertEquals(closed.vertexCount() + 6 * probes, bounded.vertexCount());
    }

    private static float lowestVertexY(PredictionMesh mesh) {
        float lowest = Float.MAX_VALUE;
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++)
            lowest = Math.min(lowest, mesh.y(vertex));
        return lowest;
    }

    private static ClientColumnSample measured(ClientColumnSample sample, int bottom) {
        return withBottom(sample, bottom, sample.flags() | ClientColumnSample.FLAG_CONFIRMED_UNDERSIDE);
    }

    /** A bottom that no probe measured: the closed heightfield must ignore it. */
    private static ClientColumnSample spansOnly(ClientColumnSample sample, int bottom) {
        return withBottom(sample, bottom, sample.flags());
    }

    private static ClientColumnSample withBottom(ClientColumnSample sample, int bottom, int flags) {
        return new ClientColumnSample(sample.surfaceY(), sample.fluidY(), sample.biomeIndex(),
                sample.topBlockIndex(), sample.structureIndex(), sample.treeKind(), sample.treeDensity(),
                sample.treeHeight(), sample.fluid(), flags, sample.groundFeatureKind(),
                sample.underBlockIndex(), sample.deepBlockIndex(), bottom,
                sample.lowerTop(), sample.lowerBottom(), sample.spanFloor());
    }

    private static ClientColumnSample captured(int height) {
        return PredictionCaveInteriorTest.withFlags(PredictionSimpleVegetationTest.sample(height),
                ClientColumnSample.FLAG_CAPTURED);
    }

    private static ClientColumnSample voidSample(int y) {
        return new ClientColumnSample(y, y, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                0, ClientColumnSample.FLAG_SURFACE_ONLY | ClientColumnSample.FLAG_NO_SURFACE, 0,
                PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.stoneIndex(),
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }

    /** Reports the island bottom only where the test world has one. */
    private static final class StubSampler extends ClientTerrainSampler {
        StubSampler() { super(42L, PROFILE); }

        @Override int undersideY(int blockX, int blockZ, int surfaceY) {
            return blockX <= -1 && blockZ <= 0 ? 100 : ClientColumnSample.NO_SPAN;
        }
    }
}
