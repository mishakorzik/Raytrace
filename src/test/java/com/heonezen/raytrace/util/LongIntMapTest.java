package com.heonezen.raytrace.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongIntMapTest {

    @Test
    void matchesHashMapUnderRandomPutsAndMins() {
        SplittableRandom random = new SplittableRandom(7);
        LongIntMap map = new LongIntMap(4);
        Map<Long, Integer> reference = new HashMap<>();
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 50_000; i++) {
                // A small key range forces collisions and updates of existing keys.
                long key = random.nextLong(-5_000, 5_000) * 0x9E3779B9L;
                int value = random.nextInt(-100, 100);
                if (random.nextBoolean()) {
                    map.put(key, value);
                    reference.put(key, value);
                } else {
                    map.putMin(key, value);
                    reference.merge(key, value, Math::min);
                }
            }
            assertEquals(reference.size(), map.size());
            for (Map.Entry<Long, Integer> entry : reference.entrySet()) {
                assertEquals(entry.getValue(), map.get(entry.getKey(), Integer.MIN_VALUE));
            }
            Map<Long, Integer> visited = new HashMap<>();
            map.forEach((key, value) -> assertEquals(null, visited.put(key, value), "key visited twice"));
            assertEquals(reference, visited);
            map.clear();
            reference.clear();
            assertTrue(map.isEmpty());
        }
    }

    @Test
    void missingKeysReturnTheDefault() {
        LongIntMap map = new LongIntMap(16);
        map.put(42L, 3);
        assertEquals(-1, map.get(43L, -1));
        assertEquals(3, map.get(42L, -1));
    }

    @Test
    void putMinKeepsTheSmallestValue() {
        LongIntMap map = new LongIntMap(16);
        map.putMin(1L, 5);
        map.putMin(1L, 7);
        map.putMin(1L, 2);
        assertEquals(2, map.get(1L, -1));
    }

    @Test
    void zeroAndNegativeKeysWork() {
        LongIntMap map = new LongIntMap(16);
        map.put(0L, 1);
        map.put(-1L, 2);
        map.put(Long.MIN_VALUE, 3);
        assertEquals(1, map.get(0L, -9));
        assertEquals(2, map.get(-1L, -9));
        assertEquals(3, map.get(Long.MIN_VALUE, -9));
    }

    @Test
    void clearAfterAHugeScanShrinksAndStillWorks() {
        LongIntMap map = new LongIntMap(16);
        for (long i = 0; i < 100_000; i++) {
            map.put(i, (int) i);
        }
        map.clear();
        assertTrue(map.isEmpty());
        assertFalse(map.get(5L, -1) >= 0);
        map.put(5L, 6);
        assertEquals(6, map.get(5L, -1));
    }
}
