package VLADMIX90.motorcyclePlugin;

import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ховербайк. Важный принцип этой версии: пассажир сидит на ArmorStand,
 * а сам ArmorStand перемещается обычной entity-скоростью через setVelocity().
 * Мы не телепортируем пассажира каждый тик — поэтому камера не дёргается
 * и игрок не должен отставать от мотоцикла.
 */
public final class MotorcycleManager {
    /**
     * Игрок-пассажир рендерится на 1.30 блока выше позиции своего транспорта.
     * Понижаем невидимое сиденье на эту величину, чтобы персонаж сидел прямо
     * над моделью мотоцикла, а не висел в воздухе.
     */
    private static final double SEAT_PASSENGER_OFFSET = 1.30;

    private final MotorcyclePlugin plugin;

    private final Map<UUID, Motorcycle> motorcycles = new HashMap<>();
    private final Map<UUID, UUID> playerToBike = new HashMap<>();
    private final Map<UUID, Integer> pendingStorageTasks = new HashMap<>();
    private final Map<UUID, Integer> pendingInstallTasks = new HashMap<>();

    private final Map<UUID, ArmorStand> loadedSeats = new HashMap<>();
    private final Map<UUID, ItemDisplay> loadedVisuals = new HashMap<>();

    private int tickTask = -1;
    private long ticksElapsed;

    public MotorcycleManager(MotorcyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        loadExistingLoadedEntities();
        tickTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 1L, 1L);
    }

    public void registerLoadedEntity(Entity entity) {
        String idString = entity.getPersistentDataContainer().get(plugin.bikeIdKey(), PersistentDataType.STRING);
        if (idString == null) return;

        UUID id;
        try {
            id = UUID.fromString(idString);
        } catch (IllegalArgumentException ignored) {
            return;
        }

        // Старые версии создавали Interaction. Новая архитектура его не использует.
        // При загрузке мира удаляем старый хитбокс, чтобы он не конфликтовал с новым сиденьем.
        if (entity.getPersistentDataContainer().has(plugin.interactionKey(), PersistentDataType.BYTE)) {
            entity.remove();
            return;
        }

        if (entity instanceof ArmorStand seat
                && seat.getPersistentDataContainer().has(plugin.seatKey(), PersistentDataType.BYTE)) {
            loadedSeats.put(id, seat);
            tryRegisterLoaded(id);
            return;
        }

        if (entity instanceof ItemDisplay visual
                && visual.getPersistentDataContainer().has(plugin.visualKey(), PersistentDataType.BYTE)) {
            loadedVisuals.put(id, visual);
            tryRegisterLoaded(id);
        }
    }

    private void tryRegisterLoaded(UUID id) {
        if (motorcycles.containsKey(id)) return;

        ArmorStand seat = loadedSeats.get(id);
        ItemDisplay visual = loadedVisuals.get(id);
        if (seat == null || visual == null || !seat.isValid() || !visual.isValid()) return;

        int capacity = capacity();
        int battery = seat.getPersistentDataContainer()
                .getOrDefault(plugin.batteryKey(), PersistentDataType.INTEGER, capacity);
        float heading = seat.getPersistentDataContainer()
                .getOrDefault(plugin.headingKey(), PersistentDataType.FLOAT, seat.getYaw());

        Motorcycle bike = new Motorcycle(id, seat, visual, Math.min(capacity, Math.max(0, battery)), heading);
        motorcycles.put(id, bike);
        syncVisual(bike, seat.getLocation().clone());
    }

    private void loadExistingLoadedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                registerLoadedEntity(entity);
            }
        }
    }

    public void shutdown() {
        if (tickTask != -1) Bukkit.getScheduler().cancelTask(tickTask);
        pendingStorageTasks.values().forEach(Bukkit.getScheduler()::cancelTask);
        pendingInstallTasks.values().forEach(Bukkit.getScheduler()::cancelTask);
        pendingStorageTasks.clear();
        pendingInstallTasks.clear();

        for (UUID playerId : new HashSet<>(playerToBike.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.leaveVehicle();
                restoreRiderState(player);
            }
        }
        playerToBike.clear();
    }

    public ItemStack createItem(int battery) {
        Material material = Material.matchMaterial(plugin.getConfig().getString("motorcycle.item.material", "PAPER"));
        if (material == null || !material.isItem()) material = Material.PAPER;

        int clampedBattery = Math.max(0, Math.min(capacity(), battery));
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.getPersistentDataContainer().set(plugin.bikeKey(), PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(plugin.batteryKey(), PersistentDataType.INTEGER, clampedBattery);
        meta.getPersistentDataContainer().set(plugin.bikeIdKey(), PersistentDataType.STRING, UUID.randomUUID().toString());
        meta.setMaxStackSize(Math.max(1, plugin.getConfig().getInt("motorcycle.item.max-stack-size", 1)));

        String name = replace(plugin.getConfig().getString("motorcycle.item.name", "<white>Мотоцикл"), clampedBattery, 0, 0);
        meta.displayName(plugin.miniMessage().deserialize(name));

        List<Component> lore = plugin.getConfig().getStringList("motorcycle.item.lore").stream()
                .map(line -> plugin.miniMessage().deserialize(replace(line, clampedBattery, 0, 0)))
                .toList();
        meta.lore(lore);

        String model = plugin.getConfig().getString("motorcycle.item.model", "");
        if (model != null && !model.isBlank() && model.contains(":")) {
            meta.setItemModel(org.bukkit.NamespacedKey.fromString(model));
        }

        item.setItemMeta(meta);
        return item;
    }

    public boolean isMotorcycleItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(plugin.bikeKey(), PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    public int getBattery(ItemStack item) {
        if (!isMotorcycleItem(item)) return 0;
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(plugin.batteryKey(), PersistentDataType.INTEGER, capacity());
    }

    public void updateItemBattery(ItemStack item, int battery) {
        if (!isMotorcycleItem(item)) return;
        ItemMeta meta = item.getItemMeta();
        int value = Math.max(0, Math.min(capacity(), battery));
        meta.getPersistentDataContainer().set(plugin.batteryKey(), PersistentDataType.INTEGER, value);
        meta.displayName(plugin.miniMessage().deserialize(
                replace(plugin.getConfig().getString("motorcycle.item.name", "<white>Мотоцикл"), value, 0, 0)));
        meta.lore(plugin.getConfig().getStringList("motorcycle.item.lore").stream()
                .map(line -> plugin.miniMessage().deserialize(replace(line, value, 0, 0)))
                .toList());
        item.setItemMeta(meta);
    }

    public Motorcycle spawn(Location location, int battery) {
        UUID id = UUID.randomUUID();
        float initialYaw = location.getYaw();

        Location spawn = location.clone();
        spawn.setYaw(initialYaw);
        spawn.setPitch(0);
        spawn = snapToHover(spawn, 0.0);

        int clampedBattery = Math.max(0, Math.min(capacity(), battery));

        ArmorStand seat = spawn.getWorld().spawn(spawn, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setSmall(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setPersistent(true);
            stand.setCollidable(false);
            // Не используем setCanMove(false): он мешает нормальному entity-movement через velocity.
            stand.getPersistentDataContainer().set(plugin.seatKey(), PersistentDataType.BYTE, (byte) 1);
            stand.getPersistentDataContainer().set(plugin.bikeIdKey(), PersistentDataType.STRING, id.toString());
            stand.getPersistentDataContainer().set(plugin.batteryKey(), PersistentDataType.INTEGER, clampedBattery);
            stand.getPersistentDataContainer().set(plugin.headingKey(), PersistentDataType.FLOAT, initialYaw);
        });

        ItemDisplay visual = spawn.getWorld().spawn(spawn, ItemDisplay.class, display -> {
            display.setItemStack(createItem(clampedBattery));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setPersistent(true);
            display.setInterpolationDuration(2);
            display.getPersistentDataContainer().set(plugin.visualKey(), PersistentDataType.BYTE, (byte) 1);
            display.getPersistentDataContainer().set(plugin.bikeIdKey(), PersistentDataType.STRING, id.toString());
        });

        Motorcycle bike = new Motorcycle(id, seat, visual, clampedBattery, initialYaw);
        motorcycles.put(id, bike);
        loadedSeats.put(id, seat);
        loadedVisuals.put(id, visual);
        syncVisual(bike, spawn);
        return bike;
    }

    public void mount(Player player, Motorcycle motorcycle) {
        if (getByPlayer(player) != null || player.isInsideVehicle()) {
            send(player, "messages.already-riding");
            return;
        }
        if (plugin.zones().isNoRide(player.getLocation())) {
            send(player, "messages.no-ride");
            return;
        }
        if (!motorcycle.seat().isValid() || !motorcycle.visual().isValid()) return;
        if (!motorcycle.seat().getPassengers().isEmpty()) {
            send(player, "messages.already-riding");
            return;
        }

        // Сиденье (ArmorStand) ставим ровно на 1.30 блока ниже глаз пассажира —
        // стандартное смещение игрока-пассажира. Игрок сидит ПРЯМО над моделью,
        // а не висит в воздухе над ней.
        Location seatLoc = motorcycle.seat().getLocation().clone();
        seatLoc.setY(seatLoc.getY() - SEAT_PASSENGER_OFFSET);
        motorcycle.seat().teleport(seatLoc);

        boolean mounted = motorcycle.seat().addPassenger(player);
        if (!mounted) {
            send(player, "messages.mount-failed");
            return;
        }

        playerToBike.put(player.getUniqueId(), motorcycle.id());
        player.setFallDistance(0);
        player.setSneaking(false);
        motorcycle.setSpeed(0);
    }

    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Motorcycle bike = getByEntity(event.getDismounted());
        if (bike == null) return;

        playerToBike.remove(player.getUniqueId());
        restoreRiderState(player);
        bike.setSpeed(0);
        bike.setJumping(false);
        raiseSeatAfterRide(bike);
    }

    public void removeRider(Player player) {
        Motorcycle bike = getByPlayer(player);
        if (bike == null) {
            playerToBike.remove(player.getUniqueId());
            return;
        }
        playerToBike.remove(player.getUniqueId());
        player.leaveVehicle();
        restoreRiderState(player);
        bike.setSpeed(0);
        bike.setJumping(false);
        raiseSeatAfterRide(bike);
    }

    /** Возвращаем пустое сиденье на ховер-высоту после высадки. */
    private void raiseSeatAfterRide(Motorcycle bike) {
        Location seatLoc = bike.seat().getLocation().clone();
        seatLoc.setY(seatLoc.getY() + SEAT_PASSENGER_OFFSET);
        bike.seat().teleport(seatLoc);
    }

    private void restoreRiderState(Player player) {
        player.setGravity(true);
        player.setVelocity(new Vector());
        player.setFallDistance(0);
    }

    public Motorcycle getByPlayer(Player player) {
        UUID id = playerToBike.get(player.getUniqueId());
        return id == null ? null : motorcycles.get(id);
    }

    public Motorcycle getByEntity(Entity entity) {
        if (entity == null) return null;
        String raw = entity.getPersistentDataContainer().get(plugin.bikeIdKey(), PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return motorcycles.get(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // ==================== Хранение / установка ====================

    public boolean beginStorageNearby(Player player) {
        if (player == null || getByPlayer(player) != null || pendingStorageTasks.containsKey(player.getUniqueId())) return false;

        double radius = Math.max(0.5, plugin.getConfig().getDouble("motorcycle.storage.nearby-radius", 3.0));
        Motorcycle nearest = findNearestMotorcycle(player.getLocation(), radius);
        if (nearest == null || nearest.storageInProgress()) return false;

        startNearbyStorage(player, nearest);
        return true;
    }

    private Motorcycle findNearestMotorcycle(Location location, double radius) {
        Motorcycle nearest = null;
        double best = radius * radius;
        for (Motorcycle bike : motorcycles.values()) {
            if (!bike.seat().isValid() || bike.storageInProgress() || !bike.seat().getPassengers().isEmpty()) continue;
            if (!location.getWorld().equals(bike.seat().getWorld())) continue;
            double d = location.distanceSquared(bike.seat().getLocation());
            if (d <= best) {
                best = d;
                nearest = bike;
            }
        }
        return nearest;
    }

    private void startNearbyStorage(Player player, Motorcycle bike) {
        int duration = Math.max(1, plugin.getConfig().getInt("motorcycle.storage.duration-seconds", 30));
        Location start = player.getLocation().clone();
        bike.setStorageInProgress(true);
        send(player, "messages.storage-start");

        int task = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            int left = duration;

            @Override
            public void run() {
                if (!player.isOnline() || !bike.seat().isValid()
                        || getByPlayer(player) != null
                        || player.getLocation().distanceSquared(start) > 0.16) {
                    cancelStorage(player, bike, "messages.storage-cancel");
                    return;
                }

                if (left <= 0) {
                    completeStorage(player, bike);
                    return;
                }

                player.sendActionBar(parseActionbar("motorcycle.actionbar.storage", bike, left));
                left--;
            }
        }, 0L, 20L);
        pendingStorageTasks.put(player.getUniqueId(), task);
    }

    private void completeStorage(Player player, Motorcycle bike) {
        cancelTask(pendingStorageTasks.remove(player.getUniqueId()));
        bike.setStorageInProgress(false);

        ItemStack item = createItem(bike.battery());
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }

        removeMotorcycle(bike);
        player.sendActionBar(Component.empty());
        send(player, "messages.storage-complete");
    }

    public void beginInstall(Player player) {
        if (getByPlayer(player) != null) {
            send(player, "messages.already-riding");
            return;
        }
        if (pendingInstallTasks.containsKey(player.getUniqueId())) return;
        if (plugin.zones().isNoRide(player.getLocation())) {
            send(player, "messages.no-ride-deploy");
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isMotorcycleItem(held)) {
            send(player, "messages.not-motorcycle-item");
            return;
        }

        int battery = getBattery(held);
        Location start = player.getLocation().clone();
        int duration = Math.max(1, plugin.getConfig().getInt("motorcycle.storage.duration-seconds", 30));
        send(player, "messages.install-start");

        int task = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            int left = duration;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelInstall(player, "messages.install-cancel");
                    return;
                }
                ItemStack current = player.getInventory().getItemInMainHand();
                if (!isMotorcycleItem(current) || getBattery(current) != battery) {
                    cancelInstall(player, "messages.install-cancel");
                    return;
                }
                if (plugin.zones().isNoRide(player.getLocation()) || player.getLocation().distanceSquared(start) > 0.16) {
                    cancelInstall(player, "messages.install-cancel");
                    return;
                }
                if (left <= 0) {
                    completeInstall(player, battery);
                    return;
                }

                player.sendActionBar(parseActionbar("motorcycle.actionbar.installing", null, left));
                left--;
            }
        }, 0L, 20L);
        pendingInstallTasks.put(player.getUniqueId(), task);
    }

    private void completeInstall(Player player, int battery) {
        cancelTask(pendingInstallTasks.remove(player.getUniqueId()));
        ItemStack current = player.getInventory().getItemInMainHand();
        if (!isMotorcycleItem(current)) return;

        if (current.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else {
            current.setAmount(current.getAmount() - 1);
            player.getInventory().setItemInMainHand(current);
        }

        Motorcycle bike = spawn(player.getLocation(), battery);
        send(player, "messages.install-complete");
        mount(player, bike);
    }

    private void cancelInstall(Player player, String messagePath) {
        cancelTask(pendingInstallTasks.remove(player.getUniqueId()));
        player.sendActionBar(Component.empty());
        send(player, messagePath);
    }

    public void cancelStorageForPlayer(Player player, String messagePath) {
        Motorcycle bike = getByPlayer(player);
        if (bike != null) cancelStorage(player, bike, messagePath);
    }

    private void cancelStorage(Player player, Motorcycle bike, String path) {
        bike.setStorageInProgress(false);
        cancelTask(pendingStorageTasks.remove(player.getUniqueId()));
        player.sendActionBar(Component.empty());
        send(player, path);
    }

    private void cancelTask(Integer task) {
        if (task != null) Bukkit.getScheduler().cancelTask(task);
    }

    // ==================== Основной цикл ====================

    private void tick() {
        ticksElapsed++;

        for (Motorcycle bike : motorcycles.values().toArray(Motorcycle[]::new)) {
            if (!bike.seat().isValid() || !bike.visual().isValid()) {
                removeMotorcycle(bike);
                continue;
            }

            Player rider = getRider(bike);
            if (rider != null && rider.isOnline() && !rider.isDead()) {
                playerToBike.put(rider.getUniqueId(), bike.id());
                drive(bike, rider);
            } else {
                if (rider != null) {
                    playerToBike.remove(rider.getUniqueId());
                    rider.leaveVehicle();
                    restoreRiderState(rider);
                }
                bike.setSpeed(moveTowardZero(bike.speed(), rollingPerTick()));
                applyIdleHoverVelocity(bike);
                syncPredictedVisual(bike, bike.seat().getVelocity());
            }
        }
    }

    private Player getRider(Motorcycle bike) {
        for (Entity passenger : bike.seat().getPassengers()) {
            if (passenger instanceof Player player) return player;
        }
        return null;
    }

    private void drive(Motorcycle bike, Player player) {
        if (bike.storageInProgress()) {
            setZeroVelocity(bike);
            syncPredictedVisual(bike, new Vector());
            return;
        }

        if (player.isSneaking()) {
            dismount(player);
            return;
        }

        if (!bike.seat().getPassengers().contains(player)) {
            if (!bike.seat().addPassenger(player)) {
                send(player, "messages.mount-failed");
                return;
            }
        }

        player.setFallDistance(0);

        Input input = player.getCurrentInput();
        boolean forward = input != null && input.isForward();
        boolean backward = input != null && input.isBackward();
        boolean left = input != null && input.isLeft();
        boolean right = input != null && input.isRight();
        boolean jump = input != null && input.isJump();

        if (plugin.zones().isNoRide(bike.seat().getLocation())) {
            bike.setSpeed(moveTowardZero(bike.speed(), brakingPerTick() * 1.6));
            bike.setJumping(false);
            applyFlatMovementVelocity(bike, 0.0, false);
            player.sendActionBar(plugin.miniMessage().deserialize(plugin.message("messages.no-ride")));
            updateVisualLean(bike, false, false, false, 0, 0);
            return;
        }

        updateHeading(bike, left, right);
        handleJumpState(bike, jump);

        updateSpeed(bike, forward, backward);

        Vector velocity = calculateVelocity(bike);
        if (bike.jumping()) {
            velocity = calculateJumpVelocity(bike, velocity);
        } else {
            velocity = resolveGroundMovement(bike, velocity);
        }

        bike.seat().setVelocity(velocity);

        consumeBattery(bike, Math.abs(velocity.getX()) + Math.abs(velocity.getZ()));
        chargeIfNeeded(bike);

        updateVisualLean(bike, left, right, forward, backward ? -1 : (forward ? 1 : 0), velocity.length());
        syncPredictedVisual(bike, velocity);
        player.sendActionBar(parseActionbar("motorcycle.actionbar.driving", bike, 0));
    }

    private void dismount(Player player) {
        Motorcycle bike = getByPlayer(player);
        if (bike == null) return;

        Location exit = bike.seat().getLocation().clone()
                .add(0, SEAT_PASSENGER_OFFSET + 0.15, 0);
        exit.setYaw(player.getYaw());
        exit.setPitch(player.getPitch());

        player.leaveVehicle();
        playerToBike.remove(player.getUniqueId());
        restoreRiderState(player);
        player.teleport(exit);
        bike.setSpeed(0);
        raiseSeatAfterRide(bike);
    }

    // ==================== Управление ====================

    private void updateHeading(Motorcycle bike, boolean left, boolean right) {
        double absKmh = Math.abs(blocksPerTickToKmh(bike.speed()));
        double maxSteer = plugin.getConfig().getDouble("motorcycle.physics.steering-degrees-per-second", 95.0) / 20.0;
        double minKmh = plugin.getConfig().getDouble("motorcycle.physics.steering-min-kmh", 4.0);
        double factor = Math.max(0.0, Math.min(1.0, absKmh / Math.max(0.1, minKmh + 8.0)));

        if (left == right) return;

        // +yaw в нашей системе — вправо, -yaw — влево.
        if (left) bike.setHeadingYaw(bike.headingYaw() - (float) (maxSteer * factor));
        if (right) bike.setHeadingYaw(bike.headingYaw() + (float) (maxSteer * factor));
        bike.seat().setRotation(bike.headingYaw(), 0);
        bike.seat().getPersistentDataContainer().set(plugin.headingKey(), PersistentDataType.FLOAT, bike.headingYaw());
    }

    private void updateSpeed(Motorcycle bike, boolean forward, boolean backward) {
        double max = kmhToBlocksPerTick(plugin.getConfig().getDouble("motorcycle.physics.max-speed-kmh", 90));
        double reverse = kmhToBlocksPerTick(plugin.getConfig().getDouble("motorcycle.physics.reverse-speed-kmh", 12));
        double accel = kmhToBlocksPerTick(plugin.getConfig().getDouble("motorcycle.physics.acceleration-kmh-per-second", 22)) / 20.0;
        double braking = brakingPerTick();
        double rolling = rollingPerTick();

        if (forward && bike.battery() > 0) {
            bike.setSpeed(Math.min(max, bike.speed() + accel));
        } else if (backward) {
            if (bike.speed() > 0.02) bike.setSpeed(Math.max(0, bike.speed() - braking));
            else if (bike.battery() > 0) bike.setSpeed(Math.max(-reverse, bike.speed() - accel * 0.5));
        } else {
            bike.setSpeed(moveTowardZero(bike.speed(), rolling));
        }

        if (bike.battery() <= 0 && bike.speed() > 0) bike.setSpeed(Math.max(0, bike.speed() - braking * 1.6));
    }

    private Vector calculateVelocity(Motorcycle bike) {
        double yaw = Math.toRadians(bike.headingYaw());
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        return forward.multiply(bike.speed());
    }

    /**
     * Главная механика ступенек.
     * Мы не пытаемся «протолкнуть» мотоцикл в стену. Вместо этого определяем
     * поверхность прямо перед корпусом. Разница 0..1 блока считается ступенью:
     * мотоцикл плавно поднимает свою высоту и продолжает ехать.
     * Разница > 1 блока — препятствие, горизонтальная скорость обнуляется.
     * Спуск с блока всегда разрешён и плавно опускает ховербайк.
     */
    private Vector resolveGroundMovement(Motorcycle bike, Vector desiredVelocity) {
        Location current = bike.seat().getLocation().clone();
        double horizontalLength = Math.hypot(desiredVelocity.getX(), desiredVelocity.getZ());

        if (horizontalLength <= 1.0e-6) {
            double targetY = targetHoverY(current, 0.0, false);
            double dy = targetY - current.getY();
            double maxRise = plugin.getConfig().getDouble("motorcycle.physics.hover-rise-per-tick", 0.22);
            double maxDrop = plugin.getConfig().getDouble("motorcycle.physics.hover-drop-per-tick", 0.28);
            dy = clamp(dy, -maxDrop, maxRise);
            return new Vector(0, dy, 0);
        }

        int steps = Math.max(1, (int) Math.ceil(horizontalLength / 0.10));
        Vector horizontal = desiredVelocity.clone().setY(0).multiply(1.0 / steps);
        Location probe = current.clone();
        double finalY = current.getY();
        double maxRisePerTick = plugin.getConfig().getDouble("motorcycle.physics.hover-rise-per-tick", 0.22);
        double maxDropPerTick = plugin.getConfig().getDouble("motorcycle.physics.hover-drop-per-tick", 0.28);
        double maxStep = plugin.getConfig().getDouble("motorcycle.physics.step-up-max-height", 1.0);

        for (int i = 0; i < steps; i++) {
            Location next = probe.clone().add(horizontal);
            double currentGround = groundYAt(probe.getWorld(), probe.getX(), probe.getZ());
            double nextGround = frontGroundY(next, horizontal);

            if (Double.isNaN(nextGround)) {
                // Нет обычной поверхности: смотрим на объём и продолжаем лететь/опускаться.
                nextGround = currentGround;
            }

            if (!Double.isNaN(currentGround) && !Double.isNaN(nextGround)) {
                double rise = nextGround - currentGround;
                if (rise > maxStep + 0.01) {
                    // Слишком высокая стена. Останавливаемся до нуля.
                    bike.setSpeed(0);
                    return new Vector(0, 0, 0);
                }

                double desiredY = hoverCenterY(nextGround, bike.hoverPhase());
                double localDy = clamp(desiredY - probe.getY(), -maxDropPerTick / steps, maxRisePerTick / steps);
                probe.add(0, localDy, 0);
                finalY = probe.getY();
            }

            next.setY(probe.getY());
            next.setYaw(bike.headingYaw());
            if (!vehicleVolumeClear(bike, next)) {
                // Если на текущей высоте упёрлись — пробуем именно ступеньку.
                if (!Double.isNaN(nextGround) && !Double.isNaN(currentGround)
                        && nextGround - currentGround >= -0.01
                        && nextGround - currentGround <= maxStep + 0.01) {
                    double steppedY = hoverCenterY(nextGround, bike.hoverPhase());
                    Location stepped = next.clone();
                    stepped.setY(steppedY);
                    if (vehicleVolumeClear(bike, stepped)) {
                        probe = stepped;
                        finalY = steppedY;
                        continue;
                    }
                }
                bike.setSpeed(0);
                return new Vector(0, 0, 0);
            }

            probe = next;
        }

        double dyTotal = finalY - current.getY();
        dyTotal = clamp(dyTotal, -maxDropPerTick, maxRisePerTick);
        return new Vector(desiredVelocity.getX(), dyTotal, desiredVelocity.getZ());
    }

    private Vector calculateJumpVelocity(Motorcycle bike, Vector horizontal) {
        double gravity = Math.max(0.001, plugin.getConfig().getDouble("motorcycle.physics.jump-gravity", 0.08));
        double vy = bike.jumpVelocity();
        Location current = bike.seat().getLocation().clone();
        Location next = current.clone().add(horizontal).add(0, vy, 0);

        if (!vehicleVolumeClear(bike, next)) {
            // В воздухе тоже нельзя проходить сквозь стену/потолок.
            horizontal.multiply(0.0);
            next = current.clone().add(0, vy, 0);
            if (!vehicleVolumeClear(bike, next)) vy = 0;
        }

        double landingY = hoverCenterY(frontGroundY(next, horizontal), bike.hoverPhase());
        if (Double.isNaN(landingY)) landingY = hoverCenterY(footprintGroundY(current, horizontal), bike.hoverPhase());

        if (vy <= 0 && !Double.isNaN(landingY) && next.getY() <= landingY) {
            bike.setJumping(false);
            bike.setJumpVelocity(0);
            double dy = landingY - current.getY();
            return new Vector(horizontal.getX(), clamp(dy, -0.4, 0.4), horizontal.getZ());
        }

        bike.setJumpVelocity(vy - gravity);
        return new Vector(horizontal.getX(), vy, horizontal.getZ());
    }

    private void handleJumpState(Motorcycle bike, boolean pressed) {
        if (pressed && !bike.jumpHeld() && !bike.jumping() && bike.battery() > 0) {
            double height = Math.max(0.1, plugin.getConfig().getDouble("motorcycle.physics.jump-height", 2.5));
            double gravity = Math.max(0.001, plugin.getConfig().getDouble("motorcycle.physics.jump-gravity", 0.08));
            bike.setJumpVelocity(Math.sqrt(2.0 * gravity * height));
            bike.setJumping(true);
        }
        bike.setJumpHeld(pressed);
    }

    // ==================== Hover ====================

    private void applyIdleHoverVelocity(Motorcycle bike) {
        Location current = bike.seat().getLocation().clone();
        double targetY = targetHoverY(current, 0, false);
        double dy = clamp(targetY - current.getY(),
                -plugin.getConfig().getDouble("motorcycle.physics.hover-drop-per-tick", 0.28),
                plugin.getConfig().getDouble("motorcycle.physics.hover-rise-per-tick", 0.22));
        bike.seat().setVelocity(new Vector(0, dy, 0));
        syncPredictedVisual(bike, new Vector(0, dy, 0));
    }

    private Location snapToHover(Location location, double phase) {
        double ground = groundYAt(location.getWorld(), location.getX(), location.getZ());
        if (Double.isNaN(ground)) return location;
        location.setY(hoverCenterY(ground, phase));
        return location;
    }

    private double targetHoverY(Location location, double ignored, boolean useFront) {
        double ground = useFront ? frontGroundY(location, new Vector()) : footprintGroundY(location, new Vector());
        if (Double.isNaN(ground)) ground = groundYAt(location.getWorld(), location.getX(), location.getZ());
        if (Double.isNaN(ground)) return location.getY();
        double amplitude = plugin.getConfig().getDouble("motorcycle.physics.hover-bob-amplitude", 0.02);
        double frequency = plugin.getConfig().getDouble("motorcycle.physics.hover-bob-frequency", 1.2);
        double bob = amplitude * Math.sin((ticksElapsed / 20.0) * Math.PI * 2.0 * frequency);
        return hoverCenterY(ground, 0) + bob;
    }

    private double hoverCenterY(double groundY, double phase) {
        if (Double.isNaN(groundY)) return Double.NaN;
        double hoverHeight = plugin.getConfig().getDouble("motorcycle.physics.hover-height", 0.70);
        double collisionHeight = Math.max(0.6, plugin.getConfig().getDouble("motorcycle.collision.height", 1.20));
        double amplitude = plugin.getConfig().getDouble("motorcycle.physics.hover-bob-amplitude", 0.02);
        double frequency = plugin.getConfig().getDouble("motorcycle.physics.hover-bob-frequency", 1.2);
        double bob = amplitude * Math.sin((ticksElapsed / 20.0) * Math.PI * 2.0 * frequency + phase);
        return groundY + hoverHeight + collisionHeight / 2.0 + bob;
    }

    // ==================== Collision / terrain ====================

    private boolean vehicleVolumeClear(Motorcycle bike, Location location) {
        World world = location.getWorld();
        if (world == null) return false;

        double width = Math.max(0.4, plugin.getConfig().getDouble("motorcycle.collision.width", 1.20));
        double height = Math.max(0.6, plugin.getConfig().getDouble("motorcycle.collision.height", 1.20));
        double extra = Math.max(0.0, plugin.getConfig().getDouble("motorcycle.collision.extra-radius", 0.10));
        double half = width / 2.0 + extra;
        double minY = location.getY() - height / 2.0 + 0.05;
        double maxY = location.getY() + height / 2.0 - 0.05;

        BoundingBox bikeBox = new BoundingBox(
                location.getX() - half, minY, location.getZ() - half,
                location.getX() + half, maxY, location.getZ() + half
        );

        int minX = (int) Math.floor(bikeBox.getMinX());
        int maxX = (int) Math.floor(bikeBox.getMaxX());
        int minYBlock = (int) Math.floor(bikeBox.getMinY());
        int maxYBlock = (int) Math.floor(bikeBox.getMaxY());
        int minZ = (int) Math.floor(bikeBox.getMinZ());
        int maxZ = (int) Math.floor(bikeBox.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minYBlock; y <= maxYBlock; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isPassable()) continue;
                    BoundingBox blockBox = new BoundingBox(x, y, z, x + 1.0, y + 1.0, z + 1.0);
                    if (bikeBox.overlaps(blockBox)) return false;
                }
            }
        }
        return true;
    }

    private double footprintGroundY(Location location, Vector movement) {
        World world = location.getWorld();
        if (world == null) return Double.NaN;

        double half = Math.max(0.25, plugin.getConfig().getDouble("motorcycle.collision.width", 1.20) / 2.0 - 0.08);
        Vector dir = movement.clone().setY(0);
        if (dir.lengthSquared() > 1.0e-6) dir.normalize();

        List<Location> points = new ArrayList<>();
        points.add(location.clone());
        points.add(location.clone().add(dir.clone().multiply(half * 0.8)));
        points.add(location.clone().add(dir.clone().multiply(half * 0.8)).add(-dir.getZ() * half * 0.55, 0, dir.getX() * half * 0.55));
        points.add(location.clone().add(dir.clone().multiply(half * 0.8)).add(dir.getZ() * half * 0.55, 0, -dir.getX() * half * 0.55));

        double max = Double.NEGATIVE_INFINITY;
        for (Location point : points) {
            double y = groundYAt(world, point.getX(), point.getZ());
            if (!Double.isNaN(y)) max = Math.max(max, y);
        }
        return max == Double.NEGATIVE_INFINITY ? Double.NaN : max;
    }

    /** Поверхность именно перед мотоциклом, а не по всему корпусу. */
    private double frontGroundY(Location location, Vector movement) {
        World world = location.getWorld();
        if (world == null) return Double.NaN;

        Vector dir = movement.clone().setY(0);
        if (dir.lengthSquared() <= 1.0e-8) return groundYAt(world, location.getX(), location.getZ());
        dir.normalize();

        double half = Math.max(0.25, plugin.getConfig().getDouble("motorcycle.collision.width", 1.20) / 2.0 - 0.05);
        double forwardDistance = half + 0.12;
        double sideDistance = half * 0.55;

        Location center = location.clone().add(dir.clone().multiply(forwardDistance));
        Location left = center.clone().add(-dir.getZ() * sideDistance, 0, dir.getX() * sideDistance);
        Location right = center.clone().add(dir.getZ() * sideDistance, 0, -dir.getX() * sideDistance);

        double a = groundYAt(world, center.getX(), center.getZ());
        double b = groundYAt(world, left.getX(), left.getZ());
        double c = groundYAt(world, right.getX(), right.getZ());

        double max = Double.NEGATIVE_INFINITY;
        if (!Double.isNaN(a)) max = Math.max(max, a);
        if (!Double.isNaN(b)) max = Math.max(max, b);
        if (!Double.isNaN(c)) max = Math.max(max, c);
        return max == Double.NEGATIVE_INFINITY ? Double.NaN : max;
    }

    private double groundYAt(World world, double x, double z) {
        if (world == null) return Double.NaN;
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);

        // Ищем сверху вниз только рядом с текущей позицией. Это надёжнее,
        // чем getHighestBlockYAt при мостах/потолках.
        int top = world.getHighestBlockYAt(bx, bz);
        int start = Math.min(world.getMaxHeight() - 1, top + 2);
        int end = Math.max(world.getMinHeight(), top - 8);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(bx, y, bz);
            if (block.isEmpty() || block.isPassable()) continue;
            return y + 1.0;
        }
        return Double.NaN;
    }

    private Vector forwardVector(Motorcycle bike) {
        double yaw = Math.toRadians(bike.headingYaw());
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    // ==================== Visual ====================

    private void updateVisualLean(Motorcycle bike, boolean left, boolean right, boolean forward, int accelDirection, double movementLength) {
        double speedKmh = Math.abs(blocksPerTickToKmh(bike.speed()));
        double maxSpeed = Math.max(1.0, plugin.getConfig().getDouble("motorcycle.physics.max-speed-kmh", 90.0));
        double bank = plugin.getConfig().getDouble("motorcycle.physics.max-bank-degrees", 28.0)
                * Math.min(1.0, speedKmh / maxSpeed);

        // ВАЖНО: знак исправлен под нужное управление:
        // A = левый бок, D = правый бок.
        double targetRoll = left && !right ? bank : (right && !left ? -bank : 0.0);

        double targetPitch = 0.0;
        if (accelDirection > 0 && bike.speed() > 0) {
            targetPitch = -plugin.getConfig().getDouble("motorcycle.physics.acceleration-lean-degrees", 6.0)
                    * Math.min(1.0, speedKmh / maxSpeed);
        } else if (accelDirection < 0 && bike.speed() != 0) {
            targetPitch = plugin.getConfig().getDouble("motorcycle.physics.braking-lean-degrees", 8.0)
                    * Math.min(1.0, speedKmh / maxSpeed);
        }

        double smoothing = clamp(plugin.getConfig().getDouble("motorcycle.physics.visual-smoothing", 0.18), 0.01, 1.0);
        bike.setVisualRoll((float) lerp(bike.visualRoll(), targetRoll, smoothing));
        bike.setVisualPitch((float) lerp(bike.visualPitch(), targetPitch, smoothing));
    }

    private void syncPredictedVisual(Motorcycle bike, Vector velocity) {
        Location predicted = bike.seat().getLocation().clone().add(velocity);
        predicted.setYaw(bike.headingYaw());
        predicted.setPitch(0);
        syncVisual(bike, predicted);
    }

    private void syncVisual(Motorcycle bike, Location location) {
        Location visualLocation = location.clone().add(0,
                plugin.getConfig().getDouble("motorcycle.visual.height-offset", 0.0), 0);
        float scale = (float) plugin.getConfig().getDouble("motorcycle.visual.scale", 1.0);

        bike.visual().teleport(visualLocation);
        bike.visual().setRotation(bike.headingYaw(), 0);

        Quaternionf rotation = new Quaternionf()
                .rotateY(0)
                .rotateX((float) Math.toRadians(bike.visualPitch()))
                .rotateZ((float) Math.toRadians(bike.visualRoll()));

        bike.visual().setTransformation(new Transformation(
                new Vector3f(),
                rotation,
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        ));
    }

    // ==================== Battery ====================

    private void consumeBattery(Motorcycle bike, double horizontalSpeed) {
        double maxSpeed = kmhToBlocksPerTick(plugin.getConfig().getDouble("motorcycle.physics.max-speed-kmh", 90));
        if (maxSpeed <= 0 || horizontalSpeed <= 0.0001) return;

        double fraction = Math.min(1.0, horizontalSpeed / maxSpeed);
        double perMinute = plugin.getConfig().getDouble("motorcycle.battery.consumption-at-max-speed-percent-per-minute", 0.65);
        double consumePerTick = perMinute / 60.0 / 20.0 * fraction;

        double accumulator = bike.seat().getPersistentDataContainer()
                .getOrDefault(plugin.batteryFractionKey(), PersistentDataType.DOUBLE, 0.0);
        accumulator += consumePerTick;
        int battery = bike.battery();
        while (accumulator >= 1.0 && battery > 0) {
            battery--;
            accumulator -= 1.0;
        }
        bike.seat().getPersistentDataContainer().set(plugin.batteryFractionKey(), PersistentDataType.DOUBLE, accumulator);
        setBattery(bike, battery);
    }

    private void chargeIfNeeded(Motorcycle bike) {
        if (Math.abs(bike.speed()) > 0.001 || !plugin.zones().isCharge(bike.seat().getLocation())) return;

        int before = bike.battery();
        if (before >= capacity()) return;

        double accumulator = bike.seat().getPersistentDataContainer()
                .getOrDefault(plugin.chargeFractionKey(), PersistentDataType.DOUBLE, 0.0);
        accumulator += plugin.getConfig().getDouble("motorcycle.battery.charge-per-second", 3.0) / 20.0;
        int gained = (int) Math.floor(accumulator);
        accumulator -= gained;
        bike.seat().getPersistentDataContainer().set(plugin.chargeFractionKey(), PersistentDataType.DOUBLE, accumulator);
        setBattery(bike, Math.min(capacity(), before + gained));
    }

    private void setBattery(Motorcycle bike, int battery) {
        bike.setBattery(Math.min(capacity(), Math.max(0, battery)));
        bike.seat().getPersistentDataContainer().set(plugin.batteryKey(), PersistentDataType.INTEGER, bike.battery());

        ItemStack display = bike.visual().getItemStack();
        if (isMotorcycleItem(display)) {
            updateItemBattery(display, bike.battery());
            bike.visual().setItemStack(display);
        }
    }

    // ==================== Misc ====================

    private void setZeroVelocity(Motorcycle bike) {
        bike.seat().setVelocity(new Vector());
    }

    private void applyFlatMovementVelocity(Motorcycle bike, double speed, boolean reverse) {
        double yaw = Math.toRadians(bike.headingYaw());
        Vector dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        bike.seat().setVelocity(dir.multiply(reverse ? -speed : speed));
    }

    private double rollingPerTick() {
        return kmhToBlocksPerTick(plugin.getConfig().getDouble("motorcycle.physics.rolling-resistance-kmh-per-second", 7)) / 20.0;
    }

    private double brakingPerTick() {
        return kmhToBlocksPerTick(plugin.getConfig().getDouble("motorcycle.physics.braking-kmh-per-second", 42)) / 20.0;
    }

    private double moveTowardZero(double value, double amount) {
        if (value > 0) return Math.max(0, value - amount);
        if (value < 0) return Math.min(0, value + amount);
        return 0;
    }

    private int capacity() {
        return Math.max(1, plugin.getConfig().getInt("motorcycle.battery.capacity", 100));
    }

    private double kmhToBlocksPerTick(double kmh) { return kmh / 3.6 / 20.0; }
    private double blocksPerTickToKmh(double value) { return value * 20.0 * 3.6; }
    private double lerp(double current, double target, double amount) { return current + (target - current) * amount; }
    private double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    private Component parseActionbar(String path, Motorcycle bike, int seconds) {
        int battery = bike == null ? 0 : bike.battery();
        int speed = bike == null ? 0 : (int) Math.round(Math.abs(blocksPerTickToKmh(bike.speed())));
        String raw = plugin.getConfig().getString(path, "");
        raw = replace(raw, battery, speed, seconds);
        return plugin.miniMessage().deserialize(raw);
    }

    private String replace(String text, int battery, int speed, int seconds) {
        return text == null ? "" : text.replace("{charge}", Integer.toString(battery))
                .replace("{speed}", Integer.toString(speed))
                .replace("{seconds}", Integer.toString(seconds));
    }

    private void send(Player player, String path) {
        player.sendMessage(plugin.miniMessage().deserialize(plugin.message(path)));
    }

    public Collection<Motorcycle> getAll() {
        return java.util.Collections.unmodifiableCollection(motorcycles.values());
    }

    public boolean intersectsPlacedBlock(Motorcycle bike, Block block) {
        if (bike == null || block == null || !bike.seat().getWorld().equals(block.getWorld())) return false;

        double width = Math.max(0.1, plugin.getConfig().getDouble("motorcycle.collision.width", 1.20));
        double height = Math.max(0.1, plugin.getConfig().getDouble("motorcycle.collision.height", 1.20));
        double extra = Math.max(0.0, plugin.getConfig().getDouble("motorcycle.collision.extra-radius", 0.10));
        double half = width / 2.0 + extra;
        Location c = bike.seat().getLocation();

        BoundingBox bikeBox = new BoundingBox(
                c.getX() - half, c.getY() - height / 2.0, c.getZ() - half,
                c.getX() + half, c.getY() + height / 2.0, c.getZ() + half
        );
        BoundingBox placed = new BoundingBox(
                block.getX(), block.getY(), block.getZ(),
                block.getX() + 1.0, block.getY() + 1.0, block.getZ() + 1.0
        );
        return bikeBox.overlaps(placed);
    }

    public void removeMotorcycle(Motorcycle bike) {
        if (bike == null) return;
        motorcycles.remove(bike.id());
        loadedSeats.remove(bike.id());
        loadedVisuals.remove(bike.id());

        for (Entity passenger : new ArrayList<>(bike.seat().getPassengers())) {
            if (passenger instanceof Player player) {
                playerToBike.remove(player.getUniqueId());
                player.leaveVehicle();
                restoreRiderState(player);
            }
        }

        bike.seat().remove();
        bike.visual().remove();
    }
}
