package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Bee;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
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
    private final AlarmSensor alarmSensor; // F8 — vento/som (johnston)
    private final LoomingSensor loomingSensor = new LoomingSensor(); // F9/AD-20 — looming (escape)
    private final TasteSensor tasteSensor = new TasteSensor(); // F10 — comida (taste)
    private volatile boolean tasteTargetHeldByPlayer = false; // F10 — ver StatusLabel
    private final EnergyTracker energyTracker = new EnergyTracker(); // F11 — fome/energia, proxy de engenharia
    private final String bridgeHost;
    private final int bridgePort;

    private static final int LOG_EVERY_TICKS = 20;   // 1x por segundo
    private static final int VISUALIZE_EVERY_TICKS = 5; // 4Hz — 20Hz de partículas seria spam visual
    private static final int HUD_EVERY_TICKS = 4;    // 5Hz — F6, painel "Flywire Bee Live"

    private final ActivityVisualizer visualizer = new ActivityVisualizer();
    private final StatusLabel statusLabel = new StatusLabel(); // F9 — balão de texto acima da abelha

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
    // F8 — telemetria do subcircuito johnston (vazio se server.py não tiver
    // johnston_connectome carregado). Só o canal `startle`, telemetria pura.
    private volatile JsonObject latestJohnstonMotor = new JsonObject();
    // F9/AD-20 — telemetria do subcircuito escape (vazio se server.py não
    // tiver escape_connectome carregado). Só o canal `escape_drive`,
    // telemetria pura — ver escape_motor.py, não entra em MotorMapping.java
    // ainda (sem sensor calibrado/lesão em servidor real).
    private volatile JsonObject latestEscapeMotor = new JsonObject();
    // F10 — telemetria do subcircuito taste (vazio se server.py não tiver
    // taste_connectome carregado). Só o canal `appetite`, telemetria pura —
    // ver taste_motor.py, não entra em MotorMapping.java ainda.
    private volatile JsonObject latestTasteMotor = new JsonObject();
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
    // F7/AD-17 — polimento (22/09/2026), pedido do usuário: "travar entre
    // blocos" (cercada em vários lados) ficava perceptível demais — 2s só
    // pra DETECTAR que travou, mais um empurrão de força moderada que às
    // vezes não bastava pra escapar de vez, exigindo vários ciclos de
    // detecção+empurrão em sequência. Recalibrado: detecção mais rápida
    // (1s em vez de 2s — ainda uma barra baixa, 0.3 blocos, fácil de
    // passar em voo livre normal, então não deveria gerar mais falso
    // positivo) e empurrão mais longo/decisivo (1,5s em vez de 1s,
    // horizontal maior). Estimativas de engenharia, não calibradas.
    private static final int STUCK_CHECK_TICKS = 20; // 1s a 20Hz
    private static final double STUCK_DISPLACEMENT_THRESHOLD_BLOCKS = 0.3;
    private static final double RECOVERY_BOOST_BLOCKS_PER_TICK = 0.15;
    private static final int RECOVERY_BOOST_TICKS = 30; // 1,5s de empurrão
    // F7 — polimento (pendência registrada em docs/03-roadmap-fases.md,
    // 20/09/2026): o empurrão era só vertical, sem componente horizontal pra
    // longe do obstáculo nem giro do corpo, por isso parecia um solavanco em
    // vez de um movimento de escape. Direção horizontal = oposto da direção
    // COMANDADA no momento em que travou (heading, não a orientação visual)
    // — é a direção que a abelha estava tentando seguir quando esbarrou,
    // logo o obstáculo está aproximadamente nela. Estimativa de engenharia,
    // não calibrada, mesma categoria das constantes acima.
    private static final double RECOVERY_BOOST_HORIZONTAL_BLOCKS_PER_TICK = 0.15;
    // F9 — bug real, achado do usuário (25/09/2026): "oposto de heading"
    // parte de uma suposição que só vale na PRIMEIRA tentativa — que o
    // obstáculo está aproximadamente na direção que ela tentava seguir.
    // Numa área reclusa/cercada por vários lados, isso pode simplesmente
    // estar ERRADO (o obstáculo real pode estar em qualquer direção), e
    // como `heading` (yaw_steering do ocelar) gira muito devagar
    // (MAX_YAW_RADIANS_PER_TICK=0,01), tentativas consecutivas usavam
    // quase a MESMA direção — log real mostrou o mesmo vetor de empurrão
    // (~variação de 0,001) se repetindo por dezenas de segundos, sem nunca
    // liberar. Corrigido escalando a estratégia: a PRIMEIRA falha ainda usa
    // a heurística original (barata, funciona na maioria dos casos — morro/
    // degrau únicos); a partir da SEGUNDA falha consecutiva no mesmo
    // episódio de travamento, sorteia uma direção horizontal aleatória a
    // cada nova tentativa — explora em vez de insistir numa hipótese que já
    // provou estar errada. Reseta assim que ela volta a se mover de verdade.
    private static final java.util.Random STUCK_RECOVERY_RNG = new java.util.Random();
    private Location stuckCheckAnchor = null;
    private int stuckCheckTicksLeft = STUCK_CHECK_TICKS;
    private int recoveryBoostTicksLeft = 0;
    private Vector recoveryEscapeDirection = new Vector(0, 0, 0);
    private int consecutiveStuckCount = 0;

    // F7/AD-17 — pedido do usuário (22/09/2026): nem todo toque/proximidade
    // deveria virar pouso-e-limpeza — sem dado químico real da mosca pra
    // decidir quando, sorteia 50/50 a cada NOVO episódio de grooming
    // (mesmo instante em que a folga de transição já existente detecta a
    // subida do limiar): metade das vezes pousa e se limpa (comportamento
    // original, já validado por lesão), metade só DESVIA — empurrão breve
    // pra longe da direção atual (mesmo mecanismo/escala do sistema de
    // recuperação de obstáculo acima, reaproveitado) e depois volta a voar
    // normal (phototaxis/hygro), ignorando esse episódio de grooming por
    // completo. Engenharia pra dar variedade de comportamento, NÃO é
    // achado biológico novo — RN-08/RN-09 e a validação por lesão do
    // grooming continuam intocadas, isto só decide QUANDO deixar o sinal
    // já validado controlar o movimento.
    private static final java.util.Random GROOMING_DODGE_RNG = new java.util.Random();
    private static final int DODGE_BOOST_TICKS = 20; // ~1s, mesma escala do RECOVERY_BOOST_TICKS
    private static final double DODGE_BOOST_HORIZONTAL_BLOCKS_PER_TICK = 0.2;
    private volatile boolean groomingEpisodeIsDodge = false;
    private int dodgeBoostTicksLeft = 0;
    private Vector dodgeDirection = new Vector(0, 0, 0);

    // F9 — pedido do usuário (25/09/2026): escape_drive/looming_threat são
    // sinais de NÍVEL que podem cair rápido (a ameaça mais próxima sai do
    // raio de busca, ou a distância para de fechar) — sem isto, a fuga
    // podia durar só 1-2 trocas (menos de 100ms), tempo curto demais pra
    // observar visualmente o comportamento. Mesmo mecanismo de "trava
    // temporal" já usado em LANDED_GRACE_TICKS/SHELTER_ABANDON_TICKS: uma
    // vez que o circuito REAL cruza o limiar, garante pelo menos
    // ESCAPE_LATCH_TICKS de fuga visível, recarregando a contagem enquanto
    // a ameaça continuar de verdade (não é um tempo fixo desde o disparo —
    // se a ameaça persistir, a fuga persiste). Direção é capturada uma vez
    // por recarga (não recalculada a cada tick do latch) — estável o
    // bastante pra dar pra acompanhar visualmente, mesmo se a ameaça saltar
    // de posição entre trocas.
    private static final int ESCAPE_LATCH_TICKS = 50; // ~2,5s a 20Hz, dentro do pedido de "2 a 3 segundos"
    private int escapeLatchTicksLeft = 0;
    private Vector escapeLatchDirection = new Vector(0, 0, 0);
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
    // F7/AD-17 — bug 7 real (22/09/2026), regressão do fix do bug 6 (abelha
    // flutuando parada): usar a MESMA janela curta (LANDED_GRACE_TICKS,
    // 0,5s) pra desfazer um abrigo já achado fez ela abandonar cobertura
    // real toda vez que um flicker normal de onGround (bug 2, mesma
    // instabilidade de sempre, agora batendo numa abelha PARADA embaixo de
    // galho/folha em vez de em voo) durasse um pouco mais que 0,5s — saía
    // voando de novo achando que tinha perdido o abrigo, mesmo continuando
    // no mesmo lugar. Corrigido separando os dois: LANDED_GRACE_TICKS
    // continua decidindo mergulhar vs. procurar (precisa ser curto, senão
    // ela demora pra reagir de verdade a ficar no ar), mas desistir de um
    // abrigo já achado usa uma janela bem mais tolerante
    // (SHELTER_ABANDON_TICKS, poucos segundos) — flicker de meio segundo
    // não derruba mais uma abelha genuinamente descansando; só ausência
    // sustentada de verdade (o cenário do bug 6, minutos flutuando) derruba.
    private static final int SHELTER_ABANDON_TICKS = 60; // ~3s a 20Hz
    // F7/AD-17 — busca guiada (23/09/2026, ver ShelterSensor.findNearbyShelterDirection
    // e docstring do parâmetro shelterDirectionHint em MotorMapping.toVelocity):
    // distância de sondagem, em blocos. Estimativa de engenharia — pequena o
    // bastante pra ser barata (8 lookups de heightmap por troca), grande o
    // bastante pra alcançar um abrigo pequeno perto antes dela passar batido.
    private static final int SHELTER_LOOK_AHEAD_BLOCKS = 4;
    private volatile int ticksSinceGroundOrWaterContact = LANDED_GRACE_TICKS;
    // F7/AD-17 — decisão do usuário (21/09/2026): "abrigo" só conta com
    // teto de verdade acima (ShelterSensor), não só ter tocado chão/água
    // em qualquer lugar a céu aberto. Trava assim que observa cobertura
    // real; só destrava quando o canal do hygro desativa. Sem conceito
    // equivalente pro grooming (toque não tem noção de "abrigo").
    private volatile boolean shelterFoundThisEpisode = false;
    // F7/AD-17 — polimento (22/09/2026): diferente de ticksSinceGroundOrWaterContact
    // (janela CURTA, esquece depois de LANDED_GRACE_TICKS no ar), este fica
    // true PRA SEMPRE dentro do episódio assim que ela toca chão/água pela
    // primeira vez — usado só pra MotorMapping saber se o próximo mergulho
    // é o primeiro do episódio (rápido) ou uma re-descida durante a busca
    // (suave, ver SEARCH_REDESCENT_BLOCKS_PER_TICK).
    private volatile boolean hasTouchedThisEpisode = false;

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
            AlarmSensor alarmSensor,
            String bridgeHost,
            int bridgePort
    ) {
        this.plugin = plugin;
        this.marker = marker;
        this.damageTracker = damageTracker;
        this.alarmSensor = alarmSensor;
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
        loomingSensor.reset(); // F9/AD-20 — sem distância anterior pra comparar ainda
        wasGroomingActive = false;
        groomingGraceTicksLeft = 0;
        stuckCheckAnchor = null;
        stuckCheckTicksLeft = STUCK_CHECK_TICKS;
        recoveryBoostTicksLeft = 0;
        recoveryEscapeDirection = new Vector(0, 0, 0);
        consecutiveStuckCount = 0;
        groomingEpisodeIsDodge = false;
        dodgeBoostTicksLeft = 0;
        dodgeDirection = new Vector(0, 0, 0);
        escapeLatchTicksLeft = 0;
        escapeLatchDirection = new Vector(0, 0, 0);
        tasteTargetHeldByPlayer = false;
        energyTracker.reset();
        ticksSinceGroundOrWaterContact = LANDED_GRACE_TICKS;
        shelterFoundThisEpisode = false;
        hasTouchedThisEpisode = false;
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::onTick, 0L, 1L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        LiveHud.clear(plugin);
        statusLabel.remove(); // F9 — não deixa o marcador do balão de texto sobrando no mundo
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
            if (groomingActive) {
                // F7/AD-17 — novo episódio começando: sorteia pousar vs
                // desviar (ver docstring de groomingEpisodeIsDodge).
                groomingEpisodeIsDodge = GROOMING_DODGE_RNG.nextBoolean();
                if (groomingEpisodeIsDodge) {
                    dodgeBoostTicksLeft = DODGE_BOOST_TICKS;
                    Vector awayFrom = heading != null ? heading : bee.getLocation().getDirection();
                    dodgeDirection = horizontalOpposite(awayFrom);
                }
                plugin.getLogger().info("[ControlLoop] episódio de grooming — sorteio: "
                        + (groomingEpisodeIsDodge ? "desvio" : "pouso e limpeza"));
            }
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

        // F9/AD-20 — sem interação com grooming/pouso (diferente do touch
        // acima) — só rastreia distância até ameaça mais próxima, todo tick.
        loomingSensor.recordTick(bee);

        // F7/AD-17 — recuperação mecânica de obstáculo lateral, independente
        // da decisão do circuito (ver docstring do campo). Mede a cada
        // STUCK_CHECK_TICKS; não reseta o relógio durante um empurrão em
        // andamento (senão nunca teria chance de medir se ele funcionou).
        if (stuckCheckAnchor == null) {
            stuckCheckAnchor = bee.getLocation();
        } else if (recoveryBoostTicksLeft == 0) {
            stuckCheckTicksLeft--;
            if (stuckCheckTicksLeft <= 0) {
                // F7/AD-17 — achado real (22/09/2026): abelha presa entre
                // blocos nas duas laterais + na frente (retaguarda livre)
                // durante a busca de abrigo, sistema de recuperação NUNCA
                // disparou. Causa: distance() mede 3D total, e o planeio da
                // busca (SEARCH_HOVER_BLOCKS_PER_TICK) sozinho já produz
                // deslocamento vertical suficiente pra passar do limiar de
                // 0,3 blocos — o detector achava "não travou" só por ela
                // estar subindo/descendo, mesmo presa na horizontal.
                // Corrigido medindo só X/Z: o que importa pra saber se ela
                // está escapando de um cercado lateral é progresso
                // horizontal, não altitude.
                Location current = bee.getLocation();
                double dx = current.getX() - stuckCheckAnchor.getX();
                double dz = current.getZ() - stuckCheckAnchor.getZ();
                double moved = Math.sqrt(dx * dx + dz * dz);
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
                    consecutiveStuckCount++;
                    recoveryBoostTicksLeft = RECOVERY_BOOST_TICKS;
                    if (consecutiveStuckCount == 1) {
                        // Primeira falha: heurística barata, funciona na
                        // maioria dos casos (obstáculo único na direção que
                        // ela tentava seguir).
                        Vector commandedDirection = heading != null ? heading : bee.getLocation().getDirection();
                        recoveryEscapeDirection = horizontalOpposite(commandedDirection);
                    } else {
                        // F9 — 2ª+ falha consecutiva: a heurística acima já
                        // provou estar errada pra este travamento — explora
                        // em vez de repetir a mesma direção (ver docstring
                        // do campo STUCK_RECOVERY_RNG).
                        recoveryEscapeDirection = randomHorizontalDirection();
                    }
                    plugin.getLogger().info(String.format(Locale.ROOT,
                            "[ControlLoop] recuperação (tentativa %d): só %.2f blocos em %d ticks — "
                                    + "empurrão pra cima e pra longe (%s)",
                            consecutiveStuckCount, moved, STUCK_CHECK_TICKS, recoveryEscapeDirection));
                } else {
                    consecutiveStuckCount = 0;
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
        } else if (dodgeBoostTicksLeft > 0) {
            // F7/AD-17 — episódio de grooming sorteado como "desvio" (ver
            // groomingEpisodeIsDodge): empurrão breve pra longe, puramente
            // horizontal, mesmo mecanismo do sistema de recuperação acima.
            velocityToApply = new Vector(
                    dodgeDirection.getX() * DODGE_BOOST_HORIZONTAL_BLOCKS_PER_TICK,
                    0,
                    dodgeDirection.getZ() * DODGE_BOOST_HORIZONTAL_BLOCKS_PER_TICK);
            dodgeBoostTicksLeft--;
        } else if (escapeLatchTicksLeft > 0) {
            // F11 — fuga NÃO é reduzida pela fome (medo vence cansaço).
            velocityToApply = latestVelocity;
        } else {
            velocityToApply = applyHungerModifiers(bee, latestVelocity);
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
            visualizer.render(bee, latestMotor, latestBristleMotor, latestHygroMotor, latestJohnstonMotor,
                    latestEscapeMotor, latestTasteMotor);
            // F9 — mesma cadência das partículas, texto não precisa de 20Hz.
            // escapeLatchTicksLeft é campo (não local), seguro de ler aqui
            // mesmo computado mais abaixo nesta troca — reflete o valor do
            // FIM do tick anterior, defasagem de 1 tick (50ms), imperceptível.
            statusLabel.update(bee, latestBristleMotor, latestHygroMotor, latestJohnstonMotor, latestTasteMotor,
                    groomingEpisodeIsDodge, shelterFoundThisEpisode, escapeLatchTicksLeft > 0,
                    tasteTargetHeldByPlayer, energyTracker.level());
        }
        if (tickCount % HUD_EVERY_TICKS == 0) {
            LiveHud.update(plugin, latestMotor, latestActiveDn, latestBristleMotor, latestHygroMotor,
                    latestJohnstonMotor, latestEscapeMotor, latestTasteMotor, energyTracker.level());
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
            String startle = latestJohnstonMotor.has("startle")
                    ? String.format(Locale.ROOT, "%.3f", latestJohnstonMotor.get("startle").getAsDouble())
                    : "-";
            String escapeDrive = latestEscapeMotor.has("escape_drive")
                    ? String.format(Locale.ROOT, "%.3f", latestEscapeMotor.get("escape_drive").getAsDouble())
                    : "-";
            String appetite = latestTasteMotor.has("appetite")
                    ? String.format(Locale.ROOT, "%.3f", latestTasteMotor.get("appetite").getAsDouble())
                    : "-";
            plugin.getLogger().info(String.format(Locale.ROOT,
                    "[ControlLoop] light=%.2f (real=%.2f, forçado=%s) vel=%s trocas=%d falhas=%d "
                            + "proximity=%s grooming=%s onGround=%s raining=%s hygrotaxis=%s "
                            + "hostileMob=%s startle=%s looming=%s escapeDrive=%s food=%s appetite=%s",
                    light, realLight, forced, latestVelocity, exchangeCount, exchangeFailures,
                    touchSensor.isNearSomething(bee), grooming, bee.isOnGround(), raining, hygrotaxis,
                    alarmSensor.isHostileMobNearby(bee), startle, loomingSensor.isLoomingThreat(), escapeDrive,
                    tasteSensor.findNearestFood(bee) != null, appetite));
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
        // F8 — sensor de "som" (ver AlarmSensor): explosão é borda, mob
        // hostil e música são nível, mesma dualidade da família de toque
        // acima. Música: achado do usuário (24/09/2026) — som ambiente
        // também conta, não só ameaça.
        boolean alarmExplosion = alarmSensor.consumeExplosion();
        boolean alarmHostileMob = alarmSensor.isHostileMobNearby(bee);
        boolean soundMusic = alarmSensor.isMusicNearby(bee);
        if (alarmExplosion) {
            plugin.getLogger().info("[AlarmSensor] alarm_explosion = true (explosão perto da abelha)");
        }
        // F9/AD-20 — sensor do subcircuito escape (ver LoomingSensor): nível,
        // resultado do recordTick já rodado nesta chamada de onTick, acima.
        boolean loomingThreat = loomingSensor.isLoomingThreat();
        // F9 — bug real, servidor real (25/09/2026): World#getNearbyEntities
        // (usado por fleeDirectionAwayFromNearestThreat) só pode ser chamado
        // na thread principal — Paper derruba com AsyncCatcher se chamado de
        // dentro do lambda do bridgeExecutor (thread "flywire-control-bridge"),
        // travando a troca ANTES de atualizar latestVelocity/latestBristleMotor
        // (grooming/etc. congelavam no último valor bem-sucedido). Corrigido
        // computando aqui, sempre (mesmo padrão de alarmHostileMob/
        // touchProximity acima), e só USANDO o resultado lá embaixo se
        // escape estiver ativo — nunca chamar Bukkit API de dentro do lambda.
        Vector nearestThreatFleeDirection = loomingSensor.fleeDirectionAwayFromNearestThreat(bee);
        // F10 — sensor do subcircuito taste (ver TasteSensor): nível,
        // mesmo cuidado de thread do looming acima (varre blocos e usa
        // getNearbyEntities internamente) — sempre computado na thread
        // principal. A localização (não só sim/não) é reaproveitada mais
        // abaixo pra decidir a velocidade de busca de comida.
        TasteSensor.FoodTarget nearestFoodTarget = tasteSensor.findNearestFood(bee);
        boolean foodContact = nearestFoodTarget != null;
        // F10 — campo (não local), pra StatusLabel poder ler no início do
        // PRÓXIMO tick (o balão atualiza antes deste trecho rodar de novo
        // nesta mesma troca) — mesma defasagem de 1 tick já aceita em
        // escapeLatchTicksLeft, imperceptível a 20Hz.
        tasteTargetHeldByPlayer = nearestFoodTarget != null && nearestFoodTarget.heldByPlayer();
        // F11 — fome/energia (proxy de engenharia, ver EnergyTracker): usa
        // o MESMO alvo/distância de chegada do taste pra decidir "comendo"
        // agora, não um sensor novo. lastAppliedVelocity já reflete o
        // deslocamento REAL aplicado neste tick (mesma medida que
        // TouchSensor já usa).
        boolean eatingNow = nearestFoodTarget != null
                && nearestFoodTarget.location().distance(bee.getLocation()) < MotorMapping.TASTE_ARRIVAL_THRESHOLD_BLOCKS;
        energyTracker.tick(lastAppliedVelocity.length(), eatingNow);
        long tMs = System.currentTimeMillis();
        // F6/AD-16: heading é a direção COMANDADA da troca anterior, não a
        // orientação real da abelha — só cai pra getDirection() se ainda não
        // tem estado (início do controle ou depois de resetHeading()).
        Vector currentHeading = heading != null ? heading : bee.getLocation().getDirection();

        // F9 — trava temporal da fuga (ver docstring de ESCAPE_LATCH_TICKS):
        // usa latestEscapeMotor (estado real do último frame recebido, já
        // na thread principal — mesmo padrão de groomingActive no topo de
        // onTick), não o que vier na resposta desta troca (ainda em
        // andamento). Recarrega a contagem E a direção capturada sempre que
        // o circuito real está acima do limiar; só deixa a contagem cair
        // quando o circuito real já desativou.
        if (MotorMapping.isEscapeActive(latestEscapeMotor)) {
            escapeLatchTicksLeft = ESCAPE_LATCH_TICKS;
            escapeLatchDirection = nearestThreatFleeDirection != null ? nearestThreatFleeDirection : currentHeading;
        } else if (escapeLatchTicksLeft > 0) {
            escapeLatchTicksLeft--;
        }
        boolean escapeLatched = escapeLatchTicksLeft > 0;

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
        // F8 — ainda sem experimento de lesão pro johnston (mesma sequência
        // do hygro: sensor → protocolo → simulador antes de qualquer
        // experimento) — envia sempre o valor real, sem máscara ainda.

        bridgeExecutor.submit(() -> {
            try {
                if (bridge == null) {
                    bridge = new BridgeClient(bridgeHost, bridgePort);
                }
                JsonObject response = bridge.sendSensorAndReceiveMotor(
                        light, dorsalLight, damageToSend, touchContactToSend, touchProximityToSend,
                        rainingToSend, alarmExplosion, alarmHostileMob, soundMusic, loomingThreat,
                        foodContact, tMs, muteToSend, stimulateToSend);
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
                // F7/AD-17 — episódio de grooming sorteado como "desvio" (ver
                // groomingEpisodeIsDodge): mesma técnica, ignora bristle_motor
                // pro resto do episódio inteiro (não só durante o empurrão),
                // pra deixar phototaxis/hygro retomarem o controle normal.
                JsonObject responseForMotor = response;
                if (bristleSuppressedForExperiment || groomingEpisodeIsDodge) {
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
                    hasTouchedThisEpisode = false;
                } else if (touchingGroundOrWater) {
                    ticksSinceGroundOrWaterContact = 0;
                    hasTouchedThisEpisode = true;
                    // F7/AD-17 — decisão do usuário: só conta abrigo com
                    // teto de verdade acima (ver ShelterSensor). Só checa
                    // geometria quando já tocou algo (barato: não varre
                    // blocos toda hora, só quando pode importar) e ainda
                    // não achou — depois de achar, trava e não recomputa.
                    if (shelterActiveNow && !shelterFoundThisEpisode
                            && ShelterSensor.hasShelterAbove(bee.getLocation())) {
                        shelterFoundThisEpisode = true;
                        // F7/AD-17 — diagnóstico (22/09/2026, mantido após a
                        // troca pro heightmap MOTION_BLOCKING_NO_LEAVES —
                        // ver ShelterSensor): loga a posição e a altura do
                        // teto mais próximo detectado, pra facilitar
                        // conferir no mundo se bateu com um lugar real.
                        Location loc = bee.getLocation();
                        plugin.getLogger().info(String.format(Locale.ROOT,
                                "[ControlLoop] abrigo encontrado em (%.1f, %.1f, %.1f)",
                                loc.getX(), loc.getY(), loc.getZ()));
                    }
                } else {
                    // F7/AD-17 — bugs 6 e 7 (ver docstring de
                    // SHELTER_ABANDON_TICKS): cresce sem teto enquanto não
                    // toca nada, pra alimentar as DUAS janelas separadas
                    // abaixo (curta pra decisão de voo, longa pra desistir
                    // de abrigo já achado) — antes tinha um teto em
                    // LANDED_GRACE_TICKS que impedia alcançar o limiar maior.
                    ticksSinceGroundOrWaterContact++;
                    if (ticksSinceGroundOrWaterContact >= SHELTER_ABANDON_TICKS && shelterFoundThisEpisode) {
                        shelterFoundThisEpisode = false;
                    }
                }
                boolean landed = ticksSinceGroundOrWaterContact < LANDED_GRACE_TICKS;
                // F7/AD-17 — bug 7: `sheltered` NÃO depende de `landed` aqui
                // (janela curta demais, flicker normal derrubava abrigo
                // genuíno) — confia na trava, que só se desfaz sozinha após
                // SHELTER_ABANDON_TICKS de ausência sustentada (bem mais
                // tolerante). MotorMapping.toVelocity para só com
                // `sheltered` (voltou a checar só isso, sem exigir `landed`
                // no mesmo instante).
                boolean sheltered = shelterFoundThisEpisode;

                // F7/AD-17 — busca guiada (23/09/2026, pedido do usuário):
                // só sonda quando ainda procurando (senão gasta lookup à
                // toa) — ver docstring de MotorMapping.toVelocity.
                Vector shelterDirectionHint = (shelterActiveNow && !sheltered)
                        ? ShelterSensor.findNearbyShelterDirection(bee.getLocation(), SHELTER_LOOK_AHEAD_BLOCKS)
                        : null;

                // F9 — usa a trava temporal já computada na thread principal
                // (escapeLatched/escapeLatchDirection, ver onTick) — nunca
                // chamar Bukkit API aqui dentro (thread errada, bug real
                // corrigido 25/09/2026) nem reagir só ao estado bruto desta
                // troca (sinal cai rápido demais pra dar pra ver a fuga,
                // pedido do usuário).
                Vector escapeDirectionHint = escapeLatched ? escapeLatchDirection : null;

                // F10 — pedido do usuário: vetor CRU (não normalizado) da
                // abelha até a fonte de comida, recalculado a cada troca
                // com a posição ATUAL dela (bee.getLocation() aqui dentro é
                // seguro, mesmo padrão já usado por bee.isOnGround() logo
                // abaixo — só getNearbyEntities/varredura de bloco exigem
                // thread principal, não getLocation() de uma entidade já
                // conhecida). nearestFoodTarget já foi computado na thread
                // principal (ver onTick) — usa só a localização congelada
                // dali, nunca chama TasteSensor de novo aqui.
                Vector tasteDisplacementHint = nearestFoodTarget != null
                        ? nearestFoodTarget.location().toVector().subtract(bee.getLocation().toVector())
                        : null;

                latestVelocity = MotorMapping.toVelocity(
                        responseForMotor, newHeading, landed, sheltered, hasTouchedThisEpisode,
                        escapeLatched,
                        shelterDirectionHint, escapeDirectionHint, tasteDisplacementHint,
                        nearestFoodTarget != null && nearestFoodTarget.heldByPlayer());
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
                JsonObject johnstonMotor = response.getAsJsonObject("johnston_motor");
                if (johnstonMotor != null) {
                    latestJohnstonMotor = johnstonMotor;
                }
                JsonObject escapeMotor = response.getAsJsonObject("escape_motor");
                if (escapeMotor != null) {
                    latestEscapeMotor = escapeMotor;
                }
                JsonObject tasteMotor = response.getAsJsonObject("taste_motor");
                if (tasteMotor != null) {
                    latestTasteMotor = tasteMotor;
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
     * F11 (26/09/2026, pedido do usuário) — reação à fome: com energia baixa
     * ({@link EnergyTracker#hungerFactor}) a abelha voa mais DEVAGAR
     * (horizontal reduzido até {@link EnergyTracker#MIN_SPEED_FRACTION}) e
     * mais BAIXO (teto de altura acima do chão desce de
     * {@code STARVING_CEILING + FED_CEILING_EXTRA} até
     * {@link EnergyTracker#STARVING_CEILING_BLOCKS}). Aplicado só no fim,
     * sobre a velocidade já decidida pelos circuitos — não muda o que o
     * circuito calcula (RN-08), é fadiga física de embodiment, mesmo
     * status do sistema de recuperação. Fuga e empurrões mecânicos ficam de
     * fora (ver chamada). Roda na thread principal (rayTraceBlocks). Sem
     * chão a até 16 blocos abaixo, só a redução de velocidade vale.
     */
    private Vector applyHungerModifiers(Bee bee, Vector velocity) {
        double hunger = energyTracker.hungerFactor();
        if (hunger <= 0.0) {
            return velocity;
        }
        Vector adjusted = velocity.clone();
        double speedFraction = 1.0 - hunger * (1.0 - EnergyTracker.MIN_SPEED_FRACTION);
        adjusted.setX(adjusted.getX() * speedFraction);
        adjusted.setZ(adjusted.getZ() * speedFraction);

        double ceiling = EnergyTracker.STARVING_CEILING_BLOCKS
                + (1.0 - hunger) * EnergyTracker.FED_CEILING_EXTRA_BLOCKS;
        Location location = bee.getLocation();
        RayTraceResult ground = bee.getWorld().rayTraceBlocks(
                location, new Vector(0, -1, 0), 16.0, FluidCollisionMode.NEVER, true);
        if (ground != null) {
            double height = location.getY() - ground.getHitPosition().getY();
            if (height > ceiling) {
                adjusted.setY(Math.min(adjusted.getY(), -EnergyTracker.HUNGRY_DESCENT_BLOCKS_PER_TICK));
            } else if (adjusted.getY() > 0) {
                adjusted.setY(Math.min(adjusted.getY(), (ceiling - height) * 0.2));
            }
        }
        return adjusted;
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

    /**
     * F9 — direção horizontal (unitária, Y=0) aleatória, usada pela
     * recuperação mecânica a partir da 2ª falha consecutiva no mesmo
     * travamento (ver docstring de {@code STUCK_RECOVERY_RNG}) — explora em
     * vez de repetir uma heurística que já provou estar errada pra este
     * obstáculo específico.
     */
    private static Vector randomHorizontalDirection() {
        double angle = STUCK_RECOVERY_RNG.nextDouble() * 2 * Math.PI;
        return new Vector(Math.cos(angle), 0, Math.sin(angle));
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
