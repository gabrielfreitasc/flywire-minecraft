package com.flywireminecraft.bee;

import org.bukkit.World;

/**
 * F13 (26/09/2026, pedido do usuário) — sonolência noturna da abelha.
 *
 * <p>Comportamento pedido: durante a noite do jogo ela fica sonolenta, sem
 * voos altos, praticamente parada, mas NÃO totalmente adormecida — som alto
 * (circuito `johnston`), ameaça que ativa o medo (`escape`) ou um hit tiram
 * ela desse estado, e se tudo se acalmar ela volta a ficar sonolenta.
 *
 * <p><b>Achado real, não contornado (ver docs/03-roadmap-fases.md F13):</b>
 * diferente do hygro/escape/taste/thermo, o estado de sono em si NÃO é um
 * circuito LIF utilizável. As sementes reais (neurônios ER5/"R5" e dFB
 * FB6A/H/I/Z — todas inibitórias, GABA/glutamato) foram extraídas e testadas
 * (RN-09, {@code sim/tools/sleep_calibration_check.py}): estimulá-las NÃO muda
 * os descendentes em 2 saltos (p=0,083 / p=1,0) e em 3 saltos o efeito é ~3%
 * com custo de ~1,12 ms/passo, inviável em tempo real. Sono em mosca é
 * modulação de ESTADO, não silenciamento sináptico direto. Por isso — mesmo
 * status do {@link EnergyTracker}, PROXY DE ENGENHARIA, não simulação de
 * neurônio — o estado sonolento vem do relógio do mundo (o jogo não tem
 * neurônios de relógio). O que É circuito real: os GATILHOS de despertar
 * (ver {@code ControlLoop}: `startle` do johnston com alarme real, fuga por
 * medo, dano).
 *
 * <p>Todos os números abaixo são PROVISÓRIOS, engenharia.
 */
final class SleepState {

    /** Hora do mundo (0 = 06:00, ticks de 0 a 24000) em que anoitece — mesmo início em que jogador pode dormir. */
    // Pacote-vis00edvel 2014 F14 reaproveita em JohnstonLesionExperiment (for00e7ar
    // noite antes do experimento, mesmo valor que SleepState j00e1 usa).
    static final long NIGHT_START_TICK = 13000;
    /** Amanhece. */
    private static final long NIGHT_END_TICK = 23000;

    /** Calma contínua necessária pra voltar a ficar sonolenta depois de despertar — 10 s a 20 Hz. */
    static final int CALM_TICKS_TO_RESLEEP = 200;

    /** Fração da velocidade normal enquanto sonolenta ("praticamente parada"). */
    static final double DROWSY_SPEED_FRACTION = 0.12;
    /** Altura máxima acima do chão enquanto sonolenta, em blocos ("sem voos altos"). */
    static final double DROWSY_CEILING_BLOCKS = 1.0;

    private int calmTicksLeftUntilResleep = 0;
    private boolean night = false;

    /** Chamar em {@code ControlLoop::start}. */
    void reset() {
        calmTicksLeftUntilResleep = 0;
        night = false;
    }

    /**
     * Uma vez por tick (thread principal).
     *
     * @param disturbed algo que tira ela da sonolência AGORA: som alto real,
     *     medo em curso ou dano. Cada tick perturbado reinicia a contagem de
     *     calma.
     */
    void tick(World world, boolean disturbed) {
        long time = world.getTime();
        night = time >= NIGHT_START_TICK && time < NIGHT_END_TICK;
        if (!night) {
            calmTicksLeftUntilResleep = 0;
            return;
        }
        if (disturbed) {
            calmTicksLeftUntilResleep = CALM_TICKS_TO_RESLEEP;
        } else if (calmTicksLeftUntilResleep > 0) {
            calmTicksLeftUntilResleep--;
        }
    }

    boolean isNight() {
        return night;
    }

    /** Noite E calma o bastante — é aqui que ela fica quase parada e baixa. */
    boolean isDrowsy() {
        return night && calmTicksLeftUntilResleep == 0;
    }

    /** Noite, mas foi perturbada há pouco — acordada até a calma voltar. */
    boolean isWokenAtNight() {
        return night && calmTicksLeftUntilResleep > 0;
    }
}
