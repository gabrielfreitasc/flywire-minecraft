package com.flywireminecraft.bee;

import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import org.bukkit.Bukkit;
import org.bukkit.entity.Bee;

final class CompetingGoals {
    private CompetingGoals() {
    }

    static void disable(Bee bee) {
        MobGoals goals = Bukkit.getMobGoals();
        goals.removeGoal(bee, VanillaGoal.BEE_WANDER);
        goals.removeGoal(bee, VanillaGoal.BEE_GO_TO_KNOWN_FLOWER);
        goals.removeGoal(bee, VanillaGoal.BEE_POLLINATE);
        goals.removeGoal(bee, VanillaGoal.BEE_GO_TO_HIVE);
        goals.removeGoal(bee, VanillaGoal.BEE_LOCATE_HIVE);
        goals.removeGoal(bee, VanillaGoal.BEE_ENTER_HIVE);
    }
}
