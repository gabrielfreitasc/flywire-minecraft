package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Bee;

/** L5 → visualização — uma cor POR CIRCUITO (não por canal), partículas ao redor da abelha. */
public final class ActivityVisualizer {
    private record CircuitVisual(Color color, String... channels) {
    }

    static final Color COLOR_OCELAR = Color.fromRGB(255, 230, 0);
    static final Color COLOR_BRISTLE = Color.fromRGB(139, 90, 43);
    static final Color COLOR_HYGRO = Color.fromRGB(0, 128, 128);
    static final Color COLOR_JOHNSTON = Color.fromRGB(220, 0, 0);
    static final Color COLOR_ESCAPE = Color.fromRGB(148, 0, 211);
    static final Color COLOR_TASTE = Color.fromRGB(255, 105, 180);
    static final Color COLOR_THERMO = Color.fromRGB(255, 140, 0);

    private static final CircuitVisual[] CIRCUITS = {
            new CircuitVisual(COLOR_OCELAR, "phototaxis", "yaw_steering"),
            new CircuitVisual(COLOR_BRISTLE, "grooming"),
            new CircuitVisual(COLOR_HYGRO, "hygrotaxis"),
            new CircuitVisual(COLOR_JOHNSTON, "startle"),
            new CircuitVisual(COLOR_ESCAPE, "escape_drive"),
            new CircuitVisual(COLOR_TASTE, "appetite"),
            new CircuitVisual(COLOR_THERMO, "thermal"),
    };

    private static final int MAX_PARTICLES_PER_CIRCUIT = 6;
    private static final double RADIUS = 0.6;

    // F23
    private static final int MAX_SNOW_PARTICLES = 6;
    private static final int HEAT_PARTICLE_COUNT = 4;

    /** Roda na thread principal — spawnar partícula é chamada de World, não thread-safe fora dela. */
    public void render(
            Bee bee, JsonObject motor, JsonObject bristleMotor, JsonObject hygroMotor, JsonObject johnstonMotor,
            JsonObject escapeMotor, JsonObject tasteMotor, JsonObject thermoMotor,
            boolean heatNear, boolean coldNear, double coldSeverity
    ) {
        if (!bee.isValid()) {
            return;
        }
        Location center = bee.getLocation().add(0, 0.5, 0);
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        JsonObject[] byCircuit = {motor, bristleMotor, hygroMotor, johnstonMotor, escapeMotor, tasteMotor, thermoMotor};
        for (int i = 0; i < CIRCUITS.length; i++) {
            renderCircuit(world, center, byCircuit[i], CIRCUITS[i]);
        }
        renderEnvironment(world, center, heatNear, coldNear, coldSeverity);
    }

    private void renderEnvironment(World world, Location center, boolean heatNear, boolean coldNear, double coldSeverity) {
        if (coldNear) {
            int count = Math.max(1, (int) Math.round(Math.min(1.0, coldSeverity) * MAX_SNOW_PARTICLES));
            for (int i = 0; i < count; i++) {
                double angle = 2 * Math.PI * Math.random();
                double dx = Math.cos(angle) * RADIUS;
                double dz = Math.sin(angle) * RADIUS;
                double dy = Math.random() * RADIUS;
                world.spawnParticle(Particle.SNOWFLAKE, center.clone().add(dx, dy, dz), 1, 0, 0, 0, 0);
            }
        }
        if (heatNear) {
            for (int i = 0; i < HEAT_PARTICLE_COUNT; i++) {
                double angle = 2 * Math.PI * Math.random();
                double dx = Math.cos(angle) * RADIUS;
                double dz = Math.sin(angle) * RADIUS;
                double dy = Math.random() * RADIUS;
                world.spawnParticle(Particle.FLAME, center.clone().add(dx, dy, dz), 1, 0, 0, 0, 0);
            }
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
