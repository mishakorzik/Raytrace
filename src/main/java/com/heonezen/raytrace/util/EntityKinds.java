package com.heonezen.raytrace.util;

/**
 * Classifies server entity classes. The classification walks the class hierarchy by simple
 * name, so it does not depend on the package layout, which moved between Minecraft versions
 * (for example the minecart classes). Results are cached per class.
 */
public final class EntityKinds {

    private static final Class<?> PLAYER = load("net.minecraft.world.entity.player.Player");
    private static final Class<?> LIVING = load("net.minecraft.world.entity.LivingEntity");

    private static final ClassValue<EntityKind> CACHE = new ClassValue<>() {
        @Override
        protected EntityKind computeValue(Class<?> type) {
            return classify(type);
        }
    };

    private EntityKinds() {
    }
    /** The kind of a server entity class (an entity handle's class or a spawn packet's base class). */
    public static EntityKind of(Class<?> nmsClass) {
        return nmsClass == null ? EntityKind.OTHER : CACHE.get(nmsClass);
    }
    private static EntityKind classify(Class<?> type) {
        if (PLAYER != null && PLAYER.isAssignableFrom(type)) {
            return EntityKind.PLAYER;
        }
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            switch (current.getSimpleName()) {
                // An armor stand is a living entity in the server, but it is furniture to players.
                case "ArmorStand", "HangingEntity", "ItemFrame", "GlowItemFrame", "Painting", "LeashFenceKnotEntity", "Display" -> {
                    return EntityKind.DECOR;
                }
                case "VehicleEntity", "AbstractMinecart", "AbstractMinecartContainer", "Minecart", "AbstractBoat", "AbstractChestBoat", "Boat", "ChestBoat", "Raft", "ChestRaft" -> {
                    return EntityKind.VEHICLE;
                }
                case "ItemEntity" -> {
                    return EntityKind.ITEM;
                }
                default -> {
                    // keep walking up
                }
            }
        }
        return LIVING != null && LIVING.isAssignableFrom(type) ? EntityKind.MOB : EntityKind.OTHER;
    }
    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            return null;
        }
    }
}
