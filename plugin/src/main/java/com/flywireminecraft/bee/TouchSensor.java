package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Monster;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Sinais de "toque" além de dano (DamageTracker, que já existia e não muda). */
public final class TouchSensor {
    private static final double PROXIMITY_RADIUS = 3.0;

    // F4
    private static final double CONTACT_RATIO_THRESHOLD = 0.5;

    private Location lastLocation;
    private volatile boolean contactSinceLastRead = false;
    private volatile boolean ignorePlayer = false;

    void setIgnorePlayer(boolean ignore) {
        this.ignorePlayer = ignore;
    }

    public void recordTick(Bee bee, Vector lastAppliedVelocity) {
        Location current = bee.getLocation();
        if (lastLocation != null && lastAppliedVelocity.lengthSquared() > 1.0E-6) {
            double expected = lastAppliedVelocity.length();
            double actual = current.distance(lastLocation);
            if (actual < expected * CONTACT_RATIO_THRESHOLD) {
                contactSinceLastRead = true;
            }
        }
        lastLocation = current.clone();
    }

    /** Reinicia o rastreamento de posição — chamar em start(), mesmo padrão de heading. */
    public void reset() {
        lastLocation = null;
        contactSinceLastRead = false;
    }

    /** Sinal de borda, mesmo padrão de DamageTracker#consumeRecentDamage(). */
    public boolean consumeContact() {
        boolean was = contactSinceLastRead;
        contactSinceLastRead = false;
        return was;
    }

    /** Sinal de nível — verdadeiro agora, não "desde a última leitura". */
    public boolean isNearSomething(Bee bee) {
        boolean ignorePlayerNow = ignorePlayer;
        return bee.getWorld()
                .getNearbyEntities(bee.getLocation(), PROXIMITY_RADIUS, PROXIMITY_RADIUS, PROXIMITY_RADIUS)
                .stream()
                .anyMatch(e -> e != bee
                        && (e instanceof Monster || e instanceof Animals || e instanceof NPC
                                || (e instanceof Player && !ignorePlayerNow)));
    }
}
