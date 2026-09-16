package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Bee;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L5 → visualização — cada canal do vetor motor (8 grupos provisórios por
 * prefixo de `cell_type` + `phototaxis`) vira partículas coloridas ao redor
 * da abelha. Quantidade de partículas é proporcional a |valor| do canal:
 * mais partículas = mais atividade daquele grupo agora.
 *
 * <p>Critério de saída da F5 (`docs/03-roadmap-fases.md`): um observador
 * olhando o mundo consegue dizer qual parte do circuito está ativa, sem ler
 * log nenhum. Não é decoração — é o vetor motor de verdade, traduzido pra
 * cor. `phototaxis` (único canal com direção validada, RN-09) tem cor
 * própria (amarelo) pra se destacar dos outros 8, que são só magnitude.
 */
public final class ActivityVisualizer {

    private static final Map<String, Color> CHANNEL_COLORS = new LinkedHashMap<>();

    static {
        CHANNEL_COLORS.put("phototaxis", Color.fromRGB(255, 230, 0));
        CHANNEL_COLORS.put("DNp", Color.fromRGB(220, 20, 60));
        CHANNEL_COLORS.put("DNpe", Color.fromRGB(255, 105, 180));
        CHANNEL_COLORS.put("DNg", Color.fromRGB(30, 144, 255));
        CHANNEL_COLORS.put("DNge", Color.fromRGB(0, 206, 209));
        CHANNEL_COLORS.put("DNb", Color.fromRGB(50, 205, 50));
        CHANNEL_COLORS.put("DNbe", Color.fromRGB(154, 205, 50));
        CHANNEL_COLORS.put("DNa", Color.fromRGB(148, 0, 211));
        CHANNEL_COLORS.put("DNae", Color.fromRGB(255, 140, 0));
    }

    private static final int MAX_PARTICLES_PER_CHANNEL = 6;
    private static final double RADIUS = 0.6;

    /** Roda na thread principal — spawnar partícula é chamada de World, não thread-safe fora dela. */
    public void render(Bee bee, JsonObject motor) {
        if (motor == null || !bee.isValid()) {
            return;
        }
        Location center = bee.getLocation().add(0, 0.5, 0);
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        for (Map.Entry<String, Color> entry : CHANNEL_COLORS.entrySet()) {
            if (!motor.has(entry.getKey())) {
                continue;
            }
            double value = Math.abs(motor.get(entry.getKey()).getAsDouble());
            int count = (int) Math.round(Math.min(1.0, value) * MAX_PARTICLES_PER_CHANNEL);
            if (count <= 0) {
                continue;
            }

            Particle.DustOptions dust = new Particle.DustOptions(entry.getValue(), 1.0f);
            for (int i = 0; i < count; i++) {
                double angle = 2 * Math.PI * Math.random();
                double dx = Math.cos(angle) * RADIUS;
                double dz = Math.sin(angle) * RADIUS;
                double dy = (Math.random() - 0.5) * RADIUS;
                world.spawnParticle(Particle.DUST, center.clone().add(dx, dy, dz), 1, dust);
            }
        }
    }
}
