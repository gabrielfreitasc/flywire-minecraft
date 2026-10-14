package com.flywireminecraft.bee;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Marca qual abelha do mundo é "a" abelha controlada pelo conectoma. */
public final class FlywireBeeMarker {
    private static final String KEY = "controlled";

    private final NamespacedKey key;

    public FlywireBeeMarker(Plugin plugin) {
        this.key = new NamespacedKey(plugin, KEY);
    }

    public void mark(Bee bee) {
        bee.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        bee.customName(Component.text("Drosophila melanogaster"));
        bee.setCustomNameVisible(true);
        bee.setGlowing(true);
    }

    public boolean isMarked(Entity entity) {
        if (!(entity instanceof Bee)) {
            return false;
        }
        Byte value = entity.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }
}
