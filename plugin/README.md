# plugin — encarnação no Minecraft

Scaffold criado na **Fase 3**. Ver `docs/03-roadmap-fases.md`.

## Estado atual (F3 — compila e conecta; comportamento da abelha é F4)

- `FlywireBeePlugin` — só liga/desliga, conecta em `BridgeClient` e loga
  sucesso/falha. Não lê sensor nenhum, não aplica vetor motor a nada.
- `BridgeClient` — cliente TCP JSON-lines que fala o protocolo real de
  `sim/src/flywire_sim/server.py` (`sendSensorAndReceiveMotor`).
- **Compila.** Wrapper do Gradle (`gradlew`/`gradlew.bat`) foi bootstrapado com
  Gradle 8.10 oficial e está versionado — não precisa mais de Gradle instalado
  à parte, só rodar `./gradlew build` (Windows: `.\gradlew.bat build`). Sem
  toolchain fixo no `build.gradle.kts`: usa o JDK que já roda o Gradle,
  compilando com `--release 21` (compatibilidade de bytecode com o Paper, não
  exige JDK 21 instalado literalmente).
- **✅ Validado em servidor real em 16/09/2026.** Paper 1.21.1 rodando em
  `mc-server/`, simulador em Docker, log confirma `[FlywireBee] Conectado ao
  simulador em localhost:8765` e um jogador (TLauncher, conta offline) entrou
  no mundo normalmente. Primeira prova de que o protocolo Java↔Python
  interopera de verdade, não só em teste isolado.
- **O que isso não prova:** a abelha ainda se comporta como abelha normal —
  não há listener de sensor nem aplicação de vetor motor (TODO explícito no
  código). Isso é trabalho da F4.

## Spawn da abelha marcada

Antes de aplicar sensor/motor (F4), é preciso conseguir identificar *qual*
abelha do mundo é a controlada pelo conectoma — v1 é uma simulação, uma
abelha (`docs/00-visao-geral.md`).

**✅ Testado em servidor real em 16/09/2026** — `/flywirebee give` (depois de
opar o jogador; permissão padrão é `op`, e servidor novo não opa ninguém
sozinho), clique no bloco, abelha spawnou com nome "FlyWire Bee" visível e
brilho. Mecanismo de identificação funciona de ponta a ponta.

- `/flywirebee give` (permissão `flywirebee.give`, padrão op) dá ao jogador um
  item "FlyWire Bee Spawner" (ícone de favo de mel, nome/lore customizados).
- Clicar com o item (botão direito, bloco ou ar) spawna uma abelha, marca ela
  via `PersistentDataContainer` (`FlywireBeeMarker`), dá nome visível "FlyWire
  Bee" e efeito de brilho (`setGlowing`) para identificar no mundo. Consome um
  item (exceto em creative).
- Se já existir uma abelha marcada no mundo, o spawn é negado — evita duas
  abelhas "controladas" ao mesmo tempo, que o loop de controle da F4 ainda não
  sabe distinguir.
- **IA nativa continua ligada** nessa abelha por enquanto — ela se comporta
  normal (voa, poliniza, etc.) até a F4 aplicar controle motor de verdade e
  desligar a IA. A marca por si só não muda comportamento nenhum.

## O que será

Plugin Paper/Spigot (Java 21, Gradle) que:

1. Lê sensores da abelha — nível de luz do bloco, luz dorsal (céu visível), dano.
2. Envia a `localhost:8765` em JSON-lines a 20 Hz.
3. Recebe o vetor motor e o aplica à abelha.
4. Sobrescreve a velocidade a cada tick — **não desliga a IA nativa** (ver
   "Risco investigado" abaixo, o plano original mudou).

## Risco investigado (F4, 16/09/2026) — resultado inverteu o plano original

Quatro modos testados via `/flywirebee spike <modo>` numa abelha real, 5s cada,
medindo deslocamento:

| Modo | Resultado |
|---|---|
| `velocity-noai` (`setVelocity()` a cada tick + `setAI(false)`) | **0,000 blocos** — abelha congela totalmente |
| `velocity-ai` (`setVelocity()` a cada tick, IA ligada) | **28,7 de ~30 blocos esperados** (96%, perda normal de ar) ✅ |
| `pathfinder` (`Mob#getPathfinder().moveTo()`) | não testado — desnecessário, velocity-ai já resolveu |
| `teleport` (incremento de posição direto) | implementado, disponível como alternativa, não testado a fundo |

**Achado: `setAI(false)` em mob voador trava movimento inteiro, não só decisão
de IA.** O plano original ("desabilitar a IA antes de aplicar") estava
**errado** — a solução certa é o oposto: manter a IA ligada e reaplicar a
velocidade desejada a cada tick (20 Hz), que aparentemente vence/domina
qualquer decisão da IA nativa no mesmo tick, sem precisar desligar nada.

**Risco residual não testado:** em 5s de bee "ociosa" a sobrescrita venceu de
forma limpa. Em uso real e prolongado, se a abelha tiver um objetivo de IA
ativo (indo atrás de flor, fugindo de ameaça), pode haver disputa visível
tick a tick. Se isso virar problema perceptível, `teleport` já está
implementado como alternativa determinística (sem física/colisão, mas
garantida).

## Loop de controle real

**✅ Validado em servidor real em 16/09/2026** — `/flywirebee control start` rodou
~58s, **1500 trocas com a ponte, 0 falhas** (~26 Hz, acima da meta de 20Hz), abelha
observada voando de verdade pelo mapa (confirmado visualmente, não só pelo log).

`ControlLoop` implementa exatamente o padrão de RN-06: leitura de sensor (luz do
bloco) e aplicação de velocidade rodam na thread principal (obrigatório — API do
Bukkit não é thread-safe fora dela); a troca com a ponte (`BridgeClient`, I/O de
rede bloqueante) roda numa thread dedicada, nunca mais de uma por vez; a cada tick a
abelha recebe o ÚLTIMO vetor motor já calculado, nunca espera uma troca terminar.

`MotorMapping` traduz `phototaxis` em velocidade de AVANÇO na direção que a abelha já
está olhando (ainda sem yaw/lift próprio — RN-08 completa continua pendente).

**Tentamos somar `locomotion_drive`** (RN-08/AD-14 — 6 tipos com categoria "Fast"/
"Broad Locomotion" publicada por Namiki et al. 2018) e **revertemos**: experimento de
lesão re-rodado deu nulo (p=0,43), testamos se era confundidor de local (abelha tinha
spawnado dentro de casa) refazendo ao ar livre — continuou nulo (p=0,27). Causa real:
`locomotion_drive` não responde ao caminho de luz, então somar dilui o sinal que
`phototaxis` carregava sozinho. Terceira vez que "agregar cancela o efeito" aparece
neste projeto (RN-09 na F1, primeiro experimento de lesão na F4, agora aqui). Ver
`docs/04-regras-de-negocio.md` (RN-08) para o relato completo e a tabela dos 13/46
tipos com dado real (12 de citação direta + 1 via `hemibrain_type`) —
`locomotion_drive` continua exposto pra telemetria/exploração, só não entra mais na
velocidade.

Comandos: `/flywirebee control start` | `/flywirebee control stop`.

## Experimento de lesão

`/flywirebee lesion [trials=20] [segundos=10]` — critério de saída da F4
(`docs/00-visao-geral.md`, "Critério de falsificação"). Precisa do loop de controle
já rodando. Roda N trials com ordem aleatória entre fotorreceptores normais e
silenciados (lesão = plugin sempre manda `light=0`, sem mudar `server.py`), teleporta
a abelha de volta à origem a cada trial, mede comprimento de trajetória. Grava CSV em
`plugins/FlywireBee/lesion_experiment.csv`.

Analisar com (a partir de `sim/`, venv ativo):
```
python tools/lesion_analysis.py
```
Compara `path_length`/`avg_speed` entre os grupos (Welch t-test + Mann-Whitney U).

**Bug encontrado e corrigido (primeira tentativa, 16/09/2026):** o CSV saía
corrompido — `String.format("%.4f", ...)` usa o locale padrão da JVM, que
neste servidor é pt_BR (vírgula como separador decimal). Como o CSV também
usa vírgula como separador de coluna, cada número quebrava em duas colunas
(`21,7311` virava dois campos: `21` e `7311`), bagunçando o arquivo inteiro
silenciosamente — sem erro, só dado errado. Corrigido forçando
`Locale.ROOT` em todo `String.format`/`printf` que grava arquivo (mantido
locale padrão só nos logs de console, que são só para leitura humana).
A lógica de sorteio da condição (normal/lesionado) **não** estava com bug —
conferido nos dados brutos corrompidos, a distribuição true/false era normal;
só a serialização quebrava.

**Segundo bug (também 16/09/2026):** com o CSV corrigido, resultado deu nulo
(p=0,37) — mas `MotorMapping` estava tirando a média simples dos 8 grupos
por prefixo, e essa média cancela sinais que respondem em direções opostas
(mesmo erro já corrigido uma vez em RN-09). Corrigido trocando por um canal
`phototaxis` novo em `motor.py`, baseado na topologia de sinal já validada
(`excitatório − inibitório`, não curadoria nova). Ver `docs/04-regras-de-negocio.md`.

**✅ Critério de saída da F4 atingido em 16/09/2026, segunda rodada** —
Mann-Whitney U p=0,0014 (distância percorrida: normal 31,26±1,60 vs.
lesionado 27,56±5,08 blocos, N=20). Ver `docs/03-roadmap-fases.md` para a
tabela completa e as ressalvas (N pequeno, mapeamento motor ainda só
magnitude, sem yaw/direção própria do circuito).

## Visualização de atividade (F5)

`/flywirebee visualize <on|off>` — liga junto com `control start` por padrão.
`ActivityVisualizer` spawna partículas coloridas (`Particle.DUST`) ao redor da
abelha, uma cor fixa por canal do vetor motor. `phototaxis` (amarelo) e
`locomotion_drive` (branco) têm cor própria — são os dois canais que de fato movem a
abelha (`MotorMapping`). Quantidade de partículas por canal é proporcional a |valor|
do canal, renderizado a ~4Hz (20Hz seria spam visual).
**✅ Confirmado visualmente em servidor real — 16/09/2026 (phototaxis) e de novo após
RN-08/AD-14 (locomotion_drive, partículas brancas visíveis junto das amarelas).**

## Lesão e estimulação por comando (F5)

Dois comandos que estendem o protocolo da ponte (`mute`/`stimulate`, ver
`docs/02-arquitetura.md`):

- `/flywirebee mute <grupo>` / `unmute <grupo|all>` — silencia de verdade a
  saída sináptica do grupo no simulador (`engine.py::Engine.set_silenced`).
  Grupos válidos: `sensory` (os 273 fotorreceptores) + os 8 por prefixo de
  `cell_type` (`DNp`, `DNpe`, `DNg`, `DNge`, `DNb`, `DNbe`, `DNa`, `DNae`).
  **Mecanismo diferente do `/flywirebee lesion`** (que só zera o sensor de
  luz) — aqui o neurônio é removido da rede de verdade, não só perde
  estímulo. Leva até ~50ms pra aparecer no vetor motor (janela deslizante).
- `/flywirebee stimulate <grupo> <amplitude>` / `stimulate stop` — injeta
  corrente extra num grupo (`engine.py::Engine.set_directed_stimulus`),
  **somada** ao estímulo de luz dos fotorreceptores, não substituindo.

**✅ Testados de ponta a ponta via TCP manual em 16/09/2026** (mutar DNp → canal
exatamente 0,0; estimular DNg com amplitude 5,0 → canal satura perto de 1,0; os
dois voltam ao baseline ao limpar).

**✅ Testados em servidor real com jogador, 16/09/2026:**
- `mute DNp` → partículas vermelhas (cor do DNp) somem, como esperado.
- `stimulate DNg 5.0` → a abelha ficou **mais lenta**, não mais rápida.
  Contraintuitivo, mas explicado: `DNg` (agrupamento por prefixo de
  `cell_type`) é 15 de 16 neurônios (94%) do grupo **inibitório** da
  topologia de sinal (RN-09) — só 1 é excitatório. Estimular DNg forte
  aumenta a taxa do grupo inibitório, o que **reduz** `phototaxis`
  (`excitatório − inibitório`), e é esse canal que `MotorMapping` usa pra
  velocidade. **O efeito de estimular um grupo por nome depende de sua
  composição excitatório/inibitório na topologia real, não é
  "mais corrente = mais rápido" de forma direta.** Verificável com:
  ```python
  from flywire_sim import graph
  from flywire_sim.motor import group_by_cell_type_prefix
  from flywire_sim.topology import group_outputs_by_predicted_sign
  # cruzar os dois agrupamentos por nid
  ```
- Confirmado: a IA nativa "vazou" uma vez (abelha parou pra polinizar uma
  flor) durante o teste — esperado, ver "Risco investigado" acima. Não
  invalida os testes, é ruído de fundo com a mesma expectativa nos dois
  grupos de qualquer comparação.

## Experimento dia/noite (F6)

`/flywirebee daynight [trials=20] [segundos=10] [x y z] [blind]` — mesmo desenho do
`/flywirebee lesion` (F4), trocando a variável manipulada: em vez de silenciar
fotorreceptores, alterna a hora do mundo (`world.setTime()`) entre meio-dia
(tick 6000) e meia-noite (tick 18000) por trial, em ordem aleatória. Grava CSV
em `plugins/FlywireBee/daynight_experiment.csv`.

**Achado técnico antes de implementar (16/09/2026) — a premissa original estava
errada.** A ideia inicial (`docs/03-roadmap-fases.md`, nota de multi-sensor) era
usar `dorsal_light` (`Block.getLightFromSky()`) pra dia/noite, achando que era
"quase de graça". Verificamos a API do Bukkit antes de mexer em código e achamos
o oposto: `getLightFromSky()` retorna o **skylight bruto**, travado em 15 ao ar
livre **independente da hora do dia** (confirmado via Minecraft Wiki) — é sensor
de teto/céu aberto, não de hora. Quem já varia com dia/noite é
`Block.getLightLevel()` — o `light` que a F4 já usa e já validou (p=0,0014).
Substituir `light` por `dorsal_light` teria trocado sensibilidade a dia/noite por
sensibilidade a indoor/outdoor — o oposto do que "multi-sensor dia/noite" pede.
Ver `CONVENCOES.md`, "Armadilhas conhecidas".

**Desenho final:** `light` continua alimentando os fotorreceptores exatamente
como na F4 — nenhuma mudança em `server.py` nem no protocolo. `dorsal_light`
entra só como **filtro de confundidor**: `DayNightExperiment` recusa rodar se a
abelha não estiver ao ar livre (skylight bruto &lt; 15), porque debaixo de teto
`light` não varia com a hora do mundo e o experimento ficaria nulo por desenho
errado — mesma armadilha de "abelha dentro de casa" que já apareceu uma vez em
RN-08/F6 (locomotion_drive).

Analisar com (a partir de `sim/`, venv ativo):
```
python tools/daynight_analysis.py
```
Compara `path_length`/`avg_speed` entre trials de dia e de noite (Welch t-test +
Mann-Whitney U, mesma dupla do experimento de lesão).

**Controle cego:** `/flywirebee daynight 20 10 blind` roda o mesmo experimento com
`light=0` (mesmo mecanismo do `lesion`), gravando em
`daynight_experiment_blind.csv`. Existe porque abelha vanilla muda de comportamento à
noite pela IA nativa — sem esse controle, diferença dia/noite não seria atribuível ao
circuito. Analisar passando o caminho: `python tools/daynight_analysis.py
../mc-server/plugins/FlywireBee/daynight_experiment_blind.csv`.

**Rodado em servidor real, 16/09/2026 — resultado negativo.** Dia × noite nulo nas
três rodadas (normal p=0,29; cegas p=0,68 e p=0,40), com checagem de manipulação
passando (`light` 0,987 de dia, 0,250 à noite). Tabela completa e o que dá/não dá
pra afirmar em `docs/03-roadmap-fases.md`, F6.

**Armadilhas de operação encontradas rodando:**
- **A origem é a posição da ABELHA quando o comando roda, não a sua.** `/tp` no
  jogador não move a origem. **Causa raiz do drift (17/09/2026):** a abelha sai
  andando/voando pela IA nativa entre um comando e o próximo — mesmo um
  `/flywirebee goto` separado do comando do experimento deixa uma janela de
  deriva (o tempo que você leva pra digitar o próximo comando). **Corrigido de
  verdade:** `daynight`/`doseresponse` aceitam `x y z` opcional no final, que
  teleporta a abelha no mesmo instante de execução do comando, sem intervalo:
  `/flywirebee daynight [trials] [segundos] [x y z] [blind]` e
  `/flywirebee doseresponse [trials] [segundos] [x y z]`. `/flywirebee goto <x> <y>
  <z>` continua existindo pra reposicionar manualmente fora de um experimento
  (ex.: antes de `control start`), mas **não substitui** passar a coordenada
  direto no comando do experimento.
- **Não compare rodadas feitas em lugares diferentes.** Duas rodadas cegas a 70
  blocos de distância diferiram 9 blocos só por terreno (ver `CONVENCOES.md`).
- **Nova rodada sobrescreve o CSV.** Renomeie o anterior antes de rodar de novo.
- **`/flywirebee kill` durante a rodada aborta o experimento sem gravar CSV** e
  desliga o modo cego — comportamento esperado, não bug.
- Use `/gamemode creative` e `/difficulty peaceful`: o experimento força meia-noite,
  e monstros podem matar a abelha no meio da rodada.

## Desligar IA que compete com locomoção (F6)

`/flywirebee goals off` — hipótese do usuário (16/09/2026) pro resultado nulo do
experimento dia/noite: a IA nativa estaria mascarando um efeito real do circuito,
não que o efeito não existe.

**`setAI(false)` já foi testado e descartado na F4** — congela a física inteira, não
só a decisão (0,000 blocos em 5s, ver "Risco investigado" acima). Alternativa nunca
testada até agora: a **Mob Goal API do Paper** (`Bukkit.getMobGoals()`), que remove
objetivos específicos de IA sem tocar em `setAI`. `/flywirebee goals off` remove só
os que competem com locomoção — `BEE_WANDER` (vagar aleatório), `BEE_GO_TO_KNOWN_FLOWER`/
`BEE_POLLINATE` (o "vazamento" já visto na F5), `BEE_GO_TO_HIVE`/`BEE_LOCATE_HIVE`/
`BEE_ENTER_HIVE` (candidato mais forte pro confundidor dia/noite — vanilla bee tenta
voltar pra colmeia à noite). Mantém ataque/fúria/dor/crescer planta — não competem
com locomoção aqui. Ver `CompetingGoals.java`.

**✅ Testado isolado em servidor real, 17/09/2026** (`/flywirebee spike
no-competing-goals`): 28,84 de 30 blocos esperados em 5s (96%, igual ao modo com IA
ligada da F4) — a física não trava, diferente do `setAI(false)`.

**Sem volta pela API pública** — não existe forma de re-registrar a implementação
vanilla original a partir do `GoalKey`. Pra essa abelha específica voltar a ter os
goals padrão: `/flywirebee kill` + `give` (spawna uma abelha nova).

**✅ Confirmado em servidor real, 17/09/2026 — hipótese do usuário estava certa.**
Três rodadas com IA nativa ligada deram dia/noite nulo (p≥0,29). Com `goals off`,
repetido em dois locais diferentes (38 e 14 blocos do ponto de referência): efeito
real e replicado (Welch p=0,00087, Mann-Whitney p=0,00184 na rodada mais próxima do
ponto certo; as duas rodadas `goals off` concordam entre si, p=0,96). Desvio-padrão
caiu de ~0,6–8,2 blocos pra ~0,22 — a IA nativa realmente competia pelo controle e
mascarava o sinal.

**A direção veio invertida do esperado, e o teste de dose-resposta abaixo explicou
por quê:** não é saturação nem ruído, é uma curva não-monotônica real, com pico em
luz=0,25. Ver `docs/03-roadmap-fases.md`, F6, pra tabela completa.

## Dose-resposta de luz (F6)

`/flywirebee doseresponse [trials=20] [segundos=10] [x y z]` — pergunta em aberto deixada
pelo experimento dia/noite: luz 0,25 (noite) deu MAIS distância que luz 1,0 (dia),
o oposto do esperado, e luz 0 (F4) deu a MENOR distância das três condições já
vistas — mas cada ponto veio de um experimento diferente, não comparáveis entre si
de forma limpa. Este comando testa 4 níveis (0 / 0,25 / 0,5 / 1,0) sorteados trial a
trial **na mesma rodada, mesma origem**.

Usa `ControlLoop.setForcedLight(Double)` — generalização do mecanismo que já fazia
`/flywirebee lesion` mandar `light=0` (agora `setLesioned` é um caso particular
disso). Não depende de hora do mundo (`light` só assume os valores discretos que o
motor de iluminação do Minecraft produz — não dá pra pedir exatamente 0,5 via
`world.setTime()`), então funciona em qualquer lugar, sem checagem de skylight.

**Não liga `goals off` sozinho** — F6 mostrou que sem isso a IA nativa pode mascarar
o efeito (rode `/flywirebee goals off` antes, se for repetir o padrão que já deu
resultado significativo no dia/noite).

Analisar com (a partir de `sim/`, venv ativo):
```
python tools/doseresponse_analysis.py
```
Kruskal-Wallis (omnibus entre os 4 níveis) + Mann-Whitney de cada nível contra
`light=1,0`.

**✅ Rodado em servidor real, 17/09/2026 — curva não-monotônica confirmada.** 32
trials, `goals off`, origem exata (129,58; 71,40; -116,01): luz 0,25 deu distância
MAIOR que luz 1,0 (30,36 vs 30,12, p=0,005), luz 0,5 igual a luz 1,0 (p=0,48), luz 0
menor que todos (28,75, p=0,0002). Kruskal-Wallis p=0,00003. Não é saturação — é um
pico em luz baixa. Mecanismo não explicado, hipótese candidata em
`docs/03-roadmap-fases.md` F6 (não testada — não citar como se fosse).

**Achado de operação:** as duas primeiras tentativas derivaram 24–38 blocos do ponto
pretendido, mesmo usando `/flywirebee goto` antes — o tempo de digitar o próximo
comando já bastava pra IA nativa mover a abelha de novo. Corrigido fazendo
`doseresponse`/`daynight` aceitarem `x y z` no próprio comando (teleporta no mesmo
instante de execução, sem intervalo): `/flywirebee doseresponse 32 10 129.58 71.40
-116.01`.

## Regra

O plugin **nunca** altera a simulação. Se o comportamento não emerge, o problema
é da hipótese ou do modelo — não se ajusta o mob para "ficar bonito".
Ver `.claude/CLAUDE.md`.
