# plugin — encarnação no Minecraft (L6)

Plugin Paper que dá um corpo ao conectoma: lê os sensores de uma abelha marcada a
20 Hz, envia à simulação pela ponte TCP e aplica a velocidade que volta. **O plugin
nunca altera a simulação.** Se o comportamento não emerge, o problema é da hipótese
ou do modelo, não do mob.

O registro cronológico de cada decisão, bug e medição (F3–F30) está em
[`HISTORICO.md`](HISTORICO.md). Este arquivo descreve o estado atual.

## Compilar e instalar

Requer JDK ≥ 21. O Gradle wrapper vem no repositório.

```bash
cd plugin
./gradlew build                 # Windows: .\gradlew.bat build
cp build/libs/flywire-bee-0.1.0.jar ../mc-server/plugins/
```

Alvo: Paper 1.21.1 (`paper-api:1.21.1-R0.1-SNAPSHOT`, bytecode release 21). O
endereço do simulador fica em `src/main/resources/config.yml`
(`bridge.host`/`bridge.port`, padrão `localhost:8765`). Suba o simulador **antes**
do servidor: sem ele o plugin só registra um aviso no `onEnable`.

## Como funciona

```
 tick do jogo (thread principal, 20 Hz)                 thread "flywire-control-bridge"
 ─────────────────────────────────────                  ─────────────────────────────
 lê sensores da abelha ──► estado compartilhado ──────► envia JSON ao simulador
 aplica o último vetor motor ◄── latestMotor ◄───────── recebe o vetor motor
```

- **O tick nunca espera a ponte** (RN-06). Só uma troca fica em andamento por vez, e
  cada tick aplica o último vetor recebido.
- **Toda chamada à API do Bukkit roda na thread principal.** Sensores que varrem
  blocos ou entidades são calculados no tick e só o resultado vai para a thread da
  ponte; chamar a API na thread da ponte derruba a troca (AsyncCatcher do Paper).
- **A IA nativa fica ligada** e a velocidade é sobrescrita a cada tick.
  `setAI(false)` congela a física da abelha. Para experimentos, `/flywirebee goals
  off` remove só os objetivos que competem com a locomoção (`CompetingGoals`), pela
  Mob Goal API do Paper. Não há como desfazer pela API: para ter os objetivos de
  volta, `kill` + `give`.
- **O corpo acompanha a velocidade:** `setVelocity` não gira o mob, então o yaw é
  recalculado a partir do vetor aplicado (`setRotation`) a cada tick.

### Sensores

| Classe | Campo do protocolo | O que detecta | Circuito |
|---|---|---|---|
| `ControlLoop` | `light`, `dorsal_light` | luz do bloco (varia com dia/noite); luz do céu (só exposição, não hora) | main (ocelar) |
| `DamageTracker` | `damage` | dano recebido (borda) | bristle, escape |
| `TouchSensor` | `touch_proximity` (`touch_contact` só telemetria) | mob/jogador a até 3 blocos | bristle |
| `ControlLoop` | `raining` | `World#hasStorm()` | hygro |
| `AlarmSensor` | `alarm_explosion`, `alarm_hostile_mob`, `sound_music` | explosão a 16 blocos, mob hostil a 8, jukebox a 6 | johnston |
| `LoomingSensor` | `looming_threat` | ameaça a até 12 blocos se aproximando a ≥0,25 bloco/tick | escape |
| `TasteSensor` | `food_contact` | bloco de mel, melancia, abóbora, bolo, arbusto de frutas; item comestível no chão ou na mão do jogador; raio 4 (+4 com fome) | taste |
| `ThermalSensor` | `thermo_heat`, `thermo_cold` | lava/fogo/magma; gelo, neve, bioma frio | thermo |
| `ShelterSensor` | — | teto que bloqueia chuva (`MOTION_BLOCKING_NO_LEAVES`; folha não abriga) | hygro (destino) |

`olfactory`, `aggression` e `path_integration` não têm sensor: os campos vão sempre
`false` e os canais são só telemetria.

### Decisão do movimento

`MotorMapping.toVelocity` escolhe um comportamento por tick, nesta prioridade:

| # | Condição | Movimento | Limiar |
|---|---|---|---|
| 1 | `escape_drive` acima do limiar (com trava de 50 ticks) | foge da ameaça, subindo | 0,6 |
| 2 | `hygrotaxis` acima do limiar | mergulha e procura abrigo; parada quando abrigada | 0,8 |
| 3 | `appetite` acima do limiar e comida na mão do jogador | vai até a comida | 0,7 |
| 4 | `grooming` acima do limiar | desce e pousa; parada no chão (ou desvio, ver abaixo) | 0,8 |
| 5 | `appetite` acima do limiar | vai até a comida, para ao chegar (0,3 bloco) | 0,7 |
| 6 | caso geral | avança na direção atual com velocidade por `phototaxis` | — |

Antes disso, o `ControlLoop` pode substituir o resultado:

- **fuga de calor:** `thermal` > 0,45 **e** fonte perigosa detectada pelo sensor;
- **recuperação de obstáculo:** empurrão quando a abelha anda menos que o esperado em
  20 ticks;
- **desvio:** sorteado em metade dos episódios de grooming, um recuo horizontal no
  lugar do pouso.

Depois aplica, em sequência:

- **Fome** (`EnergyTracker`): a energia cai com o tempo e com a distância voada.
  Com fome a abelha voa mais baixo (teto de 2 blocos faminta) e busca comida
  ativamente abaixo de 20%.
- **Frio** (`ThermalSensor`): desacelera e baixa o teto, sem ficar abaixo de 2 blocos.
- **Sono** (`SleepState`): à noite fica sonolenta, a 12% da velocidade e com teto de
  1 bloco. Som alto confirmado pelo `startle`, medo ou dano a acordam.

Fome e sono são **estados de engenharia**, não circuitos neurais: os circuitos
candidatos foram testados e não serviram (AD-22, AD-24).

**Paradas intencionais** (pouso do grooming, abrigo encontrado, chegada à comida)
entram na exceção do sistema anti-obstáculo. Sem isso ele confunde parada com
travamento e desfaz o comportamento. Todo comportamento novo que pare a abelha
precisa entrar nessa lista.

### Observabilidade

- `ActivityVisualizer` (`/flywirebee visualize on`): partículas ao redor da abelha,
  uma cor por circuito ativo.
- `StatusLabel`: nome flutuante com o estado atual (limpando, abrigando, fugindo,
  comendo, com sono...).
- `LiveHud`: placar lateral (scoreboard) com os canais motores, a energia e o sono.
- Log do servidor a cada 20 ticks com os canais, a posição e o alvo.

## Comandos

Todos sob `/flywirebee` e exigem op.

| Comando | Efeito |
|---|---|
| `give` | Dá o item que cria a abelha marcada (só uma por mundo) |
| `kill` | Remove a abelha marcada (aborta experimento sem gravar) |
| `control <start\|stop>` | Liga/desliga o laço de controle |
| `goals off` | Remove os objetivos de IA nativa que competem com a locomoção |
| `goto <x> <y> <z>` | Teleporta a abelha marcada |
| `visualize <on\|off>` | Partículas de atividade |
| `ignoreplayer <on\|off>` | Sensores ignoram o jogador (toque, looming, comida na mão) |
| `touchmute <on\|off>` | Mascara o circuito bristle inteiro |
| `mute <grupo>` / `unmute <grupo\|all>` | Silencia grupos de descendentes do ocelar no simulador |
| `stimulate <grupo> <amplitude>` | Injeta corrente num grupo de descendentes do ocelar |
| `spike <modo>` | Testes técnicos de movimento (`VelocityProbe`) |

Grupos aceitos por `mute`/`stimulate`: `sensory`, os prefixos `DNp`, `DNpe`, `DNg`,
`DNge`, `DNb`, `DNbe`, `DNa`, `DNae`, as categorias funcionais da RN-08
(`fast_locomotion`, `broad_locomotion`, `wing_abdomen_movements`, `steering`,
`escape_takeoff`, `landing`, `flight`, `walking`, `ocellar`, `neuromodulatory`) e
`steering_left`/`steering_right`.

## Experimentos

Todos sorteiam a condição trial a trial, teleportam a abelha para `x y z` (ou a
posição atual dela) **dentro do próprio comando** e gravam um CSV em
`mc-server/plugins/FlywireBee/`. Sintaxe: `<comando> [trials=20] [segundosPorTrial=10] [x y z]`.

| Comando | Classe | Compara | CSV |
|---|---|---|---|
| `lesion` | `LesionExperiment` | luz normal × fotorreceptores sem luz | `lesion_experiment.csv` |
| `touchlesion` | `TouchLesionExperiment` | toque normal × mascarado | `touch_lesion_experiment.csv` |
| `hygrolesion` | `HygroLesionExperiment` | chuva normal × mascarada (exige `/weather rain`) | `hygro_lesion_experiment.csv` |
| `escapelesion` | `EscapeLesionExperiment` | fuga por dano normal × mascarada | `escape_lesion_experiment.csv` |
| `loominglesion` | `LoomingLesionExperiment` | fuga por looming normal × mascarada | `looming_lesion_experiment.csv` |
| `tastelesion` | `TasteLesionExperiment` | paladar normal × mascarado | `taste_lesion_experiment.csv` |
| `thermolesion` | `ThermoLesionExperiment` | temperatura normal × mascarada | `thermo_lesion_experiment.csv` |
| `johnstonlesion` | `JohnstonLesionExperiment` | despertar por som normal × mascarado | `johnston_lesion_experiment.csv` |
| `daynight [blind]` | `DayNightExperiment` | meio-dia × meia-noite | `daynight_experiment[_blind].csv` |
| `doseresponse` | `LightDoseResponseExperiment` | luz 0 / 0,25 / 0,5 / 1,0 | `doseresponse_experiment.csv` |
| `validateyaw [amplitude]` | `SteeringValidationExperiment` | estímulo em `steering_left` × `steering_right` × nenhum | `steering_validation_experiment.csv` |

A lesão **mascara o que é enviado**, não o que é detectado: o sensor continua
funcionando e o simulador recebe `false`. Análise em `sim/tools/` (`lesion_analysis.py`
serve para todas as lesões; dia/noite, dose-resposta e yaw têm o próprio script).

Antes de medir:

- `/flywirebee goals off`;
- `/weather clear`, salvo no `hygrolesion` (a busca de abrigo domina os outros canais);
- origem sem obstáculo na direção esperada do movimento (recuperações repetidas
  inflam `path_length`);
- jogador a menos de ~160 blocos (fora da distância de simulação a física da abelha
  para);
- `ignoreplayer on` quando o jogador não faz parte do estímulo.

## Classes

| Classe | Papel |
|---|---|
| `FlywireBeePlugin` | Ciclo de vida, configuração e comandos |
| `BridgeClient` | Cliente TCP JSON-lines da ponte |
| `ControlLoop` | Laço por tick: sensores, troca com a ponte, modificadores, anti-obstáculo, log |
| `MotorMapping` | Vetor motor → velocidade; limiares de cada canal |
| `CompetingGoals` | Remoção dos objetivos de IA nativa (Mob Goal API) |
| `FlywireBeeMarker`, `SpawnItem`, `BeeSpawnListener` | Identificam e criam a abelha controlada |
| `DamageTracker`, `TouchSensor`, `AlarmSensor`, `LoomingSensor`, `TasteSensor`, `ThermalSensor`, `ShelterSensor` | Sensores |
| `EnergyTracker`, `SleepState` | Estados de engenharia (fome, sono) |
| `ActivityVisualizer`, `StatusLabel`, `LiveHud` | Observabilidade |
| `*Experiment` | Experimentos com CSV |
| `VelocityProbe` | Testes técnicos de movimento (F4) |

## Armadilhas

As medidas estão em [`../CONVENCOES.md`](../CONVENCOES.md), seção "Armadilhas
conhecidas". As que mais afetam o plugin:

- `setAI(false)` congela a física;
- `setVelocity` não gira o corpo;
- `getLightFromSky()` não varia com a hora;
- folha não bloqueia chuva;
- `String.format` sem `Locale.ROOT` corrompe CSV em pt_BR;
- origem diferente invalida a comparação;
- a abelha congela longe do jogador.
