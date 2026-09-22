package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * F7/AD-17 — detecta se há um "teto" de verdade acima da abelha, pra
 * distinguir "tocou chão/água em qualquer lugar" de "abrigo de verdade"
 * (decisão do usuário, 21/09/2026, ver
 * {@code MotorMapping.isSeekingShelterActive}).
 *
 * <p><b>Primeira tentativa (falhou, real, 21/09/2026):</b> varredura manual
 * de blocos acima checando {@code Material#isOccluding()}. Usuário testou
 * debaixo de cobertura real e ela continuou "vagando" — `isOccluding()` é
 * uma flag de OTIMIZAÇÃO DE RENDERIZAÇÃO (culling de face entre blocos
 * vizinhos), não de bloqueio de luz/chuva; folhas de árvore, por exemplo,
 * tipicamente retornam `false` mesmo cobrindo de verdade.
 *
 * <p><b>Corrigido reaproveitando sensor já validado neste projeto:</b>
 * {@code Block#getLightFromSky()} (o mesmo que gera `dorsal_light` no
 * protocolo, ver `docs/02-arquitetura.md`) é o cálculo de luz do céu do
 * PRÓPRIO motor do jogo — já correto pra qualquer tipo de bloco (folha,
 * vidro, laje, etc.), porque é exatamente o que o Minecraft usa pra decidir
 * onde chove de verdade. `dorsal_light` já era documentado como confiável
 * pra "tem céu visível daqui?" (indoor/outdoor) — ver `CLAUDE.md`,
 * armadilha sobre `getLightFromSky()`. Sem varredura manual de blocos: o
 * valor já reflete tudo que está acima, até onde o céu enxerga.
 *
 * <p><b>Limiar errado na primeira versão (real, 22/09/2026):</b>
 * {@code < 15} (qualquer redução conta) — usuário relatou ela parando fora
 * de cobertura visível. Log de diagnóstico (`ControlLoop`) confirmou dois
 * casos reais no mesmo teste: {@code skylight=0} (cobertura de verdade,
 * correto) e {@code skylight=14} (só 1 ponto de atenuação — luz difundindo
 * de uma sombra vizinha, não bloco de verdade acima dela). O céu propaga
 * luz lateralmente entre colunas vizinhas no motor do jogo; ficar perto de
 * uma sombra (sem estar embaixo dela) já derruba o valor um pouco. Limiar
 * apertado pra exigir bloqueio substancial, não qualquer difusão —
 * calibração provisória com só esses dois pontos de dado, não uma
 * varredura completa; pode precisar de ajuste fino de novo.
 *
 * <p><b>Passou por árvores sem parar (real, 22/09/2026).</b> Usuário
 * perguntou se é a ALTURA da árvore/construção — provavelmente não: ar não
 * atenua luz no motor do jogo (só bloco atenua), então uma folha lá no alto
 * com ar livre até o chão já deveria abaixar o skylight embaixo dela do
 * mesmo jeito que uma cobertura baixa. Suspeita mais provável: copa de
 * árvore no Minecraft é naturalmente esparsa (blocos de folha com buracos
 * entre eles) — checar só a coluna EXATA onde a abelha está faz ela
 * "passar batido" se cruzar por um buraco da copa, mesmo estando
 * visualmente debaixo da árvore. **Corrigido:** checa a coluna dela mais
 * as 4 colunas vizinhas (padrão "mais", N/S/L/O) — conta como abrigo se
 * QUALQUER uma tiver skylight baixo, cobrindo os buracos naturais da copa
 * sem precisar de alinhamento perfeito com um bloco de folha específico.
 */
final class ShelterSensor {

    private static final int MAX_SKYLIGHT_UNDER_SHELTER = 4;

    private ShelterSensor() {
    }

    static boolean hasShelterAbove(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        int[][] columns = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : columns) {
            Block block = world.getBlockAt(x + offset[0], y, z + offset[1]);
            if (block.getLightFromSky() <= MAX_SKYLIGHT_UNDER_SHELTER) {
                return true;
            }
        }
        return false;
    }
}
