package com.flywireminecraft.bee;

/**
 * F11 (26/09/2026, pedido do usuário) — "fome"/energia da abelha.
 *
 * <p><b>Achado real, não contornado (ver `docs/03-roadmap-fases.md` F11):</b>
 * os neurônios reais de fome/saciedade em *Drosophila* (IPC — células
 * produtoras de insulina; Hugin-RG; DH44) sinalizam por HORMÔNIO (caem na
 * hemolinfa), não por sinapse ponto-a-ponto. Confirmado no dado bruto do
 * conectoma: IPC tem 143 conexões de SAÍDA no conectoma inteiro, nenhuma
 * passa de 3 sinapses (limiar do projeto é ≥5, RN-03) — mesmo padrão em
 * Hugin-RG/DH44/ITP (zero arestas de saída acima do limiar, todos). Não dá
 * pra simular como circuito LIF sem fabricar uma conexão sináptica que não
 * existe de verdade — o projeto não faz isso (ver `CONVENCOES.md`).
 *
 * <p>Decisão do usuário (26/09/2026, via {@code AskUserQuestion}, entre 3
 * opções): medidor de energia por ENGENHARIA, documentado claramente como
 * proxy de jogo — <b>ao contrário de todo canal anterior</b> (phototaxis,
 * grooming, hygrotaxis, startle, escape_drive, appetite), isto NÃO é
 * simulação de neurônio real. Puramente do lado do plugin — não passa pela
 * ponte/simulador.
 *
 * <p>Mecânica: energia começa cheia (1,0), cai com o tempo (metabolismo
 * basal) e com deslocamento (voar custa energia de verdade — achado do
 * usuário: "as sinapses neurais conseguem detectar que está sendo gasto
 * uma certa quantidade de energia... e precisa repor"), sobe enquanto ela
 * está "comendo" — mesmo alvo/distância de chegada do `taste` (F10,
 * {@code MotorMapping.TASTE_ARRIVAL_THRESHOLD_BLOCKS}), não um sensor
 * novo.
 */
final class EnergyTracker {

    private static final double ENERGY_MAX = 1.0;
    // Consumo passivo (metabolismo basal), por tick. PROVISÓRIO — a 20Hz,
    // esgota do zero (parada, sem comer) em ~1000s (~16min).
    private static final double BASE_DEPLETION_PER_TICK = 0.001;
    // Consumo adicional proporcional à distância voada no tick (voar
    // custa energia de verdade, não só existir). PROVISÓRIO.
    private static final double MOVEMENT_DEPLETION_PER_BLOCK = 0.002;
    // Reposição por tick enquanto "comendo" — enche do zero em ~5s (100
    // ticks) de contato sustentado com comida. PROVISÓRIO.
    private static final double REPLENISH_PER_TICK_WHILE_EATING = 0.01;

    private volatile double energy = ENERGY_MAX;

    /** Chamar em {@code ControlLoop::start} — começa cheia a cada novo episódio de controle. */
    void reset() {
        energy = ENERGY_MAX;
    }

    /**
     * Chamar uma vez por tick (thread principal).
     *
     * @param blocksMovedThisTick deslocamento real no tick que passou —
     *     mesma medida que {@link TouchSensor} já usa
     *     ({@code lastAppliedVelocity.length()}), não um sensor novo.
     * @param eating {@code true} quando ela está perto o bastante de
     *     comida de verdade pra estar "se alimentando" (mesmo critério de
     *     chegada do `taste`, não um estado novo).
     */
    void tick(double blocksMovedThisTick, boolean eating) {
        if (eating) {
            energy = Math.min(ENERGY_MAX, energy + REPLENISH_PER_TICK_WHILE_EATING);
        } else {
            double depletion = BASE_DEPLETION_PER_TICK + blocksMovedThisTick * MOVEMENT_DEPLETION_PER_BLOCK;
            energy = Math.max(0.0, energy - depletion);
        }
    }

    /** Nível atual — 0,0 (faminta) a 1,0 (cheia). */
    double level() {
        return energy;
    }
}
