package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.util.Vector;

/**
 * Traduz o vetor motor da ponte em velocidade aplicável à abelha.
 *
 * <p>Magnitude de avanço usa {@code phototaxis} — direção real (sinal), vem
 * da topologia de sinal validada estatisticamente por RN-09 (F1) e
 * confirmada pelo experimento de lesão da F4 (Mann-Whitney p=0,0014). Ver
 * `docs/04-regras-de-negocio.md`.
 *
 * <p><b>Histórico (16/09/2026, revertido):</b> tentamos somar
 * {@code locomotion_drive} (RN-08/AD-14 — 6 tipos com categoria "Fast"/
 * "Broad Locomotion" publicada por Namiki et al. 2018) na velocidade. O
 * experimento de lesão re-rodado deu nulo (p=0,43, N=20) — e continuou nulo
 * mesmo controlando um confundidor real (abelha tinha spawnado dentro de
 * casa; refeito ao ar livre com céu visível: p=0,27, ainda nulo). Isso
 * descarta o confundidor e aponta pra causa real: `locomotion_drive` vem de
 * neurônios que não respondem ao estímulo de luz (foram escolhidos só por
 * categoria comportamental publicada, não por topologia de sinal) — somar
 * esse canal dilui o sinal real que `phototaxis` carregava sozinho. Mesma
 * armadilha de "agregar cancela o efeito" que já apareceu em RN-09 (F1) e no
 * primeiro experimento de lesão (F4). Não ficamos ajustando peso até achar
 * p&lt;0,05 de novo — isso seria manipular o resultado. Revertido pro que já
 * estava validado. `locomotion_drive` continua exposto em
 * `motor.py::decode()` para visualização/exploração (F5: `/flywirebee
 * mute|stimulate`), só não entra mais aqui. Ver `docs/04-regras-de-negocio.md`
 * (RN-08) para o relato completo.
 *
 * <p><b>Guinada (F6/AD-16, 17/09/2026, EM VALIDAÇÃO):</b> {@code yaw_steering}
 * rotaciona a direção de avanço em torno do eixo Y, proporcional ao valor do
 * canal — {@code rotateAroundY} do Bukkit, ângulo em radianos.
 * {@code MAX_YAW_RADIANS_PER_TICK} é um valor provisório, não calibrado
 * contra nada (diferente de {@code MAX_SPEED_BLOCKS_PER_TICK}, que veio do
 * spike técnico da F4). <b>O sentido do sinal (yaw positivo → gira pra qual
 * lado do mundo) não está validado ainda</b> — é exatamente o que
 * `SteeringValidationExperiment` testa. Não afirmar "esquerda" ou "direita"
 * a partir do sinal do canal até esse experimento rodar. Ver
 * `docs/04-regras-de-negocio.md` RN-08.
 *
 * <p><b>Bug encontrado e corrigido na primeira tentativa (17/09/2026):</b> a
 * rotação não acumulava porque {@code ControlLoop} recapturava
 * {@code bee.getLocation().getDirection()} — a orientação REAL da abelha,
 * controlada pela IA nativa — a cada troca, em vez de reaproveitar a direção
 * já rotacionada da troca anterior. `net_turn_rad` medido saiu quase sempre
 * exatamente 0,0 (giro nunca compõe). Corrigido separando {@link
 * #rotatedHeading}, que `ControlLoop` chama guardando o resultado como
 * estado persistente (campo {@code heading}, não relido da abelha a cada
 * tick).
 *
 * <p>Ainda sem controle de altura (lift) — RN-08 completa segue parcial.
 *
 * <p>Escala de velocidade (`MAX_SPEED_BLOCKS_PER_TICK`) usa o mesmo valor
 * validado no spike técnico da F4 (0,3 blocos/tick ≈ 96% de eficiência com
 * IA ligada — ver plugin/README.md).
 *
 * <p><b>Grooming (F7/AD-17, 20/09/2026, PRIMEIRA VEZ QUE O BRISTLE CONTROLA A
 * ABELHA — decisão do usuário).</b> Quando {@code bristle_motor.grooming}
 * (único canal do subcircuito `bristle` com comportamento PUBLICADO, RN-08 —
 * ver `bristle_motor.py`) passa de {@link #GROOMING_THRESHOLD}, a abelha
 * PARA de responder a {@code phototaxis} e desce (velocidade vertical
 * negativa fixa, zero horizontal) até tocar o chão; uma vez no chão, fica
 * parada — "pousa numa superfície próxima e fica se limpando". Sem esse
 * canal ativo, comportamento idêntico a antes desta mudança.
 *
 * <p><b>Nada disto foi validado por lesão em servidor real ainda</b> — é a
 * PRIMEIRA vez que qualquer canal do `bristle` produz efeito observável na
 * abelha (antes era só telemetria, RN-09/RN-08 validaram o circuito
 * isoladamente). {@code GROOMING_THRESHOLD} e
 * {@code LANDING_DESCENT_BLOCKS_PER_TICK} são estimativas de engenharia,
 * não calibradas — mesma disciplina de {@code MAX_YAW_RADIANS_PER_TICK}
 * quando foi introduzido. O experimento de lesão real (comparar abelha com
 * toque real vs. mascarado, medindo se ela realmente pousa mais/menos) é o
 * próximo passo, não feito aqui.
 *
 * <p><b>Buscar abrigo (F7/AD-17, 21/09/2026, PRIMEIRA VEZ QUE O HYGRO
 * CONTROLA A ABELHA — decisão do usuário).</b> Quando
 * {@code hygro_motor.hygrotaxis} (único canal do `hygro`, topologia de
 * sinal — ver `hygro_motor.py`) passa de {@link #HYGROTAXIS_THRESHOLD}, a
 * abelha busca abrigo — **deliberadamente diferente do pouso calmo do
 * `grooming`**: usuário observou que, na vida real, um inseto voaria MAIS
 * RÁPIDO até um abrigo quando começa a chover, não devagar. Enquanto no ar,
 * voa em velocidade máxima ({@code MAX_SPEED_BLOCKS_PER_TICK}) na direção
 * comandada enquanto mergulha pro chão ({@link
 * #SHELTER_DIVE_DESCENT_BLOCKS_PER_TICK}, mais rápido que
 * {@code LANDING_DESCENT_BLOCKS_PER_TICK}); ao tocar o chão, para — mesmo
 * estado final do `grooming`, caminho até lá diferente. Sem lesão validando
 * ainda; {@code HYGROTAXIS_THRESHOLD} tem margem bem mais folgada que a do
 * `GROOMING_THRESHOLD` original — baseline medido (calibração + servidor
 * real, 21/09/2026) oscila entre -0,44 e +0,5, chuva sustentada satura em
 * 0,986-0,994.
 *
 * <p><b>Dois bugs reais, mesmo teste em servidor real (21/09/2026) — abelha
 * morreu afogada DUAS vezes seguidas.</b> Mesma categoria de achado do
 * "obstáculo lateral" da F7: mecanismo pensado só pra um cenário (chão
 * sólido, leitura estável) não cobria outro. Ver docstring do parâmetro
 * {@code landed} de {@link #toVelocity} pro relato completo dos dois — o
 * segundo só apareceu depois de corrigir o primeiro. Corrigidos antes de
 * qualquer nova lesão — não afirmar resultado sem consertar o bug primeiro.
 */
public final class MotorMapping {

    private static final double MAX_SPEED_BLOCKS_PER_TICK = 0.3;
    // Provisório — ver docstring da classe. yaw_steering=1,0 sustentado por
    // 10s (200 ticks) gira até ~2 rad (~115°) no total.
    private static final double MAX_YAW_RADIANS_PER_TICK = 0.01;

    // F7/AD-17 — recalibrado (20/09/2026) depois do primeiro
    // /flywirebee touchlesion dar nulo (p=0,33). Medido isolado (sem
    // Minecraft, tools/grooming_baseline_check.py-style, Engine direto):
    // SEM estímulo nenhum, grooming já fica em média 0,41-0,44 e passa de
    // 0,5 em ~25-27% das amostras só de ruído/atividade espontânea (RN-09)
    // — bate com o 24% medido no log real durante trials mascarados do
    // experimento de lesão. COM estímulo sustentado, satura em ~0,998,
    // 100% das amostras acima de 0,5. 0,5 era baixo demais pra separar
    // ruído de sinal; 0,8 fica bem acima do pico de ruído medido (0,71) e
    // bem abaixo da saturação real (0,998). Ainda não validado com um novo
    // /flywirebee touchlesion depois da mudança.
    private static final double GROOMING_THRESHOLD = 0.8;
    // Descida vertical enquanto GROOMING_THRESHOLD é ultrapassado, até
    // tocar o chão. PROVISÓRIO — nunca testado em servidor real.
    private static final double LANDING_DESCENT_BLOCKS_PER_TICK = 0.1;

    // F7/AD-17 — margem já observada (calibração + servidor real,
    // 21/09/2026, ver docstring da classe) é bem maior que a do grooming
    // original: baseline até ~0,5, chuva satura acima de 0,98. 0,8 fica
    // longe dos dois lados, sem precisar de recalibração posterior como
    // aconteceu com GROOMING_THRESHOLD.
    private static final double HYGROTAXIS_THRESHOLD = 0.8;
    // Descida vertical enquanto buscando abrigo — mais rápida que o pouso
    // calmo do grooming (mesma escala de MAX_SPEED_BLOCKS_PER_TICK, "voando
    // rápido pra fugir da chuva"). PROVISÓRIO — nunca testado em servidor real.
    private static final double SHELTER_DIVE_DESCENT_BLOCKS_PER_TICK = 0.3;
    // F7/AD-17 — bug 4 real (22/09/2026): buscar sem cobertura com
    // velocidade vertical zero deixava a abelha "andando" no chão (física
    // de atrito bem mais forte que a de voo do Minecraft), quase sem
    // deslocamento real — o sistema de recuperação de obstáculo (ainda
    // ativo enquanto procura, ver ControlLoop) achava que ela tinha
    // travado, dava um empurrão pra cima, e a busca reativava o mergulho
    // usando `heading` — que gira devagar (yaw_steering) e ainda apontava
    // quase pro mesmo lugar, trazendo ela de volta perto de onde começou,
    // repetidamente. Corrigido dando uma subida leve constante enquanto
    // procura sem abrigo — mantém ela em física de VOO (bem menos atrito)
    // em vez de física de andar, evita a interferência do sistema de
    // recuperação, e a leva a perder contato com o chão periodicamente
    // (reativando o mergulho em direções ligeiramente diferentes a cada
    // ciclo, conforme `heading` gira) em vez de ficar arrastando no mesmo
    // lugar. PROVISÓRIO, engenharia — não é busca de caminho de verdade.
    //
    // Recalibrado (22/09/2026) de 0,08 pra 0,15 — usuário testou e viu ela
    // "trancar" tentando subir degraus de terreno (elevação de 1 bloco):
    // subia um pouco, caía de volta, repetia — 0,08/tick não ganhava
    // altura rápido o bastante pra vencer um degrau antes de perder o
    // impulso. 0,15 é a mesma magnitude já usada (e testada) em
    // RECOVERY_BOOST_BLOCKS_PER_TICK pra "escalar" obstáculo lateral —
    // reaproveita uma escala que já se mostrou suficiente nesse mesmo tipo
    // de situação, não um número novo arbitrário.
    private static final double SEARCH_HOVER_BLOCKS_PER_TICK = 0.15;

    private MotorMapping() {
    }

    /**
     * Rotaciona a direção comandada por {@code yaw_steering}. Quem chama
     * (`ControlLoop`) guarda o resultado e passa de volta na próxima troca —
     * NUNCA derivar de {@code bee.getLocation().getDirection()} a cada vez,
     * isso é o bug já corrigido (ver docstring da classe).
     */
    public static Vector rotatedHeading(JsonObject bridgeResponse, Vector previousHeading) {
        JsonObject motor = bridgeResponse.getAsJsonObject("motor");
        double yawSteering = (motor != null && motor.has("yaw_steering"))
                ? motor.get("yaw_steering").getAsDouble() : 0.0;
        return previousHeading.clone().rotateAroundY(yawSteering * MAX_YAW_RADIANS_PER_TICK).normalize();
    }

    /**
     * @param landed decisão JÁ ESTABILIZADA de "chegou, pode parar de
     *     descer/mergulhar" — {@code MotorMapping} não toca na API do
     *     Bukkit diretamente nem guarda estado entre ticks, quem chama
     *     (`ControlLoop`) fornece isso pronto. **Não é só
     *     {@code bee.isOnGround() || bee.isInWater()} lido no instante** —
     *     ver o porquê abaixo.
     *
     *     <p><b>Bug 1, real, servidor real (21/09/2026):</b> checagem
     *     original usava só {@code onGround}; mergulho sobre um lago nunca
     *     parava (água não conta pra {@code onGround}) até a abelha morrer
     *     afogada. Corrigido incluindo {@code isInWater()}.
     *
     *     <p><b>Bug 2, real, servidor real (21/09/2026), no MESMO teste do
     *     fix do Bug 1:</b> incluir {@code isInWater()} não bastou —
     *     parada na água, a abelha começou a "tiquetaquear": virava rápido e
     *     tentava mergulhar de novo, repetidamente, até morrer de novo.
     *     Causa: {@code isInWater()} não é estável tick a tick na superfície
     *     da água (física de boiar do próprio jogo, mais o fato de ser lido
     *     numa thread diferente da que move a abelha) — cada vez que lia
     *     "saiu da água" por UM tick, o mergulho de velocidade máxima
     *     reativava na direção de {@code heading} atual, que continua
     *     girando sozinha por causa do `yaw_steering` do ocelar (circuito
     *     independente, nunca para). Isso parecia decisão nova a cada vez;
     *     era só o estado piscando. **Corrigido transferindo a decisão pro
     *     chamador**, que absorve o flicker numa janela curta
     *     (`ticksSinceGroundOrWaterContact`/`LANDED_GRACE_TICKS`, ver
     *     `ControlLoop`) em vez de reagir a uma leitura de um tick só.
     *
     *     <p><b>Bug 3, real, servidor real (22/09/2026), no fix de "buscar
     *     até achar abrigo":</b> a primeira versão desta janela era uma
     *     trava PERMANENTE (uma vez tocando chão/água, ficava `landed=true`
     *     pra sempre até o canal desativar) — resolvia o Bug 2, mas
     *     quebrava a busca: se ela tocasse de raspão (ou fosse empurrada
     *     pelo sistema de recuperação de obstáculo) e voltasse pro ar,
     *     {@code landed} continuava `true`, e ela nunca mais mergulhava —
     *     só deslizava na horizontal pro resto do episódio, mesmo bem no
     *     ar. Corrigido: a janela agora é CURTA (alguns ticks depois do
     *     último toque confirmado), não permanente — absorve flicker sem
     *     perder decolagem de verdade.
     * @param sheltered só usado pela busca de abrigo (`hygro`), ignorado por
     *     `grooming`: decisão (também JÁ ESTABILIZADA pelo chamador, ver
     *     {@code ControlLoop}) de que a abelha está debaixo de um teto de
     *     verdade ({@link ShelterSensor#hasShelterAbove}), não só tocou
     *     chão/água em qualquer lugar a céu aberto — decisão do usuário
     *     (21/09/2026): abrigo de verdade tem bloco sólido acima. Enquanto
     *     {@code landed} é true mas {@code sheltered} é false, a abelha
     *     continua se deslocando (não mergulha de novo — evita repetir os
     *     dois bugs acima) até achar um lugar coberto.
     */
    public static Vector toVelocity(
            JsonObject bridgeResponse, Vector heading, boolean landed, boolean sheltered
    ) {
        if (isGroomingActive(bridgeResponse.getAsJsonObject("bristle_motor"))) {
            // F7/AD-17 — grooming vence phototaxis: para de avançar, desce até
            // pousar, fica parada uma vez no chão (ou na água, ver docstring).
            // Sem conceito de "abrigo" aqui — qualquer chão/água serve.
            return landed ? new Vector(0, 0, 0) : new Vector(0, -LANDING_DESCENT_BLOCKS_PER_TICK, 0);
        }
        if (isSeekingShelterActive(bridgeResponse.getAsJsonObject("hygro_motor"))) {
            // F7/AD-17 — busca abrigo: voa RÁPIDO (velocidade máxima) até
            // achar um lugar com teto de verdade (ver docstring do parâmetro
            // sheltered) — diferente do pouso calmo do grooming.
            if (sheltered) {
                return new Vector(0, 0, 0); // abrigo de verdade — para
            }
            if (landed) {
                // Já tocou chão/água, mas sem cobertura — continua se
                // deslocando pra procurar em outro lugar. Subida leve
                // constante (não mais Y=0) — ver docstring de
                // SEARCH_HOVER_BLOCKS_PER_TICK (bug 4): mantém física de
                // voo, evita o atrito de "andar" e a interferência do
                // sistema de recuperação de obstáculo.
                Vector search = heading.clone().multiply(MAX_SPEED_BLOCKS_PER_TICK);
                search.setY(SEARCH_HOVER_BLOCKS_PER_TICK);
                return search;
            }
            // Ainda no ar — mergulha até tocar em algo pela primeira vez.
            Vector dive = heading.clone().multiply(MAX_SPEED_BLOCKS_PER_TICK);
            dive.setY(-SHELTER_DIVE_DESCENT_BLOCKS_PER_TICK);
            return dive;
        }

        JsonObject motor = bridgeResponse.getAsJsonObject("motor");
        if (motor == null || !motor.has("phototaxis")) {
            return new Vector(0, 0, 0);
        }

        double phototaxis = motor.get("phototaxis").getAsDouble(); // em (-1, 1)
        double activity = clamp((phototaxis + 1.0) / 2.0, 0.0, 1.0); // reescala p/ [0,1]

        return heading.clone().multiply(activity * MAX_SPEED_BLOCKS_PER_TICK);
    }

    /**
     * F7/AD-17 — bug encontrado em servidor real (20/09/2026): {@link ControlLoop}
     * usa isto pra decidir se pausa a detecção de colisão ({@code TouchSensor}).
     * Sem essa pausa, o PRÓPRIO pouso vira um loop auto-sustentado: parar em
     * cima de um bloco esbarra na física ao tentar micro-mover, isso conta
     * como {@code touch_contact}, que realimenta {@code grooming}, que a
     * mantém parada — ela nunca mais decola (confirmado: abelha presa na copa
     * de uma árvore por minutos, `grooming` saturado em 0,998-0,999 o tempo
     * todo). Exposto público (não `private`) porque tanto {@code toVelocity}
     * quanto {@code ControlLoop} (com o `bristle_motor` já em mãos de uma
     * troca anterior) precisam da mesma resposta, mesmo limiar.
     */
    public static boolean isGroomingActive(JsonObject bristleMotor) {
        if (bristleMotor == null || !bristleMotor.has("grooming")) {
            return false; // sem Engine do bristle rodando — sem efeito, comportamento antigo
        }
        return bristleMotor.get("grooming").getAsDouble() > GROOMING_THRESHOLD;
    }

    /**
     * F7/AD-17 — mesmo motivo de {@link #isGroomingActive}: exposto público
     * porque {@code ControlLoop} também usa (pro gate de "pouso intencional"
     * do sistema de recuperação mecânica de obstáculo — sem isso, buscar
     * abrigo de propósito seria confundido com estar preso).
     */
    public static boolean isSeekingShelterActive(JsonObject hygroMotor) {
        if (hygroMotor == null || !hygroMotor.has("hygrotaxis")) {
            return false; // sem Engine do hygro rodando — sem efeito, comportamento antigo
        }
        return hygroMotor.get("hygrotaxis").getAsDouble() > HYGROTAXIS_THRESHOLD;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
