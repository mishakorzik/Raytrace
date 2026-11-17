package com.heonezen.raytrace.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanBudgetTest {

    private static final long PER_TICK = 6_000_000L;

    @Test
    void neverHoldsMoreThanOneTick() throws Exception {
        ScanBudget budget = new ScanBudget();
        budget.available(PER_TICK);
        Thread.sleep(200);
        assertEquals(PER_TICK, budget.available(PER_TICK));
    }

    @Test
    void spendingTakesFromTheBucketAndItRefills() throws Exception {
        ScanBudget budget = new ScanBudget();
        budget.available(PER_TICK);
        Thread.sleep(60);
        long before = budget.available(PER_TICK);
        budget.spend(before);
        assertTrue(budget.available(PER_TICK) < PER_TICK / 10, "bucket should be near empty after spending it");
        Thread.sleep(60);
        assertTrue(budget.available(PER_TICK) > PER_TICK / 2, "bucket should refill with wall-clock time");
    }

    @Test
    void overspendingIsPaidBackLater() throws Exception {
        ScanBudget budget = new ScanBudget();
        budget.available(PER_TICK);
        Thread.sleep(60);
        budget.spend(budget.available(PER_TICK) + PER_TICK);
        assertTrue(budget.available(PER_TICK) < 0, "a scan that ran over leaves a debt");
    }

    @Test
    void eachThreadHasItsOwnBucket() throws Exception {
        ScanBudget budget = new ScanBudget();
        budget.available(PER_TICK);
        Thread.sleep(60);
        budget.spend(10 * PER_TICK);
        long[] other = new long[1];
        Thread thread = new Thread(() -> {
            budget.available(PER_TICK);
            try {
                Thread.sleep(60);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            other[0] = budget.available(PER_TICK);
        });
        thread.start();
        thread.join();
        assertEquals(PER_TICK, other[0]);
    }

    @Test
    void capsTotalUseAtTheConfiguredShare() throws Exception {
        ScanBudget budget = new ScanBudget();
        budget.available(PER_TICK);
        Thread.sleep(60);
        long used = 0;
        long started = System.nanoTime();
        // 100 "players" per tick for 10 ticks, each wanting 1 ms.
        for (int tick = 0; tick < 10; tick++) {
            long tickStart = System.nanoTime();
            for (int player = 0; player < 100; player++) {
                long available = budget.available(PER_TICK);
                if (available < 150_000L) {
                    continue;
                }
                long spend = Math.min(1_000_000L, available);
                long start = System.nanoTime();
                while (System.nanoTime() - start < spend) {
                    Thread.onSpinWait();
                }
                long took = System.nanoTime() - start;
                budget.spend(took);
                used += took;
            }
            long rest = 50_000_000L - (System.nanoTime() - tickStart);
            if (rest > 0) {
                Thread.sleep(rest / 1_000_000L, (int) (rest % 1_000_000L));
            }
        }
        double elapsedTicks = (System.nanoTime() - started) / 50_000_000.0;
        double perTick = used / elapsedTicks;
        assertTrue(perTick <= PER_TICK * 1.25, "used " + perTick / 1e6 + " ms per tick");
    }
}
