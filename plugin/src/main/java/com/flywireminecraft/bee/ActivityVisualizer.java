package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Bee;

/**
 * L5 → visualização — uma cor POR CIRCUITO (não por canal), partículas ao
 * redor da abelha. Quantidade de partículas é proporcional à maior |valor|
 * entre os canais de referência daquele circuito: mais partículas = mais
 * atividade agora.
 *
 * <p><b>Redesenhado (21/09/2026, pedido do usuário) — de "1 cor por canal"
 * pra "1 cor por circuito".</b> A versão anterior dava 1 cor pra cada um dos
 * 8 grupos provisórios por prefixo de `cell_type` do ocelar (RN-08, telemetria
 * sem curadoria) + `phototaxis` + `locomotion_drive` — 10 cores só do ocelar,
 * poluído visualmente e ficaria pior a cada canal novo. Trocado por uma
 * composição fixa, **escalável**: cada circuito (`CircuitVisual`) declara sua
 * cor e a LISTA de canais que representam ele — a maioria só tem um (o canal
 * validado ou candidato a validado), não uma cor por canal bruto. Adicionar
 * um circuito novo (F7+) é uma linha nova em {@link #CIRCUITS}, não uma
 * palheta nova.
 *
 * <p>Canais de referência por circuito, e por quê:
 * <ul>
 *   <li>{@code ocelar} — {@code phototaxis} (validado por lesão, F4,
 *       p=0,0014) e {@code yaw_steering} (canal de direção, RN-08/AD-16, em
 *       validação). Os 8 grupos por prefixo (telemetria bruta, sem curadoria)
 *       ficaram de fora — são ruído visual, não sinal com significado.</li>
 *   <li>{@code bristle} — {@code grooming} (único canal com comportamento
 *       publicado, RN-08 equivalente, e o único que já controla a abelha de
 *       verdade). {@code conn_*} (cluster de conectividade, evidência mais
 *       fraca) fica de fora pelo mesmo motivo dos grupos do ocelar.</li>
 *   <li>{@code hygro} — {@code hygrotaxis} (único canal que existe, telemetria
 *       ainda sem lesão validando, ver `hygro_motor.py`).</li>
 *   <li>{@code johnston} — {@code startle} (F8, vento/som; único canal que
 *       existe, telemetria ainda sem lesão validando, ver
 *       `johnston_motor.py`).</li>
 *   <li>{@code escape} — {@code escape_drive} (F9/AD-20, fuga por looming;
 *       único canal que existe, curado por identidade celular
 *       (DNp01+DNp02), telemetria ainda sem lesão validando, ver
 *       `escape_motor.py`).</li>
 *   <li>{@code taste} — {@code appetite} (F10, paladar apetitivo; único
 *       canal que existe, telemetria ainda sem lesão validando, ver
 *       `taste_motor.py`).</li>
 * </ul>
 *
 * <p><b>Nota importante:</b> pegar o MAIOR |valor| entre os canais de um
 * circuito (não somar) é só pra decidir QUANTAS partículas mostrar — uma
 * escolha visual, não um sinal novo. Não recalibra nem realimenta
 * `MotorMapping.java`; a mesma armadilha de "somar canais de fontes
 * diferentes dilui o efeito" (RN-08/RN-09, `CONVENCOES.md`) é sobre o vetor
 * motor que MOVE a abelha, não sobre quantas partículas aparecem na tela.
 */
public final class ActivityVisualizer {

    private record CircuitVisual(Color color, String... channels) {
    }

    // Ordem = ordem de desenho. Cor de cada circuito reaproveitada da versão
    // anterior (já eram as cores de destaque de phototaxis/grooming/
    // hygrotaxis) — só a composição mudou, não a paleta em si.
    private static final CircuitVisual[] CIRCUITS = {
            new CircuitVisual(Color.fromRGB(255, 230, 0), "phototaxis", "yaw_steering"),   // ocelar — amarelo (luz)
            new CircuitVisual(Color.fromRGB(139, 90, 43), "grooming"),                     // bristle — marrom (toque/limpeza)
            new CircuitVisual(Color.fromRGB(0, 128, 128), "hygrotaxis"),                   // hygro — azul-petróleo (chuva)
            new CircuitVisual(Color.fromRGB(220, 0, 0), "startle"),                        // johnston — vermelho (alarme/som)
            new CircuitVisual(Color.fromRGB(148, 0, 211), "escape_drive"),                 // escape — violeta (medo/fuga)
            new CircuitVisual(Color.fromRGB(255, 105, 180), "appetite"),                   // taste — rosa (paladar/doce)
    };

    private static final int MAX_PARTICLES_PER_CIRCUIT = 6;
    private static final double RADIUS = 0.6;

    /** Roda na thread principal — spawnar partícula é chamada de World, não thread-safe fora dela. */
    public void render(
            Bee bee, JsonObject motor, JsonObject bristleMotor, JsonObject hygroMotor, JsonObject johnstonMotor,
            JsonObject escapeMotor, JsonObject tasteMotor
    ) {
        if (!bee.isValid()) {
            return;
        }
        Location center = bee.getLocation().add(0, 0.5, 0);
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        JsonObject[] byCircuit = {motor, bristleMotor, hygroMotor, johnstonMotor, escapeMotor, tasteMotor};
        for (int i = 0; i < CIRCUITS.length; i++) {
            renderCircuit(world, center, byCircuit[i], CIRCUITS[i]);
        }
    }

    private void renderCircuit(World world, Location center, JsonObject motor, CircuitVisual circuit) {
        if (motor == null) {
            return;
        }
        double maxAbsValue = 0.0;
        for (String channel : circuit.channels()) {
            if (motor.has(channel)) {
                maxAbsValue = Math.max(maxAbsValue, Math.abs(motor.get(channel).getAsDouble()));
            }
        }
        int count = (int) Math.round(Math.min(1.0, maxAbsValue) * MAX_PARTICLES_PER_CIRCUIT);
        if (count <= 0) {
            return;
        }

        Particle.DustOptions dust = new Particle.DustOptions(circuit.color(), 1.0f);
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * Math.random();
            double dx = Math.cos(angle) * RADIUS;
            double dz = Math.sin(angle) * RADIUS;
            double dy = (Math.random() - 0.5) * RADIUS;
            world.spawnParticle(Particle.DUST, center.clone().add(dx, dy, dz), 1, dust);
        }
    }
}
