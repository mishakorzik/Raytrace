package com.heonezen.raytrace.util;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentIntSetTest {

    @Test
    void matchesTreeSet() {
        SplittableRandom random = new SplittableRandom(3);
        ConcurrentIntSet set = new ConcurrentIntSet();
        TreeSet<Integer> reference = new TreeSet<>();
        for (int i = 0; i < 20_000; i++) {
            int value = random.nextInt(-300, 300);
            if (random.nextBoolean()) {
                assertEquals(reference.add(value), set.add(value));
            } else {
                assertEquals(reference.remove(value), set.remove(value));
            }
            int probe = random.nextInt(-300, 300);
            assertEquals(reference.contains(probe), set.contains(probe));
            assertEquals(reference.isEmpty(), set.isEmpty());
        }
        set.clear();
        assertTrue(set.isEmpty());
        assertFalse(set.contains(0));
    }

    @Test
    void readersNeverSeeAPartialWrite() throws Exception {
        ConcurrentIntSet set = new ConcurrentIntSet();
        // These ids stay in the set the whole time; a reader must always find them.
        for (int stable = 0; stable < 50; stable++) {
            set.add(stable * 1_000);
        }
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> failure = new AtomicReference<>();
        Thread[] readers = new Thread[4];
        for (int r = 0; r < readers.length; r++) {
            readers[r] = new Thread(() -> {
                while (!done.get()) {
                    for (int stable = 0; stable < 50; stable++) {
                        if (!set.contains(stable * 1_000)) {
                            failure.set("lost " + stable * 1_000);
                        }
                    }
                }
            });
            readers[r].start();
        }
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < 100_000; i++) {
            int value = random.nextInt(1, 999) + 1_000 * random.nextInt(0, 50);
            if (random.nextBoolean()) {
                set.add(value);
            } else {
                set.remove(value);
            }
        }
        done.set(true);
        for (Thread reader : readers) {
            reader.join();
        }
        assertNull(failure.get());
    }
}
