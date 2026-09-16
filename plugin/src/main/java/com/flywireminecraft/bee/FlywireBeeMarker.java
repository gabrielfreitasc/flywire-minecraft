package com.flywireminecraft.bee;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Marca qual abelha do mundo é "a" abelha controlada pelo conectoma.
 *
 * <p>v1 é uma simulação = uma abelha (ver docs/00-visao-geral.md) — a marca
 * existe para distinguir essa abelha de qualquer outra abelha comum que
 * exista no mundo. Guardada em {@link org.bukkit.persistence.PersistentDataContainer},
 * não como referência de objeto Java, porque sobrevive a save/reload de
 * chunk e a restart do servidor; uma referência em memória não sobreviveria.
 */
public final class FlywireBeeMarker {

    private static final String KEY = "controlled";

    private final NamespacedKey key;

    public FlywireBeeMarker(Plugin plugin) {
        this.key = new NamespacedKey(plugin, KEY);
    }

    public void mark(Bee bee) {
        bee.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        bee.customName(Component.text("FlyWire Bee"));
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
