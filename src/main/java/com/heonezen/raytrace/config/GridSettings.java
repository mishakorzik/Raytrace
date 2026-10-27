package com.heonezen.raytrace.config;

/** The two independent grids used by the scanner. */
public record GridSettings(BlockGrid blocks, EntityGrid entities) {

    /** Terrain grid and its scan limits. */
    public record BlockGrid(
            int size,
            int occlusionGraceBlocks,
            long scanTicks,
            double moveThresholdBlocks,
            double yawThresholdDegrees,
            double pitchThresholdDegrees,
            int maxStepsPerScan,
            int targetTotalRays,
            int maxRevealedBlocksPerScan,
            long maxScanMicros
    ) {
    }

    /** Entity silhouette grid, scan timing and the entity-specific ray allowance. */
    public record EntityGrid(
            int size,
            long scanIntervalTicks,
            double scanRadiusBlocks,
            int occlusionBlocks
    ) {
    }
}
