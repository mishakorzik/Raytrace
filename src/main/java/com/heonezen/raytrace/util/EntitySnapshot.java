package com.heonezen.raytrace.util;

import org.bukkit.entity.Entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Immutable entity data for cross-region checks. The raw NMS handle is read without
 * {@code CraftEntity#getHandle()}, which is tick-thread guarded on Canvas. All server members
 * are bound once as method handles, so a snapshot costs a handful of direct calls instead of a
 * dozen reflective ones.
 */
public record EntitySnapshot(UUID uuid, int entityId, double x, double y, double z, double eyeY, double width, double height, EntityKind kind, boolean valid) {

    private static final MethodType OBJ_TO_OBJ = MethodType.methodType(Object.class, Object.class);
    private static final MethodType OBJ_TO_INT = MethodType.methodType(int.class, Object.class);
    private static final MethodType OBJ_TO_DOUBLE = MethodType.methodType(double.class, Object.class);
    private static final MethodType OBJ_TO_BOOL = MethodType.methodType(boolean.class, Object.class);

    private static final Class<?> NMS_ENTITY = load("net.minecraft.world.entity.Entity");

    private static final MethodHandle GET_ID = handle(NMS_ENTITY, OBJ_TO_INT, "getId");
    private static final MethodHandle GET_UUID = handle(NMS_ENTITY, OBJ_TO_OBJ, "getUUID");
    private static final MethodHandle GET_X = handle(NMS_ENTITY, OBJ_TO_DOUBLE, "getX");
    private static final MethodHandle GET_Y = handle(NMS_ENTITY, OBJ_TO_DOUBLE, "getY");
    private static final MethodHandle GET_Z = handle(NMS_ENTITY, OBJ_TO_DOUBLE, "getZ");
    private static final MethodHandle GET_EYE_Y = handle(NMS_ENTITY, OBJ_TO_DOUBLE, "getEyeY");
    private static final MethodHandle GET_WIDTH = handle(NMS_ENTITY, OBJ_TO_DOUBLE, "getBbWidth", "getWidth");
    private static final MethodHandle GET_HEIGHT = handle(NMS_ENTITY, OBJ_TO_DOUBLE, "getBbHeight", "getHeight");
    private static final MethodHandle IS_REMOVED = handle(NMS_ENTITY, OBJ_TO_BOOL, "isRemoved");
    private static final MethodHandle IS_ALIVE = handle(NMS_ENTITY, OBJ_TO_BOOL, "isAlive");

    private static final boolean AVAILABLE = NMS_ENTITY != null && GET_ID != null && GET_UUID != null && GET_X != null && GET_Y != null && GET_Z != null && GET_WIDTH != null && GET_HEIGHT != null;

    /** Marks a class that has no raw-handle accessor, since a ClassValue should not hold null. */
    private static final MethodHandle NONE = MethodHandles.constant(Object.class, null);

    private static final ClassValue<MethodHandle> RAW_METHOD = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("getHandleRaw");
                return MethodHandles.publicLookup().unreflect(method).asType(OBJ_TO_OBJ);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return NONE;
            }
        }
    };

    private static final ClassValue<Field> RAW_FIELD = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            if (NMS_ENTITY == null) {
                return null;
            }
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Field candidate : current.getDeclaredFields()) {
                    if (candidate.getType() == NMS_ENTITY) {
                        try {
                            candidate.setAccessible(true);
                            return candidate;
                        } catch (RuntimeException ignored) {
                            // try the next candidate
                        }
                    }
                }
            }
            return null;
        }
    };

    /** The server-side entity object behind a Bukkit entity, or null. */
    public static Object handleOf(Entity entity) {
        if (entity == null || NMS_ENTITY == null) {
            return null;
        }
        try {
            Class<?> type = entity.getClass();
            MethodHandle method = RAW_METHOD.get(type);
            if (method != NONE) {
                return (Object) method.invokeExact((Object) entity);
            }
            Field field = RAW_FIELD.get(type);
            return field == null ? null : field.get(entity);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** Reads a snapshot, or returns {@code null} when the server classes do not match. */
    public static EntitySnapshot read(Entity entity) {
        if (!AVAILABLE || entity == null) {
            return null;
        }
        Object handle = handleOf(entity);
        if (handle == null || !NMS_ENTITY.isInstance(handle)) {
            return null;
        }
        return readHandle(handle);
    }

    /** Reads a snapshot from the server-side entity object directly. */
    public static EntitySnapshot readHandle(Object handle) {
        if (!AVAILABLE || handle == null) {
            return null;
        }
        try {
            UUID uuid = (UUID) (Object) GET_UUID.invokeExact(handle);
            int id = (int) GET_ID.invokeExact(handle);
            double x = (double) GET_X.invokeExact(handle);
            double y = (double) GET_Y.invokeExact(handle);
            double z = (double) GET_Z.invokeExact(handle);
            double height = (double) GET_HEIGHT.invokeExact(handle);
            double width = (double) GET_WIDTH.invokeExact(handle);
            double eyeY = GET_EYE_Y != null ? (double) GET_EYE_Y.invokeExact(handle) : y + Math.max(0.2, height) * 0.85;
            boolean removed = IS_REMOVED != null && (boolean) IS_REMOVED.invokeExact(handle);
            boolean alive = IS_ALIVE == null || (boolean) IS_ALIVE.invokeExact(handle);
            return new EntitySnapshot(uuid, id, x, y, z, eyeY, width, height, EntityKinds.of(handle.getClass()), !removed && alive);
        } catch (Throwable throwable) {
            return null;
        }
    }
    public boolean player() {
        return kind == EntityKind.PLAYER;
    }
    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            return null;
        }
    }
    private static MethodHandle handle(Class<?> owner, MethodType generic, String... names) {
        if (owner == null) {
            return null;
        }
        for (String name : names) {
            try {
                Method method = owner.getMethod(name);
                return MethodHandles.publicLookup().unreflect(method).asType(generic);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // try the next name
            }
        }
        return null;
    }
}
