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

## Regra

O plugin **nunca** altera a simulação. Se o comportamento não emerge, o problema
é da hipótese ou do modelo — não se ajusta o mob para "ficar bonito".
Ver `.claude/CLAUDE.md`.
