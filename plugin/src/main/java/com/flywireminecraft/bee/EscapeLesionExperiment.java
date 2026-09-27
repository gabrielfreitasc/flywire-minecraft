package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Bee;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Level;

/**
 * F14 (27/09/2026) — experimento de lesão pro subcircuito `escape` (fuga por
 * looming/dano), mesmo desenho estatístico e mesmo formato de CSV dos
 * experimentos anteriores (F4/F7) — reaproveita
 * {@code sim/tools/lesion_analysis.py} sem mudar nada nele.
 *
 * <p><b>Estímulo é SCRIPTED, não ambiente/evento externo</b> (diferente de
 * {@link TouchLesionExperiment}, que precisa de obstáculo, e de
 * {@link HygroLesionExperiment}, que precisa de chuva real): a cada trial,
 * no meio da janela de medição, o próprio experimento chama
 * {@code bee.damage(DAMAGE_PER_TRIAL)} — um evento REAL de dano (capturado
 * por {@link DamageTracker} como qualquer hit de verdade, não fabricado no
 * protocolo), só que disparado por código em vez de esperar o jogador ou um
 * mob acertar a abelha. Isso torna o trial 100% repetível sem precisar de
 * ameaça externa por perto. A vida da abelha é restaurada ao máximo antes
 * de cada trial — 20+ trials de dano pequeno não a matam nem acumulam.
 *
 * <p>N trials sorteados aleatoriamente entre escape normal e lesionado
 * (mesma razão de sempre: não confundir condição com deriva ao longo do
 * experimento). Cada trial: teleporta a abelha de volta à origem, zera
 * velocidade e vida, dano no meio da janela, mede comprimento de trajetória.
 * Direção esperada: com escape ativo, `ESCAPE_SPEED_BLOCKS_PER_TICK=0,45` +
 * impulso vertical por ~2,5s (`ESCAPE_LATCH_TICKS`) é mais rápido que voo
 * normal (`MAX_SPEED_BLOCKS_PER_TICK=0,3`) — o grupo NORMAL deveria ter
 * `path_length`/`avg_speed` MAIORES que o LESIONADO (mesmo sentido do
 * looming/damage não mascarado vs. mascarado — oposto do grooming, que PARA
 * a abelha em vez de acelerá-la).
 *
 * <p>Origem em ar aberto (sem obstáculo por perto) evita confundidor de
 * `touch_proximity`/grooming — `setEscapeLesioned` já mascara `damage`
 * também pro `bristle` nesta janela (ver docstring), então mesmo perto de
 * algo o grooming não teria estímulo de dano pra competir.
 */
public final class EscapeLesionExperiment {

    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;
    /** Dano real por trial — pequeno o bastante pra não arriscar matar a abelha em 20+ trials. */
    private static final double DAMAGE_PER_TRIAL = 1.0;

    private final Plugin plugin;
    private final ControlLoop controlLoop;

    public EscapeLesionExperiment(Plugin plugin, ControlLoop controlLoop) {
        this.plugin = plugin;
        this.controlLoop = controlLoop;
    }

    /**
     * @param forcedOrigin se não-nulo, a abelha é teleportada pra cá ANTES do
     *     primeiro trial, no mesmo instante de execução do comando (mesma
     *     lição da F6 sobre deriva de IA nativa entre comandos).
     */
    public void run(Bee bee, int trials, int secondsPerTrial, Location forcedOrigin, CommandSender notify) {
        if (!controlLoop.isRunning()) {
            notify.sendMessage("Loop de controle precisa estar rodando primeiro: /flywirebee control start");
            return;
        }
        if (secondsPerTrial < 4) {
            notify.sendMessage("Aviso: secondsPerTrial < 4 — a janela de fuga (~2,5s) pode não caber no trial.");
        }
        if (forcedOrigin != null) {
            bee.teleport(forcedOrigin);
            bee.setVelocity(new Vector(0, 0, 0));
        }
        Location origin = bee.getLocation().clone();
        List<TrialResult> results = new ArrayList<>();
        notify.sendMessage("Experimento de lesão de fuga iniciado: " + trials + " trials de "
                + secondsPerTrial + "s.");
        plugin.getLogger().info("[EscapeLesionExperiment] início — " + trials + " trials x " + secondsPerTrial
                + "s, origem=" + formatLocation(origin));
        runTrial(bee, origin, 0, trials, secondsPerTrial * TICKS_PER_SECOND, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, int trialIndex, int totalTrials, int durationTicks,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            plugin.getLogger().warning("[EscapeLesionExperiment] abelha sumiu — abortando experimento");
            controlLoop.setEscapeLesioned(false);
            notify.sendMessage("Abelha sumiu — experimento abortado.");
            return;
        }
        if (trialIndex >= totalTrials) {
            finish(results, notify);
            return;
        }

        boolean lesioned = RNG.nextBoolean();
        bee.teleport(origin);
        bee.setVelocity(new Vector(0, 0, 0));
        bee.setHealth(bee.getMaxHealth());
        controlLoop.setEscapeLesioned(lesioned);

        plugin.getLogger().info(String.format(
                "[EscapeLesionExperiment] trial %d/%d — lesionado=%s", trialIndex + 1, totalTrials, lesioned));

        int damageAtTick = durationTicks / 4; // dano perto do início, sobra janela pra medir a fuga inteira

        new BukkitRunnable() {
            int tick = 0;
            double pathLength = 0.0;
            Location last = bee.getLocation().clone();

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    plugin.getLogger().warning("[EscapeLesionExperiment] abelha sumiu durante o trial — abortando");
                    controlLoop.setEscapeLesioned(false);
                    notify.sendMessage("Abelha sumiu durante o experimento — abortado.");
                    cancel();
                    return;
                }

                if (tick == damageAtTick) {
                    bee.damage(DAMAGE_PER_TRIAL); // evento REAL, capturado por DamageTracker
                }

                Location current = bee.getLocation();
                pathLength += current.distance(last);
                last = current.clone();
                tick++;

                if (tick >= durationTicks) {
                    double durationS = durationTicks / (double) TICKS_PER_SECOND;
                    results.add(new TrialResult(trialIndex, lesioned, pathLength, pathLength / durationS));
                    cancel();
                    runTrial(bee, origin, trialIndex + 1, totalTrials, durationTicks, results, notify);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void finish(List<TrialResult> results, CommandSender notify) {
        controlLoop.setEscapeLesioned(false);

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[EscapeLesionExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, "escape_lesion_experiment.csv");
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println("trial,lesioned,path_length,avg_speed");
            for (TrialResult r : results) {
                // Locale.ROOT — nunca o locale padrão da JVM (pt_BR usa vírgula
                // decimal, que colide com a vírgula do CSV e corrompe o arquivo
                // silenciosamente).
                out.printf(Locale.ROOT, "%d,%s,%.4f,%.4f%n",
                        r.trial(), r.lesioned(), r.pathLength(), r.avgSpeed());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[EscapeLesionExperiment] falha ao escrever CSV", e);
        }

        plugin.getLogger().info("[EscapeLesionExperiment] concluído — " + results.size()
                + " trials, CSV em " + file.getAbsolutePath());
        notify.sendMessage("Experimento concluído — " + results.size() + " trials. CSV em "
                + file.getAbsolutePath() + ". Analisar com "
                + "sim/tools/lesion_analysis.py <caminho do escape_lesion_experiment.csv>");
    }

    private static String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private record TrialResult(int trial, boolean lesioned, double pathLength, double avgSpeed) {
    }
}
