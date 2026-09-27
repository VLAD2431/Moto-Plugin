package VLADMIX90.motorcyclePlugin;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ZoneManager {

    private final MotorcyclePlugin plugin;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final Map<UUID, Location> pos1 = new java.util.HashMap<>();
    private final Map<UUID, Location> pos2 = new java.util.HashMap<>();
    private final File file;

    public ZoneManager(MotorcyclePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "zones.yml");
        load();
    }

    public void setPos1(UUID player, Location location) {
        pos1.put(player, location.clone());
    }

    public void setPos2(UUID player, Location location) {
        pos2.put(player, location.clone());
    }

    public Location getPos1(UUID player) {
        return pos1.get(player);
    }

    public Location getPos2(UUID player) {
        return pos2.get(player);
    }

    public Zone create(String name, ZoneType type, Location a, Location b) {
        if (!a.getWorld().getUID().equals(b.getWorld().getUID())) {
            throw new IllegalArgumentException("Locations must be in the same world");
        }

        double minX = Math.min(a.getX(), b.getX());
        double minY = Math.min(a.getY(), b.getY());
        double minZ = Math.min(a.getZ(), b.getZ());
        double maxX = Math.max(a.getX(), b.getX());
        double maxY = Math.max(a.getY(), b.getY());
        double maxZ = Math.max(a.getZ(), b.getZ());

        Zone zone = new Zone(name, type, a.getWorld().getName(), minX, minY, minZ, maxX, maxY, maxZ);
        zones.put(name.toLowerCase(), zone);
        save();
        return zone;
    }

    public boolean delete(String name) {
        Zone removed = zones.remove(name.toLowerCase());
        if (removed != null) {
            save();
            return true;
        }
        return false;
    }

    public Zone get(String name) {
        return zones.get(name.toLowerCase());
    }

    public List<Zone> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(zones.values()));
    }

    public boolean isNoRide(Location location) {
        return zones.values().stream()
                .anyMatch(zone -> zone.type() == ZoneType.NO_RIDE && zone.contains(location));
    }

    public boolean isCharge(Location location) {
        return zones.values().stream()
                .anyMatch(zone -> zone.type() == ZoneType.CHARGE && zone.contains(location));
    }

    public void save() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder.");
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        for (Zone zone : zones.values()) {
            String path = "zones." + zone.name();
            yaml.set(path + ".type", zone.type().name());
            yaml.set(path + ".world", zone.worldName());
            yaml.set(path + ".min.x", zone.minX());
            yaml.set(path + ".min.y", zone.minY());
            yaml.set(path + ".min.z", zone.minZ());
            yaml.set(path + ".max.x", zone.maxX());
            yaml.set(path + ".max.y", zone.maxY());
            yaml.set(path + ".max.z", zone.maxZ());
        }

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save zones.yml: " + e.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("zones");
        if (section == null) {
            return;
        }

        for (String name : section.getKeys(false)) {
            String path = "zones." + name;
            ZoneType type;
            try {
                type = ZoneType.valueOf(yaml.getString(path + ".type", "NO_RIDE"));
            } catch (IllegalArgumentException e) {
                continue;
            }

            String world = yaml.getString(path + ".world");
            if (world == null) {
                continue;
            }

            zones.put(name.toLowerCase(), new Zone(
                    name,
                    type,
                    world,
                    yaml.getDouble(path + ".min.x"),
                    yaml.getDouble(path + ".min.y"),
                    yaml.getDouble(path + ".min.z"),
                    yaml.getDouble(path + ".max.x"),
                    yaml.getDouble(path + ".max.y"),
                    yaml.getDouble(path + ".max.z")
            ));
        }
    }
}
