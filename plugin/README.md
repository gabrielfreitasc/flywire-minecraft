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

`MotorMapping` traduz o vetor motor (8 grupos provisórios de RN-08) em velocidade —
**provisório**: como os canais não têm direção própria ainda (só magnitude ≥0), usa
a atividade média do circuito como velocidade de AVANÇO na direção que a abelha já
está olhando. Yaw/lift genuinamente controlados pelo circuito exigem a curadoria de
RN-08, ainda pendente.

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

## Regra

O plugin **nunca** altera a simulação. Se o comportamento não emerge, o problema
é da hipótese ou do modelo — não se ajusta o mob para "ficar bonito".
Ver `.claude/CLAUDE.md`.
