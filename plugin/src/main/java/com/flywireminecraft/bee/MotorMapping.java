package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.util.Vector;

/**
 * Traduz o vetor motor da ponte em velocidade aplicável à abelha.
 *
 * <p>Usa o canal {@code phototaxis} (adicionado em `motor.py` na F4), não a
 * média dos 8 grupos por prefixo de `cell_type` (RN-08, ainda sem
 * curadoria). Motivo: a primeira versão usava a média de todos os grupos, e
 * o experimento de lesão deu nulo (p=0,37) — mesmo erro já corrigido uma vez
 * em RN-09, onde agregar descendentes que respondem em direções opostas
 * (29 excitatórios via desinibição, 63 inibitórios diretos) cancela o sinal.
 * `phototaxis` já vem separado corretamente do lado Python — ver
 * `docs/04-regras-de-negocio.md`.
 *
 * <p>`phototaxis` tem sinal real (não é só magnitude ≥0 como os grupos por
 * prefixo), mas ainda não sabemos calibrar isso como direção 3D própria do
 * circuito (yaw/lift — RN-08 completa). Por ora, reescala para [0,1] e usa
 * como magnitude de avanço na direção que a abelha já está olhando.
 *
 * <p>Escala de velocidade (`MAX_SPEED_BLOCKS_PER_TICK`) usa o mesmo valor
 * validado no spike técnico da F4 (0,3 blocos/tick ≈ 96% de eficiência com
 * IA ligada — ver plugin/README.md).
 */
public final class MotorMapping {

    private static final double MAX_SPEED_BLOCKS_PER_TICK = 0.3;

    private MotorMapping() {
    }

    public static Vector toVelocity(JsonObject bridgeResponse, Vector facing) {
        JsonObject motor = bridgeResponse.getAsJsonObject("motor");
        if (motor == null || !motor.has("phototaxis")) {
            return new Vector(0, 0, 0);
        }

        double phototaxis = motor.get("phototaxis").getAsDouble(); // em (-1, 1)
        double activity = clamp((phototaxis + 1.0) / 2.0, 0.0, 1.0); // reescala p/ [0,1]

        return facing.clone().multiply(activity * MAX_SPEED_BLOCKS_PER_TICK);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
