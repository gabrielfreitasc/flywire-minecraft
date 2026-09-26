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

/**
 * F8 — sinal de "som" pro subcircuito `johnston` (vento/som). Achado do
 * usuário (23/09/2026, citando Eberl, Hardy &amp; Kernan 2000 — mesmo
 * mecanismo de transdução de toque e som em Drosophila): órgão de
 * Johnston deveria reagir a eventos que uma mosca de verdade reconheceria
 * como ameaça, não qualquer proximidade genérica — {@link TouchSensor}
 * já cobre "qualquer mob/jogador/item por perto" pro `bristle`, e toque
 * com folha/objeto pequeno é inofensivo (não dispara fuga,
 * evolutivamente). Três gatilhos:
 *
 * <ul>
 *   <li><b>Explosão por perto</b> ({@code alarm_explosion}) — sinal de
 *       BORDA, mesmo padrão de {@link DamageTracker}: evento Bukkit real
 *       ({@code EntityExplodeEvent}/{@code BlockExplodeEvent}), filtrado
 *       por distância até a abelha marcada (o evento não sabe por si só
 *       se foi "perto" dela).</li>
 *   <li><b>Mob HOSTIL por perto</b> ({@code alarm_hostile_mob}) — sinal de
 *       NÍVEL, mesmo padrão de {@code touch_proximity} mas com dois
 *       diferenças propositais: só {@code Monster} (zumbi, esqueleto,
 *       aranha, creeper — não qualquer {@code LivingEntity}), e raio maior
 *       ({@link #HOSTILE_MOB_RADIUS} &gt; {@code TouchSensor.PROXIMITY_RADIUS}
 *       — som viaja mais longe que toque).</li>
 *   <li><b>Música tocando por perto</b> ({@code sound_music}) — sinal de
 *       NÍVEL, achado do usuário (24/09/2026): testou perto de uma jukebox
 *       tocando disco e viu {@code startle} só no ruído de fundo (o gatilho
 *       original só cobre AMEAÇA, não som em geral) — pediu que som
 *       ambiente também contasse, mais fiel ao órgão de Johnston responder
 *       a som em geral, não só alarme. Varre blocos num raio ao redor da
 *       abelha procurando {@code Jukebox#isPlaying()} — Bukkit não tem
 *       evento de "tocando agora" nem busca de bloco por proximidade
 *       (diferente de entidades), só o estado do bloco em si.</li>
 * </ul>
 *
 * <p>Raios PROVISÓRIOS, engenharia — não calibrados contra nada, mesma
 * disciplina de {@code TouchSensor}/{@code MotorMapping}.
 */
public final class AlarmSensor implements Listener {

    private static final double EXPLOSION_ALARM_RADIUS = 16.0; // blocos
    private static final double HOSTILE_MOB_RADIUS = 8.0; // blocos — maior que o de toque (3)
    // Varredura de bloco, não de entidade — sem API de "nearby blocks" no
    // Bukkit. Raio menor que o do mob hostil por custo (varredura é O(raio³)
    // blocos, não indexada como entidades) — estimativa de engenharia.
    private static final int MUSIC_RADIUS_BLOCKS = 6;

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

    /** Sinal de borda, mesmo padrão de {@link DamageTracker#consumeRecentDamage()}. */
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

    /**
     * Sinal de nível — verdadeiro agora, não "desde a última leitura".
     * Varre um cubo de blocos ao redor da abelha procurando uma jukebox
     * tocando ({@link Jukebox#isPlaying()}). Achado do usuário (24/09/2026)
     * — ver docstring da classe.
     */
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
