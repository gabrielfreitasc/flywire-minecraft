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
 * congelante. Além do nível sim/não, {@link Reading#coldSeverity} dá uma
 * escala contínua (0,0 a 1,0) — ver {@link #coldSeverityFromBiomeTemperature}.
 * Sem fuga (pedido do usuário) — frio não é perigo físico como calor, é
 * fadiga metabólica.
 *
 * <p><b>Frio desacelera (F21, 06/10/2026, pedido do usuário).</b> Moscas são
 * ectotérmicas — a temperatura corporal depende do ambiente, e com frio o
 * metabolismo desacelera: abaixo de ~18°C a locomoção já cai visivelmente,
 * perto de ~10-12°C o voo fica praticamente impossível (estado letárgico).
 * Minecraft não tem °C — {@link Reading#coldSeverity} mapeia os dois
 * limiares reais pros dois extremos da escala de temperatura de bioma do
 * próprio jogo ({@link #COLD_ONSET_BIOME_TEMPERATURE} ~18°C,
 * {@link #COLD_LETHARGIC_BIOME_TEMPERATURE} ~10-12°C), mesma ideia de
 * {@code HOT_BIOME_TEMPERATURE}/{@code COLD_BIOME_TEMPERATURE} já usada pro
 * sim/não. Consumido por {@code ControlLoop#applyColdModifiers}, mesmo
 * padrão de proxy de engenharia de {@link EnergyTracker}/{@link SleepState}
 * — não é circuito neural novo.
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
     * F21 — temperatura de bioma onde a desaceleração por frio COMEÇA,
     * equivalente a ~18°C real (ver javadoc da classe). Mesmo valor de
     * {@link #COLD_BIOME_TEMPERATURE} — abaixo disto já é "frio" pro
     * sim/não E já começa a pesar na velocidade.
     */
    private static final double COLD_ONSET_BIOME_TEMPERATURE = COLD_BIOME_TEMPERATURE;

    /**
     * F21 — temperatura de bioma onde a desaceleração por frio satura em
     * 1,0 (estado letárgico), equivalente a ~10-12°C real. Biomas congelados
     * do próprio jogo (neve, picos gelados) chegam a -0,7; -0,5 cobre a
     * maioria deles sem exigir o extremo exato.
     */
    private static final double COLD_LETHARGIC_BIOME_TEMPERATURE = -0.5;

    /**
     * @param heat calor por perto (fonte perigosa OU bioma quente).
     * @param cold frio por perto (gelo/neve OU bioma congelante) — sim/não,
     *     ver {@link #coldSeverity} pra escala contínua.
     * @param hazardAwayDirection direção NORMALIZADA pra longe da fonte
     *     perigosa mais próxima; {@code null} se não há fonte perigosa
     *     (bioma quente sozinho não gera fuga).
     * @param coldSeverity 0,0 (sem efeito) a 1,0 (letárgica) — ver javadoc
     *     da classe. Cresce com bioma mais frio; gelo/neve por perto também
     *     impõe um piso (frio local, mesmo sem o bioma ser extremo).
     */
    public record Reading(boolean heat, boolean cold, Vector hazardAwayDirection, double coldSeverity) {
    }

    /** Piso de {@link Reading#coldSeverity} quando há gelo/neve por perto, mesmo em bioma ameno. */
    private static final double COLD_SOURCE_NEARBY_SEVERITY_FLOOR = 0.3;

    // F21 — reação ao frio (pedido do usuário, 06/10/2026): com
    // coldSeverity=1,0 ("estado letárgico", ~10-12°C real) ela quase não
    // se move e voa bem baixo — mais extremo que a fome (MIN_SPEED_FRACTION
    // 0,35) porque "voo praticamente impossível" é um limite biológico mais
    // duro que fadiga por fome. Mesmo padrão de `EnergyTracker`/`SleepState`,
    // consumido por `ControlLoop#applyColdModifiers`. Tudo PROVISÓRIO.
    /** Fração da velocidade normal com coldSeverity=1,0. */
    static final double COLD_LETHARGIC_SPEED_FRACTION = 0.1;
    /**
     * Altura máxima acima do chão com coldSeverity=1,0, em blocos. F27
     * (07/10/2026): era 1,0 — medido em jogo (log: `applied.y=0,0025` =
     * `(1,0 − altura)×0,2` → presa a ~0,99 bloco) que ela não passava
     * degrau de 1 bloco e divergia da comida a cada recuperação. 2,0 =
     * degrau + corpo (~0,6) + folga. Decisão do usuário.
     */
    static final double COLD_LETHARGIC_CEILING_BLOCKS = 2.0;
    /** Altura máxima extra (acima do teto letárgico) quando coldSeverity está em 0,0. */
    static final double COLD_WARM_CEILING_EXTRA_BLOCKS = 6.0;

    private static final Reading NONE = new Reading(false, false, null, 0.0);

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

        double coldSeverity = coldSeverityFromBiomeTemperature(biomeTemperature);
        if (cold) {
            coldSeverity = Math.max(coldSeverity, COLD_SOURCE_NEARBY_SEVERITY_FLOOR);
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
        return new Reading(heat, cold, away, coldSeverity);
    }

    /**
     * F21 — mapeia a temperatura de bioma (escala do jogo) pra 0,0-1,0,
     * interpolando entre {@link #COLD_ONSET_BIOME_TEMPERATURE} (0,0, ~18°C
     * real) e {@link #COLD_LETHARGIC_BIOME_TEMPERATURE} (1,0, ~10-12°C
     * real) — ver javadoc da classe.
     */
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
