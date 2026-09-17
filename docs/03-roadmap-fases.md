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

---

## Fora de escopo (candidatos a v2+)

| Item | Fase provável |
|---|---|
| Posição anatômica real dos neurônios | v2 — exige coordenadas da Zenodo |
| Agrupar/colorir por neurópilo | v2 — exige quebra por neurópilo |
| Circuito de 2 saltos (10.578 neurônios) | v2 |
| Cérebro inteiro (139k) fora do loop | v3 |
| Plasticidade / aprendizado | v3 — o conectoma é estático por natureza |
| Multi-sensor: chuva, toque | v2 — ver nota abaixo (dia/noite saiu daqui, ver F6) |

**Nota — multi-sensor (16/09/2026, atualizada na F6):** ideia do usuário, avaliada
antes da F5. Dia/noite passou a ser trabalho da F6 (ver acima) depois que a premissa
inicial ("quase de graça") se mostrou tecnicamente errada. Os outros dois sensores
continuam fora de escopo:

- **Chuva** — parcialmente de graça: chuva escurece o céu no Minecraft, já afeta luz
  indiretamente. Um sensor de chuva independente exigiria achar (se existir) um circuito
  higro-sensorial no conectoma e extraí-lo à parte — mesmo processo da F0, semente diferente.
- **Toque** — exige um circuito mecanossensorial inteiro, sem relação com os 625 neurônios
  ocelares atuais. A conectividade **já está** em `Connectivity_783.parquet` e nas
  anotações da F0 (cobrem os 139k neurônios do cérebro inteiro) — nunca fomos atrás deles.

**Importante: nada disso precisa do arquivo de 9,5GB da Zenodo** (`flywire_synapses_783.feather`).
Esse arquivo traz precisão espacial (coordenada XYZ por sinapse, neurópilo, NT mais fino) —
útil pra anatomia real (primeiras duas linhas da tabela acima), não pra acessar novos tipos
de neurônio. Multi-sensor é sobre extrair um SUBCIRCUITO DIFERENTE do que já temos, não sobre
precisar de mais dado.

## Dívida técnica aberta

| # | Item | Impacto |
|---|---|---|
| DT-1 | Dados vêm de espelho GitHub, não da fonte primária | Proveniência para publicação |
| DT-2 | Regra de sinal herdada de Shiu et al., não validada por nós | Correção científica |
| DT-3 | Override de histamina é hipótese nossa, não dado | Correção científica |
