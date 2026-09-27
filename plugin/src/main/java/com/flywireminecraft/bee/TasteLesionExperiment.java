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
 * F14 (27/09/2026) — experimento de lesão pro subcircuito `taste` (paladar
 * apetitivo), mesmo desenho estatístico e mesmo formato de CSV dos
 * experimentos anteriores — reaproveita {@code sim/tools/lesion_analysis.py}
 * sem mudar nada nele.
 *
 * <p>Orientado a EVENTO/objeto, igual {@link TouchLesionExperiment}: sem
 * comida de verdade perto da origem, nem a condição normal nem a lesionada
 * têm estímulo — o experimento mediria ruído. A origem (x/y/z do comando)
 * precisa ficar perto de um bloco de comida (mel, melancia, abóbora, bolo,
 * plantação madura) OU item comestível largado no chão, dentro do raio de
 * busca do {@link TasteSensor} (4 blocos) — quem roda o comando garante
 * isso antes.
 *
 * <p>Suprime o efeito do `bristle` na velocidade pelo experimento inteiro
 * (mesma técnica de {@link HygroLesionExperiment#run}, aplicada
 * preventivamente aqui em vez de esperar redescobrir em jogo): o jogador
 * parado perto pra observar, ou uma abelha/animal por perto da fonte de
 * comida, aciona `touch_proximity` e `grooming` teria prioridade sobre
 * `appetite` em {@code MotorMapping}, mascarando o efeito do taste.
 *
 * <p>N trials sorteados aleatoriamente entre taste normal e lesionado. Cada
 * trial: teleporta a abelha de volta à origem, zera velocidade, roda por N
 * segundos medindo comprimento de trajetória. Direção esperada: com
 * `appetite` ativo ela voa até a comida e para bem perto/em cima dela
 * (`MotorMapping.TASTE_ARRIVAL_THRESHOLD_BLOCKS`) — o grupo NORMAL deveria
 * ter `path_length`/`avg_speed` MENORES (mesmo sentido do `bristle`: real =
 * pousa/para, mascarado = nunca para, sempre voando via phototaxis).
 */
public final class TasteLesionExperiment {

    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;

    private final Plugin plugin;
    private final ControlLoop controlLoop;

    public TasteLesionExperiment(Plugin plugin, ControlLoop controlLoop) {
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
        // Mesma técnica de HygroLesionExperiment — evita que touch_proximity
        // (jogador/mob perto da comida) mascare o efeito do taste via grooming.
        controlLoop.setBristleSuppressedForExperiment(true);
        notify.sendMessage("Experimento de lesão de paladar iniciado: " + trials + " trials de "
                + secondsPerTrial + "s. Confirme que há comida real a até 4 blocos da origem.");
        plugin.getLogger().info("[TasteLesionExperiment] início — " + trials + " trials x " + secondsPerTrial
                + "s, origem=" + formatLocation(origin));
        runTrial(bee, origin, 0, trials, secondsPerTrial * TICKS_PER_SECOND, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, int trialIndex, int totalTrials, int durationTicks,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            plugin.getLogger().warning("[TasteLesionExperiment] abelha sumiu — abortando experimento");
            controlLoop.setTasteLesioned(false);
            controlLoop.setBristleSuppressedForExperiment(false);
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
        controlLoop.setTasteLesioned(false);
        controlLoop.setBristleSuppressedForExperiment(false);

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[TasteLesionExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, "taste_lesion_experiment.csv");
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

    private record TrialResult(int trial, boolean lesioned, double pathLength, double avgSpeed) {
    }
}
