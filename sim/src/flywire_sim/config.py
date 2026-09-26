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

# RN-01a/AD-18 — mesmo padrão de artefato do classificador que gerou
# PHOTORECEPTOR_SIGN, achado ao investigar o subcircuito `hygro` (F7):
# Eckstein et al. rotula "serotonin" quando a evidência real aponta pra outro
# transmissor. Dois grupos identificados por fonte independente do
# classificador, não por suposição:
# - ORNs (`cell_class == "olfactory"`) são colinérgicas — estabelecido
#   (Yasuyama & Salvaterra 1999; Barbara et al. 2005).
# - Neurônios locais do lobo antenal (`cell_class == "ALLN"`, famílias
#   lLN1/lLN2) são GABAérgicos — Schlegel et al. 2021 (eLife), atribuição de
#   transmissor por hemilinhagem via imuno-histoquímica.
# Ver RN-01a em docs/04-regras-de-negocio.md.
OLFACTORY_RECEPTOR_SIGN = 1
ANTENNAL_LOBE_LOCAL_NEURON_SIGN = -1

# RN-01a/AD-19 — mesmo padrão de artefato, achado ao investigar o
# subcircuito `johnston` (F8, vento/som): neurônios do órgão de Johnston
# (`cell_sub_class` em {"wind_gravity", "auditory"}) são colinérgicos —
# estabelecido via expressão de ChAT (Kitamoto et al. 1995, J Neurobiol
# 28:70-81; Yasuyama & Salvaterra 1999 — mesmos autores que já confirmam
# identidade colinérgica dos ORNs em RN-01a/AD-18). Ver RN-01a em
# docs/04-regras-de-negocio.md.
JOHNSTON_ORGAN_SIGN = 1

# RN-01a/AD-21 — mesmo padrão de artefato, achado ao investigar o
# subcircuito `taste` (F10, paladar): GRNs de açúcar/água (`cell_sub_class
# == "sugar/water"`) são colinérgicas — mesma evidência independente de
# ChAT já usada em RN-01a/AD-18/AD-19 (Yasuyama & Salvaterra 1999, expressão
# de ChAT em neurônios sensoriais periféricos, não específica de ORN — se
# aplica a quimiorreceptores em geral). Achado real: dentro do MESMO
# cell_type (LB3), 84/122 acetilcolina, 27/122 serotonin, 11/122 glutamato
# — heterogeneidade de rótulo numa população geneticamente homogênea, igual
# ao padrão já visto em ORN/órgão de Johnston. Ver RN-01a em
# docs/04-regras-de-negocio.md.
GUSTATORY_RECEPTOR_SIGN = 1

# F10 — escala PRÓPRIA do canal `appetite` (grupo excitatório de 12
# descendentes do `taste` — ver `taste_motor.py`). Mesma disciplina de
# `ESCAPE_MOTOR_RATE_SCALE`: checado ANTES de fixar limiar nenhum (lição do
# F9 — não repetir o erro de assumir que a escala genérica serve). Medido
# isolado, sem estímulo: baseline 22,2 Hz ± 4,3, p95=30,0 Hz — mais perto
# do esperado que o escape, mas ainda satura demais com `MOTOR_RATE_SCALE`
# genérico (tanh p95=0,762, perigosamente perto de qualquer limiar de
# 0,8). Estimulado: 219,3 Hz. Escala 60 separa bem: tanh baseline
# média=0,352 (p95=0,462), tanh estimulado=0,999.
TASTE_MOTOR_RATE_SCALE = 60.0

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

# F7/AD-17 — amplitude de estímulo na semente do subcircuito `bristle`
# quando `damage`/`touch_contact`/`touch_proximity` indicam toque (ligado/
# desligado, não um nível contínuo como `light` — daí ser uma amplitude
# fixa, não um ganho multiplicado por um valor [0,1]). MESMO valor usado em
# `tools/bristle_calibration_check.py`, onde já foi validado (RN-09: efeito
# ~18x o baseline, p≈0, N=30) — não é um número novo/arbitrário, é o mesmo
# que produziu o resultado documentado em RN-09. Não recalibrado contra
# dinâmica real de toque no Minecraft (só o SENSOR de toque foi validado em
# servidor real — ver plugin/README.md; a amplitude de estímulo resultante
# no circuito ainda não).
SENSOR_TOUCH_AMPLITUDE = 2.0

# F7/AD-17 — amplitude de estímulo na semente do subcircuito `hygro` quando
# `raining` (World#hasStorm(), ligado/desligado) indica chuva. MESMO valor
# usado em `tools/hygro_calibration_check.py`, onde já foi validado (RN-09:
# diff média=139,90 no grupo excitatório e 29,03 no inibitório, p≈0 nos
# dois, N=30) — mesma disciplina de SENSOR_TOUCH_AMPLITUDE acima. Não
# calibrado contra dinâmica real de chuva no Minecraft ainda.
SENSOR_RAIN_AMPLITUDE = 2.0

# F8 — amplitude de estímulo na semente do subcircuito `johnston` quando
# `alarm_explosion`/`alarm_hostile_mob` indicam alarme (ligado/desligado,
# mesma lógica de SENSOR_TOUCH_AMPLITUDE — evento discreto, não nível
# contínuo). MESMO valor usado em `tools/johnston_calibration_check.py`,
# onde já foi validado (RN-09: diff média=433,87 no grupo excitatório,
# p≈0, N=30) — mesma disciplina das duas constantes acima. Não calibrado
# contra dinâmica real de alarme no Minecraft ainda.
SENSOR_ALARM_AMPLITUDE = 2.0

# F9 — amplitude de estímulo na semente do subcircuito `escape` (LC4/LPLC2,
# detectores de looming — ver AD-20) quando o sensor de ameaça no jogo
# indica aproximação rápida (ligado/desligado, mesma lógica das três
# constantes acima). MESMO valor usado em `tools/escape_calibration_check.py`
# — mesma disciplina, não recalibrado contra dinâmica real de looming no
# Minecraft ainda (nem o sensor do lado do plugin existe ainda).
SENSOR_LOOMING_AMPLITUDE = 2.0

# F10 — amplitude de estímulo na semente do subcircuito `taste` (GRNs de
# açúcar/água — ver AD-21) quando o sensor de comida no jogo indica contato
# com bloco/item comestível (ligado/desligado, mesma lógica das constantes
# acima). MESMO valor usado em `tools/taste_calibration_check.py` — mesma
# disciplina, não calibrado contra dinâmica real de alimentação no
# Minecraft ainda.
SENSOR_TASTE_AMPLITUDE = 2.0

# ------------------------------------------------------------------ RN-08
# Escala usada para normalizar taxa de disparo (Hz) em (-1, 1) via tanh.
# Ordem de grandeza da taxa basal observada no subcircuito v1 (RN-09) com
# bias/ruído calibrados: ~25-30 Hz por neurônio. Usada tanto pelos 8 canais
# provisórios por prefixo de cell_type (sem direção, RN-08 ainda sem
# curadoria) quanto pelo canal `phototaxis` (com direção real, baseado na
# topologia de sinal validada — ver motor.py e RN-08/RN-09 em
# docs/04-regras-de-negocio.md).
MOTOR_RATE_SCALE = 30.0

# F9 (25/09/2026) — escala PRÓPRIA do canal `escape_drive` (DNp01+DNp02, só
# 4 neurônios — ver `escape_motor.py`). Achado real, servidor real: com
# MOTOR_RATE_SCALE genérico (30, calibrado pro ocelar inteiro), a taxa
# BASAL desse grupo pequeno e muito convergente já satura o tanh — medido
# isolado (`tools/escape_calibration_check.py`-style, sem estímulo): média
# 38,3 Hz, desvio 10,0, p95=55,0 Hz. Com escala 30, isso vira tanh
# média=0,830 (73% das amostras já acima de um limiar de 0,8 SEM nenhum
# estímulo) — mesmo tipo de erro de calibração que já aconteceu com
# `GROOMING_THRESHOLD` (F7: baseline perto demais do limiar), mas aqui a
# causa é a ESCALA, não o limiar. Estimulado (`SENSOR_LOOMING_AMPLITUDE`):
# 340 Hz, desvio 0,0 (satura, ~68% do teto teórico de 500 Hz dado o
# refratário de 2 ms). Escala 150 separa bem as duas distribuições: tanh
# baseline média=0,249 (p95=0,351), tanh estimulado=0,979 — gap de quase
# 0,63 entre as duas, bem mais folgado que o do grooming original.
ESCAPE_MOTOR_RATE_SCALE = 150.0
