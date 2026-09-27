package VLADMIX90.motorcyclePlugin;

import org.bukkit.Location;
import org.bukkit.World;

public record Zone(String name, ZoneType type, String worldName,
                   double minX, double minY, double minZ,
                   double maxX, double maxY, double maxZ) {

    public boolean contains(Location location) {
        World world = location.getWorld();
        if (world == null || !world.getName().equals(worldName)) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }
}
