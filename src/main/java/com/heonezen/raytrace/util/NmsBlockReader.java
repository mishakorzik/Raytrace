package com.heonezen.raytrace.util;

import org.bukkit.Material;
import org.bukkit.World;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
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

    // Shape access for partial blocks (stairs, slabs, ...): canOcclude() only tells whether a
    // block is a full opaque cube, so a stair with a hole on the ray's side would be wrongly
    // treated as a full wall. The shape clip below tests the actual opaque boxes instead.
    // Missing pieces degrade to the old behaviour (canOcclude() only).
    private static final Method GET_SHAPE;
    private static final Method SHAPE_CLIP;
    private static final Method SHAPE_IS_EMPTY;
    private static final Method SHAPE_IS_FULL;
    private static final Constructor<?> BLOCK_POS_CTOR;
    private static final Constructor<?> VEC3_CTOR;
    private static final boolean SHAPES_AVAILABLE;
    private static final String SHAPE_FAILURE;

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

    // Block states are immutable singletons, so their classification can be cached.
    private static final ConcurrentHashMap<Object, Boolean> SENSITIVE_STATES = new ConcurrentHashMap<>();
    // The same goes for their Bukkit material: the reflective call is paid once per state.
    private static final ConcurrentHashMap<Object, Material> MATERIALS = new ConcurrentHashMap<>();
    // Per block state, the cached voxel shape that needs a clip test (a VoxelShape, or
    // Boolean.FALSE when the shape test can never block a sight line: empty, full, or not
    // solid-rendered like glass).
    private static final ConcurrentHashMap<Object, Object> SHAPE_CACHE = new ConcurrentHashMap<>();

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

        Method shapeMethod = null;
        Method clipMethod = null;
        Method emptyMethod = null;
        Method fullMethod = null;
        Constructor<?> posCtor = null;
        Constructor<?> vecCtor = null;
        String shapeFailure = null;
        try {
            Class<?> blockGetter = Class.forName("net.minecraft.world.level.BlockGetter");
            Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos");
            Class<?> stateClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
            try {
                shapeMethod = stateClass.getMethod("getShape", blockGetter, blockPos);
                shapeMethod.setAccessible(true);
            } catch (Throwable failure) {
                shapeFailure = "BlockState.getShape " + failure;
            }
            Class<?> shapeClass = Class.forName("net.minecraft.world.phys.shapes.VoxelShape");
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            clipMethod = shapeClass.getMethod("clip", vec3, vec3, blockPos);
            clipMethod.setAccessible(true);
            emptyMethod = shapeClass.getMethod("isEmpty");
            emptyMethod.setAccessible(true);
            // The "is this shape the full cube" check has three names across versions:
            // isCubeLike() (26.x, protected), isFullBlock() (older). Full shapes are excluded
            // from the shape clip test because canOcclude() already covers them, and glass
            // (a full cube that stays see-through) must not start blocking after this change.
            try {
                fullMethod = shapeClass.getDeclaredMethod("isCubeLike");
            } catch (NoSuchMethodException ignored) {
                try {
                    fullMethod = shapeClass.getMethod("isFullBlock");
                } catch (NoSuchMethodException ignoredAgain) {
                    fullMethod = shapeClass.getMethod("isFullCube");
                }
            }
            fullMethod.setAccessible(true);
            vecCtor = vec3.getConstructor(double.class, double.class, double.class);
            vecCtor.setAccessible(true);
            posCtor = blockPos.getConstructor(int.class, int.class, int.class);
            posCtor.setAccessible(true);
        } catch (Throwable ignored) {
            // Shape access unavailable on this version: keep the canOcclude() behaviour.
            if (shapeFailure == null) {
                shapeFailure = "shape lookup " + ignored;
            }
        }
        GET_SHAPE = shapeMethod;
        SHAPE_CLIP = clipMethod;
        SHAPE_IS_EMPTY = emptyMethod;
        SHAPE_IS_FULL = fullMethod;
        BLOCK_POS_CTOR = posCtor;
        VEC3_CTOR = vecCtor;
        SHAPES_AVAILABLE = shapeMethod != null && clipMethod != null && emptyMethod != null
                && fullMethod != null && posCtor != null && vecCtor != null;
        SHAPE_FAILURE = SHAPES_AVAILABLE ? null : shapeFailure;
    }

    private NmsBlockReader() {
    }

    // Whether direct server block reads work on this server.
    public static boolean available() {
        return AVAILABLE;
    }

    // Whether partial-block shape raycasts (stairs, slabs) work on this server.
    public static boolean shapesAvailable() {
        return SHAPES_AVAILABLE;
    }

    // Which shape lookup failed, or null when shapes work.
    public static String shapeFailure() {
        return SHAPE_FAILURE;
    }

    // The chunk source (the object that hands out loaded chunks) of a world, or null. Resolve it
    // once per ray or scan and pass it to {@link #chunkAt(Object, int, int)}.
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

    // The loaded chunk from a chunk source, or null; never loads one.
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

    // Returns the loaded server chunk, or null; never loads one.
    public static Object chunkAt(World world, int chunkX, int chunkZ) {
        return chunkAt(chunkSource(world), chunkX, chunkZ);
    }

    // The block state at these coordinates inside the given chunk, or null.
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

    // The Bukkit material of an NMS block state, or null when the accessor is unavailable.
    public static Material materialOf(Object state) {
        if (state == null || GET_BUKKIT_MATERIAL == null) {
            return null;
        }
        Material cached = MATERIALS.get(state);
        if (cached != null) {
            return cached;
        }
        try {
            Object material = GET_BUKKIT_MATERIAL.invoke(state);
            if (material instanceof Material value) {
                MATERIALS.putIfAbsent(state, value);
                return value;
            }
            return null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    // Blocks that reveal something valuable when seen through a wall: ores, containers,
    // spawners and similar block entities. Used only inside the soil surface buffer.
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

    // Null means unknown; masking unknown states is the safe choice.
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
    // Whether a block state occludes sight.
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
    // Whether the exact block state at these chunk-relative coordinates occludes sight.
    public static boolean isOccluding(Object chunk, int x, int y, int z) {
        if (!AVAILABLE || chunk == null) {
            return false;
        }
        return occludes(stateAt(chunk, x, y, z));
    }

    /**
     * The sight verdict for this voxel: the actual shape decides for partial shapes (stairs,
     * slabs, fences, ...). A ray through a missing part of the shape (the stair notch, the
     * empty half of a slab, the gaps of a fence) is clear, a ray through a solid box is
     * blocked. Full cubes and empty shapes keep the canOcclude() verdict - in particular glass
     * is a full cube and stays see-through. Resolved states are cached per block state;
     * anything unresolvable falls back to canOcclude().
     */
    public static boolean occludesRay(Object chunk, int worldX, int worldY, int worldZ,
                                      double ex, double ey, double ez, double tx, double ty, double tz) {
        if (!AVAILABLE || chunk == null) {
            return false;
        }
        Object state = stateAt(chunk, worldX & 15, worldY, worldZ & 15);
        if (state == null) {
            return false;
        }
        boolean base = occludes(state);
        if (!SHAPES_AVAILABLE) {
            return base;
        }
        Object shape = SHAPE_CACHE.get(state);
        if (shape == null) {
            shape = resolveShape(chunk, state, worldX, worldY, worldZ);
            SHAPE_CACHE.putIfAbsent(state, shape);
            shape = SHAPE_CACHE.get(state);
        }
        if (shape == null || Boolean.FALSE.equals(shape)) {
            // Full cube (already covered by canOcclude()), empty, transparent, or shape
            // lookup failed: keep the coarse verdict.
            return base;
        }
        try {
            Object start = VEC3_CTOR.newInstance(ex, ey, ez);
            Object end = VEC3_CTOR.newInstance(tx, ty, tz);
            Object pos = BLOCK_POS_CTOR.newInstance(worldX, worldY, worldZ);
            Object hit = SHAPE_CLIP.invoke(shape, start, end, pos);
            // Old versions return Optional<Vec3>; 26.x returns a nullable BlockHitResult.
            if (hit instanceof Optional) {
                return ((Optional<?>) hit).isPresent();
            }
            return hit != null;
        } catch (Throwable throwable) {
            return base;
        }
    }

    private static Object resolveShape(Object chunk, Object state, int worldX, int worldY, int worldZ) {
        try {
            Object pos = BLOCK_POS_CTOR.newInstance(worldX, worldY, worldZ);
            Object shape = GET_SHAPE.invoke(state, chunk, pos);
            if (shape == null) {
                return Boolean.FALSE;
            }
            if ((Boolean) SHAPE_IS_EMPTY.invoke(shape) || (Boolean) SHAPE_IS_FULL.invoke(shape)) {
                // Air never blocks; a full cube is already covered by canOcclude() - and glass,
                // which is a full cube but see-through, must stay see-through.
                return Boolean.FALSE;
            }
            // Every other partial shape is tested with the clip: stairs, slabs, fences, walls
            // and similar pass rays only through their actually solid boxes. (Fences e.g. return
            // false from useShapeForLightOcclusion(), which is the light-engine flag, not the
            // sight shape - applying it would keep them solid.)
            return shape;
        } catch (Throwable throwable) {
            return Boolean.FALSE;
        }
    }
}
