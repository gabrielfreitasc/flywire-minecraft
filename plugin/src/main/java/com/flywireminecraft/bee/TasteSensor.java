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

/** F10 — sinal de "comida" pro subcircuito `taste` (paladar apetitivo, GRNs de açúcar/água). */
public final class TasteSensor {
    private static final Set<Material> FOOD_BLOCKS = Set.of(
            Material.HONEY_BLOCK,
            Material.MELON,
            Material.PUMPKIN,
            Material.CAKE,
            Material.SWEET_BERRY_BUSH
    );

    private static final Set<Material> MATURE_FOOD_CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS
    );

    static final double FOOD_SEARCH_RADIUS_BLOCKS = 4.0;

    // F21
    static final double HUNGRY_SEARCH_RADIUS_EXTRA_BLOCKS = 4.0;

    private volatile boolean ignorePlayer = false;

    void setIgnorePlayer(boolean ignore) {
        this.ignorePlayer = ignore;
    }

    private static final double BLOCK_TOP_SURFACE_Y_OFFSET = 1.0;

    private static final double ITEM_HOVER_Y_OFFSET = 0.2;

    private static final double PLAYER_HOVER_Y_OFFSET = 1.0;

    /** F10 — pedido do usuário: "nos blocos e items dropados ela deve pousar acima deles. */
    public record FoodTarget(Location location, boolean heldByPlayer) {
    }

    public FoodTarget findNearestFood(Bee bee) {
        return findNearestFood(bee, FOOD_SEARCH_RADIUS_BLOCKS);
    }

    public FoodTarget findNearestFood(Bee bee, double searchRadiusBlocks) {
        Location center = bee.getLocation();
        World world = center.getWorld();
        if (world == null) {
            return null;
        }

        FoodTarget best = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        int radius = (int) Math.ceil(searchRadiusBlocks);
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
                    if (distanceSquared <= searchRadiusBlocks * searchRadiusBlocks
                            && distanceSquared < bestDistanceSquared) {
                        bestDistanceSquared = distanceSquared;
                        best = new FoodTarget(above, false);
                    }
                }
            }
        }

        boolean ignorePlayerNow = ignorePlayer;
        for (Entity entity : world.getNearbyEntities(
                center, searchRadiusBlocks, searchRadiusBlocks, searchRadiusBlocks)) {
            if (ignorePlayerNow && entity instanceof Player) {
                continue;
            }
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
