package com.heonezen.raytrace.util;

/**
 * A grid cell used as the reveal granularity. The size is stored per instance, so cells from
 * before and after a config reload never compare equal.
 */
public record CellPos(int cellX, int cellY, int cellZ, int size) {
    public static CellPos of(int blockX, int blockY, int blockZ, int size) {
        return new CellPos(Math.floorDiv(blockX, size), Math.floorDiv(blockY, size), Math.floorDiv(blockZ, size), size);
    }
    public int minBlockX() {
        return cellX * size;
    }
    public int minBlockY() {
        return cellY * size;
    }
    public int minBlockZ() {
        return cellZ * size;
    }
    public int chunkX() {
        return Math.floorDiv(minBlockX(), 16);
    }
    public int chunkZ() {
        return Math.floorDiv(minBlockZ(), 16);
    }
    /**
     * Packs cell coordinates into one long (26 bits X, 26 bits Z, 12 bits Y), so the per-scan
     * maps need no key objects. Y is offset so negative world heights stay positive.
     */
    public static long pack(int cellX, int cellY, int cellZ) {
        return ((long) (cellX & 0x3FFFFFF) << 38) | ((long) (cellZ & 0x3FFFFFF) << 12) | ((cellY + 2048) & 0xFFFL);
    }
    public static int unpackX(long key) {
        return (int) (key >> 38);
    }
    public static int unpackZ(long key) {
        return (int) ((key << 26) >> 38);
    }
    public static int unpackY(long key) {
        return (int) (key & 0xFFFL) - 2048;
    }
    // The packed key of this cell.
    public long packed() {
        return pack(cellX, cellY, cellZ);
    }
}
