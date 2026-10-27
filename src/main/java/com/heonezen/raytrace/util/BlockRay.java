package com.heonezen.raytrace.util;

import org.bukkit.Chunk;
import org.bukkit.World;

// Shared DDA block walk for line-of-sight checks.
public final class BlockRay {
    // A block crossed for less than this distance is treated as a corner clip.
    private static final double CORNER_CLIP_SIZE = 0.12;
    private BlockRay() {
    }
    // Counts solid blocks strictly between two points, stopping after {@code stopAfter}.
    public static int countOccluding(World world, double ex, double ey, double ez, double tx, double ty, double tz, int stopAfter) {
        return countOccluding(world, ex, ey, ez, tx, ty, tz, stopAfter, null, 0, false, 0);
    }
    /**
     * Same as above, but lets a ray pass up to {@code allowedCornerSkips} thin corner clips.
     * This makes partially visible entities appear smoothly without opening a path through
     * a wall running along a corner.
     */
    public static int countOccluding(World world, double ex, double ey, double ez, double tx, double ty, double tz, int stopAfter, int allowedCornerSkips) {
        return countOccluding(world, ex, ey, ez, tx, ty, tz, stopAfter, null, 0, false, allowedCornerSkips);
    }

    // Netty-safe variant of {@link #countOccluding}.
    public static int countOccludingNms(World world, double ex, double ey, double ez, double tx, double ty, double tz, int stopAfter) {
        return countOccluding(world, ex, ey, ez, tx, ty, tz, stopAfter, null, 0, true, 0);
    }

    // Netty-safe variant with corner clipping.
    public static int countOccludingNms(World world, double ex, double ey, double ez, double tx, double ty, double tz, int stopAfter, int allowedCornerSkips) {
        return countOccluding(world, ex, ey, ez, tx, ty, tz, stopAfter, null, 0, true, allowedCornerSkips);
    }

    // Netty-safe cell-reporting variant with corner clipping.
    public static int countOccludingNms(World world, double ex, double ey, double ez, double tx, double ty, double tz, int stopAfter, CellVisitor visitor, int gridSize, int allowedCornerSkips) {
        return countOccluding(world, ex, ey, ez, tx, ty, tz, stopAfter, visitor, gridSize, true, allowedCornerSkips);
    }

    /**
     * Fastest safe variant for the engine. {@code packetThread} callers (Netty) must never touch
     * Bukkit, so they only use direct server reads; region-thread callers use the same direct
     * reads when they are available and fall back to Bukkit otherwise.
     */
    public static int countOccludingAuto(World world, double ex, double ey, double ez,
                                         double tx, double ty, double tz, int stopAfter,
                                         CellVisitor visitor, int gridSize, int allowedCornerSkips,
                                         boolean packetThread) {
        boolean nms = packetThread || NmsBlockReader.available();
        return countOccluding(world, ex, ey, ez, tx, ty, tz, stopAfter, visitor, gridSize, nms, allowedCornerSkips);
    }

    // Counts solid blocks and reports every grid cell the line passes through.
    public static int countOccluding(World world, double ex, double ey, double ez, double tx, double ty, double tz, int stopAfter, CellVisitor visitor, int gridSize) {
        return countOccluding(world, ex, ey, ez, tx, ty, tz, stopAfter, visitor, gridSize, false, 0);
    }

    // Cell-reporting variant with corner clipping.
    public static int countOccluding(World world, double ex, double ey, double ez, double tx, double ty, double tz, int stopAfter, CellVisitor visitor, int gridSize, int allowedCornerSkips) {
        return countOccluding(world, ex, ey, ez, tx, ty, tz, stopAfter, visitor, gridSize, false, allowedCornerSkips);
    }

    private static int countOccluding(World world, double ex, double ey, double ez, double tx, double ty, double tz, int stopAfter, CellVisitor visitor, int gridSize, boolean nmsSafe, int allowedCornerSkips) {
        if (stopAfter <= 0) {
            return 0;
        }
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0e-6) {
            return 0;
        }
        dx /= distance;
        dy /= distance;
        dz /= distance;

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        int x = floor(ex);
        int y = floor(ey);
        int z = floor(ez);

        int stepX = signum(dx);
        int stepY = signum(dy);
        int stepZ = signum(dz);

        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);

        double tMaxX = stepX > 0 ? (x + 1 - ex) / dx : (stepX < 0 ? (ex - x) / -dx : Double.MAX_VALUE);
        double tMaxY = stepY > 0 ? (y + 1 - ey) / dy : (stepY < 0 ? (ey - y) / -dy : Double.MAX_VALUE);
        double tMaxZ = stepZ > 0 ? (z + 1 - ez) / dz : (stepZ < 0 ? (ez - z) / -dz : Double.MAX_VALUE);

        int count = 0;
        boolean firstVoxel = true;
        Chunk chunk = null;
        Object nmsChunk = null;
        // Resolved once per ray: the chunk source lookup itself is not free.
        Object source = nmsSafe ? NmsBlockReader.chunkSource(world) : null;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        int lastCellX = Integer.MIN_VALUE;
        int lastCellY = Integer.MIN_VALUE;
        int lastCellZ = Integer.MIN_VALUE;
        double entryT = 0.0;
        int entryAxis = -1;
        int cornerRun = 0;

        while (true) {
            double exitT = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            int exitAxis = tMaxX <= tMaxY && tMaxX <= tMaxZ ? 0 : (tMaxY <= tMaxZ ? 1 : 2);
            boolean cornerClip = false;
            if (allowedCornerSkips > 0 && !firstVoxel && exitT < distance) {
                double segment = exitT - entryT;
                if (segment < CORNER_CLIP_SIZE) {
                    if (cornerRun < allowedCornerSkips) {
                        cornerClip = true;
                    }
                    cornerRun++;
                } else {
                    cornerRun = 0;
                }
            }

            if (!firstVoxel && y >= minY && y < maxY) {
                int cx = x >> 4;
                int cz = z >> 4;
                if (cx != chunkX || cz != chunkZ) {
                    chunkX = cx;
                    chunkZ = cz;
                    if (nmsSafe) {
                        nmsChunk = NmsBlockReader.chunkAt(source, cx, cz);
                    } else {
                        chunk = world.isChunkLoaded(cx, cz) ? world.getChunkAt(cx, cz) : null;
                    }
                }
                // A thin segment is only a corner clip if the corner itself is open. If
                // another solid block shares that edge, the line is grazing a wall seam
                // and must not pass through the concave corner.
                if (cornerClip && !isOpenCorner(world, nmsSafe, source, x, y, z, entryAxis, exitAxis,
                        stepX, stepY, stepZ, chunk, nmsChunk, chunkX, chunkZ)) {
                    cornerClip = false;
                }
                boolean occluding;
                if (nmsSafe) {
                    occluding = NmsBlockReader.isOccluding(nmsChunk, x & 15, y, z & 15);
                } else {
                    occluding = chunk != null && chunk.getBlock(x & 15, y, z & 15).getType().isOccluding();
                }
                if (occluding && !cornerClip) {
                    count++;
                    if (count >= stopAfter) {
                        return count;
                    }
                }
            }
            if (visitor != null && gridSize > 0) {
                int cellX = Math.floorDiv(x, gridSize);
                int cellY = Math.floorDiv(y, gridSize);
                int cellZ = Math.floorDiv(z, gridSize);
                if (cellX != lastCellX || cellY != lastCellY || cellZ != lastCellZ) {
                    lastCellX = cellX;
                    lastCellY = cellY;
                    lastCellZ = cellZ;
                    if (!visitor.visit(cellX, cellY, cellZ)) {
                        return count;
                    }
                }
            }
            firstVoxel = false;
            double t;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
                entryAxis = 0;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
                entryAxis = 1;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                entryAxis = 2;
            }
            entryT = t;
            if (t > distance) {
                return count;
            }
        }
    }
    /**
     * Checks the two blocks diagonally opposite the current voxel around the edge the ray
     * is crossing. A single block corner is open; a wall corner has a solid diagonal
     * neighbour and is not.
     */
    private static boolean isOpenCorner(World world, boolean nmsSafe, Object source,
                                        int x, int y, int z, int entryAxis, int exitAxis,
                                        int stepX, int stepY, int stepZ,
                                        Chunk chunk, Object nmsChunk, int chunkX, int chunkZ) {
        if (entryAxis < 0 || entryAxis == exitAxis) {
            return false;
        }
        int[] position = {x, y, z};
        int[] steps = {stepX, stepY, stepZ};
        if (steps[entryAxis] == 0 || steps[exitAxis] == 0) {
            return false;
        }

        int[] first = position.clone();
        first[entryAxis] -= steps[entryAxis];
        first[exitAxis] += steps[exitAxis];
        int[] second = position.clone();
        second[entryAxis] += steps[entryAxis];
        second[exitAxis] -= steps[exitAxis];
        return !isOccludingAt(world, nmsSafe, source, first[0], first[1], first[2],
                chunk, nmsChunk, chunkX, chunkZ)
                && !isOccludingAt(world, nmsSafe, source, second[0], second[1], second[2],
                chunk, nmsChunk, chunkX, chunkZ);
    }
    private static boolean isOccludingAt(World world, boolean nmsSafe, Object source, int x, int y, int z, Chunk chunk, Object nmsChunk, int chunkX, int chunkZ) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        if (cx == chunkX && cz == chunkZ) {
            return nmsSafe ? NmsBlockReader.isOccluding(nmsChunk, x & 15, y, z & 15) : chunk != null && chunk.getBlock(x & 15, y, z & 15).getType().isOccluding();
        }
        if (nmsSafe) {
            return NmsBlockReader.isOccluding(NmsBlockReader.chunkAt(source, cx, cz), x & 15, y, z & 15);
        }
        if (!world.isChunkLoaded(cx, cz)) {
            return false;
        }
        return world.getChunkAt(cx, cz).getBlock(x & 15, y, z & 15).getType().isOccluding();
    }
    public static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
    // Receives the grid cells a line passes through. Returning false stops the walk.
    public interface CellVisitor {
        boolean visit(int cellX, int cellY, int cellZ);
    }
    public static int signum(double value) {
        return value > 0.0 ? 1 : (value < 0.0 ? -1 : 0);
    }
}
