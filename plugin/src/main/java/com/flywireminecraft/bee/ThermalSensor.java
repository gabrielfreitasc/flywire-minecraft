package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.entity.Bee;
import org.bukkit.util.Vector;

import java.util.Set;

/** Sensor de temperatura do circuito thermo (F12): flags independentes de calor e frio por perto. */
public final class ThermalSensor {
    private static final Set<Material> HEAT_HAZARDS = Set.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
            Material.LAVA_CAULDRON
    );

    private static final Set<Material> COLD_SOURCES = Set.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE,
            Material.SNOW_BLOCK, Material.POWDER_SNOW
    );

    private static final int SCAN_RADIUS_BLOCKS = 3;
    private static final double HOT_BIOME_TEMPERATURE = 1.5;
    private static final double COLD_BIOME_TEMPERATURE = 0.15;
    private static final int REFRESH_EVERY_TICKS = 5;

    // F21
    private static final double COLD_ONSET_BIOME_TEMPERATURE = COLD_BIOME_TEMPERATURE;

    // F21
    private static final double COLD_LETHARGIC_BIOME_TEMPERATURE = -0.5;

    public record Reading(boolean heat, boolean cold, Vector hazardAwayDirection, double coldSeverity) {
    }

    private static final double COLD_SOURCE_NEARBY_SEVERITY_FLOOR = 0.3;

    // F21
    static final double COLD_LETHARGIC_SPEED_FRACTION = 0.1;
    // F27
    static final double COLD_LETHARGIC_CEILING_BLOCKS = 2.0;
    static final double COLD_WARM_CEILING_EXTRA_BLOCKS = 6.0;

    private static final Reading NONE = new Reading(false, false, null, 0.0);

    private Reading cached = NONE;
    private int ticksSinceRefresh = REFRESH_EVERY_TICKS;

    /** Reinicia o cache — chamar em ControlLoop::start. */
    void reset() {
        cached = NONE;
        ticksSinceRefresh = REFRESH_EVERY_TICKS;
    }

    /** Uma chamada por tick (thread principal); só re-varre a cada #REFRESH_EVERY_TICKS. */
    public Reading read(Bee bee) {
        if (ticksSinceRefresh++ >= REFRESH_EVERY_TICKS) {
            ticksSinceRefresh = 1;
            cached = scan(bee);
        }
        return cached;
    }

    private Reading scan(Bee bee) {
        Location center = bee.getLocation();
        World world = center.getWorld();
        if (world == null) {
            return NONE;
        }

        boolean heat = false;
        boolean cold = false;
        Location nearestHazard = null;
        double nearestHazardDistanceSquared = Double.MAX_VALUE;

        int bx = center.getBlockX();
        int by = center.getBlockY();
        int bz = center.getBlockZ();
        for (int dx = -SCAN_RADIUS_BLOCKS; dx <= SCAN_RADIUS_BLOCKS; dx++) {
            for (int dy = -SCAN_RADIUS_BLOCKS; dy <= SCAN_RADIUS_BLOCKS; dy++) {
                for (int dz = -SCAN_RADIUS_BLOCKS; dz <= SCAN_RADIUS_BLOCKS; dz++) {
                    Block block = world.getBlockAt(bx + dx, by + dy, bz + dz);
                    Material type = block.getType();
                    if (COLD_SOURCES.contains(type)) {
                        cold = true;
                    } else if (isHeatHazard(block, type)) {
                        heat = true;
                        Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
                        double distanceSquared = blockCenter.distanceSquared(center);
                        if (distanceSquared < nearestHazardDistanceSquared) {
                            nearestHazardDistanceSquared = distanceSquared;
                            nearestHazard = blockCenter;
                        }
                    }
                }
            }
        }

        double biomeTemperature = center.getBlock().getTemperature();
        if (biomeTemperature >= HOT_BIOME_TEMPERATURE) {
            heat = true;
        } else if (biomeTemperature <= COLD_BIOME_TEMPERATURE) {
            cold = true;
        }

        double coldSeverity = coldSeverityFromBiomeTemperature(biomeTemperature);
        if (cold) {
            coldSeverity = Math.max(coldSeverity, COLD_SOURCE_NEARBY_SEVERITY_FLOOR);
        }

        Vector away = null;
        if (nearestHazard != null) {
            away = center.toVector().subtract(nearestHazard.toVector());
            if (away.lengthSquared() < 1.0E-6) {
                away = new Vector(0, 1, 0);
            } else {
                away.normalize();
            }
        }
        return new Reading(heat, cold, away, coldSeverity);
    }

    private static double coldSeverityFromBiomeTemperature(double biomeTemperature) {
        if (biomeTemperature >= COLD_ONSET_BIOME_TEMPERATURE) {
            return 0.0;
        }
        double span = COLD_ONSET_BIOME_TEMPERATURE - COLD_LETHARGIC_BIOME_TEMPERATURE;
        double severity = (COLD_ONSET_BIOME_TEMPERATURE - biomeTemperature) / span;
        return Math.min(1.0, severity);
    }

    private boolean isHeatHazard(Block block, Material type) {
        if (HEAT_HAZARDS.contains(type)) {
            return true;
        }
        return (type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE)
                && block.getBlockData() instanceof Campfire campfire
                && campfire.isLit();
    }
}
