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
{"t_ms": 12450, "light": 0.94, "dorsal_light": 0.61, "damage": false}
```

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

## Docker

Só o simulador é containerizado. O servidor Minecraft roda nativo no Windows, porque
containerizá-lo traz dor de rede e I/O sem ganho de reprodutibilidade — o que precisa ser
reproduzível é a **simulação**, não o jogo.

A ponte atravessa a fronteira do container via porta publicada em `localhost`.
