package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.entity.Bee;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * L5→L6 — laço de controle em tempo real: sensor da abelha → ponte → vetor
 * motor → velocidade aplicada.
 *
 * <p>RN-06 — o tick do jogo NUNCA espera o simulador. A troca com a ponte
 * ({@link BridgeClient#sendSensorAndReceiveMotor}) é I/O bloqueante de rede;
 * rodá-la na thread principal travaria o servidor inteiro a cada tick. Por
 * isso:
 *
 * <ul>
 *   <li>Leitura de sensor e aplicação de velocidade rodam na thread principal
 *       (obrigatório — API do Bukkit não é thread-safe para isso);</li>
 *   <li>A troca com a ponte roda numa thread dedicada, nunca mais de uma por
 *       vez ({@code exchangeInFlight});</li>
 *   <li>A cada tick, a abelha recebe o ÚLTIMO vetor motor já calculado — nunca
 *       espera uma troca em andamento terminar. Mesma lógica de
 *       {@code server.py::Engine.step()} do lado Python.</li>
 * </ul>
 */
public final class ControlLoop {

    private final Plugin plugin;
    private final FlywireBeeMarker marker;
    private final DamageTracker damageTracker;
    private final String bridgeHost;
    private final int bridgePort;

    private static final int LOG_EVERY_TICKS = 20;   // 1x por segundo
    private static final int VISUALIZE_EVERY_TICKS = 5; // 4Hz — 20Hz de partículas seria spam visual

    private final ActivityVisualizer visualizer = new ActivityVisualizer();

    private BridgeClient bridge;
    private ExecutorService bridgeExecutor;
    private BukkitTask tickTask;
    private final AtomicBoolean exchangeInFlight = new AtomicBoolean(false);
    private volatile Vector latestVelocity = new Vector(0, 0, 0);
    private volatile JsonObject latestMotor = new JsonObject();
    private volatile long exchangeCount = 0;
    private volatile long exchangeFailures = 0;
    private long tickCount = 0;
    private volatile boolean lesioned = false;
    private volatile boolean visualize = true;

    /** F5 — liga/desliga as partículas de atividade. Controle nunca depende disso. */
    public void setVisualize(boolean visualize) {
        this.visualize = visualize;
    }

    /**
     * RN — experimento de lesão (docs/00-visao-geral.md, critério de
     * falsificação): quando true, o sensor de luz real é ignorado e sempre
     * se envia light=0 — os fotorreceptores nunca recebem estímulo do mundo,
     * só a dinâmica basal (bias+ruído) do circuito continua rodando. A lesão
     * é aplicada aqui (do lado do plugin, antes de mandar pra ponte), não no
     * simulador — mais simples e não exige mudar o protocolo.
     */
    public void setLesioned(boolean lesioned) {
        this.lesioned = lesioned;
    }

    public ControlLoop(
            Plugin plugin,
            FlywireBeeMarker marker,
            DamageTracker damageTracker,
            String bridgeHost,
            int bridgePort
    ) {
        this.plugin = plugin;
        this.marker = marker;
        this.damageTracker = damageTracker;
        this.bridgeHost = bridgeHost;
        this.bridgePort = bridgePort;
    }

    public boolean isRunning() {
        return tickTask != null;
    }

    public void start() {
        if (isRunning()) {
            return;
        }
        bridgeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "flywire-control-bridge");
            t.setDaemon(true);
            return t;
        });
        latestVelocity = new Vector(0, 0, 0);
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::onTick, 0L, 1L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (bridgeExecutor != null) {
            bridgeExecutor.shutdownNow();
            bridgeExecutor = null;
        }
        if (bridge != null) {
            try {
                bridge.close();
            } catch (IOException ignored) {
                // servidor caindo mesmo assim, não há o que fazer com o erro aqui
            }
            bridge = null;
        }
    }

    /** Roda na thread principal, a cada tick (20Hz). */
    private void onTick() {
        Bee bee = findMarkedBee();
        if (bee == null) {
            return;
        }

        // RN-06: aplica o último vetor já calculado, nunca espera a ponte.
        bee.setVelocity(latestVelocity);

        if (visualize && tickCount % VISUALIZE_EVERY_TICKS == 0) {
            visualizer.render(bee, latestMotor);
        }

        double realLight = bee.getLocation().getBlock().getLightLevel() / 15.0;
        double light = lesioned ? 0.0 : realLight;

        if (tickCount % LOG_EVERY_TICKS == 0) {
            plugin.getLogger().info(String.format(Locale.ROOT,
                    "[ControlLoop] light=%.2f (real=%.2f, lesionado=%s) vel=%s trocas=%d falhas=%d",
                    light, realLight, lesioned, latestVelocity, exchangeCount, exchangeFailures));
        }
        tickCount++;

        if (!exchangeInFlight.compareAndSet(false, true)) {
            return; // troca anterior ainda em andamento
        }

        double dorsalLight = bee.getLocation().getBlock().getLightFromSky() / 15.0;
        boolean damage = damageTracker.consumeRecentDamage();
        long tMs = System.currentTimeMillis();
        Vector facing = bee.getLocation().getDirection();

        bridgeExecutor.submit(() -> {
            try {
                if (bridge == null) {
                    bridge = new BridgeClient(bridgeHost, bridgePort);
                }
                JsonObject response = bridge.sendSensorAndReceiveMotor(light, dorsalLight, damage, tMs);
                latestVelocity = MotorMapping.toVelocity(response, facing);
                JsonObject motor = response.getAsJsonObject("motor");
                if (motor != null) {
                    latestMotor = motor;
                }
                exchangeCount++;
            } catch (IOException e) {
                exchangeFailures++;
                plugin.getLogger().log(Level.WARNING,
                        "Troca com o simulador falhou — tentando reconectar na próxima. " + e.getMessage());
                closeQuietly();
            } finally {
                exchangeInFlight.set(false);
            }
        });
    }

    private void closeQuietly() {
        if (bridge != null) {
            try {
                bridge.close();
            } catch (IOException ignored) {
                // já estamos tratando uma falha de conexão, ignora erro no close
            }
            bridge = null;
        }
    }

    private Bee findMarkedBee() {
        for (var world : plugin.getServer().getWorlds()) {
            var found = world.getEntitiesByClass(Bee.class).stream()
                    .filter(marker::isMarked)
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
        }
        return null;
    }
}
