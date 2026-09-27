package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.entity.Bee;
import org.bukkit.util.Vector;

import java.util.Set;

/**
 * F12 (26/09/2026) — sinal de temperatura pro subcircuito `thermo` (TRNs de
 * aquecimento e de frio, ver `sim/tools/build_f12_circuit.py`). Duas
 * flags independentes, cada uma estimula só a sua semente no simulador
 * ({@code thermo_heat}/{@code thermo_cold}).
 *
 * <p><b>Calor</b> — fontes PERIGOSAS (lava, magma, fogo, fogueira acesa) num
 * raio curto, mais bioma muito quente (deserto, savana, mesa, Nether). Só as
 * fontes perigosas geram {@link Reading#hazardAwayDirection}, usada pra
 * fugir (pedido do usuário, 26/09/2026: moscas evitam perigos físicos
 * extremamente quentes). Bioma quente só informa ("sente muito calor").
 *
 * <p><b>Frio</b> — gelo, neve, neve em pó num raio curto, ou bioma
 * congelante. Só informa ("sente frio"), sem fuga (pedido do usuário).
 *
 * <p>Varredura em cubo de blocos, então cacheada a cada
 * {@link #REFRESH_EVERY_TICKS} ticks — mesma disciplina de custo de
 * {@link TasteSensor}, sem varrer a cada tick. Chamar só da thread
 * principal (lê blocos). Raios/limiares PROVISÓRIOS, engenharia.
 */
public final class ThermalSensor {

    /** Fontes de calor de fato perigosas ao toque. */
    private static final Set<Material> HEAT_HAZARDS = Set.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
            Material.LAVA_CAULDRON
    );

    private static final Set<Material> COLD_SOURCES = Set.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE,
            Material.SNOW_BLOCK, Material.POWDER_SNOW
    );

    /** Raio do cubo varrido, em blocos. */
    private static final int SCAN_RADIUS_BLOCKS = 3;
    /** Bioma com temperatura >= isto conta como "muito quente" (deserto/savana/mesa = 2,0). */
    private static final double HOT_BIOME_TEMPERATURE = 1.5;
    /** Bioma com temperatura <= isto conta como frio (limiar de neve do Minecraft = 0,15). */
    private static final double COLD_BIOME_TEMPERATURE = 0.15;
    private static final int REFRESH_EVERY_TICKS = 5;

    /**
     * @param heat calor por perto (fonte perigosa OU bioma quente).
     * @param cold frio por perto (gelo/neve OU bioma congelante).
     * @param hazardAwayDirection direção NORMALIZADA pra longe da fonte
     *     perigosa mais próxima; {@code null} se não há fonte perigosa
     *     (bioma quente sozinho não gera fuga).
     */
    public record Reading(boolean heat, boolean cold, Vector hazardAwayDirection) {
    }

    private static final Reading NONE = new Reading(false, false, null);

    private Reading cached = NONE;
    private int ticksSinceRefresh = REFRESH_EVERY_TICKS;

    /** Reinicia o cache — chamar em {@code ControlLoop::start}. */
    void reset() {
        cached = NONE;
        ticksSinceRefresh = REFRESH_EVERY_TICKS;
    }

    /** Uma chamada por tick (thread principal); só re-varre a cada {@link #REFRESH_EVERY_TICKS}. */
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

        Vector away = null;
        if (nearestHazard != null) {
            away = center.toVector().subtract(nearestHazard.toVector());
            if (away.lengthSquared() < 1.0E-6) {
                away = new Vector(0, 1, 0); // dentro do bloco perigoso — sobe
            } else {
                away.normalize();
            }
        }
        return new Reading(heat, cold, away);
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
