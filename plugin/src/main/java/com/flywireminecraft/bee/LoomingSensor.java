package com.flywireminecraft.bee;

import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
 * a distância até a ameaça mais próxima tick a tick e sinaliza quando ela
 * está caindo rápido, não só "perto" (isso já é {@code alarm_hostile_mob} do
 * `johnston`, sinal de NÍVEL de distância absoluta — este é de VELOCIDADE de
 * aproximação, distinção deliberada).
 *
 * <p>Mesmo escopo de ameaça de {@link AlarmSensor#isHostileMobNearby} —
 * {@code Monster} (mob hostil) ou {@code Player} — não qualquer
 * {@code LivingEntity}; um mob passivo (vaca, aldeão) se aproximando não é
 * plausível biologicamente como estímulo de fuga.
 *
 * <p><b>Limitação conhecida, não corrigida ainda:</b> rastreia a distância
 * até a ameaça MAIS PRÓXIMA a cada tick, não uma entidade específica
 * perseguida entre ticks. Se a ameaça mais próxima TROCAR de um tick pro
 * outro (ex.: mob A a 9 blocos vira mob B a 3 blocos, sem nenhum dos dois
 * ter se movido rápido de verdade), a "distância mais próxima" cai de
 * repente e dispara um falso positivo de looming. Mesma disciplina de
 * {@link AlarmSensor}/{@link ShelterSensor}: raio e limiar são estimativa de
 * engenharia, primeira tentativa, não calibrados contra nada — corrigir a
 * partir de bug real observado em servidor, não especular agora.
 */
public final class LoomingSensor {

    /** Raio de busca por ameaça, em blocos. Provisório — ver docstring da classe. */
    private static final double SEARCH_RADIUS_BLOCKS = 12.0;

    /**
     * Queda de distância acima disto, num tick (0,05s a 20Hz), conta como
     * looming.
     *
     * <p><b>Bug real, dois testes em servidor real (25/09/2026):</b> valor
     * original (0,3) era MAIOR que a velocidade de sprint do próprio
     * Minecraft (~5,6 blocos/s = 0,28 blocos/tick) — usuário correu direto
     * na direção dela, de fora do raio de busca, e nunca disparou, porque
     * matematicamente não tinha como (nem no sprint mais rápido em linha
     * reta a distância cai mais que isso por tick). Erro de direção na
     * calibração original: a docstring já citava 0,28 como referência, mas
     * o valor final ficou ACIMA, não abaixo.
     *
     * <p>Andar (~4,3 blocos/s ≈ 0,22 blocos/tick) e correr (~0,28) ficam
     * perto um do outro. Testado 0,15 (abaixo dos dois) — usuário reportou
     * que andar um único passo já disparava looming, sensível demais pra
     * ser "aproximação com intenção", virou ruído. Recalibrado pro usuário
     * pra 0,25 — entre andar e correr, exige aproximação de verdade (correr
     * ou quase) sem cair no extremo oposto (0,3 original, acima até do
     * sprint, nunca disparava). PROVISÓRIO, mesma disciplina dos outros
     * raios — ajustar de novo a partir de bug real observado.
     */
    private static final double CLOSING_SPEED_THRESHOLD_BLOCKS_PER_TICK = 0.25;

    private double lastDistance = Double.POSITIVE_INFINITY;
    private volatile boolean loomingNow = false;

    /**
     * Chamar uma vez por tick. Compara a distância até a ameaça mais
     * próxima agora contra a distância gravada na chamada anterior — só
     * sinaliza looming quando as duas leituras encontraram ameaça (evita
     * falso positivo de "distância caiu de infinito pra 5" só por uma
     * ameaça ter acabado de entrar no raio de busca).
     */
    public void recordTick(Bee bee) {
        double currentDistance = nearestThreatDistance(bee);
        boolean bothFinite = Double.isFinite(lastDistance) && Double.isFinite(currentDistance);
        double closingSpeed = bothFinite ? lastDistance - currentDistance : 0.0;
        loomingNow = bothFinite && closingSpeed > CLOSING_SPEED_THRESHOLD_BLOCKS_PER_TICK;
        lastDistance = currentDistance;
    }

    /** Reinicia o rastreamento de distância — chamar em {@code start()}, mesmo padrão de {@code TouchSensor.reset()}. */
    public void reset() {
        lastDistance = Double.POSITIVE_INFINITY;
        loomingNow = false;
    }

    /** Sinal de nível — resultado do último {@link #recordTick}, não recomputado aqui. */
    public boolean isLoomingThreat() {
        return loomingNow;
    }

    private double nearestThreatDistance(Bee bee) {
        return bee.getWorld()
                .getNearbyEntities(bee.getLocation(), SEARCH_RADIUS_BLOCKS, SEARCH_RADIUS_BLOCKS, SEARCH_RADIUS_BLOCKS)
                .stream()
                .filter(e -> e instanceof Monster || e instanceof Player)
                .mapToDouble(e -> ((LivingEntity) e).getLocation().distance(bee.getLocation()))
                .min()
                .orElse(Double.POSITIVE_INFINITY);
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
        Entity nearest = bee.getWorld()
                .getNearbyEntities(bee.getLocation(), SEARCH_RADIUS_BLOCKS, SEARCH_RADIUS_BLOCKS, SEARCH_RADIUS_BLOCKS)
                .stream()
                .filter(e -> e instanceof Monster || e instanceof Player)
                .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(bee.getLocation())))
                .orElse(null);
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
