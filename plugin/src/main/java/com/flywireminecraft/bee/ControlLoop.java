package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.Bee;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final TouchSensor touchSensor = new TouchSensor();
    private final String bridgeHost;
    private final int bridgePort;

    private static final int LOG_EVERY_TICKS = 20;   // 1x por segundo
    private static final int VISUALIZE_EVERY_TICKS = 5; // 4Hz — 20Hz de partículas seria spam visual
    private static final int HUD_EVERY_TICKS = 4;    // 5Hz — F6, painel "Flywire Bee Live"

    private final ActivityVisualizer visualizer = new ActivityVisualizer();

    private BridgeClient bridge;
    private ExecutorService bridgeExecutor;
    private BukkitTask tickTask;
    private final AtomicBoolean exchangeInFlight = new AtomicBoolean(false);
    private volatile Vector latestVelocity = new Vector(0, 0, 0);
    // F7/AD-17 — velocidade que esteve ativa durante o tick que acabou de
    // passar, usada por TouchSensor pra saber o deslocamento ESPERADO e
    // comparar com o real (heurística de touch_contact). Não confundir com
    // latestVelocity, que é a próxima a ser aplicada.
    private volatile Vector lastAppliedVelocity = new Vector(0, 0, 0);
    private volatile JsonObject latestMotor = new JsonObject();
    private volatile int latestActiveDn = 0;
    // F7/AD-17 — telemetria do subcircuito bristle (vazio se server.py não
    // tiver bristle_connectome carregado).
    private volatile JsonObject latestBristleMotor = new JsonObject();
    // F7/AD-17 — achado em servidor real (20/09/2026): pausar TouchSensor só
    // ENQUANTO grooming está ativo não bastava. Toda vez que grooming CRUZA
    // o limiar (subindo OU descendo), a velocidade comandada muda de direção
    // abruptamente (voo horizontal <-> descida vertical) — a abelha tem
    // inércia física, não troca de direção instantaneamente, e esse
    // descompasso momentâneo parecia "toque" de novo, prolongando a
    // oscilação. Folga curta em torno de QUALQUER troca de estado (não só
    // entrando) resolve. GROOMING_TRANSITION_GRACE_TICKS é provisório, não
    // calibrado.
    private static final int GROOMING_TRANSITION_GRACE_TICKS = 10; // 0,5s a 20Hz
    private boolean wasGroomingActive = false;
    private int groomingGraceTicksLeft = 0;

    // F7/AD-17 — achado em servidor real (20/09/2026), SEPARADO do problema
    // acima: mesmo com grooming reagindo, nem "voar" (phototaxis) nem
    // "descer na vertical" (pouso) tiram a abelha de um obstáculo do LADO
    // (morro, degrau de bloco) — nenhum dos dois movimentos a afasta.
    // Também piora porque grooming oscilando perto do limiar rearma a folga
    // de transição o tempo todo, quase sempre pausando TouchSensor — o
    // sinal de toque nem chega a se sustentar direito. Recuperação MECÂNICA,
    // independente da decisão do circuito: mede deslocamento real a cada
    // STUCK_CHECK_TICKS; se ficou abaixo de STUCK_DISPLACEMENT_THRESHOLD_BLOCKS
    // e não é um pouso intencional (onGround + grooming ativo), aplica um
    // empurrão pra cima por RECOVERY_BOOST_TICKS — tenta escalar o
    // obstáculo. Constantes provisórias, não calibradas, primeira tentativa
    // de engenharia. Não fabrica sinal nenhum do circuito — é só robustez
    // de física de embodiment, mesma categoria do achado de `setVelocity()`
    // vencendo a IA nativa (ver "Risco investigado" no plugin/README.md).
    private static final int STUCK_CHECK_TICKS = 40; // 2s a 20Hz
    private static final double STUCK_DISPLACEMENT_THRESHOLD_BLOCKS = 0.3;
    private static final double RECOVERY_BOOST_BLOCKS_PER_TICK = 0.15;
    private static final int RECOVERY_BOOST_TICKS = 20; // 1s de empurrão
    private Location stuckCheckAnchor = null;
    private int stuckCheckTicksLeft = STUCK_CHECK_TICKS;
    private int recoveryBoostTicksLeft = 0;

    private volatile long exchangeCount = 0;
    private volatile long exchangeFailures = 0;
    private long tickCount = 0;
    private volatile Double forcedLight = null;
    private volatile boolean touchLesioned = false; // F7/AD-17 — ver setTouchLesioned
    private volatile boolean visualize = true;
    // F6/AD-16 — direção COMANDADA, persiste entre trocas (não é a orientação
    // visual da abelha, que a IA nativa continua controlando). null = precisa
    // reinicializar a partir de bee.getLocation().getDirection() no próximo
    // tick. Bug corrigido 17/09/2026: antes recapturava a orientação real da
    // abelha a cada troca, então a rotação de yaw_steering nunca acumulava —
    // ver MotorMapping.java.
    private volatile Vector heading = null;

    // F5 — ferramenta de lesão por comando (server.py, campo "mute"). Nomes
    // válidos: os 8 grupos por prefixo de cell_type + "sensory" (fotorreceptores).
    // Mecanismo DIFERENTE do `lesioned` acima (que zera light) — este silencia
    // a saída sináptica do grupo de verdade, no engine.
    private final Set<String> mutedGroups = ConcurrentHashMap.newKeySet();
    private volatile boolean muteDirty = false;

    /** F5 — liga/desliga as partículas de atividade. Controle nunca depende disso. */
    public void setVisualize(boolean visualize) {
        this.visualize = visualize;
    }

    public void mute(String groupName) {
        mutedGroups.add(groupName);
        muteDirty = true;
    }

    public void unmute(String groupName) {
        mutedGroups.remove(groupName);
        muteDirty = true;
    }

    public void unmuteAll() {
        mutedGroups.clear();
        muteDirty = true;
    }

    public Set<String> getMutedGroups() {
        return Set.copyOf(mutedGroups);
    }

    // F5 — estimulação dirigida (server.py, campo "stimulate"). Soma com o
    // estímulo de luz nos fotorreceptores, não o substitui.
    private volatile BridgeClient.StimulateSpec directedStimulus;
    private volatile boolean stimulateDirty = false;

    public void stimulate(String groupName, double amplitude) {
        directedStimulus = new BridgeClient.StimulateSpec(groupName, amplitude);
        stimulateDirty = true;
    }

    public void stopStimulating() {
        directedStimulus = new BridgeClient.StimulateSpec(null, 0.0);
        stimulateDirty = true;
    }

    /**
     * RN — experimento de lesão (docs/00-visao-geral.md, critério de
     * falsificação): quando true, o sensor de luz real é ignorado e sempre
     * se envia light=0 — os fotorreceptores nunca recebem estímulo do mundo,
     * só a dinâmica basal (bias+ruído) do circuito continua rodando. A lesão
     * é aplicada aqui (do lado do plugin, antes de mandar pra ponte), não no
     * simulador — mais simples e não exige mudar o protocolo. Implementado
     * em cima de {@link #setForcedLight} (lesão = caso particular, light=0).
     */
    public void setLesioned(boolean lesioned) {
        this.forcedLight = lesioned ? 0.0 : null;
    }

    /**
     * F7/AD-17 — experimento de lesão pro `bristle` (toque): quando true,
     * {@code damage}/{@code touch_contact}/{@code touch_proximity} sempre
     * chegam `false` na ponte, não importa o que os sensores reais
     * detectem — mesma filosofia de {@link #setLesioned}, aplicada aqui
     * (plugin) em vez do simulador, sem mudar o protocolo. Os sensores
     * continuam rodando de verdade (não pausa `TouchSensor`/`DamageTracker`)
     * — só a LEITURA que chega no circuito é mascarada, igual `light=0` não
     * desliga o bloco, só ignora o valor real dele.
     */
    public void setTouchLesioned(boolean lesioned) {
        this.touchLesioned = lesioned;
    }

    /**
     * F6 — experimento de dose-resposta ({@link LightDoseResponseExperiment}):
     * substitui a luz real por um valor arbitrário em [0, 1], ignorando o
     * bloco onde a abelha está. {@code null} volta a usar a luz real. Mesmo
     * mecanismo de {@link #setLesioned} generalizado — dois overrides ao
     * mesmo tempo não fazem sentido, quem chamar por último vence.
     */
    public void setForcedLight(Double level) {
        this.forcedLight = level;
    }

    /**
     * F6/AD-16 — reinicia a direção comandada a partir da orientação real da
     * abelha no próximo tick. Chamar antes de cada trial de
     * {@link SteeringValidationExperiment} — sem isso, o giro acumulado de um
     * trial vazaria pro início do próximo, quebrando a independência entre
     * trials.
     */
    public void resetHeading() {
        this.heading = null;
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
        lastAppliedVelocity = new Vector(0, 0, 0);
        heading = null; // F6/AD-16 — começa do zero, da orientação real da abelha
        touchSensor.reset(); // F7/AD-17 — sem posição anterior pra comparar ainda
        wasGroomingActive = false;
        groomingGraceTicksLeft = 0;
        stuckCheckAnchor = null;
        stuckCheckTicksLeft = STUCK_CHECK_TICKS;
        recoveryBoostTicksLeft = 0;
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::onTick, 0L, 1L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        LiveHud.clear(plugin);
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

        // F7/AD-17 — bug encontrado em servidor real (20/09/2026): enquanto o
        // grooming já está no controle (ver MotorMapping.isGroomingActive),
        // NÃO grava contato — senão o próprio pouso vira um loop
        // auto-sustentado (parar em cima de bloco esbarra ao tentar
        // micro-mover, conta como touch_contact, realimenta grooming, nunca
        // solta). touchSensor.reset() garante que, quando grooming soltar,
        // a comparação recomeça do zero, sem posição antiga arrastada.
        boolean groomingActive = MotorMapping.isGroomingActive(latestBristleMotor);
        if (groomingActive != wasGroomingActive) {
            // Acabou de cruzar o limiar (subindo ou descendo) — folga, ver
            // docstring do campo acima.
            groomingGraceTicksLeft = GROOMING_TRANSITION_GRACE_TICKS;
        }
        wasGroomingActive = groomingActive;

        if (groomingActive || groomingGraceTicksLeft > 0) {
            touchSensor.reset();
            if (groomingGraceTicksLeft > 0) {
                groomingGraceTicksLeft--;
            }
        } else {
            // grava ANTES de aplicar a velocidade nova: compara a posição
            // atual (resultado de lastAppliedVelocity, aplicada no tick
            // anterior) contra a posição gravada na chamada anterior.
            touchSensor.recordTick(bee, lastAppliedVelocity);
        }

        // F7/AD-17 — recuperação mecânica de obstáculo lateral, independente
        // da decisão do circuito (ver docstring do campo). Mede a cada
        // STUCK_CHECK_TICKS; não reseta o relógio durante um empurrão em
        // andamento (senão nunca teria chance de medir se ele funcionou).
        if (stuckCheckAnchor == null) {
            stuckCheckAnchor = bee.getLocation();
        } else if (recoveryBoostTicksLeft == 0) {
            stuckCheckTicksLeft--;
            if (stuckCheckTicksLeft <= 0) {
                double moved = bee.getLocation().distance(stuckCheckAnchor);
                boolean intentionalLanding = bee.isOnGround() && groomingActive;
                if (moved < STUCK_DISPLACEMENT_THRESHOLD_BLOCKS && !intentionalLanding) {
                    recoveryBoostTicksLeft = RECOVERY_BOOST_TICKS;
                    plugin.getLogger().info(String.format(Locale.ROOT,
                            "[ControlLoop] recuperação: só %.2f blocos em %d ticks — empurrão pra cima",
                            moved, STUCK_CHECK_TICKS));
                }
                stuckCheckAnchor = bee.getLocation();
                stuckCheckTicksLeft = STUCK_CHECK_TICKS;
            }
        }

        // RN-06: aplica o último vetor já calculado, nunca espera a ponte —
        // exceto durante um empurrão de recuperação em andamento, que
        // sobrescreve por cima (ver acima).
        Vector velocityToApply;
        if (recoveryBoostTicksLeft > 0) {
            velocityToApply = new Vector(0, RECOVERY_BOOST_BLOCKS_PER_TICK, 0);
            recoveryBoostTicksLeft--;
        } else {
            velocityToApply = latestVelocity;
        }
        bee.setVelocity(velocityToApply);
        lastAppliedVelocity = velocityToApply;

        // F6/AD-16 — achado do usuário (18/09/2026): setVelocity() move a
        // abelha, mas NÃO gira o corpo visual dela — isso é responsabilidade
        // da IA nativa (MoveControl), que só ajusta esporadicamente quando
        // não está perseguindo objetivo nenhum (a maioria removida por
        // `goals off`). Resultado: corpo aponta pra um lado, movimento real
        // vai pra outro — parece "andar de ré" mesmo o giro medido
        // (net_turn_rad) estando correto. Corrigido girando o corpo junto
        // com `latestVelocity` a cada tick — conversão vetor->yaw padrão do
        // Bukkit (yaw=0 look +Z/sul, cresce no sentido horário visto de cima).
        if (latestVelocity.lengthSquared() > 1.0E-6) {
            Vector dir = latestVelocity.clone().normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
            bee.setRotation(yaw, bee.getLocation().getPitch());
        }

        if (visualize && tickCount % VISUALIZE_EVERY_TICKS == 0) {
            visualizer.render(bee, latestMotor);
        }
        if (tickCount % HUD_EVERY_TICKS == 0) {
            LiveHud.update(plugin, latestMotor, latestActiveDn, latestBristleMotor);
        }

        double realLight = bee.getLocation().getBlock().getLightLevel() / 15.0;
        Double forced = forcedLight;
        double light = forced != null ? forced : realLight;

        if (tickCount % LOG_EVERY_TICKS == 0) {
            String grooming = latestBristleMotor.has("grooming")
                    ? String.format(Locale.ROOT, "%.3f", latestBristleMotor.get("grooming").getAsDouble())
                    : "-";
            plugin.getLogger().info(String.format(Locale.ROOT,
                    "[ControlLoop] light=%.2f (real=%.2f, forçado=%s) vel=%s trocas=%d falhas=%d "
                            + "proximity=%s grooming=%s onGround=%s",
                    light, realLight, forced, latestVelocity, exchangeCount, exchangeFailures,
                    touchSensor.isNearSomething(bee), grooming, bee.isOnGround()));
        }
        tickCount++;

        if (!exchangeInFlight.compareAndSet(false, true)) {
            return; // troca anterior ainda em andamento
        }

        double dorsalLight = bee.getLocation().getBlock().getLightFromSky() / 15.0;
        boolean damage = damageTracker.consumeRecentDamage();
        // F7/AD-17 — família de sensores de toque (ver TouchSensor): contact
        // é borda (consumido), proximity é nível (lido de novo a cada troca).
        boolean touchContact = touchSensor.consumeContact();
        boolean touchProximity = touchSensor.isNearSomething(bee);
        if (touchContact) {
            // F7/AD-17 — log imediato, não espera o resumo periódico (LOG_EVERY_TICKS):
            // é o único jeito de confirmar visualmente que o teste manual (voar contra
            // parede) disparou o sensor. Ver "Sensor de toque" em plugin/README.md.
            plugin.getLogger().info("[TouchSensor] touch_contact = true "
                    + "(deslocamento real bem abaixo do esperado pela velocidade comandada)");
        }
        long tMs = System.currentTimeMillis();
        // F6/AD-16: heading é a direção COMANDADA da troca anterior, não a
        // orientação real da abelha — só cai pra getDirection() se ainda não
        // tem estado (início do controle ou depois de resetHeading()).
        Vector currentHeading = heading != null ? heading : bee.getLocation().getDirection();

        // Só marca muteDirty=false DEPOIS do envio ter sucesso (dentro do try
        // abaixo) — se a troca falhar, a mudança de mute não pode se perder
        // silenciosamente, tem que tentar de novo na próxima troca bem-sucedida.
        boolean sendMuteThisTime = muteDirty;
        List<String> muteToSend = sendMuteThisTime ? List.copyOf(mutedGroups) : null;
        boolean sendStimulateThisTime = stimulateDirty;
        BridgeClient.StimulateSpec stimulateToSend = sendStimulateThisTime ? directedStimulus : null;

        // F7/AD-17 — experimento de lesão do bristle: mascara o que é
        // ENVIADO, não o que é detectado (mesma lógica de setLesioned/light,
        // ver docstring de setTouchLesioned). consumeContact() já rodou
        // acima — mascarar depois não perde nem acumula o estado real.
        boolean touchLesionedNow = touchLesioned;
        boolean damageToSend = touchLesionedNow ? false : damage;
        boolean touchContactToSend = touchLesionedNow ? false : touchContact;
        boolean touchProximityToSend = touchLesionedNow ? false : touchProximity;

        bridgeExecutor.submit(() -> {
            try {
                if (bridge == null) {
                    bridge = new BridgeClient(bridgeHost, bridgePort);
                }
                JsonObject response = bridge.sendSensorAndReceiveMotor(
                        light, dorsalLight, damageToSend, touchContactToSend, touchProximityToSend, tMs,
                        muteToSend, stimulateToSend);
                if (sendMuteThisTime) {
                    muteDirty = false;
                }
                if (sendStimulateThisTime) {
                    stimulateDirty = false;
                }
                Vector newHeading = MotorMapping.rotatedHeading(response, currentHeading);
                latestVelocity = MotorMapping.toVelocity(response, newHeading, bee.isOnGround());
                heading = newHeading;
                JsonObject motor = response.getAsJsonObject("motor");
                if (motor != null) {
                    latestMotor = motor;
                }
                if (response.has("active_dn")) {
                    latestActiveDn = response.get("active_dn").getAsInt();
                }
                JsonObject bristleMotor = response.getAsJsonObject("bristle_motor");
                if (bristleMotor != null) {
                    latestBristleMotor = bristleMotor;
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
