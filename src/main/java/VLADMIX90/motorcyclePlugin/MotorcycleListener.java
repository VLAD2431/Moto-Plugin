package VLADMIX90.motorcyclePlugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class MotorcycleListener implements Listener {
    private final MotorcyclePlugin plugin;

    public MotorcycleListener(MotorcyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (var entity : event.getChunk().getEntities()) {
            plugin.motorcycles().registerLoadedEntity(entity);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Motorcycle bike = plugin.motorcycles().getByEntity(event.getRightClicked());
        if (bike == null) return;
        event.setCancelled(true);
        plugin.motorcycles().mount(event.getPlayer(), bike);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Motorcycle bike = plugin.motorcycles().getByEntity(event.getRightClicked());
        if (bike == null) return;
        event.setCancelled(true);
        plugin.motorcycles().mount(event.getPlayer(), bike);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        if (plugin.motorcycles().getByPlayer(event.getPlayer()) != null) return;
        if (!plugin.motorcycles().isMotorcycleItem(event.getPlayer().getInventory().getItemInMainHand())) return;

        event.setCancelled(true);
        plugin.motorcycles().beginInstall(event.getPlayer());
    }

    /** F — уборка мотоцикла рядом. Q плагином не используется. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (plugin.motorcycles().getByPlayer(event.getPlayer()) != null) return;
        if (plugin.motorcycles().beginStorageNearby(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        plugin.motorcycles().onDismount(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.motorcycles().removeRider(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        for (Motorcycle bike : plugin.motorcycles().getAll()) {
            if (!bike.seat().getWorld().equals(event.getBlock().getWorld())) continue;
            if (plugin.motorcycles().intersectsPlacedBlock(bike, event.getBlock())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Motorcycle bike = plugin.motorcycles().getByEntity(event.getEntity());
        if (bike != null) {
            event.setCancelled(true);
            return;
        }

        if (event.getEntity() instanceof org.bukkit.entity.Player player) {
            Motorcycle riding = plugin.motorcycles().getByPlayer(player);
            if (riding != null && riding.storageInProgress()
                    && plugin.getConfig().getBoolean("motorcycle.storage.cancel-on-damage", true)) {
                plugin.motorcycles().cancelStorageForPlayer(player, "messages.storage-cancel");
            }
        }
    }
}
