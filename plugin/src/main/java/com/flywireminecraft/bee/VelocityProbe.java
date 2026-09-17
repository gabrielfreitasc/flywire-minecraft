package com.flywireminecraft.bee;

import com.destroystokyo.paper.entity.ai.MobGoals;
import org.bukkit.Location;
import org.bukkit.entity.Bee;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Spikes técnicos isolados para responder: dá pra mover a abelha por código,
 * e de que jeito? Nenhum depende da ponte nem do vetor motor de verdade — só
 * testam mecanismos de movimento diferentes por alguns segundos, logando
 * posição. Isola "dá pra mover por código" de "o vetor motor está certo"
 * (dois riscos bem diferentes — ver docs/03-roadmap-fases.md, risco alto
 * sinalizado desde a F2).
 *
 * <p>Resultado do primeiro spike (setVelocity + IA desligada): deslocamento
 * ZERO em 5s reais, apesar de reaplicar velocidade a cada tick. Hipótese:
 * `setAI(false)` em mob voador trava movimento também, não só decisão — daí
 * os outros modos aqui.
 */
public final class VelocityProbe {

    private static final int DURATION_TICKS = 100; // 5s a 20 ticks/s
    private static final int LOG_EVERY_TICKS = 20;  // 1x por segundo

    private final Plugin plugin;
    private final Logger logger;

    public VelocityProbe(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** Modo 1 (já testado): setVelocity() a cada tick, IA desligada. */
    public void runVelocityNoAI(Bee bee) {
        boolean hadAI = bee.hasAI();
        bee.setAI(false);
        runLoop(bee, "velocity-noai", (b, tick) -> b.setVelocity(new Vector(0.3, 0.0, 0.0)),
                () -> bee.setAI(hadAI));
    }

    /** Modo 2: setVelocity() a cada tick, IA LIGADA — isola se o problema é o NoAI. */
    public void runVelocityWithAI(Bee bee) {
        runLoop(bee, "velocity-ai", (b, tick) -> b.setVelocity(new Vector(0.3, 0.0, 0.0)), () -> {
        });
    }

    /** Modo 3: usa o Pathfinder nativo da abelha, redirecionando o alvo continuamente. */
    public void runPathfinder(Bee bee) {
        runLoop(bee, "pathfinder", (b, tick) -> {
            Location target = b.getLocation().add(5, 0, 0);
            b.getPathfinder().moveTo(target, 1.0);
        }, () -> {
        });
    }

    /** Modo 4: teleporte incremental — garantido funcionar, sem física/colisão. */
    public void runTeleport(Bee bee) {
        runLoop(bee, "teleport", (b, tick) -> {
            Location next = b.getLocation().add(0.3, 0.0, 0.0); // mesma taxa dos outros modos, p/ comparar
            b.teleport(next);
        }, () -> {
        });
    }

    /**
     * Modo 5 — hipótese do usuário (16/09/2026): a "confusão" no experimento
     * dia/noite (F6, resultado nulo) seria a IA nativa competindo pelo
     * controle, não falta de efeito do circuito. {@code setAI(false)} já foi
     * testado e descartado (modo 1 — congela a física inteira, não só a
     * decisão). Alternativa nunca testada: Mob Goal API do Paper
     * ({@link MobGoals#removeGoal}), que remove objetivos específicos sem
     * tocar em {@code setAI}/física. Remove só os que competem com
     * movimento/velocidade — {@code BEE_WANDER} (vagar aleatório),
     * {@code BEE_GO_TO_KNOWN_FLOWER}/{@code BEE_POLLINATE} (o "vazamento" já
     * visto na F5, abelha parou pra polinizar), {@code BEE_GO_TO_HIVE}/
     * {@code BEE_LOCATE_HIVE}/{@code BEE_ENTER_HIVE} (candidato mais forte
     * pro confundidor dia/noite — vanilla bee tenta voltar pra colmeia à
     * noite). Mantém {@code BEE_ATTACK}/{@code BEE_BECOME_ANGRY}/
     * {@code BEE_HURT_BY_OTHER}/{@code BEE_GROW_CROP} — não competem com
     * locomoção no nosso cenário.
     *
     * <p>**Não restaura os goals ao final** (diferente do modo 1, que
     * restaura {@code setAI}) — a API do Paper não expõe uma forma pública
     * de re-registrar a implementação vanilla original a partir do
     * {@code GoalKey}, só de adicionar um {@link com.destroystokyo.paper.entity.ai.Goal}
     * customizado. Pra essa abelha específica voltar a ter os goals padrão,
     * seria preciso {@code /flywirebee kill} + {@code give} (spawna uma
     * abelha nova, com goals default).
     *
     * <p>**✅ Testado em servidor real, 17/09/2026: 28,84 de 30 blocos
     * esperados em 5s (96%, igual ao modo 2 com IA ligada) — a física não
     * trava.** Confirma que a API funciona; não confirma ainda que esses
     * goals específicos explicam o nulo do dia/noite (ver `ControlLoop`,
     * `CompetingGoals`, `docs/03-roadmap-fases.md` F6).
     */
    public void runVelocityNoCompetingGoals(Bee bee) {
        CompetingGoals.disable(bee);
        runLoop(bee, "velocity-no-competing-goals",
                (b, tick) -> b.setVelocity(new Vector(0.3, 0.0, 0.0)), () -> {
                });
    }

    private interface TickAction {
        void apply(Bee bee, int tick);
    }

    private void runLoop(Bee bee, String label, TickAction action, Runnable onFinish) {
        Location start = bee.getLocation().clone();
        logger.info(String.format(Locale.ROOT,
                "[VelocityProbe:%s] início — pos=(%.2f, %.2f, %.2f)",
                label, start.getX(), start.getY(), start.getZ()));

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    logger.warning(String.format("[VelocityProbe:%s] abelha morreu/sumiu — abortando", label));
                    cancel();
                    return;
                }

                action.apply(bee, tick);

                if (tick % LOG_EVERY_TICKS == 0) {
                    Location loc = bee.getLocation();
                    double displaced = loc.distance(start);
                    logger.info(String.format(Locale.ROOT,
                            "[VelocityProbe:%s] t=%dms pos=(%.2f, %.2f, %.2f) deslocado=%.3f vel=%s",
                            label, tick * 50, loc.getX(), loc.getY(), loc.getZ(), displaced, bee.getVelocity()));
                }

                tick++;
                if (tick >= DURATION_TICKS) {
                    Location end = bee.getLocation();
                    double totalDisplacement = end.distance(start);
                    logger.info(String.format(Locale.ROOT,
                            "[VelocityProbe:%s] FIM — deslocamento total=%.3f blocos",
                            label, totalDisplacement));
                    onFinish.run();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
