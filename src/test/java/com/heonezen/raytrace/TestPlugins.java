package com.heonezen.raytrace;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

// A stand-in Plugin for tests: only getLogger() works, and every message is kept.
public final class TestPlugins {

    public final List<String> messages = new CopyOnWriteArrayList<>();
    public final Plugin plugin;

    public TestPlugins() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        plugin = (Plugin) Proxy.newProxyInstance(TestPlugins.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getLogger" -> logger;
            case "getName", "toString" -> "RaytraceTest";
            case "hashCode" -> 1;
            case "equals" -> proxy == args[0];
            default -> null;
        });
    }
}
