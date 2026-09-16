"""
Testes de engine.py — silenciamento e estimulação dirigida de neurônios
(F5). Ver docstring de engine.py e docs/03-roadmap-fases.md.
"""
from __future__ import annotations

import numpy as np

from flywire_sim import config as C
from flywire_sim import graph
from flywire_sim.engine import Engine


def test_set_silenced_blocks_spikes(monkeypatch):
    """Um neurônio silenciado nunca aparece em spikes, mesmo sob estímulo forte."""
    cc = graph.load()
    monkeypatch.setattr(C, "BIAS_CURRENT", 0.0)
    monkeypatch.setattr(C, "NOISE_STD", 0.0)
    engine = Engine(cc, seed=0)

    target = int(cc.sensory[0])
    engine.stimulate(np.array([target]), amplitude=5.0)  # dispararia sozinho sem silenciar
    engine.set_silenced(np.array([target]))

    for _ in range(20):
        frame = engine.step()
        assert not frame.spikes[target]


def test_set_silenced_replaces_not_accumulates(monkeypatch):
    """Chamar set_silenced de novo troca o conjunto, não soma ao anterior."""
    cc = graph.load()
    monkeypatch.setattr(C, "BIAS_CURRENT", 0.0)
    monkeypatch.setattr(C, "NOISE_STD", 0.0)
    engine = Engine(cc, seed=0)

    a, b = int(cc.sensory[0]), int(cc.sensory[1])
    engine.stimulate(np.array([a, b]), amplitude=5.0)
    engine.set_silenced(np.array([a]))
    engine.set_silenced(np.array([b]))  # substitui — a não deveria mais estar silenciado

    a_spiked = False
    for _ in range(10):
        frame = engine.step()
        assert not frame.spikes[b]
        a_spiked = a_spiked or bool(frame.spikes[a])
    assert a_spiked


def test_set_directed_stimulus_adds_to_external(monkeypatch):
    """set_directed_stimulus soma com stimulate(), não substitui/desliga ele."""
    cc = graph.load()
    monkeypatch.setattr(C, "BIAS_CURRENT", 0.0)
    monkeypatch.setattr(C, "NOISE_STD", 0.0)
    engine = Engine(cc, seed=0)

    a = int(cc.sensory[0])
    b = int(cc.output[0])
    engine.stimulate(np.array([a]), amplitude=5.0)
    engine.set_directed_stimulus(np.array([b]), amplitude=5.0)

    a_spiked = b_spiked = False
    for _ in range(5):
        frame = engine.step()
        a_spiked = a_spiked or bool(frame.spikes[a])
        b_spiked = b_spiked or bool(frame.spikes[b])
    assert a_spiked
    assert b_spiked
