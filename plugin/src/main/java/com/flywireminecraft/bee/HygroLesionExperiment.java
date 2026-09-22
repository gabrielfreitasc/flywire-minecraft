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
 * F7/AD-17 — experimento de lesão pro subcircuito `hygro` (chuva), mesmo
 * desenho estatístico e mesmo formato de CSV do {@link TouchLesionExperiment}
 * (que validou o `bristle`, p=0,0025) e do {@link LesionExperiment} original
 * da F4 (ocelar, p=0,0014) — reaproveita {@code sim/tools/lesion_analysis.py}
 * sem mudar nada nele, só apontando pro arquivo novo.
 *
 * <p>Diferente do toque (orientado a evento, precisa de obstáculo por perto),
 * chuva é ambiente como a luz — qualquer origem serve, DESDE que esteja
 * chovendo de verdade no mundo no momento do trial (`/weather rain` antes de
 * rodar, ou aguardar o ciclo natural). Sem chuva real acontecendo, nem a
 * condição normal nem a mascarada têm estímulo — o experimento mediria
 * ruído, não sinal (mesma armadilha do toque, motivo diferente).
 *
 * <p>N trials sorteados aleatoriamente entre chuva normal e mascarada (não
 * alternância estrita, mesma razão da F4). Cada trial: teleporta a abelha de
 * volta à origem, zera velocidade, roda por N segundos medindo comprimento
 * de trajetória. Direção esperada — mesma do toque: se `hygrotaxis` faz a
 * abelha buscar abrigo (decisão do usuário, 21/09/2026, ver
 * `MotorMapping.isSeekingShelterActive`), o grupo NORMAL deveria ter
 * `path_length`/`avg_speed` MENORES (ela mergulha pro chão e para), o
 * MASCARADO maiores (nunca para, sempre voando via `phototaxis`) — mesmo
 * sentido do `bristle`, apesar do mergulho ser mais rápido que o pouso do
 * grooming (é um evento breve no início do trial, não muda a direção do
 * efeito ao longo de um trial de vários segundos).
 *
 * <p><b>Confundidor real encontrado na primeira rodada (22/09/2026):</b>
 * origem perto de árvore/construção (necessária pra `ShelterSensor` achar
 * abrigo) também aciona `touch_proximity` do `bristle` (raio de 3 blocos)
 * — log confirmou `grooming` saturado (~0,998) o experimento inteiro, e
 * `MotorMapping` checa `grooming` ANTES de `hygrotaxis`, mascarando a
 * busca de abrigo o tempo todo. Corrigido suprimindo o efeito do `bristle`
 * na velocidade durante todo o experimento
 * ({@link ControlLoop#setBristleSuppressedForExperiment}) — o circuito
 * continua rodando de verdade, só não comanda mais o movimento aqui.
 */
public final class HygroLesionExperiment {

    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;

    private final Plugin plugin;
    private final ControlLoop controlLoop;

    public HygroLesionExperiment(Plugin plugin, ControlLoop controlLoop) {
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
        if (!bee.getWorld().hasStorm()) {
            notify.sendMessage("Aviso: não está chovendo agora (World#hasStorm()=false) — "
                    + "rode /weather rain antes, senão nem o grupo normal tem estímulo real.");
        }
        if (forcedOrigin != null) {
            bee.teleport(forcedOrigin);
            bee.setVelocity(new Vector(0, 0, 0));
        }
        Location origin = bee.getLocation().clone();
        List<TrialResult> results = new ArrayList<>();
        // F7/AD-17 — ver docstring da classe: suprime o efeito do bristle na
        // velocidade pelo experimento inteiro, evita que grooming (acionado
        // por estar perto da árvore que dá abrigo) mascare a busca do hygro.
        controlLoop.setBristleSuppressedForExperiment(true);
        notify.sendMessage("Experimento de lesão de chuva iniciado: " + trials + " trials de "
                + secondsPerTrial + "s.");
        plugin.getLogger().info("[HygroLesionExperiment] início — " + trials + " trials x " + secondsPerTrial
                + "s, origem=" + formatLocation(origin));
        runTrial(bee, origin, 0, trials, secondsPerTrial * TICKS_PER_SECOND, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, int trialIndex, int totalTrials, int durationTicks,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            plugin.getLogger().warning("[HygroLesionExperiment] abelha sumiu — abortando experimento");
            controlLoop.setHygroLesioned(false);
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
        controlLoop.setHygroLesioned(lesioned);

        plugin.getLogger().info(String.format(
                "[HygroLesionExperiment] trial %d/%d — lesionado=%s", trialIndex + 1, totalTrials, lesioned));

        new BukkitRunnable() {
            int tick = 0;
            double pathLength = 0.0;
            Location last = bee.getLocation().clone();

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    plugin.getLogger().warning("[HygroLesionExperiment] abelha sumiu durante o trial — abortando");
                    controlLoop.setHygroLesioned(false);
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
        controlLoop.setHygroLesioned(false);
        controlLoop.setBristleSuppressedForExperiment(false);

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[HygroLesionExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, "hygro_lesion_experiment.csv");
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
            plugin.getLogger().log(Level.WARNING, "[HygroLesionExperiment] falha ao escrever CSV", e);
        }

        plugin.getLogger().info("[HygroLesionExperiment] concluído — " + results.size()
                + " trials, CSV em " + file.getAbsolutePath());
        notify.sendMessage("Experimento concluído — " + results.size() + " trials. CSV em "
                + file.getAbsolutePath() + ". Analisar com "
                + "sim/tools/lesion_analysis.py <caminho do hygro_lesion_experiment.csv>");
    }

    private static String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private record TrialResult(int trial, boolean lesioned, double pathLength, double avgSpeed) {
    }
}
