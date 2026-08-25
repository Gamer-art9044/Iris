package art.arcane.iris.engine.river.cave;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RiverCaveContainmentPlannerTest {
    private static final List<CavePosition> DIRECTIONS = List.of(
            new CavePosition(1, 0, 0),
            new CavePosition(-1, 0, 0),
            new CavePosition(0, 1, 0),
            new CavePosition(0, -1, 0),
            new CavePosition(0, 0, 1),
            new CavePosition(0, 0, -1)
    );

    private final RiverCaveContainmentPlanner planner = new RiverCaveContainmentPlanner();

    @Test
    public void closedComponentProducesConnectedThroatPoolAndGuards() {
        TestVoxelView view = new TestVoxelView();
        view.set(CaveVoxel.CAVE_AIR, position(0, 7, 0), position(1, 7, 0), position(1, 6, 0));
        RiverCaveSource source = source(14L, 10, RiverCaveMode.CLOSED_COMPONENT, position(0, 7, 0));

        RiverCavePlan plan = planner.plan(view, source, settings());

        assertTrue(plan.accepted());
        assertEquals(RiverCaveAction.DRY_AIR, plan.actions().get(position(0, 12, 0)));
        assertEquals(RiverCaveAction.DRY_AIR, plan.actions().get(position(0, 11, 0)));
        assertEquals(RiverCaveAction.WET_SOURCE, plan.actions().get(position(0, 10, 0)));
        assertEquals(RiverCaveAction.WET_SOURCE, plan.actions().get(position(1, 6, 0)));
        assertTrue(plan.actions().containsValue(RiverCaveAction.SEAL_GUARD));
        assertMutationConnected(plan, source.entry(), position(1, 6, 0));
        assertEquals(plan.actions().keySet(), plan.baselinePreconditions().keySet());
        assertEquals(CaveVoxel.CAVE_AIR, plan.baselinePreconditions().get(position(1, 6, 0)).voxel());
        assertEquals(CaveVoxel.SOLID, plan.baselinePreconditions().get(position(0, 12, 0)).voxel());
    }

    @Test
    public void compatibleFluidFollowsExplicitPolicy() {
        TestVoxelView view = new TestVoxelView();
        CavePosition target = position(0, 7, 0);
        view.set(CaveVoxel.COMPATIBLE_FLUID, target);
        RiverCaveSource source = source(1L, 10, RiverCaveMode.CLOSED_COMPONENT, target);

        RiverCavePlan allowed = planner.plan(view, source, settings(RiverCaveFluidPolicy.ALLOW_COMPATIBLE));
        RiverCavePlan rejected = planner.plan(view, source, settings(RiverCaveFluidPolicy.REJECT_EXISTING));

        assertTrue(allowed.accepted());
        assertEquals(RiverCaveAction.WET_SOURCE, allowed.actions().get(target));
        assertEquals(RiverCaveRejection.EXISTING_FLUID, rejected.rejection());
        assertTrue(rejected.actions().isEmpty());
    }

    @Test
    public void replacementPolicyIsDistinctAndStillRejectsLava() {
        CavePosition target = position(0, 7, 0);
        TestVoxelView compatibleView = new TestVoxelView();
        compatibleView.set(CaveVoxel.COMPATIBLE_FLUID, target);
        TestVoxelView incompatibleView = new TestVoxelView();
        incompatibleView.set(CaveVoxel.INCOMPATIBLE_FLUID, target);
        TestVoxelView lavaView = new TestVoxelView();
        lavaView.set(CaveVoxel.LAVA, target);
        RiverCaveSource source = source(101L, 10, RiverCaveMode.CLOSED_COMPONENT, target);

        RiverCavePlan rejectedCompatible = planner.plan(
                compatibleView,
                source,
                settings(RiverCaveFluidPolicy.REJECT_EXISTING)
        );
        RiverCavePlan allowedCompatible = planner.plan(
                compatibleView,
                source,
                settings(RiverCaveFluidPolicy.ALLOW_COMPATIBLE)
        );
        RiverCavePlan rejectedIncompatible = planner.plan(
                incompatibleView,
                source,
                settings(RiverCaveFluidPolicy.ALLOW_COMPATIBLE)
        );
        RiverCavePlan replacedIncompatible = planner.plan(
                incompatibleView,
                source,
                settings(RiverCaveFluidPolicy.REPLACE_CONTAINED)
        );
        RiverCavePlan rejectedLava = planner.plan(
                lavaView,
                source,
                settings(RiverCaveFluidPolicy.REPLACE_CONTAINED)
        );

        assertEquals(RiverCaveRejection.EXISTING_FLUID, rejectedCompatible.rejection());
        assertTrue(allowedCompatible.accepted());
        assertEquals(RiverCaveRejection.INCOMPATIBLE_FLUID, rejectedIncompatible.rejection());
        assertTrue(replacedIncompatible.accepted());
        assertEquals(RiverCaveAction.WET_SOURCE, replacedIncompatible.actions().get(target));
        assertEquals(RiverCaveRejection.LAVA_CONTACT, rejectedLava.rejection());
    }

    @Test
    public void throatRadiusExpandsTheConnectedBoreFootprint() {
        TestVoxelView thinView = new TestVoxelView();
        TestVoxelView thickView = new TestVoxelView();
        CavePosition target = position(0, 7, 0);
        thinView.set(CaveVoxel.CAVE_AIR, target);
        thickView.set(CaveVoxel.CAVE_AIR, target);
        RiverCaveSource source = source(102L, 10, RiverCaveMode.CLOSED_COMPONENT, target);
        RiverCavePlannerSettings thinSettings = detailedSettings(
                1,
                0,
                RiverCaveGrottoShape.ELLIPSOID,
                RiverCaveFluidPolicy.ALLOW_COMPATIBLE
        );
        RiverCavePlannerSettings thickSettings = detailedSettings(
                3,
                0,
                RiverCaveGrottoShape.ELLIPSOID,
                RiverCaveFluidPolicy.ALLOW_COMPATIBLE
        );

        RiverCavePlan thin = planner.plan(thinView, source, thinSettings);
        RiverCavePlan thick = planner.plan(thickView, source, thickSettings);

        assertTrue(thin.accepted());
        assertTrue(thick.accepted());
        assertFalse(thin.actions().containsKey(position(2, 10, 0)));
        assertEquals(RiverCaveAction.WET_SOURCE, thick.actions().get(position(2, 10, 0)));
        assertTrue(mutationCount(thick) > mutationCount(thin));
    }

    @Test
    public void generatedGrottoRetainsConfiguredDryHeadroom() {
        TestVoxelView view = new TestVoxelView();
        RiverCaveSource source = source(103L, 10, RiverCaveMode.GENERATED_GROTTO, position(0, 8, 0));
        RiverCavePlannerSettings acceptedSettings = detailedSettings(
                1,
                2,
                RiverCaveGrottoShape.ELLIPSOID,
                RiverCaveFluidPolicy.ALLOW_COMPATIBLE
        );
        RiverCavePlannerSettings rejectedSettings = detailedSettings(
                1,
                5,
                RiverCaveGrottoShape.ELLIPSOID,
                RiverCaveFluidPolicy.ALLOW_COMPATIBLE
        );

        RiverCavePlan accepted = planner.plan(view, source, acceptedSettings);
        RiverCavePlan rejected = planner.plan(view, source, rejectedSettings);

        assertTrue(accepted.accepted());
        assertEquals(RiverCaveAction.FALLING_FLUID, accepted.actions().get(position(0, 11, 0)));
        assertEquals(RiverCaveAction.FALLING_FLUID, accepted.actions().get(position(0, 12, 0)));
        assertEquals(RiverCaveRejection.DRY_HEADROOM_LIMIT, rejected.rejection());
    }

    @Test
    public void configuredGrottoPredicateChangesFootprintDeterministically() {
        TestVoxelView view = new TestVoxelView();
        RiverCaveSource source = source(104L, 10, RiverCaveMode.GENERATED_GROTTO, position(0, 8, 0));
        RiverCavePlannerSettings ellipsoid = detailedSettings(
                1,
                0,
                RiverCaveGrottoShape.ELLIPSOID,
                RiverCaveFluidPolicy.ALLOW_COMPATIBLE
        );
        RiverCaveGrottoShape compactShape = (candidate, settings, dx, dy, dz) -> {
            double horizontal = settings.grottoHorizontalRadius();
            double vertical = settings.grottoVerticalRadius();
            double normalized = (dx * dx / (horizontal * horizontal))
                    + (dy * dy / (vertical * vertical))
                    + (dz * dz / (horizontal * horizontal));
            return normalized <= 0.5D;
        };
        RiverCavePlannerSettings compact = detailedSettings(
                1,
                0,
                compactShape,
                RiverCaveFluidPolicy.ALLOW_COMPATIBLE
        );

        RiverCavePlan full = planner.plan(view, source, ellipsoid);
        RiverCavePlan firstCompact = planner.plan(view, source, compact);
        RiverCavePlan secondCompact = planner.plan(view, source, compact);

        assertTrue(full.accepted());
        assertTrue(firstCompact.accepted());
        assertEquals(firstCompact, secondCompact);
        assertTrue(mutationCount(full) > mutationCount(firstCompact));
    }

    @Test
    public void radiusLimitRejectsWholeComponentWithoutClipping() {
        TestVoxelView view = new TestVoxelView();
        for (int x = 0; x <= 3; x++) {
            view.set(CaveVoxel.CAVE_AIR, position(x, 7, 0));
        }
        RiverCavePlannerSettings settings = new RiverCavePlannerSettings(
                2,
                20,
                64,
                32,
                1,
                1,
                RiverCaveFluidPolicy.ALLOW_COMPATIBLE
        );

        RiverCavePlan plan = planner.plan(
                view,
                source(2L, 10, RiverCaveMode.CLOSED_COMPONENT, position(0, 7, 0)),
                settings
        );

        assertRejectedWithoutPublication(plan, RiverCaveRejection.RADIUS_LIMIT);
    }

    @Test
    public void depthLimitRejectsWholeComponentWithoutClipping() {
        TestVoxelView view = new TestVoxelView();
        view.set(CaveVoxel.CAVE_AIR, position(0, 8, 0), position(0, 7, 0));
        RiverCavePlannerSettings settings = new RiverCavePlannerSettings(
                8,
                4,
                64,
                32,
                1,
                1,
                RiverCaveFluidPolicy.ALLOW_COMPATIBLE
        );

        RiverCavePlan plan = planner.plan(
                view,
                source(3L, 10, RiverCaveMode.CLOSED_COMPONENT, position(0, 8, 0)),
                settings
        );

        assertRejectedWithoutPublication(plan, RiverCaveRejection.DEPTH_LIMIT);
    }

    @Test
    public void volumeLimitRejectsWholeComponentWithoutClipping() {
        TestVoxelView view = new TestVoxelView();
        view.set(CaveVoxel.CAVE_AIR, position(0, 7, 0), position(1, 7, 0), position(2, 7, 0));
        RiverCavePlannerSettings settings = new RiverCavePlannerSettings(
                8,
                20,
                2,
                32,
                1,
                1,
                RiverCaveFluidPolicy.ALLOW_COMPATIBLE
        );

        RiverCavePlan plan = planner.plan(
                view,
                source(4L, 10, RiverCaveMode.CLOSED_COMPONENT, position(0, 7, 0)),
                settings
        );

        assertRejectedWithoutPublication(plan, RiverCaveRejection.VOLUME_LIMIT);
    }

    @Test
    public void worldBoundaryRejectsWholeComponent() {
        TestVoxelView view = new TestVoxelView(-16, 0, 0, 32, -16, 16);
        view.set(CaveVoxel.CAVE_AIR, position(0, 7, 0));

        RiverCavePlan plan = planner.plan(
                view,
                source(5L, 10, RiverCaveMode.CLOSED_COMPONENT, position(0, 7, 0)),
                settings()
        );

        assertRejectedWithoutPublication(plan, RiverCaveRejection.WORLD_BOUNDARY);
    }

    @Test
    public void openSurfaceRejectsWholeComponent() {
        TestVoxelView view = new TestVoxelView();
        CavePosition target = position(0, 7, 0);
        view.set(CaveVoxel.CAVE_AIR, target);
        view.openToSurface(target);

        RiverCavePlan plan = planner.plan(
                view,
                source(6L, 10, RiverCaveMode.CLOSED_COMPONENT, target),
                settings()
        );

        assertRejectedWithoutPublication(plan, RiverCaveRejection.OPEN_SURFACE);
    }

    @Test
    public void lavaAndIncompatibleFluidContactsRejectWholeComponent() {
        TestVoxelView lavaView = new TestVoxelView();
        lavaView.set(CaveVoxel.CAVE_AIR, position(0, 7, 0));
        lavaView.set(CaveVoxel.LAVA, position(1, 7, 0));
        TestVoxelView fluidView = new TestVoxelView();
        fluidView.set(CaveVoxel.CAVE_AIR, position(0, 7, 0));
        fluidView.set(CaveVoxel.INCOMPATIBLE_FLUID, position(1, 7, 0));
        RiverCaveSource source = source(7L, 10, RiverCaveMode.CLOSED_COMPONENT, position(0, 7, 0));

        RiverCavePlan lavaPlan = planner.plan(lavaView, source, settings());
        RiverCavePlan fluidPlan = planner.plan(fluidView, source, settings());

        assertRejectedWithoutPublication(lavaPlan, RiverCaveRejection.LAVA_CONTACT);
        assertRejectedWithoutPublication(fluidPlan, RiverCaveRejection.INCOMPATIBLE_FLUID);
    }

    @Test
    public void fallingHazardAbovePoolRejectsWholeComponent() {
        TestVoxelView view = new TestVoxelView();
        view.set(CaveVoxel.CAVE_AIR, position(0, 7, 0));
        view.set(CaveVoxel.LAVA, position(1, 11, 0));

        RiverCavePlan plan = planner.plan(
                view,
                source(15L, 10, RiverCaveMode.CLOSED_COMPONENT, position(0, 7, 0)),
                settings()
        );

        assertRejectedWithoutPublication(plan, RiverCaveRejection.LAVA_CONTACT);
    }

    @Test
    public void generatedGrottoHasBoundedWetDryAndGuardActions() {
        TestVoxelView view = new TestVoxelView();
        RiverCaveSource source = source(8L, 10, RiverCaveMode.GENERATED_GROTTO, position(0, 8, 0));

        RiverCavePlan plan = planner.plan(view, source, settings());

        assertTrue(plan.accepted());
        assertEquals(RiverCaveAction.FALLING_FLUID, plan.actions().get(position(0, 12, 0)));
        assertEquals(RiverCaveAction.WET_SOURCE, plan.actions().get(position(0, 8, 0)));
        assertTrue(plan.actions().containsValue(RiverCaveAction.SEAL_GUARD));
        assertEquals(plan.actions().keySet(), plan.baselinePreconditions().keySet());
        assertFalse(plan.actions().containsKey(position(0, 13, 0)));
        assertMutationConnected(plan, source.entry(), source.target());
    }

    @Test
    public void generatedGrottoAcceptsItsCompleteSurfaceMouth() {
        TestVoxelView view = new TestVoxelView();
        CavePosition[] wetMouth = {
                position(-1, 12, 0),
                position(1, 12, 0),
                position(0, 12, -1),
                position(0, 12, 1)
        };
        CavePosition[] dryMouth = {
                position(-1, 13, 0),
                position(1, 13, 0),
                position(0, 13, -1),
                position(0, 13, 1)
        };
        view.set(CaveVoxel.COMPATIBLE_FLUID, wetMouth);
        view.set(CaveVoxel.CAVE_AIR, dryMouth);
        for (CavePosition position : wetMouth) {
            view.openToSurface(position);
        }
        for (CavePosition position : dryMouth) {
            view.openToSurface(position);
        }
        RiverCaveSource source = source(108L, 10, RiverCaveMode.GENERATED_GROTTO, position(0, 8, 0));

        RiverCavePlan plan = planner.plan(view, source, detailedSettings(
                2,
                2,
                RiverCaveGrottoShape.ELLIPSOID,
                RiverCaveFluidPolicy.ALLOW_COMPATIBLE
        ));

        assertTrue(plan.rejection().toString(), plan.accepted());
        for (CavePosition position : wetMouth) {
            assertEquals(RiverCaveAction.FALLING_FLUID, plan.actions().get(position));
        }
    }

    @Test
    public void generatedGrottoRejectsAnOpenShell() {
        TestVoxelView view = new TestVoxelView();
        view.set(CaveVoxel.CAVE_AIR, position(3, 8, 0));

        RiverCavePlan plan = planner.plan(
                view,
                source(9L, 10, RiverCaveMode.GENERATED_GROTTO, position(0, 8, 0)),
                settings()
        );

        assertRejectedWithoutPublication(plan, RiverCaveRejection.GROTTO_SHELL_OPEN);
    }

    @Test
    public void deepPoolOpensOnlyAboveItsContainedFluidHead() {
        TestVoxelView view = new TestVoxelView();
        view.set(CaveVoxel.CAVE_AIR, position(0, 11, 0), position(0, 13, 0));
        RiverCaveSource source = new RiverCaveSource(
                109L,
                position(0, 10, 0),
                position(0, 8, 0),
                10,
                RiverCaveMode.DEEP_POOL
        );

        RiverCavePlan plan = planner.plan(view, source, detailedSettings(
                1,
                2,
                RiverCaveGrottoShape.ELLIPSOID,
                RiverCaveFluidPolicy.REJECT_EXISTING
        ));

        assertTrue(plan.rejection().toString(), plan.accepted());
        assertEquals(RiverCaveAction.WET_SOURCE, plan.actions().get(position(0, 10, 0)));
        assertEquals(RiverCaveAction.DRY_AIR, plan.actions().get(position(0, 11, 0)));
        assertFalse(plan.actions().containsKey(position(0, 13, 0)));
    }

    @Test
    public void deepPoolRejectsCaveLeakAtOrBelowItsFluidHead() {
        TestVoxelView view = new TestVoxelView();
        view.set(CaveVoxel.CAVE_AIR, position(5, 8, 0));
        RiverCaveSource source = new RiverCaveSource(
                110L,
                position(0, 10, 0),
                position(0, 8, 0),
                10,
                RiverCaveMode.DEEP_POOL
        );

        RiverCavePlan plan = planner.plan(view, source, detailedSettings(
                1,
                2,
                RiverCaveGrottoShape.ELLIPSOID,
                RiverCaveFluidPolicy.REJECT_EXISTING
        ));

        assertRejectedWithoutPublication(plan, RiverCaveRejection.GROTTO_SHELL_OPEN);
    }

    @Test
    public void combinedModeUsesClosedCaveOrGeneratedGrottoFromBaseline() {
        TestVoxelView caveView = new TestVoxelView();
        CavePosition target = position(0, 8, 0);
        caveView.set(CaveVoxel.CAVE_AIR, target);
        TestVoxelView solidView = new TestVoxelView();
        RiverCaveSource source = source(10L, 10, RiverCaveMode.GROTTO_OR_CLOSED_COMPONENT, target);

        RiverCavePlan cavePlan = planner.plan(caveView, source, settings());
        RiverCavePlan grottoPlan = planner.plan(solidView, source, settings());

        assertTrue(cavePlan.accepted());
        assertTrue(grottoPlan.accepted());
        assertEquals(CaveVoxel.CAVE_AIR, cavePlan.baselinePreconditions().get(target).voxel());
        assertEquals(CaveVoxel.SOLID, grottoPlan.baselinePreconditions().get(target).voxel());
    }

    @Test
    public void waterfallKeepsColumnDistinctFromSourcePoolAndDryAir() {
        TestVoxelView view = new TestVoxelView();
        RiverCaveSource source = source(11L, 10, RiverCaveMode.WATERFALL_POOL, position(0, 8, 0));

        RiverCavePlan plan = planner.plan(view, source, settings());

        assertTrue(plan.accepted());
        assertEquals(RiverCaveAction.FALLING_FLUID, plan.actions().get(position(0, 12, 0)));
        assertEquals(RiverCaveAction.FALLING_FLUID, plan.actions().get(position(0, 11, 0)));
        assertEquals(RiverCaveAction.WET_SOURCE, plan.actions().get(position(0, 10, 0)));
        assertFalse(plan.actions().containsValue(RiverCaveAction.DRY_AIR));
    }

    @Test
    public void waterfallRejectsAnUnsealedFallingColumn() {
        TestVoxelView view = new TestVoxelView();
        view.set(CaveVoxel.CAVE_AIR, position(0, 8, 0), position(1, 11, 0));
        RiverCaveSource source = source(13L, 10, RiverCaveMode.WATERFALL_POOL, position(0, 8, 0));

        RiverCavePlan plan = planner.plan(view, source, settings());

        assertRejectedWithoutPublication(plan, RiverCaveRejection.WATERFALL_SHAFT_OPEN);
    }

    @Test
    public void overlapArbitrationIsOrderIndependentAndNamesWinner() {
        TestVoxelView view = new TestVoxelView();
        view.set(CaveVoxel.CAVE_AIR, position(0, 7, 0), position(1, 7, 0), position(2, 7, 0));
        RiverCaveSource lowerHead = new RiverCaveSource(
                1L,
                position(2, 12, 0),
                position(2, 7, 0),
                10,
                RiverCaveMode.CLOSED_COMPONENT
        );
        RiverCaveSource higherHead = new RiverCaveSource(
                20L,
                position(0, 12, 0),
                position(0, 7, 0),
                11,
                RiverCaveMode.CLOSED_COMPONENT
        );

        RiverCavePlanningResult forward = planner.planAll(view, List.of(lowerHead, higherHead), settings());
        RiverCavePlanningResult reverse = planner.planAll(view, List.of(higherHead, lowerHead), settings());

        assertEquals(forward, reverse);
        assertEquals(higherHead, forward.plans().get(0).source());
        assertTrue(forward.plans().get(0).accepted());
        assertEquals(RiverCaveRejection.OVERLAPPING_SOURCE, forward.plans().get(1).rejection());
        assertEquals(20L, forward.plans().get(1).arbitrationWinnerSourceId().getAsLong());
        assertEquals(forward.actions().keySet(), forward.baselinePreconditions().keySet());
    }

    @Test
    public void equalHeadOverlapUsesLowestStableSourceId() {
        TestVoxelView view = new TestVoxelView();
        view.set(CaveVoxel.CAVE_AIR, position(0, 7, 0), position(1, 7, 0), position(2, 7, 0));
        RiverCaveSource largerId = new RiverCaveSource(
                20L,
                position(0, 12, 0),
                position(0, 7, 0),
                10,
                RiverCaveMode.CLOSED_COMPONENT
        );
        RiverCaveSource smallerId = new RiverCaveSource(
                1L,
                position(2, 12, 0),
                position(2, 7, 0),
                10,
                RiverCaveMode.CLOSED_COMPONENT
        );

        RiverCavePlanningResult result = planner.planAll(view, List.of(largerId, smallerId), settings());

        assertEquals(smallerId, result.plans().get(0).source());
        assertTrue(result.plans().get(0).accepted());
        assertEquals(1L, result.plans().get(1).arbitrationWinnerSourceId().getAsLong());
    }

    @Test
    public void arbitrationUsesDirectHigherPriorityPlansEvenWhenTheyAreRejected() {
        TestVoxelView view = new TestVoxelView();
        view.set(
                CaveVoxel.CAVE_AIR,
                position(0, 7, 0),
                position(2, 7, 0),
                position(4, 7, 0)
        );
        RiverCaveSource lowest = new RiverCaveSource(
                30L,
                position(0, 12, 0),
                position(0, 7, 0),
                10,
                RiverCaveMode.CLOSED_COMPONENT
        );
        RiverCaveSource middle = new RiverCaveSource(
                20L,
                position(2, 12, 0),
                position(2, 7, 0),
                11,
                RiverCaveMode.CLOSED_COMPONENT
        );
        RiverCaveSource highest = new RiverCaveSource(
                10L,
                position(4, 12, 0),
                position(4, 7, 0),
                12,
                RiverCaveMode.CLOSED_COMPONENT
        );

        RiverCavePlanningResult forward = planner.planAll(
                view,
                List.of(lowest, middle, highest),
                settings()
        );
        RiverCavePlanningResult reverse = planner.planAll(
                view,
                List.of(highest, middle, lowest),
                settings()
        );

        assertEquals(forward, reverse);
        assertTrue(forward.plans().get(0).accepted());
        assertEquals(RiverCaveRejection.OVERLAPPING_SOURCE, forward.plans().get(1).rejection());
        assertEquals(10L, forward.plans().get(1).arbitrationWinnerSourceId().getAsLong());
        assertEquals(RiverCaveRejection.OVERLAPPING_SOURCE, forward.plans().get(2).rejection());
        assertEquals(20L, forward.plans().get(2).arbitrationWinnerSourceId().getAsLong());
        assertFalse(forward.actions().containsKey(position(0, 7, 0)));
        assertTrue(forward.actions().containsKey(position(4, 7, 0)));
    }

    @Test
    public void planOutputsAreImmutable() {
        TestVoxelView view = new TestVoxelView();
        view.set(CaveVoxel.CAVE_AIR, position(0, 7, 0));
        RiverCaveSource source = source(12L, 10, RiverCaveMode.CLOSED_COMPONENT, position(0, 7, 0));
        RiverCavePlanningResult result = planner.planAll(view, List.of(source), settings());
        RiverCavePlan plan = result.plans().get(0);

        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.actions().put(position(9, 9, 9), RiverCaveAction.WET_SOURCE)
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.baselinePreconditions().put(
                        position(9, 9, 9),
                        new CaveVoxelPrecondition(CaveVoxel.SOLID, false)
                )
        );
        assertThrows(UnsupportedOperationException.class, () -> result.plans().add(plan));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.actions().put(position(9, 9, 9), RiverCaveAction.WET_SOURCE)
        );
    }

    private void assertMutationConnected(RiverCavePlan plan, CavePosition start, CavePosition expected) {
        Set<CavePosition> mutations = new HashSet<>();
        for (Map.Entry<CavePosition, RiverCaveAction> entry : plan.actions().entrySet()) {
            if (entry.getValue() != RiverCaveAction.SEAL_GUARD) {
                mutations.add(entry.getKey());
            }
        }
        Queue<CavePosition> queue = new ArrayDeque<>();
        Set<CavePosition> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            CavePosition position = queue.remove();
            for (CavePosition direction : DIRECTIONS) {
                CavePosition neighbor = position.offset(direction.x(), direction.y(), direction.z());
                if (mutations.contains(neighbor) && visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        assertTrue(visited.contains(expected));
        assertEquals(mutations, visited);
    }

    private int mutationCount(RiverCavePlan plan) {
        int count = 0;
        for (RiverCaveAction action : plan.actions().values()) {
            if (action != RiverCaveAction.SEAL_GUARD) {
                count++;
            }
        }
        return count;
    }

    private void assertRejectedWithoutPublication(RiverCavePlan plan, RiverCaveRejection rejection) {
        assertFalse(plan.accepted());
        assertEquals(rejection, plan.rejection());
        assertTrue(plan.actions().isEmpty());
        assertTrue(plan.baselinePreconditions().isEmpty());
    }

    private RiverCaveSource source(long sourceId, int waterHeadY, RiverCaveMode mode, CavePosition target) {
        return new RiverCaveSource(sourceId, position(0, 12, 0), target, waterHeadY, mode);
    }

    private RiverCavePlannerSettings settings() {
        return settings(RiverCaveFluidPolicy.ALLOW_COMPATIBLE);
    }

    private RiverCavePlannerSettings settings(RiverCaveFluidPolicy policy) {
        return new RiverCavePlannerSettings(12, 20, 64, 32, 2, 2, policy);
    }

    private RiverCavePlannerSettings detailedSettings(
            int throatRadius,
            int dryHeadroom,
            RiverCaveGrottoShape shape,
            RiverCaveFluidPolicy policy
    ) {
        return new RiverCavePlannerSettings(
                12,
                20,
                2048,
                32,
                throatRadius,
                4,
                4,
                dryHeadroom,
                policy,
                shape
        );
    }

    private CavePosition position(int x, int y, int z) {
        return new CavePosition(x, y, z);
    }

    private static final class TestVoxelView implements CaveVoxelView {
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;
        private final Map<CavePosition, CaveVoxel> voxels = new HashMap<>();
        private final Set<CavePosition> surfaceOpenings = new HashSet<>();

        private TestVoxelView() {
            this(-32, 32, 0, 32, -32, 32);
        }

        private TestVoxelView(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        @Override
        public boolean isInWorld(CavePosition position) {
            return position.x() >= minX
                    && position.x() <= maxX
                    && position.y() >= minY
                    && position.y() <= maxY
                    && position.z() >= minZ
                    && position.z() <= maxZ;
        }

        @Override
        public CaveVoxel voxelAt(CavePosition position) {
            return voxels.getOrDefault(position, CaveVoxel.SOLID);
        }

        @Override
        public boolean isOpenToSurface(CavePosition position) {
            return surfaceOpenings.contains(position);
        }

        private void set(CaveVoxel voxel, CavePosition... positions) {
            for (CavePosition position : positions) {
                voxels.put(position, voxel);
            }
        }

        private void openToSurface(CavePosition position) {
            surfaceOpenings.add(position);
        }
    }
}
