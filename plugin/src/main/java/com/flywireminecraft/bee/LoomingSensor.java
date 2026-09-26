package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Comparator;

/**
 * F9/AD-20 — sinal de "looming" pro subcircuito `escape` (fuga por looming,
 * semente LC4/LPLC2/DNp01/DNp02 — ver {@code sim/tools/build_f9_circuit.py}). Looming
 * de verdade, na literatura (Schiff et al. 1962; Ache et al. 2019), é a taxa
 * de EXPANSÃO ANGULAR de um objeto no campo visual — Bukkit não expõe campo
 * visual nem tamanho angular de entidade nenhuma. Proxy de engenharia,
 * decisão do usuário (24/09/2026, escolhido entre 3 opções via
 * {@code AskUserQuestion}): pra um objeto de tamanho aproximadamente fixo
 * (mob, jogador), taxa de expansão angular ≈ taxa de aproximação — rastreia
 * o DESLOCAMENTO DA AMEAÇA (não a distância bruta, ver bug real abaixo)
 * tick a tick e sinaliza quando ela mesma está vindo rápido na direção da
 * abelha, não só "perto" (isso já é {@code alarm_hostile_mob} do
 * `johnston`, sinal de NÍVEL de distância absoluta — este é de VELOCIDADE de
 * aproximação, distinção deliberada).
 *
 * <p>Mesmo escopo de ameaça de {@link AlarmSensor#isHostileMobNearby} —
 * {@code Monster} (mob hostil) ou {@code Player} — não qualquer
 * {@code LivingEntity}; um mob passivo (vaca, aldeão) se aproximando não é
 * plausível biologicamente como estímulo de fuga.
 *
 * <p><b>Bug real, achado do usuário (26/09/2026) — distância bruta não
 * distingue QUEM está se aproximando.</b> Primeira versão media só a queda
 * de distância entre ticks — funciona quando a AMEAÇA vem até a abelha, mas
 * dispara IGUAL quando é a PRÓPRIA abelha que voa rápido até um jogador
 * parado (ex.: perseguindo comida na mão dele pro `taste`, F10) — a
 * distância cai rápido nos dois casos, e o sensor não tinha como saber
 * qual lado se moveu. Biologicamente, um animal real distingue expansão
 * visual AUTOGERADA (seu próprio movimento) de expansão causada por algo
 * vindo até ele — mecanismo de cópia eferente, ausente aqui até esta
 * correção. Corrigido rastreando a POSIÇÃO da ameaça mais próxima (não só
 * a distância) entre ticks: {@link #recordTick} agora mede quanto a
 * AMEAÇA se deslocou na direção da abelha, ignorando o quanto a abelha
 * mesma se moveu. Se a ameaça estiver parada (jogador parado segurando
 * comida) e a abelha voar até ela, o deslocamento da ameaça é ~0 —
 * looming não dispara, só o `appetite`/`taste` correto.
 *
 * <p><b>Limitação conhecida, não corrigida ainda:</b> rastreia a ameaça
 * MAIS PRÓXIMA a cada tick, não uma entidade específica perseguida entre
 * ticks — usa a POSIÇÃO ANTERIOR da "mais próxima de antes" mesmo que a
 * mais próxima de agora seja uma entidade DIFERENTE (mob A a 9 blocos vira
 * mob B a 3 blocos). Nesse caso o "deslocamento" comparado é entre duas
 * entidades diferentes, não uma perseguida no tempo — pode gerar falso
 * positivo/negativo ocasional. Mesma disciplina de
 * {@link AlarmSensor}/{@link ShelterSensor}: raio e limiar são estimativa de
 * engenharia, primeira tentativa, não calibrados contra nada — corrigir a
 * partir de bug real observado em servidor, não especular agora.
 */
public final class LoomingSensor {

    /** Raio de busca por ameaça, em blocos. Provisório — ver docstring da classe. */
    private static final double SEARCH_RADIUS_BLOCKS = 12.0;

    /**
     * Deslocamento da AMEAÇA (não da abelha) na direção da abelha, acima
     * disto num tick (0,05s a 20Hz), conta como looming.
     *
     * <p><b>Histórico de recalibração (25/09/2026, servidor real):</b>
     * valor original (0,3) era MAIOR que a velocidade de sprint do próprio
     * Minecraft (~5,6 blocos/s = 0,28 blocos/tick) — nunca disparava, nem
     * no sprint mais rápido em linha reta. Testado 0,15 (abaixo de
     * andar~0,22 e correr~0,28) — sensível demais, andar um passo já
     * disparava. Recalibrado pra 0,25 — entre andar e correr, exige
     * aproximação de verdade. PROVISÓRIO, mesma disciplina dos outros
     * raios — ajustar de novo a partir de bug real observado.
     */
    private static final double CLOSING_SPEED_THRESHOLD_BLOCKS_PER_TICK = 0.25;

    private Location lastThreatLocation = null;
    private volatile boolean loomingNow = false;

    /**
     * Chamar uma vez por tick. Mede quanto a ameaça mais próxima se
     * deslocou NA DIREÇÃO da abelha desde a chamada anterior — não a queda
     * de distância bruta (ver bug real na docstring da classe: isso
     * confundia a PRÓPRIA abelha se aproximando de um alvo parado com o
     * alvo vindo até ela). {@code null}/ameaça ausente em qualquer uma das
     * duas leituras zera o sinal (evita falso positivo de "acabou de
     * entrar no raio").
     */
    public void recordTick(Bee bee) {
        Entity nearest = nearestThreat(bee);
        Location currentThreatLocation = nearest != null ? nearest.getLocation() : null;

        if (lastThreatLocation != null && currentThreatLocation != null) {
            Location beeLocation = bee.getLocation();
            Vector towardBee = beeLocation.toVector().subtract(lastThreatLocation.toVector());
            if (towardBee.lengthSquared() > 1.0E-6) {
                Vector threatDisplacement = currentThreatLocation.toVector().subtract(lastThreatLocation.toVector());
                // Componente do deslocamento da AMEAÇA na direção da abelha
                // — positivo significa que ela veio pra cá; negativo/zero
                // significa que se afastou ou não se moveu (a abelha pode
                // ter se movido pra perto dela, isso não conta aqui).
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

    /** Reinicia o rastreamento de posição — chamar em {@code start()}, mesmo padrão de {@code TouchSensor.reset()}. */
    public void reset() {
        lastThreatLocation = null;
        loomingNow = false;
    }

    /** Sinal de nível — resultado do último {@link #recordTick}, não recomputado aqui. */
    public boolean isLoomingThreat() {
        return loomingNow;
    }

    private Entity nearestThreat(Bee bee) {
        return bee.getWorld()
                .getNearbyEntities(bee.getLocation(), SEARCH_RADIUS_BLOCKS, SEARCH_RADIUS_BLOCKS, SEARCH_RADIUS_BLOCKS)
                .stream()
                .filter(e -> e instanceof Monster || e instanceof Player)
                .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(bee.getLocation())))
                .orElse(null);
    }

    /**
     * F9/AD-20 — direção horizontal (unitária) PRA LONGE da ameaça mais
     * próxima agora, pro comportamento de fuga em {@code MotorMapping}
     * (mesmo padrão de {@link ShelterSensor#findNearbyShelterDirection}:
     * chamado só quando o canal está ativo, não todo tick). {@code null} se
     * não há ameaça no raio de busca agora, ou se a abelha está exatamente
     * em cima da ameaça (direção horizontal indefinida) — quem chama cai de
     * volta pra {@code heading}, mesma convenção do hint de abrigo.
     */
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
