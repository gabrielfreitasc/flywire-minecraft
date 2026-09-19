"""
F7 (AD-17) — extrai os dois subcircuitos candidatos a sensor novo, mesma
mecânica da F0 (`ingest.select_seed`/`ingest.build`, generalizados para
aceitar padrão/hops/diretório de saída), semente diferente:

- `hygro`  — 74 neurônios `cell_class == "hygrosensory"` (HRN_VP4/VP1d/VP5/VP1l,
  Frank et al. 2017 / Enjin et al. 2016). Candidato a sensor de chuva/umidade.
  1 salto só alcança 2 descendentes (cadeia fraca demais pra validar por
  lesão) — usado **2 saltos**, decisão do usuário (19/09/2026), que reabre
  RN-01a (ver docs/04-regras-de-negocio.md).
- `bristle` — 1.417 neurônios `cell_sub_class` em {"eye bristle", "head bristle"}
  (mecanossensorial de contato direto, não confundir com órgão de Johnston
  `wind_gravity`/`auditory`, que mede vento/som, não toque). Candidato a
  sensor de toque. **1 salto** — mesmo padrão do circuito ocelar (AD-06),
  110 descendentes alcançados, sem precisar reabrir RN-01a de forma severa
  (36/1417 neurônios semente com `top_nt=serotonin`, ruído de classificador
  normal — maioria é acetilcolina, 90,8%, sem artefato sistemático tipo RN-02).

Escreve em `data/processed/hygro/` e `data/processed/bristle/` — nunca em
`data/processed/` direto, pra não colidir com o circuito ocelar da v1.

Uso:  python tools/build_f7_circuits.py   (a partir de sim/, venv ativo)

Isto é só L0→L1 (extração + validação de contagem). L2/L3 (sinal RN-01,
override tipo RN-02 se necessário, calibração de bias/ruído RN-09) é o
próximo passo, não feito aqui — mesma sequência de fases que o circuito
ocelar seguiu (F0 antes de F1).
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest


def build_all() -> dict[str, dict]:
    hygro = ingest.build(
        pattern="hygro", hops=2, out_dir=C.PROCESSED / "hygro", circuit="hygro"
    )
    bristle = ingest.build(
        pattern="bristle", hops=1, out_dir=C.PROCESSED / "bristle", circuit="bristle"
    )
    return {"hygro": hygro, "bristle": bristle}


if __name__ == "__main__":
    print(json.dumps(build_all(), indent=2, ensure_ascii=False))
