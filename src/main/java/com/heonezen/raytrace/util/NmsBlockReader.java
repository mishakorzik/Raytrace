package com.heonezen.raytrace.util;

import org.bukkit.Material;
import org.bukkit.World;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only block access that is safe on any thread. Bukkit accessors assert the tick thread on
 * Folia/Canvas and allocate a wrapper per block; {@code LevelChunk#getBlockState} is a plain
 * data read. Every member is bound once as a method handle, so a block read is a few direct
 * calls. Missing pieces degrade to "not available".
 */
public final class NmsBlockReader {

    private static final MethodType OBJ_TO_OBJ = MethodType.methodType(Object.class, Object.class);
    private static final MethodType CHUNK_AT = MethodType.methodType(Object.class, Object.class, int.class, int.class);
    private static final MethodType STATE_AT = MethodType.methodType(Object.class, Object.class, int.class, int.class, int.class);
    private static final MethodType OBJ_TO_BOOL = MethodType.methodType(boolean.class, Object.class);

    private static final MethodHandle GET_CHUNK_SOURCE;
    private static final MethodHandle GET_CHUNK_AT_IF_LOADED;
    private static final MethodHandle GET_BLOCK_STATE;
    private static final MethodHandle CAN_OCCLUDE;
    private static final Method GET_BUKKIT_MATERIAL;
    private static final boolean AVAILABLE;

    private static final MethodHandle NO_HANDLE = MethodHandles.constant(Object.class, null);
    private static final ClassValue<MethodHandle> GET_HANDLE = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            try {
                return MethodHandles.publicLookup().unreflect(type.getMethod("getHandle")).asType(OBJ_TO_OBJ);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return NO_HANDLE;
            }
        }
    };

    /** Block states are immutable singletons, so their classification can be cached. */
    private static final ConcurrentHashMap<Object, Boolean> SENSITIVE_STATES = new ConcurrentHashMap<>();

    static {
        MethodHandle chunkSource = null;
        MethodHandle chunkAt = null;
        MethodHandle getBlockState = null;
        MethodHandle canOcclude = null;
        Method getMaterial = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> levelClass = Class.forName("net.minecraft.server.level.ServerLevel");
            chunkSource = lookup.unreflect(levelClass.getMethod("getChunkSource")).asType(OBJ_TO_OBJ);
            Class<?> sourceClass = Class.forName("net.minecraft.server.level.ServerChunkCache");
            chunkAt = lookup.unreflect(sourceClass.getMethod("getChunkAtIfLoadedImmediately", int.class, int.class)).asType(CHUNK_AT);
            Class<?> chunkClass = Class.forName("net.minecraft.world.level.chunk.LevelChunk");
            getBlockState = lookup.unreflect(chunkClass.getMethod("getBlockState", int.class, int.class, int.class)).asType(STATE_AT);
            Class<?> stateClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
            try {
                // canOcclude() is inherited from BlockBehaviour$BlockStateBase.
                canOcclude = lookup.unreflect(stateClass.getMethod("canOcclude")).asType(OBJ_TO_BOOL);
            } catch (ReflectiveOperationException ignored) {
                // Fall back to Paper's material accessor below.
            }
            try {
                getMaterial = stateClass.getMethod("getBukkitMaterial");
            } catch (NoSuchMethodException ignored) {
                // Paper-only accessor; canOcclude normally works without it.
            }
        } catch (Throwable ignored) {
            // NMS not reachable: treat every block as clear instead of touching Bukkit from Netty.
        }
        GET_CHUNK_SOURCE = chunkSource;
        GET_CHUNK_AT_IF_LOADED = chunkAt;
        GET_BLOCK_STATE = getBlockState;
        CAN_OCCLUDE = canOcclude;
        GET_BUKKIT_MATERIAL = getMaterial;
        AVAILABLE = chunkSource != null && chunkAt != null && getBlockState != null && (canOcclude != null || getMaterial != null);
    }

    private NmsBlockReader() {
    }

    /** Whether direct server block reads work on this server. */
    public static boolean available() {
        return AVAILABLE;
    }

    /**
     * The chunk source (the object that hands out loaded chunks) of a world, or null. Resolve it
     * once per ray or scan and pass it to {@link #chunkAt(Object, int, int)}.
     */
    public static Object chunkSource(World world) {
        if (!AVAILABLE || world == null) {
            return null;
        }
        try {
            MethodHandle getHandle = GET_HANDLE.get(world.getClass());
            if (getHandle == NO_HANDLE) {
                return null;
            }
            Object level = (Object) getHandle.invokeExact((Object) world);
            return level == null ? null : (Object) GET_CHUNK_SOURCE.invokeExact(level);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** The loaded chunk from a chunk source, or null; never loads one. */
    public static Object chunkAt(Object chunkSource, int chunkX, int chunkZ) {
        if (chunkSource == null) {
            return null;
        }
        try {
            return (Object) GET_CHUNK_AT_IF_LOADED.invokeExact(chunkSource, chunkX, chunkZ);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** Returns the loaded server chunk, or null; never loads one. */
    public static Object chunkAt(World world, int chunkX, int chunkZ) {
        return chunkAt(chunkSource(world), chunkX, chunkZ);
    }

    /** The block state at these coordinates inside the given chunk, or null. */
    public static Object stateAt(Object chunk, int x, int y, int z) {
        if (chunk == null || !AVAILABLE) {
            return null;
        }
        try {
            return (Object) GET_BLOCK_STATE.invokeExact(chunk, x, y, z);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** The Bukkit material of an NMS block state, or null when the accessor is unavailable. */
    public static Material materialOf(Object state) {
        if (state == null || GET_BUKKIT_MATERIAL == null) {
            return null;
        }
        try {
            Object material = GET_BUKKIT_MATERIAL.invoke(state);
            return material instanceof Material value ? value : null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * Blocks that reveal something valuable when seen through a wall: ores, containers,
     * spawners and similar block entities. Used only inside the soil surface buffer.
     */
    public static boolean isSensitiveBlockState(Object state) {
        if (state == null) {
            return true;
        }
        Boolean cached = SENSITIVE_STATES.get(state);
        if (cached != null) {
            return cached;
        }
        boolean sensitive = isSensitiveMaterial(materialOf(state));
        SENSITIVE_STATES.putIfAbsent(state, sensitive);
        return sensitive;
    }

    /** Null means unknown; masking unknown states is the safe choice. */
    public static boolean isSensitiveMaterial(Material material) {
        if (material == null) {
            return true;
        }
        String name = material.name();
        if (name.endsWith("_ORE") || name.endsWith("SHULKER_BOX")) {
            return true;
        }
        return switch (material) {
            case ANCIENT_DEBRIS, CHEST, TRAPPED_CHEST, BARREL, ENDER_CHEST, HOPPER, DISPENSER, DROPPER, FURNACE, BLAST_FURNACE, SMOKER, BREWING_STAND, BEACON, ENCHANTING_TABLE, SPAWNER, TRIAL_SPAWNER, VAULT, DECORATED_POT, SUSPICIOUS_SAND, SUSPICIOUS_GRAVEL, DRAGON_EGG, RAW_IRON_BLOCK, RAW_COPPER_BLOCK, RAW_GOLD_BLOCK -> true;
            default -> false;
        };
    }
    /** Whether a block state occludes sight. */
    public static boolean occludes(Object state) {
        if (state == null) {
            return false;
        }
        try {
            if (CAN_OCCLUDE != null) {
                return (boolean) CAN_OCCLUDE.invokeExact(state);
            }
            Material material = materialOf(state);
            return material != null && material.isOccluding();
        } catch (Throwable throwable) {
            return false;
        }
    }
    /** Whether the exact block state at these chunk-relative coordinates occludes sight. */
    public static boolean isOccluding(Object chunk, int x, int y, int z) {
        if (!AVAILABLE || chunk == null) {
            return false;
        }
        return occludes(stateAt(chunk, x, y, z));
    }
}
