package com.heonezen.raytrace.config;

// The two independent grids used by the scanner. The *PerTick limits are shared by all players a server thread ticks.
public record GridSettings(BlockGrid blocks, EntityGrid entities) {

    // Terrain grid and its scan limits.
    public record BlockGrid(int size, int occlusionGraceBlocks, long scanTicks, double moveThresholdBlocks, double yawThresholdDegrees, double pitchThresholdDegrees, int maxStepsPerScan, int targetTotalRays, int maxRevealedBlocksPerScan, long maxScanMicros, long maxScanMicrosPerTick) {}

    // Entity silhouette grid, scan timing and its limits.
    public record EntityGrid(int size, long scanIntervalTicks, double scanRadiusBlocks, int occlusionBlocks, long maxScanMicros, long maxScanMicrosPerTick) {}
}
