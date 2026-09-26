package com.flywireminecraft.bee;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * F10 (25/09/2026) — sinal de "comida" pro subcircuito `taste` (paladar
 * apetitivo, GRNs de açúcar/água). Achado do usuário: diferente de
 * humanos (papilas gustativas só na boca), a mosca prova com o CORPO
 * TODO — os próprios tipos celulares da semente confirmam isso (sensilas
 * de perna, boca e faringe, ver `sim/tools/build_f10_circuit.py`). Por
 * isso o gatilho aqui é mais amplo que "comeu": qualquer contato com
 * comida conta, não só ingestão.
 *
 * <p><b>Comportamento (25/09/2026, pedido do usuário, ver
 * {@link MotorMapping}).</b> Não é só sim/não — {@link #findNearestFood}
 * localiza ONDE a comida está, pra ela poder voar até lá. Bloco/item
 * dropado: pousa em cima. Jogador segurando comida: segue o jogador
 * enquanto o item estiver na mão.
 *
 * <p>Três fontes, a mais próxima vence:
 * <ul>
 *   <li><b>Bloco de comida</b> — mel, melancia, abóbora, bolo, ou
 *       plantação madura, num cubo de blocos ao redor. Curadoria manual —
 *       Bukkit não tem uma flag "isFood" pra Material de BLOCO (só pra
 *       item, ver abaixo), diferente de itens.</li>
 *   <li><b>Item de comida largado no chão</b> — pedido do usuário: fruta/
 *       comida dropada por perto também deveria disparar. Usa
 *       {@link Material#isEdible()}, a flag nativa do Bukkit pra "dá pra
 *       comer" — mais robusto que curar uma lista manual de itens (cobre
 *       maçã, pão, carne, fatia de melancia, cenoura, batata, biscoito,
 *       torta de abóbora, etc. automaticamente).</li>
 *   <li><b>Jogador segurando comida</b> — mesmo pedido do usuário: jogador
 *       com item comestível na mão (principal ou secundária) por perto
 *       também conta, mesma checagem {@code isEdible()}.</li>
 * </ul>
 *
 * <p>Raio/lista de blocos são estimativa de engenharia, PROVISÓRIOS — mesma
 * disciplina de {@link TouchSensor}/{@link AlarmSensor}/{@link LoomingSensor}.
 */
public final class TasteSensor {

    /**
     * Blocos "de comida" de verdade — mel, melancia, abóbora, bolo (ver
     * pedido original do usuário). Bukkit não tem {@code isEdible()} pra
     * bloco (só item), curadoria manual. {@code CARVED_PUMPKIN} fora —
     * é decorativo/máscara, não comida.
     */
    private static final Set<Material> FOOD_BLOCKS = Set.of(
            Material.HONEY_BLOCK,
            Material.MELON,
            Material.PUMPKIN,
            Material.CAKE,
            Material.SWEET_BERRY_BUSH
    );

    /** Plantações — só conta madura (pronta pra colher), não muda em qualquer estágio. */
    private static final Set<Material> MATURE_FOOD_CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS
    );

    /** Raio de busca por bloco/item/jogador com comida, em blocos. Provisório. */
    private static final double FOOD_SEARCH_RADIUS_BLOCKS = 4.0;

    /**
     * Deslocamento em Y pra mirar exatamente na SUPERFÍCIE DE CIMA de um
     * bloco (não afundada dentro dele) — {@code Block#getLocation()}
     * devolve o canto inferior, +1,0 em Y é o topo exato. Bug real, achado
     * do usuário (25/09/2026): mesma constante era reaproveitada pra ITEM
     * também, e item largado descansa REBAIXADO (perto do chão, ~0,1-0,25
     * de altura) — reaproveitar o mesmo +1,0 fazia ela pairar um bloco
     * INTEIRO acima do item, longe demais pra parecer "encostada".
     * Separado abaixo.
     */
    private static final double BLOCK_TOP_SURFACE_Y_OFFSET = 1.0;

    /** Item largado fica rente ao chão — deslocamento bem menor que o do bloco (ver acima). */
    private static final double ITEM_HOVER_Y_OFFSET = 0.2;

    /** Altura acima dos pés do jogador — perto da mão/tronco, pra "seguir" parecer natural. */
    private static final double PLAYER_HOVER_Y_OFFSET = 1.0;

    /**
     * F10 — pedido do usuário (25/09/2026): "nos blocos e items dropados
     * ela deve pousar acima deles. Em caso de items segurados pelo player
     * ela deve seguir o player enquanto o item estiver sendo segurado."
     *
     * @param location onde voar (já com o deslocamento pra ficar ACIMA do
     *     bloco/item/jogador, nunca a posição crua do bloco/entidade).
     * @param heldByPlayer {@code true} só quando a fonte é um jogador
     *     segurando comida — quem chama ({@code ControlLoop}) usa isto só
     *     pra decidir se recalcula a posição a cada troca (jogador se move)
     *     ou não; o comportamento de voo em si (ir até lá, parar perto) é o
     *     MESMO nos dois casos, recomputado a cada troca de qualquer jeito.
     */
    public record FoodTarget(Location location, boolean heldByPlayer) {
    }

    /**
     * Sinal de nível — a fonte de comida mais próxima agora, ou
     * {@code null} se nenhuma estiver no raio. Roda a varredura de blocos E
     * de entidades numa passada só (reaproveitada tanto pro estímulo do
     * circuito quanto pro alvo de voo — ver {@code ControlLoop}).
     */
    public FoodTarget findNearestFood(Bee bee) {
        Location center = bee.getLocation();
        World world = center.getWorld();
        if (world == null) {
            return null;
        }

        FoodTarget best = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        int radius = (int) Math.ceil(FOOD_SEARCH_RADIUS_BLOCKS);
        int bx = center.getBlockX();
        int by = center.getBlockY();
        int bz = center.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block block = world.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (!isFoodBlock(block)) {
                        continue;
                    }
                    Location above = block.getLocation().add(0.5, BLOCK_TOP_SURFACE_Y_OFFSET, 0.5);
                    double distanceSquared = above.distanceSquared(center);
                    if (distanceSquared <= FOOD_SEARCH_RADIUS_BLOCKS * FOOD_SEARCH_RADIUS_BLOCKS
                            && distanceSquared < bestDistanceSquared) {
                        bestDistanceSquared = distanceSquared;
                        best = new FoodTarget(above, false);
                    }
                }
            }
        }

        for (Entity entity : world.getNearbyEntities(
                center, FOOD_SEARCH_RADIUS_BLOCKS, FOOD_SEARCH_RADIUS_BLOCKS, FOOD_SEARCH_RADIUS_BLOCKS)) {
            FoodTarget candidate = asFoodTarget(entity);
            if (candidate == null) {
                continue;
            }
            double distanceSquared = candidate.location().distanceSquared(center);
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                best = candidate;
            }
        }

        return best;
    }

    private FoodTarget asFoodTarget(Entity entity) {
        if (entity instanceof Item item && isEdible(item.getItemStack())) {
            return new FoodTarget(item.getLocation().add(0, ITEM_HOVER_Y_OFFSET, 0), false);
        }
        if (entity instanceof Player player
                && (isEdible(player.getInventory().getItemInMainHand())
                        || isEdible(player.getInventory().getItemInOffHand()))) {
            return new FoodTarget(player.getLocation().add(0, PLAYER_HOVER_Y_OFFSET, 0), true);
        }
        return null;
    }

    private boolean isFoodBlock(Block block) {
        Material type = block.getType();
        if (FOOD_BLOCKS.contains(type)) {
            return true;
        }
        if (MATURE_FOOD_CROPS.contains(type) && block.getBlockData() instanceof Ageable ageable) {
            return ageable.getAge() == ageable.getMaximumAge();
        }
        return false;
    }

    private boolean isEdible(ItemStack stack) {
        return stack != null && stack.getType().isEdible();
    }
}
