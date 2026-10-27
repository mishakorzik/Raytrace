package com.heonezen.raytrace.diagnostics;

import com.heonezen.raytrace.protocol.NativeProtocol;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that block-change packets are copied per player without touching the shared original.
 * Needs a live server because the packet and block-state classes only exist there.
 */
public final class NativePacketSelfTest {

    private final List<Result> results = new ArrayList<>();

    private NativePacketSelfTest() {}

    public record Result(String name, boolean passed, String detail) {}

    public static List<Result> run() {
        NativePacketSelfTest test = new NativePacketSelfTest();
        try {
            test.testBlockUpdateCopy();
        } catch (Throwable throwable) {
            test.results.add(new Result("the single block-update copy could not be checked", false, String.valueOf(throwable)));
        }
        try {
            test.testSectionUpdateCopy();
        } catch (Throwable throwable) {
            test.results.add(new Result("the batched block-update copy could not be checked", false, String.valueOf(throwable)));
        }
        try {
            test.testRemoveEntityIds();
        } catch (Throwable throwable) {
            test.results.add(new Result("the remove-entities id lookup could not be checked", false, String.valueOf(throwable)));
        }
        return List.copyOf(test.results);
    }

    private void testBlockUpdateCopy() throws ReflectiveOperationException {
        Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
        Object position = blockPosClass.getConstructor(int.class, int.class, int.class).newInstance(1, 2, 3);
        Object stone = blockState("STONE");
        Object deepslate = blockState("DEEPSLATE");

        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket");
        Object original = packetClass.getConstructor(blockPosClass, Class.forName("net.minecraft.world.level.block.state.BlockState")).newInstance(position, stone);

        Object copy = NativeProtocol.copyBlockUpdate(original, deepslate);
        boolean passed = copy != null && copy != original
                && NativeProtocol.get(original, "blockState") == stone
                && NativeProtocol.get(copy, "blockState") == deepslate
                && NativeProtocol.get(copy, "pos") == position;
        results.add(new Result("single block update is copied per player and the original is untouched", passed, passed ? "" : "original=" + NativeProtocol.get(original, "blockState") + ", copy=" + (copy == null ? "null" : NativeProtocol.get(copy, "blockState"))));
    }

    private void testSectionUpdateCopy() throws ReflectiveOperationException {
        Class<?> sectionPosClass = Class.forName("net.minecraft.core.SectionPos");
        Object sectionPos = sectionPosClass.getMethod("of", int.class, int.class, int.class).invoke(null, 0, 0, 0);
        // Use a full section's worth of distinct positions, not just two: the copy constructor
        // rebuilds the position and state arrays from a map, and this checks that the two arrays
        // stay aligned in the map's iteration order.
        short[] positions = new short[16];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = (short) ((i << 8) | ((15 - i) << 4) | (i & 15));
        }
        Object stone = blockState("STONE");
        Object deepslate = blockState("DEEPSLATE");
        Object states = Array.newInstance(Class.forName("net.minecraft.world.level.block.state.BlockState"), positions.length);
        for (int i = 0; i < positions.length; i++) {
            Array.set(states, i, stone);
        }

        Class<?> shortSetClass = Class.forName("it.unimi.dsi.fastutil.shorts.ShortSet");
        Object shortSet = Class.forName("it.unimi.dsi.fastutil.shorts.ShortOpenHashSet").getConstructor(short[].class).newInstance((Object) positions);
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket");
        Object original = packetClass.getConstructor(sectionPosClass, shortSetClass, states.getClass()).newInstance(sectionPos, shortSet, states);

        Object[] replacements = new Object[positions.length];
        for (int i = 0; i < positions.length; i += 2) {
            replacements[i] = deepslate;
        }
        Object copy = NativeProtocol.copySectionBlocksUpdate(original, positions, (Object[]) states, replacements);

        Map<Short, Object> copyStates = statesByPosition(copy);
        boolean copyIndependent = copy != null && copy != original && copyStates.size() == positions.length && matchesPattern(copyStates, positions, deepslate, stone, 0);
        boolean originalUntouched = true;
        for (int i = 0; i < positions.length; i++) {
            if (Array.get(states, i) != stone) {
                originalUntouched = false;
                break;
            }
        }
        originalUntouched = originalUntouched && NativeProtocol.get(original, "states") == states;
        results.add(new Result("batched block update is copied per player and the original is untouched", copyIndependent && originalUntouched, copyIndependent && originalUntouched ? "" : "copy=" + copyStates + ", original=" + Arrays.toString((Object[]) states)));

        Object[] otherReplacements = new Object[positions.length];
        for (int i = 1; i < positions.length; i += 2) {
            otherReplacements[i] = deepslate;
        }
        Object otherCopy = NativeProtocol.copySectionBlocksUpdate(original, positions, (Object[]) states, otherReplacements);
        Map<Short, Object> otherStates = statesByPosition(otherCopy);
        boolean independent = otherCopy != null && otherCopy != copy && matchesPattern(copyStates, positions, deepslate, stone, 0) && matchesPattern(otherStates, positions, deepslate, stone, 1);
        results.add(new Result("two players receive independent batched copies", independent, independent ? "" : "first=" + copyStates + ", second=" + otherStates));
    }

    /** Checks every position against the pattern: deepslate for one parity, stone for the other. */
    private static boolean matchesPattern(Map<Short, Object> states, short[] positions, Object deepslate, Object stone, int deepslateParity) {
        for (int i = 0; i < positions.length; i++) {
            Object expected = (i & 1) == deepslateParity ? deepslate : stone;
            if (states.get(positions[i]) != expected) {
                return false;
            }
        }
        return true;
    }

    private void testRemoveEntityIds() throws ReflectiveOperationException {
        Class<?> packetClass = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
        int[] expected = {17, 42};
        Object packet = packetClass.getConstructor(int[].class)
                .newInstance((Object) expected);
        int[] actual = NativeProtocol.removeEntityIds(packet);
        boolean passed = Arrays.equals(actual, expected);
        results.add(new Result("remove-entities ids are readable on this server version", passed, passed ? "" : "expected=" + Arrays.toString(expected) + ", actual=" + Arrays.toString(actual)));
    }

    private static Map<Short, Object> statesByPosition(Object packet) {
        if (packet == null) {
            return Map.of();
        }
        short[] positions = (short[]) NativeProtocol.get(packet, "positions");
        Object[] states = (Object[]) NativeProtocol.get(packet, "states");
        Map<Short, Object> result = new HashMap<>();
        for (int i = 0; i < positions.length && i < states.length; i++) {
            result.put(positions[i], states[i]);
        }
        return result;
    }

    private static Object blockState(String name) throws ReflectiveOperationException {
        Object block = NativeProtocol.staticField("net.minecraft.world.level.block.Blocks", name);
        return block.getClass().getMethod("defaultBlockState").invoke(block);
    }
}
