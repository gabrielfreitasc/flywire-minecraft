"""
Constantes científicas e caminhos. Nenhum número com significado biológico
deve aparecer inline em outro módulo — todos vivem aqui.
"""
from __future__ import annotations

import os
from pathlib import Path

# ---------------------------------------------------------------- caminhos
# .../flywire-minecraft/sim/src/flywire_sim/config.py -> parents[3] = raiz do projeto
ROOT = Path(__file__).resolve().parents[3]
# No container, data/ é montado em /data (ver docker-compose.yml)
DATA = Path(os.environ.get("FLYWIRE_DATA_DIR", ROOT / "data"))
RAW = DATA / "raw"
INTERIM = DATA / "interim"
PROCESSED = DATA / "processed"

CONNECTIVITY_FILE = RAW / "Connectivity_783.parquet"
ANNOTATIONS_FILE = RAW / "Supplemental_file1_neuron_annotations.tsv"
DUCKDB_FILE = PROCESSED / "runs.duckdb"

# ------------------------------------------------- validação da fonte (L0)
# Números publicados em Dorkenwald et al. 2024. O ingest aborta se divergir.
# Ver data/raw/README.md.
EXPECTED_TOTAL_SYNAPSES = 54_492_922
EXPECTED_EDGES_ABOVE_THRESHOLD = 2_700_513
EXPECTED_ANNOTATION_ROWS = 139_248

# --------------------------------------------------- extração do subcircuito
MATERIALIZATION = 783
SYN_THRESHOLD = 5          # RN-03 — artigo usa ">4"
HOPS = 1                   # AD-06 — 2 saltos explode para 10.578 neurônios
SEED_PATTERN = "ocell"     # casa em super_class/cell_class/cell_sub_class/supertype/cell_type

# ------------------------------------------------------------ RN-01 / RN-02
EXCITATORY_NT = ("acetylcholine", "dopamine", "octopamine")
INHIBITORY_NT = ("gaba", "glutamate")  # glutamato é INIBITÓRIO em Drosophila (GluCl)

# Fotorreceptores ocelares são histaminérgicos (inibitórios). O classificador de
# Eckstein et al. não tem histamina entre suas 6 classes e os rotula como serotonina.
PHOTORECEPTOR_SIGN = -1

# ------------------------------------------------------------ modelo (RN-07)
DT_MS = 1.0                # passo de integração
V_REST = 0.0               # potencial de repouso (normalizado)
V_THRESHOLD = 1.0          # limiar de disparo
TAU_MS = 20.0              # constante de tempo de membrana
REFRACTORY_MS = 2.0        # RN-07

# Ganho aplicado ao peso sináptico (nº de sinapses) para chegar a corrente.
# Calibrado na F1 (RN-09) por varredura — ver docs/04-regras-de-negocio.md.
# Reproduzível via `python tools/calibration_check.py`.
SYNAPTIC_GAIN = 0.01

# RN-09 — corrente tônica de base (bias) + ruído, igual em distribuição para
# todo neurônio.
# Os 273 fotorreceptores da semente são 100% inibitórios (RN-02): com V_REST=0
# e V_THRESHOLD>0, corrente puramente inibitória nunca cruza um limiar positivo,
# então sem atividade basal nenhum sinal sai da camada sensorial — não é ajuste
# de parâmetro, é impossibilidade matemática do modelo em repouso absoluto.
# BIAS_CURRENT representa disparo espontâneo/tônico (bombardeio sináptico não
# modelado), técnica padrão em redes LIF (cf. Brunel 2000).
#
# Bias sozinho não basta: com todo neurônio partindo do mesmo V_REST e do mesmo
# bias, a rede é perfeitamente homogênea e sincroniza — todo mundo cruza o
# limiar no mesmo passo só pelo bias, mascarando qualquer efeito real do
# circuito. NOISE_STD adiciona ruído gaussiano i.i.d. por neurônio a cada
# passo (bombardeio sináptico de fundo não correlacionado), quebrando a
# sincronia artificial. NOISE_SEED fixa a semente para reprodutibilidade.
# Calibrados os três (BIAS_CURRENT, NOISE_STD, SYNAPTIC_GAIN) juntos por
# varredura — ver AD-13 e RN-09 em docs/04-regras-de-negocio.md.
BIAS_CURRENT = 0.045
NOISE_STD = 0.05
NOISE_SEED = 0

# ----------------------------------------------------------- ponte (RN-06)
BRIDGE_HOST = "0.0.0.0"
BRIDGE_PORT = 8765
MOTOR_WINDOW_MS = 50.0     # janela para converter disparos em taxa

# Ganho do sensor "light" (plugin -> sim, [0,1]) para amplitude de estímulo
# nos fotorreceptores. PROVISÓRIO — direção é a correta (RN-02: luz despolariza
# o fotorreceptor histaminérgico, aumenta disparo), mas a escala não foi
# calibrada contra dinâmica de luz real do Minecraft. Revisitar na F4.
SENSOR_LIGHT_GAIN = 2.0

# ------------------------------------------------------------------ RN-08
# Escala usada para normalizar taxa de disparo (Hz) em (-1, 1) via tanh.
# Ordem de grandeza da taxa basal observada no subcircuito v1 (RN-09) com
# bias/ruído calibrados: ~25-30 Hz por neurônio. Usada tanto pelos 8 canais
# provisórios por prefixo de cell_type (sem direção, RN-08 ainda sem
# curadoria) quanto pelo canal `phototaxis` (com direção real, baseado na
# topologia de sinal validada — ver motor.py e RN-08/RN-09 em
# docs/04-regras-de-negocio.md).
MOTOR_RATE_SCALE = 30.0
