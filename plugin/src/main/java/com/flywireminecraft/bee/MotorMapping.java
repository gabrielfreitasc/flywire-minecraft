package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.util.Vector;

/**
 * Traduz o vetor motor da ponte em velocidade aplicável à abelha.
 *
 * <p>Combina dois canais validados de formas diferentes:
 *
 * <ul>
 *   <li>{@code phototaxis} — direção real (sinal), vem da topologia de sinal
 *       validada estatisticamente por RN-09 (F1) e confirmada pelo
 *       experimento de lesão da F4 (Mann-Whitney p=0,0014). Ver
 *       `docs/04-regras-de-negocio.md`.</li>
 *   <li>{@code locomotion_drive} — magnitude, vem de RN-08/AD-14 (16/09/2026):
 *       12 dos 46 tipos de descendente têm categoria comportamental publicada
 *       (Namiki et al. 2018, eLife, Figura 6 — leitura de rótulo dos autores,
 *       não inferência nossa). Só "fast_locomotion"+"broad_locomotion" entram
 *       aqui — "anterior_movements" e "wing_abdomen_movements" também têm
 *       dado real, mas são comportamento de MOSCA ANDANDO sem tradução
 *       validada pra voo; ficam expostos em `motor.py::decode()` para
 *       visualização/exploração (F5), não usados aqui. Ver `motor.py`.</li>
 * </ul>
 *
 * <p>Nem `phototaxis` nem `locomotion_drive`, sozinhos, dão direção 3D
 * própria do circuito (yaw/lift) — os 34 tipos restantes de RN-08 seguem sem
 * curadoria. Por ora, a combinação dos dois vira magnitude de avanço na
 * direção que a abelha já está olhando.
 *
 * <p>Escala de velocidade (`MAX_SPEED_BLOCKS_PER_TICK`) usa o mesmo valor
 * validado no spike técnico da F4 (0,3 blocos/tick ≈ 96% de eficiência com
 * IA ligada — ver plugin/README.md).
 *
 * <p><b>Pendência:</b> o experimento de lesão da F4 validou só `phototaxis`.
 * Misturar `locomotion_drive` aqui muda o que um novo experimento de lesão
 * mediria — recomendado rodar `/flywirebee lesion` de novo depois dessa
 * mudança para confirmar que o acoplamento se mantém.
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
        double locomotionDrive = motor.has("locomotion_drive")
                ? motor.get("locomotion_drive").getAsDouble()
                : 0.0; // em (-1, 1); 0 se a ponte ainda não expõe o canal

        double combined = (phototaxis + locomotionDrive) / 2.0;
        double activity = clamp((combined + 1.0) / 2.0, 0.0, 1.0); // reescala p/ [0,1]

        return facing.clone().multiply(activity * MAX_SPEED_BLOCKS_PER_TICK);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
