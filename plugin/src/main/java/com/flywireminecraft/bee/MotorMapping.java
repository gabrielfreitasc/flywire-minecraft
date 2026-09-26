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
 *
 * <p><b>Fuga por looming (F9/AD-20, 24/09/2026, PRIORIDADE MÁXIMA — decisão
 * do usuário).</b> Quando {@code escape_motor.escape_drive} (único canal do
 * `escape`, curado por identidade celular — DNp01/Giant Fiber + DNp02, ver
 * `escape_motor.py`) passa de {@link #ESCAPE_THRESHOLD}, a abelha ignora
 * TUDO (hygro/grooming/phototaxis) e voa pra longe da ameaça que disparou o
 * {@link LoomingSensor}, numa velocidade MAIOR que o voo normal — decisão
 * do usuário, coerente com a biologia real (Giant Fiber dispara
 * salto+voo de fuga, não um passeio calmo). Direção vem de
 * {@link LoomingSensor#fleeDirectionAwayFromNearestThreat} (a mesma ameaça
 * mais próxima que fez o sensor disparar), com um leve componente vertical
 * pra cima (subir também ajuda a escapar de ameaça terrestre). Sem hint
 * disponível (ameaça saiu do raio entre a leitura do sensor e a troca
 * responder), cai pra {@code heading} — mesma convenção do hint de abrigo.
 *
 * <p><b>Trava temporal (25/09/2026, pedido do usuário).</b> {@code
 * escapeActive}/{@code escapeDirectionHint} aqui já vêm com a decisão
 * ESTABILIZADA pelo {@code ControlLoop} ({@code ESCAPE_LATCH_TICKS}, ~2,5s)
 * — {@code MotorMapping} não checa mais {@code escape_motor} cru nem
 * conhece {@link #ESCAPE_THRESHOLD} pra essa decisão (o limiar só é usado
 * pelo chamador, pra armar/recarregar a trava). Sem isso, um sinal que cai
 * rápido (a ameaça sai do raio, ou a distância para de fechar) faria a fuga
 * durar menos que uma troca — tempo curto demais pra observar visualmente.
 *
 * <p><b>Nada disto foi validado por lesão em servidor real ainda</b> —
 * mesma disciplina de grooming/hygro quando entraram: {@code
 * ESCAPE_SPEED_BLOCKS_PER_TICK}/{@code ESCAPE_VERTICAL_BOOST_BLOCKS_PER_TICK}
 * são estimativas de engenharia, não calibradas. {@code ESCAPE_THRESHOLD}
 * já foi recalibrado (25/09/2026) contra a distribuição real de baseline vs.
 * estimulado (ver docstring da constante) — achado real em servidor real:
 * o valor original (0,8) deixava a abelha "fugindo" o tempo todo mesmo sem
 * ameaça nenhuma. O experimento de lesão (mascarar {@code looming_threat},
 * medir se ela realmente foge menos) é o próximo passo, não feito aqui.
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
    // F9 — componente horizontal durante a descida do grooming (ver
    // docstring do branch em toVelocity) — mesma ordem de grandeza de
    // SEARCH_HOVER_BLOCKS_PER_TICK (achado real: precisa de "físico de
    // voo" suficiente pra não ficar mecanicamente presa). PROVISÓRIO, não
    // calibrado.
    private static final double GROOMING_DESCENT_HORIZONTAL_BLOCKS_PER_TICK = 0.15;

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

    // F7/AD-17 — polimento (22/09/2026), pedido do usuário: o ciclo
    // planeio→mergulho (bug 4) fica visualmente "pulando" mesmo em terreno
    // plano, porque toda vez que ela perde contato durante a busca, a
    // re-descida reativa o MESMO mergulho íngreme
    // (SHELTER_DIVE_DESCENT_BLOCKS_PER_TICK=0,3) usado pro primeiro
    // mergulho urgente quando a chuva começa. Separado em dois casos: a
    // primeira descida do episódio (ainda no ar, nunca tocou nada) continua
    // rápida — é a urgência real que o usuário pediu ("voaria com mais
    // velocidade até um abrigo"). Re-descidas DURANTE a busca (já tocou
    // pelo menos uma vez, voltou pro ar via SEARCH_HOVER) usam este valor
    // bem mais suave — ela já está perto do chão, não precisa "mergulhar"
    // de novo, só descer um pouco. Reduz a amplitude do ciclo sem mudar o
    // mecanismo. PROVISÓRIO, não testado em servidor real ainda.
    private static final double SEARCH_REDESCENT_BLOCKS_PER_TICK = 0.05;

    // F9 — recalibrado (25/09/2026), bug real achado em servidor real: 0,8
    // (valor original, "por consistência" com os outros limiares) deu
    // escape_drive oscilando 0,58-0,97 com looming=false o tempo todo — a
    // abelha entrava em modo fuga sem ameaça nenhuma, atropelando qualquer
    // teste de grooming/hygro. Causa raiz não era o limiar, era a ESCALA:
    // grupo de só 4 neurônios (DNp01+DNp02) satura o tanh genérico mesmo em
    // repouso (ver ESCAPE_MOTOR_RATE_SCALE em config.py — corrigido lá).
    // Medido isolado com a escala nova: baseline tanh média=0,249
    // (p95=0,351), estimulado=0,979 — gap de quase 0,63. 0,6 fica bem acima
    // do p95 de baseline e bem abaixo da saturação estimulada, mesma
    // disciplina que recalibrou GROOMING_THRESHOLD (0,5→0,8) na F7.
    private static final double ESCAPE_THRESHOLD = 0.6;
    // Mais rápido que MAX_SPEED_BLOCKS_PER_TICK (que já é chamado de
    // "velocidade máxima" em phototaxis/busca de abrigo) — decisão explícita
    // do usuário: fuga de ameaça real deve ser mais urgente que qualquer
    // outro voo. 1,5x MAX_SPEED_BLOCKS_PER_TICK, estimativa de engenharia,
    // não calibrada.
    private static final double ESCAPE_SPEED_BLOCKS_PER_TICK = 0.45;
    // Componente vertical leve durante a fuga — subir também afasta de
    // ameaça terrestre. PROVISÓRIO, não calibrado.
    private static final double ESCAPE_VERTICAL_BOOST_BLOCKS_PER_TICK = 0.2;

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
     * @param hasTouchedThisEpisode só usado pela busca de abrigo: já tocou
     *     chão/água pelo menos uma vez neste episódio (JÁ ESTABILIZADO pelo
     *     chamador, ver {@code ControlLoop}), mesmo que {@code landed} tenha
     *     voltado a `false` (ela subiu de novo via planeio, ver
     *     {@code SEARCH_HOVER_BLOCKS_PER_TICK}). Diferencia o primeiro
     *     mergulho do episódio (rápido, {@code SHELTER_DIVE_DESCENT_
     *     BLOCKS_PER_TICK}) das re-descidas durante a busca (suaves,
     *     {@code SEARCH_REDESCENT_BLOCKS_PER_TICK}) — polimento pedido pelo
     *     usuário, ver docstring da constante.
     * @param shelterDirectionHint só usado pela busca de abrigo: direção
     *     horizontal (unitária) pra um abrigo detectado por perto
     *     ({@link ShelterSensor#findNearbyShelterDirection}), ou
     *     {@code null} se nada foi detectado nas proximidades. Quando
     *     presente, SUBSTITUI {@code heading} nos movimentos de busca/
     *     mergulho — mira direto na cobertura conhecida em vez de seguir a
     *     direção do circuito ocelar (`yaw_steering`), que não tem relação
     *     nenhuma com onde está o abrigo. Pedido do usuário (23/09/2026),
     *     depois de testar um cubo pequeno com abrigo num canto: sem isto,
     *     ela só vagava sem se aproximar.
     * @param escapeActive decisão JÁ ESTABILIZADA pelo chamador (ver
     *     {@code ControlLoop}) de que a fuga por looming deve controlar
     *     agora — NÃO é {@code isEscapeActive(bridgeResponse...)} lido cru
     *     desta troca: {@code escape_drive}/{@code looming_threat} são
     *     sinais de nível que podem cair rápido (a ameaça sai do raio de
     *     busca, a distância para de fechar), e sem essa estabilização a
     *     fuga podia durar só uma troca, tempo curto demais pra observar
     *     visualmente (pedido do usuário, 25/09/2026). O chamador garante
     *     um mínimo de tempo de fuga (`ESCAPE_LATCH_TICKS`) mesmo depois do
     *     circuito real cair abaixo do limiar. Mesma disciplina de
     *     {@code landed}/{@code sheltered} acima — {@code MotorMapping} não
     *     guarda estado entre ticks, só aplica o que já vier pronto.
     * @param escapeDirectionHint só usado pela fuga por looming: direção
     *     horizontal (unitária) PRA LONGE da ameaça mais próxima
     *     ({@link LoomingSensor#fleeDirectionAwayFromNearestThreat}),
     *     capturada pelo chamador no início/recarga da trava (não
     *     recalculada a cada tick da fuga) — {@code null} só se nunca
     *     nenhuma ameaça esteve no raio de busca. Mesma convenção de
     *     {@code shelterDirectionHint}: substitui {@code heading} quando
     *     presente, cai pra {@code heading} quando não.
     */
    public static Vector toVelocity(
            JsonObject bridgeResponse, Vector heading, boolean landed, boolean sheltered,
            boolean hasTouchedThisEpisode, boolean escapeActive, Vector shelterDirectionHint,
            Vector escapeDirectionHint
    ) {
        if (escapeActive) {
            // F9/AD-20 — prioridade máxima, ignora tudo mais (ver docstring
            // da classe). Foge na direção oposta à ameaça, ou heading se a
            // ameaça já saiu do raio de busca entre a leitura do sensor e a
            // resposta da ponte chegar.
            Vector fleeDirection = escapeDirectionHint != null ? escapeDirectionHint : heading;
            Vector escapeVelocity = fleeDirection.clone().multiply(ESCAPE_SPEED_BLOCKS_PER_TICK);
            escapeVelocity.setY(ESCAPE_VERTICAL_BOOST_BLOCKS_PER_TICK);
            return escapeVelocity;
        }
        if (isSeekingShelterActive(bridgeResponse.getAsJsonObject("hygro_motor"))) {
            // F7/AD-17 — busca abrigo VENCE grooming quando os dois estão
            // ativos ao mesmo tempo (decisão do usuário, 22/09/2026, achado
            // em jogo livre: abelha parada e estática com chuva + mob perto
            // — touch_proximity do mob subia grooming, que tinha prioridade
            // e mascarava a busca de abrigo por completo). Fugir da chuva é
            // mais urgente/vital que parar pra se limpar por um mob de
            // passagem — inverteu a ordem de checagem em relação à versão
            // original (que checava grooming primeiro, sem essa noção de
            // urgência relativa). Voa RÁPIDO (velocidade máxima) até achar
            // um lugar com teto de verdade (ver docstring do parâmetro
            // sheltered) — diferente do pouso calmo do grooming.
            if (sheltered) {
                // F7/AD-17 — bug 6 real (22/09/2026): abelha flutuando
                // parada no ar longe de cobertura, porque `sheltered`
                // travava pra sempre. Bug 7 (mesmo dia): exigir `landed`
                // aqui TAMBÉM pra parar quebrou o caso normal — flicker de
                // onGround numa abelha genuinamente descansando (mesma
                // instabilidade do bug 2) já bastava pra `landed` piscar
                // falso e tirá-la do abrigo de novo. Resolvido nos DOIS
                // bugs do lado de `ControlLoop` agora (ver
                // SHELTER_ABANDON_TICKS): a trava de `sheltered` só se
                // desfaz sozinha depois de ausência sustentada de verdade
                // (segundos, não meio segundo) — aqui volta a confiar só
                // nela, sem `landed` no meio.
                return new Vector(0, 0, 0); // abrigo de verdade — para
            }
            // F7/AD-17 — busca guiada: mira num abrigo detectado por perto
            // em vez de seguir heading (sem relação com onde está o abrigo)
            // — ver docstring do parâmetro shelterDirectionHint.
            Vector moveDirection = shelterDirectionHint != null ? shelterDirectionHint : heading;
            if (landed) {
                // Já tocou chão/água, mas sem cobertura — continua se
                // deslocando pra procurar em outro lugar. Subida leve
                // constante (não mais Y=0) — ver docstring de
                // SEARCH_HOVER_BLOCKS_PER_TICK (bug 4): mantém física de
                // voo, evita o atrito de "andar" e a interferência do
                // sistema de recuperação de obstáculo.
                Vector search = moveDirection.clone().multiply(MAX_SPEED_BLOCKS_PER_TICK);
                search.setY(SEARCH_HOVER_BLOCKS_PER_TICK);
                return search;
            }
            // Ainda no ar. Primeiro mergulho do episódio (nunca tocou nada
            // ainda): rápido, urgente — ver docstring da classe. Re-descida
            // durante a busca (já tocou antes, subiu de novo via
            // SEARCH_HOVER): bem mais suave — ver docstring de
            // SEARCH_REDESCENT_BLOCKS_PER_TICK, polimento pra reduzir o
            // "pulo" visual do ciclo planeio→mergulho.
            double descent = hasTouchedThisEpisode
                    ? SEARCH_REDESCENT_BLOCKS_PER_TICK
                    : SHELTER_DIVE_DESCENT_BLOCKS_PER_TICK;
            Vector dive = moveDirection.clone().multiply(MAX_SPEED_BLOCKS_PER_TICK);
            dive.setY(-descent);
            return dive;
        }
        if (isGroomingActive(bridgeResponse.getAsJsonObject("bristle_motor"))) {
            // F7/AD-17 — grooming vence phototaxis (mas não hygro, ver
            // acima): para de avançar, desce até pousar, fica parada uma vez
            // no chão (ou na água, ver docstring). Sem conceito de "abrigo"
            // aqui — qualquer chão/água serve.
            //
            // F9 — bug real, achado do usuário (25/09/2026): descida
            // ORIGINAL era puramente vertical (0,-DESCENT,0), sem componente
            // horizontal nenhum. Resquício de quando `grooming` foi
            // implementado (F7), ANTES de todos os bugs de "mergulho preso"
            // do `hygro` serem descobertos e corrigidos (ver docstring da
            // classe, seção "Buscar abrigo"). Confirmado em servidor real:
            // abelha numa área reclusa nunca alcançava `onGround` (log:
            // vel=0,-0.1,0 sustentado, onGround=false o tempo todo), e o
            // sistema de recuperação mecânica (ControlLoop) disparava
            // repetidamente (217 vezes numa sessão) sem nunca resolver —
            // ele empurra na direção OPOSTA a `heading`, que não tem
            // relação nenhuma com o que está bloqueando a descida vertical
            // pura; assim que o empurrão acaba, grooming volta a mandar
            // velocidade zero-horizontal, trava nas paredes de novo, loop
            // infinito ("voando pra parede", achado do usuário). Corrigido
            // dando um componente horizontal (`heading`, mesma direção que
            // phototaxis usaria — sem conceito de "abrigo" pra mirar,
            // diferente do hygro) durante a descida — ela desliza enquanto
            // desce em vez de cair reto, com folga pra sair de um canto
            // apertado sozinha.
            if (landed) {
                return new Vector(0, 0, 0);
            }
            Vector descending = heading.clone().multiply(GROOMING_DESCENT_HORIZONTAL_BLOCKS_PER_TICK);
            descending.setY(-LANDING_DESCENT_BLOCKS_PER_TICK);
            return descending;
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

    /**
     * F9/AD-20 — mesmo motivo de {@link #isGroomingActive}/
     * {@link #isSeekingShelterActive}: exposto público porque
     * {@code ControlLoop} também usa (pra saber se precisa computar
     * {@code escapeDirectionHint} antes de chamar {@link #toVelocity}).
     */
    public static boolean isEscapeActive(JsonObject escapeMotor) {
        if (escapeMotor == null || !escapeMotor.has("escape_drive")) {
            return false; // sem Engine do escape rodando — sem efeito, comportamento antigo
        }
        return escapeMotor.get("escape_drive").getAsDouble() > ESCAPE_THRESHOLD;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
