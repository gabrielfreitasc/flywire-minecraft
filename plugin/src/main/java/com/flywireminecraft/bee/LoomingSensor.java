package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Comparator;

/** Sensor de looming do circuito escape (F9/AD-20): ameaça cuja distância cai rápido. */
public final class LoomingSensor {
    private static final double SEARCH_RADIUS_BLOCKS = 12.0;

    private static final double CLOSING_SPEED_THRESHOLD_BLOCKS_PER_TICK = 0.25;

    private Location lastThreatLocation = null;
    private volatile boolean loomingNow = false;
    private volatile boolean ignorePlayer = false;

    void setIgnorePlayer(boolean ignore) {
        this.ignorePlayer = ignore;
    }

    /** Chamar uma vez por tick. */
    public void recordTick(Bee bee) {
        Entity nearest = nearestThreat(bee);
        Location currentThreatLocation = nearest != null ? nearest.getLocation() : null;

        if (lastThreatLocation != null && currentThreatLocation != null) {
            Location beeLocation = bee.getLocation();
            Vector towardBee = beeLocation.toVector().subtract(lastThreatLocation.toVector());
            if (towardBee.lengthSquared() > 1.0E-6) {
                Vector threatDisplacement = currentThreatLocation.toVector().subtract(lastThreatLocation.toVector());
                double approachSpeed = threatDisplacement.dot(towardBee.normalize());
                loomingNow = approachSpeed > CLOSING_SPEED_THRESHOLD_BLOCKS_PER_TICK;
            } else {
                loomingNow = false;
            }
        } else {
            loomingNow = false;
        }
        lastThreatLocation = currentThreatLocation;
    }

    /** Reinicia o rastreamento de posição — chamar em start(), mesmo padrão de TouchSensor.reset(). */
    public void reset() {
        lastThreatLocation = null;
        loomingNow = false;
    }

    /** Sinal de nível — resultado do último #recordTick, não recomputado aqui. */
    public boolean isLoomingThreat() {
        return loomingNow;
    }

    private Entity nearestThreat(Bee bee) {
        boolean ignorePlayerNow = ignorePlayer;
        return bee.getWorld()
                .getNearbyEntities(bee.getLocation(), SEARCH_RADIUS_BLOCKS, SEARCH_RADIUS_BLOCKS, SEARCH_RADIUS_BLOCKS)
                .stream()
                .filter(e -> e instanceof Monster || (e instanceof Player && !ignorePlayerNow))
                .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(bee.getLocation())))
                .orElse(null);
    }

    public Vector fleeDirectionAwayFromNearestThreat(Bee bee) {
        Entity nearest = nearestThreat(bee);
        if (nearest == null) {
            return null;
        }
        Vector away = bee.getLocation().toVector().subtract(nearest.getLocation().toVector());
        away.setY(0);
        if (away.lengthSquared() < 1.0E-6) {
            return null;
        }
        return away.normalize();
    }
}
