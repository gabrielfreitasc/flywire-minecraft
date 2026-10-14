package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.util.Vector;

/** Traduz o vetor motor da ponte em velocidade aplicável à abelha. */
public final class MotorMapping {
    private static final double MAX_SPEED_BLOCKS_PER_TICK = 0.3;
    private static final double MAX_YAW_RADIANS_PER_TICK = 0.01;

    // F7, AD-17, RN-09
    private static final double GROOMING_THRESHOLD = 0.8;
    private static final double LANDING_DESCENT_BLOCKS_PER_TICK = 0.1;
    // F9
    private static final double GROOMING_DESCENT_HORIZONTAL_BLOCKS_PER_TICK = 0.15;

    // F7, AD-17
    private static final double HYGROTAXIS_THRESHOLD = 0.8;
    private static final double SHELTER_DIVE_DESCENT_BLOCKS_PER_TICK = 0.3;
    // F7, AD-17
    private static final double SEARCH_HOVER_BLOCKS_PER_TICK = 0.15;

    // F7, AD-17
    private static final double SEARCH_REDESCENT_BLOCKS_PER_TICK = 0.05;

    // F9, F7
    private static final double ESCAPE_THRESHOLD = 0.6;
    private static final double ESCAPE_SPEED_BLOCKS_PER_TICK = 0.45;
    private static final double ESCAPE_VERTICAL_BOOST_BLOCKS_PER_TICK = 0.2;

    // F10, F9
    private static final double TASTE_THRESHOLD = 0.7;
    private static final double TASTE_APPROACH_SPEED_BLOCKS_PER_TICK = 0.3;
    // F11
    static final double TASTE_ARRIVAL_THRESHOLD_BLOCKS = 0.3;

    private MotorMapping() {
    }

    /** Rotaciona a direção comandada por yaw_steering. */
    public static Vector rotatedHeading(JsonObject bridgeResponse, Vector previousHeading) {
        JsonObject motor = bridgeResponse.getAsJsonObject("motor");
        double yawSteering = (motor != null && motor.has("yaw_steering"))
                ? motor.get("yaw_steering").getAsDouble() : 0.0;
        return previousHeading.clone().rotateAroundY(yawSteering * MAX_YAW_RADIANS_PER_TICK).normalize();
    }

    public static Vector toVelocity(
            JsonObject bridgeResponse, Vector heading, boolean landed, boolean sheltered,
            boolean hasTouchedThisEpisode, boolean escapeActive, Vector shelterDirectionHint,
            Vector escapeDirectionHint, Vector tasteDisplacementHint, boolean tasteTargetHeldByPlayer
    ) {
        if (escapeActive) {
            Vector fleeDirection = escapeDirectionHint != null ? escapeDirectionHint : heading;
            Vector escapeVelocity = fleeDirection.clone().multiply(ESCAPE_SPEED_BLOCKS_PER_TICK);
            escapeVelocity.setY(ESCAPE_VERTICAL_BOOST_BLOCKS_PER_TICK);
            return escapeVelocity;
        }
        if (isSeekingShelterActive(bridgeResponse.getAsJsonObject("hygro_motor"))) {
            if (sheltered) {
                return new Vector(0, 0, 0);
            }
            Vector moveDirection = shelterDirectionHint != null ? shelterDirectionHint : heading;
            if (landed) {
                Vector search = moveDirection.clone().multiply(MAX_SPEED_BLOCKS_PER_TICK);
                search.setY(SEARCH_HOVER_BLOCKS_PER_TICK);
                return search;
            }
            double descent = hasTouchedThisEpisode
                    ? SEARCH_REDESCENT_BLOCKS_PER_TICK
                    : SHELTER_DIVE_DESCENT_BLOCKS_PER_TICK;
            Vector dive = moveDirection.clone().multiply(MAX_SPEED_BLOCKS_PER_TICK);
            dive.setY(-descent);
            return dive;
        }
        boolean tasteActive = isTasteSeekingActive(bridgeResponse.getAsJsonObject("taste_motor"));
        if (tasteActive && tasteTargetHeldByPlayer) {
            return computeTasteVelocity(tasteDisplacementHint);
        }
        if (isGroomingActive(bridgeResponse.getAsJsonObject("bristle_motor"))) {
            if (landed) {
                return new Vector(0, 0, 0);
            }
            Vector descending = heading.clone().multiply(GROOMING_DESCENT_HORIZONTAL_BLOCKS_PER_TICK);
            descending.setY(-LANDING_DESCENT_BLOCKS_PER_TICK);
            return descending;
        }
        if (tasteActive) {
            return computeTasteVelocity(tasteDisplacementHint);
        }

        JsonObject motor = bridgeResponse.getAsJsonObject("motor");
        if (motor == null || !motor.has("phototaxis")) {
            return new Vector(0, 0, 0);
        }

        double phototaxis = motor.get("phototaxis").getAsDouble();
        double activity = clamp((phototaxis + 1.0) / 2.0, 0.0, 1.0);

        return heading.clone().multiply(activity * MAX_SPEED_BLOCKS_PER_TICK);
    }

    public static boolean isGroomingActive(JsonObject bristleMotor) {
        if (bristleMotor == null || !bristleMotor.has("grooming")) {
            return false;
        }
        return bristleMotor.get("grooming").getAsDouble() > GROOMING_THRESHOLD;
    }

    public static boolean isSeekingShelterActive(JsonObject hygroMotor) {
        if (hygroMotor == null || !hygroMotor.has("hygrotaxis")) {
            return false;
        }
        return hygroMotor.get("hygrotaxis").getAsDouble() > HYGROTAXIS_THRESHOLD;
    }

    public static boolean isEscapeActive(JsonObject escapeMotor) {
        if (escapeMotor == null || !escapeMotor.has("escape_drive")) {
            return false;
        }
        return escapeMotor.get("escape_drive").getAsDouble() > ESCAPE_THRESHOLD;
    }

    public static boolean isTasteSeekingActive(JsonObject tasteMotor) {
        if (tasteMotor == null || !tasteMotor.has("appetite")) {
            return false;
        }
        return tasteMotor.get("appetite").getAsDouble() > TASTE_THRESHOLD;
    }

    // F12, F9, F10, AD-29
    private static final double THERMO_THRESHOLD = 0.45;
    private static final double HEAT_AVOID_SPEED_BLOCKS_PER_TICK = 0.4;

    /** F12 — canal thermal do `thermo` acima do limiar (sem Engine rodando: falso). */
    public static boolean isThermalActive(JsonObject thermoMotor) {
        if (thermoMotor == null || !thermoMotor.has("thermal")) {
            return false;
        }
        return thermoMotor.get("thermal").getAsDouble() > THERMO_THRESHOLD;
    }

    public static Vector heatAvoidVelocity(Vector awayFromHazard) {
        return awayFromHazard.clone().normalize().multiply(HEAT_AVOID_SPEED_BLOCKS_PER_TICK);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Vector computeTasteVelocity(Vector tasteDisplacementHint) {
        if (tasteDisplacementHint == null) {
            return new Vector(0, 0, 0);
        }
        double distance = tasteDisplacementHint.length();
        if (distance < TASTE_ARRIVAL_THRESHOLD_BLOCKS) {
            return new Vector(0, 0, 0);
        }
        return tasteDisplacementHint.clone().normalize().multiply(TASTE_APPROACH_SPEED_BLOCKS_PER_TICK);
    }
}
