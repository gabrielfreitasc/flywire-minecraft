"""Constantes científicas e caminhos."""
from __future__ import annotations

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DATA = Path(os.environ.get("FLYWIRE_DATA_DIR", ROOT / "data"))
RAW = DATA / "raw"
INTERIM = DATA / "interim"
PROCESSED = DATA / "processed"

CONNECTIVITY_FILE = RAW / "Connectivity_783.parquet"
ANNOTATIONS_FILE = RAW / "Supplemental_file1_neuron_annotations.tsv"
DUCKDB_FILE = PROCESSED / "runs.duckdb"

# Dorkenwald et al. 2024
EXPECTED_TOTAL_SYNAPSES = 54_492_922
EXPECTED_EDGES_ABOVE_THRESHOLD = 2_700_513
EXPECTED_ANNOTATION_ROWS = 139_248

MATERIALIZATION = 783
SYN_THRESHOLD = 5  # RN-03
HOPS = 1  # AD-06
SEED_PATTERN = "ocell"

# RN-01, RN-02
EXCITATORY_NT = ("acetylcholine", "dopamine", "octopamine")
INHIBITORY_NT = ("gaba", "glutamate")
# AD-29: inibitórios rápidos fora da tabela; known_nt com eles não sobrescreve
KNOWN_NT_AMBIGUOUS = ("histamine", "glycine")

# RN-02, AD-03: fotorreceptores são histaminérgicos
PHOTORECEPTOR_SIGN = -1

# RN-01a, AD-18 (Yasuyama & Salvaterra 1999; Schlegel et al. 2021)
OLFACTORY_RECEPTOR_SIGN = 1
ANTENNAL_LOBE_LOCAL_NEURON_SIGN = -1

# RN-01a, AD-19 (Kitamoto et al. 1995)
JOHNSTON_ORGAN_SIGN = 1

# RN-01a, AD-21 (Yasuyama & Salvaterra 1999)
GUSTATORY_RECEPTOR_SIGN = 1

# Escalas próprias por circuito: RN-09, medidas antes de fixar limiar (F9–F19)
# F10
TASTE_MOTOR_RATE_SCALE = 60.0

# F12; AD-29 reduziu a margem do canal thermal (RN-01)
THERMO_MOTOR_RATE_SCALE = 60.0

# F17
OLFACTORY_MOTOR_RATE_SCALE = 60.0

# F18
AGGRESSION_MOTOR_RATE_SCALE = 60.0

# F19
PATH_INTEGRATION_MOTOR_RATE_SCALE = 60.0

# RN-07
DT_MS = 1.0
V_REST = 0.0
V_THRESHOLD = 1.0
TAU_MS = 20.0
REFRACTORY_MS = 2.0  # RN-07

# F1, RN-09
SYNAPTIC_GAIN = 0.01

# RN-09, AD-13: corrente tônica + ruído (Brunel 2000)
BIAS_CURRENT = 0.045
NOISE_STD = 0.05
NOISE_SEED = 0

# RN-06
BRIDGE_HOST = "0.0.0.0"
BRIDGE_PORT = 8765
MOTOR_WINDOW_MS = 50.0

# Amplitude de estímulo por sensor: F4–F19
# RN-02, F4
SENSOR_LIGHT_GAIN = 2.0

# F7, AD-17, RN-09
SENSOR_TOUCH_AMPLITUDE = 2.0

# F7, AD-17, RN-09
SENSOR_RAIN_AMPLITUDE = 2.0

# F8, RN-09
SENSOR_ALARM_AMPLITUDE = 2.0

# F9, AD-20
SENSOR_LOOMING_AMPLITUDE = 2.0

# F10, AD-21
SENSOR_TASTE_AMPLITUDE = 2.0

# F12
SENSOR_THERMO_AMPLITUDE = 2.0

# F13
SENSOR_SLEEP_AMPLITUDE = 2.0

# F17
SENSOR_OLFACTORY_AMPLITUDE = 2.0

# F18
SENSOR_AGGRESSION_AMPLITUDE = 2.0

# F19
SENSOR_PATH_INTEGRATION_AMPLITUDE = 2.0

# RN-08, RN-09
MOTOR_RATE_SCALE = 30.0

# F9
ESCAPE_MOTOR_RATE_SCALE = 150.0
