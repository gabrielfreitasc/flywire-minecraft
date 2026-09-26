# 03 — Roadmap e Fases

Cada fase tem **critério de saída verificável**. Não se avança por sensação de progresso.

---

## F0 — Fundação de dados ✅ concluída

Estabelecer a matéria-prima com proveniência rastreável.

- [x] Identificar fontes canônicas (Zenodo, Codex, GitHub)
- [x] Contornar bloqueio de egresso via espelho GitHub
- [x] **Validar espelho contra o artigo** — 54.492.922 sinapses, 2.700.513 conexões
- [x] Extrair subcircuito ocelar — 625 nós, 2.981 arestas
- [x] Registrar AD-01…AD-05

**Saída:** `nodes.csv` + `edges.csv` + `manifest.json` com checksums. ✅

---

## F1 — Núcleo de simulação ✅ concluída

O simulador roda headless, sem Minecraft, e produz disparos plausíveis.

- [x] `ingest.py` — L0→L1 com validação que aborta em divergência
- [x] `graph.py` — L1→L2, CSR + vetor de sinal + **override de histamina (RN-02)**
- [x] `neuron.py` — LIF com parâmetros de Shiu et al. (RN-07)
- [x] `engine.py` — laço de integração, dt=1 ms, + corrente tônica/ruído (RN-09)
- [ ] `telemetry.py` — gravação em DuckDB (⚠️ docstring do arquivo diz "Fase 2";
      roadmap original listava em F1 — reconciliado, tratando como F2)
- [x] Testes RN-01/02/03/04/05/07 (`test_business_rules.py`, 7/7 passam)

**Critério de saída:** estimular os 273 fotorreceptores produz efeito **estatisticamente
mensurável e distinguível de não estimular** sobre pelo menos um neurônio descendente.

**✅ Atingido — ver RN-09 em `04-regras-de-negocio.md`.** Caminho não foi direto: os 273
fotorreceptores são 100% inibitórios (RN-02); sem corrente de base o modelo não conseguia
disparar fora da camada sensorial (impossibilidade matemática, não de calibração).
Adicionamos `BIAS_CURRENT` + `NOISE_STD`. Uma primeira comparação pareada (30 sementes),
somando os 92 descendentes juntos, deu efeito nulo (p=0,44) — mas o problema era o
desenho do teste: a topologia tem 29 descendentes com caminho de sinal líquido
excitatório (desinibição de 2 saltos) e 63 com caminho líquido inibitório, que se
cancelam quando somados. Separando os grupos, o grupo inibitório mostra efeito
significativo (p=0,0028, N=30). Registrado com transparência — o teste errado deu
resultado nulo real, a correção veio de mapear a topologia, não de ajustar parâmetro até
aparecer número bonito (ver `CONVENCOES.md`).

**Risco original (confirmado e endereçado):** parâmetros do LIF herdados de Shiu et al.
foram ajustados para o cérebro inteiro; num subcircuito de 625 neurônios a excitação
recorrente é menor e a rede pode ficar silenciosa — confirmado, mas a causa raiz era mais
estrutural (RN-09, corrente de base) do que apenas ganho de entrada.

**Pendência para F2:** grupo de desinibição (29 descendentes) ainda sem significância
estatística (p=0,25, tendência na direção certa) — acompanhar ao construir `motor.py`.

---

## F2 — Contrato motor e telemetria ✅ concluída (com RN-08 provisório, ver abaixo)

Traduzir disparos em intenção, e conseguir analisar o que aconteceu.

- [x] Agrupar os 92 descendentes por função motora — **provisório**: por prefixo de
      `cell_type` (RN-08 segue sem curadoria por função real, só mecanismo implementado)
- [x] `motor.py` — taxa de disparo → vetor normalizado por tanh, janela deslizante
      (`MOTOR_WINDOW_MS`)
- [x] Esquema DuckDB: `runs`, `spikes`, `stimuli`, `motor_frames` (`telemetry.py`)
- [x] Consulta de exemplo cruzando spikes com `cell_type` (`telemetry.top_cell_types`)

**Critério de saída:** um run de 10 s gravado, e uma consulta SQL que responde "quais
tipos celulares mais dispararam sob estímulo X" sem script ad-hoc.

**✅ Atingido** — `sim/tools/run_demo.py` liga engine+motor+telemetry de ponta a ponta:
10.000 ms gravados, consulta retorna os `cell_type` mais ativos sob estímulo dos
fotorreceptores (mistura de interneurônios e vários `DN*`, consistente com RN-09).

**Armadilha de performance encontrada e corrigida:** a taxa de disparo real do
subcircuito sob estímulo é alta (~95 disparos/passo, quase 1M eventos num run de 10 s).
Gravar linha a linha via `executemany` do DuckDB (autocommit por lote) travava por
minutos. Corrigido para inserção colunar em lote via `DataFrame` + `con.append()` —
1000 passos com telemetria caiu de "não termina" para 0,19 s. Nenhum lock de arquivo
sobrevive um processo morto (`TaskStop`/kill libera o handle do SO), mas o `.wal`
parcial de uma conexão interrompida fica em disco — seguro apagar (`runs.duckdb` é
dado de telemetria descartável, não `data/raw` nem `data/processed` científico).

**RN-08 continua aberta:** o agrupamento por prefixo é mecanismo, não curadoria — não
sabemos ainda que grupo corresponde a "forward"/"yaw"/etc. Isso é trabalho de leitura de
literatura (DNp/DNa/DNg), não algo para inventar. Decidir antes da F4 (encarnação), que é
quando a semântica do vetor motor passa a importar de verdade.

---

## F3 — Ponte ✅ concluída (plugin compila; não testado num servidor real ainda)

- [x] `server.py` — socket TCP, JSON-lines, thread de simulação desacoplada (RN-06)
- [x] `Dockerfile` + `docker-compose.yml` — já existiam, validados com build + run reais
- [x] Cliente de teste em Python que finge ser o Minecraft (`tools/fake_minecraft_client.py`)
- [x] Scaffold do plugin Paper (Gradle, Java 21) — compila (`./gradlew build`), **ver ressalva**

**Critério de saída:** cliente falso injeta sensores a 20 Hz por 60 s e recebe vetores
motores sem perda de frame nem crescimento de memória.

**✅ Atingido e validado em dois níveis:**
1. In-process: 60 s reais a 20 Hz, 1200/1200 mensagens indo e voltando, zero erro,
   `motor._history` estável (não cresce).
2. Docker real: `docker compose build` + `up`, cliente do host conectou em
   `localhost:8765` e trocou mensagens com o container normalmente — a mesma forma
   como o plugin vai se conectar de verdade.

**Protocolo implementado difere do aspiracional em um ponto, documentado:** o campo
`motor` carrega os 8 grupos provisórios de RN-08 (`DNp`, `DNg`, ...), não
`forward`/`yaw`/`lift` como `02-arquitetura.md` descreve — porque essa curadoria
ainda não existe (RN-08 segue aberta). `active_dn` é literal: quantos descendentes
dispararam na janela.

**Atualização — plugin agora compila de verdade.** O wrapper do Gradle
(`gradlew`/`gradlew.bat`/`gradle-wrapper.jar`) foi bootstrapado com Gradle 8.10
oficial (baixado, usado uma vez para gerar o wrapper, removido) e está
versionado — `./gradlew build` funciona sem precisar de Gradle instalado à
parte. `build.gradle.kts` não fixa toolchain (evita exigir JDK 21 literal);
compila com o JDK disponível usando `--release 21` para compatibilidade de
bytecode com o Paper. O `.jar` (`flywire-bee-0.1.0.jar`) foi copiado para
`mc-server/plugins/`.

**✅ Validado em servidor real em 16/09/2026.** Simulador rodando em Docker +
Paper 1.21.1 em `mc-server/` (`online-mode=false`, conta TLauncher offline) +
plugin carregado: log confirma `[FlywireBee] Conectado ao simulador em
localhost:8765` no `onEnable`, e um jogador real entrou no mundo. Primeira
prova de que `BridgeClient` (Java) e `server.py` (Python) realmente
interoperam fora de teste isolado — não só protocolo compatível no papel.

**O que isso NÃO prova ainda:** a abelha não se comporta diferente de uma
abelha normal — o plugin só conecta e loga (`onEnable`); não há listener de
sensores nem aplicação do vetor motor (isso é o TODO explícito no código,
trabalho da F4).

**Extra além do checklist original:** mecanismo de spawn/identificação da
abelha controlada (`/flywirebee give` + `FlywireBeeMarker`, ver
`plugin/README.md`) — testado em servidor real, spawna e marca corretamente
(nome visível + brilho). IA nativa continua ligada de propósito até a F4
aplicar controle de verdade. Isso não estava no checklist da F3, mas é
pré-requisito natural para F4 (precisa dar pra identificar qual abelha
controlar antes de controlar ela).

**✅ Risco investigado no início da F4 (16/09/2026) — resultado inverteu o plano.**
Quatro modos de movimento testados via spike isolado (`/flywirebee spike <modo>`) numa
abelha real, servidor rodando: `setVelocity()` a cada tick com IA **desligada** produziu
**0,000 blocos** de deslocamento em 5s (a abelha congela — achado real, não bug); com IA
**ligada**, o mesmo `setVelocity()` a cada tick produziu 28,7 de ~30 blocos esperados
(96%, perda normal por resistência do ar). **Conclusão: NÃO desligar a IA nativa** —
sobrescrever velocidade a cada tick (20Hz) domina a decisão da IA nativa no mesmo tick,
sem precisar desativar nada. O plano original ("desabilitar IA antes de aplicar") estava
errado na direção oposta. Ver `plugin/README.md` para a tabela completa.

---

## F4 — Encarnação ✅ critério de saída atingido

- [x] Risco de física investigado — velocidade funciona com IA ligada (ver acima)
- [x] Spawn/identificação da abelha controlada (`/flywirebee give`, feito na F3)
- [x] Plugin lê luz do bloco (`light`, `dorsal_light`) → envia sensores (`ControlLoop`)
- [x] Plugin aplica vetor motor à abelha — magnitude de avanço via canal `phototaxis`
      (topologia de sinal validada, ver RN-08/RN-09 em `04-regras-de-negocio.md`)
- [x] ~~Desabilitar a IA nativa do mob~~ — **invertido**: manter IA ligada, sobrescrever
      velocidade a cada tick (ver risco investigado acima)
- [x] **Experimento de lesão — critério de saída atingido** (ver resultado abaixo)

**✅ Loop de controle validado em servidor real em 16/09/2026.** `/flywirebee control
start`, ~58s rodando, **1500 trocas com a ponte, 0 falhas** (~26 Hz efetivo, acima da
meta de 20 Hz). Luz do bloco lida corretamente (variou 0,80–1,00 durante o teste),
velocidade aplicada varia continuamente em X/Z conforme a atividade do circuito. Thread
principal nunca bloqueou na ponte (RN-06 respeitada — I/O de rede isolado em thread
dedicada, sempre aplica o último vetor motor já calculado). **Confirmado visualmente
pelo usuário** — a abelha realmente voou pelo mapa, não só o log mostrando números.

**Experimento de lesão — desenho:** N trials (padrão 20×10s), ordem aleatória entre
fotorreceptores normais e silenciados (não alternância estrita — evita confundir
condição com deriva do ciclo dia/noite do Minecraft ao longo do experimento). Cada
trial teleporta a abelha de volta à origem e zera velocidade antes de começar, mede
comprimento de trajetória (soma de distância por tick, não só início-fim). Lesão é
aplicada do lado do plugin (sempre manda `light=0` pra ponte, sem mudar o protocolo
nem `server.py`) — os fotorreceptores continuam existindo no circuito, só não recebem
estímulo do mundo real; a dinâmica basal (bias+ruído, RN-09) continua rodando. CSV vai
para `mc-server/plugins/FlywireBee/lesion_experiment.csv`; `sim/tools/lesion_analysis.py`
compara `path_length`/`avg_speed` entre os dois grupos (Welch t-test + Mann-Whitney U,
mesma dupla de testes já usada para validar RN-09).

**Critério de saída:** o teste de lesão mostra diferença **estatisticamente mensurável**
entre abelha com e sem entrada sensorial. Sem isso, a v1 não está acoplada.

**✅ Atingido em 16/09/2026, na segunda tentativa.** Primeira rodada (20 trials, motor
mapeado pela MÉDIA simples dos 8 grupos por prefixo) deu nulo — p=0,37 — repetindo o
mesmo erro de agregação já corrigido uma vez em RN-09 (excitatório+inibitório se
cancelam na média). Corrigido adicionando o canal `phototaxis` (topologia de sinal já
validada, não curadoria nova — ver RN-08 em `04-regras-de-negocio.md`) e usando ele em
vez da média. Segunda rodada (20 trials, 11 normais / 9 lesionados):

| Métrica | Normal | Lesionado |
|---|---|---|
| distância percorrida (blocos) | 31,26 ± 1,60 | 27,56 ± 5,08 |

Mann-Whitney U: **p = 0,0014** ✅ (Welch t-test: p=0,078, marginal — um trial lesionado
outlier, 13,6 blocos contra ~27-30 dos demais, infla a variância e prejudica o teste
paramétrico; o teste não-paramétrico, mais robusto a esse tipo de outlier, é o que
carrega a conclusão aqui, prática estatística padrão, não descarte seletivo de dado).

**Isto responde a pergunta de pesquisa da v1** (`00-visao-geral.md`): o circuito ocelar,
alimentado por luz real do Minecraft, produz resposta comportamental distinguível de
ruído. A abelha se move de forma mensuravelmente diferente com e sem entrada sensorial
— a simulação está de fato acoplada, não é só movimento gerado à toa.

**Ressalvas honestas:** N=20 é pequeno; mais trials fortaleceriam a conclusão. O
mapeamento motor (`MotorMapping`) ainda é magnitude-apenas (RN-08 sem curadoria de
direção/yaw) — o resultado prova ACOPLAMENTO, não prova que o comportamento resultante
seja "orientação" ou "estabilização" específica como a pergunta de pesquisa original
especula; isso exigiria a curadoria pendente de RN-08 e mais experimentos direcionais.

---

## F5 — Observabilidade e experimentos ✅ concluída

- [x] Visualizar disparos no mundo — `ActivityVisualizer` + `/flywirebee visualize <on|off>`
- [x] Ferramenta de lesão: silenciar neurônio/tipo por comando — `/flywirebee mute|unmute`
- [x] Estimulação dirigida de tipos específicos — `/flywirebee stimulate <grupo> <amp>`
- [x] Documentar resultados — `docs/05-resultados.md`

**Critério de saída:** um observador humano consegue, olhando o mundo, dizer qual parte
do circuito está ativa.

**Visualização — implementada e confirmada visualmente em 16/09/2026.** Cada canal do
vetor motor (8 grupos por prefixo de `cell_type` + `phototaxis`) tem uma cor de partícula
fixa (`Particle.DUST` com RGB customizado); quantidade de partículas por canal é
proporcional a |valor| do canal (até 6 por grupo), renderizado a ~4Hz ao redor da abelha
(20Hz de partículas seria spam visual). Liga junto com `control start`, comando
`/flywirebee visualize <on|off>` para alternar. Usuário confirmou ver as partículas
coloridas em servidor real.

**Lesão e estimulação por comando — implementadas em 16/09/2026, testadas via TCP
manual (não em servidor com jogador ainda).** Estendem o protocolo com dois campos
opcionais (`mute`, `stimulate` — ver `docs/02-arquitetura.md`). `mute` silencia de
verdade a saída sináptica do grupo no `engine.py` (`set_silenced`) — mecanismo
DIFERENTE do experimento de lesão da F4 (que só zera `light`, não desliga neurônio
nenhum). `stimulate` injeta corrente extra num grupo (`set_directed_stimulus`),
somada ao estímulo de luz, não substituindo. Ambos testados fim a fim: mutar DNp fez
o canal ir a exatamente 0,0 (depois da janela de 50ms esvaziar); estimular DNg com
amplitude 5,0 saturou o canal perto de 1,0; os dois voltam ao baseline ao limpar.
Comandos: `/flywirebee mute <grupo>`, `unmute <grupo|all>`,
`stimulate <grupo> <amplitude>`, `stimulate stop`.

**✅ Confirmado em servidor real com jogador, 16/09/2026.** `mute DNp` fez as
partículas vermelhas sumirem, como esperado. `stimulate DNg 5.0` deixou a abelha
mais **lenta**, não mais rápida — achado real, não bug: DNg (agrupamento por
prefixo) é 15 de 16 neurônios (94%) do grupo **inibitório** da topologia de sinal
(RN-09), então estimulá-lo reduz `phototaxis` em vez de aumentar. Ver
`plugin/README.md` para o detalhe completo. Também confirmado: a IA nativa
"vazou" uma vez (abelha parou pra polinizar flor) — esperado, ruído de fundo
simétrico entre condições, não invalida nada (ver discussão sobre IA nativa
mais acima).

**Pendente:** só o item "documentar resultados" (relato coerente de F1-F5,
não é código).

---

## F6 — RN-08 completa (BANC) + multi-sensor dia/noite ✅ concluída — resposta à luz é não-monotônica

### RN-08 — segunda fonte via BANC connectome ✅ concluída

- [x] BANC (Bates, Phelps, Kim, Yang et al. 2026) baixado do Harvard Dataverse,
      cruzado contra os 34 tipos sem curadoria (AD-15)
- [x] 5 tipos novos com comportamento medido (literatura), 7 conflitos Namiki×BANC
      resolvidos tipo a tipo, 27 tipos com cluster de conectividade (evidência mais
      fraca, exposto como telemetria `conn_*`)
- [x] `motor.py` atualizado (`PUBLISHED_DN_BEHAVIOR`, `CONNECTIVITY_CLUSTER_BANC`),
      20/20 testes passando

Ver `docs/04-regras-de-negocio.md` RN-08 para a tabela completa. `MotorMapping.java`
não foi tocado — os canais novos ficam fora do cálculo de velocidade até validação
própria por lesão.

### Multi-sensor: dia/noite ✅ efeito confirmado com `goals off`; explicado pela dose-resposta

**Correção de premissa (16/09/2026) — a nota original desta seção estava errada.**
A ideia inicial era: "`dorsal_light` (luz do céu) é quase de graça pra dia/noite, só
falta usar". Investigando a API do Bukkit antes de implementar, achamos o oposto:
`Block.getLightFromSky()` (nosso `dorsal_light`) retorna o **skylight bruto**, que
fica travado em 15 ao ar livre **independente da hora do dia** — é sensor de
teto/céu aberto (indoor vs. outdoor), não de hora. Quem já é sensível a dia/noite é
`Block.getLightLevel()` (nosso `light` — o mesmo canal que já validou `phototaxis`,
p=0,0014) — confirmado via Minecraft Wiki (skylight bruto vs. "internal sky light",
que aplica a redução por hora do mundo). Ver `CONVENCOES.md`, "Armadilhas conhecidas".

**Desenho adotado, sem tocar no estímulo já validado:** `light` continua
alimentando os fotorreceptores exatamente como na F4. `dorsal_light` vira **filtro
de confundidor** — confirma que a abelha está mesmo ao ar livre (skylight=15) antes
de rodar um trial, evitando o mesmo confundidor de "abelha dentro de casa" que já
apareceu uma vez em RN-08/F6. Novo experimento (`DayNightExperiment.java`, mesmo
padrão do experimento de lesão da F4): N trials, `world.setTime()` alternando
meio-dia/meia-noite por trial, mede `path_length`/`avg_speed`, compara com
`sim/tools/daynight_analysis.py` (mesma dupla Welch t-test + Mann-Whitney U).

- [x] `DayNightExperiment.java` + `/flywirebee daynight [trials] [segundos] [blind]`
- [x] `sim/tools/daynight_analysis.py`
- [x] Controle cego (`blind`, `light=0`) — adicionado antes de rodar: abelha vanilla
      muda de comportamento à noite pela IA nativa, então diferença dia/noite sem
      controle não seria atribuível ao circuito
- [x] Rodado em servidor real, 16/09/2026 — **resultado negativo**, ver abaixo

**Critério de saída:** diferença estatisticamente mensurável entre trials de dia e
de noite — mesmo critério de falsificação do experimento de lesão da F4, aplicado a
um novo eixo de variação (hora do mundo em vez de fotorreceptor silenciado).

**❌ Não atingido — resultado negativo, registrado como tal (16/09/2026).** Três
rodadas de 20×10s, dia/noite sorteado por trial dentro de cada rodada:

| Rodada | Origem (dist. da normal) | Geral (blocos) | Dia | Noite | Dia × noite (MW) |
|---|---|---|---|---|---|
| Normal | (129,6; 71,4; -116,0) — 0 | 31,31 ± 1,53 | 31,34 | 31,29 | p=0,29 (Welch 0,94) |
| Cega | (137,8; 72,5; -108,1) — 11,5 | 29,96 ± 0,54 | 29,88 | 30,04 | p=0,68 |
| Cega | (179,7; 69,0; -169,7) — 70 | 20,97 ± 8,21 | 22,27 | 19,91 | p=0,40 |

**Checagem de manipulação — passou.** `light` logado pelo `ControlLoop` por
condição: dia 0,987, noite 0,250 (= 4/15, exatamente o "internal sky light" de
meia-noite da Minecraft Wiki — confirma empiricamente que `getLightLevel()` varia
com a hora). Na rodada cega, `light` enviado = 0,000 em 100% das amostras, com a
luz real seguindo variando (0,97/0,26). O experimento testou o que pretendia; o nulo
é da hipótese, não do desenho.

**O que isso mostra:**
1. **Reduzir a luz em 75% (1,0 → 0,25) não muda o comportamento.** Nenhuma das três
   rodadas mostra diferença dia/noite. A abelha não responde ao ciclo dia/noite do
   mundo na faixa de luz que o ciclo produz.
2. **A IA nativa noturna não é confundidor detectável** — rodadas cegas também não
   mostram diferença dia/noite.
3. **O local da origem pesa mais que a luz.** As duas rodadas cegas (mesma condição)
   diferem em 9 blocos (p&lt;0,001) só por estarem a 70 blocos de distância — a
   rodada a 70 blocos teve trial de 1,78 blocos (abelha presa em terreno). A queda
   "normal × cega" de ~10 blocos que apareceu primeiro era quase toda terreno.
   **Comparação entre rodadas com origens diferentes não é válida** — virou armadilha
   registrada em `CONVENCOES.md`.

**O que NÃO dá pra afirmar:**
- **Lesão reproduzida nesta sessão.** Normal × cega a 11,5 blocos dá 1,35 blocos
  (~4%, MW p=5·10⁻⁵), mesma direção da F4 — mas com efeito de local de até 9 blocos,
  11,5 blocos de distância bastam pra explicar 1,35. A evidência válida de lesão
  continua sendo a da F4 (normal/lesionado sorteado **dentro** da mesma rodada e
  origem, p=0,0014).
- **Resposta à luz saturante.** Luz 0,25 ≈ luz 1,0, e luz 0 aparentemente abaixo, é
  compatível com saturação em luz baixa — mas o ponto "luz 0" desta sessão vem de
  comparação entre rodadas. **Resolvido mais abaixo** (seção "Dose-resposta de
  luz") — não era saturação, é curva não-monotônica com pico em luz=0,25.

CSVs brutos preservados em `mc-server/plugins/FlywireBee/`:
`daynight_experiment_normal_local129.csv`, `daynight_experiment_blind_local137.csv`,
`daynight_experiment_blind_local179.csv`.

### Hipótese do usuário — IA nativa mascarando o efeito (17/09/2026)

**`setAI(false)` já tinha sido descartado na F4** (congela a física). Investigado
mecanismo novo, nunca testado: **Mob Goal API do Paper** (`Bukkit.getMobGoals()`),
que remove objetivos de IA específicos sem tocar em `setAI`. Implementado
(`CompetingGoals.java`, `/flywirebee goals off`, spike
`no-competing-goals`) e **testado isolado antes de integrar** (mesma disciplina da
F4): 28,84 de 30 blocos em 5s (96%, igual ao modo com IA ligada) — a física não
trava. Remove `BEE_WANDER`, `BEE_GO_TO_KNOWN_FLOWER`, `BEE_POLLINATE`,
`BEE_GO_TO_HIVE`, `BEE_LOCATE_HIVE`, `BEE_ENTER_HIVE` (candidatos a competir com
locomoção — os três últimos, específicos de voltar pra colmeia à noite, eram o
candidato mais forte pro confundidor dia/noite). Sem volta pela API pública —
abelha precisa ser respawnada (`kill`+`give`) pra ter os goals padrão de volta.

**Resultado — real, mas ambíguo, e ainda com confundidor de local.** Rodada com
`goals off`, origem a 38 blocos da rodada normal (não o ponto combinado):

| | Dia | Noite |
|---|---|---|
| Distância (blocos) | 30,01 ± 0,22 | 30,23 ± 0,21 |

Welch p=0,047 (limítrofe), Mann-Whitney p=0,10 (não significativo) — pelo padrão
já estabelecido na F4 (Mann-Whitney decide quando os dois discordam), **continua
nulo**. Direção inversa da esperada (noite andou um pouco mais que dia, não
menos), efeito do tamanho do próprio desvio-padrão.

**Achado real, mesmo sem confirmar dia/noite:** desvio-padrão caiu de ~0,6–8,2
blocos (rodadas com IA nativa ligada) pra **0,22** — quase 3 a 40× menos ruído.
Isso é evidência de que a IA nativa competia pelo controle e adicionava variação;
a hipótese do usuário tinha fundamento nesse sentido, mesmo sem confirmar que
essa competição mascarava um efeito de dia/noite. Com ruído tão menor, uma rodada
maior (mais trials) teria bem mais poder estatístico que as anteriores.

**Pendente:** repetir com `goals off` na origem exata da rodada normal
((129,58; 71,40; -116,01)) pra eliminar o confundidor de local e decidir se o
p=0,047/0,10 é sinal real ou ruído residual. CSV desta rodada preservado como
`daynight_experiment_goalsoff_local92.csv`.

### Confirmado — efeito real de dia/noite com `goals off` (17/09/2026)

Repetido com `goals off`, origem (129,37; 71,00; -102,07) — 13,9 blocos do ponto
combinado (X/Y bateram, Z não; `/tp` no jogador não move onde a abelha vai spawnar,
ver armadilha em `CONVENCOES.md`).

| | Dia (n=9) | Noite (n=11) |
|---|---|---|
| Distância (blocos) | 29,95 ± 0,15 | 30,26 ± 0,18 |

Welch p=0,00087, Mann-Whitney p=0,00184 — **os dois testes concordam, diferença real.**
Checagem de manipulação passou de novo (`light` 1,00 de dia, 0,27 à noite).

**Replicação entre locais, só nas rodadas `goals off`:** esta rodada (13,9 blocos do
alvo) e a anterior (`daynight_experiment_goalsoff_local92.csv`, 38 blocos do alvo)
deram médias praticamente idênticas — 30,12 ± 0,22 nas duas, p=0,96/0,92 entre elas.
**Com os goals desligados, o confundidor de local que dominava as rodadas com IA
ligada (9 blocos de diferença só por terreno) praticamente desaparece** — evidência
adicional de que a IA nativa, não o terreno em si, era a maior fonte de ruído.

**O que fica confirmado:**
1. A hipótese do usuário estava certa: a IA nativa mascarava um efeito real. Com ela
   competindo, três rodadas independentes deram nulo (p≥0,29); sem ela, o efeito
   aparece de forma consistente e replicada.
2. O circuito ocelar responde ao ciclo dia/noite do mundo — não só a lesão binária já
   provada na F4.

**Na hora, direção inversa da esperada não estava explicada.** Menos luz (noite,
`light`≈0,27) produziu **mais** distância percorrida que luz cheia (dia,
`light`=1,0), não menos — o oposto do que RN-09/F4 sugeririam de forma ingênua.
Efeito pequeno (~1%, 0,3 de ~30 blocos) mas estatisticamente sólido. Hipótese
levantada então, não testada ainda: resposta não-monotônica à luz. **Resolvido
logo abaixo, com o experimento de dose-resposta.**

CSV desta rodada: `daynight_experiment_goalsoff_local129z-14.csv`.

### Dose-resposta de luz — curva não-monotônica confirmada, "pico" em 0,25 (17/09/2026)

Implementado `LightDoseResponseExperiment.java` (`/flywirebee doseresponse`) —
4 níveis (0 / 0,25 / 0,5 / 1,0) sorteados trial a trial, `ControlLoop.setForcedLight`
(generalização do mecanismo que já fazia `lesion` mandar `light=0`; não depende de
hora do mundo, funciona em qualquer lugar).

**Achado de engenharia, não só ciência:** as duas primeiras tentativas derivaram 24
e 38 blocos do ponto pretendido — mesmo depois de criar `/flywirebee goto <x> <y>
<z>` pra reposicionar a abelha, o intervalo real entre digitar `goto` e digitar o
comando do experimento era tempo suficiente pra IA nativa mover a abelha de novo.
**Corrigido de verdade:** `daynight`/`doseresponse` passaram a aceitar `x y z`
opcional no próprio comando, teleportando a abelha no mesmo instante de execução —
zero janela de deriva. Origem bateu exata na rodada final (129,58; 71,40; -116,01,
0 blocos de erro).

**Resultado, 32 trials na origem exata, `goals off`:**

| Luz | n | Distância (blocos) | vs. luz=1,0 (Mann-Whitney) |
|---|---|---|---|
| 0,00 | 7 | 28,75 ± 0,14 | menor, p=0,00017 |
| 0,25 | 7 | **30,36 ± 0,08** | **maior**, p=0,00524 |
| 0,50 | 9 | 30,02 ± 0,16 | sem diferença, p=0,48 |
| 1,00 | 9 | 30,12 ± 0,13 | (referência) |

Kruskal-Wallis (4 níveis): H=23,57, **p=0,00003**. Checagem de manipulação: cada
nível foi enviado exatamente como pedido (0/0,25/0,5/1,0, desvio zero).

**A curva é não-monotônica — pico em luz=0,25, não platô nem inversão simples.**
Escuro total reduz o movimento (consistente com F4/RN-09). Luz 0,25 é
**significativamente maior** que luz plena — reproduz e agora confirma
estatisticamente, com poder adequado, o achado "invertido" da seção anterior
(que tinha vindo com confundidor de local). Luz 0,5 volta ao mesmo patamar de luz
1,0. Formato: sobe de 0 pra 0,25 (pico), desce de 0,25 pra 0,5/1,0 (platô).

**Hipótese candidata testada (17/09/2026) — NÃO se sustentou.** RN-09 mostrou que
os 92 descendentes se dividem em 29 com caminho de sinal excitatório (desinibição
de 2 saltos) e 63 com caminho inibitório direto. A hipótese era: se as duas vias
tiverem curvas de resposta à intensidade de luz diferentes, a diferença
(`phototaxis`) pode ficar não-monotônica mesmo que cada via sozinha seja mais
simples. Testado com `sim/tools/light_curve_check.py` (novo — mesma filosofia de
`calibration_check.py`, roda sem Minecraft): mede taxa excitatória e inibitória
separadas para os 4 níveis de luz, `Engine` novo e semente pareada por trial (30
sementes, 200ms cada).

**Resultado: as duas vias NÃO explicam o pico.** As duas mudam de light=0 pra
qualquer light>0 (p&lt;0,001 nas duas), mas ficam achatadas entre 0,25/0,5/1,0
(p>0,7 em ambas, nada de pico). `phototaxis` bruto também sai achatado nesse
teste (0,016 / 0,013 / 0,013 — dentro do próprio desvio-padrão), nada parecido
com os 30,36 blocos medidos no jogo. **A hipótese registrada não se sustenta —
não citar "as duas vias têm curvas diferentes" como explicação.**

**Discrepância em aberto, não resolvida:** o teste usa um `Engine` novo por
trial (estado zerado); o experimento real no Minecraft usa UM `Engine` contínuo
rodando os 32 trials, com estado (potencial de membrana, refratário, fluxo de
ruído) carregando de um nível de luz pro outro, e mede distância acumulada em
10s de física da abelha, não taxa instantânea. Duas explicações candidatas, **nenhuma
testada**: (1) o pico vem da dinâmica contínua do engine, não do circuito em
estado estacionário; (2) o pico vem do lado Minecraft (`MotorMapping.java`,
física/momento da abelha), não do `phototaxis` em si. Registrado como pergunta
em aberto — não investigado mais a fundo por decisão do usuário (17/09/2026),
prioridade foi pra outros itens.

CSVs brutos: `doseresponse_experiment_local129z-92_old.csv` (origem errada, 23,9
blocos de erro, só a forma dentro da rodada é válida),
`doseresponse_experiment_local129_32trials.csv` (origem exata — usar este pra
qualquer citação), `doseresponse_experiment_local129_replica20.csv` (réplica,
N=20, mesma origem — confirma a forma: 0,25 no topo, 0,5/1,0 empatados, 0,00
abaixo, Kruskal-Wallis p=0,01).

### Lesão da F4 repetida com `goals off` — hipótese errada na direção (17/09/2026)

Testado se a IA nativa estava subestimando o efeito de lesão (mesmo raciocínio
que valeu pro dia/noite). **20 trials, mesma origem exata (129,58; 71,40;
-116,01), `goals off`:**

| | Normal | Lesionado |
|---|---|---|
| F4 original (IA ligada, N=20) | 31,26 ± 1,60 | 27,56 ± 5,08 |
| F6 (`goals off`, N=20) | 29,98 ± 0,17 | 28,66 ± 0,10 |

F4: diferença 3,70 blocos (~12%), Mann-Whitney p=0,0014, Welch só marginal
(p=0,078, outlier de 13,6 blocos já documentado). F6: diferença **1,32 blocos
(~4,4%)** — menor — mas Welch e Mann-Whitney concordam fortemente agora
(p≈0,00000 / 0,0002). Checagem de manipulação: luz real variava (~0,86-0,91,
não forçada quando normal), luz enviada forçada em 0,000 exatamente quando
lesionado.

**A hipótese registrada ontem estava errada na direção.** Não era "IA nativa
dilui o efeito, tamanho real é maior" — foi o oposto: o efeito de 12% da F4
vinha inflado por um outlier específico; com ruído baixo, o efeito real é mais
modesto, só que muito mais confiável estatisticamente. **Lição geral: ruído de
IA nativa não tem direção previsível** — pode mascarar (dia/noite) ou inflar via
outlier (lesão), tem que medir de novo em cada caso, não assumir a direção.

CSV: `lesion_experiment_goalsoff_local129.csv`.

### RN-08 completa — primeiro canal de direção real, `yaw_steering` (AD-16, 17/09/2026)

Início de "anatomia real", pedido pelo usuário. Antes de processar o arquivo de
9,5GB da Zenodo (posição XYZ por sinapse), achado que não precisava dele:
`nodes.parquet` já tem uma coluna `side` (esquerda/direita/centro, do
`Supplemental_file1` original) **nunca usada em lugar nenhum do código**.

Cruzando `side` contra os 5 tipos `steering` já resolvidos (RN-08/AD-15, Feng et
al. 2024 e Yang et al. 2024): **4 têm par bilateral limpo — 1 neurônio à
esquerda + 1 à direita, exatamente** (`DNae003`, `DNb05`, `DNb06`, `DNge070`).
`DNa03` (5º tipo steering) fica de fora, só tem 1 neurônio no subcircuito.

Implementado `motor.py::group_steering_by_side` + canal `yaw_steering =
tanh((taxa_esquerda − taxa_direita) / MOTOR_RATE_SCALE)`, exposto em
`decode()`. **Só telemetria** — `side` é o lado do corpo celular, não
necessariamente o lado do efeito comportamental (RN-08/AD-15 já registrou a
mesma ressalva pro `side` do BANC). Não entra em `MotorMapping.java` até um
experimento validar o sentido do sinal — precisaria medir mudança de direção
da abelha (heading), infraestrutura que não existe ainda.

**É o primeiro candidato real a canal de direção além de `phototaxis`** desde
que RN-08 foi aberta — construído de um tipo com função medida (não cluster
fraco) e anatomia bilateral real, não fabricada. Pendente: desenhar o
experimento que testa se o sinal corresponde a virar pra um lado ou outro.

Processamento do arquivo de 9,5GB (posição XYZ, neurópilo por sinapse) segue
pendente — ficou pra depois dessa checagem mais barata, decisão do usuário.

### Anatomia real — arquivo de 9,5GB processado (17/09/2026)

`sim/tools/extract_anatomy.py` — streaming por record batch (AD-12), 21s,
1.813.778 de 54,5 milhões de sinapses casadas com os 625 neurônios do
subcircuito. Resultado em `data/processed/anatomy_783.parquet` (não
versionado): posição 3D (centroide por neurônio) + neurópilo dominante,
**625/625 neurônios (100%) cobertos nos dois**.

Checagem de sanidade: os dois neurônios de `DNp22` caem em `IPS_L`/`IPS_R`
(bate com `side`), coordenada X consistente com o lado. Distribuição de
neurópilo dominante faz sentido biológico — `OCG` (ganglio ocelar, 314
neurônios, onde o circuito nasce) domina, seguido de regiões que descendentes
atravessam a caminho do cordão nervoso (`GNG`, `SPS`, `IPS`, `PLP`) e do lobo
óptico (`ME`, `LO` — parte do circuito passa perto de vias visuais mais
amplas, não só ocelar).

**Não usado em nada ainda** — é enriquecimento, não input de nenhum cálculo.
Candidato natural: cruzar posição X real (não só `side` categórico) contra os
32 tipos bilaterais achados em AD-16, pra confirmar/refinar os pares
esquerda/direita com mais precisão que a categoria discreta já dá.

### RN-08 completa — `yaw_steering` validado, primeira direção real do circuito (17-18/09/2026)

Pergunta que AD-16 deixou aberta: o canal controla direção de verdade, ou é
só um número sem efeito no mundo? Resposta: **controla, e o sentido bate com
o nome.**

**Caminho até a resposta, com dois falsos-negativos registrados por
transparência:**
1. `MotorMapping.java` ganhou guinada real (`rotateAroundY` proporcional a
   `yaw_steering`) e `SteeringValidationExperiment`/`validateyaw` (3
   condições: estimular `steering_left`, `steering_right`, ou nada — mede
   ângulo de giro líquido, não distância).
2. 1ª rodada: `net_turn_rad`≈0 sempre — bug real. `ControlLoop` recapturava a
   orientação FÍSICA da abelha (controlada pela IA nativa) a cada troca com a
   ponte, em vez de reaproveitar a direção já rotacionada — giro nunca
   compunha. Corrigido com estado persistente (`ControlLoop::heading`).
3. 2ª rodada: resultado idêntico à 1ª — mas por um motivo bem mais chato: o
   servidor não tinha reiniciado desde antes do jar corrigido ser copiado,
   rodou o código velho de novo. Diagnóstico direto no simulador (sem
   Minecraft) confirmou nesse meio tempo que o canal em si já estava saudável
   (`yaw_steering` satura em exatamente ±1,0 estimulando cada lado, desvio
   zero) — evitou queimar mais uma rodada perseguindo hipótese errada.
4. 3ª rodada, jar certo finalmente carregado — **validado**.

**Resultado:**

| Condição | Giro líquido acumulado | n |
|---|---|---|
| `left` | −1,776 ± 0,028 rad (−101,8°) | 4 |
| `right` | +1,795 ± 0,003 rad (+102,8°) | 7 |
| `baseline` | −0,004 ± 0,043 rad (−0,3°) | 9 |

Kruskal-Wallis p=0,00028; `left`×`right` p=0,006 (sentidos opostos); cada um
contra `baseline` p=0,0028 e p=0,00017. Magnitude bate com o previsto
(`yaw_steering` saturado por 10s × `MAX_YAW_RADIANS_PER_TICK` ≈ 2 rad
teóricos, medido ~1,8 rad) — não é só "significativo", é quantitativamente
consistente com o mecanismo desenhado.

**✅ Sentido real do mundo — confirmado visualmente, 18-19/09/2026.**
Dedução geométrica do `bearing`: `steering_right` vira a abelha pra direita,
`steering_left` pra esquerda — o nome do canal bate com o lado real. Um bug à
parte quase atrapalhou a checagem: `setVelocity()` move a abelha mas não gira
o corpo visual dela sozinho (isso é papel da IA nativa/pathfinding, que não
reage a movimento comandado por código) — primeira observação visual do
usuário descreveu a abelha "andando de ré" e virando "esporadicamente", que
parecia contradizer o resultado estatístico limpo. O log mostrou a
velocidade girando suave o tempo todo; só a aparência estava errada.
Corrigido com `bee.setRotation(yaw, pitch)` a cada tick em `ControlLoop`
(yaw calculado a partir de `latestVelocity`). Depois da correção, usuário
confirmou: a abelha "acompanha a curva corretamente e de forma leve", e
`steering_left` vira ela pra esquerda dela mesma — **bate exatamente com a
dedução geométrica**.

**Achado tangencial, não investigado:** estimular qualquer um dos dois lados
reduz a velocidade de avanço bastante (`right` chega a quase metade),
mesmo esses neurônios não alimentando `phototaxis` — efeito de rede
recorrente que extrapola o canal que se queria medir.

**O que isso NÃO prova:** que `MotorMapping.java` deveria usar
`yaw_steering` fora de experimento controlado (constante de rotação não
calibrada) — e o canal só cobre 4 dos 47 tipos, o resto de RN-08 continua sem
direção própria.

Também implementado, pedido separado do usuário: painel `LiveHud`
("Flywire Bee Live", sidebar do Bukkit) mostrando `phototaxis`,
`yaw_steering` e `active_dn` ao vivo — confirmado visualmente, aparece no
canto superior direito como esperado. Ver `plugin/README.md`.

---

## F7 — Multi-sensor v2: chuva e toque ✅ concluída — `bristle` ✅ validado ponta a ponta (lesão p=0,0025); `hygro` ✅ validado ponta a ponta (lesão p=0,00019, 22/09/2026)

Começou como planejamento puro (19/09/2026): registrar o plano completo antes
de tocar em qualquer seed novo, dado que os dois sensores anteriores (F6,
dia/noite) só saíram certos depois de descartar uma premissa errada e corrigir
o desenho do experimento duas vezes. Ainda no mesmo dia, o levantamento de
literatura (primeiro item do checklist) achou rótulo real pros dois sensores
nas anotações — motivou seguir direto pra extração L0→L1 (ver checklists
abaixo), sem esperar uma sessão nova.

**Princípio geral, herdado de RN-08/RN-09 e da seção "Armadilhas conhecidas" do
`CONVENCOES.md`:** cada sensor novo é um **subcircuito independente**, extraído
e validado por lesão própria antes de qualquer tentativa de combinar canais.
Não misturar chuva/toque com `phototaxis` sem repetir o mesmo teste que já
falhou 3 vezes por diluição (RN-09/F1, F4 primeiro experimento, RN-08/F6).

### Decisão de arquitetura a registrar via ADR antes de extrair qualquer seed

- [ ] **AD-17** — como múltiplos subcircuitos coexistem no simulador. Hoje
      `select_seed` (`ingest.py`) e `SEED_PATTERN`/`HOPS`/`SYN_THRESHOLD`
      (`config.py`) assumem **um** subcircuito hardcoded ("ocell"). Opções a
      decidir, não a inventar durante a extração:
      1. **Engines separados** — um `Engine` (RN-06) por subcircuito, rodando
         em paralelo na mesma thread de simulação, cada um com seu próprio
         `nodes`/`edges`/`manifest`. Mais simples de isolar (lesão de um não
         toca no outro), mas replica overhead de bias/ruído (RN-09) e não
         captura interação real entre circuitos se ela existir no conectoma.
      2. **Grafo único maior** — uma extração com semente = ocelar ∪ novo(s)
         sensor(es), um só `Engine`. Mais fiel à biologia (se os circuitos se
         tocam no cérebro real, aparece), mas reabre RN-01a (neuromoduladores
         deixam de ser inócuos fora da fronteira atual, já registrado como
         risco) e exige recalibrar RN-09 (bias/ruído calibrados para 625
         neurônios, não para o tamanho novo).
      3. Naming/config: se for (1), `SEED_PATTERN` vira um mapa nome→padrão em
         vez de constante única — mudança pequena em `config.py`, mas é
         mudança de contrato de `ingest.py`, cabe em ADR mesmo assim.

### Sub-trilha: chuva ✅ L0→L1 extraída (19/09/2026), L2/L3 pendente

- [x] Levantamento de literatura — **rótulo existe, achado real, não
      inventado**: `cell_class == "hygrosensory"` (74 neurônios), `cell_type`
      `HRN_VP4`/`VP1d`/`VP5`/`VP1l`, bate exatamente com a nomenclatura VP1-5
      de Frank et al. 2017 / Enjin et al. 2016. Ver `04-regras-de-negocio.md`
      e `01-camada-de-dados.md`.
- [x] Extração — feita, `python tools/build_f7_circuits.py`. Varredura de
      saltos: 1 salto só alcança **2 descendentes** (cadeia fraca demais);
      **decisão do usuário (19/09/2026): usar 2 saltos** (5.638 nós, 107.226
      arestas, 41 descendentes), aceitando reabrir RN-01a.
- [x] RN-01a reaberta e quantificada — 373 neurônios `serotonin` (371 fora da
      fronteira motora, confiança média 0,47). **Decisão de como tratar
      pendente** (não resolvida — não inventar sinal sem base de literatura,
      ver RN-01a).
- [x] **RN-01a resolvida em 96% (21/09/2026), AD-18.** Investigado por
      `cell_class`: 318 são ORNs (colinérgicas, Yasuyama & Salvaterra 1999)
      e 40 são neurônios locais do lobo antenal lLN1/lLN2 (GABAérgicos,
      Schlegel et al. 2021, fonte independente do classificador) — mesmo
      padrão de artefato de RN-02, confirmado por confiança baixa do rótulo
      original (0,36-0,48) nesses dois grupos. Override em
      `graph.py::apply_serotonin_artifact_overrides` (+ corrigido em
      `topology.group_outputs_by_predicted_sign`, que também calculava sinal
      e não tinha o override — achado ao revisar todos os call sites).
      Restam 15 neurônios (inclui `CSD`, a serotonérgica real do lobo
      antenal) genuinamente sem sinal — 0,27% do subcircuito, não 6,6%. Ver
      RN-01a em `04-regras-de-negocio.md`, 28/28 testes passam.
- [x] **RN-09 aplicada ao hygro, sem precisar recalibrar (21/09/2026).**
      `tools/hygro_calibration_check.py`, mesmo desenho pareado do
      `bristle`: 28/41 descendentes com caminho excitatório, 13/41
      inibitório, N=30 sementes, valores atuais de `config.py` — grupo
      excitatório diff média=139,90 (p≈0), inibitório diff média=29,03
      (p≈0). Rede não fica silenciosa em 5.638 neurônios com os parâmetros
      calibrados pro ocelar (625).
- [x] **Sensor no plugin — implementado (21/09/2026).** `ControlLoop`
      lê `bee.getWorld().hasStorm()` a cada troca (sinal de NÍVEL, igual
      `touch_proximity` — não precisou de heurística nenhuma, diferente do
      `touch_contact`, que precisou por falta de evento nativo). Campo
      `raining` novo no protocolo (`BridgeClient`/`server.py`).
- [x] **Terceiro `Engine` no `SimulationServer` (21/09/2026), mesmo padrão
      do `bristle` (AD-17).** `hygro_connectome` opcional, default `None`
      (compatível com quem só usa ocelar/bristle). `raining` estimula a
      semente higrossensorial com `SENSOR_RAIN_AMPLITUDE` (mesmo valor já
      validado em `tools/hygro_calibration_check.py`). 30/30 testes Python
      passam; validado também contra o container Docker real (não só os
      testes automatizados) — `raining=true` sustentado saturou
      `hygrotaxis` em 0,977 (30/41 descendentes ativos), `raining=false`
      ficou em 0,213 (só bias/ruído, RN-09) — checagem de manipulação limpa.
- [x] **Decoder motor próprio — `hygro_motor.py` (21/09/2026).** Sem
      curadoria RN-08 equivalente ainda (nenhuma leitura de literatura/BANC
      feita pros 41 tipos do hygro) — só o canal `hygrotaxis`, mesmo
      mecanismo que gerou `phototaxis` pro ocelar ANTES de RN-08 existir
      (`topology.group_outputs_by_predicted_sign`, sem depender de saber o
      que cada tipo "significa"). Inicialmente telemetria só; ver abaixo —
      virou controle real no mesmo dia.
- [x] **Visualização (21/09/2026), pedido do usuário.** `ActivityVisualizer`
      ganhou suporte a múltiplos circuitos — `grooming` (bristle) tinha cor
      própria nunca implementada até agora (só efeito físico + número no
      HUD), `hygrotaxis` (hygro, azul-petróleo) ganhou junto. `LiveHud`
      ganhou uma 5ª linha (`hygrotaxis`). Compila limpo, jar copiado —
      **ainda sem confirmação visual em servidor real**, só validado via
      Docker/testes automatizados.
- [x] **Redesenho da paleta — 1 cor por circuito, não por canal (21/09/2026),
      pedido do usuário.** A primeira versão dava 1 cor pra cada um dos 8
      grupos por prefixo do ocelar (RN-08, telemetria sem curadoria) — 10
      cores só ali, poluído e não escalável. Trocado por
      `CircuitVisual(cor, canais[])`, uma entrada por circuito: ocelar
      (amarelo, canais `phototaxis`+`yaw_steering`), bristle (marrom,
      `grooming`), hygro (azul-petróleo, `hygrotaxis`) — os grupos brutos
      por prefixo e `conn_*` saíram da visualização (continuam disponíveis
      via `/flywirebee mute|stimulate` e log, só não viram partícula).
      Quantidade de partículas = maior |valor| entre os canais do circuito
      (decisão visual, não um sinal novo — não realimenta `MotorMapping`).
      Escalável: circuito novo = uma linha em `ActivityVisualizer.CIRCUITS`.
      Compila limpo, jar copiado.
- [x] **✅ Confirmado em servidor real, 21/09/2026 (usuário).** Roteiro
      completo (`/flywirebee give` → `control start` → partículas amarelas
      já visíveis, painel de 5 linhas no canto superior direito, `/weather
      rain` pra hygro, encostar em obstáculo pra bristle) — "tudo aconteceu
      como o roteiro alegou". Log do servidor confirma a checagem de
      manipulação do `hygro` ao vivo: `raining=true` sustentado manteve
      `hygrotaxis` saturado (0,986–0,994); `/weather clear` (20:18:39) →
      `raining=false` no tick seguinte → `hygrotaxis` caiu imediatamente pra
      0,149 e seguiu oscilando em torno de 0 (ruído de fundo, -0,44 a
      +0,25) — mesmo padrão já validado fora do jogo
      (`tools/hygro_calibration_check.py`). `grooming` variou de 0,2 a 0,99
      em resposta a `touch_contact` real, e a recuperação mecânica (F7,
      polimento) disparou com componente horizontal não-nulo várias vezes
      no log (ex.: `0.988,-0.0,-0.156`) — evidência incidental de que o
      polimento do escape está mecanicamente ativo, embora a suavidade
      visual especificamente não tenha sido o foco deste roteiro.
- [x] **`hygrotaxis` vira controle real — "buscar abrigo" (21/09/2026),
      decisão do usuário.** Pré-requisito notado antes de tentar a lesão:
      diferente da luz/toque, `hygro` roda em `Engine` separado (AD-17) sem
      nenhum canal ligado a `MotorMapping.java` ainda — uma lesão medindo
      `path_length` com esse estado seria nula por construção (não por
      ausência de efeito). Usuário decidiu o comportamento e propôs a
      correção biológica: **diferente do pouso calmo do `grooming`**, uma
      mosca de verdade voaria MAIS RÁPIDO até um abrigo quando começa a
      chover, não devagar. Implementado: `hygrotaxis` >
      `HYGROTAXIS_THRESHOLD` (0,8 — margem bem mais folgada que a do
      `GROOMING_THRESHOLD` original, baseline até ~0,5 vs. chuva saturando
      acima de 0,98, medido em `tools/hygro_calibration_check.py` e ao vivo
      no roteiro acima) faz a abelha voar em velocidade MÁXIMA
      (`MAX_SPEED_BLOCKS_PER_TICK`) na direção comandada enquanto mergulha
      pro chão (`SHELTER_DIVE_DESCENT_BLOCKS_PER_TICK=0,3`, mais rápido que
      o pouso do grooming); ao tocar o chão, para — mesmo estado final do
      grooming, caminho até lá diferente (`MotorMapping.toVelocity`,
      `isSeekingShelterActive`). Gate de "pouso intencional" do sistema de
      recuperação mecânica de obstáculo (F7, polimento) estendido pra
      incluir busca de abrigo, senão o sistema empurraria a abelha de volta
      pro ar achando que ela está presa. Compila limpo, jar copiado —
      **ainda sem teste em servidor real**.
- [x] **Ferramenta de lesão implementada — `HygroLesionExperiment.java` +
      `/flywirebee hygrolesion [trials] [segundos] [x y z]` (21/09/2026).**
      Mesmo desenho estatístico e mesmo formato de CSV do
      `TouchLesionExperiment` (bristle) e `LesionExperiment` (F4) —
      `sim/tools/lesion_analysis.py` reaproveitado sem mudar nada, só
      apontando pro `hygro_lesion_experiment.csv` novo.
      `ControlLoop.setHygroLesioned` mascara `raining` (sempre `false`
      quando lesionado) do mesmo jeito que `setTouchLesioned`/`setLesioned`
      já mascaravam os deles. Diferente do toque (evento, precisa de
      obstáculo perto), chuva é ambiente como a luz — qualquer origem
      serve, DESDE que esteja chovendo de verdade no mundo (comando avisa
      se `World#hasStorm()=false` ao iniciar). Direção esperada: mesma do
      `bristle` — normal (chuva real) deveria ter `path_length` MENOR
      (mergulha e para), mascarado MAIOR (nunca para). Compila limpo, jar
      copiado — **ainda não rodado em servidor real**.
- [x] **🐛 Bug real encontrado e corrigido no primeiro teste em servidor
      real (21/09/2026) — abelha morreu afogada.** Usuário testou `/weather
      rain` antes de rodar a lesão: a abelha mergulhou sobre um lago e
      **nunca parou** — a checagem usava só `bee.isOnGround()`, e água não
      conta como chão pra essa API, então o mergulho
      (`SHELTER_DIVE_DESCENT_BLOCKS_PER_TICK`) continuou empurrando pra
      baixo até ela se afogar/sufocar (dano padrão do Minecraft pra mob
      não-aquático submerso). Mesma categoria do achado do "obstáculo
      lateral" (F7): mecanismo pensado só pra um tipo de terreno (bloco
      sólido) não cobria outro (água). **Corrigido:** `Entity#isInWater()`
      tratado igual a `onGround` pra fins de "parar de descer"
      (`MotorMapping.toVelocity` ganhou parâmetro `inWater`; gate de "pouso
      intencional" da recuperação de obstáculo em `ControlLoop` também
      atualizado). Compila limpo, jar copiado, servidor reiniciado.
      **Incerteza registrada, não resolvida:** o fix impede o mergulho
      contínuo, mas não foi testado se, parada dentro d'água, ela recupera
      sozinha ou afunda aos poucos por física passiva do jogo — se
      persistir, precisará de um passo ativo de subida ao detectar água,
      não implementado ainda. **Lesão da chuva adiada até confirmar que
      este fix resolveu, antes de coletar dados.**
- [x] **🐛 Segundo bug real, mesmo teste (21/09/2026) — abelha morreu
      afogada DE NOVO, com o fix do primeiro bug já aplicado.** Parada na
      água, ela começou a "tiquetaquear": virar rápido e tentar mergulhar
      de novo repetidamente, até morrer de novo. Usuário testou também
      longe de qualquer lago, em chão sólido: mesmo sintoma —
      "se arrastando no chão voando, dando tique, virando de um lado e
      outro" — confirmando que não é específico de água. **Causa:**
      `bee.isOnGround()`/`isInWater()` não são estáveis tick a tick (física
      de boiar/assentar do próprio jogo, mais o fato de serem lidos numa
      thread diferente da que move a abelha, RN-06) — cada vez que uma
      leitura de UM tick só dizia "não chegou", o mergulho de velocidade
      máxima reativava, com o corpo virando pra a direção de `heading`
      atual (que segue girando sozinha por causa do `yaw_steering` do
      ocelar, circuito independente). Parecia decisão nova a cada vez; era
      só o estado piscando. **Corrigido com uma trava "assentada"
      (`ControlLoop.settledForLanding`):** primeira vez que observa
      chão/água durante um episódio de grooming/abrigo ativo, trava; só
      destrava quando o PRÓPRIO canal desativa (grooming ou hygrotaxis cai
      abaixo do limiar), nunca por uma leitura instável de um tick.
      `MotorMapping.toVelocity` simplificado de volta pra um parâmetro só
      (`landed`, já estabilizado por quem chama) em vez dos dois brutos do
      fix anterior. Compila limpo, jar copiado, servidor reiniciado.
      **✅ Reteste confirmado pelo usuário (21/09/2026)** — chão sólido:
      mergulha, toca o chão, fica parada sem tique/giro. Perto de água:
      mesma coisa, sem afogar. "Ela fez o que foi descrito no roteiro."
      Fecha os dois bugs de física da busca de abrigo.
- [x] **Refinamento do conceito de abrigo (21/09/2026), pedido do usuário
      antes da lesão.** Até aqui, "abrigo" era só "tocou chão ou água em
      qualquer lugar" — usuário apontou que abrigo de verdade precisa de um
      teto (bloco sólido, pelo menos 1 bloco acima dela). Implementado
      `ShelterSensor.hasShelterAbove` — varre até 10 blocos acima da
      posição procurando um bloco opaco (`Material#isOccluding()`, não
      conta vidro/placa como cobertura parcial). Comportamento quando
      pousa sem cobertura: **continua procurando** (decisão do usuário,
      opção escolhida sobre "para em qualquer lugar" ou "não implementar
      ainda") — se não achou teto, segue se deslocando na direção
      comandada, sem mais pressão vertical pra baixo (evita reabrir o bug
      de afogamento: já tocou algo uma vez, não precisa "mergulhar" de
      novo). Só para de verdade quando `ShelterSensor` confirma cobertura.
      Gate do sistema de recuperação de obstáculo atualizado: só conta
      como "pouso intencional" quando achou abrigo DE VERDADE, não só por
      ter tocado algo — enquanto procurando, se ficar presa contra um
      obstáculo, o sistema de recuperação continua podendo ajudar.
      Compila limpo, jar copiado, servidor reiniciado.
- [x] **🐛 Terceiro bug real (22/09/2026), no mesmo teste do refinamento de
      abrigo — ela parou de mergulhar de vez, só voava reto sem nunca
      descer.** Log confirmou: `hygrotaxis` saturado (0,98+), `raining=true`
      o tempo todo, `onGround=false`, mas velocidade Y sempre `0.0` — nunca
      mergulhava. Causa: a trava do bug 2 (`settledForLanding`) era
      PERMANENTE — uma vez tocando chão/água (mesmo de raspão, ou empurrada
      pelo sistema de recuperação de obstáculo, visível no log) ficava
      `landed=true` pra sempre até o canal desativar, mesmo com ela de
      volta no ar. **Corrigido:** trocada a trava permanente por uma janela
      curta (`ticksSinceGroundOrWaterContact` / `LANDED_GRACE_TICKS=10`,
      ~0,5s, mesma ordem de `GROOMING_TRANSITION_GRACE_TICKS`) — absorve o
      flicker de 1 tick do bug 2 sem perder decolagem de verdade. Compila
      limpo, jar copiado, servidor reiniciado.
- [x] **🐛 Quarto bug real (22/09/2026) — buscava, mas voltava
      repetidamente pra quase o mesmo lugar.** Descia até o chão sem
      cobertura (certo), mas ao "decolar de novo em busca de cobertura"
      voltava rápido pra mesma posição, em ciclo. Causa: a busca no chão
      comandava velocidade vertical zero — física de "andar" do Minecraft
      tem atrito bem mais forte que a de voo, então o deslocamento real
      ficava pequeno; o sistema de recuperação de obstáculo (ainda ativo
      enquanto procura, por design) achava que ela tinha travado e dava um
      empurrão pra cima; já no ar, a busca reativava o mergulho usando
      `heading` (direção do ocelar, gira devagar via `yaw_steering`) —
      ainda apontando quase pro mesmo lugar, trazendo ela de volta.
      **Corrigido:** busca sem cobertura ganhou uma subida leve constante
      (`SEARCH_HOVER_BLOCKS_PER_TICK=0,08`) em vez de Y=0 — mantém física
      de voo (bem menos atrito), evita a interferência do sistema de
      recuperação, e faz ela perder contato com o chão periodicamente
      (reativando o mergulho em direções ligeiramente diferentes a cada
      ciclo, conforme `heading` gira) em vez de arrastar no mesmo lugar.
      Engenharia, não busca de caminho de verdade. Compila limpo, jar
      copiado, servidor reiniciado.
- [x] **Reteste (22/09/2026) — parcialmente positivo, achado misto.**
      Usuário confirmou o mais importante: **ela pousa de verdade só
      quando detecta abrigo real** ("aparentemente ela está pousando
      abaixo de um abrigo quando percebe que está sobre um abrigo") — o
      núcleo do comportamento funciona. Mas a busca "trancava" tentando
      subir degraus de 1 bloco de terreno (subia um pouco, caía de volta,
      repetia) — `SEARCH_HOVER_BLOCKS_PER_TICK=0,08` não ganhava altura
      rápido o bastante. **Recalibrado pra 0,15** (mesma magnitude já
      testada em `RECOVERY_BOOST_BLOCKS_PER_TICK` pra escalar obstáculo
      lateral — reaproveita escala já validada, não número novo).
      Log de diagnóstico (`getLightFromSky()` no instante exato da decisão
      de abrigo) adicionado nesta rodada mas ainda não precisou ser
      consultado — o "parar sem teto" relatado antes não se repetiu.
      Compila limpo, jar copiado, servidor reiniciado.
- [x] **🐛 Quinto bug real (22/09/2026) — parava fora de cobertura
      visível, confirmado pelo log de diagnóstico.** Reteste do hover
      recalibrado: usuário reportou ela parada no chão sem teto. Log
      mostrou dois eventos reais de "abrigo encontrado" na mesma sessão:
      `skylight=0` (cobertura de verdade) e `skylight=14` (quase o máximo
      — luz difundindo de uma sombra vizinha, não bloco real acima dela;
      o motor do jogo propaga luz lateralmente entre colunas). O limiar
      `< 15` (qualquer redução) era sensível demais a essa difusão.
      **Corrigido:** `ShelterSensor` agora exige `skylight <= 4`
      (`MAX_SKYLIGHT_UNDER_SHELTER`) — bloqueio substancial, não qualquer
      atenuação. Calibração provisória com só 2 pontos de dado (0 real,
      14 falso-positivo) — pode precisar de ajuste fino de novo. Compila
      limpo, jar copiado, servidor reiniciado.
- [x] **🐛 Sexto bug real (22/09/2026) — passou por árvores com cobertura
      sem parar.** Usuário perguntou se a ALTURA da árvore/construção
      importa — não deveria (ar não atenua luz no motor do jogo, só bloco
      atenua; uma folha lá no alto com ar livre até embaixo já abaixaria o
      skylight do mesmo jeito que cobertura baixa). Suspeita mais provável:
      copa de árvore é naturalmente esparsa (buracos entre blocos de
      folha) — checar só a coluna EXATA onde ela está fazia "passar batido"
      pelos buracos, mesmo visualmente debaixo da árvore. **Corrigido:**
      `ShelterSensor` agora checa a coluna dela mais as 4 vizinhas
      (padrão "mais", N/S/L/O) — conta como abrigo se qualquer uma tiver
      skylight baixo, cobrindo os buracos sem exigir alinhamento perfeito
      com uma folha específica. Compila limpo, jar copiado, servidor
      reiniciado. **✅ Reteste confirmado pelo usuário (22/09/2026)** —
      "testei e agora ela parou embaixo da árvore". Fecha a cadeia de seis
      bugs reais do comportamento de busca de abrigo (afogamento ×2,
      trava permanente, ciclo de retorno, limiar de luz frouxo demais,
      cobertura esparsa) — pronto pra lesão de verdade. Observação
      separada do usuário (não tratada, não bloqueante): ela "pula"
      subindo/descendo mesmo em terreno plano — consequência esperada do
      ciclo planeio→mergulho do bug 4 (nunca flutua indefinidamente, perde
      contato e remergulha periodicamente), registrado como pendência de
      polimento, mesma categoria do escape de obstáculo lateral.
- [x] **Polimento do ciclo planeio→mergulho (22/09/2026), pedido do
      usuário, depois da lesão fechar.** Toda re-descida durante a busca
      reativava o MESMO mergulho íngreme
      (`SHELTER_DIVE_DESCENT_BLOCKS_PER_TICK=0,3`) do primeiro mergulho
      urgente — daí o "pulo" visível mesmo em terreno plano. Diferenciado:
      `ControlLoop` ganhou `hasTouchedThisEpisode` (fica `true` pra sempre
      no episódio assim que toca chão/água a primeira vez, diferente da
      janela curta de `ticksSinceGroundOrWaterContact`) — primeiro
      mergulho do episódio continua rápido (urgência real pedida pelo
      usuário: "voaria com mais velocidade até um abrigo"), re-descidas
      durante a busca usam `SEARCH_REDESCENT_BLOCKS_PER_TICK=0,05`, bem
      mais suave. Reduz a amplitude do ciclo sem mudar o mecanismo.
      Compila limpo, jar copiado, servidor reiniciado — **reteste
      pendente**.
- [x] **Primeira rodada real (22/09/2026) — confundida, não conta.** 20
      trials, origem perto de árvore (necessário pro `ShelterSensor`
      achar cobertura). Mann-Whitney deu p=0,021 (direção certa: normal
      4,72 blocos vs. mascarado 11,74), mas log mostrou `grooming`
      saturado (~0,998) o experimento inteiro e `proximity=true` quase
      sempre — **perto de árvore também aciona `touch_proximity` do
      bristle**, e `MotorMapping` checa `grooming` antes de `hygrotaxis`,
      então essa rodada mediu majoritariamente o pouso do `grooming`, não
      a busca de abrigo do `hygro`. CSV preservado como
      `hygro_lesion_experiment_confounded_by_grooming.csv` — não usar
      pra citar resultado do hygro. **Corrigido:**
      `ControlLoop.setBristleSuppressedForExperiment` suprime o efeito do
      bristle na velocidade durante todo o experimento (circuito continua
      rodando de verdade, só o efeito no movimento é ignorado) — ligado
      automaticamente por `HygroLesionExperiment`. Compila limpo, jar
      copiado, servidor reiniciado.
- [x] **✅ CRITÉRIO DE SAÍDA ATINGIDO (22/09/2026) — segunda rodada, sem
      o confundidor do bristle.** 20 trials, mesma origem perto de árvore,
      chuva real ligada o experimento inteiro: `path_length` normal
      (chuva real) = 0,333±0,299 blocos vs. mascarado (sem chuva) =
      28,30±0,676 blocos (9 normais / 11 mascarados, sorteio
      desbalanceado como já aconteceu em outras rodadas). **Welch
      t-test p≈0,00000, Mann-Whitney U=0,0 p=0,00019 — separação
      perfeita, zero sobreposição entre os grupos.** Mais forte que
      qualquer lesão anterior deste projeto (ocelar p=0,0014, bristle
      p=0,0025) — faz sentido: `hygrotaxis` vira comportamento BINÁRIO
      (para completamente sob abrigo vs. voa normal ~28 blocos), não uma
      diferença de magnitude parcial como `phototaxis`. **Fecha a cadeia
      completa do `hygro`: sensor real → circuito → busca de abrigo →
      comportamento observável**, mesmo padrão do ocelar (F4) e do
      bristle (F7), depois de 6 bugs reais de física corrigidos e 1
      confundidor real (bristle/proximity) isolado. CSV:
      `hygro_lesion_experiment.csv` (a rodada confundida anterior ficou
      preservada como `hygro_lesion_experiment_confounded_by_grooming.csv`,
      não usar pra citar resultado).

### Sub-trilha: toque ✅ L0→L1 extraída (19/09/2026), L2/L3 pendente

- [x] Levantamento de literatura e decisão registrada — **cerdas
      mecanossensoriais** (`cell_sub_class` em `{"eye bristle", "head
      bristle"}`, 1.417 neurônios), não órgão de Johnston (`wind_gravity`/
      `auditory`, 874 neurônios, mesmo `cell_class == "mechanosensory"`, mas
      mede vento/som, não contato). Sensilas campaniformes de perna/asa não
      encontradas rotuladas separadamente nesta base (neurônios sensoriais de
      perna majoritariamente projetam pro cordão nervoso, fora do cérebro
      capturado pelo FlyWire). Ver `01-camada-de-dados.md`.
- [x] Extração — feita, `python tools/build_f7_circuits.py`. **1 salto**
      (mesmo padrão do ocelar, AD-06): 1.865 nós, 16.856 arestas, **110
      descendentes** — mais cobertura que os 92 do ocelar. Semente 90,8%
      acetilcolina, sem artefato sistemático tipo RN-02; 36 neurônios (2,5%)
      `serotonin` são ruído de classificador normal, não bloqueiam nada.
- [x] **Decisão do escopo de "toque" registrada (20/09/2026, usuário):**
      amplo — engloba contato com bloco, dano, e aproximação de mob, jogador
      ou objeto do jogo. Não é um evento Bukkit só, é uma FAMÍLIA de
      gatilhos.
- [x] **Sensor no plugin — implementado E validado (20/09/2026),
      `TouchSensor.java`.** Dois campos novos no protocolo (`touch_contact`
      borda, `touch_proximity` nível), mais `damage` que já existia — três
      sinais, não um booleano só. `touch_contact` usa heurística de
      deslocamento real vs. esperado (Bukkit não tem evento de colisão pra
      entidade comandada por código); `touch_proximity` usa
      `getNearbyEntities` num raio fixo. **✅ Testado em servidor real:**
      teste controlado (abelha presa num cubículo 4×4, paredes de 4 blocos)
      deu `touch_contact` quase contínuo por 2+ minutos; teleportada pra área
      aberta, 81s seguidos sem nenhum disparo — distingue contato real de
      voo livre. `touch_proximity` confirmado pelo usuário (longe=false,
      aproximar sem encostar=true). Limiares (`CONTACT_RATIO_THRESHOLD=0.5`,
      `PROXIMITY_RADIUS=3.0`) seguem sem calibração fina, mas o mecanismo
      funciona. Ver `plugin/README.md`, `docs/02-arquitetura.md`.
- [x] **Simulador integrado (20/09/2026).** `SimulationServer` ganhou um
      segundo `Engine` opcional (`bristle_connectome`, default `None` —
      compatível com quem só usa o ocelar, 26/26 testes passam).
      `damage`/`touch_contact`/`touch_proximity` combinam em OR e
      estimulam a semente do `bristle` com `SENSOR_TOUCH_AMPLITUDE` (mesmo
      valor de `tools/bristle_calibration_check.py`, já validado em RN-09).
      Resposta ganha `bristle_motor` (`grooming` + `conn_DN_*`,
      telemetria) e `bristle_active_dn`. `BristleMotorDecoder` é uma classe
      dedicada, não o `MotorDecoder` do ocelar reaproveitado — esse
      chamaria `topology.group_outputs_by_predicted_sign` sem `out_dir` e
      leria o `edges.parquet` errado (nids de cada circuito são espaços
      independentes, AD-17). Testado contra o container Docker real (não só
      testes automatizados): `grooming` saturou perto de 1,0 sob
      `touch_contact` sustentado.
- [x] **`grooming` passa a controlar a abelha (20/09/2026), decisão do
      usuário.** `MotorMapping.java` ganhou lógica de pouso: `grooming` >
      `GROOMING_THRESHOLD=0,5` faz a abelha ignorar `phototaxis`, descer
      (`LANDING_DESCENT_BLOCKS_PER_TICK`, vertical, zero horizontal) até
      tocar o chão, e ficar parada — "pousa e se limpa". Constantes
      provisórias, sem calibração. `LiveHud`/log periódico do
      `ControlLoop` mostram `grooming`/`onGround` pra acompanhar em
      servidor real. Compila limpo (`./gradlew build`). **Ainda sem teste
      em servidor real** — próximo passo antes da lesão.
- [x] **Bug real encontrado e corrigido no primeiro teste em servidor real
      (20/09/2026) — loop auto-sustentado.** Abelha pousou numa árvore e
      **nunca mais decolou**: `grooming` saturado em 0,998-0,999 por
      minutos seguidos, `touch_contact` disparando sem parar. Causa: o
      PRÓPRIO pouso virava "toque" — parada em cima de um bloco, qualquer
      tentativa de micro-mover (inclusive a descida do próprio pouso)
      esbarra na física, `touch_contact` dispara, realimenta `grooming`,
      que a mantém parada, que mantém o "toque" — loop fechado, sem saída.
      **Corrigido:** `ControlLoop` agora pausa `TouchSensor` inteiro
      (`touchSensor.reset()` em vez de `recordTick()`) sempre que
      `MotorMapping.isGroomingActive` já está no controle — só volta a
      detectar colisão depois que `grooming` soltar. `touch_proximity`/
      `damage` continuam ativos (são sinais externos genuínos, não
      autorreferentes — não precisavam da mesma pausa). Compila limpo,
      jar novo copiado — **precisa reiniciar o servidor pra valer**
      (mesma lição já registrada: trocar o jar no disco não afeta o
      processo rodando). Achado registrado com transparência, não
      escondido — ver `MotorMapping.isGroomingActive`, `ControlLoop.onTick`.
- [x] **Reteste em servidor real (20/09/2026) — correção funcionou pro
      loop principal, achou um segundo efeito mais sutil.** Depois de
      reiniciar com o jar corrigido: nenhum `touch_contact` disparou
      enquanto ela ficou parada só por `proximity` genuíno (usuário por
      perto) — assim que ele se afastou, `grooming` caiu e ela decolou
      sozinha. **Mas** numa área bem aberta (sem galho nenhum), ela ficou
      OSCILANDO — pousando e decolando em ciclo, sem se estabilizar em voo
      livre. Causa: toda vez que `grooming` cruza o limiar (subindo OU
      descendo), a velocidade comandada troca de direção bruscamente
      (voo horizontal ↔ descida vertical) — a abelha tem inércia física,
      não troca instantaneamente, e esse descompasso de UM tick parecia
      "toque" de novo, bem na hora da transição, empurrando `grooming` pra
      cima de novo antes de decair de verdade.
- [x] **Segunda correção (20/09/2026):** folga de
      `GROOMING_TRANSITION_GRACE_TICKS=10` (0,5s, provisório) pausando
      `TouchSensor` não só enquanto `grooming` está ativo, mas por um
      tempinho depois de QUALQUER troca de estado (entrando ou saindo).
      `ControlLoop` ganhou `wasGroomingActive`/`groomingGraceTicksLeft`.
      Compila limpo, jar copiado — **precisa reiniciar o servidor de
      novo, ainda sem reteste desta correção específica**.
- [x] **Reteste da segunda correção — confirmado (20/09/2026).** Depois de
      reiniciar de novo: voo livre estável em área aberta, `grooming`
      oscilando numa faixa saudável (~0,2-0,5) sem prender, `touch_contact`
      raro e isolado (não sustentado), e pouso correto só quando há toque
      genuíno (`proximity` real). **Confirmado visualmente pelo usuário**
      — "voando normal".
- [x] **Terceiro bug encontrado e corrigido (20/09/2026) — obstáculo
      lateral (morro/degrau de bloco).** Usuário reportou visualmente:
      abelha "voando de forma inercial" contra um bloco, claramente
      diferente do movimento de pouso. Log confirmou: `light` travado no
      mesmo valor por 40s seguidos (não deslocava nada de verdade) apesar
      de `vel` variando e `onGround` alternando; só 1 `touch_contact` nesse
      tempo todo — a folga de transição (segunda correção) estava,
      sem querer, quase sempre pausando a detecção, porque `grooming`
      cruzava o limiar repetidamente (rearmando a folga toda hora).
      **Causa raiz mais funda:** mesmo quando `grooming` reage, nem "voar"
      (phototaxis) nem "descer na vertical" (pouso) afastam a abelha de um
      obstáculo do LADO — nenhum dos dois movimentos resolve isso.
      **Corrigido com recuperação MECÂNICA, independente da decisão do
      circuito:** `ControlLoop` mede deslocamento real a cada
      `STUCK_CHECK_TICKS=40` (2s); se ficou abaixo de
      `STUCK_DISPLACEMENT_THRESHOLD_BLOCKS=0,3` e não é pouso intencional
      (`onGround` + `grooming` ativo ao mesmo tempo), aplica um empurrão
      pra cima (`RECOVERY_BOOST_BLOCKS_PER_TICK=0,15` por
      `RECOVERY_BOOST_TICKS=20`, 1s) tentando escalar o obstáculo. Log
      imediato quando dispara. Todas as constantes são estimativas de
      engenharia, não calibradas. Compila limpo, jar copiado — **precisa
      reiniciar o servidor, ainda sem reteste**.
- [x] **Reteste confirmado (20/09/2026) — funciona, visual rústico.**
      Log mostrou `recuperação: só 0.09 blocos em 40 ticks` disparando, e
      luz voltou a variar de forma contínua depois (0,60→0,53→0,47) —
      ela realmente se deslocou. **Usuário confirmou visualmente:** "esbarra
      e tenta se livrar, de maneira estranha mas consegue se livrar e sair
      voando". Funcional (não fica mais presa pra sempre), mas o empurrão é
      só vertical puro, sem ajustar rotação do corpo nem misturar
      componente horizontal pra longe do obstáculo — por isso o escape
      parece um solavanco, não um movimento suave. **Decisão do usuário:
      deixar como está por agora, registrado como pendência de polimento**
      (não bloqueia a lesão).
- [x] **Pendência de polimento resolvida (21/09/2026) — recuperação
      mecânica ganhou componente horizontal + giro do corpo.** Antes o
      empurrão era só vertical puro (`Vector(0, boost, 0)`) e a rotação do
      corpo usava `latestVelocity` (a próxima decisão do circuito), não a
      velocidade REALMENTE aplicada durante o empurrão — por isso o escape
      parecia um solavanco desalinhado. `ControlLoop` agora: (1) no
      instante em que trava, calcula a direção horizontal oposta a `heading`
      (a direção COMANDADA no momento do travamento — aproximação de "onde
      está o obstáculo", já que foi tentando ir por ali que travou) e some
      esse vetor ao empurrão vertical (`RECOVERY_BOOST_HORIZONTAL_BLOCKS_PER_TICK
      = 0,10`, estimativa de engenharia, não calibrada); (2) a rotação do
      corpo (`bee.setRotation`, mesmo mecanismo do achado de yaw/AD-16)
      passa a seguir a velocidade REALMENTE aplicada a cada tick
      (`velocityToApply`), não mais `latestVelocity` — durante o empurrão o
      corpo agora acompanha a direção de escape em vez de continuar
      apontando pra onde o circuito mandava antes de travar. Compila
      limpo (`./gradlew build`), jar copiado pra `mc-server/plugins/`.
      **Ainda sem reteste visual em servidor real** — próximo passo antes
      de considerar fechado, mesma disciplina das outras correções desta
      fase (não afirmar resultado sem o teste, ver `docs/00-visao-geral.md`).
- [x] **Experimento de lesão implementado (20/09/2026),
      `TouchLesionExperiment.java` + `/flywirebee touchlesion [trials]
      [segundos] [x y z]`.** Mesmo desenho estatístico e mesmo formato de
      CSV do `LesionExperiment` da F4 — `sim/tools/lesion_analysis.py`
      reaproveitado sem mudar nada, só apontando pro
      `touch_lesion_experiment.csv` novo. `ControlLoop.setTouchLesioned`
      mascara `damage`/`touch_contact`/`touch_proximity` (sempre `false`
      quando lesionado) do mesmo jeito que `setLesioned` já mascarava
      `light` — sensores continuam rodando de verdade, só a LEITURA
      enviada pra ponte é mascarada. **Diferença de desenho importante em
      relação à luz:** toque é orientado a evento, não ambiente — a origem
      do experimento PRECISA ficar perto de um obstáculo ou do jogador,
      senão nem o grupo normal nem o mascarado têm estímulo real pra
      medir (documentado no comando e na classe). Compila limpo, jar
      copiado — **ainda não rodado em servidor real**.
- [x] **Primeira rodada real (20/09/2026) — nulo, causa diagnosticada,
      não é falta de efeito.** 20 trials (`115,64,-282`, perto de
      obstáculo), 10s cada: `path_length` normal=11,55±0,51,
      mascarado=11,86±0,82 — direção certa (mascarado maior), mas Welch
      p=0,33 e Mann-Whitney p=0,39, bem longe de significativo.
      **Diagnóstico:** contei `grooming` acima de 0,5 por condição no log
      — mascarado já cruzava em **24% das amostras**, normal só um pouco
      mais (34,3%). Confirmado isolado (sem Minecraft, `Engine` direto,
      5 sementes): **sem estímulo nenhum**, `grooming` já fica em média
      0,41-0,44 e passa de 0,5 em 25-27% das amostras só de ruído/
      atividade espontânea (RN-09) — bate com o 24% do log real. **Com**
      estímulo sustentado, satura em ~0,998 (100% das amostras). O
      limiar de 0,5 estava perto demais do ruído de fundo, diluindo a
      comparação — não é evidência de que o circuito não responde a
      toque.
- [x] **Recalibrado `GROOMING_THRESHOLD` de 0,5 pra 0,8 (20/09/2026)** —
      bem acima do pico de ruído medido (0,71), bem abaixo da saturação
      real (0,998). Compila limpo, jar copiado.
- [x] **✅ CRITÉRIO DE SAÍDA ATINGIDO (20/09/2026) — segunda rodada, com o
      limiar recalibrado.** 20 trials, mesma origem (115, 64, -282):
      `path_length` normal=7,36±0,49 vs. mascarado=9,79±1,17 — direção
      certa (normal MENOR, ela pousa mais), efeito grande (~25%). **Welch
      t-test p=0,013, Mann-Whitney U p=0,0025 — os dois concordam**, mesmo
      padrão de rigor que validou o circuito ocelar na F4 (lá p=0,0014).
      N=15 normal / 5 lesionado (desbalanceado por sorteio aleatório, como
      já aconteceu na F4 original). **Isto fecha a validação do
      `bristle`**: sensor real → circuito → comportamento observável, a
      mesma cadeia completa que a luz já tinha.
- [x] **L2/L3 — RN-09 aplicada, sem precisar recalibrar (19/09/2026).**
      `graph.load`/`topology.group_outputs_by_predicted_sign` generalizados
      pra aceitar `out_dir`. Testado com os valores ATUAIS de `config.py`
      (calibrados só pro ocelar) — a rede `bristle` já dispara espontaneamente
      sem ajuste (semente é 90,8% excitatória, não tem a impossibilidade
      matemática que forçou RN-09 pro ocelar). Topologia: **109/110
      descendentes com caminho previsto excitatório** (quase sem risco de
      cancelamento por agregação, ao contrário do ocelar 29×63). Validação
      pareada (`tools/bristle_calibration_check.py`, N=30): t=568,5 e
      Wilcoxon, ambos **p≈0** — efeito ~18× baseline, sem saturação (23,8% da
      capacidade teórica). Ver RN-09 em `04-regras-de-negocio.md`.
- [x] **RN-08 equivalente — curadoria feita (20/09/2026).** Fonte: mesma
      tabela do BANC connectome de AD-15 (`banc_neck_functional_classes.csv`),
      obtida do repositório GitHub público do projeto BANC (Harvard Dataverse
      apresenta desafio anti-bot, mesma classe de bloqueio de AD-11).
      **6/60 tipos (9 neurônios) com comportamento publicado — todos
      `grooming`** (biologicamente coerente: cerdas de contato → reflexo de
      limpeza). 52/60 (97 neurônios) só com cluster de conectividade BANC
      (código bruto `DN_01`...`DN_17`, sem nome descritivo disponível na
      fonte — não inventado). 2/60 (4 neurônios) sem dado. Namiki/Cande 2018
      checado de novo, zero overlap com os tipos do bristle (nomenclatura de
      driver line, não bate com estes tipos; PDF suplementar confirmado de
      novo não confiável de interpretar). Ver RN-08 em
      `04-regras-de-negocio.md`, `sim/src/flywire_sim/bristle_motor.py`,
      `sim/tests/test_bristle_motor.py` (24/24 testes passam).
- [ ] **Falta pra fechar a fase real:** sensor de toque no plugin — usuário
      decidiu (20/09/2026) que "toque" engloba contato com bloco, dano,
      aproximação de mob/jogador/objeto (visão ampla, não só um evento
      Bukkit); precisa desenhar como isso vira sinal no simulador sem virar
      ruído constante — e validação por lesão em servidor real, mesmo padrão
      da F4.
- [ ] Validação — idem: lesão comparando estímulo de toque real vs.
      mascarado, mesmo padrão estatístico (Welch + Mann-Whitney) já usado em
      todos os experimentos desde a F4.

### Riscos a herdar, não redescobrir

Tudo isto já foi medido nesta sessão (F4/F6) e se aplica igual a qualquer
sensor novo — ver `CONVENCOES.md`, "Armadilhas conhecidas":

- Agregar canais de fontes diferentes sem checar se respondem ao mesmo
  estímulo dilui/cancela o efeito (3 ocorrências já registradas).
- IA nativa é a maior fonte de ruído não controlada — `goals off`
  (`CompetingGoals.java`) é obrigatório em qualquer experimento novo.
- Comparar rodadas de origens diferentes não é válido — sortear condição
  trial a trial dentro da MESMA rodada, MESMA origem, teleporte no mesmo
  comando que inicia a medição (não num comando manual anterior).
- Direção do viés de ruído não é previsível de um experimento pro outro —
  medir de novo em cada caso, não assumir.
- `String.format`/`printf` com `%f` em CSV: sempre `Locale.ROOT`.

**Critério de saída da etapa de planejamento:** ✅ atingido (19/09/2026) — F7
registrada com checklist e decisões pendentes explícitas.

**Critério de saída da etapa L0/L1 (extração):** ✅ atingido (19/09/2026) para
os dois sensores — AD-17 decidida (engines separados), semente real encontrada
nas anotações pros dois (`hygro` e `bristle`, nenhuma inventada), extraída e
validada com `python tools/build_f7_circuits.py`. Nenhum resultado negativo
precisou ser registrado desta vez — os dois rótulos existiam.

**Critério de saída da fase inteira:** por sensor, ou o circuito chega ao
mesmo padrão do ocelar — sinal RN-01/RN-02 resolvido, bias/ruído RN-09
calibrado, lesão com diferença estatisticamente mensurável — ou um resultado
negativo é registrado com a mesma transparência do dia/noite nulo da F6.
**`bristle` atingiu (21/09/2026); `hygro` atingiu (22/09/2026). F7 concluída
— os dois sensores novos fecharam o mesmo padrão do ocelar.**

**✅ `bristle` — CADEIA COMPLETA VALIDADA (19-21/09/2026): sensor real →
circuito → comportamento observável, mesmo padrão da luz.** L2/L3 (RN-09,
p≈0 N=30), curadoria RN-08 equivalente (6/60 tipos publicados, todos
`grooming`, 52/60 cluster de conectividade), sensor no plugin
(`touch_contact`/`touch_proximity`, testado cubículo fechado vs. área
aberta), integração no simulador (segundo `Engine`, testado contra o
container Docker real) e `grooming` controlando a abelha de verdade
(pousa/fica parada, `MotorMapping.java`) — tudo pronto e testado
individualmente. **Lesão em servidor real fechou a validação
(21/09/2026):** primeira rodada deu nulo (p=0,33/0,39) por limiar mal
calibrado (`GROOMING_THRESHOLD=0,5` cruzado por ruído espontâneo em 24-27%
das amostras, medido tanto no log real quanto isolado via `Engine`, sem
Minecraft); recalibrado pra 0,8; segunda rodada (20 trials, mesma origem):
`path_length` normal=7,36±0,49 vs. mascarado=9,79±1,17, **Welch p=0,013,
Mann-Whitney p=0,0025** — os dois concordam, direção certa (toque real faz
ela pousar mais, andar menos). Mesmo rigor que validou o ocelar na F4
(p=0,0014 lá).

**✅ `hygro` — CADEIA COMPLETA VALIDADA (19-22/09/2026): sensor real →
circuito → comportamento observável.** RN-01a resolvida em 96% (AD-18: 318
ORNs + 40 neurônios locais do lobo antenal, override por `cell_class` com
fonte independente do classificador), RN-09 validada sem recalibrar
(`tools/hygro_calibration_check.py`, p≈0 nos dois grupos de topologia),
sensor no plugin (`World#hasStorm()`), terceiro `Engine` no
`SimulationServer` (testado contra o container Docker real), canal
`hygrotaxis` (topologia de sinal) virando comportamento real — "buscar
abrigo" (decisão do usuário: foge da chuva rápido, não pousa devagar como
o grooming) — depois de **seis bugs reais de física corrigidos** (dois
episódios de afogamento, trava permanente vs. janela de tolerância, ciclo
de retorno ao mesmo lugar, limiar de luz frouxo demais, cobertura esparsa
de copa de árvore) e **um confundidor real isolado** (proximidade da
árvore acionando `grooming` do bristle, que tinha prioridade sobre
`hygrotaxis` em `MotorMapping` e mascarava o efeito). **Lesão em servidor
real fechou a validação (22/09/2026):** primeira rodada confundida (não
conta, CSV preservado como `..._confounded_by_grooming.csv`); segunda
rodada, com o bristle isolado do movimento
(`ControlLoop.setBristleSuppressedForExperiment`), 20 trials:
`path_length` normal=0,333±0,299 vs. mascarado=28,30±0,676 blocos, **Welch
p≈0,00000, Mann-Whitney U=0,0 p=0,00019** — separação perfeita, o
resultado mais forte de qualquer lesão deste projeto (ocelar p=0,0014,
bristle p=0,0025).

**Achado em jogo livre (22/09/2026) — mesmo confundidor, fora de
experimento.** Usuário observou: chuva ligada + porco próximo, abelha
ficou parada e estática. Mesma causa da rodada confundida — `grooming`
(acionado por `touch_proximity` do mob) tinha prioridade incondicional
sobre `hygrotaxis` em `MotorMapping.toVelocity`, mascarando a busca de
abrigo por completo fora do experimento também (a supressão da lesão só
vale dentro do experimento). **Decisão do usuário: `hygro` vence
`grooming`** quando os dois estão ativos — fugir da chuva é mais
urgente/vital que parar pra se limpar por um mob de passagem. Ordem de
checagem invertida em `MotorMapping.toVelocity` (hygro primeiro, grooming
depois). Compila limpo, jar copiado, servidor reiniciado.

**🐛 Bug real (22/09/2026) — sistema de recuperação de obstáculo não
disparava presa entre 3 blocos.** Usuário relatou: pousada sem cobertura,
travada por blocos nas duas laterais + na frente (retaguarda livre), nunca
decolou pra continuar buscando. Causa: a checagem de "travou" media
distância 3D total (`Location.distance()`); o planeio da busca
(`SEARCH_HOVER_BLOCKS_PER_TICK`) sozinho já produz deslocamento vertical
suficiente pra passar do limiar de 0,3 blocos, mascarando o fato de que
ela não progredia nada na horizontal. **Corrigido:** medição trocada pra
só X/Z (horizontal) — o que importa pra saber se está escapando de um
cercado lateral é progresso horizontal, não altitude. Mesma correção
beneficia o caso original de obstáculo do ocelar/bristle, não só o hygro.

**Grooming vira probabilístico — "desviar" ou "pousar e limpar" (22/09/2026),
pedido do usuário.** Hoje todo toque/proximidade acima do limiar sempre
virava pouso — mecanicamente repetitivo, sem dado químico real da mosca
pra justificar variação. Sorteio 50/50 a cada NOVO episódio de grooming
(mesmo instante da folga de transição já existente): metade pousa e limpa
(comportamento original, já validado por lesão — RN-08/RN-09 intocadas),
metade só desvia — empurrão breve puramente horizontal
(`DODGE_BOOST_HORIZONTAL_BLOCKS_PER_TICK=0,2`, ~1s, mesmo mecanismo do
sistema de recuperação de obstáculo) pra longe da direção atual, depois
`bristle_motor` é ignorado pro resto do episódio (mesma técnica da
supressão da lesão) e `phototaxis`/`hygro` retomam o controle normal.
Engenharia pra dar variedade comportamental, não achado biológico novo —
deixa explícito que é uma escolha de design, não uma medição. Compila
limpo, jar copiado, servidor reiniciado.

**🐛 Bug real (22/09/2026) — abelha flutuando parada no ar, longe de
qualquer cobertura.** Log confirmou: `hygrotaxis=0,99+`, `raining=true`,
`onGround=false` sustentado, `vel=0,0,0` o tempo todo — ela não estava
pousada em lugar nenhum, só flutuando imóvel. Causa: `shelterFoundThisEpisode`
travava PRA SEMPRE assim que achava cobertura uma vez (otimização
deliberada, "não recomputa depois de achar") — se esse achado veio de um
toque antigo (possivelmente até um falso-positivo passageiro do sensor) e
ela depois deixou de estar pousada de verdade, o código continuava achando
que ela estava abrigada e forçava velocidade zero pra sempre, mesmo no ar
e longe de qualquer teto. **Corrigido em duas camadas:** (1)
`ControlLoop` desfaz o achado de abrigo assim que a janela de tolerância
de "pousada" esgota — precisa pousar de novo E reconfirmar cobertura antes
de parar outra vez; (2) `MotorMapping.toVelocity` exige `sheltered && landed`
juntos pra parar (defesa extra, nunca parar sem estar REALMENTE tocando
chão/água no momento). Compila limpo, jar copiado, servidor reiniciado.

**🐛 Bug 7 real (22/09/2026) — regressão do fix do bug 6, mesmo dia.**
Reteste dos três achados: ela nunca mais parava em cobertura nenhuma,
"continua voando pelo mapa". Causa: o fix do bug 6 usava a MESMA janela
curta (`LANDED_GRACE_TICKS`, 0,5s) pra desfazer um abrigo já achado — um
flicker normal de `onGround` (bug 2, mesma instabilidade de sempre, agora
batendo numa abelha PARADA embaixo de galho/folha em vez de em voo)
bastava passar de 0,5s pra derrubar um abrigo genuíno, mandando ela voar
de novo. **Corrigido separando as duas janelas:** `LANDED_GRACE_TICKS`
(0,5s) continua decidindo mergulhar vs. procurar; desistir de abrigo já
achado usa `SHELTER_ABANDON_TICKS` novo (60 ticks, ~3s) — bem mais
tolerante a flicker, só derruba por ausência sustentada de verdade (o
cenário do bug 6 tinha minutos flutuando, 3s ainda pega isso sem sobrar
sensível a meio segundo de ruído). `MotorMapping.toVelocity` voltou a
checar só `sheltered` pra parar (sem exigir `landed` junto, que era a
causa direta da regressão). Compila limpo, jar copiado, servidor
reiniciado.

**Polimento do sistema de recuperação de obstáculo (22/09/2026), pedido do
usuário — "vale a pena polir e afinar a questão dela travar entre
blocos".** Detecção recalibrada de `STUCK_CHECK_TICKS=40` (2s) pra `20`
(1s) — barra de detecção continua baixa (0,3 blocos), não deveria gerar
falso positivo em voo livre normal. Empurrão recalibrado de
`RECOVERY_BOOST_TICKS=20` (1s) pra `30` (1,5s) e
`RECOVERY_BOOST_HORIZONTAL_BLOCKS_PER_TICK` de `0,10` pra `0,15` — mais
decisivo, menos ciclos de detecção+empurrão em sequência pra escapar de
verdade. Estimativas de engenharia, não calibradas. Compila limpo, jar
copiado, servidor reiniciado.

**🐛 Bug 8 real (23/09/2026) — abelha "sheltered" a céu aberto de verdade.**
Chuva ligada, ela simplesmente não buscava cobertura. Log de diagnóstico
mostrou "abrigo encontrado — skylight=15" (céu TOTALMENTE aberto na
posição dela) três vezes seguidas — o fix do bug 6 (checar coluna + 4
vizinhas, contando QUALQUER uma) foi longe demais: bastava uma coluna
VIZINHA (1 bloco de distância) estar coberta pra ela se considerar
abrigada, mesmo exposta de verdade. **Corrigido exigindo MAIORIA:**
`ShelterSensor.MIN_COVERED_COLUMNS=3` de 5 (centro + 4 vizinhas), não
qualquer uma — tolera 1-2 buracos de copa esparsa (problema original)
sem deixar 1 sombra vizinha isolada contar como abrigo (problema novo).
Compila limpo, jar copiado, servidor reiniciado.

**🐛 Bug 9 real (23/09/2026) — a métrica inteira estava errada, não só o
limiar.** Usuário perguntou: "ela considera folhas de qualquer árvore como
cobertura?? Ou seja a chuva não pega nela?" — pergunta certeira. Confirmado
por pesquisa: **no Minecraft, chuva atravessa folhas** (mecânica real do
jogo desde uma atualização — "goteja" através de copas). `getLightFromSky()`
(usado nas três tentativas anteriores) mede LUZ, não chuva — folha reduz
luz (dá sombra) mas nunca bloqueou chuva de verdade. Não era questão de
limiar nem raio de varredura (bugs 6/8) — era a métrica errada desde o
início pra qualquer bioma com árvore. **Corrigido usando a métrica exata
que o próprio Minecraft usa internamente** pra decidir onde chove
(ex.: extinguir mobs em chamas): `World#getHighestBlockYAt(x, z,
HeightMap.MOTION_BLOCKING_NO_LEAVES)` — heightmap dedicado que já exclui
folhas corretamente. Abrigo = algum bloco sólido não-folha em algum lugar
acima dela na coluna, não importa a altura — sem varredura manual, sem
proxy de luz, sem checagem de vizinhas (RESOLVE os bugs 6 e 8 de raiz, não
só sintoma). `ShelterSensor.java` reescrito do zero, bem mais simples.
Compila limpo, jar copiado, servidor reiniciado — **reteste pendente**.
Roteiro sugerido: perto de árvore (folha só) NÃO deveria parar mais;
perto de telhado/bloco sólido real deveria continuar parando.

**✅ Confirmado pelo usuário (23/09/2026)** — testou perto de árvore (folha
só) e o comportamento bateu com o esperado (chuva atravessa folha de
verdade agora).

**Busca guiada (23/09/2026), pedido do usuário depois de um teste
controlado.** Usuário construiu um cubo com teto aberto + um mini-telhado
num canto, ativou chuva: ela ficou "indo e voltando" sem se aproximar do
canto coberto, e depois saiu voando pra fora do cubo mesmo chovendo.
Diagnóstico: a busca só seguia `heading` (circuito ocelar via
`yaw_steering`, sem relação nenhuma com onde está o abrigo) — achar um
canto pequeno por sorte é raro. **Implementado:**
`ShelterSensor.findNearbyShelterDirection` sonda 8 direções (N/NE/L/SE/S/
SO/O/NO) a `SHELTER_LOOK_AHEAD_BLOCKS=4` blocos, usando o mesmo heightmap
`MOTION_BLOCKING_NO_LEAVES` do bug 9; se achar cobertura numa direção,
`MotorMapping.toVelocity` mira direto nela em vez de `heading`. Não é
busca de caminho de verdade (sem A*/desvio de obstáculo) — só um aceno
simples quando o abrigo já está por perto. Compila limpo, jar copiado,
servidor reiniciado com desligamento gracioso configurado (pipe stdin,
`server_stdin.fifo`) — **reteste pendente**. Segundo achado do mesmo teste
(ela saindo do cubo via sistema de recuperação de obstáculo) registrado
mas não tratado ainda — decisão do usuário foi focar na busca guiada
primeiro.

**Nota operacional (23/09/2026):** até aqui todo redeploy usava
`Stop-Process -Force` pra derrubar o servidor antes de subir o jar novo —
isso não dá tempo do Minecraft salvar o mundo, então abelha/jogador
podiam voltar pra uma posição de alguns minutos atrás (o último autosave)
em vez do estado mais recente. Corrigido: servidor agora roda com stdin
ligado a um pipe nomeado (`tail -f server_stdin.fifo | java -jar ...`),
permitindo `echo "stop" > server_stdin.fifo` pra desligamento gracioso
(salva o mundo antes de sair) em vez de kill forçado daqui pra frente.

---

## F8 — Multi-sensor v3: órgão de Johnston (vento/som) 🔶 integrado ponta a ponta (sensor+simulador+visualização) — falta validação por lesão

Continuação natural do F7 (chuva, toque), pedido do usuário (23/09/2026)
depois de compartilhar referências novas (BANC v888, MaleCNS v1.0, e
papers incluindo Schiff et al. 1962 sobre resposta de fuga a "looming"
visual). Duas direções possíveis — **escolhido o órgão de Johnston**
(vento/som) por já ter dado real e rotulado neste conectoma, mesmo padrão
de continuidade do F7. A direção de fuga a looming visual (olho composto/
lobo óptico) ficou registrada como candidata futura, território novo
nunca tocado neste projeto (só o ocelar foi usado até aqui).

- [x] **Levantamento de literatura — achado real, não inventado.** 874
      neurônios `cell_sub_class` em {"wind_gravity", "auditory"} (`super_class
      =="sensory"`, `nerve=="AN"` — nervo antenal), nomenclatura JO-* bate
      exatamente com Kamikouchi et al. 2009/Yorozu et al. 2009 (duas zonas
      funcionais: JO-A/JO-B para som/canção de corte, JO-C/JO-E para vento/
      gravidade via deflexão da arista). Mesmos 874 neurônios já
      identificados e excluídos de propósito da semente do `bristle` na F7
      (`build_f7_circuits.py`, "não confundir com órgão de Johnston").
      **Cuidado no padrão de seleção:** prefixo `cell_type` "JO-" sozinho
      pega 1.103 neurônios, não 874 — inclui um terceiro grupo
      (`cell_sub_class=="grooming"` ou vazio) que não é vento/som. Usado
      `wind_gravity|auditory` (regex OR via `select_seed`), não o prefixo.
- [x] **Extração — `tools/build_f8_circuit.py`, 1 salto.** 1.740 nós,
      18.688 arestas, 136 descendentes — mais que os 110 do bristle e os
      92 do ocelar. 2 saltos deu erro técnico (`IntCastingNaNError`, não
      investigado, irrelevante já que 1 salto basta, mesmo padrão do
      bristle). Escreve em `data/processed/johnston/`.
- [x] **🐛 Erro real cometido e corrigido na mesma sessão (23/09/2026) —
      quase corrompeu o circuito ocelar canônico.** Primeiro teste de
      extração chamou `ingest.build()` sem `out_dir` explícito — por
      padrão isso escreve em `data/processed/` (o ocelar da v1!),
      sobrescrevendo `nodes.parquet`/`edges.parquet`/`manifest.json` reais
      com o dado de teste do johnston. Detectado na hora (manifesto
      mostrando `"circuit": "johnston_test_h1"` onde devia ser
      `"ocellar"`), restaurado rodando `ingest.build()` sem argumentos
      (default = ocelar) e confirmado contra os números documentados no
      `CLAUDE.md` (625 nós, 2.981 arestas) + 30/30 testes. Lição registrada
      em RN-01a: `out_dir=None` tem default silencioso pro ocelar — sempre
      passar `out_dir` explícito em qualquer teste exploratório de
      subcircuito novo.
- [x] **RN-01a/AD-19 — 77/874 neurônios "serotonin" da semente eram
      artefato de classificador.** Órgão de Johnston é colinérgico
      (Kitamoto et al. 1995; Yasuyama & Salvaterra 1999) — ver RN-01a em
      `04-regras-de-negocio.md` pro relato completo. Override implementado,
      32/32 testes passam.
- [x] **RN-09 aplicada, sem precisar recalibrar.**
      `tools/johnston_calibration_check.py`: 135/136 descendentes com
      caminho previsto excitatório, N=30 sementes, grupo excitatório diff
      média=433,87 (Welch/Wilcoxon p≈0) — efeito bem mais forte que
      bristle/hygro, sem precisar de sweep de `BIAS_CURRENT`/`NOISE_STD`
      novo. **Fecha L2/L3.**
- [x] **Sensor no plugin — decidido e implementado (23/09/2026).** Usuário
      trouxe achado biológico decisivo: Eberl, Hardy & Kernan (2000)
      mostram que toque e som compartilham o mesmo mecanismo de
      transdução em *Drosophila* — mas toque com objeto pequeno/folha é
      evolutivamente inofensivo e não dispara fuga. Desenho: sensor de
      "alarme" deliberadamente mais restrito que `touch_proximity`, só
      dois gatilhos que uma mosca reconheceria como ameaça:
      `alarm_explosion` (borda, `EntityExplodeEvent`/`BlockExplodeEvent`
      num raio de 16 blocos) e `alarm_hostile_mob` (nível, `Monster` do
      Bukkit — não qualquer `LivingEntity` — num raio de 8 blocos, maior
      que o do toque porque som viaja mais longe). `AlarmSensor.java`
      novo, registrado em `FlywireBeePlugin`.
- [x] **Integração no simulador — quarto `Engine`, mesmo padrão do
      bristle/hygro.** `SimulationServer` ganhou `johnston_connectome`
      opcional, `johnston_motor.py` (canal `startle`, mesmo mecanismo de
      topologia de sinal). Testado contra o container Docker real:
      `alarm_hostile_mob=true` sustentado → `startle=0,973` (quase
      saturado, 55 descendentes ativos); `false` → `-0,288` (ruído de
      fundo) — checagem de manipulação limpa. 34/34 testes Python passam.
- [x] **Visualização** — `ActivityVisualizer` ganhou o circuito `johnston`
      (vermelho, canal `startle`) e `LiveHud` uma 6ª linha, mesmo padrão
      escalável do F7 (uma entrada nova, não uma paleta nova).
- [x] **Terceiro gatilho — som ambiente, não só ameaça (24/09/2026).**
      Usuário testou perto de jukebox tocando disco: `startle` só no ruído
      de fundo (-0,3 a +0,3), porque os dois gatilhos originais cobrem só
      AMEAÇA. Pedido: som ambiente também deveria contar, mais fiel ao
      órgão de Johnston responder a som em geral. `AlarmSensor.isMusicNearby`
      varre um cubo de blocos (raio 6) procurando `Jukebox#isPlaying()` —
      sem evento Bukkit de "tocando agora" nem busca de bloco por
      proximidade (diferente de entidade). Campo `sound_music` novo,
      combina em OR com os outros dois. Compila limpo, testes passam
      (34/34), jar copiado, servidor reiniciado (desligamento gracioso).
      **✅ Confirmado em servidor real (25/09/2026)** — usuário colocou
      jukebox tocando, `startle` subiu beirando 1 (quase saturado, mesma
      faixa medida isolado contra o Docker). Sensor de som ambiente
      funcionando ponta a ponta.
- [ ] **Validação por lesão em servidor real** — pendente, mesmo padrão
      do bristle/hygro (`alarm_hostile_mob` mascarado/real sorteado trial
      a trial). `startle` continua telemetria pura, não entra em
      `MotorMapping.java` ainda.

---

## F9 — Circuito de fuga por looming (checklist de 7 itens, 24/09/2026) 🔶 comportamento real implantado, falta reteste em jogo + lesão

**Contexto:** usuário pediu prosseguir por um checklist de 7 sistemas
comportamentais novos (medo/fuga, ponto cego, tato+audição, paladar/fome/olfato,
calor/água/atrativo/sono/clima, memória/integração de trajetórias, corte/agressividade),
citando as referências que ele já tinha passado (Schiff et al. 1962 sobre resposta a
looming, Eberl/Hardy/Kernan 2000, BANC v888, MaleCNS v1.0). **Tato e audição já estava
feito** (bristle+johnston, F7/F8). Levantamento rápido do conectoma anotado mostrou:
olfato (2.282 neurônios `cell_class=="olfactory"`) e paladar (334, `gustatory`) são
extensão natural com dado já rotulado; memória/integração de trajetórias e corte/
agressividade são circuitos centrais grandes sem semente pequena óbvia; "ponto cego"
ficou sem definição clara do usuário. Usuário escolheu **medo/fuga (circuito de looming
visual)** como próximo item, via `AskUserQuestion`.

**Semente (achado real, não inventado):** 318 neurônios `cell_type` em {`LC4` (104),
`LPLC2` (210), `DNp01` (2, Giant Fiber), `DNp02` (2)} — os detectores de looming
clássicos da literatura de fuga visual em *Drosophila* (Ache et al. 2019; von Reyn et
al. 2017; de Vries & Clandinin 2012), ligados ao Schiff et al. 1962 que o usuário já
tinha mandado. Todos colinérgicos (`top_nt=="acetylcholine"`) — sem artefato de
serotonina tipo RN-01a aqui. Confirmado neste subcircuito extraído: aresta sináptica
DIRETA LC4/LPLC2→DNp01/DNp02 com syn forte (até 962 agregadas), batendo com a
identificação de Ache et al. 2019 via conectômica EM.

**Diferença estrutural em relação a bristle/hygro/johnston (AD-20):** a semente não é
`super_class=="sensory"` (é `visual_projection`+`descending`) — `Connectome.sensory`
ficaria vazio, sem onde `Engine.stimulate()` injetar corrente. `ingest.build` ganhou o
parâmetro `sensory_cell_types` pra marcar role="sensory" por identidade de `cell_type`
quando o circuito pede (default `None` preserva os outros 4 circuitos). Ver RN-04 em
`docs/04-regras-de-negocio.md`.

- [x] **Extração** (`sim/tools/build_f9_circuit.py`) — semente 318, `hops=1`,
      `sensory_cell_types={"LC4","LPLC2"}` → 768 nós (314 sensory, 423
      interneuron, 31 output), 10.861 arestas, 179.312 sinapses. Escrito em
      `data/processed/escape/`, canônico ocelar confirmado intacto depois.
- [x] **RN-04/AD-20** — mecanismo `sensory_cell_types` generalizado em
      `ingest.build`, testado (sintético + dado real).
- [x] **Canal motor `escape_drive`** (`sim/src/flywire_sim/escape_motor.py`) —
      terceira forma de curadoria do projeto (identidade celular direto da
      literatura + conectividade EM confirmada, não BFS de sinal nem cluster
      BANC): lê só `DNp01`+`DNp02`, não os outros 29 descendentes alcançados
      em 1 salto (vias paralelas não confirmadas contra looming
      especificamente — não agregar sem checar identidade). Ver RN-08 em
      `docs/04-regras-de-negocio.md`.
- [x] **RN-09** (`sim/tools/escape_calibration_check.py`) — estimular
      LC4+LPLC2 muda a atividade de DNp01+DNp02: diff média=63,07,
      desvio=1,31, t=258,3, p≈0,00000, N=30 sementes. Efeito grande e
      extremamente consistente (bem mais lopsided que hygro/johnston, que já
      eram bem assimétricos).
- [x] **Integração no bridge** (`server.py`) — quinto `Engine` opcional
      (`escape_connectome`), campo `looming_threat` no protocolo,
      `escape_motor`/`escape_active_dn` na resposta. 40/40 testes Python
      passando.
- [x] **Sensor no plugin (`LoomingSensor.java`, 24/09/2026)** — decisão do
      usuário via `AskUserQuestion`: distância até mob hostil/jogador mais
      próximo caindo rápido entre ticks (proxy de engenharia pra taxa de
      expansão angular — Bukkit não expõe campo visual/tamanho angular de
      entidade). Mesmo escopo de ameaça do `alarm_hostile_mob`
      (`Monster`/`Player`, não qualquer `LivingEntity`). Limitação conhecida
      e documentada, não corrigida: rastreia a AMEAÇA MAIS PRÓXIMA a cada
      tick, não uma entidade específica — troca de alvo mais próximo entre
      ticks pode gerar falso positivo. Compila limpo, Docker reconstruído (5
      engines confirmados no log), servidor reiniciado com desligamento
      gracioso — **reteste em jogo pendente**.
- [x] **Visualização** (`ActivityVisualizer`/`LiveHud`) — cor própria
      (violeta) no `CIRCUITS[]`, linha `escape_drive` no HUD.
- [x] **`escape_drive` vira comportamento real (25/09/2026), PRIORIDADE
      MÁXIMA — decisão do usuário.** Quando `escape_drive > ESCAPE_THRESHOLD`
      (`MotorMapping.java`), a abelha ignora hygro/grooming/phototaxis e voa
      pra longe da ameaça mais próxima (`LoomingSensor.
      fleeDirectionAwayFromNearestThreat`) numa velocidade maior que o voo
      normal (`ESCAPE_SPEED_BLOCKS_PER_TICK=0,45` vs `MAX_SPEED=0,3`) mais
      um leve componente vertical pra cima. Constantes provisórias, não
      calibradas — mesma disciplina de `GROOMING_THRESHOLD`/
      `HYGROTAXIS_THRESHOLD` quando entraram. Compilado, jar deployado,
      **reteste em jogo pendente**.
- [x] **Balão de texto acima da abelha (`StatusLabel.java`, 25/09/2026,
      pedido do usuário).** `ArmorStand` invisível/marcador seguindo a
      abelha, nametag em português explicando qual circuito REAL está no
      controle agora (mesma ordem de prioridade de `MotorMapping`, citando o
      canal entre parênteses) — cor do texto casa com a cor da partícula do
      circuito em `ActivityVisualizer`. `startle` (johnston) aparece como
      informativo só (verbo mais fraco, "percebendo") porque ainda não
      controla movimento de verdade.
- [ ] **Validação por lesão em servidor real** — pendente, agora que o canal
      controla comportamento de verdade. Precisa de um
      `LoomingLesionExperiment.java` (mascarar `looming_threat`, medir se a
      abelha realmente foge menos/menos rápido), mesmo padrão de
      `HygroLesionExperiment`/`TouchLesionExperiment`.
- [x] **Bug real corrigido — grooming preso em loop infinito (25/09/2026,
      achado do usuário).** Testando numa área reclusa, `grooming` dominava
      o movimento e impedia testar qualquer outro sensor: pouso mandava
      velocidade PURAMENTE vertical (resquício de antes dos 9 bugs do
      `hygro` serem corrigidos), ela nunca alcançava `onGround`, sistema de
      recuperação empurrava numa direção sem relação com o obstáculo real
      (oposto de `heading`, não do que estava bloqueando) — log confirmou
      217 disparos de recuperação numa sessão, sem nunca resolver
      ("voando pra parede infinitamente"). Corrigido dando componente
      horizontal (`heading`) à descida do grooming, mesma receita que já
      funcionou no `hygro`. Também adicionado `/flywirebee touchmute
      <on|off>` — toggle MANUAL (diferente do experimento automatizado
      `touchlesion`) pra mascarar toque durante teste isolado de outros
      circuitos, já que o jogador observando de perto mantém
      `touch_proximity` permanentemente verdadeiro. Deployado,
      **reteste em jogo pendente**.
- [x] **Dois bugs reais adicionais, mesma sessão de reteste (25/09/2026).**
      (1) **Crash real**: `LoomingSensor.fleeDirectionAwayFromNearestThreat`
      (usa `World#getNearbyEntities`) estava sendo chamado de dentro do
      lambda assíncrono da ponte (thread `flywire-control-bridge`) — Paper
      derruba com `AsyncCatcher` (só permite essa API na thread principal),
      quebrando a troca ANTES de atualizar `latestVelocity`/
      `latestBristleMotor` toda vez que `escape_drive` cruzava o limiar.
      Corrigido movendo o cálculo pra dentro de `onTick` (thread principal,
      mesmo padrão de `alarmHostileMob`/`touchProximity`), passando o
      resultado pronto pro lambda. (2) **`ESCAPE_THRESHOLD` catastroficamente
      mal calibrado**: log mostrou `escape_drive` oscilando 0,58-0,97 com
      `looming=false` o tempo todo — abelha "fugindo" sem ameaça nenhuma,
      atropelando prioridade de hygro/grooming. Causa raiz: `C.
      MOTOR_RATE_SCALE=30` (genérico, calibrado pro ocelar) aplicado a um
      grupo de só 4 neurônios (DNp01+DNp02) satura o tanh mesmo em repouso —
      medido isolado, baseline=38,3±10,0 Hz (p95=55,0), estimulado satura em
      340 Hz. Corrigido com `C.ESCAPE_MOTOR_RATE_SCALE=150` (constante
      própria) + `ESCAPE_THRESHOLD` recalibrado 0,8→0,6 — separação limpa
      (baseline tanh p95=0,351, estimulado=0,979). Ver RN-09 em
      `docs/04-regras-de-negocio.md` pro relato completo e a lição
      generalizável (escala de um grupo grande não serve pra um grupo
      pequeno). Docker reconstruído, jar redeployado — **reteste em jogo
      pendente**.
- [x] **Grooming restrito ao contexto real de toque (25/09/2026, pedido do
      usuário).** `touch_contact` (esbarrar em bloco/parede/chão) saiu do
      OR que estimula o `bristle` — bater numa parede durante o voo não é
      o mesmo estímulo biológico de algo pousar/tocar o corpo da mosca
      (mesmo princípio já usado no `AlarmSensor`/johnston — Eberl, Hardy &
      Kernan 2000). O sensor continua computado/logado (telemetria), só não
      afeta mais `grooming`. `touch_proximity` (`TouchSensor.isNearSomething`)
      restrito de "qualquer `LivingEntity`/`Item`" pra só `Monster`/
      `Animals`/`NPC`/`Player` — item largado no chão não conta mais.
      Detecção de obstáculo/travamento (`ControlLoop.STUCK_CHECK`/
      `RECOVERY_BOOST`) é sistema separado, mede deslocamento real
      diretamente, nunca dependeu de `touch_contact` — confirmado intacto.
      Também: balão de texto (`StatusLabel`) perdeu os nomes técnicos de
      canal entre parênteses (pedido do usuário) — só o texto em português
      agora, nome do canal só no HUD/log. 39/39 testes Python passando
      (novo teste de regressão: `touch_contact` isolado não estimula mais o
      bristle). Docker reconstruído, jar redeployado.
- [x] **Trava temporal da fuga (25/09/2026, pedido do usuário).**
      `escape_drive`/`looming_threat` são sinais de nível que podem cair
      rápido (ameaça sai do raio de busca, distância para de fechar) —
      sem trava, a fuga podia durar menos que uma troca, curto demais pra
      observar visualmente. `ControlLoop.ESCAPE_LATCH_TICKS=50` (~2,5s,
      dentro do pedido de "2 a 3 segundos"): uma vez que o circuito real
      cruza `ESCAPE_THRESHOLD`, garante pelo menos esse tempo de fuga
      visível, recarregando a contagem (e a direção capturada) enquanto a
      ameaça continuar de verdade. `MotorMapping.toVelocity` e o balão de
      texto (`StatusLabel`) passam a receber a decisão JÁ ESTABILIZADA do
      `ControlLoop`, não mais o valor cru de `escape_motor` — mesma
      disciplina de `landed`/`sheltered`. Compilado, jar redeployado.
- [x] **Recalibração do limiar de looming, dois ciclos (25/09/2026, achado
      do usuário em teste real).** `CLOSING_SPEED_THRESHOLD_BLOCKS_PER_TICK`
      original (0,3) estava ACIMA da velocidade de sprint do próprio
      Minecraft (~5,6 blocos/s = 0,28/tick) — usuário correu direto na
      direção dela de fora do raio e nunca disparou, matematicamente
      impossível disparar. Baixado pra 0,15 — usuário testou e reportou
      oposto: andar um passo já disparava (sensível demais, virou ruído).
      Recalibrado pra 0,25 (entre andar ~0,22 e correr ~0,28) a pedido do
      usuário — exige aproximação de verdade sem cair em nenhum dos dois
      extremos anteriores.
- [x] **`damage` (hit real) também estimula `escape`, não só
      `looming_threat` (25/09/2026, pedido do usuário).** Levar um hit de
      verdade é sinal de ameaça mais forte que taxa de aproximação —
      deveria escalar pra fuga plena, não só toque/grooming. Mesmo `damage`
      que já estimula `bristle` (um hit real dispara os dois circuitos, não
      é exclusivo). `server.py::on_sensor` — `looming = looming_threat OR
      damage`. 40/40 testes Python passando. Docker reconstruído.
      **✅ Confirmado em servidor real (25/09/2026)** — usuário deu um hit
      nela, modo fuga ativou.
- [x] **Recuperação mecânica escala a estratégia depois da 1ª falha
      (25/09/2026, achado ao vivo no log).** `horizontalOpposite(heading)`
      assume que o obstáculo está na direção que ela tentava seguir — só
      vale a PRIMEIRA tentativa. Log real mostrou o mesmo vetor de empurrão
      (variação de ~0,001) se repetindo por dezenas de segundos numa área
      reclusa, sem nunca liberar — `heading` (yaw_steering) gira devagar
      demais pra diversificar a direção entre tentativas. Corrigido:
      `consecutiveStuckCount` conta falhas seguidas no mesmo travamento;
      1ª falha usa a heurística original (barata, funciona pra obstáculo
      único), 2ª+ sorteia direção horizontal aleatória a cada nova
      tentativa — explora em vez de insistir numa hipótese já refutada.
      Reseta assim que ela volta a se mover de verdade. Compilado, jar
      redeployado — **reteste em jogo pendente**.

---

## Fora de escopo (candidatos a v2+)

| Item | Fase provável |
|---|---|
| Posição anatômica real dos neurônios | ✅ feita — ver AD-11/F6, "Anatomia real" |
| Agrupar/colorir por neurópilo | ✅ feita — ver AD-11/F6, "Anatomia real" |
| Circuito de 2 saltos (10.578 neurônios) | v2 |
| Cérebro inteiro (139k) fora do loop | v3 |
| Plasticidade / aprendizado | v3 — o conectoma é estático por natureza |
| Multi-sensor: chuva, toque | **F7 — `bristle` validado ponta a ponta; `hygro` L2/L3 pronto, falta integração + lesão, ver acima** (dia/noite saiu daqui, ver F6) |

## Dívida técnica aberta

| # | Item | Impacto |
|---|---|---|
| DT-1 | Dados vêm de espelho GitHub, não da fonte primária | Proveniência para publicação |
| DT-2 | Regra de sinal herdada de Shiu et al., não validada por nós | Correção científica |
| DT-3 | Override de histamina é hipótese nossa, não dado | Correção científica |
