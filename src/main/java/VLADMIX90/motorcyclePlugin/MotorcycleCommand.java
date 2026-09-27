package VLADMIX90.motorcyclePlugin;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MotorcycleCommand implements CommandExecutor, TabCompleter {

    private final MotorcyclePlugin plugin;

    public MotorcycleCommand(MotorcyclePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, plugin.getConfig().getString("messages.usage", "/moto help"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args);
            case "pos1" -> position(sender, 1);
            case "pos2" -> position(sender, 2);
            case "zone" -> zone(sender, args);
            case "reload" -> reload(sender);
            default -> send(sender, plugin.getConfig().getString("messages.usage", "/moto help"));
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("motorcycle.give")) {
            send(sender, plugin.message("messages.no-permission"));
            return;
        }
        if (args.length < 2) {
            send(sender, "/moto give <игрок> [количество]");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            target = Bukkit.getPlayer(args[1]);
        }
        if (target == null) {
            send(sender, plugin.message("messages.motorcycle-not-found"));
            return;
        }

        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                send(sender, "<red>Количество должно быть числом.");
                return;
            }
        }

        for (int i = 0; i < amount; i++) {
            ItemStack item = plugin.motorcycles().createItem(100);
            var result = target.getInventory().addItem(item);
            for (ItemStack leftover : result.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }
        }

        send(sender, plugin.message("messages.motorcycle-given").replace("{charge}", "100"));
        if (!target.equals(sender)) {
            send(target, plugin.message("messages.motorcycle-given").replace("{charge}", "100"));
        }
    }

    private void position(CommandSender sender, int position) {
        if (!(sender instanceof Player player)) {
            send(sender, plugin.message("messages.only-player"));
            return;
        }
        if (!sender.hasPermission("motorcycle.admin")) {
            send(sender, plugin.message("messages.no-permission"));
            return;
        }

        if (position == 1) {
            plugin.zones().setPos1(player.getUniqueId(), player.getLocation());
        } else {
            plugin.zones().setPos2(player.getUniqueId(), player.getLocation());
        }

        Location loc = player.getLocation();
        String path = position == 1 ? "messages.pos1-set" : "messages.pos2-set";
        send(sender, plugin.message(path)
                .replace("{x}", String.valueOf(loc.getBlockX()))
                .replace("{y}", String.valueOf(loc.getBlockY()))
                .replace("{z}", String.valueOf(loc.getBlockZ())));
    }

    private void zone(CommandSender sender, String[] args) {
        if (!sender.hasPermission("motorcycle.admin")) {
            send(sender, plugin.message("messages.no-permission"));
            return;
        }
        if (args.length < 2) {
            send(sender, "/moto zone <create|delete|list> ...");
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> createZone(sender, args);
            case "delete" -> deleteZone(sender, args);
            case "list" -> listZones(sender);
            default -> send(sender, "/moto zone <create|delete|list> ...");
        }
    }

    private void createZone(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, plugin.message("messages.only-player"));
            return;
        }
        if (args.length < 4) {
            send(sender, "/moto zone create <no_ride|charge> <имя>");
            return;
        }

        ZoneType type = ZoneType.fromString(args[2]);
        if (type == null) {
            send(sender, "<red>Тип должен быть no_ride или charge.");
            return;
        }

        Location pos1 = plugin.zones().getPos1(player.getUniqueId());
        Location pos2 = plugin.zones().getPos2(player.getUniqueId());
        if (pos1 == null || pos2 == null) {
            send(sender, plugin.message("messages.missing-pos"));
            return;
        }

        String name = args[3].toLowerCase(Locale.ROOT);
        Zone zone;
        try {
            zone = plugin.zones().create(name, type, pos1, pos2);
        } catch (IllegalArgumentException ex) {
            send(sender, "<red>Обе точки должны быть в одном мире.");
            return;
        }

        send(sender, plugin.message("messages.zone-created")
                .replace("{name}", zone.name())
                .replace("{type}", zone.type() == ZoneType.NO_RIDE ? "no_ride" : "charge"));
    }

    private void deleteZone(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "/moto zone delete <имя>");
            return;
        }
        String name = args[2];
        if (!plugin.zones().delete(name)) {
            send(sender, plugin.message("messages.zone-not-found"));
            return;
        }
        send(sender, plugin.message("messages.zone-deleted").replace("{name}", name));
    }

    private void listZones(CommandSender sender) {
        List<String> names = plugin.zones().getAll().stream()
                .map(zone -> zone.name() + "(" + (zone.type() == ZoneType.NO_RIDE ? "no_ride" : "charge") + ")")
                .toList();
        String zones = names.isEmpty() ? "нет" : String.join(", ", names);
        send(sender, plugin.message("messages.zones-list").replace("{zones}", zones)
                .replace("<zones>", zones));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("motorcycle.admin")) {
            send(sender, plugin.message("messages.no-permission"));
            return;
        }
        plugin.reloadConfig();
        send(sender, "<green>Конфигурация мотоцикла перезагружена.");
    }

    private void send(CommandSender sender, String miniMessage) {
        Component component = plugin.miniMessage().deserialize(miniMessage == null ? "" : miniMessage);
        sender.sendMessage(component);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], List.of("give", "pos1", "pos2", "zone", "reload"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("zone")) {
            return partial(args[1], List.of("create", "delete", "list"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("zone") && args[1].equalsIgnoreCase("create")) {
            return partial(args[2], List.of("no_ride", "charge"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("zone") && args[1].equalsIgnoreCase("delete")) {
            return partial(args[2], plugin.zones().getAll().stream().map(Zone::name).toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return partial(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return new ArrayList<>();
    }

    private List<String> partial(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
