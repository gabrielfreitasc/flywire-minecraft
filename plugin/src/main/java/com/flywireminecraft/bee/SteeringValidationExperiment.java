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
 * Valida o sentido do canal {@code yaw_steering} (RN-08/AD-16, F6) — a
 * pergunta em aberto que ele deixou: o sinal do canal corresponde a virar pra
 * um lado específico do mundo, ou não significa nada direcional?
 *
 * <p>3 condições sorteadas trial a trial, mesma origem (embutida no comando,
 * mesma correção de deriva de `daynight`/`doseresponse`):
 * <ul>
 *   <li>{@code left} — {@code stimulate steering_left <amplitude>}: força a
 *       taxa do neurônio esquerdo do par bilateral pra cima, deveria puxar
 *       `yaw_steering` fortemente positivo;</li>
 *   <li>{@code right} — {@code stimulate steering_right <amplitude>}: mesma
 *       coisa pro neurônio direito, `yaw_steering` fortemente negativo;</li>
 *   <li>{@code baseline} — sem estimulação, `yaw_steering` no valor orgânico
 *       do circuito.</li>
 * </ul>
 *
 * <p>Métrica: não é distância (como lesão/dia-noite/dose-resposta) — é
 * **ângulo de giro líquido acumulado**. Amostra a posição a cada
 * {@code SAMPLE_EVERY_TICKS}, calcula o rumo (bearing, atan2 no plano
 * horizontal XZ) do deslocamento entre amostras consecutivas, acumula a
 * diferença ANGULAR SINALIZADA entre rumos consecutivos (`angleDiff`, corrige
 * wraparound em ±π). Positivo/negativo aqui são só "sentido de rotação no
 * plano XZ do Minecraft" — a correspondência com {@code yaw_steering} é
 * exatamente o que este experimento mede, não uma suposição prévia.
 *
 * <p>Se `left` e `right` derem giro líquido significativamente diferente, em
 * sentidos opostos, e diferentes de `baseline`: `yaw_steering` controla
 * direção de verdade, e o sinal de qual condição gira pra qual lado real do
 * mundo sai direto do CSV (positivo = horário ou anti-horário, ver
 * `sim/tools/steering_validation_analysis.py`).
 */
public final class SteeringValidationExperiment {

    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;
    private static final int SAMPLE_EVERY_TICKS = 10; // 0,5s — deslocamento suficiente pro rumo não ser ruído
    private static final String[] CONDITIONS = {"left", "right", "baseline"};

    private final Plugin plugin;
    private final ControlLoop controlLoop;

    public SteeringValidationExperiment(Plugin plugin, ControlLoop controlLoop) {
        this.plugin = plugin;
        this.controlLoop = controlLoop;
    }

    public void run(Bee bee, int trials, int secondsPerTrial, double amplitude, Location forcedOrigin,
            CommandSender notify) {
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
        notify.sendMessage("Validação de yaw_steering iniciada: " + trials + " trials de " + secondsPerTrial
                + "s, amplitude=" + amplitude + ".");
        plugin.getLogger().info("[SteeringValidationExperiment] início — " + trials + " trials x "
                + secondsPerTrial + "s, amplitude=" + amplitude + ", origem=" + formatLocation(origin));
        runTrial(bee, origin, 0, trials, secondsPerTrial * TICKS_PER_SECOND, amplitude, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, int trialIndex, int totalTrials, int durationTicks, double amplitude,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            plugin.getLogger().warning("[SteeringValidationExperiment] abelha sumiu — abortando experimento");
            controlLoop.stopStimulating();
            notify.sendMessage("Abelha sumiu — experimento abortado.");
            return;
        }
        if (trialIndex >= totalTrials) {
            finish(results, notify);
            return;
        }

        String condition = CONDITIONS[RNG.nextInt(CONDITIONS.length)];
        switch (condition) {
            case "left" -> controlLoop.stimulate("steering_left", amplitude);
            case "right" -> controlLoop.stimulate("steering_right", amplitude);
            default -> controlLoop.stopStimulating();
        }
        bee.teleport(origin);
        bee.setVelocity(new Vector(0, 0, 0));
        // reseta o giro acumulado do trial anterior — sem isso, o giro de um
        // trial vazaria pro início do próximo (ver ControlLoop::resetHeading).
        controlLoop.resetHeading();

        plugin.getLogger().info(String.format(Locale.ROOT,
                "[SteeringValidationExperiment] trial %d/%d — condicao=%s", trialIndex + 1, totalTrials, condition));

        new BukkitRunnable() {
            int tick = 0;
            double pathLength = 0.0;
            double netTurnRad = 0.0;
            Double lastBearing = null;
            Location last = bee.getLocation().clone();
            Location lastSample = bee.getLocation().clone();

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    plugin.getLogger().warning(
                            "[SteeringValidationExperiment] abelha sumiu durante o trial — abortando");
                    controlLoop.stopStimulating();
                    notify.sendMessage("Abelha sumiu durante o experimento — abortado.");
                    cancel();
                    return;
                }

                Location current = bee.getLocation();
                pathLength += current.distance(last);
                last = current.clone();

                if (tick > 0 && tick % SAMPLE_EVERY_TICKS == 0) {
                    double dx = current.getX() - lastSample.getX();
                    double dz = current.getZ() - lastSample.getZ();
                    if (Math.hypot(dx, dz) > 0.01) { // ignora amostra com deslocamento desprezível (ruído de rumo)
                        double bearing = Math.atan2(dz, dx);
                        if (lastBearing != null) {
                            netTurnRad += angleDiff(lastBearing, bearing);
                        }
                        lastBearing = bearing;
                    }
                    lastSample = current.clone();
                }
                tick++;

                if (tick >= durationTicks) {
                    double durationS = durationTicks / (double) TICKS_PER_SECOND;
                    results.add(new TrialResult(trialIndex, condition, netTurnRad, pathLength, pathLength / durationS));
                    cancel();
                    runTrial(bee, origin, trialIndex + 1, totalTrials, durationTicks, amplitude, results, notify);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Diferença angular sinalizada de `from` pra `to`, normalizada em (-pi, pi]. */
    private static double angleDiff(double from, double to) {
        double diff = to - from;
        while (diff > Math.PI) {
            diff -= 2 * Math.PI;
        }
        while (diff <= -Math.PI) {
            diff += 2 * Math.PI;
        }
        return diff;
    }

    private void finish(List<TrialResult> results, CommandSender notify) {
        controlLoop.stopStimulating();

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[SteeringValidationExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, "steering_validation_experiment.csv");
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println("trial,condition,net_turn_rad,path_length,avg_speed");
            for (TrialResult r : results) {
                // Locale.ROOT — vírgula decimal do pt_BR corrompe CSV, já visto no
                // experimento de lesão (CONVENCOES.md).
                out.printf(Locale.ROOT, "%d,%s,%.5f,%.4f,%.4f%n",
                        r.trial(), r.condition(), r.netTurnRad(), r.pathLength(), r.avgSpeed());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[SteeringValidationExperiment] falha ao escrever CSV", e);
        }

        plugin.getLogger().info("[SteeringValidationExperiment] concluído — " + results.size()
                + " trials, CSV em " + file.getAbsolutePath());
        notify.sendMessage("Experimento concluído — " + results.size() + " trials. CSV em "
                + file.getAbsolutePath() + ". Analisar com sim/tools/steering_validation_analysis.py");
    }

    private static String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private record TrialResult(int trial, String condition, double netTurnRad, double pathLength, double avgSpeed) {
    }
}
