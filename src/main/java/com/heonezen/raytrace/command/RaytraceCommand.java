package com.heonezen.raytrace.command;

import com.heonezen.raytrace.config.RaytraceConfig;
import com.heonezen.raytrace.engine.RaytraceEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import java.util.List;

public final class RaytraceCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload");

    private final Plugin plugin;
    private final RaytraceConfig config;
    private final RaytraceEngine engine;

    public RaytraceCommand(Plugin plugin, RaytraceConfig config, RaytraceEngine engine) {
        this.plugin = plugin;
        this.config = config;
        this.engine = engine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("raytrace.admin")) {
            sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            sendUsage(sender);
            return true;
        }
        config.reload();
        engine.clearConfigCaches();
        engine.resetAllPlayers();
        sender.sendMessage(Component.text("Raytrace configuration reloaded.", NamedTextColor.GREEN));
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("/raytrace reload", NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("raytrace.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String partial = args[0].toLowerCase(java.util.Locale.ROOT);
            return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(partial)).toList();
        }
        return List.of();
    }
}
