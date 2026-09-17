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
 * Experimento de lesão — critério de saída da F4 (ver docs/00-visao-geral.md,
 * "Critério de falsificação"): silenciar os fotorreceptores deve mudar o
 * comportamento de forma mensurável. Se não mudar, a simulação não está
 * acoplada — só gera movimento.
 *
 * <p>N trials alternados aleatoriamente entre normal e lesionado (ordem
 * aleatória, não estritamente alternada, para não confundir condição com
 * deriva temporal — luz do mundo muda com o ciclo dia/noite ao longo do
 * experimento). Cada trial: teleporta a abelha de volta à origem, zera
 * velocidade, roda por N segundos medindo comprimento de trajetória (soma
 * das distâncias por tick — mais informativo que deslocamento início-fim,
 * que zera se a abelha só circular no lugar).
 *
 * <p>Grava um CSV (não faz estatística em Java — isso é trabalho de
 * `sim/tools/lesion_analysis.py`, mesma divisão que o resto do projeto:
 * Java executa, Python faz ciência).
 */
public final class LesionExperiment {

    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;

    private final Plugin plugin;
    private final ControlLoop controlLoop;

    public LesionExperiment(Plugin plugin, ControlLoop controlLoop) {
        this.plugin = plugin;
        this.controlLoop = controlLoop;
    }

    /**
     * @param forcedOrigin se não-nulo, a abelha é teleportada pra cá ANTES do
     *     primeiro trial, no mesmo instante de execução do comando — sem
     *     intervalo pra IA nativa derivar (achado F6, 17/09/2026: um comando
     *     de teleporte separado ainda deixava a abelha andar/voar no tempo
     *     entre ele e o comando do experimento rodar). Se nulo, usa a posição
     *     atual da abelha, como antes.
     */
    public void run(Bee bee, int trials, int secondsPerTrial, Location forcedOrigin, CommandSender notify) {
        if (!controlLoop.isRunning()) {
            notify.sendMessage("Loop de controle precisa estar rodando primeiro: /flywirebee control start");
            return;
        }
        if (forcedOrigin != null) {
            bee.teleport(forcedOrigin);
            bee.setVelocity(new Vector(0, 0, 0));
        }
        Location origin = bee.getLocation().clone();
        List<TrialResult> results = new ArrayList<>();
        notify.sendMessage("Experimento de lesão iniciado: " + trials + " trials de " + secondsPerTrial + "s.");
        plugin.getLogger().info("[LesionExperiment] início — " + trials + " trials x " + secondsPerTrial + "s, origem="
                + formatLocation(origin));
        runTrial(bee, origin, 0, trials, secondsPerTrial * TICKS_PER_SECOND, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, int trialIndex, int totalTrials, int durationTicks,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            plugin.getLogger().warning("[LesionExperiment] abelha sumiu — abortando experimento");
            controlLoop.setLesioned(false);
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
        controlLoop.setLesioned(lesioned);

        plugin.getLogger().info(String.format(
                "[LesionExperiment] trial %d/%d — lesionado=%s", trialIndex + 1, totalTrials, lesioned));

        new BukkitRunnable() {
            int tick = 0;
            double pathLength = 0.0;
            Location last = bee.getLocation().clone();

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    plugin.getLogger().warning("[LesionExperiment] abelha sumiu durante o trial — abortando");
                    controlLoop.setLesioned(false);
                    notify.sendMessage("Abelha sumiu durante o experimento — abortado.");
                    cancel();
                    return;
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
        controlLoop.setLesioned(false);

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[LesionExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, "lesion_experiment.csv");
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println("trial,lesioned,path_length,avg_speed");
            for (TrialResult r : results) {
                // Locale.ROOT — nunca o locale padrão da JVM (aqui, pt_BR usa
                // vírgula como separador decimal, que colide com a vírgula do
                // CSV e corrompe o arquivo silenciosamente).
                out.printf(Locale.ROOT, "%d,%s,%.4f,%.4f%n",
                        r.trial(), r.lesioned(), r.pathLength(), r.avgSpeed());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[LesionExperiment] falha ao escrever CSV", e);
        }

        plugin.getLogger().info("[LesionExperiment] concluído — " + results.size()
                + " trials, CSV em " + file.getAbsolutePath());
        notify.sendMessage("Experimento concluído — " + results.size() + " trials. CSV em "
                + file.getAbsolutePath() + ". Analisar com sim/tools/lesion_analysis.py");
    }

    private static String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private record TrialResult(int trial, boolean lesioned, double pathLength, double avgSpeed) {
    }
}
