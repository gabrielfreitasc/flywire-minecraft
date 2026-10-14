package com.flywireminecraft.bee;

import org.bukkit.World;

/** F13 — sonolência noturna da abelha. */
final class SleepState {
    // F14
    static final long NIGHT_START_TICK = 13000;
    private static final long NIGHT_END_TICK = 23000;

    static final int CALM_TICKS_TO_RESLEEP = 200;

    static final double DROWSY_SPEED_FRACTION = 0.12;
    static final double DROWSY_CEILING_BLOCKS = 1.0;

    private int calmTicksLeftUntilResleep = 0;
    private boolean night = false;

    /** Chamar em ControlLoop::start. */
    void reset() {
        calmTicksLeftUntilResleep = 0;
        night = false;
    }

    /** Uma vez por tick (thread principal). */
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
