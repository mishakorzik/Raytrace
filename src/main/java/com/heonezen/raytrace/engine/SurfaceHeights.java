package com.heonezen.raytrace.engine;

import com.heonezen.raytrace.config.WorldSettings;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Lowers the configured masking ceiling to each column's real surface. Height maps are read via
 * NMS on the Netty thread; if unavailable, the flat ceiling is used. The motion height map
 * ignores leaves but counts fluids, so fluid columns fall back to the ocean floor.
 */
public final class SurfaceHeights {

    /** Surface materials treated as soil: the walkable layer that stays real under the surface buffer. */
    private static final Set<Material> SOFT_SURFACE_BLOCKS = Set.of(
            Material.DIRT,
            Material.GRASS_BLOCK,
            Material.PODZOL,
            Material.MYCELIUM,
            Material.COARSE_DIRT,
            Material.ROOTED_DIRT,
            Material.MUD,
            Material.SAND,
            Material.RED_SAND,
            Material.GRAVEL,
            Material.CLAY,
            Material.SNOW,
            Material.SNOW_BLOCK,
            Material.ICE,
            Material.PACKED_ICE,
            Material.BLUE_ICE,
            Material.MAGMA_BLOCK,
            Material.SOUL_SAND,
            Material.SOUL_SOIL);

    private SurfaceHeights() {
    }

    /**
     * Whether a surface block is wood rather than terrain. Without this a tree trunk would become
     * the column's surface and a fake-stone pillar would show under it.
     */
    private static boolean isWoody(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM")
                || name.endsWith("_HYPHAE")
                || material == Material.BAMBOO || material == Material.BAMBOO_BLOCK
                || material == Material.MANGROVE_ROOTS
                || material == Material.MUDDY_MANGROVE_ROOTS
                || material == Material.MUSHROOM_STEM
                || material == Material.CACTUS;
    }

    /**
     * Hard cutoff for one column. Everything below this Y is underground, so ores, containers
     * and block entities are hidden there unless the viewer earned the cell. Unlike
     * {@link #effectiveCeiling}, this never applies the soil surface buffer.
     */
    public static int hardCeiling(World world, WorldSettings worldSettings, int x, int z) {
        int configuredCeiling = Math.min(worldSettings.maskCeilingY(), world.getMaxHeight());
        int minY = world.getMinHeight();
        if (configuredCeiling <= minY || worldSettings.surfaceBuffer() < 0) {
            return configuredCeiling;
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        Object chunk = Nms.chunkAt(world, chunkX, chunkZ);
        if (chunk != null) {
            Integer surfaceY = Nms.surfaceY(chunk, world, x & 15, z & 15);
            if (surfaceY == null) {
                return configuredCeiling;
            }
            return resolveHardCeiling(configuredCeiling, minY, surfaceY);
        }
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return configuredCeiling;
        }
        try {
            return resolveHardCeiling(configuredCeiling, minY, world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES));
        } catch (RuntimeException exception) {
            return configuredCeiling;
        }
    }

    /** Masking ceiling for one column: the Y at and above which nothing is masked. */
    public static int effectiveCeiling(World world, WorldSettings worldSettings, int x, int z) {
        int configuredCeiling = Math.min(worldSettings.maskCeilingY(), world.getMaxHeight());
        int minY = world.getMinHeight();
        if (configuredCeiling <= minY) {
            return configuredCeiling;
        }
        int surfaceBuffer = worldSettings.surfaceBuffer();
        if (surfaceBuffer < 0) {
            // Surface-awareness disabled for this world.
            return configuredCeiling;
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int localX = x & 15;
        int localZ = z & 15;

        // Fast path: the loaded chunk's primed height map. Safe on the Netty thread, where
        // Bukkit's accessor may not be callable.
        Object chunk = Nms.chunkAt(world, chunkX, chunkZ);
        if (chunk != null) {
            Integer surfaceY = Nms.surfaceY(chunk, world, localX, localZ);
            if (surfaceY == null) {
                // Unreadable surface: stay flat, which never exposes a block.
                return configuredCeiling;
            }
            return resolveCeiling(configuredCeiling, minY, surfaceBuffer, chunk, localX, localZ, surfaceY);
        }

        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            // Never force-load a chunk just to measure it; an unloaded chunk is not on screen,
            // so the flat ceiling is safe.
            return configuredCeiling;
        }

        int naturalSurfaceY;
        try {
            naturalSurfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        } catch (RuntimeException exception) {
            // Not loaded, or no access off the tick thread: use the flat ceiling.
            return configuredCeiling;
        }
        if (naturalSurfaceY < minY) {
            return configuredCeiling;
        }

        // Bukkit fallback for servers where the NMS chunk is unreachable. Still correct on the
        // region thread.
        return resolveCeiling(configuredCeiling, minY, surfaceBuffer, () -> isSoftSurface(world, chunkX, chunkZ, x, naturalSurfaceY, z), naturalSurfaceY);
    }

    /**
     * A chunk's per-column ceilings. {@code values} are the normal terrain ceilings (with the
     * soil buffer); {@code oreValues} are the hard cutoffs used for ores, containers and block
     * entities. {@code fromTerrain == false} means a flat fallback, which must not be cached: a
     * later packet may arrive once the height map is readable and use real terrain.
     */
    public record ChunkCeilings(int[] values, int[] oreValues, boolean fromTerrain) {}

    /**
     * Per-column ceilings indexed by {@code x * 16 + z}; packet height map first, then the loaded
     * chunk. A flat fallback is never cached.
     */
    public static ChunkCeilings ceilingsForChunk(World world, WorldSettings worldSettings, int chunkX, int chunkZ, Object chunkData) {
        int configuredCeiling = Math.min(worldSettings.maskCeilingY(), world.getMaxHeight());
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int[] ceilings = new int[256];
        int[] oreCeilings = new int[256];
        Arrays.fill(ceilings, configuredCeiling);
        Arrays.fill(oreCeilings, configuredCeiling);
        int surfaceBuffer = worldSettings.surfaceBuffer();
        if (surfaceBuffer < 0 || configuredCeiling <= minY) {
            return new ChunkCeilings(ceilings, oreCeilings, false);
        }

        // Packet height map: plain data, safe on the Netty thread.
        long[] packetHeights = PacketHeights.raw(chunkData);
        int bits = PacketHeights.bits(maxY - minY);
        if (packetHeights != null && bits > 0 && PacketHeights.fits(packetHeights, bits)) {
            Object chunk = Nms.chunkAt(world, chunkX, chunkZ);
            boolean decodedAny = false;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int raw = PacketHeights.decode(packetHeights, bits, x, z);
                    if (raw < 0) {
                        continue;
                    }
                    decodedAny = true;
                    int surfaceY = raw + minY - 1;
                    int index = x * 16 + z;
                    if (surfaceY < minY) {
                        // Empty column: fake stone here would itself be visible.
                        ceilings[index] = minY;
                        oreCeilings[index] = minY;
                        continue;
                    }
                    if (surfaceY >= maxY) {
                        continue;
                    }
                    // The packet map counts water, so resolve fluid columns to the seabed via the
                    // live chunk.
                    if (chunk != null) {
                        surfaceY = Nms.adjustSurface(chunk, world, x, z, surfaceY);
                    }
                    if (surfaceY < minY) {
                        ceilings[index] = minY;
                        oreCeilings[index] = minY;
                        continue;
                    }
                    boolean soft = chunk != null && Nms.isSoftSurface(chunk, x, z, surfaceY, minY);
                    ceilings[index] = resolveCeiling(configuredCeiling, minY, surfaceBuffer, soft, surfaceY);
                    oreCeilings[index] = resolveHardCeiling(configuredCeiling, minY, surfaceY);
                }
            }
            if (decodedAny) {
                return new ChunkCeilings(ceilings, oreCeilings, true);
            }
        }

        Object chunk = Nms.chunkAt(world, chunkX, chunkZ);
        if (chunk == null) {
            return new ChunkCeilings(ceilings, oreCeilings, false);
        }
        boolean readAny = false;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Integer surfaceY = Nms.surfaceY(chunk, world, x, z);
                if (surfaceY == null) {
                    continue;
                }
                readAny = true;
                int index = x * 16 + z;
                if (surfaceY < minY) {
                    ceilings[index] = minY;
                    oreCeilings[index] = minY;
                    continue;
                }
                ceilings[index] = resolveCeiling(configuredCeiling, minY, surfaceBuffer, chunk, x, z, surfaceY);
                oreCeilings[index] = resolveHardCeiling(configuredCeiling, minY, surfaceY);
            }
        }
        return new ChunkCeilings(ceilings, oreCeilings, readAny);
    }

    /** Applies only the configured cap: the surface buffer never raises the hard cutoff. */
    private static int resolveHardCeiling(int configuredCeiling, int minY, int surfaceY) {
        return Math.min(configuredCeiling, Math.max(surfaceY, minY));
    }

    /** Applies the surface buffer and configured cap to one column whose surface Y is known. */
    private static int resolveCeiling(int configuredCeiling, int minY, int surfaceBuffer, Object chunk, int localX, int localZ, int surfaceY) {
        return resolveCeiling(configuredCeiling, minY, surfaceBuffer, Nms.isSoftSurface(chunk, localX, localZ, surfaceY, minY), surfaceY);
    }

    private static int resolveCeiling(int configuredCeiling, int minY, int surfaceBuffer, boolean softSurface, int surfaceY) {
        int naturalCeiling;
        if (surfaceBuffer > 0 && softSurface) {
            // Soil: keep the walkable layer real.
            naturalCeiling = surfaceY - surfaceBuffer + 1;
        } else {
            // Hard ground: the surface itself is hidden, so the ores inside stay hidden too.
            naturalCeiling = surfaceY;
        }
        return Math.min(configuredCeiling, Math.max(naturalCeiling, minY));
    }

    /** The Bukkit-only variant of {@link #resolveCeiling}, for servers without reachable NMS. */
    private static int resolveCeiling(int configuredCeiling, int minY, int surfaceBuffer,
                                      java.util.function.BooleanSupplier softSurface, int surfaceY) {
        int naturalCeiling;
        if (surfaceBuffer > 0 && softSurface.getAsBoolean()) {
            naturalCeiling = surfaceY - surfaceBuffer + 1;
        } else {
            naturalCeiling = surfaceY;
        }
        return Math.min(configuredCeiling, Math.max(naturalCeiling, minY));
    }

    /**
     * Whether the surface is soil, checking the top block and a few blocks below it. Unknown
     * states count as soil, which is safe.
     */
    private static boolean isSoftSurface(World world, int chunkX, int chunkZ, int x, int surfaceY, int z) {
        try {
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            Material material = chunk.getBlock(x & 15, surfaceY - 1, z & 15).getType();
            if (SOFT_SURFACE_BLOCKS.contains(material)) {
                return true;
            }
            // Look a little deeper through air, so one dirt block over stone still counts.
            for (int depth = 2; depth <= 4; depth++) {
                Material deeper = chunk.getBlock(x & 15, surfaceY - depth, z & 15).getType();
                if (SOFT_SURFACE_BLOCKS.contains(deeper)) {
                    return true;
                }
                if (deeper != Material.AIR && deeper != Material.CAVE_AIR) {
                    break;
                }
            }
            return false;
        } catch (RuntimeException exception) {
            // Unreadable: assume soil. Keeping too much surface real never exposes buried blocks.
            return true;
        }
    }

    /**
     * Reflective access to the server's chunk height maps and block states. There is no NMS compile
     * dependency; any missing member degrades to the flat ceiling, and nothing here loads a chunk.
     */
    private static final class Nms {

        private static final Method GET_CHUNK_SOURCE;
        private static final Method GET_CHUNK_AT_IF_LOADED;
        private static final Class<?> HEIGHTMAP_TYPES;
        private static final Object MOTION_BLOCKING_NO_LEAVES;
        private static final Object OCEAN_FLOOR;
        private static final Method HAS_PRIMED_HEIGHTMAP;
        private static final Method GET_HEIGHT;
        private static final Method GET_BLOCK_STATE;
        private static final Method GET_BUKKIT_MATERIAL;
        private static final Method GET_FLUID_STATE;
        private static final Method FLUID_IS_EMPTY;
        private static final Method CAN_OCCLUDE;
        private static final Predicate<Object> SURFACE_PREDICATE;
        private static final Predicate<Object> OCEAN_FLOOR_PREDICATE;
        private static final boolean AVAILABLE;
        private static final Map<Class<?>, Method> GET_HANDLE = new ConcurrentHashMap<>();

        static {
            Method chunkSource = null;
            Method chunkAt = null;
            Class<?> heightmapTypes = null;
            Object motionBlocking = null;
            Object oceanFloorType = null;
            Method hasPrimed = null;
            Method getHeight = null;
            Method getBlockState = null;
            Method getMaterial = null;
            Method getFluidState = null;
            Method fluidIsEmpty = null;
            Method canOcclude = null;
            Predicate<Object> surfacePredicate = null;
            Predicate<Object> oceanFloorPredicate = null;
            try {
                Class<?> levelClass = Class.forName("net.minecraft.server.level.ServerLevel");
                chunkSource = levelClass.getMethod("getChunkSource");
                Class<?> sourceClass = Class.forName("net.minecraft.server.level.ServerChunkCache");
                chunkAt = sourceClass.getMethod("getChunkAtIfLoadedImmediately", int.class, int.class);
                heightmapTypes = Class.forName("net.minecraft.world.level.levelgen.Heightmap$Types");
                motionBlocking = heightmapTypes.getField("MOTION_BLOCKING_NO_LEAVES").get(null);
                Class<?> chunkClass = Class.forName("net.minecraft.world.level.chunk.LevelChunk");
                hasPrimed = chunkClass.getMethod("hasPrimedHeightmap", heightmapTypes);
                getHeight = chunkClass.getMethod("getHeight", heightmapTypes, int.class, int.class);
                getBlockState = chunkClass.getMethod("getBlockState", int.class, int.class, int.class);
                Class<?> stateClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
                try {
                    getMaterial = stateClass.getMethod("getBukkitMaterial");
                } catch (NoSuchMethodException ignored) {
                    // No Paper material accessor: treat the surface as soil (safe, nothing buried exposed).
                }
                try {
                    Object predicate = heightmapTypes.getMethod("isOpaque").invoke(motionBlocking);
                    if (predicate instanceof Predicate<?> value) {
                        @SuppressWarnings("unchecked")
                        Predicate<Object> cast = (Predicate<Object>) value;
                        surfacePredicate = cast;
                    }
                } catch (Throwable ignored) {
                    // The direct block scan is only a fallback for an unprimed height map.
                }
                try {
                    // MOTION_BLOCKING_NO_LEAVES counts fluids and would report the water surface;
                    // OCEAN_FLOOR ignores fluids and gives the seabed.
                    oceanFloorType = heightmapTypes.getField("OCEAN_FLOOR").get(null);
                    Object predicate = heightmapTypes.getMethod("isOpaque").invoke(oceanFloorType);
                    if (predicate instanceof Predicate<?> value) {
                        @SuppressWarnings("unchecked")
                        Predicate<Object> cast = (Predicate<Object>) value;
                        oceanFloorPredicate = cast;
                    }
                } catch (Throwable ignored) {
                    // Without it, fluid columns keep their height-map surface.
                }
                try {
                    getFluidState = stateClass.getMethod("getFluidState");
                    Class<?> fluidState = Class.forName(
                            "net.minecraft.world.level.material.FluidState");
                    fluidIsEmpty = fluidState.getMethod("isEmpty");
                } catch (Throwable ignored) {
                    // The material fallback below still recognises water and lava.
                }
                try {
                    canOcclude = stateClass.getMethod("canOcclude");
                } catch (NoSuchMethodException ignored) {
                    // Optional; only used to tell open fluid from a waterlogged solid surface.
                }
            } catch (Throwable ignored) {
                // NMS unreachable (e.g. a mocked World): accessors return null and callers fall back.
            }
            GET_CHUNK_SOURCE = chunkSource;
            GET_CHUNK_AT_IF_LOADED = chunkAt;
            HEIGHTMAP_TYPES = heightmapTypes;
            MOTION_BLOCKING_NO_LEAVES = motionBlocking;
            OCEAN_FLOOR = oceanFloorType;
            HAS_PRIMED_HEIGHTMAP = hasPrimed;
            GET_HEIGHT = getHeight;
            GET_BLOCK_STATE = getBlockState;
            GET_BUKKIT_MATERIAL = getMaterial;
            GET_FLUID_STATE = getFluidState;
            FLUID_IS_EMPTY = fluidIsEmpty;
            CAN_OCCLUDE = canOcclude;
            SURFACE_PREDICATE = surfacePredicate;
            OCEAN_FLOOR_PREDICATE = oceanFloorPredicate;
            AVAILABLE = chunkSource != null && chunkAt != null && heightmapTypes != null && motionBlocking != null && hasPrimed != null && getHeight != null && getBlockState != null;
        }

        private Nms() {
        }

        /** The already-loaded server chunk, or null if unavailable. */
        static Object chunkAt(World world, int chunkX, int chunkZ) {
            if (!AVAILABLE) {
                return null;
            }
            try {
                Method getHandle = GET_HANDLE.computeIfAbsent(world.getClass(), type -> {
                    try {
                        return type.getMethod("getHandle");
                    } catch (ReflectiveOperationException exception) {
                        return null;
                    }
                });
                if (getHandle == null) {
                    return null;
                }
                Object level = getHandle.invoke(world);
                if (level == null) {
                    return null;
                }
                Object source = GET_CHUNK_SOURCE.invoke(level);
                return GET_CHUNK_AT_IF_LOADED.invoke(source, chunkX, chunkZ);
            } catch (Throwable throwable) {
                return null;
            }
        }

        /** Whether the motion-blocking height map is primed, or null on failure. */
        static Boolean hasPrimedHeightmap(Object chunk) {
            return hasPrimedHeightmap(chunk, MOTION_BLOCKING_NO_LEAVES);
        }

        private static Boolean hasPrimedHeightmap(Object chunk, Object type) {
            if (!AVAILABLE || type == null) {
                return null;
            }
            try {
                return (Boolean) HAS_PRIMED_HEIGHTMAP.invoke(chunk, type);
            } catch (Throwable throwable) {
                return null;
            }
        }

        /** Height-map Y of one column, if the map is primed. */
        static Integer heightAt(Object chunk, int x, int z) {
            if (!Boolean.TRUE.equals(hasPrimedHeightmap(chunk))) {
                return null;
            }
            return heightAtPrimed(chunk, x, z);
        }

        /** Motion-blocking height of one column. */
        static Integer heightAtPrimed(Object chunk, int x, int z) {
            return heightAtPrimed(chunk, x, z, MOTION_BLOCKING_NO_LEAVES);
        }

        /** Height-map Y of one column; the caller must have checked priming first. */
        private static Integer heightAtPrimed(Object chunk, int x, int z, Object type) {
            if (!AVAILABLE || type == null) {
                return null;
            }
            try {
                return (Integer) GET_HEIGHT.invoke(chunk, type, x, z);
            } catch (Throwable throwable) {
                return null;
            }
        }

        /** Primed {@code OCEAN_FLOOR} height of one column, or null if unavailable. */
        private static Integer oceanFloorAt(Object chunk, int x, int z) {
            if (OCEAN_FLOOR == null || !Boolean.TRUE.equals(hasPrimedHeightmap(chunk, OCEAN_FLOOR))) {
                return null;
            }
            return heightAtPrimed(chunk, x, z, OCEAN_FLOOR);
        }

        /**
         * Real terrain surface of one column: the primed height map, otherwise a direct block scan.
         * Both paths start from {@code MOTION_BLOCKING_NO_LEAVES}, which counts fluids, so fluid
         * columns are resolved to {@code OCEAN_FLOOR}.
         */
        static Integer surfaceY(Object chunk, World world, int x, int z) {
            if (!AVAILABLE) {
                return null;
            }
            if (Boolean.TRUE.equals(hasPrimedHeightmap(chunk))) {
                Integer height = heightAtPrimed(chunk, x, z);
                if (height != null) {
                    return adjustSurface(chunk, world, x, z, height);
                }
            }
            Predicate<Object> predicate = SURFACE_PREDICATE;
            if (predicate == null) {
                return null;
            }
            try {
                int minY = world.getMinHeight();
                for (int y = world.getMaxHeight() - 1; y >= minY; y--) {
                    Object state = stateAt(chunk, x, y, z);
                    if (state != null && predicate.test(state)) {
                        return adjustSurface(chunk, world, x, z, y);
                    }
                }
                // Void column, not surface at minY.
                return minY - 1;
            } catch (Throwable throwable) {
                return null;
            }
        }

        /**
         * Resolves a height-map surface to real terrain: through vegetation first, then through
         * fluid or an ice cap to the floor. The order matters for trees standing in shallow water.
         */
        static Integer adjustSurface(Object chunk, World world, int x, int z, int surfaceY) {
            return adjustForFluids(chunk, world, x, z,
                    adjustForVegetation(chunk, world, x, z, surfaceY));
        }

        /**
         * Replaces a wood or foliage surface with the ground underneath, so a tree trunk does not
         * become the masking ceiling and leave a fake-stone pillar below it.
         */
        private static int adjustForVegetation(Object chunk, World world, int x, int z, int surfaceY) {
            if (chunk == null) {
                return surfaceY;
            }
            try {
                int minY = world.getMinHeight();
                if (surfaceY < minY || surfaceY >= world.getMaxHeight()) {
                    return surfaceY;
                }
                if (!isVegetation(materialAt(chunk, x, surfaceY, z))) {
                    return surfaceY;
                }
                for (int y = surfaceY - 1; y >= minY; y--) {
                    Material material = materialAt(chunk, x, y, z);
                    if (material == null) {
                        // Unknown state: keep the height-map answer.
                        return surfaceY;
                    }
                    if (isVegetation(material) || material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
                        continue;
                    }
                    return y;
                }
                return minY - 1;
            } catch (Throwable throwable) {
                return surfaceY;
            }
        }

        private static boolean isVegetation(Material material) {
            return isWoody(material) || (material != null && material.name().endsWith("_LEAVES"));
        }

        /**
         * Turns a fluid or ice-capped surface into the seabed below it, scanning with the
         * {@code OCEAN_FLOOR} predicate, which ignores fluids.
         */
        static Integer adjustForFluids(Object chunk, World world, int x, int z, int surfaceY) {
            if (chunk == null || surfaceY < world.getMinHeight()) {
                return surfaceY;
            }
            try {
                int minY = world.getMinHeight();
                // A waterlogged solid is not open ocean; only open fluid, or fluid under a thin
                // cap such as ice, marks a water column.
                boolean openFluid = isFluidAt(chunk, x, surfaceY, z) && !isOccludingAt(chunk, x, surfaceY, z);
                boolean fluidColumn = openFluid || (surfaceY - 1 >= minY && isFluidAt(chunk, x, surfaceY - 1, z));
                if (!fluidColumn) {
                    return surfaceY;
                }
                // With an ice or snow cap OCEAN_FLOOR returns the cap, not the seabed, so only use
                // it for open fluid.
                if (openFluid) {
                    Integer floorHeight = oceanFloorAt(chunk, x, z);
                    if (floorHeight != null) {
                        if (floorHeight < minY) {
                            return minY - 1;
                        }
                        // Validate the block the map points at before trusting it.
                        if (floorHeight <= surfaceY) {
                            if (OCEAN_FLOOR_PREDICATE == null) {
                                return floorHeight;
                            }
                            Object floorState = stateAt(chunk, x, floorHeight, z);
                            if (floorState != null && OCEAN_FLOOR_PREDICATE.test(floorState)) {
                                return floorHeight;
                            }
                        }
                        // Stale or unusable OCEAN_FLOOR value: use the direct scan below.
                    }
                }
                Predicate<Object> floor = OCEAN_FLOOR_PREDICATE;
                if (floor == null) {
                    // No server predicate: use the material test, never treating fluid as ground.
                    for (int y = surfaceY - 1; y >= minY; y--) {
                        Material material = materialAt(chunk, x, y, z);
                        if (material != null && material != Material.WATER
                                && material != Material.LAVA
                                && material != Material.BUBBLE_COLUMN
                                && material.isSolid()) {
                            return y;
                        }
                    }
                    return minY - 1;
                }
                for (int y = surfaceY - 1; y >= minY; y--) {
                    Object state = stateAt(chunk, x, y, z);
                    if (state != null && floor.test(state)) {
                        return y;
                    }
                }
                // Fluid-only column: no terrain to hide.
                return minY - 1;
            } catch (Throwable throwable) {
                return surfaceY;
            }
        }

        private static boolean isOccludingAt(Object chunk, int x, int y, int z) {
            if (CAN_OCCLUDE == null) {
                // Unknown shape: treat as solid; fluid below still marks open water.
                return true;
            }
            try {
                Object state = stateAt(chunk, x, y, z);
                return state != null && Boolean.TRUE.equals(CAN_OCCLUDE.invoke(state));
            } catch (Throwable throwable) {
                return true;
            }
        }

        private static boolean isFluidAt(Object chunk, int x, int y, int z) {
            if (GET_FLUID_STATE != null && FLUID_IS_EMPTY != null) {
                try {
                    Object state = stateAt(chunk, x, y, z);
                    if (state != null) {
                        Object fluid = GET_FLUID_STATE.invoke(state);
                        return fluid != null
                                && !Boolean.TRUE.equals(FLUID_IS_EMPTY.invoke(fluid));
                    }
                } catch (Throwable ignored) {
                    // Fall through to the material test.
                }
            }
            Material material = materialAt(chunk, x, y, z);
            return material == Material.WATER || material == Material.LAVA || material == Material.BUBBLE_COLUMN;
        }

        /**
         * Whether the surface is soil, checking the top block and a few blocks below it through air.
         * Unknown states count as soil, which is safe.
         */
        static boolean isSoftSurface(Object chunk, int x, int z, int surfaceY, int minY) {
            // The top block is what a fluid-adjusted seabed reports, so a sand floor stays real.
            if (surfaceY >= minY) {
                Material top = materialAt(chunk, x, surfaceY, z);
                if (top != null && SOFT_SURFACE_BLOCKS.contains(top)) {
                    return true;
                }
            }
            for (int depth = 1; depth <= 4; depth++) {
                int y = surfaceY - depth;
                if (y < minY) {
                    break;
                }
                Material material = materialAt(chunk, x, y, z);
                if (material == null) {
                    return true;
                }
                if (SOFT_SURFACE_BLOCKS.contains(material)) {
                    return true;
                }
                if (material != Material.AIR && material != Material.CAVE_AIR) {
                    return false;
                }
            }
            return false;
        }

        private static Object stateAt(Object chunk, int x, int y, int z) {
            try {
                return GET_BLOCK_STATE.invoke(chunk, x, y, z);
            } catch (Throwable throwable) {
                return null;
            }
        }

        private static Material materialAt(Object chunk, int x, int y, int z) {
            Object state = stateAt(chunk, x, y, z);
            if (state == null || GET_BUKKIT_MATERIAL == null) {
                return null;
            }
            try {
                Object material = GET_BUKKIT_MATERIAL.invoke(state);
                return material instanceof Material ? (Material) material : null;
            } catch (Throwable throwable) {
                return null;
            }
        }
    }

    /**
     * The packet's own height map: the client's exact terrain snapshot, safe to read on the Netty
     * thread. The column order is read once from {@code Heightmap.getIndex} rather than guessed.
     */
    private static final class PacketHeights {

        private static final Method GET_HEIGHTMAPS;
        private static final Object MOTION_BLOCKING_NO_LEAVES;
        private static final int INDEX_MODE; // 0 = x + z*16, 1 = x*16 + z

        static {
            Method getHeightmaps = null;
            Object motionBlocking = null;
            int indexMode = -1;
            try {
                Class<?> dataClass = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData");
                getHeightmaps = dataClass.getMethod("getHeightmaps");
                Class<?> types = Class.forName("net.minecraft.world.level.levelgen.Heightmap$Types");
                motionBlocking = types.getField("MOTION_BLOCKING_NO_LEAVES").get(null);
                Class<?> heightmap = Class.forName("net.minecraft.world.level.levelgen.Heightmap");
                Method getIndex = heightmap.getDeclaredMethod("getIndex", int.class, int.class);
                getIndex.setAccessible(true);
                int oneZero = (Integer) getIndex.invoke(null, 1, 0);
                int zeroOne = (Integer) getIndex.invoke(null, 0, 1);
                if (oneZero == 1 && zeroOne == 16) {
                    indexMode = 0;
                } else if (oneZero == 16 && zeroOne == 1) {
                    indexMode = 1;
                }
            } catch (Throwable ignored) {
                // Unknown layout: fall back to the live height map.
            }
            GET_HEIGHTMAPS = getHeightmaps;
            MOTION_BLOCKING_NO_LEAVES = motionBlocking;
            INDEX_MODE = indexMode;
        }

        private PacketHeights() {
        }

        /** The raw {@code MOTION_BLOCKING_NO_LEAVES} array, or null if the packet has none. */
        static long[] raw(Object chunkData) {
            if (chunkData == null || GET_HEIGHTMAPS == null || MOTION_BLOCKING_NO_LEAVES == null) {
                return null;
            }
            try {
                Object value = GET_HEIGHTMAPS.invoke(chunkData);
                if (!(value instanceof Map<?, ?> heightmaps)) {
                    return null;
                }
                Object raw = heightmaps.get(MOTION_BLOCKING_NO_LEAVES);
                return raw instanceof long[] array ? array : null;
            } catch (Throwable throwable) {
                return null;
            }
        }

        /** Whether the raw array is long enough for all 256 packed columns. */
        static boolean fits(long[] raw, int bits) {
            if (raw == null || bits <= 0) {
                return false;
            }
            int valuesPerLong = 64 / bits;
            if (valuesPerLong <= 0) {
                return false;
            }
            int needed = (256 + valuesPerLong - 1) / valuesPerLong;
            return raw.length >= needed;
        }

        /** The bits per packed height value, or -1 when this world height cannot be packed. */
        static int bits(int worldHeight) {
            if (worldHeight <= 0) {
                return -1;
            }
            long needed = (long) worldHeight + 1L;
            int bits = 0;
            while (bits < 32 && (1L << bits) < needed) {
                bits++;
            }
            return bits >= 32 ? -1 : bits;
        }

        /** Decodes one packed height; -1 if the array is too short for the layout. */
        static int decode(long[] raw, int bits, int x, int z) {
            if (bits <= 0 || INDEX_MODE < 0) {
                return -1;
            }
            int index = INDEX_MODE == 0 ? x + z * 16 : x * 16 + z;
            int valuesPerLong = 64 / bits;
            int longIndex = index / valuesPerLong;
            if (longIndex < 0 || longIndex >= raw.length) {
                return -1;
            }
            int shift = (index % valuesPerLong) * bits;
            long mask = (1L << bits) - 1L;
            return (int) ((raw[longIndex] >>> shift) & mask);
        }
    }
}
