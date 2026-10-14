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

/** Dose-resposta de luz (F6): várias intensidades sorteadas trial a trial na mesma origem. */
public final class LightDoseResponseExperiment {
    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;
    private static final double[] LEVELS = {0.0, 0.25, 0.5, 1.0};

    private final Plugin plugin;
    private final ControlLoop controlLoop;

    public LightDoseResponseExperiment(Plugin plugin, ControlLoop controlLoop) {
        this.plugin = plugin;
        this.controlLoop = controlLoop;
    }

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
        notify.sendMessage("Dose-resposta de luz iniciada: " + trials + " trials de " + secondsPerTrial
                + "s, níveis 0/0,25/0,5/1,0 sorteados.");
        plugin.getLogger().info("[LightDoseResponseExperiment] início — " + trials + " trials x "
                + secondsPerTrial + "s, origem=" + formatLocation(origin));
        runTrial(bee, origin, 0, trials, secondsPerTrial * TICKS_PER_SECOND, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, int trialIndex, int totalTrials, int durationTicks,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            plugin.getLogger().warning("[LightDoseResponseExperiment] abelha sumiu — abortando experimento");
            controlLoop.setForcedLight(null);
            notify.sendMessage("Abelha sumiu — experimento abortado.");
            return;
        }
        if (trialIndex >= totalTrials) {
            finish(results, notify);
            return;
        }

        double level = LEVELS[RNG.nextInt(LEVELS.length)];
        controlLoop.setForcedLight(level);
        bee.teleport(origin);
        bee.setVelocity(new Vector(0, 0, 0));

        plugin.getLogger().info(String.format(Locale.ROOT,
                "[LightDoseResponseExperiment] trial %d/%d — light=%.2f", trialIndex + 1, totalTrials, level));

        new BukkitRunnable() {
            int tick = 0;
            double pathLength = 0.0;
            Location last = bee.getLocation().clone();

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    plugin.getLogger().warning(
                            "[LightDoseResponseExperiment] abelha sumiu durante o trial — abortando");
                    controlLoop.setForcedLight(null);
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
                    results.add(new TrialResult(trialIndex, level, pathLength, pathLength / durationS));
                    cancel();
                    runTrial(bee, origin, trialIndex + 1, totalTrials, durationTicks, results, notify);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void finish(List<TrialResult> results, CommandSender notify) {
        controlLoop.setForcedLight(null);

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[LightDoseResponseExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, "doseresponse_experiment.csv");
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println("trial,light_level,path_length,avg_speed");
            for (TrialResult r : results) {
                out.printf(Locale.ROOT, "%d,%.2f,%.4f,%.4f%n",
                        r.trial(), r.lightLevel(), r.pathLength(), r.avgSpeed());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[LightDoseResponseExperiment] falha ao escrever CSV", e);
        }

        plugin.getLogger().info("[LightDoseResponseExperiment] concluído — " + results.size()
                + " trials, CSV em " + file.getAbsolutePath());
        notify.sendMessage("Experimento concluído — " + results.size() + " trials. CSV em "
                + file.getAbsolutePath() + ". Analisar com sim/tools/doseresponse_analysis.py");
    }

    private static String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private record TrialResult(int trial, double lightLevel, double pathLength, double avgSpeed) {
    }
}
