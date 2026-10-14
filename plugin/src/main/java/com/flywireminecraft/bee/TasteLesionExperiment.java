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

/** Lesão do circuito taste (F14): mede chegada à comida com e sem o circuito. */
public final class TasteLesionExperiment {
    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;
    private static final double NO_FOOD_DETECTED_SENTINEL = -1.0;

    private final Plugin plugin;
    private final ControlLoop controlLoop;
    private final TasteSensor tasteSensor = new TasteSensor();

    public TasteLesionExperiment(Plugin plugin, ControlLoop controlLoop) {
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
        TasteSensor.FoodTarget initialFood = tasteSensor.findNearestFood(bee);
        Location foodLocation = initialFood != null ? initialFood.location() : null;
        if (foodLocation == null) {
            notify.sendMessage("Aviso: não detectei comida real a até 4 blocos da origem — "
                    + "o experimento vai medir ruído.");
        }
        List<TrialResult> results = new ArrayList<>();
        controlLoop.setBristleSuppressedForExperiment(true);
        controlLoop.setSatietyGateSuppressedForExperiment(true);
        notify.sendMessage("Experimento de lesão de paladar iniciado: " + trials + " trials de "
                + secondsPerTrial + "s. Confirme que há comida real a até 4 blocos da origem.");
        plugin.getLogger().info("[TasteLesionExperiment] início — " + trials + " trials x " + secondsPerTrial
                + "s, origem=" + formatLocation(origin) + ", comida="
                + (foodLocation != null ? formatLocation(foodLocation) : "NÃO DETECTADA"));
        runTrial(bee, origin, foodLocation, 0, trials, secondsPerTrial * TICKS_PER_SECOND, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, Location foodLocation, int trialIndex, int totalTrials, int durationTicks,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            plugin.getLogger().warning("[TasteLesionExperiment] abelha sumiu — abortando experimento");
            controlLoop.setTasteLesioned(false);
            controlLoop.setBristleSuppressedForExperiment(false);
            controlLoop.setSatietyGateSuppressedForExperiment(false);
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
        controlLoop.setTasteLesioned(lesioned);

        plugin.getLogger().info(String.format(
                "[TasteLesionExperiment] trial %d/%d — lesionado=%s", trialIndex + 1, totalTrials, lesioned));

        new BukkitRunnable() {
            int tick = 0;
            double pathLength = 0.0;
            Location last = bee.getLocation().clone();

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    plugin.getLogger().warning("[TasteLesionExperiment] abelha sumiu durante o trial — abortando");
                    controlLoop.setTasteLesioned(false);
                    controlLoop.setBristleSuppressedForExperiment(false);
                    controlLoop.setSatietyGateSuppressedForExperiment(false);
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
                    double finalDistance = foodLocation != null
                            ? current.distance(foodLocation) : NO_FOOD_DETECTED_SENTINEL;
                    results.add(new TrialResult(
                            trialIndex, lesioned, pathLength, pathLength / durationS, finalDistance));
                    cancel();
                    runTrial(bee, origin, foodLocation, trialIndex + 1, totalTrials, durationTicks, results, notify);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void finish(List<TrialResult> results, CommandSender notify) {
        controlLoop.setTasteLesioned(false);
        controlLoop.setBristleSuppressedForExperiment(false);
        controlLoop.setSatietyGateSuppressedForExperiment(false);

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[TasteLesionExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, "taste_lesion_experiment.csv");
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println("trial,lesioned,path_length,avg_speed,final_distance_to_food");
            for (TrialResult r : results) {
                out.printf(Locale.ROOT, "%d,%s,%.4f,%.4f,%.4f%n",
                        r.trial(), r.lesioned(), r.pathLength(), r.avgSpeed(), r.finalDistanceToFood());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[TasteLesionExperiment] falha ao escrever CSV", e);
        }

        plugin.getLogger().info("[TasteLesionExperiment] concluído — " + results.size()
                + " trials, CSV em " + file.getAbsolutePath());
        notify.sendMessage("Experimento concluído — " + results.size() + " trials. CSV em "
                + file.getAbsolutePath() + ". Analisar com "
                + "sim/tools/lesion_analysis.py <caminho do taste_lesion_experiment.csv>");
    }

    private static String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private record TrialResult(
            int trial, boolean lesioned, double pathLength, double avgSpeed, double finalDistanceToFood) {
    }
}
