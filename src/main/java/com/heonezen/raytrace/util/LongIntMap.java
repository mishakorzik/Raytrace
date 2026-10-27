package com.heonezen.raytrace.util;

import java.util.Arrays;

/**
 * Open-addressing {@code long -> int} map for the ray scans. It is single-threaded by design
 * (one instance per player, used only on that player's region thread) and never allocates per
 * entry, unlike a {@code HashMap<CellPos, Integer>} which allocated a key and a boxed value for
 * every cell a ray touched.
 */
public final class LongIntMap {
    /** Returned by {@link #get} for a missing key. */
    public static final int ABSENT = Integer.MIN_VALUE;
    /** Receives every entry of the map. */
    @FunctionalInterface
    public interface Visitor {
        void visit(long key, int value);
    }

    private long[] keys;
    private int[] values;
    private boolean[] used;
    private int size;
    private int mask;

    public LongIntMap() {
        this(256);
    }
    public LongIntMap(int expected) {
        int capacity = 16;
        while (capacity < expected * 2) {
            capacity <<= 1;
        }
        allocate(capacity);
    }
    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new int[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
        size = 0;
    }
    public int size() {
        return size;
    }
    public boolean isEmpty() {
        return size == 0;
    }
    /** Forgets every entry but keeps the allocated tables for reuse. */
    public void clear() {
        if (size == 0) {
            return;
        }
        Arrays.fill(used, false);
        size = 0;
        // A one-off huge scan must not pin a huge table for the rest of the session.
        if (keys.length > 65_536) {
            allocate(1024);
        }
    }
    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }
    public int get(long key) {
        int index = hash(key) & mask;
        while (used[index]) {
            if (keys[index] == key) {
                return values[index];
            }
            index = (index + 1) & mask;
        }
        return ABSENT;
    }
    public boolean containsKey(long key) {
        return get(key) != ABSENT;
    }
    public void put(long key, int value) {
        int index = hash(key) & mask;
        while (used[index]) {
            if (keys[index] == key) {
                values[index] = value;
                return;
            }
            index = (index + 1) & mask;
        }
        used[index] = true;
        keys[index] = key;
        values[index] = value;
        if (++size * 2 > keys.length) {
            grow();
        }
    }
    /** Stores {@code value} unless the key already holds a smaller or equal one. */
    public void putMin(long key, int value) {
        int index = hash(key) & mask;
        while (used[index]) {
            if (keys[index] == key) {
                if (value < values[index]) {
                    values[index] = value;
                }
                return;
            }
            index = (index + 1) & mask;
        }
        used[index] = true;
        keys[index] = key;
        values[index] = value;
        if (++size * 2 > keys.length) {
            grow();
        }
    }
    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        boolean[] oldUsed = used;
        allocate(oldKeys.length << 1);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }
    public void forEach(Visitor visitor) {
        long[] k = keys;
        int[] v = values;
        boolean[] u = used;
        for (int i = 0; i < k.length; i++) {
            if (u[i]) {
                visitor.visit(k[i], v[i]);
            }
        }
    }
}
