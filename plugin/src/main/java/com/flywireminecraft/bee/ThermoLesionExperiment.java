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
 * F14 (27/09/2026) — experimento de lesão pro subcircuito `thermo`
 * (calor/frio), mesmo desenho estatístico e mesmo formato de CSV dos
 * experimentos anteriores — reaproveita {@code sim/tools/lesion_analysis.py}
 * sem mudar nada nele.
 *
 * <p><b>Só testa a fuga de CALOR PERIGOSO</b> — decisão de projeto (ver
 * `docs/03-roadmap-fases.md` F12): frio e calor de bioma só mudam o balão
 * de texto, não a velocidade (`MotorMapping.heatAvoidVelocity` só é chamado
 * com {@code heatAvoidDirection != null}, que só existe com fonte
 * PERIGOSA — lava/fogo/magma/fogueira acesa por perto, ver
 * {@link ThermalSensor}). Testar frio aqui mediria ruído — não há efeito
 * de movimento pra detectar. A origem (x/y/z do comando) precisa ficar
 * perto de lava/fogo/magma, dentro do raio do sensor (3 blocos).
 *
 * <p>Ambiente, igual {@link HygroLesionExperiment}: a fonte de calor não é
 * consumida, continua lá o experimento inteiro — qualquer origem com fonte
 * perigosa por perto serve pros dois grupos.
 *
 * <p>N trials sorteados aleatoriamente entre thermo normal e lesionado.
 * Cada trial: teleporta a abelha de volta à origem, zera velocidade, roda
 * por N segundos medindo comprimento de trajetória. Direção esperada: com
 * `thermal` ativo ela foge (`HEAT_AVOID_SPEED_BLOCKS_PER_TICK=0,4`,
 * prioridade acima de recuperação/desvio/fome/sono) — o grupo NORMAL
 * deveria ter `path_length`/`avg_speed` MAIORES que o LESIONADO (mesmo
 * sentido do escape: estímulo real acelera a fuga, mascarado deixa só o
 * voo normal de phototaxis).
 */
public final class ThermoLesionExperiment {

    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;

    private final Plugin plugin;
    private final ControlLoop controlLoop;

    public ThermoLesionExperiment(Plugin plugin, ControlLoop controlLoop) {
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
        if (forcedOrigin != null) {
            bee.teleport(forcedOrigin);
            bee.setVelocity(new Vector(0, 0, 0));
        }
        Location origin = bee.getLocation().clone();
        List<TrialResult> results = new ArrayList<>();
        notify.sendMessage("Experimento de lesão de calor iniciado: " + trials + " trials de "
                + secondsPerTrial + "s. Confirme que há lava/fogo/magma real a até 3 blocos da origem.");
        plugin.getLogger().info("[ThermoLesionExperiment] início — " + trials + " trials x " + secondsPerTrial
                + "s, origem=" + formatLocation(origin));
        runTrial(bee, origin, 0, trials, secondsPerTrial * TICKS_PER_SECOND, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, int trialIndex, int totalTrials, int durationTicks,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            plugin.getLogger().warning("[ThermoLesionExperiment] abelha sumiu — abortando experimento");
            controlLoop.setThermoLesioned(false);
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
        controlLoop.setThermoLesioned(lesioned);

        plugin.getLogger().info(String.format(
                "[ThermoLesionExperiment] trial %d/%d — lesionado=%s", trialIndex + 1, totalTrials, lesioned));

        new BukkitRunnable() {
            int tick = 0;
            double pathLength = 0.0;
            Location last = bee.getLocation().clone();

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    plugin.getLogger().warning("[ThermoLesionExperiment] abelha sumiu durante o trial — abortando");
                    controlLoop.setThermoLesioned(false);
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
        controlLoop.setThermoLesioned(false);

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[ThermoLesionExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, "thermo_lesion_experiment.csv");
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
            plugin.getLogger().log(Level.WARNING, "[ThermoLesionExperiment] falha ao escrever CSV", e);
        }

        plugin.getLogger().info("[ThermoLesionExperiment] concluído — " + results.size()
                + " trials, CSV em " + file.getAbsolutePath());
        notify.sendMessage("Experimento concluído — " + results.size() + " trials. CSV em "
                + file.getAbsolutePath() + ". Analisar com "
                + "sim/tools/lesion_analysis.py <caminho do thermo_lesion_experiment.csv>");
    }

    private static String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private record TrialResult(int trial, boolean lesioned, double pathLength, double avgSpeed) {
    }
}
