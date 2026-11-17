package com.heonezen.raytrace.util;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CellPosTest {

    @Test
    void packRoundTripsEveryCoordinateRange() {
        int[] xs = {0, 1, -1, 33_554_431, -33_554_432, 1_875_000, -1_875_000};
        int[] ys = {0, 1, -1, 2_047, -2_048, -8, 40};
        for (int x : xs) {
            for (int y : ys) {
                for (int z : xs) {
                    long key = CellPos.pack(x, y, z);
                    assertEquals(x, CellPos.unpackX(key), "x of " + x + "," + y + "," + z);
                    assertEquals(y, CellPos.unpackY(key), "y of " + x + "," + y + "," + z);
                    assertEquals(z, CellPos.unpackZ(key), "z of " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void packRoundTripsRandomCells() {
        SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < 200_000; i++) {
            int x = random.nextInt(-33_554_432, 33_554_432);
            int y = random.nextInt(-2_048, 2_048);
            int z = random.nextInt(-33_554_432, 33_554_432);
            long key = CellPos.pack(x, y, z);
            assertEquals(x, CellPos.unpackX(key));
            assertEquals(y, CellPos.unpackY(key));
            assertEquals(z, CellPos.unpackZ(key));
        }
    }

    @Test
    void distinctCellsGetDistinctKeys() {
        assertNotEquals(CellPos.pack(1, 0, 0), CellPos.pack(0, 0, 1));
        assertNotEquals(CellPos.pack(0, 1, 0), CellPos.pack(0, 0, 1));
        assertNotEquals(CellPos.pack(-1, 0, 0), CellPos.pack(0, 0, -1));
    }

    @Test
    void ofUsesFloorDivisionForNegativeBlocks() {
        assertEquals(new CellPos(-1, -1, -1, 8), CellPos.of(-1, -1, -1, 8));
        assertEquals(new CellPos(-1, 0, 0, 8), CellPos.of(-8, 0, 7, 8));
        assertEquals(new CellPos(-2, 0, 0, 8), CellPos.of(-9, 0, 0, 8));
        assertEquals(new CellPos(3, -8, 0, 5), CellPos.of(15, -40, 4, 5));
    }

    @Test
    void blockAndChunkCorners() {
        CellPos cell = CellPos.of(-9, 70, 33, 8);
        assertEquals(-16, cell.minBlockX());
        assertEquals(64, cell.minBlockY());
        assertEquals(32, cell.minBlockZ());
        assertEquals(-1, cell.chunkX());
        assertEquals(2, cell.chunkZ());
    }

    @Test
    void cellsOfDifferentSizesNeverCompareEqual() {
        assertNotEquals(new CellPos(1, 1, 1, 8), new CellPos(1, 1, 1, 16));
    }
}
