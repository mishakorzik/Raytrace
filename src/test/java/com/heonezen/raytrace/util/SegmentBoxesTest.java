package com.heonezen.raytrace.util;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The segment/box test behind stairs, slabs and fences, against dense sampling along the segment.
class SegmentBoxesTest {

    private static final int BX = 10;
    private static final int BY = 64;
    private static final int BZ = -7;

    @Test
    void agreesWithSamplingOnRandomBoxesAndSegments() {
        SplittableRandom random = new SplittableRandom(11);
        int hits = 0;
        int misses = 0;
        for (int i = 0; i < 5_000; i++) {
            double[] box = randomBox(random);
            // A line through a point near the box, so hits and near misses are both common.
            double px = BX + random.nextDouble(box[0] - 0.4, box[3] + 0.4);
            double py = BY + random.nextDouble(box[1] - 0.4, box[4] + 0.4);
            double pz = BZ + random.nextDouble(box[2] - 0.4, box[5] + 0.4);
            double dx = random.nextDouble(-1, 1);
            double dy = random.nextDouble(-1, 1);
            double dz = random.nextDouble(-1, 1);
            double before = random.nextDouble(-1, 3);
            double after = random.nextDouble(-1, 3);
            double sx = px - dx * before;
            double sy = py - dy * before;
            double sz = pz - dz * before;
            double tx = px + dx * after;
            double ty = py + dy * after;
            double tz = pz + dz * after;
            boolean hit = NmsBlockReader.segmentHitsBoxes(box, BX, BY, BZ, sx, sy, sz, tx, ty, tz);
            if (hit) {
                hits++;
                assertTrue(sampled(box, 1.0e-3, sx, sy, sz, tx, ty, tz), "reported hit, but no sample is near the box");
            } else {
                misses++;
                assertFalse(sampled(box, -1.0e-3, sx, sy, sz, tx, ty, tz), "reported miss, but a sample lies inside the box");
            }
        }
        // Both outcomes must actually be exercised.
        assertTrue(hits > 500 && misses > 500, hits + " hits, " + misses + " misses");
    }

    @Test
    void stairNotchIsOpenAndStepIsSolid() {
        double[] stair = {0, 0, 0, 1, 0.5, 1, 0, 0.5, 0.5, 1, 1, 1};
        assertFalse(NmsBlockReader.segmentHitsBoxes(stair, BX, BY, BZ, BX - 5, BY + 0.75, BZ + 0.25, BX + 5, BY + 0.75, BZ + 0.25));
        assertTrue(NmsBlockReader.segmentHitsBoxes(stair, BX, BY, BZ, BX - 5, BY + 0.75, BZ + 0.75, BX + 5, BY + 0.75, BZ + 0.75));
    }

    @Test
    void axisAlignedSegmentsWork() {
        double[] slab = {0, 0, 0, 1, 0.5, 1};
        assertTrue(NmsBlockReader.segmentHitsBoxes(slab, BX, BY, BZ, BX + 0.5, BY + 9, BZ + 0.5, BX + 0.5, BY - 9, BZ + 0.5));
        assertFalse(NmsBlockReader.segmentHitsBoxes(slab, BX, BY, BZ, BX + 1.5, BY + 9, BZ + 0.5, BX + 1.5, BY - 9, BZ + 0.5));
    }

    private static double[] randomBox(SplittableRandom random) {
        double[] box = new double[6];
        for (int axis = 0; axis < 3; axis++) {
            double a = random.nextDouble();
            double b = random.nextDouble();
            box[axis] = Math.min(a, b);
            box[axis + 3] = Math.max(a, b) + 0.05;
        }
        return box;
    }

    // Whether a sample along the segment lies in the box grown (positive) or shrunk (negative) by margin.
    private static boolean sampled(double[] box, double margin, double sx, double sy, double sz, double tx, double ty, double tz) {
        int steps = 20_000;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double x = sx + (tx - sx) * t - BX;
            double y = sy + (ty - sy) * t - BY;
            double z = sz + (tz - sz) * t - BZ;
            if (x >= box[0] - margin && x <= box[3] + margin && y >= box[1] - margin && y <= box[4] + margin && z >= box[2] - margin && z <= box[5] + margin) {
                return true;
            }
        }
        return false;
    }
}
