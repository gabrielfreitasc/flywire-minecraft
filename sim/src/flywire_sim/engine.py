"""
L3 — laço de integração.

Contrato:

    engine = Engine(connectome)
    engine.stimulate(nids, amplitude)
    frame = engine.step()      # -> SpikeFrame {t_ms, spikes}

RN-06 — roda a dt=1 ms; quando a ponte existir (F3), em thread própria,
desacoplado do tick do jogo. O engine nunca espera o jogo, e o jogo nunca
espera o engine.

RN-09 — toda corrente inclui um termo tônico de base (config.BIAS_CURRENT) e
ruído gaussiano i.i.d. por neurônio (config.NOISE_STD). Sem bias, a rede não
pode disparar fora da camada sensorial: os 273 fotorreceptores são 100%
inibitórios (RN-02), e corrente puramente inibitória nunca cruza um limiar
positivo a partir de V_REST=0. Sem ruído, o bias sozinho sincroniza todo mundo
(mesmo V_REST + mesmo bias = mesma trajetória), mascarando o circuito real.

Critério de saída da Fase 1 (RN-09): estimular os 273 fotorreceptores produz
efeito estatisticamente mensurável sobre a atividade de pelo menos um grupo de
descendentes, comparado a não estimular. Não é "disparou uma vez em 50 ms" —
um trial único é dominado pelo ruído de fundo necessário para o modelo sair do
silêncio (ver RN-09). A validação real é uma comparação pareada em várias
sementes de ruído (mesma semente em baseline e estimulado), separando os
descendentes pelo sinal do caminho topológico esperado (excitatório por
desinibição de 2 saltos vs. inibitório direto) — ver
`docs/04-regras-de-negocio.md`.

F5 — `set_silenced(nids)` remove um conjunto de neurônios da rede: eles ainda
integram corrente e podem "disparar" internamente (a própria dinâmica de
`neuron.py` não muda), mas o disparo é mascarado antes de: (a) contribuir pra
corrente recorrente de qualquer outro neurônio, e (b) aparecer no `SpikeFrame`
retornado. Equivale a cortar a saída sináptica do neurônio (mais parecido com
um bloqueio de neurotransmissor do que com matar o neurônio) — ferramenta de
exploração, não é o mesmo mecanismo do experimento de lesão da F4 (que corta
o estímulo de luz, não a saída do neurônio; ver `server.py`).

F5 — `set_directed_stimulus(nids, amplitude)` é um segundo canal de corrente
externa, independente de `stimulate()` (que server.py usa para o estímulo de
luz nos fotorreceptores). Os dois somam — estimular um grupo de descendentes
não desliga o estímulo de luz em andamento. Ferramenta de exploração: "cutucar"
um grupo específico e observar o efeito, sem precisar mexer no sensor de luz.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from . import config as C
from .graph import Connectome
from .neuron import LIFState
from .neuron import step as lif_step


@dataclass
class SpikeFrame:
    t_ms: int
    spikes: NDArray[np.bool_]  # shape (n,), indexado por nid


class Engine:
    """Integra o Connectome no tempo. Corrente = estímulo externo + recorrente."""

    def __init__(
        self,
        connectome: Connectome,
        dt_ms: float = C.DT_MS,
        seed: int = C.NOISE_SEED,
    ) -> None:
        self.connectome = connectome
        self.dt_ms = dt_ms
        self.state = LIFState.zeros(connectome.n)
        self._bias = np.full(connectome.n, C.BIAS_CURRENT, dtype=np.float32)
        self._external = np.zeros(connectome.n, dtype=np.float32)
        self._directed = np.zeros(connectome.n, dtype=np.float32)
        self._recurrent = np.zeros(connectome.n, dtype=np.float32)
        self._rng = np.random.default_rng(seed)
        self._silenced = np.zeros(connectome.n, dtype=bool)
        self.t_ms = 0

    def stimulate(self, nids: NDArray[np.integer], amplitude: float) -> None:
        """Define a corrente externa constante nos `nids` dados (substitui a anterior)."""
        self._external[:] = 0.0
        self._external[np.asarray(nids)] = amplitude

    def set_directed_stimulus(self, nids: NDArray[np.integer], amplitude: float) -> None:
        """F5 — canal de estímulo independente de `stimulate()`; os dois somam."""
        self._directed[:] = 0.0
        if len(nids):
            self._directed[np.asarray(nids)] = amplitude

    def set_silenced(self, nids: NDArray[np.integer]) -> None:
        """F5 — substitui o conjunto de neurônios silenciados (não acumula com o anterior)."""
        self._silenced[:] = False
        if len(nids):
            self._silenced[np.asarray(nids)] = True

    def step(self) -> SpikeFrame:
        """Avança um passo de dt_ms. A corrente recorrente do passo t alimenta t+dt."""
        noise = self._rng.normal(0.0, C.NOISE_STD, self.connectome.n).astype(np.float32)
        current = self._bias + self._external + self._directed + self._recurrent + noise
        spikes = lif_step(self.state, current, self.dt_ms)
        spikes = spikes & ~self._silenced
        self._recurrent = self.connectome.W @ spikes.astype(np.float32)
        self.t_ms += round(self.dt_ms)
        return SpikeFrame(t_ms=self.t_ms, spikes=spikes)
