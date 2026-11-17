package com.heonezen.raytrace.protocol;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeProtocolTest {

    // Shaped like the server's packets: private final fields, a superclass field, getters.
    static class Base {
        private final int inherited;

        Base(int inherited) {
            this.inherited = inherited;
        }
    }

    static final class FakePacket extends Base {
        private final int id;
        private final double x;
        private final byte[] buffer;
        private final List<Object> blockEntitiesData;

        FakePacket(int id, double x, byte[] buffer, List<Object> blockEntitiesData) {
            super(99);
            this.id = id;
            this.x = x;
            this.buffer = buffer;
            this.blockEntitiesData = blockEntitiesData;
        }

        public double getX() {
            return x;
        }
    }

    static final class NoIdPacket {
        private final String name = "none";
    }

    @Test
    void readsEntityIdsUnboxed() {
        assertEquals(42, NativeProtocol.readEntityId(new FakePacket(42, 0, null, null), "entityId", "id"));
        assertEquals(NativeProtocol.NO_ENTITY_ID, NativeProtocol.readEntityId(new NoIdPacket(), "id", "entityId"));
    }

    @Test
    void readsNumbersFromGettersAndFields() {
        FakePacket packet = new FakePacket(1, 2.5, null, null);
        assertEquals(2.5, NativeProtocol.readNumber(packet, "getX").doubleValue());
        assertEquals(1, NativeProtocol.readNumber(packet, "missing", "id").intValue());
        assertNull(NativeProtocol.readNumber(packet, "missing"));
    }

    @Test
    void readsAndWritesPrivateFinalFieldsIncludingInheritedOnes() {
        FakePacket packet = new FakePacket(1, 0, new byte[]{1}, null);
        assertEquals(99, NativeProtocol.get(packet, "inherited"));
        NativeProtocol.set(packet, "buffer", new byte[]{7});
        assertArrayEquals(new byte[]{7}, (byte[]) NativeProtocol.get(packet, "buffer"));
        assertThrows(IllegalStateException.class, () -> NativeProtocol.get(packet, "nope"));
        assertThrows(IllegalStateException.class, () -> NativeProtocol.get(packet, "nope"), "a cached miss still throws");
    }

    @Test
    void firstNonNullField() {
        FakePacket packet = new FakePacket(1, 0, null, List.of("a"));
        assertEquals(List.of("a"), NativeProtocol.readFirstField(packet, "buffer", "blockEntitiesData"));
        assertNull(NativeProtocol.readFirstField(packet, "buffer", "missing"));
    }

    @Test
    void shallowCopyLeavesTheSharedOriginalUntouched() throws Exception {
        Method shallowCopy = NativeProtocol.class.getDeclaredMethod("shallowCopy", Object.class);
        shallowCopy.setAccessible(true);
        byte[] buffer = {1, 2, 3};
        List<Object> entities = List.of("chest");
        FakePacket original = new FakePacket(5, 1.0, buffer, entities);
        FakePacket copy = (FakePacket) shallowCopy.invoke(null, original);
        assertNotSame(original, copy);
        assertEquals(5, NativeProtocol.readEntityId(copy, "id"));
        assertEquals(99, NativeProtocol.get(copy, "inherited"));
        assertSame(entities, NativeProtocol.get(copy, "blockEntitiesData"));
        NativeProtocol.set(copy, "buffer", new byte[]{9});
        assertSame(buffer, NativeProtocol.get(original, "buffer"), "the original keeps its own data");
    }

    @Test
    void recordsAreNeverShallowCopied() throws Exception {
        record Sealed(int value) {}
        Method shallowCopy = NativeProtocol.class.getDeclaredMethod("shallowCopy", Object.class);
        shallowCopy.setAccessible(true);
        assertNull(shallowCopy.invoke(null, new Sealed(1)), "final record fields cannot be rewritten, so no copy");
    }
}
