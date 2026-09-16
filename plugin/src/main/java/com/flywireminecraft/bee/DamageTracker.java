package com.flywireminecraft.bee;

import org.bukkit.entity.Bee;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Sinal de dano para o campo "damage" do protocolo (ver docs/02-arquitetura.md).
 *
 * <p>v1 = uma abelha (docs/00-visao-geral.md): uma flag simples basta, não
 * precisa rastrear por entidade. É consumida (lida e resetada) a cada troca
 * com a ponte — sinal de borda, não de nível: "houve dano desde a última
 * leitura", não "está tomando dano agora".
 */
public final class DamageTracker implements Listener {

    private volatile boolean damagedRecently = false;

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Bee) {
            damagedRecently = true;
        }
    }

    public boolean consumeRecentDamage() {
        boolean was = damagedRecently;
        damagedRecently = false;
        return was;
    }
}
