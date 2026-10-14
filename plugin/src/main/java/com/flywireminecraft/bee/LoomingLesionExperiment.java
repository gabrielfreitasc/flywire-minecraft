package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Husk;
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

/** F9 — experimento de lesão do subcircuito `escape` com LOOMING como estímulo. */
public final class LoomingLesionExperiment {
    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;
    private static final double START_DISTANCE_BLOCKS = 10.0;
    private static final double END_DISTANCE_BLOCKS = 4.0;
    private static final double APPROACH_SPEED_BLOCKS_PER_TICK = 0.4;
    private static final int APPROACH_TICKS =
            (int) Math.round((START_DISTANCE_BLOCKS - END_DISTANCE_BLOCKS) / APPROACH_SPEED_BLOCKS_PER_TICK);

    private final Plugin plugin;
    private final ControlLoop controlLoop;
    private boolean previousIgnorePlayer;
    private Husk threat;

    public LoomingLesionExperiment(Plugin plugin, ControlLoop controlLoop) {
        this.plugin = plugin;
        this.controlLoop = controlLoop;
    }

    public void run(Bee bee, int trials, int secondsPerTrial, Location forcedOrigin, CommandSender notify) {
        if (!controlLoop.isRunning()) {
            notify.sendMessage("Loop de controle precisa estar rodando primeiro: /flywirebee control start");
            return;
        }
        if (secondsPerTrial < 4) {
            notify.sendMessage("Aviso: secondsPerTrial < 4 — a janela de fuga (~2,5s) pode não caber no trial.");
        }
        if (controlLoop.isDrowsy()) {
            notify.sendMessage("Aviso: a abelha está sonolenta (noite) — o mob também aciona o alarme do johnston,"
                    + " que a acorda; rode de dia pra não misturar o efeito do despertar com o da fuga.");
        }
        if (forcedOrigin != null) {
            bee.teleport(forcedOrigin);
            bee.setVelocity(new Vector(0, 0, 0));
        }
        previousIgnorePlayer = controlLoop.isIgnoringPlayer();
        controlLoop.setIgnorePlayer(true);
        Location origin = bee.getLocation().clone();
        List<TrialResult> results = new ArrayList<>();
        notify.sendMessage("Experimento de lesão de looming iniciado: " + trials + " trials de "
                + secondsPerTrial + "s (jogador ignorado durante o experimento).");
        plugin.getLogger().info("[LoomingLesionExperiment] início — " + trials + " trials x " + secondsPerTrial
                + "s, origem=" + formatLocation(origin) + ", mob " + START_DISTANCE_BLOCKS + "→"
                + END_DISTANCE_BLOCKS + " blocos em " + APPROACH_TICKS + " ticks");
        runTrial(bee, origin, 0, trials, secondsPerTrial * TICKS_PER_SECOND, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, int trialIndex, int totalTrials, int durationTicks,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            abort("abelha sumiu — abortando experimento", notify);
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
        controlLoop.refillEnergyForExperiment();
        controlLoop.setEscapeLesioned(lesioned);

        plugin.getLogger().info(String.format(
                "[LoomingLesionExperiment] trial %d/%d — lesionado=%s", trialIndex + 1, totalTrials, lesioned));

        int stimulusAtTick = durationTicks / 4;

        new BukkitRunnable() {
            int tick = 0;
            double pathLength = 0.0;
            Location last = bee.getLocation().clone();
            Vector approachStep = null;
            int approachTicksLeft = 0;

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    cancel();
                    abort("abelha sumiu durante o trial — abortando", notify);
                    return;
                }

                if (tick == stimulusAtTick) {
                    approachStep = spawnThreat(bee);
                    approachTicksLeft = APPROACH_TICKS;
                } else if (approachTicksLeft > 0 && threat != null) {
                    threat.teleport(threat.getLocation().add(approachStep));
                    approachTicksLeft--;
                    if (approachTicksLeft == 0) {
                        removeThreat();
                    }
                }

                Location current = bee.getLocation();
                pathLength += current.distance(last);
                last = current.clone();
                tick++;

                if (tick >= durationTicks) {
                    removeThreat();
                    double durationS = durationTicks / (double) TICKS_PER_SECOND;
                    results.add(new TrialResult(trialIndex, lesioned, pathLength, pathLength / durationS));
                    cancel();
                    runTrial(bee, origin, trialIndex + 1, totalTrials, durationTicks, results, notify);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Vector spawnThreat(Bee bee) {
        Location beeLoc = bee.getLocation();
        Location spawn = beeLoc.clone().add(START_DISTANCE_BLOCKS, 0, 0);
        threat = bee.getWorld().spawn(spawn, Husk.class, h -> {
            h.setAI(false);
            h.setGravity(false);
            h.setInvulnerable(true);
            h.setSilent(true);
            h.setPersistent(false);
            h.setCanPickupItems(false);
            h.setAdult();
        });
        return beeLoc.toVector().subtract(spawn.toVector()).normalize().multiply(APPROACH_SPEED_BLOCKS_PER_TICK);
    }

    private void removeThreat() {
        if (threat != null) {
            threat.remove();
            threat = null;
        }
    }

    private void abort(String reason, CommandSender notify) {
        removeThreat();
        controlLoop.setEscapeLesioned(false);
        controlLoop.setIgnorePlayer(previousIgnorePlayer);
        plugin.getLogger().warning("[LoomingLesionExperiment] " + reason);
        notify.sendMessage("Experimento de looming abortado: " + reason);
    }

    private void finish(List<TrialResult> results, CommandSender notify) {
        removeThreat();
        controlLoop.setEscapeLesioned(false);
        controlLoop.setIgnorePlayer(previousIgnorePlayer);

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[LoomingLesionExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, "looming_lesion_experiment.csv");
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println("trial,lesioned,path_length,avg_speed");
            for (TrialResult r : results) {
                out.printf(Locale.ROOT, "%d,%s,%.4f,%.4f%n",
                        r.trial(), r.lesioned(), r.pathLength(), r.avgSpeed());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[LoomingLesionExperiment] falha ao escrever CSV", e);
        }

        plugin.getLogger().info("[LoomingLesionExperiment] concluído — " + results.size()
                + " trials, CSV em " + file.getAbsolutePath());
        notify.sendMessage("Experimento concluído — " + results.size() + " trials. CSV em "
                + file.getAbsolutePath() + ". Analisar com "
                + "sim/tools/lesion_analysis.py <caminho do looming_lesion_experiment.csv>");
    }

    private static String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private record TrialResult(int trial, boolean lesioned, double pathLength, double avgSpeed) {
    }
}
