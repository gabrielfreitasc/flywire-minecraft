package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Monster;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Sinais de "toque" além de dano ({@link DamageTracker}, que já existia e
 * não muda). Decisão original do usuário (20/09/2026): toque engloba
 * contato com bloco, dano, e aproximação de mob, jogador ou objeto do jogo.
 *
 * <h2>{@code touch_contact} — bloco (sensor mantido, NÃO alimenta mais o `bristle`)</h2>
 *
 * Bukkit não tem um evento limpo de "entidade colidiu com bloco durante voo
 * comandado por código" ({@code EntityMoveEvent} não existe pra mobs; a
 * colisão de física é interna ao motor do jogo). Heurística: comparar o
 * deslocamento REAL da abelha no tick contra o que a velocidade comandada
 * deveria produzir. Em voo livre isso fica perto de 100% do esperado (~96%
 * medido na F4); se cair bem abaixo disso, é sinal de ter esbarrado em algo
 * sólido. <b>✅ Validado em servidor real</b> (F7, ver plugin/README.md).
 *
 * <p><b>Ajuste (F9, 25/09/2026, pedido do usuário) — separado do gatilho de
 * `grooming`.</b> Bater numa PAREDE não é o mesmo estímulo biológico que
 * algo pousar/tocar o corpo da mosca (mesmo princípio já usado pra restringir
 * o `AlarmSensor` do johnston — Eberl, Hardy &amp; Kernan 2000). `touch_contact`
 * continua sendo computado e logado (telemetria/diagnóstico útil — ex.:
 * confirmar que ela não está atravessando bloco), mas `server.py` não usa
 * mais esse campo pra estimular a semente do `bristle` — ver
 * `docs/02-arquitetura.md`. Isso NÃO afeta a detecção de obstáculo/
 * travamento ({@code ControlLoop.STUCK_CHECK}/{@code RECOVERY_BOOST}) — esse
 * mecanismo é inteiramente separado, mede deslocamento real diretamente,
 * nunca leu {@code touch_contact}.
 *
 * <h2>{@code touch_proximity} — mob, animal, NPC ou jogador</h2>
 *
 * Sinal de NÍVEL, não de borda — verdadeiro enquanto algo estiver dentro do
 * raio, não só no instante em que entrou. {@link #PROXIMITY_RADIUS} não foi
 * calibrado contra nada — primeira estimativa.
 *
 * <p><b>Escopo restrito (F9, 25/09/2026, mesmo pedido acima).</b> Antes
 * batia em qualquer {@code LivingEntity}/{@code Item} — item largado no
 * chão contava igual a um mob de verdade. Restrito a {@link Monster}
 * (zumbi, esqueleto...), {@link Animals} (vaca, porco, galinha...),
 * {@link NPC} (aldeão, mercador ambulante) e {@link Player} — a lista que o
 * usuário pediu, biologicamente mais próxima de "algo vivo tocou/chegou
 * perto de mim" do que "qualquer objeto no raio".
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
                .anyMatch(e -> e != bee
                        && (e instanceof Monster || e instanceof Animals || e instanceof NPC || e instanceof Player));
    }
}
