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
    // F7/AD-17 — telemetria do subcircuito hygro (vazio se server.py não
    // tiver hygro_connectome carregado). Só o canal `hygrotaxis`, telemetria
    // pura — ver hygro_motor.py, não entra em MotorMapping.java ainda.
    private volatile JsonObject latestHygroMotor = new JsonObject();
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
    // F7 — polimento (pendência registrada em docs/03-roadmap-fases.md,
    // 20/09/2026): o empurrão era só vertical, sem componente horizontal pra
    // longe do obstáculo nem giro do corpo, por isso parecia um solavanco em
    // vez de um movimento de escape. Direção horizontal = oposto da direção
    // COMANDADA no momento em que travou (heading, não a orientação visual)
    // — é a direção que a abelha estava tentando seguir quando esbarrou,
    // logo o obstáculo está aproximadamente nela. Estimativa de engenharia,
    // não calibrada, mesma categoria das constantes acima.
    private static final double RECOVERY_BOOST_HORIZONTAL_BLOCKS_PER_TICK = 0.10;
    private Location stuckCheckAnchor = null;
    private int stuckCheckTicksLeft = STUCK_CHECK_TICKS;
    private int recoveryBoostTicksLeft = 0;
    private Vector recoveryEscapeDirection = new Vector(0, 0, 0);
    // F7/AD-17 — bug 2 real (21/09/2026, ver MotorMapping.toVelocity):
    // bee.isOnGround()/isInWater() não são estáveis tick a tick na
    // superfície da água (física de boiar do jogo + leitura fora da thread
    // principal), então reagir a uma leitura de UM tick só fazia o mergulho
    // de busca de abrigo reativar em rajadas, girando o corpo sem parar até
    // a abelha morrer de novo.
    //
    // F7/AD-17 — bug 3 real (22/09/2026), no fix de "buscar até achar
    // abrigo": uma trava PERMANENTE (true pra sempre até o canal desativar)
    // resolvia o bug 2, mas criava outro — uma vez tocando chão/água UMA
    // VEZ (mesmo de raspão, ou empurrada pelo sistema de recuperação de
    // obstáculo), o código achava "já pousou" PRA SEMPRE, mesmo que ela
    // estivesse bem no ar de novo — ela só deslizava na horizontal, nunca
    // mais mergulhava. Corrigido trocando a trava permanente por uma
    // JANELA CURTA de tolerância: `landed` fica true por até
    // LANDED_GRACE_TICKS depois do último toque confirmado — absorve
    // flicker de 1 tick (bug 2) sem perder decolagem de verdade (bug 3).
    private static final int LANDED_GRACE_TICKS = 10; // ~0,5s a 20Hz, mesma ordem de GROOMING_TRANSITION_GRACE_TICKS
    private volatile int ticksSinceGroundOrWaterContact = LANDED_GRACE_TICKS;
    // F7/AD-17 — decisão do usuário (21/09/2026): "abrigo" só conta com
    // teto de verdade acima (ShelterSensor), não só ter tocado chão/água
    // em qualquer lugar a céu aberto. Trava assim que observa cobertura
    // real; só destrava quando o canal do hygro desativa. Sem conceito
    // equivalente pro grooming (toque não tem noção de "abrigo").
    private volatile boolean shelterFoundThisEpisode = false;

    private volatile long exchangeCount = 0;
    private volatile long exchangeFailures = 0;
    private long tickCount = 0;
    private volatile Double forcedLight = null;
    private volatile boolean touchLesioned = false; // F7/AD-17 — ver setTouchLesioned
    private volatile boolean hygroLesioned = false; // F7/AD-17 — ver setHygroLesioned
    private volatile boolean bristleSuppressedForExperiment = false; // F7/AD-17 — ver setBristleSuppressedForExperiment
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
     * F7/AD-17 — experimento de lesão pro `hygro` (chuva): quando true,
     * {@code raining} sempre chega `false` na ponte, não importa o que
     * {@code World#hasStorm()} diga de verdade — mesma filosofia de
     * {@link #setLesioned}/{@link #setTouchLesioned}. O sensor real continua
     * sendo lido (não pausa nada) — só a LEITURA enviada é mascarada.
     */
    public void setHygroLesioned(boolean lesioned) {
        this.hygroLesioned = lesioned;
    }

    /**
     * F7/AD-17 — confundidor real encontrado na primeira rodada de
     * {@code /flywirebee hygrolesion} (22/09/2026): a origem precisa ficar
     * perto de árvore/construção pra ter abrigo de verdade (`ShelterSensor`),
     * mas "perto de árvore" também aciona {@code touch_proximity} do
     * `bristle` (raio de 3 blocos) — o log confirmou {@code grooming}
     * saturado (~0,998) o experimento inteiro, e {@code MotorMapping}
     * checa `grooming` ANTES de `hygrotaxis`, então o pouso do `grooming`
     * mascarava a busca de abrigo do `hygro` o tempo todo, invalidando a
     * medição. Diferente de {@link #setTouchLesioned} (mascara o que é
     * ENVIADO ao circuito do bristle) — isto faz {@code MotorMapping}
     * ignorar a saída do bristle na hora de decidir velocidade, mesmo que
     * o circuito continue rodando e disparando de verdade (telemetria/
     * visualização não mentem, só o efeito no movimento é suprimido).
     * Escopo: só durante o experimento de lesão do hygro, ligado/desligado
     * pelo próprio {@link HygroLesionExperiment}.
     */
    public void setBristleSuppressedForExperiment(boolean suppressed) {
        this.bristleSuppressedForExperiment = suppressed;
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
        recoveryEscapeDirection = new Vector(0, 0, 0);
        ticksSinceGroundOrWaterContact = LANDED_GRACE_TICKS;
        shelterFoundThisEpisode = false;
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
                // F7/AD-17 — pouso do grooming é sempre intencional dentro da
                // janela de tolerância (ticksSinceGroundOrWaterContact, bug 3
                // em ControlLoop). Busca de abrigo (hygro) só conta como
                // intencional quando achou cobertura DE VERDADE
                // (shelterFoundThisEpisode) — enquanto ainda procurando
                // (tocou chão/água mas sem teto), ela continua se deslocando,
                // e se ficar presa contra um obstáculo nesse meio tempo, o
                // sistema de recuperação deve continuar podendo ajudar, não
                // achar que é "abrigo".
                boolean landedStable = ticksSinceGroundOrWaterContact < LANDED_GRACE_TICKS;
                boolean intentionalLanding = (landedStable && groomingActive)
                        || (shelterFoundThisEpisode
                                && MotorMapping.isSeekingShelterActive(latestHygroMotor));
                if (moved < STUCK_DISPLACEMENT_THRESHOLD_BLOCKS && !intentionalLanding) {
                    recoveryBoostTicksLeft = RECOVERY_BOOST_TICKS;
                    Vector commandedDirection = heading != null ? heading : bee.getLocation().getDirection();
                    recoveryEscapeDirection = horizontalOpposite(commandedDirection);
                    plugin.getLogger().info(String.format(Locale.ROOT,
                            "[ControlLoop] recuperação: só %.2f blocos em %d ticks — empurrão pra cima e pra longe (%s)",
                            moved, STUCK_CHECK_TICKS, recoveryEscapeDirection));
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
            velocityToApply = new Vector(
                    recoveryEscapeDirection.getX() * RECOVERY_BOOST_HORIZONTAL_BLOCKS_PER_TICK,
                    RECOVERY_BOOST_BLOCKS_PER_TICK,
                    recoveryEscapeDirection.getZ() * RECOVERY_BOOST_HORIZONTAL_BLOCKS_PER_TICK);
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
        // com a velocidade REALMENTE aplicada a cada tick (não
        // `latestVelocity` — durante um empurrão de recuperação (F7,
        // polimento) o corpo precisa acompanhar a direção de escape, não a
        // direção que o circuito escolheria se não tivesse travado) —
        // conversão vetor->yaw padrão do Bukkit (yaw=0 look +Z/sul, cresce no
        // sentido horário visto de cima).
        if (velocityToApply.lengthSquared() > 1.0E-6) {
            Vector dir = velocityToApply.clone().normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
            bee.setRotation(yaw, bee.getLocation().getPitch());
        }

        if (visualize && tickCount % VISUALIZE_EVERY_TICKS == 0) {
            visualizer.render(bee, latestMotor, latestBristleMotor, latestHygroMotor);
        }
        if (tickCount % HUD_EVERY_TICKS == 0) {
            LiveHud.update(plugin, latestMotor, latestActiveDn, latestBristleMotor, latestHygroMotor);
        }

        double realLight = bee.getLocation().getBlock().getLightLevel() / 15.0;
        Double forced = forcedLight;
        double light = forced != null ? forced : realLight;
        // F7/AD-17 — sensor do subcircuito hygro (chuva): nível, igual
        // touch_proximity, não borda. World#hasStorm() já reflete o ciclo de
        // clima do Minecraft sem precisar de heurística nenhuma (diferente
        // de touch_contact, que precisou de uma por falta de evento nativo).
        // Lido aqui (antes do log) e reaproveitado mais abaixo pra troca com
        // a ponte — mesmo valor, não duas leituras.
        boolean raining = bee.getWorld().hasStorm();

        if (tickCount % LOG_EVERY_TICKS == 0) {
            String grooming = latestBristleMotor.has("grooming")
                    ? String.format(Locale.ROOT, "%.3f", latestBristleMotor.get("grooming").getAsDouble())
                    : "-";
            String hygrotaxis = latestHygroMotor.has("hygrotaxis")
                    ? String.format(Locale.ROOT, "%.3f", latestHygroMotor.get("hygrotaxis").getAsDouble())
                    : "-";
            plugin.getLogger().info(String.format(Locale.ROOT,
                    "[ControlLoop] light=%.2f (real=%.2f, forçado=%s) vel=%s trocas=%d falhas=%d "
                            + "proximity=%s grooming=%s onGround=%s raining=%s hygrotaxis=%s",
                    light, realLight, forced, latestVelocity, exchangeCount, exchangeFailures,
                    touchSensor.isNearSomething(bee), grooming, bee.isOnGround(), raining, hygrotaxis));
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
        // F7/AD-17 — experimento de lesão do hygro: mascara o que é ENVIADO,
        // não o que é detectado (mesma lógica das linhas acima).
        boolean rainingToSend = hygroLesioned ? false : raining;

        bridgeExecutor.submit(() -> {
            try {
                if (bridge == null) {
                    bridge = new BridgeClient(bridgeHost, bridgePort);
                }
                JsonObject response = bridge.sendSensorAndReceiveMotor(
                        light, dorsalLight, damageToSend, touchContactToSend, touchProximityToSend,
                        rainingToSend, tMs, muteToSend, stimulateToSend);
                if (sendMuteThisTime) {
                    muteDirty = false;
                }
                if (sendStimulateThisTime) {
                    stimulateDirty = false;
                }
                Vector newHeading = MotorMapping.rotatedHeading(response, currentHeading);

                // F7/AD-17 — confundidor real (22/09/2026, ver docstring de
                // setBristleSuppressedForExperiment): durante o experimento
                // de lesão do hygro, ignora a saída do bristle na decisão de
                // velocidade (cópia sem "bristle_motor") — o circuito
                // continua rodando e disparando de verdade, só o EFEITO no
                // movimento é suprimido, pra não deixar grooming (acionado
                // por estar perto da árvore que dá abrigo) mascarar a busca
                // de abrigo do hygro.
                JsonObject responseForMotor = response;
                if (bristleSuppressedForExperiment) {
                    responseForMotor = response.deepCopy();
                    responseForMotor.remove("bristle_motor");
                }

                // F7/AD-17 — bugs 2 e 3 reais (ver docstring do campo
                // ticksSinceGroundOrWaterContact e de MotorMapping.toVelocity):
                // não passar onGround/isInWater crus pro MotorMapping —
                // janela curta de tolerância absorve flicker de 1 tick sem
                // travar "pousada" pra sempre se ela ficar no ar de novo.
                boolean touchingGroundOrWater = bee.isOnGround() || bee.isInWater();
                boolean groomingActiveNow =
                        MotorMapping.isGroomingActive(responseForMotor.getAsJsonObject("bristle_motor"));
                boolean shelterActiveNow =
                        MotorMapping.isSeekingShelterActive(responseForMotor.getAsJsonObject("hygro_motor"));
                if (!(groomingActiveNow || shelterActiveNow)) {
                    ticksSinceGroundOrWaterContact = LANDED_GRACE_TICKS; // próximo episódio começa "no ar"
                    shelterFoundThisEpisode = false;
                } else if (touchingGroundOrWater) {
                    ticksSinceGroundOrWaterContact = 0;
                    // F7/AD-17 — decisão do usuário: só conta abrigo com
                    // teto de verdade acima (ver ShelterSensor). Só checa
                    // geometria quando já tocou algo (barato: não varre
                    // blocos toda hora, só quando pode importar) e ainda
                    // não achou — depois de achar, trava e não recomputa.
                    if (shelterActiveNow && !shelterFoundThisEpisode
                            && ShelterSensor.hasShelterAbove(bee.getLocation())) {
                        shelterFoundThisEpisode = true;
                        // F7/AD-17 — diagnóstico (22/09/2026, usuário relatou
                        // parar sem cobertura visível): loga o valor bruto de
                        // getLightFromSky() e a posição no instante exato da
                        // decisão, pra confirmar se é o sensor errando (ex.:
                        // chuva mexendo no valor) ou um bloco isolado real
                        // (folha/borda) que não parece "abrigo" a olho nu.
                        Location loc = bee.getLocation();
                        plugin.getLogger().info(String.format(Locale.ROOT,
                                "[ControlLoop] abrigo encontrado — skylight=%d em (%.1f, %.1f, %.1f)",
                                loc.getBlock().getLightFromSky(), loc.getX(), loc.getY(), loc.getZ()));
                    }
                } else if (ticksSinceGroundOrWaterContact < LANDED_GRACE_TICKS) {
                    ticksSinceGroundOrWaterContact++;
                }
                boolean landed = ticksSinceGroundOrWaterContact < LANDED_GRACE_TICKS;
                boolean sheltered = shelterFoundThisEpisode;

                latestVelocity = MotorMapping.toVelocity(responseForMotor, newHeading, landed, sheltered);
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
                JsonObject hygroMotor = response.getAsJsonObject("hygro_motor");
                if (hygroMotor != null) {
                    latestHygroMotor = hygroMotor;
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

    /**
     * F7 — polimento da recuperação mecânica: direção horizontal (unitária,
     * Y=0) oposta a {@code direction}, usada pra empurrar a abelha pra longe
     * do obstáculo que ela estava tentando atravessar quando travou. Vetor
     * nulo se {@code direction} não tem componente horizontal (ex.: olhando
     * reto pra cima/baixo) — nesse caso o empurrão fica só vertical, como
     * antes deste polimento.
     */
    private static Vector horizontalOpposite(Vector direction) {
        Vector horizontal = new Vector(direction.getX(), 0, direction.getZ());
        if (horizontal.lengthSquared() < 1.0E-6) {
            return new Vector(0, 0, 0);
        }
        return horizontal.normalize().multiply(-1);
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
