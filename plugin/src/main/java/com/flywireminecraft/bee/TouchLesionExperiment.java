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
 * F7/AD-17 — experimento de lesão pro subcircuito `bristle` (toque), mesmo
 * desenho estatístico e mesmo formato de CSV do {@link LesionExperiment} da
 * F4 (que validou o circuito ocelar, p=0,0014) — reaproveita
 * {@code sim/tools/lesion_analysis.py} sem mudar nada nele, só apontando
 * pro arquivo novo.
 *
 * <p>Diferença de desenho em relação ao da luz: `light` é ambiente e varia
 * continuamente com a posição, então qualquer origem serve. Toque é
 * orientado a EVENTO — se o trial rodar no meio do ar aberto, sem nada
 * perto pra tocar ou esbarrar, nem a condição normal nem a mascarada vão
 * ter estímulo real, e o experimento não testa nada (mede ruído). Por isso
 * a origem default deste experimento (ver {@code handleTouchLesion} em
 * {@link FlywireBeePlugin}) deveria ficar perto de um obstáculo ou do
 * jogador — quem roda o comando escolhe isso passando x/y/z.
 *
 * <p>N trials sorteados aleatoriamente entre toque normal e mascarado
 * (não alternância estrita, mesma razão do experimento da F4 — não
 * confundir condição com deriva ao longo do experimento). Cada trial:
 * teleporta a abelha de volta à origem, zera velocidade, roda por N
 * segundos medindo comprimento de trajetória. Métrica: se o toque real faz
 * ela pousar/ficar parada (grooming) e o mascarado não, `path_length`/
 * `avg_speed` devem ser MENORES no grupo normal — direção oposta da F4
 * (lá, lesionado tinha rastro MENOR; aqui, mascarado deveria ter rastro
 * MAIOR, porque sem toque ela nunca para).
 */
public final class TouchLesionExperiment {

    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;

    private final Plugin plugin;
    private final ControlLoop controlLoop;

    public TouchLesionExperiment(Plugin plugin, ControlLoop controlLoop) {
        this.plugin = plugin;
        this.controlLoop = controlLoop;
    }

    /**
     * @param forcedOrigin se não-nulo, a abelha é teleportada pra cá ANTES do
     *     primeiro trial, no mesmo instante de execução do comando (mesma
     *     lição da F6 sobre deriva de IA nativa entre comandos — ver
     *     {@link LesionExperiment}). Se nulo, usa a posição atual da abelha.
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
        notify.sendMessage("Experimento de lesão de toque iniciado: " + trials + " trials de "
                + secondsPerTrial + "s.");
        plugin.getLogger().info("[TouchLesionExperiment] início — " + trials + " trials x " + secondsPerTrial
                + "s, origem=" + formatLocation(origin));
        runTrial(bee, origin, 0, trials, secondsPerTrial * TICKS_PER_SECOND, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, int trialIndex, int totalTrials, int durationTicks,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            plugin.getLogger().warning("[TouchLesionExperiment] abelha sumiu — abortando experimento");
            controlLoop.setTouchLesioned(false);
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
        controlLoop.setTouchLesioned(lesioned);

        plugin.getLogger().info(String.format(
                "[TouchLesionExperiment] trial %d/%d — lesionado=%s", trialIndex + 1, totalTrials, lesioned));

        new BukkitRunnable() {
            int tick = 0;
            double pathLength = 0.0;
            Location last = bee.getLocation().clone();

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    plugin.getLogger().warning("[TouchLesionExperiment] abelha sumiu durante o trial — abortando");
                    controlLoop.setTouchLesioned(false);
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
        controlLoop.setTouchLesioned(false);

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[TouchLesionExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, "touch_lesion_experiment.csv");
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
            plugin.getLogger().log(Level.WARNING, "[TouchLesionExperiment] falha ao escrever CSV", e);
        }

        plugin.getLogger().info("[TouchLesionExperiment] concluído — " + results.size()
                + " trials, CSV em " + file.getAbsolutePath());
        notify.sendMessage("Experimento concluído — " + results.size() + " trials. CSV em "
                + file.getAbsolutePath() + ". Analisar com "
                + "sim/tools/lesion_analysis.py <caminho do touch_lesion_experiment.csv>");
    }

    private static String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private record TrialResult(int trial, boolean lesioned, double pathLength, double avgSpeed) {
    }
}
