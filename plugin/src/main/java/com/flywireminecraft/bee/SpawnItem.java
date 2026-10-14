package com.flywireminecraft.bee;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/** Item que, ao ser usado, spawna e marca a abelha do conectoma (ver FlywireBeeMarker, BeeSpawnListener). */
public final class SpawnItem {
    private static final String KEY = "spawn_item";

    private final NamespacedKey key;

    public SpawnItem(Plugin plugin) {
        this.key = new NamespacedKey(plugin, KEY);
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.HONEYCOMB);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("FlyWire Bee Spawner", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("Clique com o botão direito para spawnar", NamedTextColor.GRAY),
                Component.text("a abelha controlada pelo conectoma.", NamedTextColor.GRAY)
        ));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSpawnItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        Byte value = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }
}
