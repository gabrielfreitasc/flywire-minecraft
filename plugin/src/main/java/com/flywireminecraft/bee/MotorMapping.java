package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.util.Vector;

/**
 * Traduz o vetor motor da ponte em velocidade aplicável à abelha.
 *
 * <p>Magnitude de avanço usa {@code phototaxis} — direção real (sinal), vem
 * da topologia de sinal validada estatisticamente por RN-09 (F1) e
 * confirmada pelo experimento de lesão da F4 (Mann-Whitney p=0,0014). Ver
 * `docs/04-regras-de-negocio.md`.
 *
 * <p><b>Histórico (16/09/2026, revertido):</b> tentamos somar
 * {@code locomotion_drive} (RN-08/AD-14 — 6 tipos com categoria "Fast"/
 * "Broad Locomotion" publicada por Namiki et al. 2018) na velocidade. O
 * experimento de lesão re-rodado deu nulo (p=0,43, N=20) — e continuou nulo
 * mesmo controlando um confundidor real (abelha tinha spawnado dentro de
 * casa; refeito ao ar livre com céu visível: p=0,27, ainda nulo). Isso
 * descarta o confundidor e aponta pra causa real: `locomotion_drive` vem de
 * neurônios que não respondem ao estímulo de luz (foram escolhidos só por
 * categoria comportamental publicada, não por topologia de sinal) — somar
 * esse canal dilui o sinal real que `phototaxis` carregava sozinho. Mesma
 * armadilha de "agregar cancela o efeito" que já apareceu em RN-09 (F1) e no
 * primeiro experimento de lesão (F4). Não ficamos ajustando peso até achar
 * p&lt;0,05 de novo — isso seria manipular o resultado. Revertido pro que já
 * estava validado. `locomotion_drive` continua exposto em
 * `motor.py::decode()` para visualização/exploração (F5: `/flywirebee
 * mute|stimulate`), só não entra mais aqui. Ver `docs/04-regras-de-negocio.md`
 * (RN-08) para o relato completo.
 *
 * <p><b>Guinada (F6/AD-16, 17/09/2026, EM VALIDAÇÃO):</b> {@code yaw_steering}
 * rotaciona a direção de avanço em torno do eixo Y, proporcional ao valor do
 * canal — {@code rotateAroundY} do Bukkit, ângulo em radianos.
 * {@code MAX_YAW_RADIANS_PER_TICK} é um valor provisório, não calibrado
 * contra nada (diferente de {@code MAX_SPEED_BLOCKS_PER_TICK}, que veio do
 * spike técnico da F4). <b>O sentido do sinal (yaw positivo → gira pra qual
 * lado do mundo) não está validado ainda</b> — é exatamente o que
 * `SteeringValidationExperiment` testa. Não afirmar "esquerda" ou "direita"
 * a partir do sinal do canal até esse experimento rodar. Ver
 * `docs/04-regras-de-negocio.md` RN-08.
 *
 * <p><b>Bug encontrado e corrigido na primeira tentativa (17/09/2026):</b> a
 * rotação não acumulava porque {@code ControlLoop} recapturava
 * {@code bee.getLocation().getDirection()} — a orientação REAL da abelha,
 * controlada pela IA nativa — a cada troca, em vez de reaproveitar a direção
 * já rotacionada da troca anterior. `net_turn_rad` medido saiu quase sempre
 * exatamente 0,0 (giro nunca compõe). Corrigido separando {@link
 * #rotatedHeading}, que `ControlLoop` chama guardando o resultado como
 * estado persistente (campo {@code heading}, não relido da abelha a cada
 * tick).
 *
 * <p>Ainda sem controle de altura (lift) — RN-08 completa segue parcial.
 *
 * <p>Escala de velocidade (`MAX_SPEED_BLOCKS_PER_TICK`) usa o mesmo valor
 * validado no spike técnico da F4 (0,3 blocos/tick ≈ 96% de eficiência com
 * IA ligada — ver plugin/README.md).
 */
public final class MotorMapping {

    private static final double MAX_SPEED_BLOCKS_PER_TICK = 0.3;
    // Provisório — ver docstring da classe. yaw_steering=1,0 sustentado por
    // 10s (200 ticks) gira até ~2 rad (~115°) no total.
    private static final double MAX_YAW_RADIANS_PER_TICK = 0.01;

    private MotorMapping() {
    }

    /**
     * Rotaciona a direção comandada por {@code yaw_steering}. Quem chama
     * (`ControlLoop`) guarda o resultado e passa de volta na próxima troca —
     * NUNCA derivar de {@code bee.getLocation().getDirection()} a cada vez,
     * isso é o bug já corrigido (ver docstring da classe).
     */
    public static Vector rotatedHeading(JsonObject bridgeResponse, Vector previousHeading) {
        JsonObject motor = bridgeResponse.getAsJsonObject("motor");
        double yawSteering = (motor != null && motor.has("yaw_steering"))
                ? motor.get("yaw_steering").getAsDouble() : 0.0;
        return previousHeading.clone().rotateAroundY(yawSteering * MAX_YAW_RADIANS_PER_TICK).normalize();
    }

    public static Vector toVelocity(JsonObject bridgeResponse, Vector heading) {
        JsonObject motor = bridgeResponse.getAsJsonObject("motor");
        if (motor == null || !motor.has("phototaxis")) {
            return new Vector(0, 0, 0);
        }

        double phototaxis = motor.get("phototaxis").getAsDouble(); // em (-1, 1)
        double activity = clamp((phototaxis + 1.0) / 2.0, 0.0, 1.0); // reescala p/ [0,1]

        return heading.clone().multiply(activity * MAX_SPEED_BLOCKS_PER_TICK);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
