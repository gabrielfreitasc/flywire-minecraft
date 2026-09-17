package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Bee;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.Plugin;
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
 * Experimento dia/noite (F6) — mesmo desenho do {@link LesionExperiment} da
 * F4, trocando a variável manipulada: em vez de silenciar fotorreceptores,
 * alterna a hora do mundo entre meio-dia e meia-noite.
 *
 * <p>**Não troca o sensor que alimenta os fotorreceptores** — {@code light}
 * (`Block#getLightLevel()`) continua sendo o que a F4 já validou
 * (Mann-Whitney p=0,0014) e é o canal certo pra isso: ao contrário do que o
 * nome sugere, `light` já é sensível a hora do dia; `dorsal_light`
 * (`getLightFromSky()`) é sensível a teto/céu aberto, não a hora — ver
 * `CONVENCOES.md`, "Armadilhas conhecidas", e `docs/03-roadmap-fases.md` (F6).
 *
 * <p>Por isso `dorsal_light` entra aqui só como FILTRO de confundidor: o
 * experimento recusa rodar se a abelha não estiver ao ar livre (skylight
 * bruto &lt; 15), porque debaixo de teto `light` não varia com a hora do
 * mundo, e o teste ficaria nulo por desenho errado — mesma armadilha de
 * "abelha dentro de casa" que já apareceu uma vez em RN-08/F6.
 *
 * <p>**Controle obrigatório ({@code blind=true}):** abelha vanilla muda de
 * comportamento à noite pela IA nativa (volta pra colmeia), independente do
 * circuito. Rodando com fotorreceptores cegos ({@code light=0}, mesmo
 * mecanismo do {@link LesionExperiment}), qualquer diferença dia/noite que
 * sobrar vem do jogo, não do conectoma. Sem esse controle, um p&lt;0,05 no modo
 * normal não pode ser atribuído ao circuito (regra 6 do CONVENCOES.md).
 *
 * <p>N trials em ordem aleatória entre dia (meio-dia, tick 6000) e noite
 * (meia-noite, tick 18000) — aleatório, não alternado, pelo mesmo motivo do
 * experimento de lesão: não confundir condição com qualquer deriva ao longo
 * do experimento. Mede comprimento de trajetória, igual ao de lesão. CSV é
 * analisado por `sim/tools/daynight_analysis.py` (Java executa, Python faz
 * ciência — mesma divisão do resto do projeto).
 */
public final class DayNightExperiment {

    private static final Random RNG = new Random();
    private static final int TICKS_PER_SECOND = 20;
    private static final long NOON_TICKS = 6000L;
    private static final long MIDNIGHT_TICKS = 18000L;
    private static final int FULL_SKYLIGHT = 15;

    private final Plugin plugin;
    private final ControlLoop controlLoop;

    public DayNightExperiment(Plugin plugin, ControlLoop controlLoop) {
        this.plugin = plugin;
        this.controlLoop = controlLoop;
    }

    /**
     * @param forcedOrigin se não-nulo, a abelha é teleportada pra cá ANTES do
     *     primeiro trial, no mesmo instante de execução do comando — sem
     *     intervalo pra IA nativa derivar. Se nulo, usa a posição atual da
     *     abelha, como antes.
     */
    public void run(Bee bee, int trials, int secondsPerTrial, boolean blind, Location forcedOrigin,
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
        int skylight = origin.getBlock().getLightFromSky();
        if (skylight < FULL_SKYLIGHT) {
            notify.sendMessage(String.format(Locale.ROOT,
                    "Abelha precisa estar ao ar livre (skylight bruto=15) pro experimento fazer "
                            + "sentido — aqui está %d. Debaixo de teto, 'light' não varia com a hora do "
                            + "mundo (ver CONVENCOES.md). Mova a abelha pra um lugar com céu visível.",
                    skylight));
            return;
        }

        List<TrialResult> results = new ArrayList<>();
        controlLoop.setLesioned(blind);
        notify.sendMessage("Experimento dia/noite iniciado" + (blind ? " (CONTROLE CEGO, light=0)" : "")
                + ": " + trials + " trials de " + secondsPerTrial + "s.");
        plugin.getLogger().info("[DayNightExperiment] início — " + trials + " trials x " + secondsPerTrial
                + "s, cego=" + blind + ", origem=" + formatLocation(origin) + ", skylight=" + skylight);
        runTrial(bee, origin, 0, trials, secondsPerTrial * TICKS_PER_SECOND, blind, results, notify);
    }

    private void runTrial(
            Bee bee, Location origin, int trialIndex, int totalTrials, int durationTicks, boolean blind,
            List<TrialResult> results, CommandSender notify
    ) {
        if (!bee.isValid() || bee.isDead()) {
            plugin.getLogger().warning("[DayNightExperiment] abelha sumiu — abortando experimento");
            controlLoop.setLesioned(false);
            notify.sendMessage("Abelha sumiu — experimento abortado.");
            return;
        }
        if (trialIndex >= totalTrials) {
            finish(results, blind, notify);
            return;
        }

        boolean day = RNG.nextBoolean();
        World world = bee.getWorld();
        world.setTime(day ? NOON_TICKS : MIDNIGHT_TICKS);
        bee.teleport(origin);
        bee.setVelocity(new Vector(0, 0, 0));

        plugin.getLogger().info(String.format(Locale.ROOT,
                "[DayNightExperiment] trial %d/%d — dia=%s", trialIndex + 1, totalTrials, day));

        new BukkitRunnable() {
            int tick = 0;
            double pathLength = 0.0;
            Location last = bee.getLocation().clone();

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    plugin.getLogger().warning("[DayNightExperiment] abelha sumiu durante o trial — abortando");
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
                    results.add(new TrialResult(trialIndex, day, pathLength, pathLength / durationS));
                    cancel();
                    runTrial(bee, origin, trialIndex + 1, totalTrials, durationTicks, blind, results, notify);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void finish(List<TrialResult> results, boolean blind, CommandSender notify) {
        controlLoop.setLesioned(false);

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[DayNightExperiment] não consegui criar " + dir);
        }
        File file = new File(dir, blind ? "daynight_experiment_blind.csv" : "daynight_experiment.csv");
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println("trial,day,path_length,avg_speed");
            for (TrialResult r : results) {
                // Locale.ROOT — nunca o locale padrão da JVM (bug já encontrado no
                // experimento de lesão: pt_BR usa vírgula decimal, que colide com a
                // vírgula de coluna do CSV e corrompe o arquivo silenciosamente).
                out.printf(Locale.ROOT, "%d,%s,%.4f,%.4f%n",
                        r.trial(), r.day(), r.pathLength(), r.avgSpeed());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[DayNightExperiment] falha ao escrever CSV", e);
        }

        plugin.getLogger().info("[DayNightExperiment] concluído — " + results.size()
                + " trials, CSV em " + file.getAbsolutePath());
        notify.sendMessage("Experimento concluído — " + results.size() + " trials. CSV em "
                + file.getAbsolutePath() + ". Analisar com sim/tools/daynight_analysis.py");
    }

    private static String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private record TrialResult(int trial, boolean day, double pathLength, double avgSpeed) {
    }
}
