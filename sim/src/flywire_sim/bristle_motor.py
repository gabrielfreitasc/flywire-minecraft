"""
Curadoria de comportamento por tipo de descendente pro subcircuito `bristle`
(F7/AD-17, candidato a sensor de toque) — equivalente de RN-08 (AD-14/AD-15),
fonte diferente, aplicada aos 60 tipos de descendente do `bristle` (não os
47 do ocelar; ver `motor.py` pra esses).

**Fonte:** BANC connectome (Bates, Phelps, Kim, Yang et al. 2026, Nature, DOI
10.1038/s41586-026-10735-w), tabela `banc_neck_functional_classes.csv`
(materialização v888), obtida de
https://github.com/htem/BANC-project/blob/main/data/banc_annotations/v888/
— mesmo produto de dado que gerou a Supplementary Data 6/9 usada em RN-08/
AD-15, só que lida direto do repositório público do projeto (o script que a
gera, `R/figures/panels_an_dn_umap.R`, grava o resultado ali) em vez do zip
do Harvard Dataverse, que o proxy deste ambiente bloqueia (mesma restrição
de egresso já documentada pra Zenodo, AD-11).

**Namiki/Cande et al. 2018 (eLife 34275) checado primeiro, sem achado.** Os
tipos legíveis na Figura 6 do paper (DNa01/02/05/07, DNb01, DNd02/03,
DNg07/08/10/12/13/25, DNp01/02/09/10/26/29) não batem com nenhum dos 60 tipos
do `bristle` — a numeração é da era pré-conectoma completo (driver lines
acessíveis por genética, um subconjunto pequeno). O PDF suplementar de 50 MB
(análise por linha split-GAL4) foi baixado e confirmado — de novo — não
confiável de interpretar (quase todo gráfico de rastreamento bruto por
imagem, não tabela), mesmo problema já registrado em RN-08 pro ocelar. Não
repetir a tentativa sem um formato novo de fonte.

**Coluna `cell_function` (literatura revisada pelos autores do BANC, mesmo
nível de evidência de Namiki 2018 + Supplementary Data 9):** cruzando os 60
tipos do `bristle`, **6 batem, todos `grooming`**: `DNg12_e`, `DNge011`,
`DNge012`, `DNge025`, `DNge028`, `DNge078`. Achado biologicamente coerente,
não coincidência de rótulo — cerdas mecanossensoriais de contato (a própria
semente do `bristle`, ver `docs/01-camada-de-dados.md`) disparando reflexo
de limpeza é via clássica documentada em *Drosophila* (contexto geral, não
citação por neurônio individual — a tabela do BANC não expõe referência por
linha): Hampel et al. 2015/2017 (circuito de limpeza antenal),
Seeds et al. 2014 (hierarquia de supressão de grooming sequencial). Fonte
do rótulo em si continua sendo só o BANC.

**Coluna `manual_cluster` (cluster de conectividade PCA-UMAP, evidência mais
fraca, mesmo nível de RN-08/AD-15 `CONNECTIVITY_CLUSTER_BANC`):** 52 dos 60
tipos têm cluster (`DN_01`...`DN_17`). **Sem nome descritivo disponível** —
diferente do que a curadoria do ocelar conseguiu pra alguns clusters
(`head_orienting`, `flight_power`, etc.), o código público do BANC-project
não expõe uma legenda id→nome pra estes códigos numéricos desta
materialização (checado em `R/figures/panels_an_dn_umap.R`, sem tabela de
tradução). **Não inventar nome** — exposto com o código bruto
(`BRISTLE_CONNECTIVITY_CLUSTER`), mesma disciplina de RN-08 contra fabricar
semântica.

**2 tipos sem dado nenhum:** `DNge008`, `DNge021` — nem comportamento nem
cluster no BANC. Registrado, não escondido.

**O que isto NÃO faz:** não há decodificador de motor rodando pro `bristle`
ainda — RN-09 (ver `docs/04-regras-de-negocio.md`) validou que o circuito
RESPONDE ao estímulo, mas nenhum vetor motor com significado foi calculado.
Isto é só a curadoria (pesquisa), reaproveitando a mecânica genérica de
`motor.py::group_by_published_behavior`/`group_by_connectivity_cluster`
(generalizadas nesta sessão pra aceitar um dicionário por circuito). Ver
`docs/03-roadmap-fases.md` F7.
"""
from __future__ import annotations

import numpy as np
from numpy.typing import NDArray

from .graph import Connectome
from .motor import group_by_connectivity_cluster as _group_by_connectivity_cluster
from .motor import group_by_published_behavior as _group_by_published_behavior

# Namiki/Cande et al. 2018 (eLife 34275) — checado, ZERO tipos do bristle
# batem (ver docstring do módulo). Registrado aqui como valor conferido, não
# omissão: não há dicionário Namiki pro bristle porque não há dado, não
# porque não foi procurado.

# BANC (Bates, Phelps, Kim, Yang et al. 2026), Supplementary Data 9
# equivalente (`cell_function`, banc_neck_functional_classes.csv v888).
BRISTLE_PUBLISHED_DN_BEHAVIOR: dict[str, str] = {
    "DNg12_e": "grooming",
    "DNge011": "grooming",
    "DNge012": "grooming",
    "DNge025": "grooming",
    "DNge028": "grooming",
    "DNge078": "grooming",
}

# BANC, Supplementary Data 6 equivalente (`manual_cluster`) — código bruto,
# sem nome descritivo disponível na fonte (ver docstring do módulo).
BRISTLE_CONNECTIVITY_CLUSTER: dict[str, str] = {
    "DNae007": "DN_10",
    "DNde006": "DN_11",
    "DNg15": "DN_11",
    "DNg20": "DN_13",
    "DNg23": "DN_06",
    "DNg29": "DN_01",
    "DNg35": "DN_10",
    "DNg37": "DN_09",
    "DNg39": "DN_10",
    "DNg48": "DN_09",
    "DNg54": "DN_09",
    "DNg57": "DN_11",
    "DNg59": "DN_11",
    "DNg61": "DN_09",
    "DNg81": "DN_11",
    "DNg83": "DN_13",
    "DNg84": "DN_13",
    "DNg85": "DN_11",
    "DNg87": "DN_11",
    "DNge001": "DN_17",
    "DNge002": "DN_02",
    "DNge003": "DN_09",
    "DNge019": "DN_17",
    "DNge029": "DN_09",
    "DNge036": "DN_09",
    "DNge037": "DN_09",
    "DNge038": "DN_01",
    "DNge039": "DN_17",
    "DNge041": "DN_09",
    "DNge044": "DN_17",
    "DNge051": "DN_09",
    "DNge054": "DN_03",
    "DNge055": "DN_09",
    "DNge056": "DN_09",
    "DNge057": "DN_11",
    "DNge065": "DN_03",
    "DNge067": "DN_09",
    "DNge080": "DN_09",
    "DNge082": "DN_01",
    "DNge096": "DN_09",
    "DNge098": "DN_01",
    "DNge100": "DN_09",
    "DNge101": "DN_10",
    "DNge104": "DN_13",
    "DNge105": "DN_11",
    "DNge121": "DN_11",
    "DNge122": "DN_13",
    "DNge128": "DN_09",
    "DNge132": "DN_11",
    "DNge133": "DN_13",
    "DNge139": "DN_01",
    "DNpe002": "DN_09",
}

# Sem dado nenhum no BANC (nem comportamento, nem cluster) — documentado,
# não usado em nada, aqui só pra quem for conferir cobertura não precisar
# recalcular.
BRISTLE_NO_DATA = ("DNge008", "DNge021")


def group_by_published_behavior(connectome: Connectome) -> dict[str, NDArray[np.int64]]:
    """Wrapper de `motor.group_by_published_behavior` com o dicionário do
    `bristle`. Ver docstring do módulo."""
    return _group_by_published_behavior(connectome, BRISTLE_PUBLISHED_DN_BEHAVIOR)


def group_by_connectivity_cluster(connectome: Connectome) -> dict[str, NDArray[np.int64]]:
    """Wrapper de `motor.group_by_connectivity_cluster` com o dicionário do
    `bristle`. Ver docstring do módulo."""
    return _group_by_connectivity_cluster(connectome, BRISTLE_CONNECTIVITY_CLUSTER)
