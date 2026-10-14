package com.flywireminecraft.bee;

import com.destroystokyo.paper.entity.ai.MobGoals;
import org.bukkit.Location;
import org.bukkit.entity.Bee;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.logging.Logger;

/** Spikes técnicos isolados para responder: dá pra mover a abelha por código, e de que jeito? */
public final class VelocityProbe {
    private static final int DURATION_TICKS = 100;
    private static final int LOG_EVERY_TICKS = 20;

    private final Plugin plugin;
    private final Logger logger;

    public VelocityProbe(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** Modo 1 (já testado): setVelocity() a cada tick, IA desligada. */
    public void runVelocityNoAI(Bee bee) {
        boolean hadAI = bee.hasAI();
        bee.setAI(false);
        runLoop(bee, "velocity-noai", (b, tick) -> b.setVelocity(new Vector(0.3, 0.0, 0.0)),
                () -> bee.setAI(hadAI));
    }

    /** Modo 2: setVelocity() a cada tick, IA LIGADA — isola se o problema é o NoAI. */
    public void runVelocityWithAI(Bee bee) {
        runLoop(bee, "velocity-ai", (b, tick) -> b.setVelocity(new Vector(0.3, 0.0, 0.0)), () -> {
        });
    }

    /** Modo 3: usa o Pathfinder nativo da abelha, redirecionando o alvo continuamente. */
    public void runPathfinder(Bee bee) {
        runLoop(bee, "pathfinder", (b, tick) -> {
            Location target = b.getLocation().add(5, 0, 0);
            b.getPathfinder().moveTo(target, 1.0);
        }, () -> {
        });
    }

    /** Modo 4: teleporte incremental — garantido funcionar, sem física/colisão. */
    public void runTeleport(Bee bee) {
        runLoop(bee, "teleport", (b, tick) -> {
            Location next = b.getLocation().add(0.3, 0.0, 0.0);
            b.teleport(next);
        }, () -> {
        });
    }

    public void runVelocityNoCompetingGoals(Bee bee) {
        CompetingGoals.disable(bee);
        runLoop(bee, "velocity-no-competing-goals",
                (b, tick) -> b.setVelocity(new Vector(0.3, 0.0, 0.0)), () -> {
                });
    }

    private interface TickAction {
        void apply(Bee bee, int tick);
    }

    private void runLoop(Bee bee, String label, TickAction action, Runnable onFinish) {
        Location start = bee.getLocation().clone();
        logger.info(String.format(Locale.ROOT,
                "[VelocityProbe:%s] início — pos=(%.2f, %.2f, %.2f)",
                label, start.getX(), start.getY(), start.getZ()));

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!bee.isValid() || bee.isDead()) {
                    logger.warning(String.format("[VelocityProbe:%s] abelha morreu/sumiu — abortando", label));
                    cancel();
                    return;
                }

                action.apply(bee, tick);

                if (tick % LOG_EVERY_TICKS == 0) {
                    Location loc = bee.getLocation();
                    double displaced = loc.distance(start);
                    logger.info(String.format(Locale.ROOT,
                            "[VelocityProbe:%s] t=%dms pos=(%.2f, %.2f, %.2f) deslocado=%.3f vel=%s",
                            label, tick * 50, loc.getX(), loc.getY(), loc.getZ(), displaced, bee.getVelocity()));
                }

                tick++;
                if (tick >= DURATION_TICKS) {
                    Location end = bee.getLocation();
                    double totalDisplacement = end.distance(start);
                    logger.info(String.format(Locale.ROOT,
                            "[VelocityProbe:%s] FIM — deslocamento total=%.3f blocos",
                            label, totalDisplacement));
                    onFinish.run();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
