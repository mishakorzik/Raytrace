package com.heonezen.raytrace.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty packet layer. Server internals are accessed reflectively so the plugin stays a single
 * jar; a failed lookup disables only the affected feature. Handlers sit before the server's
 * encoder, which still produces the wire format.
 *
 * <p>Every reflective member is resolved once and cached, including the "no such member"
 * answer. The outgoing handler runs for every packet of every player, so an uncached
 * {@code getDeclaredField} (and the exception thrown when it misses) used to be a large share
 * of the plugin's CPU time.
 */
public final class NativeProtocol {

    private NativeProtocol() {}

    /** A failed lookup. It carries no stack trace: these are expected and caught constantly. */
    private static final class MissingMember extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        MissingMember(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /** Wraps a reflective failure without capturing a stack trace for the common case. */
    private static IllegalStateException failure(Throwable cause) {
        return new IllegalStateException(cause);
    }

    private record MemberKey(Class<?> owner, String name) {
    }

    private static final Field NO_FIELD;

    static {
        try {
            NO_FIELD = NativeProtocol.class.getDeclaredField("NO_FIELD");
        } catch (NoSuchFieldException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final Method NO_METHOD;

    static {
        try {
            NO_METHOD = NativeProtocol.class.getDeclaredMethod("failure", Throwable.class);
        } catch (NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final ConcurrentHashMap<MemberKey, Field> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<MemberKey, Method> METHODS = new ConcurrentHashMap<>();

    private static Field field(Class<?> owner, String name) {
        MemberKey key = new MemberKey(owner, name);
        Field cached = FIELDS.get(key);
        if (cached == null) {
            cached = resolveField(owner, name);
            FIELDS.put(key, cached);
        }
        if (cached == NO_FIELD) {
            throw new MissingMember("No field '" + name + "' on " + owner.getName());
        }
        return cached;
    }

    private static Field resolveField(Class<?> owner, String name) {
        Class<?> current = owner;
        while (current != null) {
            try {
                Field found = current.getDeclaredField(name);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (RuntimeException exception) {
                return NO_FIELD;
            }
        }
        return NO_FIELD;
    }

    public static Object get(Object target, String name) {
        Field field = field(target.getClass(), name);
        try {
            return field.get(target);
        } catch (IllegalAccessException exception) {
            throw failure(exception);
        }
    }

    public static void set(Object target, String name, Object value) {
        Field field = field(target.getClass(), name);
        try {
            field.set(target, value);
        } catch (IllegalAccessException exception) {
            throw failure(exception);
        }
    }

    public static Object staticField(String className, String name) {
        try {
            Field found = Class.forName(className).getDeclaredField(name);
            found.setAccessible(true);
            return found.get(null);
        } catch (ReflectiveOperationException exception) {
            throw failure(exception);
        }
    }

    private static final Class<?>[] NO_TYPES = new Class<?>[0];

    private static Method zeroArgMethod(Class<?> owner, String name) {
        MemberKey key = new MemberKey(owner, name);
        Method cached = METHODS.get(key);
        if (cached == null) {
            try {
                cached = owner.getMethod(name);
                cached.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                cached = NO_METHOD;
            }
            METHODS.put(key, cached);
        }
        if (cached == NO_METHOD) {
            throw new MissingMember("No method '" + name + "()' on " + owner.getName());
        }
        return cached;
    }

    public static Object invoke(Object target, String name, Class<?>[] types, Object[] args) {
        try {
            Method method;
            if (types == null || types.length == 0) {
                method = zeroArgMethod(target.getClass(), name);
            } else {
                method = target.getClass().getMethod(name, types);
                method.setAccessible(true);
            }
            return method.invoke(target, args);
        } catch (ReflectiveOperationException exception) {
            throw failure(exception);
        }
    }

    /** Invokes a no-argument method by name. */
    public static Object invoke(Object target, String name) {
        return invoke(target, name, NO_TYPES, new Object[0]);
    }

    /** Invokes a static method by name. */
    public static Object invokeStatic(Class<?> owner, String name, Class<?>[] types, Object[] args) {
        try {
            Method method = owner.getMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (ReflectiveOperationException exception) {
            throw failure(exception);
        }
    }

    /**
     * Reads the first of several numeric accessors a class has, without throwing. Used for the
     * per-packet coordinate reads, where most candidate names do not exist.
     *
     * @return the value, or null when none of the names resolves to a number
     */
    public static Number readNumber(Object target, String... names) {
        Class<?> type = target.getClass();
        for (String name : names) {
            try {
                Method method = zeroArgMethod(type, name);
                Object value = method.invoke(target);
                if (value instanceof Number number) {
                    return number;
                }
            } catch (MissingMember ignored) {
                // not a method here
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // unreadable: try a field
            }
            try {
                Object value = field(type, name).get(target);
                if (value instanceof Number number) {
                    return number;
                }
            } catch (MissingMember ignored) {
                // not a field here either
            } catch (IllegalAccessException | RuntimeException ignored) {
                // next name
            }
        }
        return null;
    }

    /** The first non-null field of several candidate names, or null. */
    public static Object readFirstField(Object target, String... names) {
        Class<?> type = target.getClass();
        for (String name : names) {
            try {
                Object value = field(type, name).get(target);
                if (value != null) {
                    return value;
                }
            } catch (MissingMember ignored) {
                // try the next name
            } catch (IllegalAccessException | RuntimeException ignored) {
                // try the next name
            }
        }
        return null;
    }

    private static final Object NO_INT_FIELD = new Object();
    private static final ConcurrentHashMap<Class<?>, Object> INT_ID_FIELDS = new ConcurrentHashMap<>();

    /**
     * Reads the integer entity id of a packet. The accessor is resolved once per packet class.
     *
     * @return the id, or null if the packet has none of the given int fields
     */
    public static Integer readEntityId(Object packet, String... names) {
        Class<?> type = packet.getClass();
        Object cached = INT_ID_FIELDS.get(type);
        if (cached == null) {
            cached = NO_INT_FIELD;
            for (String name : names) {
                Field found = resolveField(type, name);
                if (found != NO_FIELD && (found.getType() == int.class || found.getType() == Integer.class)) {
                    cached = found;
                    break;
                }
            }
            INT_ID_FIELDS.put(type, cached);
        }
        if (cached == NO_INT_FIELD) {
            return null;
        }
        try {
            return ((Field) cached).getInt(packet);
        } catch (IllegalAccessException | RuntimeException exception) {
            return null;
        }
    }

    /** The player's Netty channel. Paper does not expose it, so reflection is required. */
    public static Channel channelOf(Object handle) {
        Object listener = get(handle, "connection");
        Object connection = get(listener, "connection");
        return (Channel) get(connection, "channel");
    }

    /** The server's network connection object of a player (the thing packets are sent through). */
    public static Object connectionOf(Object handle) {
        Object listener = get(handle, "connection");
        return get(listener, "connection");
    }

    /** Adds the handler at the end of the pipeline, before the server's encoder. */
    public static void install(Channel channel, ChannelDuplexHandler handler) {
        if (channel == null) {
            return;
        }
        // Replacing an old handler makes a plugin reload safe: the new engine must not leave
        // the previous engine's interceptor in the pipeline.
        if (channel.pipeline().get("raytrace") != null) {
            channel.pipeline().remove("raytrace");
        }
        channel.pipeline().addLast("raytrace", handler);
    }

    /** Removes this plugin's handler from a channel, if present. */
    public static void uninstall(Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            if (channel.pipeline().get("raytrace") != null) {
                channel.pipeline().remove("raytrace");
            }
        } catch (RuntimeException ignored) {
            // The channel is already closing; there is nothing left to remove.
        }
    }

    /** The simple name of a packet class, e.g. {@code ClientboundAddEntityPacket}. */
    public static String packetName(Object packet) {
        String name = packet.getClass().getSimpleName();
        return name.isEmpty() ? packet.getClass().getName() : name;
    }

    private static volatile MethodHandle sendHandle;
    private static volatile Class<?> sendConnectionClass;

    /** Sends a packet through the server's own connection, so the server does the encoding. */
    public static void send(Object connection, Object packet) {
        try {
            Method method = connection.getClass().getMethod("send",
                    Class.forName("net.minecraft.network.protocol.Packet"));
            MethodHandle handle = sendHandle;
            if (handle == null || sendConnectionClass != connection.getClass()) {
                handle = MethodHandles.publicLookup().unreflect(method)
                        .asType(MethodType.methodType(void.class, Object.class, Object.class));
                sendConnectionClass = connection.getClass();
                sendHandle = handle;
            }
            handle.invokeExact(connection, packet);
        } catch (Throwable throwable) {
            throw failure(throwable);
        }
    }

    private static volatile Constructor<?> removeEntitiesConstructor;

    /** Builds a remove-entities packet for these ids. */
    public static Object newRemoveEntities(int... ids) {
        try {
            Constructor<?> constructor = removeEntitiesConstructor;
            if (constructor == null) {
                Class<?> packetClass = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
                constructor = packetClass.getConstructor(int[].class);
                removeEntitiesConstructor = constructor;
            }
            return constructor.newInstance((Object) ids);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static volatile Constructor<?> bundleConstructor;

    /**
     * The packets inside a bundle. The server sends an entity's spawn together with its data,
     * equipment and attributes as one bundle, and the Netty handler sees only the bundle: the
     * individual packets are split off further down the pipeline. Without looking inside,
     * a spawn packet could never be held back.
     *
     * @return the sub-packets, or null if this server version does not expose them
     */
    public static List<Object> bundleSubPackets(Object bundle) {
        try {
            Object value;
            try {
                value = invoke(bundle, "subPackets");
            } catch (RuntimeException missing) {
                value = readFirstField(bundle, "packets");
            }
            if (!(value instanceof Iterable<?> iterable)) {
                return null;
            }
            List<Object> result = new ArrayList<>();
            for (Object packet : iterable) {
                result.add(packet);
            }
            return result;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** A new bundle of the same class holding these packets, or null if it cannot be built. */
    public static Object newBundle(Object template, List<Object> packets) {
        try {
            Constructor<?> constructor = bundleConstructor;
            if (constructor == null || constructor.getDeclaringClass() != template.getClass()) {
                constructor = template.getClass().getConstructor(Iterable.class);
                bundleConstructor = constructor;
            }
            return constructor.newInstance(packets);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    /** @return a problem rewriting bundles, or null */
    public static String bundleSupportProblem() {
        try {
            Class<?> bundle = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket");
            bundle.getConstructor(Iterable.class);
            if (!Iterable.class.isAssignableFrom(bundle.getMethod("subPackets").getReturnType())) {
                return "the bundle packet no longer exposes its packets as an Iterable";
            }
            return null;
        } catch (ClassNotFoundException exception) {
            // No bundle packet at all means nothing is ever bundled.
            return null;
        } catch (Throwable throwable) {
            return "the bundle packet could not be inspected (" + throwable + ")";
        }
    }

    /**
     * Block-change packets are broadcast to every tracking player as one shared object, so
     * masking builds a private copy instead of mutating it. Copies use the server's own packet
     * constructors.
     */
    private static volatile Constructor<?> blockUpdateConstructor;
    private static volatile Constructor<?> sectionUpdateConstructor;
    private static volatile Constructor<?> short2ObjectMapConstructor;
    private static volatile Method short2ObjectMapPut;
    private static volatile Method sectionPosOf;

    /**
     * Builds a private single-block-update packet for one player.
     *
     * @return the copy, or null if this server version cannot build one
     */
    public static Object copyBlockUpdate(Object packet, Object blockState) {
        try {
            Constructor<?> constructor = blockUpdateConstructor(packet.getClass());
            return constructor.newInstance(get(packet, "pos"), blockState);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Constructor<?> blockUpdateConstructor(Class<?> packetClass) throws ReflectiveOperationException {
        Constructor<?> cached = blockUpdateConstructor;
        if (cached != null && cached.getDeclaringClass() == packetClass) {
            return cached;
        }
        synchronized (NativeProtocol.class) {
            cached = blockUpdateConstructor;
            if (cached != null && cached.getDeclaringClass() == packetClass) {
                return cached;
            }
            Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos");
            Class<?> blockState = Class.forName("net.minecraft.world.level.block.state.BlockState");
            Constructor<?> constructor = packetClass.getConstructor(blockPos, blockState);
            blockUpdateConstructor = constructor;
            return constructor;
        }
    }

    private static void resolveShortMap() throws ReflectiveOperationException {
        if (short2ObjectMapConstructor == null || short2ObjectMapPut == null) {
            synchronized (NativeProtocol.class) {
                if (short2ObjectMapConstructor == null || short2ObjectMapPut == null) {
                    Class<?> mapClass = Class.forName("it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap");
                    short2ObjectMapConstructor = mapClass.getConstructor(short[].class, Object[].class);
                    short2ObjectMapPut = mapClass.getMethod("put", short.class, Object.class);
                }
            }
        }
    }

    /**
     * Builds a private section-update packet; the shared original is never modified.
     *
     * @param replacements the fake state per entry, or null to keep the original
     * @return the copy, or null if this server version cannot build one
     */
    public static Object copySectionBlocksUpdate(Object packet, short[] positions, Object[] states, Object[] replacements) {
        try {
            resolveShortMap();
            // The map constructor copies the arrays; cloning also protects the shared state array.
            Object values = states.clone();
            Object map = short2ObjectMapConstructor.newInstance(positions, values);
            for (int i = 0; i < replacements.length && i < positions.length; i++) {
                if (replacements[i] != null) {
                    short2ObjectMapPut.invoke(map, positions[i], replacements[i]);
                }
            }
            Constructor<?> constructor = sectionUpdateConstructor(packet.getClass());
            return constructor.newInstance(get(packet, "sectionPos"), map);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * Builds a section block-update packet from scratch, straight from server block states.
     * Used to send revealed terrain without a Bukkit {@code BlockData} object per block.
     *
     * @param positions section-relative positions, {@code (x << 8) | (z << 4) | y}
     * @return the packet, or null if this server version cannot build one
     */
    public static Object newSectionBlocksUpdate(int sectionX, int sectionY, int sectionZ, short[] positions, Object[] states, int count) {
        try {
            resolveShortMap();
            Method factory = sectionPosOf;
            if (factory == null) {
                Class<?> sectionPos = Class.forName("net.minecraft.core.SectionPos");
                factory = sectionPos.getMethod("of", int.class, int.class, int.class);
                sectionPosOf = factory;
            }
            short[] usedPositions = java.util.Arrays.copyOf(positions, count);
            Object[] usedStates = java.util.Arrays.copyOf(states, count);
            Object map = short2ObjectMapConstructor.newInstance(usedPositions, usedStates);
            Object section = factory.invoke(null, sectionX, sectionY, sectionZ);
            Class<?> packetClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket");
            return sectionUpdateConstructor(packetClass).newInstance(section, map);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Constructor<?> sectionUpdateConstructor(Class<?> packetClass) throws ReflectiveOperationException {
        Constructor<?> cached = sectionUpdateConstructor;
        if (cached != null && cached.getDeclaringClass() == packetClass) {
            return cached;
        }
        synchronized (NativeProtocol.class) {
            cached = sectionUpdateConstructor;
            if (cached != null && cached.getDeclaringClass() == packetClass) {
                return cached;
            }
            Class<?> sectionPos = Class.forName("net.minecraft.core.SectionPos");
            Class<?> mapInterface = Class.forName("it.unimi.dsi.fastutil.shorts.Short2ObjectMap");
            Constructor<?> constructor = packetClass.getConstructor(sectionPos, mapInterface);
            sectionUpdateConstructor = constructor;
            return constructor;
        }
    }

    /** Per-player outgoing handler; all decisions are made by the {@link Interceptor}. */
    public static final class OutboundHandler extends ChannelDuplexHandler {

        private final Interceptor interceptor;
        private final Object owner;

        /**
         * @param owner an opaque per-player object handed back to the interceptor with every
         *              packet, so it needs no lookup to find the player's state
         */
        public OutboundHandler(Interceptor interceptor, Object owner) {
            this.interceptor = interceptor;
            this.owner = owner;
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            if (message != null) {
                Object forwarded = interceptor.intercept(owner, message);
                if (forwarded == null) {
                    // The interceptor consumed the packet.
                    promise.setSuccess();
                    return;
                }
                if (forwarded != message) {
                    // Replace the shared broadcast packet with this player's copy.
                    // context.write starts at the previous outbound handler, so the copy is
                    // not intercepted again.
                    context.write(forwarded, promise);
                    return;
                }
            }
            super.write(context, message, promise);
        }
    }

    /** Decides what happens to one outgoing packet. */
    public interface Interceptor {
        /**
         * @return the packet to forward (the original or a per-player copy), or {@code null} if
         *         the packet must not reach this client
         */
        Object intercept(Object owner, Object packet);
    }

    /** Lazily resolved server chunk classes; the plugin never encodes a section itself. */
    private static final class ChunkFormat {

        private static volatile ChunkFormat instance;

        final Method sectionRead;
        final Method sectionWrite;
        final Method sectionRecalc;
        final Method getStates;
        final Method get;
        final Method getAndSet;
        /** Direct handles for the two calls the per-block loops make thousands of times. */
        final MethodHandle getHandle;
        final MethodHandle getAndSetHandle;
        final java.lang.reflect.Constructor<?> sectionConstructor;
        final java.lang.reflect.Constructor<?> bufferConstructor;
        final java.lang.reflect.Constructor<?> singleValueContainer;
        final java.lang.reflect.Constructor<?> legacySingleValueContainer;
        final Object legacyBlockRegistry;
        final Object legacyBiomeRegistry;
        final Field strategyField;
        final Method readUnsignedShort;
        final boolean requiresFactory;

        private ChunkFormat() throws ReflectiveOperationException {
            Class<?> containerClass = Class.forName("net.minecraft.world.level.chunk.PalettedContainer");
            Class<?> sectionClass = Class.forName("net.minecraft.world.level.chunk.LevelChunkSection");
            Class<?> bufferClass = Class.forName("net.minecraft.network.FriendlyByteBuf");
            Class<?> strategyClass = strategyClass();
            sectionRead = sectionClass.getMethod("read", bufferClass);
            sectionWrite = sectionClass.getMethod("write", bufferClass);
            sectionRecalc = sectionClass.getMethod("recalcBlockCounts");
            getStates = sectionClass.getMethod("getStates");
            get = containerClass.getMethod("get", int.class, int.class, int.class);
            getAndSet = containerClass.getMethod("getAndSet", int.class, int.class, int.class, Object.class);
            MethodHandle directGet = null;
            MethodHandle directGetAndSet = null;
            try {
                directGet = MethodHandles.publicLookup().unreflect(get).asType(MethodType.methodType(
                        Object.class, Object.class, int.class, int.class, int.class));
                directGetAndSet = MethodHandles.publicLookup().unreflect(getAndSet).asType(MethodType.methodType(
                        Object.class, Object.class, int.class, int.class, int.class, Object.class));
            } catch (IllegalAccessException | RuntimeException ignored) {
                // The reflective Method is used instead.
            }
            getHandle = directGet;
            getAndSetHandle = directGetAndSet;
            bufferConstructor = bufferClass.getConstructor(ByteBuf.class);
            strategyField = containerClass.getDeclaredField("strategy");
            strategyField.setAccessible(true);
            readUnsignedShort = bufferClass.getMethod("readUnsignedShort");

            // 1.21.5+ builds sections from a PalettedContainerFactory; 1.21.4 and older use
            // the biome registry directly, with no factory class.
            java.lang.reflect.Constructor<?> modernSection = null;
            java.lang.reflect.Constructor<?> modernSingle = null;
            java.lang.reflect.Constructor<?> oldSection = null;
            java.lang.reflect.Constructor<?> oldSingle = null;
            Object blockRegistry = null;
            Object biomeRegistry = null;
            try {
                Class<?> factoryClass = Class.forName(
                        "net.minecraft.world.level.chunk.PalettedContainerFactory");
                modernSection = sectionClass.getConstructor(factoryClass);
                modernSingle = containerClass.getConstructor(Object.class, strategyClass);
            } catch (Throwable modernFailure) {
                // Resolve the constructors by parameter type, not by class name: the plugin
                // remapper can rewrite internal names, which broke this on 1.21.4.
                blockRegistry = blockStateRegistry();
                biomeRegistry = biomeRegistry();
                for (java.lang.reflect.Constructor<?> candidate : sectionClass.getConstructors()) {
                    Class<?>[] parameters = candidate.getParameterTypes();
                    if (parameters.length == 1 && parameters[0].isInstance(biomeRegistry)) {
                        oldSection = candidate;
                        break;
                    }
                }
                for (java.lang.reflect.Constructor<?> candidate : containerClass.getConstructors()) {
                    Class<?>[] parameters = candidate.getParameterTypes();
                    if (parameters.length == 3 && parameters[0].isInstance(blockRegistry) && parameters[1] == Object.class && parameters[2] == strategyClass) {
                        oldSingle = candidate;
                        break;
                    }
                }
                if (oldSection == null || oldSingle == null) {
                    throw new NoSuchMethodException("the legacy LevelChunkSection / PalettedContainer constructors were not found");
                }
            }
            if (modernSection != null) {
                sectionConstructor = modernSection;
                singleValueContainer = modernSingle;
                legacySingleValueContainer = null;
                legacyBlockRegistry = null;
                legacyBiomeRegistry = null;
                requiresFactory = true;
            } else {
                sectionConstructor = oldSection;
                singleValueContainer = null;
                legacySingleValueContainer = oldSingle;
                legacyBlockRegistry = blockRegistry;
                legacyBiomeRegistry = biomeRegistry;
                requiresFactory = false;
            }
        }

        /** The container strategy class; its package moved in newer builds. */
        private static Class<?> strategyClass() throws ClassNotFoundException {
            try {
                return Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Strategy");
            } catch (ClassNotFoundException ignored) {
                return Class.forName("net.minecraft.world.level.chunk.Strategy");
            }
        }

        /** A new section in the server's own format, ready to be read from a packet buffer. */
        Object newSection(Object factory) throws ReflectiveOperationException {
            return sectionConstructor.newInstance(requiresFactory ? factory : legacyBiomeRegistry);
        }

        /** A one-value block-state container, the smallest encoding of "all of this is stone". */
        Object newSingleValueContainer(Object fake, Object strategy) throws ReflectiveOperationException {
            if (requiresFactory) {
                return singleValueContainer.newInstance(fake, strategy);
            }
            return legacySingleValueContainer.newInstance(legacyBlockRegistry, fake, strategy);
        }

        private static Object blockStateRegistry() throws ReflectiveOperationException {
            Class<?> blocks = Class.forName("net.minecraft.world.level.block.Block");
            java.lang.reflect.Field field = blocks.getField("BLOCK_STATE_REGISTRY");
            return field.get(null);
        }

        private static Object biomeRegistry() throws ReflectiveOperationException {
            Object craftServer = org.bukkit.Bukkit.getServer();
            Object minecraftServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            Object registryAccess = minecraftServer.getClass().getMethod("registryAccess").invoke(minecraftServer);
            Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey");
            Class<?> registries = Class.forName("net.minecraft.core.registries.Registries");
            Object biomeKey = registries.getField("BIOME").get(null);
            Class<?> registryAccessClass = Class.forName("net.minecraft.core.RegistryAccess");
            return registryAccessClass.getMethod("lookupOrThrow", resourceKey).invoke(registryAccess, biomeKey);
        }

        static ChunkFormat get() {
            ChunkFormat current = instance;
            if (current != null) {
                return current;
            }
            synchronized (ChunkFormat.class) {
                if (instance == null) {
                    try {
                        instance = new ChunkFormat();
                    } catch (Throwable throwable) {
                        // A broken server must disable chunk masking, not crash the plugin.
                        return null;
                    }
                }
                return instance;
            }
        }
    }

    /**
     * Null when the server's chunk format is available, otherwise a short problem description.
     * A broken chunk masking must be loud, not silent.
     */
    public static String chunkFormatProblem() {
        return ChunkFormat.get() == null ? "the server's chunk classes (LevelChunkSection / PalettedContainer) could not be found" : null;
    }

    /**
     * Whether sections need the engine's {@code PalettedContainerFactory}. On 1.21.4 and older
     * a null factory is normal, not a startup failure.
     */
    public static boolean palettedContainerFactoryRequired() {
        ChunkFormat format = ChunkFormat.get();
        return format != null && format.requiresFactory;
    }

    /** Creates a fresh section in the server's format, for decoding masked chunks in diagnostics. */
    public static Object newSectionForRead(Object factory) {
        ChunkFormat format = ChunkFormat.get();
        if (format == null || (format.requiresFactory && factory == null)) {
            return null;
        }
        try {
            return format.newSection(factory);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * Checks that block-update packets and their per-player copy paths exist on this server.
     * They are shared between players, so a private copy must be possible.
     */
    public static String blockUpdateFormatProblem() {
        try {
            Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket");
            packet.getDeclaredField("sectionPos");
            if (packet.getDeclaredField("positions").getType() != short[].class) {
                return "the section block-update packet no longer uses a short[] position array";
            }
            Class<?> states = packet.getDeclaredField("states").getType();
            if (!states.isArray() || states.getComponentType().isPrimitive()) {
                return "the section block-update packet no longer uses an object state array";
            }
            Class<?> sectionPos = Class.forName("net.minecraft.core.SectionPos");
            sectionPos.getMethod("minBlockX");
            sectionPos.getMethod("minBlockY");
            sectionPos.getMethod("minBlockZ");

            Class<?> mapInterface = Class.forName("it.unimi.dsi.fastutil.shorts.Short2ObjectMap");
            packet.getConstructor(sectionPos, mapInterface);
            Class<?> mapClass = Class.forName("it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap");
            mapClass.getConstructor(short[].class, Object[].class);
            mapClass.getMethod("put", short.class, Object.class);

            Class<?> blockUpdate = Class.forName("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket");
            blockUpdate.getDeclaredField("pos");
            blockUpdate.getConstructor(Class.forName("net.minecraft.core.BlockPos"),
                    Class.forName("net.minecraft.world.level.block.state.BlockState"));
            try {
                Class<?> transientBlock = Class.forName("net.minecraft.network.protocol.game.ClientboundAddTransientBlockPacket");
                transientBlock.getDeclaredField("pos");
                transientBlock.getConstructor(Class.forName("net.minecraft.core.BlockPos"),
                        Class.forName("net.minecraft.world.level.block.state.BlockState"));
            } catch (ClassNotFoundException ignored) {
                // Older versions do not have this packet.
            }
            return null;
        } catch (Throwable throwable) {
            return "the block-update packet copies could not be prepared (" + throwable + ")";
        }
    }

    /** @return a problem reading entity spawn/remove packets, or null */

    public static String entitySpawnSupportProblem() {
        try {
            Class<?> add = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
            add.getMethod("getX");
            add.getMethod("getY");
            add.getMethod("getZ");
            Class<?> type = add.getMethod("getType").getReturnType();
            type.getMethod("getBaseClass");
            type.getMethod("getWidth");
            type.getMethod("getHeight");
            Class<?> remove = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
            Method ids = null;
            for (String name : new String[]{"getEntityIds", "entityIds"}) {
                try {
                    ids = remove.getMethod(name);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // Some versions expose a record accessor instead of the bean-style getter.
                }
            }
            if (ids == null) {
                return "the remove-entities packet has neither getEntityIds() nor entityIds()";
            }
            ids.getReturnType().getMethod("toIntArray");
            Class.forName("net.minecraft.world.entity.LivingEntity");
            Class.forName("net.minecraft.world.entity.player.Player");
            return null;
        } catch (Throwable throwable) {
            return "the entity spawn/remove packet shape could not be read (" + throwable + ")";
        }
    }

    /** @return a problem reading effect-packet coordinates, or null */

    public static String effectPositionSupportProblem() {
        String[] packets = {
                "ClientboundSoundPacket",
                "ClientboundLevelParticlesPacket",
                "ClientboundExplodePacket",
                "ClientboundLevelEventPacket",
                "ClientboundBlockDestructionPacket",
                "ClientboundBlockEventPacket",
                "ClientboundBlockEntityDataPacket"
        };
        for (String name : packets) {
            try {
                Class<?> packet = Class.forName("net.minecraft.network.protocol.game." + name);
                if (!hasReadablePosition(packet)) {
                    return "the " + name + " no longer exposes readable coordinates";
                }
            } catch (ClassNotFoundException exception) {
                return "the " + name + " is missing from this server version";
            }
        }
        return null;
    }

    private static boolean hasReadablePosition(Class<?> packet) {
        if (hasCoordinates(packet)) {
            return true;
        }
        for (String name : new String[]{"center", "pos", "position"}) {
            try {
                if (hasCoordinates(field(packet, name).getType())) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Not this field - try the next one.
            }
        }
        return false;
    }

    private static boolean hasCoordinates(Class<?> owner) {
        return hasCoordinate(owner, "getX", "x") && hasCoordinate(owner, "getY", "y") && hasCoordinate(owner, "getZ", "z");
    }

    private static boolean hasCoordinate(Class<?> owner, String getter, String field) {
        return hasNumberMethod(owner, getter) || hasNumberMethod(owner, field) || hasNumberField(owner, field);
    }

    private static boolean hasNumberMethod(Class<?> owner, String name) {
        try {
            return isNumber(owner.getMethod(name).getReturnType());
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    private static boolean hasNumberField(Class<?> owner, String name) {
        try {
            return isNumber(field(owner, name).getType());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isNumber(Class<?> type) {
        return type == byte.class || type == short.class || type == int.class || type == long.class || type == float.class || type == double.class || Number.class.isAssignableFrom(type);
    }

    /** The entity ids of a remove-entities packet, using either accessor name. */
    public static int[] removeEntityIds(Object packet) {
        Object ids = null;
        RuntimeException last = null;
        for (String name : new String[]{"getEntityIds", "entityIds"}) {
            try {
                ids = invoke(packet, name, new Class<?>[0], new Object[0]);
                break;
            } catch (RuntimeException exception) {
                last = exception;
            }
        }
        if (ids == null) {
            throw new IllegalStateException("No entity id accessor on " + packet.getClass().getName(), last);
        }
        return (int[]) invoke(ids, "toIntArray", new Class<?>[0], new Object[0]);
    }

    /** Blocks this viewer has already earned; they stay unmasked. */
    @FunctionalInterface
    public interface BlockVisibility {
        boolean isVisible(int x, int y, int z);

        /**
         * Whether any block of the box (inclusive corners) is earned. Implementations backed by a
         * cell grid override this with a check per cell instead of per block.
         */
        default boolean anyInBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        if (isVisible(x, y, z)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    /**
     * Rewrites the encoded chunk snapshot: masked blocks become fake, earned blocks stay real.
     *
     * @return true if the packet was changed
     */
    public static boolean maskChunkData(Object packet, Object factory, int sectionCount, int minY, int[] ceilings, int[] oreCeilings, java.util.function.IntFunction<Object> fakeStateFor, int chunkX, int chunkZ, BlockVisibility visible, java.util.function.Predicate<Object> sensitiveState) {
        try {
            ChunkFormat format = ChunkFormat.get();
            if (format == null || (format.requiresFactory && factory == null)) {
                return false;
            }
            Object chunkData = get(packet, "chunkData");
            byte[] buffer = (byte[]) get(chunkData, "buffer");
            byte[] masked = maskChunkBuffer(format, buffer, factory, sectionCount, minY, ceilings, oreCeilings, fakeStateFor, chunkX, chunkZ, visible, sensitiveState);
            if (masked == null) {
                return false;
            }
            set(chunkData, "buffer", masked);
            filterBlockEntities(chunkData, oreCeilings != null ? oreCeilings : ceilings, chunkX, chunkZ, visible);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** Drops unearned block entities below the ceiling; the client would still draw them. */
    private static void filterBlockEntities(Object chunkData, int[] ceilings, int chunkX, int chunkZ, BlockVisibility visible) {
        try {
            Object value = get(chunkData, "blockEntitiesData");
            if (!(value instanceof java.util.List<?> entities)) {
                return;
            }
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
            java.util.Iterator<?> iterator = entities.iterator();
            while (iterator.hasNext()) {
                Object info = iterator.next();
                // Position is packed as section-relative X/Z plus absolute Y.
                int packed = (int) get(info, "packedXZ");
                int y = (int) get(info, "y");
                int x = (packed >> 4) & 15;
                int z = packed & 15;
                if (y < ceilings[x * 16 + z] && (visible == null || !visible.isVisible(baseX + x, y, baseZ + z))) {
                    iterator.remove();
                }
            }
        } catch (Throwable throwable) {
            // Leave the block entities alone; a leftover one is better than a broken packet.
        }
    }

    /** Masks raw chunk bytes; also used by the self-check. */
    public static byte[] maskChunkBuffer(Object factory, byte[] buffer, int sectionCount, int minY, int[] ceilings, java.util.function.IntFunction<Object> fakeStateFor) {
        return maskChunkBuffer(factory, buffer, sectionCount, minY, ceilings, fakeStateFor, 0, 0, null);
    }

    /** Same as above, with chunk coordinates and the viewer's already-earned blocks. */
    public static byte[] maskChunkBuffer(Object factory, byte[] buffer, int sectionCount, int minY, int[] ceilings, java.util.function.IntFunction<Object> fakeStateFor, int chunkX, int chunkZ, BlockVisibility visible) {
        return maskChunkBuffer(factory, buffer, sectionCount, minY, ceilings, ceilings, fakeStateFor, chunkX, chunkZ, visible, null);
    }

    /** Rewrites hidden sections with the server's reader/writer and recalculates block counts. */
    public static byte[] maskChunkBuffer(Object factory, byte[] buffer, int sectionCount, int minY, int[] ceilings, int[] oreCeilings, java.util.function.IntFunction<Object> fakeStateFor, int chunkX, int chunkZ, BlockVisibility visible, java.util.function.Predicate<Object> sensitiveState) {
        ChunkFormat format = ChunkFormat.get();
        if (format == null || (format.requiresFactory && factory == null) || buffer == null) {
            return null;
        }
        return maskChunkBuffer(format, buffer, factory, sectionCount, minY, ceilings, oreCeilings, fakeStateFor, chunkX, chunkZ, visible, sensitiveState);
    }

    private static byte[] maskChunkBuffer(ChunkFormat format, byte[] buffer, Object factory, int sectionCount, int minY, int[] ceilings, int[] oreCeilings, java.util.function.IntFunction<Object> fakeStateFor, int chunkX, int chunkZ, BlockVisibility visible, java.util.function.Predicate<Object> sensitiveState) {
        Object in;
        Object out;
        ByteBuf target = Unpooled.buffer(buffer.length + 64);
        ByteBuf source = Unpooled.wrappedBuffer(buffer);
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        try {
            in = format.bufferConstructor.newInstance(source);
            out = format.bufferConstructor.newInstance(target);
        } catch (ReflectiveOperationException exception) {
            target.release();
            return null;
        }
        try {
            for (int section = 0; section < sectionCount; section++) {
                int baseY = minY + section * 16;
                Object chunkSection = format.newSection(factory);
                int sectionStart = source.readerIndex();
                format.sectionRead.invoke(chunkSection, in);
                int sectionEnd = source.readerIndex();

                // Masked range of each column in this section. Below maskTop everything is fake;
                // between maskTop and oreTop only ores, containers and similar blocks are fake.
                int[] maskTop = new int[256];
                int[] oreTop = new int[256];
                boolean fullyMasked = true;
                boolean anyMasked = false;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int index = x * 16 + z;
                        int top = Math.min(ceilings[index], baseY + 16);
                        if (top < baseY) {
                            top = baseY;
                        }
                        int hardTop = Math.min(oreCeilings == null ? top : oreCeilings[index], baseY + 16);
                        if (hardTop < top) {
                            hardTop = top;
                        }
                        maskTop[index] = top;
                        oreTop[index] = hardTop;
                        if (hardTop > baseY) {
                            anyMasked = true;
                        }
                        if (top != baseY + 16) {
                            fullyMasked = false;
                        }
                    }
                }

                if (anyMasked) {
                    Object states = format.getStates.invoke(chunkSection);
                    // The single-value shortcut needs no earned blocks in this section.
                    if (fullyMasked && (visible == null || !anyVisible(visible, baseX, baseY, baseZ))) {
                        Object fake = fakeStateFor.apply(baseY);
                        if (fake != null && fake == fakeStateFor.apply(baseY + 15)) {
                            // The whole section is one fake block: encode a single-value container.
                            Object single = format.newSingleValueContainer(
                                    fake, format.strategyField.get(states));
                            set(chunkSection, "states", single);
                            format.sectionRecalc.invoke(chunkSection);
                        } else {
                            maskBlocks(format, states, maskTop, oreTop, baseY, baseX, baseZ,
                                    fakeStateFor, visible, sensitiveState);
                            format.sectionRecalc.invoke(chunkSection);
                        }
                    } else {
                        maskBlocks(format, states, maskTop, oreTop, baseY, baseX, baseZ,
                                fakeStateFor, visible, sensitiveState);
                        format.sectionRecalc.invoke(chunkSection);
                    }
                }
                if (anyMasked) {
                    format.sectionWrite.invoke(chunkSection, out);
                } else {
                    // Nothing in this section changes: copy the bytes the server wrote instead
                    // of re-encoding the same data.
                    target.writeBytes(source, sectionStart, sectionEnd - sectionStart);
                }
            }
        } catch (Throwable throwable) {
            target.release();
            source.release();
            return null;
        }
        byte[] result = new byte[target.readableBytes()];
        target.readBytes(result);
        target.release();
        source.release();
        return result;
    }

    /** Whether the viewer has already earned any block in this section. */
    private static boolean anyVisible(BlockVisibility visible, int baseX, int baseY, int baseZ) {
        return visible.anyInBox(baseX, baseY, baseZ, baseX + 15, baseY + 15, baseZ + 15);
    }

    /**
     * Replaces each block below its column's ceiling with the fake block unless the viewer has
     * already earned it.
     */
    private static void maskBlocks(ChunkFormat format, Object states, int[] maskTop, int[] oreTop, int baseY, int baseX, int baseZ, java.util.function.IntFunction<Object> fakeStateFor, BlockVisibility visible, java.util.function.Predicate<Object> sensitiveState)
            throws Throwable {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int index = x * 16 + z;
                int top = maskTop[index];
                for (int worldY = baseY; worldY < top; worldY++) {
                    if (visible != null && visible.isVisible(baseX + x, worldY, baseZ + z)) {
                        continue;
                    }
                    Object fake = fakeStateFor.apply(worldY);
                    if (fake == null) {
                        continue;
                    }
                    if (format.getAndSetHandle != null) {
                        Object ignored = (Object) format.getAndSetHandle.invokeExact(states, x, worldY - baseY, z, fake);
                    } else {
                        format.getAndSet.invoke(states, x, worldY - baseY, z, fake);
                    }
                }
                int hardTop = oreTop[index];
                if (sensitiveState == null) {
                    continue;
                }
                // Inside the soil buffer, keep natural terrain visible but hide ores,
                // containers and block-entity blocks until the player earns the cell.
                for (int worldY = top; worldY < hardTop; worldY++) {
                    if (visible != null && visible.isVisible(baseX + x, worldY, baseZ + z)) {
                        continue;
                    }
                    Object state = format.getHandle != null ? (Object) format.getHandle.invokeExact(states, x, worldY - baseY, z) : format.get.invoke(states, x, worldY - baseY, z);
                    if (state == null || !sensitiveState.test(state)) {
                        continue;
                    }
                    Object fake = fakeStateFor.apply(worldY);
                    if (fake != null) {
                        if (format.getAndSetHandle != null) {
                            Object ignored = (Object) format.getAndSetHandle.invokeExact(states, x, worldY - baseY, z, fake);
                        } else {
                            format.getAndSet.invoke(states, x, worldY - baseY, z, fake);
                        }
                    }
                }
            }
        }
    }

}
