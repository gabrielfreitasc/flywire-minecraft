package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Bee;
import org.bukkit.inventory.ItemStack;
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

/** Lesão do circuito johnston (F14): mede o despertar por som com e sem o circuito. */
public final class JohnstonLesionExperiment {
    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;
    private static final int SETTLE_TIMEOUT_TICKS = 400;

    private final Plugin plugin;
    private final ControlLoop controlLoop;

    public JohnstonLesionExperiment(Plugin plugin, ControlLoop controlLoop) {
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
        World world = origin.getWorld();

        Block jukeboxBlock = findJukebox(origin);
        if (jukeboxBlock == null) {
            notify.sendMessage("Não achei uma jukebox real a até " + AlarmSensor.MUSIC_RADIUS_BLOCKS
                    + " blocos da origem — coloque uma antes de rodar. Abortando.");
            return;
        }
        long time = world.getTime();
        if (time < SleepState.NIGHT_START_TICK || time >= 23000) {
            world.setTime(SleepState.NIGHT_START_TICK);
            notify.sendMessage("Aviso: forcei o relógio do mundo pra noite (World#setTime) — "
                    + "precisa ser noite pra ela ficar sonolenta (F13).");
        }
        stopJukebox(jukeboxBlock);

        List<TrialResult> results = new ArrayList<>();
        notify.sendMessage("Experimento de lesão de som/vento iniciado: " + trials + " trials de "
                + secondsPerTrial + "s (+ assentamento de até " + (SETTLE_TIMEOUT_TICKS / TICKS_PER_SECOND)
                + "s por trial, esperando ela ficar sonolenta).");
        plugin.getLogger().info("[JohnstonLesionExperiment] início — " + trials + " trials x " + secondsPerTrial
                + "s, origem=" + formatLocation(origin) + ", jukebox=" + formatLocation(jukeboxBlock.getLocation()));
        runTrial(bee, origin, jukeboxBlock, 0, trials, secondsPerTrial * TICKS_PER_SECOND, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, Block jukeboxBlock, int trialIndex, int totalTrials, int durationTicks,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            plugin.getLogger().warning("[JohnstonLesionExperiment] abelha sumiu — abortando experimento");
            finishAbort(jukeboxBlock, notify);
            return;
        }
        if (trialIndex >= totalTrials) {
            finish(results, jukeboxBlock, notify);
            return;
        }

        boolean lesioned = RNG.nextBoolean();
        bee.teleport(origin);
        bee.setVelocity(new Vector(0, 0, 0));
        stopJukebox(jukeboxBlock);
        controlLoop.setJohnstonLesioned(true);

        plugin.getLogger().info(String.format(
                "[JohnstonLesionExperiment] trial %d/%d — lesionado=%s (fase: assentamento)",
                trialIndex + 1, totalTrials, lesioned));

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    plugin.getLogger().warning("[JohnstonLesionExperiment] abelha sumiu no assentamento — abortando");
                    finishAbort(jukeboxBlock, notify);
                    cancel();
                    return;
                }
                if (controlLoop.isDrowsy()) {
                    cancel();
                    plugin.getLogger().info(String.format(
                            "[JohnstonLesionExperiment] trial %d/%d — sonolenta após %d ticks, iniciando medição",
                            trialIndex + 1, totalTrials, tick));
                    controlLoop.setJohnstonLesioned(lesioned);
                    startPlaying(jukeboxBlock);
                    measureTrial(bee, origin, jukeboxBlock, trialIndex, totalTrials, durationTicks, lesioned,
                            results, notify);
                    return;
                }
                tick++;
                if (tick >= SETTLE_TIMEOUT_TICKS) {
                    plugin.getLogger().warning("[JohnstonLesionExperiment] não ficou sonolenta a tempo "
                            + "(não é noite, ou dano/ameaça real por perto — som mascarado durante o "
                            + "assentamento não deveria mais ser a causa) — encerrando com os trials já feitos");
                    notify.sendMessage("Ela não ficou sonolenta a tempo neste trial — encerrando o experimento "
                            + "com os " + results.size() + " trials já concluídos (confirme que é noite e não "
                            + "há dano/ameaça real por perto).");
                    finish(results, jukeboxBlock, notify);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void measureTrial(
            Bee bee, Location origin, Block jukeboxBlock, int trialIndex, int totalTrials, int durationTicks,
            boolean lesioned, List<TrialResult> results, CommandSender notify
    ) {
        new BukkitRunnable() {
            int tick = 0;
            double pathLength = 0.0;
            Location last = bee.getLocation().clone();

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    plugin.getLogger().warning("[JohnstonLesionExperiment] abelha sumiu na medição — abortando");
                    finishAbort(jukeboxBlock, notify);
                    cancel();
                    return;
                }

                Location current = bee.getLocation();
                pathLength += current.distance(last);
                last = current.clone();
                tick++;

                if (tick >= durationTicks) {
                    double durationS = durationTicks / (double) TICKS_PER_SECOND;
                    stopJukebox(jukeboxBlock);
                    results.add(new TrialResult(trialIndex, lesioned, pathLength, pathLength / durationS));
                    cancel();
                    runTrial(bee, origin, jukeboxBlock, trialIndex + 1, totalTrials, durationTicks, results, notify);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void finishAbort(Block jukeboxBlock, CommandSender notify) {
        controlLoop.setJohnstonLesioned(false);
        stopJukebox(jukeboxBlock);
    }

    private void finish(List<TrialResult> results, Block jukeboxBlock, CommandSender notify) {
        controlLoop.setJohnstonLesioned(false);
        stopJukebox(jukeboxBlock);

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[JohnstonLesionExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, "johnston_lesion_experiment.csv");
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println("trial,lesioned,path_length,avg_speed");
            for (TrialResult r : results) {
                out.printf(Locale.ROOT, "%d,%s,%.4f,%.4f%n",
                        r.trial(), r.lesioned(), r.pathLength(), r.avgSpeed());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[JohnstonLesionExperiment] falha ao escrever CSV", e);
        }

        plugin.getLogger().info("[JohnstonLesionExperiment] concluído — " + results.size()
                + " trials, CSV em " + file.getAbsolutePath());
        notify.sendMessage("Experimento concluído — " + results.size() + " trials. CSV em "
                + file.getAbsolutePath() + ". Analisar com "
                + "sim/tools/lesion_analysis.py <caminho do johnston_lesion_experiment.csv>");
    }

    private Block findJukebox(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }
        int bx = origin.getBlockX();
        int by = origin.getBlockY();
        int bz = origin.getBlockZ();
        int radius = AlarmSensor.MUSIC_RADIUS_BLOCKS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block block = world.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (block.getType() == Material.JUKEBOX) {
                        return block;
                    }
                }
            }
        }
        return null;
    }

    private void startPlaying(Block jukeboxBlock) {
        if (jukeboxBlock.getState() instanceof Jukebox jukebox) {
            if (jukebox.getRecord() == null || jukebox.getRecord().getType() == Material.AIR) {
                jukebox.setRecord(new ItemStack(Material.MUSIC_DISC_CAT));
            }
            jukebox.startPlaying();
            jukebox.update(true);
        }
    }

    private void stopJukebox(Block jukeboxBlock) {
        if (jukeboxBlock.getState() instanceof Jukebox jukebox && jukebox.isPlaying()) {
            jukebox.stopPlaying();
            jukebox.update(true);
        }
    }

    private static String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private record TrialResult(int trial, boolean lesioned, double pathLength, double avgSpeed) {
    }
}
