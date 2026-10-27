package com.heonezen.raytrace.util;

import java.util.Arrays;

/**
 * A small set of ints that is cheap to read from many threads and rarely written. Reads are a
 * binary search over an immutable sorted array (no boxing, no allocation); writes copy the
 * array under a lock. It is used for the hidden-entity ids, which the Netty threads test for
 * every outgoing entity packet while the region thread changes them a few times per second.
 */
public final class ConcurrentIntSet {

    private static final int[] EMPTY = new int[0];

    private volatile int[] data = EMPTY;

    public boolean contains(int value) {
        int[] snapshot = data;
        return snapshot.length != 0 && Arrays.binarySearch(snapshot, value) >= 0;
    }

    public boolean isEmpty() {
        return data.length == 0;
    }

    public int size() {
        return data.length;
    }

    /** @return true if the value was not present before */
    public synchronized boolean add(int value) {
        int[] current = data;
        int index = Arrays.binarySearch(current, value);
        if (index >= 0) {
            return false;
        }
        int insertAt = -index - 1;
        int[] next = new int[current.length + 1];
        System.arraycopy(current, 0, next, 0, insertAt);
        next[insertAt] = value;
        System.arraycopy(current, insertAt, next, insertAt + 1, current.length - insertAt);
        data = next;
        return true;
    }

    /** @return true if the value was present */
    public synchronized boolean remove(int value) {
        int[] current = data;
        int index = Arrays.binarySearch(current, value);
        if (index < 0) {
            return false;
        }
        if (current.length == 1) {
            data = EMPTY;
            return true;
        }
        int[] next = new int[current.length - 1];
        System.arraycopy(current, 0, next, 0, index);
        System.arraycopy(current, index + 1, next, index, current.length - index - 1);
        data = next;
        return true;
    }

    public synchronized void clear() {
        data = EMPTY;
    }

    /** An immutable copy of the current contents. */
    public int[] toArray() {
        return data.clone();
    }
}
