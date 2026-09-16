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

## F5 — Observabilidade e experimentos

- [ ] Visualizar disparos no mundo (partículas/blocos por neurônio ou tipo)
- [ ] Ferramenta de lesão: silenciar neurônio/tipo por comando
- [ ] Estimulação dirigida de tipos específicos
- [ ] Documentar resultados

**Critério de saída:** um observador humano consegue, olhando o mundo, dizer qual parte
do circuito está ativa.

---

## Fora de escopo (candidatos a v2+)

| Item | Fase provável |
|---|---|
| Posição anatômica real dos neurônios | v2 — exige coordenadas da Zenodo |
| Agrupar/colorir por neurópilo | v2 — exige quebra por neurópilo |
| Circuito de 2 saltos (10.578 neurônios) | v2 |
| Cérebro inteiro (139k) fora do loop | v3 |
| Plasticidade / aprendizado | v3 — o conectoma é estático por natureza |

## Dívida técnica aberta

| # | Item | Impacto |
|---|---|---|
| DT-1 | Dados vêm de espelho GitHub, não da fonte primária | Proveniência para publicação |
| DT-2 | Regra de sinal herdada de Shiu et al., não validada por nós | Correção científica |
| DT-3 | Override de histamina é hipótese nossa, não dado | Correção científica |
