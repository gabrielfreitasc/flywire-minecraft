package com.flywireminecraft.bee;

/** F11 — "fome"/energia da abelha. */
final class EnergyTracker {
    private static final double ENERGY_MAX = 1.0;
    // F30
    private static final double BASE_DEPLETION_PER_TICK = 0.8 / 6000.0;
    private static final double MOVEMENT_DEPLETION_PER_BLOCK = 2.0 * BASE_DEPLETION_PER_TICK;
    private static final double REPLENISH_PER_TICK_WHILE_EATING = 0.01;

    // F11
    static final double HUNGRY_BELOW = 0.4;
    static final double MIN_SPEED_FRACTION = 0.35;
    // F27
    static final double STARVING_CEILING_BLOCKS = 2.0;
    static final double FED_CEILING_EXTRA_BLOCKS = 6.0;
    static final double HUNGRY_DESCENT_BLOCKS_PER_TICK = 0.1;

    // F30, Inagaki et al., 2014
    static final double SEEK_FOOD_BELOW = 0.2;

    private volatile double energy = ENERGY_MAX;
    private volatile boolean sated = true;

    /** Chamar em ControlLoop::start — começa cheia (e saciada) a cada novo episódio de controle. */
    void reset() {
        energy = ENERGY_MAX;
        sated = true;
    }

    /** Chamar uma vez por tick (thread principal). */
    void tick(double blocksMovedThisTick, boolean eating) {
        if (eating) {
            energy = Math.min(ENERGY_MAX, energy + REPLENISH_PER_TICK_WHILE_EATING);
        } else {
            double depletion = BASE_DEPLETION_PER_TICK + blocksMovedThisTick * MOVEMENT_DEPLETION_PER_BLOCK;
            energy = Math.max(0.0, energy - depletion);
        }
        if (energy >= ENERGY_MAX) {
            sated = true;
        } else if (energy <= SEEK_FOOD_BELOW) {
            sated = false;
        }
    }

    /** F30 — false entre encher (100%) e cair até #SEEK_FOOD_BELOW. */
    boolean isSeekingFood() {
        return !sated;
    }

    /** Intensidade da fome: 0,0 com energia >= #HUNGRY_BELOW, sobe linearmente até 1,0 com energia zero. */
    double hungerFactor() {
        double e = energy;
        if (e >= HUNGRY_BELOW) {
            return 0.0;
        }
        return 1.0 - e / HUNGRY_BELOW;
    }

    /** Nível atual — 0,0 (faminta) a 1,0 (cheia). */
    double level() {
        return energy;
    }
}
