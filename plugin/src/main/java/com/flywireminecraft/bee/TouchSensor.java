package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Sinais de "toque" além de dano ({@link DamageTracker}, que já existia e
 * não muda). Decisão do usuário (20/09/2026): toque engloba contato com
 * bloco, dano, e aproximação de mob, jogador ou objeto do jogo — não é um
 * evento Bukkit só, é uma família de gatilhos.
 *
 * <h2>{@code touch_contact} — bloco</h2>
 *
 * Bukkit não tem um evento limpo de "entidade colidiu com bloco durante voo
 * comandado por código" ({@code EntityMoveEvent} não existe pra mobs; a
 * colisão de física é interna ao motor do jogo). Heurística adotada:
 * comparar o deslocamento REAL da abelha no tick contra o que a velocidade
 * comandada deveria produzir. Em voo livre isso fica perto de 100% do
 * esperado (~96% medido na F4, perda normal de resistência do ar,
 * ver {@code plugin/README.md}); se cair bem abaixo disso, é sinal de ter
 * esbarrado em algo sólido — a física não deixa a abelha atravessar bloco.
 *
 * <p><b>{@link #CONTACT_RATIO_THRESHOLD} é uma primeira estimativa,
 * PROVISÓRIA — mesma ressalva de {@code MotorMapping.MAX_YAW_RADIANS_PER_TICK}:
 * não validada em servidor real ainda.</b> Precisa testar (voar a abelha de
 * propósito contra uma parede e conferir se {@code touch_contact} acende no
 * log, e que NÃO acende em voo livre normal) antes de confiar no valor.
 * Risco conhecido de falso positivo: rajadas de vento/água também podem
 * reduzir o deslocamento sem colisão — não investigado ainda.
 *
 * <h2>{@code touch_proximity} — mob, jogador ou objeto</h2>
 *
 * Sinal de NÍVEL, não de borda — verdadeiro enquanto algo estiver dentro do
 * raio, não só no instante em que entrou (diferente de {@code touch_contact}
 * e de {@code damage}, que são consumidos e resetam). {@link #PROXIMITY_RADIUS}
 * também não foi calibrado contra nada — primeira estimativa.
 */
public final class TouchSensor {

    /** Raio de proximidade, em blocos. Provisório — ver docstring da classe. */
    private static final double PROXIMITY_RADIUS = 3.0;

    /**
     * Deslocamento real abaixo desta fração do esperado conta como contato.
     * Provisório — ver docstring da classe. ~96% medido em voo livre (F4);
     * 50% dá margem generosa antes de considerar "bateu".
     */
    private static final double CONTACT_RATIO_THRESHOLD = 0.5;

    private Location lastLocation;
    private volatile boolean contactSinceLastRead = false;

    /**
     * Chamar uma vez por tick, ANTES de aplicar a velocidade deste tick —
     * compara a posição atual (resultado da velocidade aplicada no tick
     * ANTERIOR) contra a posição gravada na chamada anterior, usando
     * {@code lastAppliedVelocity} (a velocidade que esteve ativa durante o
     * tick que acabou de passar, não a que está prestes a ser aplicada
     * agora).
     */
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

    /** Reinicia o rastreamento de posição — chamar em {@code start()}, mesmo padrão de {@code heading}. */
    public void reset() {
        lastLocation = null;
        contactSinceLastRead = false;
    }

    /** Sinal de borda, mesmo padrão de {@link DamageTracker#consumeRecentDamage()}. */
    public boolean consumeContact() {
        boolean was = contactSinceLastRead;
        contactSinceLastRead = false;
        return was;
    }

    /** Sinal de nível — verdadeiro agora, não "desde a última leitura". */
    public boolean isNearSomething(Bee bee) {
        return bee.getWorld()
                .getNearbyEntities(bee.getLocation(), PROXIMITY_RADIUS, PROXIMITY_RADIUS, PROXIMITY_RADIUS)
                .stream()
                .anyMatch(e -> e != bee && (e instanceof Player || e instanceof LivingEntity || e instanceof Item));
    }
}
