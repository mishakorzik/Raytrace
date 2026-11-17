package com.heonezen.raytrace.config;

import org.bukkit.World;

import java.lang.reflect.Proxy;

// A World that only knows its name, enough for WorldSettings lookups.
final class TestWorlds {

    private TestWorlds() {}

    static World named(String name) {
        return (World) Proxy.newProxyInstance(TestWorlds.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName", "toString" -> name;
            case "hashCode" -> name.hashCode();
            case "equals" -> proxy == args[0];
            default -> null;
        });
    }
}
