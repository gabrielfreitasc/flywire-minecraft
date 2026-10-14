package com.flywireminecraft.bee;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

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

    // F7, AD-17
    private static final double[][] COMPASS_OFFSETS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1},
    };

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
