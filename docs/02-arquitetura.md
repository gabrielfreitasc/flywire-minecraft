# 02 — Arquitetura

## Princípio organizador

O conectoma é dado científico; o Minecraft é entretenimento com loop de 20 Hz. Misturar
os dois num processo só contamina a ciência com as restrições do jogo. A arquitetura
mantém uma **fronteira dura** entre eles: o simulador é autoridade, o jogo é consumidor.

Consequência prática: o simulador roda, se valida e produz resultados **sem o Minecraft
instalado**. Isso é o que torna o projeto testável.

## Camadas

```
┌─────────────────────────────────────────────────────────┐
│ L0  RAW        data/raw/  — imutável, validado por checksum
├─────────────────────────────────────────────────────────┤
│ L1  NORMALIZED nodes.parquet / edges.parquet
│                nid int32, sinal ±1, limiar aplicado
├─────────────────────────────────────────────────────────┤
│ L2  GRAPH      Matriz esparsa CSR + vetor de sinal
│                overrides de neurotransmissor aplicados
├─────────────────────────────────────────────────────────┤
│ L3  ENGINE     LIF · passo dt=1 ms · estado (V, refratário)
│                → spikes[t] : bool[625]
├─────────────────────────────────────────────────────────┤
│ L4  MOTOR      92 descendentes → vetor de comportamento
│                agregação por grupo funcional
├─────────────────────────────────────────────────────────┤
│ L5  BRIDGE     Socket TCP · JSON-lines · 20 Hz
├─────────────────────────────────────────────────────────┤
│ L6  EMBODIMENT Plugin Paper — sensores e atuadores da abelha
└─────────────────────────────────────────────────────────┘
        ╰─→ TELEMETRIA: DuckDB (spikes, estímulos, ações)
```

Cada camada só conhece a anterior. Trocar o mob (L6) não toca no engine (L3); trocar o
modelo de neurônio (L3) não toca no ingest (L1).

## O problema do relógio

**A decisão mais consequente do projeto.** Escalas incompatíveis:

| Sistema | Passo natural |
|---|---|
| Neurônio de *Drosophila* | ~0,1–1 ms |
| Engine (dt escolhido) | 1 ms |
| Tick do Minecraft | 50 ms (20 Hz) |

Um tick do jogo = **50 passos de simulação**. Três arranjos possíveis:

| Arranjo | Como funciona | Veredito |
|---|---|---|
| **Síncrono acoplado** | Jogo espera o simulador terminar 50 passos | ❌ trava o servidor se o simulador atrasar |
| **Assíncrono desacoplado** ✅ | Simulador roda livre em thread própria; jogo lê o último vetor motor disponível | **Escolhido.** Jogo nunca bloqueia; simulação nunca é distorcida pelo jogo |
| **Lockstep lento** | Jogo e simulador ambos a 20 Hz, dt=50 ms | ❌ dt de 50 ms destrói a dinâmica do LIF |

O acoplamento assíncrono significa que o vetor motor é **sempre levemente antigo** (até
50 ms). Para o comportamento estudado, isso é aceitável e explicitamente documentado —
não é um bug a corrigir.

## Contratos

### L3 → L4 · saída do engine
```python
SpikeFrame = {
    "t_ms": int,           # tempo da simulação
    "spikes": NDArray[bool],  # shape (625,), indexado por nid
}
```

### L4 → L5 · vetor motor
```json
{"t_ms": 12450, "motor": {"forward": 0.31, "yaw": -0.12, "lift": 0.78}, "active_dn": 14}
```
Valores em [-1, 1], já normalizados por taxa de disparo. O jogo **não vê neurônios** —
vê intenção motora. Se o mob mudar, só L4 muda.

> **Contrato aspiracional.** O que roda hoje (F2/F3) manda os 8 grupos provisórios de
> RN-08 (`{"DNp": 0.31, "DNg": -0.02, ...}`), não `forward`/`yaw`/`lift` — essa
> curadoria semântica ainda não foi feita (ver `docs/04-regras-de-negocio.md`, RN-08).
> Os valores também são taxa (≥0 na prática), não direção com sinal, até RN-08 agrupar
> por função com pares agonista/antagonista. Reconciliar antes da F4.

### L6 → L5 · sensores
```json
{"t_ms": 12450, "light": 0.94, "dorsal_light": 0.61, "damage": false,
 "touch_contact": false, "touch_proximity": false, "raining": false,
 "alarm_explosion": false, "alarm_hostile_mob": false, "sound_music": false,
 "looming_threat": false, "food_contact": false}
```
Dois campos opcionais, adicionados na F5 (omitidos no exemplo acima por
serem raros — a maioria das mensagens não os inclui):

```json
{"mute": ["DNp", "sensory"]}
{"stimulate": {"group": "DNg", "amplitude": 3.0}}
```

`mute` (ferramenta de lesão por comando) e `stimulate` (estimulação dirigida)
só mudam o estado no simulador quando o campo está PRESENTE na mensagem —
ausência mantém o que já estava configurado, não reseta a cada mensagem. Ver
`server.py` e `plugin/README.md` para os comandos que expõem isso
(`/flywirebee mute|unmute|stimulate`).

**`touch_contact`/`touch_proximity` (F7/AD-17, 20/09/2026) — família de
sensores de toque, decisão do usuário: toque engloba contato com bloco, dano
(`damage`, já existia), e aproximação de mob/jogador/objeto. Dois campos
NOVOS, sinais de natureza diferente:**
- `touch_contact` — **borda** (consumido a cada leitura, mesmo padrão de
  `damage`): heurística de deslocamento real vs. esperado pela velocidade
  comandada (Bukkit não tem evento de "colidiu com bloco" pra entidade
  controlada por código). **✅ Validado em servidor real (20/09/2026)** —
  teste controlado (abelha presa num cubículo 4×4) deu disparo quase
  contínuo por 2+ minutos; teleportada pra área aberta, 81s seguidos sem
  nenhum disparo. Limiar (`CONTACT_RATIO_THRESHOLD = 0.5`) segue sem
  calibração fina, mas o mecanismo funciona. Ver `plugin/README.md`.
- `touch_proximity` — **nível** (verdadeiro enquanto algo estiver perto,
  não só no instante em que chegou): mob, jogador ou item dentro de um raio
  fixo. **✅ Validado** (usuário confirmou: longe = false, aproximar sem
  encostar = true).

**✅ Consumido pelo simulador (20/09/2026).** `SimulationServer` ganhou um
segundo `Engine` opcional pro `bristle` (`bristle_connectome`, default
`None` — sem quebrar quem só usa o ocelar). `damage`/`touch_contact`/
`touch_proximity` combinam em OR simples: qualquer um presente estimula a
semente do `bristle` com `SENSOR_TOUCH_AMPLITUDE` (mesmo valor já validado
em `tools/bristle_calibration_check.py`, RN-09). A resposta ganha
`bristle_motor` (canais `grooming` + `conn_DN_*`, telemetria — RN-08
equivalente ainda sem lesão validando em servidor real) e
`bristle_active_dn`. Testado de ponta a ponta contra o container Docker
real (não só nos testes automatizados): `grooming` saturou perto de 1,0
sob `touch_contact` sustentado, consistente com o efeito ~18× já medido.

**✅ `grooming` virou controle real (20/09/2026), decisão do usuário.**
`MotorMapping.java` faz a abelha ignorar `phototaxis` e pousar/ficar parada
quando `grooming` passa de `GROOMING_THRESHOLD` — primeira vez que um canal
do `bristle` sai de telemetria pura pra efeito observável. Constantes
provisórias, sem validação visual em servidor real ainda. Ver
`docs/03-roadmap-fases.md` F7, `sim/src/flywire_sim/server.py`,
`sim/src/flywire_sim/bristle_motor.py`,
`plugin/.../MotorMapping.java`.

**`raining` (F7/AD-17, 21/09/2026) — terceiro `Engine`, subcircuito `hygro`
(chuva).** Campo novo, sinal de NÍVEL (verdadeiro enquanto
`World#hasStorm()` for true, não um evento de borda como `touch_contact`).
`SimulationServer` ganhou `hygro_connectome` opcional (default `None`, mesmo
padrão do `bristle_connectome` — sem quebrar quem só usa ocelar/bristle).
`raining` estimula a semente higrossensorial com `SENSOR_RAIN_AMPLITUDE`
(mesmo valor validado em `tools/hygro_calibration_check.py`, RN-09: p≈0 nos
dois grupos de topologia, N=30). Resposta ganha `hygro_motor` (canal
`hygrotaxis`, topologia de sinal — mesmo mecanismo que gerou `phototaxis`
antes de RN-08 existir, ver `hygro_motor.py`) e `hygro_active_dn`. **Sem
curadoria RN-08 equivalente ainda** (nenhuma leitura de literatura/BANC feita
pros 41 tipos de descendente do hygro) e **sem lesão em servidor real** —
`hygrotaxis` é telemetria/visualização, não entra em `MotorMapping.java`.

**`alarm_explosion`/`alarm_hostile_mob` (F8, 23/09/2026) — quarto `Engine`,
subcircuito `johnston` (vento/som).** Dois campos novos, deliberadamente
mais restritos que a família de toque: `alarm_explosion` (borda,
`EntityExplodeEvent`/`BlockExplodeEvent` num raio de 16 blocos) e
`alarm_hostile_mob` (nível, só `Monster` do Bukkit — não qualquer mob —
num raio de 8 blocos). Desenho baseado em achado citado pelo usuário
(Eberl, Hardy & Kernan 2000: toque e som compartilham mecanismo de
transdução em *Drosophila*, mas toque com objeto pequeno é inofensivo e
não dispara fuga) — ver `AlarmSensor.java`. `SimulationServer` ganhou
`johnston_connectome` opcional, mesmo padrão dos outros três.
`alarm_explosion`/`alarm_hostile_mob` combinam em OR e estimulam a semente
do órgão de Johnston com `SENSOR_ALARM_AMPLITUDE` (validado em
`tools/johnston_calibration_check.py`, RN-09: diff média=433,87, p≈0,
N=30 — testado também contra o container Docker real). Resposta ganha
`johnston_motor` (canal `startle`, mesmo mecanismo de topologia de sinal)
e `johnston_active_dn`. Sem curadoria RN-08 equivalente e sem lesão em
servidor real — `startle` é telemetria/visualização, não entra em
`MotorMapping.java`. (`sound_music`, mesmo campo, som AMBIENTE não só
ameaça — jukebox tocando por perto, pedido do usuário 24/09/2026,
combina em OR com os outros dois.)

**`looming_threat`/`damage` (F9/AD-20, 24-25/09/2026) — quinto `Engine`,
subcircuito `escape` (fuga por looming).** Semente do `escape` (LC4/LPLC2,
detectores de looming) não é `super_class=="sensory"` como os outros
circuitos — `ingest.build` ganhou `sensory_cell_types` pra marcar
role="sensory" por identidade de `cell_type` só pra este circuito (ver
RN-04/AD-20 em `docs/04-regras-de-negocio.md`). `SimulationServer` ganhou
`escape_connectome` opcional. `looming_threat` (`LoomingSensor.java` —
distância até ameaça mais próxima caindo rápido, limiar recalibrado duas
vezes em teste real) OU `damage` (hit real, sinal de ameaça mais forte que
aproximação) estimulam a semente com `SENSOR_LOOMING_AMPLITUDE` (validado
em `tools/escape_calibration_check.py`, RN-09: diff média=63,07, p≈0,
N=30). Resposta ganha `escape_motor` (canal `escape_drive`, curado por
identidade de tipo celular — só `DNp01`+`DNp02`, não os outros 29
descendentes alcançados em 1 salto, ver RN-08 em
`docs/04-regras-de-negocio.md`) e `escape_active_dn`. **Virou
comportamento real** (prioridade máxima sobre hygro/grooming/phototaxis,
com trava temporal de ~2,5s) — confirmado em servidor real.

**`food_contact` (F10/AD-21, 25/09/2026) — sexto `Engine`, subcircuito
`taste` (paladar apetitivo).** Semente = 129 GRNs `cell_sub_class ==
"sugar/water"` (`super_class=="sensory"`, sem precisar de
`sensory_cell_types` — diferente do escape). `SimulationServer` ganhou
`taste_connectome` opcional. `food_contact` (`TasteSensor.java` — contato
com bloco de comida como mel/melancia/abóbora/bolo/plantação madura, item
comestível largado no chão, ou jogador segurando comida por perto, pedido
do usuário: mosca prova com o corpo todo) estimula a semente com
`SENSOR_TASTE_AMPLITUDE` (validado em `tools/taste_calibration_check.py`,
RN-09: diff média=120,73, p≈0, N=30). Resposta ganha `taste_motor` (canal
`appetite`, topologia de sinal — mesmo mecanismo de `hygrotaxis`/`startle`)
e `taste_active_dn`. Sem lesão em servidor real ainda — `appetite` é
telemetria pura, não entra em `MotorMapping.java` (decisão do usuário:
só telemetria por enquanto, sem mudar comportamento de voo).

### Protocolo da ponte
- TCP em `localhost:8765`, JSON-lines (`\n`), UTF-8.
- Sem handshake, sem estado de sessão. Reconexão é reinício.
- Simulador é **servidor**; plugin é cliente. O simulador sobrevive ao jogo, não o contrário.
- Se o plugin cair, o simulador continua rodando e gravando telemetria.

## Persistência

| Dado | Formato | Onde |
|---|---|---|
| Conectoma normalizado | Parquet | `data/processed/` |
| Spike trains | DuckDB | `data/processed/runs.duckdb` |
| Estímulos e ações | DuckDB (mesma base) | idem |
| Manifesto de proveniência | JSON | `data/processed/manifest.json` |

DuckDB lê Parquet nativamente, então uma consulta pode cruzar spikes com anotações de
tipo celular sem ETL intermediário — que é exatamente a análise que vamos querer fazer.

Sem servidor de banco: a carga é analítica, de escrita sequencial e leitura em lote.
Postgres resolveria o mesmo problema cobrando um container e latência de rede.

## Múltiplos subcircuitos (AD-17, F7, 19/09/2026)

**Decisão:** cada sensor novo (chuva, toque) é um **subcircuito independente**,
extraído e — quando chegar a hora de L2/L3 — simulado por um `Engine` próprio,
não fundido no grafo ocelar de 625 neurônios. Escolhido em vez de um grafo
único maior (ocelar ∪ hygro ∪ bristle) porque:

- **Isola o risco.** Uma lesão/estimulação num circuito não pode vazar efeito
  pro outro só por estarem no mesmo grafo — mesma disciplina que já levou a
  separar `phototaxis` de `locomotion_drive` em RN-08 (misturar canais que não
  respondem ao mesmo estímulo dilui o sinal, 3 ocorrências documentadas).
- **Não reabre RN-09 para o ocelar.** `BIAS_CURRENT`/`NOISE_STD` foram
  calibrados para 625 neurônios (RN-09); um grafo único de milhares de
  neurônios precisaria recalibrar do zero, arriscando quebrar a validação já
  feita (lesão p=0,0014, F4).
- **Contido, não evitado — RN-01a ainda reabre por circuito.** Cada subcircuito
  grande o bastante (2 saltos) continua expondo neuromoduladores em massa
  (ver RN-01a em `04-regras-de-negocio.md`), só que o efeito fica isolado
  dentro do subcircuito que precisa dele, não contamina o ocelar.

**Consequência de implementação:** `ingest.select_seed`/`ingest.build`
generalizados para aceitar `pattern`/`hops`/`out_dir`/`circuit` (antes,
constantes fixas em `config.py`, um subcircuito só). Chamada sem argumentos
continua produzindo exatamente o circuito ocelar em `data/processed/` —
nada muda para quem já consome esse caminho. Circuitos novos escrevem em
`data/processed/<circuito>/` (nunca no diretório raiz de `processed/`, pra
não colidir com o ocelar). Ver `sim/tools/build_f7_circuits.py`.

**O que isso NÃO decide ainda:** como `server.py`/`engine.py` vão rodar
múltiplos `Engine`s ao mesmo tempo (hoje só existe um, RN-06), nem como o
protocolo da ponte expõe sensores/canais motores de mais de um circuito pro
plugin. Isso é trabalho de L3/L5 da fase real de F7, não decidido aqui — esta
seção cobre só a extração (L0→L1).

## Docker

Só o simulador é containerizado. O servidor Minecraft roda nativo no Windows, porque
containerizá-lo traz dor de rede e I/O sem ganho de reprodutibilidade — o que precisa ser
reproduzível é a **simulação**, não o jogo.

A ponte atravessa a fronteira do container via porta publicada em `localhost`.
