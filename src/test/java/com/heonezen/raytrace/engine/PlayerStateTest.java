package com.heonezen.raytrace.engine;

import com.heonezen.raytrace.config.GridSettings;
import com.heonezen.raytrace.util.CellPos;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStateTest {

    private static final GridSettings.BlockGrid GRID = new GridSettings.BlockGrid(8, 1, 2L, 1.2, 6.0, 4.0, 80_000, 2_600, 32_768, 1_000L, 6_000L);
    private static final int PASSES = 3;

    private static boolean scan(PlayerState state, double x, float yaw) {
        return state.shouldRefreshScan("world", x, 64, 0, yaw, 0, GRID, PASSES);
    }

    private static void ticks(PlayerState state, int count) {
        for (int i = 0; i < count; i++) {
            state.incrementTick();
        }
    }

    @Test
    void standingStillGivesExactlyTheRefinementPassesThenStops() {
        PlayerState state = new PlayerState();
        assertTrue(scan(state, 0, 0), "the first scan always runs");
        assertEquals(0, state.refinePass());
        assertFalse(scan(state, 0, 0), "scan-ticks must pass first");
        for (int pass = 1; pass <= PASSES; pass++) {
            ticks(state, 2);
            assertTrue(scan(state, 0, 0), "refinement pass " + pass);
            assertEquals(pass, state.refinePass());
        }
        for (int i = 0; i < 5; i++) {
            ticks(state, 2);
            assertFalse(scan(state, 0, 0), "nothing more while idle");
        }
    }

    @Test
    void movingOrTurningStartsAFreshPattern() {
        PlayerState state = new PlayerState();
        scan(state, 0, 0);
        ticks(state, 2);
        scan(state, 0, 0);
        assertEquals(1, state.refinePass());
        ticks(state, 2);
        assertTrue(scan(state, 5, 0), "a move past the threshold scans");
        assertEquals(0, state.refinePass(), "and starts over with the plain pattern");
        ticks(state, 2);
        assertTrue(scan(state, 5, 45), "a turn past the threshold scans");
        assertEquals(0, state.refinePass());
    }

    @Test
    void smallMovementsDoNotCountAsMoving() {
        PlayerState state = new PlayerState();
        scan(state, 0, 0);
        for (int pass = 1; pass <= PASSES; pass++) {
            ticks(state, 2);
            scan(state, 0.1 * pass, 1.0F * pass);
        }
        ticks(state, 2);
        assertFalse(scan(state, 0.4, 4.0F), "drift below the thresholds keeps the player idle");
    }

    @Test
    void pendingWorkKeepsScansComingWithoutUsingPasses() {
        PlayerState state = new PlayerState();
        scan(state, 0, 0);
        state.advanceRays(100, 1_000);
        state.setPendingDeferredReveals(true);
        ticks(state, 2);
        assertTrue(scan(state, 0, 0));
        assertEquals(0, state.refinePass(), "a continued pass is not a refinement pass");
        assertEquals(100, state.rayProgress(), "and keeps its progress");
    }

    @Test
    void breakingABlockRestartsRefinement() {
        PlayerState state = new PlayerState();
        scan(state, 0, 0);
        for (int pass = 1; pass <= PASSES; pass++) {
            ticks(state, 2);
            scan(state, 0, 0);
        }
        ticks(state, 2);
        assertFalse(scan(state, 0, 0));
        state.restartRefinement();
        assertTrue(scan(state, 0, 0));
        assertEquals(1, state.refinePass());
    }

    @Test
    void frontierBudgetIsEnforced() {
        PlayerState state = new PlayerState();
        state.ensureFrontierBudget(3);
        state.ensureFrontierBudget(100);
        assertTrue(state.takeFrontierRay());
        assertTrue(state.takeFrontierRay());
        assertTrue(state.takeFrontierRay());
        assertFalse(state.takeFrontierRay(), "the first budget set for the period holds");
        state.offerFrontierSeed(1L, -1.0);
        assertFalse(state.hasFrontier(), "no rays left means nothing to do");
        assertEquals(3, state.frontierRaysUsed());
    }

    @Test
    void frontierHandsOutCellsNearestTheCrosshairFirstAndOnlyOnce() {
        PlayerState state = new PlayerState();
        state.ensureFrontierBudget(10);
        state.offerFrontierSeed(10L, -0.5);
        state.offerFrontierSeed(20L, -0.9);
        state.offerFrontierSeed(30L, -0.1);
        state.offerFrontierSeed(20L, -1.0);
        assertEquals(20L, state.pollFrontier());
        assertEquals(10L, state.pollFrontier());
        assertEquals(30L, state.pollFrontier());
        assertFalse(state.hasFrontier());
        assertFalse(state.markFrontierSeen(10L));
        state.forgetFrontierSeen(10L);
        assertTrue(state.markFrontierSeen(10L), "a cut-short cell can be tested again");
    }

    @Test
    void movementClearsTheFrontier() {
        PlayerState state = new PlayerState();
        scan(state, 0, 0);
        state.ensureFrontierBudget(10);
        state.offerFrontierSeed(1L, -1.0);
        ticks(state, 2);
        scan(state, 9, 0);
        assertFalse(state.hasFrontier());
        assertTrue(state.markFrontierSeen(1L));
    }

    @Test
    void tracksWhichEntitiesTheClientHas() {
        PlayerState state = new PlayerState();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        state.noteClientSpawn(5, first);
        assertTrue(state.isOnClient(first));
        state.noteClientSpawn(5, second);
        assertFalse(state.isOnClient(first), "a reused id replaces the old entity");
        assertTrue(state.isOnClient(second));
        state.noteClientRemove(5);
        assertFalse(state.isOnClient(second));

        state.noteClientSpawn(6, first);
        state.hideEntity(6, first, null);
        assertFalse(state.isOnClient(first), "hiding takes it off the client");
        assertTrue(state.isEntityHidden(6));
        state.unhideEntity(6);
        assertFalse(state.isEntityHidden(6));

        state.noteClientSpawn(7, second);
        state.forgetClientEntities();
        assertFalse(state.isOnClient(second), "respawn and login drop everything");
    }

    @Test
    void removeOfAnOldIdDoesNotForgetANewerEntity() {
        PlayerState state = new PlayerState();
        UUID uuid = UUID.randomUUID();
        state.noteClientSpawn(1, uuid);
        state.noteClientSpawn(2, uuid);
        state.noteClientRemove(1);
        assertTrue(state.isOnClient(uuid));
    }

    @Test
    void earnedCellsAreFoundPerChunkAndForgottenOutsideTheRadius() {
        PlayerState state = new PlayerState();
        int version = state.cellVersion();
        CellPos near = new CellPos(2, 0, 0, 8);
        CellPos ring = new CellPos(6, 0, 0, 8);
        CellPos far = new CellPos(40, 0, 0, 8);
        state.markCellVisible(near);
        state.markCellVisible(ring);
        state.markCellVisible(far);
        state.markCellVisible(near);
        assertEquals(version + 3, state.cellVersion(), "only new cells bump the version");
        assertTrue(state.hasVisibleCellsInChunk(1, 0));
        assertTrue(state.hasVisibleCellsInChunk(2, 0), "a cell can reach into the next chunk");
        assertFalse(state.hasVisibleCellsInChunk(5, 0));

        Set<CellPos> forgotten = state.forgetOutside(0, 0, 2);
        assertEquals(Set.of(ring), forgotten, "only the first ring outside is handed back");
        assertTrue(state.isCellVisible(near));
        assertFalse(state.isCellVisible(ring));
        assertFalse(state.isCellVisible(far));
    }

    @Test
    void forgetColumnDropsOnlyThatColumn() {
        PlayerState state = new PlayerState();
        CellPos a = new CellPos(0, 0, 0, 8);
        CellPos b = new CellPos(4, 0, 0, 8);
        state.markCellVisible(a);
        state.markCellVisible(b);
        state.forgetColumn(0, 0);
        assertFalse(state.isCellVisible(a));
        assertTrue(state.isCellVisible(b));
    }

    @Test
    void entityScanRespectsItsInterval() {
        PlayerState state = new PlayerState();
        assertTrue(state.shouldRefreshEntityScan(2));
        assertFalse(state.shouldRefreshEntityScan(2));
        ticks(state, 2);
        assertTrue(state.shouldRefreshEntityScan(2));
    }

    @Test
    void bypassCombinesSpectatorAndPermission() {
        PlayerState state = new PlayerState();
        state.setSpectator(true);
        assertTrue(state.bypassed());
        state.setPermissionBypass(true);
        state.setSpectator(false);
        assertTrue(state.bypassed());
        state.setPermissionBypass(false);
        assertFalse(state.bypassed());
    }
}
