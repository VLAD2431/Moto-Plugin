package VLADMIX90.motorcyclePlugin;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class MotorcyclePlugin extends JavaPlugin {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private NamespacedKey bikeKey;
    private NamespacedKey batteryKey;
    private NamespacedKey bikeIdKey;
    private NamespacedKey seatKey;
    private NamespacedKey interactionKey;
    private NamespacedKey visualKey;
    private NamespacedKey headingKey;
    private NamespacedKey batteryFractionKey;
    private NamespacedKey chargeFractionKey;

    private ZoneManager zoneManager;
    private MotorcycleManager motorcycleManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        bikeKey = new NamespacedKey(this, "motorcycle_item");
        batteryKey = new NamespacedKey(this, "battery");
        bikeIdKey = new NamespacedKey(this, "motorcycle_id");
        seatKey = new NamespacedKey(this, "motorcycle_seat");
        interactionKey = new NamespacedKey(this, "motorcycle_interaction");
        visualKey = new NamespacedKey(this, "motorcycle_visual");
        headingKey = new NamespacedKey(this, "heading_yaw");
        batteryFractionKey = new NamespacedKey(this, "battery_fraction");
        chargeFractionKey = new NamespacedKey(this, "charge_fraction");

        zoneManager = new ZoneManager(this);
        motorcycleManager = new MotorcycleManager(this);
        motorcycleManager.start();

        getCommand("moto").setExecutor(new MotorcycleCommand(this));
        getCommand("moto").setTabCompleter(new MotorcycleCommand(this));
        getServer().getPluginManager().registerEvents(new MotorcycleListener(this), this);

        getLogger().info("MotorcyclePlugin enabled.");
    }

    @Override
    public void onDisable() {
        if (motorcycleManager != null) {
            motorcycleManager.shutdown();
        }
        if (zoneManager != null) {
            zoneManager.save();
        }
    }

    public MiniMessage miniMessage() {
        return miniMessage;
    }

    public NamespacedKey bikeKey() {
        return bikeKey;
    }

    public NamespacedKey batteryKey() {
        return batteryKey;
    }

    public NamespacedKey bikeIdKey() {
        return bikeIdKey;
    }

    public NamespacedKey seatKey() {
        return seatKey;
    }

    public NamespacedKey interactionKey() {
        return interactionKey;
    }

    public NamespacedKey visualKey() {
        return visualKey;
    }

    public NamespacedKey headingKey() {
        return headingKey;
    }

    public NamespacedKey batteryFractionKey() {
        return batteryFractionKey;
    }

    public NamespacedKey chargeFractionKey() {
        return chargeFractionKey;
    }

    public ZoneManager zones() {
        return zoneManager;
    }

    public MotorcycleManager motorcycles() {
        return motorcycleManager;
    }

    public String message(String path) {
        return getConfig().getString("messages.prefix", "") + getConfig().getString(path, path);
    }
}
