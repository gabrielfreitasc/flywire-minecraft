package com.flywireminecraft.bee;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/** Spawna e marca a abelha do conectoma quando o jogador usa o SpawnItem. */
public final class BeeSpawnListener implements Listener {
    private final SpawnItem spawnItem;
    private final FlywireBeeMarker marker;

    public BeeSpawnListener(SpawnItem spawnItem, FlywireBeeMarker marker) {
        this.spawnItem = spawnItem;
        this.marker = marker;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        ItemStack item = event.getItem();
        if (!spawnItem.isSpawnItem(item)) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        boolean alreadyExists = player.getWorld().getEntitiesByClass(Bee.class).stream()
                .anyMatch(marker::isMarked);
        if (alreadyExists) {
            player.sendMessage("Já existe uma abelha do FlyWire neste mundo — v1 controla uma só.");
            return;
        }

        Location spawnLocation = event.getClickedBlock() != null
                ? event.getClickedBlock().getLocation().add(0.5, 1.0, 0.5)
                : player.getLocation();

        Bee bee = player.getWorld().spawn(spawnLocation, Bee.class);
        marker.mark(bee);
        player.sendMessage("Abelha do FlyWire spawnada — ainda sem controle motor (isso é F4).");

        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
    }
}
