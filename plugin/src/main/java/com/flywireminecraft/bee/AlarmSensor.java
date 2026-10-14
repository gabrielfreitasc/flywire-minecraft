package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/** F8 — sinal de "som" pro subcircuito `johnston` (vento/som). */
public final class AlarmSensor implements Listener {
    private static final double EXPLOSION_ALARM_RADIUS = 16.0;
    private static final double HOSTILE_MOB_RADIUS = 8.0;
    // F14
    static final int MUSIC_RADIUS_BLOCKS = 6;

    private final FlywireBeeMarker marker;
    private volatile boolean explosionSinceLastRead = false;

    public AlarmSensor(FlywireBeeMarker marker) {
        this.marker = marker;
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        markIfNearBee(event.getLocation());
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        markIfNearBee(event.getBlock().getLocation());
    }

    private void markIfNearBee(Location explosionLocation) {
        World world = explosionLocation.getWorld();
        if (world == null) {
            return;
        }
        boolean near = world.getEntitiesByClass(Bee.class).stream()
                .filter(marker::isMarked)
                .anyMatch(bee -> bee.getLocation().distance(explosionLocation) <= EXPLOSION_ALARM_RADIUS);
        if (near) {
            explosionSinceLastRead = true;
        }
    }

    /** Sinal de borda, mesmo padrão de DamageTracker#consumeRecentDamage(). */
    public boolean consumeExplosion() {
        boolean was = explosionSinceLastRead;
        explosionSinceLastRead = false;
        return was;
    }

    /** Sinal de nível — verdadeiro agora, não "desde a última leitura". */
    public boolean isHostileMobNearby(Bee bee) {
        return bee.getWorld()
                .getNearbyEntities(bee.getLocation(), HOSTILE_MOB_RADIUS, HOSTILE_MOB_RADIUS, HOSTILE_MOB_RADIUS)
                .stream()
                .anyMatch(e -> e instanceof Monster);
    }

    /** Sinal de nível — verdadeiro agora, não "desde a última leitura". */
    public boolean isMusicNearby(Bee bee) {
        Location center = bee.getLocation();
        World world = center.getWorld();
        if (world == null) {
            return false;
        }
        int bx = center.getBlockX();
        int by = center.getBlockY();
        int bz = center.getBlockZ();
        for (int dx = -MUSIC_RADIUS_BLOCKS; dx <= MUSIC_RADIUS_BLOCKS; dx++) {
            for (int dy = -MUSIC_RADIUS_BLOCKS; dy <= MUSIC_RADIUS_BLOCKS; dy++) {
                for (int dz = -MUSIC_RADIUS_BLOCKS; dz <= MUSIC_RADIUS_BLOCKS; dz++) {
                    if (world.getBlockAt(bx + dx, by + dy, bz + dz).getState() instanceof Jukebox jukebox
                            && jukebox.isPlaying()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
