package com.flywireminecraft.bee;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/**
 * F7/AD-17 — detecta se há um "teto" de verdade acima da abelha, pra
 * distinguir "tocou chão/água em qualquer lugar" de "abrigo de verdade"
 * (decisão do usuário, 21/09/2026, ver
 * {@code MotorMapping.isSeekingShelterActive}).
 *
 * <p><b>Três tentativas anteriores, todas erradas de formas diferentes:</b>
 * <ol>
 *   <li>{@code Material#isOccluding()} — flag de OTIMIZAÇÃO DE RENDERIZAÇÃO
 *       (culling de face), não de bloqueio de chuva; folha tipicamente
 *       retorna {@code false} mesmo cobrindo de verdade.</li>
 *   <li>{@code Block#getLightFromSky()} — o mesmo sensor que gera
 *       {@code dorsal_light} no protocolo, correto pra "tem céu visível
 *       daqui?" mas NÃO pra "chove aqui?". Limiar frouxo (qualquer redução)
 *       pegava difusão de sombra vizinha; apertado (≤4) ainda tinha o
 *       problema de fundo.</li>
 *   <li>Checar coluna + 4 vizinhas por
 *       {@code getLightFromSky()}, exigindo maioria — melhorou a
 *       sensibilidade a copa esparsa, mas o problema de fundo continuava:
 *       <b>achado real, 23/09/2026 — folha reduz luz (dá sombra) mas NÃO
 *       bloqueia chuva no Minecraft</b> (mecânica real do jogo: chuva
 *       "goteja" através de folhas desde uma atualização; existe até um
 *       heightmap dedicado, {@code MOTION_BLOCKING_NO_LEAVES}, cujo
 *       propósito é justamente calcular exposição à chuva EXCLUINDO
 *       folhas). Usar luz como proxy de chuva sempre ia estar errado pra
 *       qualquer bioma com árvore — não era questão de limiar ou raio de
 *       varredura, era a métrica errada desde o início.
 * </ol>
 *
 * <p><b>Corrigido usando a métrica certa do próprio motor do jogo:</b>
 * {@code World#getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)}
 * — o MESMO heightmap que o Minecraft usa internamente pra decidir onde
 * chove de verdade (ex.: extinguir mobs em chamas), já excluindo folhas
 * corretamente. Abelha abrigada = existe algum bloco sólido não-folha
 * acima dela nessa coluna, não importa a que altura. Sem varredura manual,
 * sem proxy de luz, sem vizinhas — pergunta a métrica exata que já existe.
 */
final class ShelterSensor {

    private ShelterSensor() {
    }

    static boolean hasShelterAbove(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        int highestNonLeafY = world.getHighestBlockYAt(
                location.getBlockX(), location.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return location.getBlockY() < highestNonLeafY;
    }

    // F7/AD-17 — busca guiada (23/09/2026), pedido do usuário depois de um
    // teste controlado (cubo com teto aberto + um mini-telhado num canto):
    // sem isto, a busca só segue `heading` (direção do circuito OCELAR via
    // yaw_steering — não tem relação nenhuma com onde está o abrigo), então
    // achar um canto pequeno por "sorte" era raro; ela ficava "indo e
    // voltando" sem se aproximar. Não é busca de caminho de verdade (sem
    // A*/navegação por obstáculo) — só sonda 8 direções a uma distância
    // fixa e mira na primeira que achar coberta, funcionando como um aceno
    // simples na direção certa quando o abrigo já está por perto.
    private static final double[][] COMPASS_OFFSETS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1},
    };

    /**
     * @return direção horizontal (unitária) pra uma coluna coberta dentro de
     *     {@code lookAheadBlocks}, ou {@code null} se nenhuma das 8 direções
     *     tiver abrigo real a essa distância.
     */
    static Vector findNearbyShelterDirection(Location location, int lookAheadBlocks) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        int baseX = location.getBlockX();
        int baseZ = location.getBlockZ();
        int y = location.getBlockY();
        for (double[] offset : COMPASS_OFFSETS) {
            double length = Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1]);
            int dx = (int) Math.round(offset[0] / length * lookAheadBlocks);
            int dz = (int) Math.round(offset[1] / length * lookAheadBlocks);
            int highestNonLeafY = world.getHighestBlockYAt(
                    baseX + dx, baseZ + dz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (y < highestNonLeafY) {
                return new Vector(offset[0], 0, offset[1]).normalize();
            }
        }
        return null;
    }
}
