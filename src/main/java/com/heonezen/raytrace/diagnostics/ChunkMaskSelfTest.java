package com.heonezen.raytrace.diagnostics;

import com.heonezen.raytrace.protocol.NativeProtocol;
import com.heonezen.raytrace.util.NmsBlockReader;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Encodes a real chunk, masks it and decodes it with the server's own classes. Every block below
 * the ceiling must be fake, and every block above must be unchanged.
 */
public final class ChunkMaskSelfTest {

    private ChunkMaskSelfTest() {
    }

    /** The outcome of the check. */
    public record Result(boolean passed, String detail) {
    }

    /** Runs the check in the given world; the caller must own its region. */
    public static Result run(Object factory, World world) {
        if (NativeProtocol.chunkFormatProblem() != null) {
            return new Result(false, NativeProtocol.chunkFormatProblem());
        }
        if (NativeProtocol.palettedContainerFactoryRequired() && factory == null) {
            return new Result(false, "the server's chunk container factory is not available");
        }
        if (world == null) {
            return new Result(false, "no normal world to test in");
        }
        int minY = world.getMinHeight();
        int sectionCount = (world.getMaxHeight() - minY) / 16;
        try {
            Chunk chunk = world.getChunkAt(world.getSpawnLocation());
            Object levelChunk = nmsChunk(chunk);
            if (levelChunk == null) {
                return new Result(false, "the chunk could not be reached");
            }
            byte[] original = chunkSnapshot(levelChunk);
            if (original == null || original.length == 0) {
                return new Result(false, "the chunk could not be encoded");
            }
            Object fake = stoneState();
            if (fake == null) {
                return new Result(false, "the stone block state could not be reached");
            }
            if (!NmsBlockReader.isSensitiveBlockState(blockState("IRON_ORE"))) {
                return new Result(false, "a real iron-ore block state was not classified as sensitive");
            }
            if (!NmsBlockReader.isSensitiveBlockState(blockState("CHEST"))) {
                return new Result(false, "a real chest block state was not classified as sensitive");
            }
            if (NmsBlockReader.isSensitiveBlockState(blockState("DIRT"))) {
                return new Result(false, "a real dirt block state was classified as sensitive");
            }

            // Everything below the middle of the world is masked, everything above stays real.
            int ceiling = minY + (sectionCount / 2) * 16;
            int[] ceilings = new int[256];
            Arrays.fill(ceilings, ceiling);
            byte[] masked = NativeProtocol.maskChunkBuffer(factory, original, sectionCount, minY, ceilings, y -> fake);
            if (masked == null) {
                return new Result(false, "the masking did not produce a chunk");
            }

            Object readerBefore = buffer(original);
            Object readerAfter = buffer(masked);
            int maskedBlocks = 0;
            int changedAbove = 0;
            int realAbove = 0;
            for (int section = 0; section < sectionCount; section++) {
                int baseY = minY + section * 16;
                Object before = readSection(factory, readerBefore);
                Object after = readSection(factory, readerAfter);
                if (before == null || after == null) {
                    return new Result(false, "the masked chunk could not be read back");
                }
                Object statesBefore = invoke(before, "getStates");
                Object statesAfter = invoke(after, "getStates");
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            Object was = blockState(statesBefore, x, y, z);
                            Object now = blockState(statesAfter, x, y, z);
                            if (baseY + y < ceiling) {
                                maskedBlocks++;
                                if (now != fake) {
                                    return new Result(false, "a block below the ceiling is not the fake block (Y "
                                            + (baseY + y) + ")");
                                }
                            } else {
                                realAbove++;
                                if (now != was) {
                                    changedAbove++;
                                }
                            }
                        }
                    }
                }
            }
            if (maskedBlocks == 0) {
                return new Result(false, "no block was masked at all");
            }
            if (changedAbove > 0) {
                return new Result(false, changedAbove + " of " + realAbove
                        + " blocks above the ceiling were changed");
            }

            // The same snapshot, masked again with a region the viewer has already earned.
            // Those blocks must come back exactly as they were: this is what stops a chunk that
            // arrives after a reveal from re-faking the cells that reveal just opened.
            int earnedMinX = 4;
            int earnedMaxX = 11;
            int earnedMinZ = 4;
            int earnedMaxZ = 11;
            int earnedMinY = minY + 4;
            int earnedMaxY = Math.min(ceiling - 1, minY + 27);
            NativeProtocol.BlockVisibility earned = (x, y, z) -> x >= earnedMinX && x <= earnedMaxX
                    && z >= earnedMinZ && z <= earnedMaxZ && y >= earnedMinY && y <= earnedMaxY;
            byte[] partiallyMasked = NativeProtocol.maskChunkBuffer(factory, original, sectionCount, minY, ceilings,
                    y -> fake, 0, 0, earned);
            if (partiallyMasked == null) {
                return new Result(false, "the earned-aware masking did not produce a chunk");
            }
            Object earnedReader = buffer(partiallyMasked);
            Object originalReader = buffer(original);
            int earnedKept = 0;
            int earnedLost = 0;
            for (int section = 0; section < sectionCount; section++) {
                int baseY = minY + section * 16;
                Object before = readSection(factory, originalReader);
                Object after = readSection(factory, earnedReader);
                if (before == null || after == null) {
                    return new Result(false, "the earned-aware chunk could not be read back");
                }
                Object statesBefore = invoke(before, "getStates");
                Object statesAfter = invoke(after, "getStates");
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            int worldY = baseY + y;
                            if (worldY >= ceiling) {
                                continue;
                            }
                            Object was = blockState(statesBefore, x, y, z);
                            Object now = blockState(statesAfter, x, y, z);
                            if (earned.isVisible(x, worldY, z)) {
                                earnedKept++;
                                if (now != was) {
                                    earnedLost++;
                                }
                            } else if (now != fake) {
                                return new Result(false, "a hidden block below the ceiling was not masked (Y "
                                        + worldY + ")");
                            }
                        }
                    }
                }
            }
            if (earnedKept == 0 || earnedLost > 0) {
                return new Result(false, earnedLost + " of " + earnedKept
                        + " blocks the viewer had already earned were re-faked by the chunk mask");
            }

            // The soil-buffer path: blocks above the normal ceiling but below the hard cutoff
            // are left real unless the sensitive-state predicate says they are an ore/container.
            int hardCeiling = Math.min(world.getMaxHeight(), ceiling + 16);
            int[] oreCeilings = new int[256];
            Arrays.fill(oreCeilings, hardCeiling);
            byte[] protectedMasked = NativeProtocol.maskChunkBuffer(factory, original, sectionCount, minY,
                    ceilings, oreCeilings, y -> fake, 0, 0, null, state -> true);
            if (protectedMasked == null) {
                return new Result(false, "the soil-buffer masking did not produce a chunk");
            }
            Object protectedReader = buffer(protectedMasked);
            Object protectedOriginal = buffer(original);
            int protectedCount = 0;
            for (int section = 0; section < sectionCount; section++) {
                int baseY = minY + section * 16;
                Object before = readSection(factory, protectedOriginal);
                Object after = readSection(factory, protectedReader);
                if (before == null || after == null) {
                    return new Result(false, "the soil-buffer chunk could not be read back");
                }
                Object statesBefore = invoke(before, "getStates");
                Object statesAfter = invoke(after, "getStates");
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            int worldY = baseY + y;
                            Object was = blockState(statesBefore, x, y, z);
                            Object now = blockState(statesAfter, x, y, z);
                            if (worldY < hardCeiling) {
                                protectedCount++;
                                if (now != fake) {
                                    return new Result(false,
                                            "a sensitive block in the soil buffer was not masked (Y " + worldY + ")");
                                }
                            } else if (now != was) {
                                return new Result(false,
                                        "a block above the hard cutoff was changed (Y " + worldY + ")");
                            }
                        }
                    }
                }
            }
            if (protectedCount == 0) {
                return new Result(false, "the soil-buffer mask changed no blocks");
            }
            return new Result(true, maskedBlocks + " blocks masked, " + realAbove + " blocks above the ceiling intact, "
                    + earnedKept + " earned blocks preserved, " + protectedCount
                    + " sensitive-buffer blocks masked");
        } catch (Throwable throwable) {
            return new Result(false, String.valueOf(throwable));
        }
    }

    /** The server's own chunk object behind a Bukkit chunk. */
    private static Object nmsChunk(Chunk chunk) throws Exception {
        // CraftChunk keeps the server's level and the chunk coordinates, not the chunk itself,
        // so the chunk is asked for through the server's own accessor.
        Class<?> statusClass = Class.forName("net.minecraft.world.level.chunk.status.ChunkStatus");
        Object full = statusClass.getField("FULL").get(null);
        Object result = chunk.getClass().getMethod("getHandle", statusClass).invoke(chunk, full);
        return result;
    }

    /** The chunk snapshot exactly as the server sends it to a client. */
    private static byte[] chunkSnapshot(Object levelChunk) throws Exception {
        Class<?> dataClass = Class.forName("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData");
        Object data = dataClass.getConstructor(levelChunk.getClass()).newInstance(levelChunk);
        Object readBuffer = dataClass.getMethod("getReadBuffer").invoke(data);
        int length = (int) readBuffer.getClass().getMethod("readableBytes").invoke(readBuffer);
        byte[] bytes = new byte[length];
        readBuffer.getClass().getMethod("readBytes", byte[].class).invoke(readBuffer, bytes);
        return bytes;
    }

    /** The server's own stone block state. */
    private static Object stoneState() throws Exception {
        return blockState("STONE");
    }

    /** The server's own block state for a {@code Blocks} field. */
    private static Object blockState(String field) throws Exception {
        Class<?> blocks = Class.forName("net.minecraft.world.level.block.Blocks");
        Object block = blocks.getField(field).get(null);
        return block.getClass().getMethod("defaultBlockState").invoke(block);
    }

    /** A buffer over the given bytes, in the server's own format. */
    private static Object buffer(byte[] bytes) throws Exception {
        Class<?> bufferClass = Class.forName("net.minecraft.network.FriendlyByteBuf");
        return bufferClass.getConstructor(io.netty.buffer.ByteBuf.class)
                .newInstance(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
    }

    /** Reads the next section of a chunk snapshot. */
    private static Object readSection(Object factory, Object reader) throws Exception {
        Object section = NativeProtocol.newSectionForRead(factory);
        if (section == null) {
            return null;
        }
        Class<?> sectionClass = Class.forName("net.minecraft.world.level.chunk.LevelChunkSection");
        Class<?> bufferClass = Class.forName("net.minecraft.network.FriendlyByteBuf");
        sectionClass.getMethod("read", bufferClass).invoke(section, reader);
        return section;
    }

    private static Object invoke(Object target, String name) throws Exception {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static Object blockState(Object states, int x, int y, int z) throws Exception {
        return states.getClass().getMethod("get", int.class, int.class, int.class).invoke(states, x, y, z);
    }
}
