package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Bee;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * L5→L6 — laço de controle em tempo real: sensor da abelha → ponte → vetor motor → velocidade aplicada.
 *
 * <p>RN-06: o tick do jogo nunca espera o simulador. Sensor e velocidade rodam na thread
 * principal (a API do Bukkit não é thread-safe); a troca com a ponte roda numa thread
 * dedicada, uma por vez, e cada tick aplica o último vetor motor já recebido.
 */
public final class ControlLoop {
    private final Plugin plugin;
    private final FlywireBeeMarker marker;
    private final DamageTracker damageTracker;
    private final TouchSensor touchSensor = new TouchSensor();
    private final AlarmSensor alarmSensor;
    private final LoomingSensor loomingSensor = new LoomingSensor();
    private final TasteSensor tasteSensor = new TasteSensor();
    private volatile boolean tasteTargetHeldByPlayer = false;
    private volatile boolean foodVisible = false;
    private volatile boolean seekingFood = false;
    private volatile boolean satietyGateSuppressedForExperiment = false;
    private volatile Location foodTargetLocation = null;
    // F28
    private static final double FOOD_TARGET_CLEARANCE_BLOCKS = 0.5;
    // Parar na comida é intencional: sem isto o sistema anti-obstáculo a empurra para longe (F14).
    private volatile boolean tasteArrived = false;
    private final EnergyTracker energyTracker = new EnergyTracker();
    private final ThermalSensor thermalSensor = new ThermalSensor();
    private final SleepState sleepState = new SleepState();
    private volatile Vector heatAvoidDirection = null;
    private volatile boolean heatNear = false;
    // F13
    private static final double WAKE_STARTLE_THRESHOLD = 0.5;
    private volatile boolean coldNear = false;
    private volatile double coldSeverity = 0.0;
    private final String bridgeHost;
    private final int bridgePort;

    private static final int LOG_EVERY_TICKS = 20;
    private static final int VISUALIZE_EVERY_TICKS = 5;
    private static final int HUD_EVERY_TICKS = 4;

    private final ActivityVisualizer visualizer = new ActivityVisualizer();
    private final StatusLabel statusLabel = new StatusLabel();

    private BridgeClient bridge;
    private ExecutorService bridgeExecutor;
    private BukkitTask tickTask;
    private final AtomicBoolean exchangeInFlight = new AtomicBoolean(false);
    private volatile Vector latestVelocity = new Vector(0, 0, 0);
    private volatile Vector lastAppliedVelocity = new Vector(0, 0, 0);
    private volatile JsonObject latestMotor = new JsonObject();
    private volatile int latestActiveDn = 0;
    private volatile JsonObject latestBristleMotor = new JsonObject();
    private volatile JsonObject latestHygroMotor = new JsonObject();
    private volatile JsonObject latestJohnstonMotor = new JsonObject();
    private volatile JsonObject latestEscapeMotor = new JsonObject();
    private volatile JsonObject latestTasteMotor = new JsonObject();
    private volatile JsonObject latestThermoMotor = new JsonObject();
    // F7, AD-17
    private static final int GROOMING_TRANSITION_GRACE_TICKS = 10;
    private boolean wasGroomingActive = false;
    private int groomingGraceTicksLeft = 0;

    // F7, AD-17
    private static final int STUCK_CHECK_TICKS = 20;
    private static final double STUCK_DISPLACEMENT_THRESHOLD_BLOCKS = 0.3;
    private static final double RECOVERY_BOOST_BLOCKS_PER_TICK = 0.15;
    private static final int RECOVERY_BOOST_TICKS = 30;
    // F26, F11, F25, F27
    private static final int OBSTACLE_GRACE_TICKS = 20;
    private int obstacleGraceTicksLeft = 0;
    // F7
    private static final double RECOVERY_BOOST_HORIZONTAL_BLOCKS_PER_TICK = 0.15;
    // F9
    private static final java.util.Random STUCK_RECOVERY_RNG = new java.util.Random();
    private Location stuckCheckAnchor = null;
    private int stuckCheckTicksLeft = STUCK_CHECK_TICKS;
    private int recoveryBoostTicksLeft = 0;
    private Vector recoveryEscapeDirection = new Vector(0, 0, 0);
    private int consecutiveStuckCount = 0;

    // F7, AD-17, RN-08, RN-09
    private static final java.util.Random GROOMING_DODGE_RNG = new java.util.Random();
    private static final int DODGE_BOOST_TICKS = 20;
    private static final double DODGE_BOOST_HORIZONTAL_BLOCKS_PER_TICK = 0.2;
    private volatile boolean groomingEpisodeIsDodge = false;
    private int dodgeBoostTicksLeft = 0;
    private Vector dodgeDirection = new Vector(0, 0, 0);

    // F9
    private static final int ESCAPE_LATCH_TICKS = 50;
    private int escapeLatchTicksLeft = 0;
    private Vector escapeLatchDirection = new Vector(0, 0, 0);
    // F7, AD-17
    private static final int LANDED_GRACE_TICKS = 10;
    // F7, AD-17
    private static final int SHELTER_ABANDON_TICKS = 60;
    // F7, AD-17
    private static final int SHELTER_LOOK_AHEAD_BLOCKS = 4;
    private volatile int ticksSinceGroundOrWaterContact = LANDED_GRACE_TICKS;
    private volatile boolean shelterFoundThisEpisode = false;
    private volatile boolean hasTouchedThisEpisode = false;

    private volatile long exchangeCount = 0;
    private volatile long exchangeFailures = 0;
    private long tickCount = 0;
    private volatile Double forcedLight = null;
    private volatile boolean touchLesioned = false;
    private volatile boolean hygroLesioned = false;
    private volatile boolean bristleSuppressedForExperiment = false;
    private volatile boolean escapeLesioned = false;
    private volatile boolean ignoringPlayer = false;
    private volatile boolean tasteLesioned = false;
    private volatile boolean thermoLesioned = false;
    private volatile boolean johnstonLesioned = false;
    private volatile boolean visualize = true;
    private volatile Vector heading = null;

    private final Set<String> mutedGroups = ConcurrentHashMap.newKeySet();
    private volatile boolean muteDirty = false;

    /** F5 — liga/desliga as partículas de atividade. */
    public void setVisualize(boolean visualize) {
        this.visualize = visualize;
    }

    public void mute(String groupName) {
        mutedGroups.add(groupName);
        muteDirty = true;
    }

    public void unmute(String groupName) {
        mutedGroups.remove(groupName);
        muteDirty = true;
    }

    public void unmuteAll() {
        mutedGroups.clear();
        muteDirty = true;
    }

    public Set<String> getMutedGroups() {
        return Set.copyOf(mutedGroups);
    }

    private volatile BridgeClient.StimulateSpec directedStimulus;
    private volatile boolean stimulateDirty = false;

    public void stimulate(String groupName, double amplitude) {
        directedStimulus = new BridgeClient.StimulateSpec(groupName, amplitude);
        stimulateDirty = true;
    }

    public void stopStimulating() {
        directedStimulus = new BridgeClient.StimulateSpec(null, 0.0);
        stimulateDirty = true;
    }

    public void setLesioned(boolean lesioned) {
        this.forcedLight = lesioned ? 0.0 : null;
    }

    public void setTouchLesioned(boolean lesioned) {
        this.touchLesioned = lesioned;
    }

    /** F22 — "todos os circuitos não reagem à presença do jogador". */
    public void setIgnorePlayer(boolean ignore) {
        this.ignoringPlayer = ignore;
        touchSensor.setIgnorePlayer(ignore);
        loomingSensor.setIgnorePlayer(ignore);
        tasteSensor.setIgnorePlayer(ignore);
    }

    /** Estado atual de #setIgnorePlayer — experimentos que forçam o modo restauram o anterior no fim. */
    public boolean isIgnoringPlayer() {
        return ignoringPlayer;
    }

    public void refillEnergyForExperiment() {
        energyTracker.reset();
    }

    public void setHygroLesioned(boolean lesioned) {
        this.hygroLesioned = lesioned;
    }

    public void setBristleSuppressedForExperiment(boolean suppressed) {
        this.bristleSuppressedForExperiment = suppressed;
    }

    public void setSatietyGateSuppressedForExperiment(boolean suppressed) {
        this.satietyGateSuppressedForExperiment = suppressed;
    }

    public void setEscapeLesioned(boolean lesioned) {
        this.escapeLesioned = lesioned;
    }

    public void setTasteLesioned(boolean lesioned) {
        this.tasteLesioned = lesioned;
    }

    public void setThermoLesioned(boolean lesioned) {
        this.thermoLesioned = lesioned;
    }

    public void setJohnstonLesioned(boolean lesioned) {
        this.johnstonLesioned = lesioned;
    }

    /** F14 — exposto pra JohnstonLesionExperiment saber quando pode começar a medir. */
    public boolean isDrowsy() {
        return sleepState.isDrowsy();
    }

    public void setForcedLight(Double level) {
        this.forcedLight = level;
    }

    /** F6/AD-16 — reinicia a direção comandada a partir da orientação real da abelha no próximo tick. */
    public void resetHeading() {
        this.heading = null;
    }

    public ControlLoop(
            Plugin plugin,
            FlywireBeeMarker marker,
            DamageTracker damageTracker,
            AlarmSensor alarmSensor,
            String bridgeHost,
            int bridgePort
    ) {
        this.plugin = plugin;
        this.marker = marker;
        this.damageTracker = damageTracker;
        this.alarmSensor = alarmSensor;
        this.bridgeHost = bridgeHost;
        this.bridgePort = bridgePort;
    }

    public boolean isRunning() {
        return tickTask != null;
    }

    public void start() {
        if (isRunning()) {
            return;
        }
        bridgeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "flywire-control-bridge");
            t.setDaemon(true);
            return t;
        });
        latestVelocity = new Vector(0, 0, 0);
        lastAppliedVelocity = new Vector(0, 0, 0);
        heading = null;
        touchSensor.reset();
        loomingSensor.reset();
        wasGroomingActive = false;
        groomingGraceTicksLeft = 0;
        stuckCheckAnchor = null;
        stuckCheckTicksLeft = STUCK_CHECK_TICKS;
        recoveryBoostTicksLeft = 0;
        obstacleGraceTicksLeft = 0;
        recoveryEscapeDirection = new Vector(0, 0, 0);
        consecutiveStuckCount = 0;
        groomingEpisodeIsDodge = false;
        dodgeBoostTicksLeft = 0;
        dodgeDirection = new Vector(0, 0, 0);
        escapeLatchTicksLeft = 0;
        escapeLatchDirection = new Vector(0, 0, 0);
        tasteTargetHeldByPlayer = false;
        energyTracker.reset();
        thermalSensor.reset();
        sleepState.reset();
        heatAvoidDirection = null;
        heatNear = false;
        coldNear = false;
        coldSeverity = 0.0;
        foodVisible = false;
        foodTargetLocation = null;
        ticksSinceGroundOrWaterContact = LANDED_GRACE_TICKS;
        shelterFoundThisEpisode = false;
        hasTouchedThisEpisode = false;
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::onTick, 0L, 1L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        LiveHud.clear(plugin);
        statusLabel.remove();
        if (bridgeExecutor != null) {
            bridgeExecutor.shutdownNow();
            bridgeExecutor = null;
        }
        if (bridge != null) {
            try {
                bridge.close();
            } catch (IOException ignored) {
            }
            bridge = null;
        }
    }

    private void onTick() {
        Bee bee = findMarkedBee();
        if (bee == null) {
            return;
        }

        // Com grooming no controle não grava contato: o próprio pouso viraria toque e realimentaria o grooming.
        boolean groomingActive = MotorMapping.isGroomingActive(latestBristleMotor);
        if (groomingActive != wasGroomingActive) {
            groomingGraceTicksLeft = GROOMING_TRANSITION_GRACE_TICKS;
            if (groomingActive) {
                groomingEpisodeIsDodge = GROOMING_DODGE_RNG.nextBoolean();
                if (groomingEpisodeIsDodge) {
                    dodgeBoostTicksLeft = DODGE_BOOST_TICKS;
                    Vector awayFrom = heading != null ? heading : bee.getLocation().getDirection();
                    dodgeDirection = horizontalOpposite(awayFrom);
                }
                plugin.getLogger().info("[ControlLoop] episódio de grooming — sorteio: "
                        + (groomingEpisodeIsDodge ? "desvio" : "pouso e limpeza"));
            }
        }
        wasGroomingActive = groomingActive;

        if (groomingActive || groomingGraceTicksLeft > 0) {
            touchSensor.reset();
            if (groomingGraceTicksLeft > 0) {
                groomingGraceTicksLeft--;
            }
        } else {
            touchSensor.recordTick(bee, lastAppliedVelocity);
        }

        loomingSensor.recordTick(bee);

        if (stuckCheckAnchor == null) {
            stuckCheckAnchor = bee.getLocation();
        } else if (recoveryBoostTicksLeft == 0) {
            stuckCheckTicksLeft--;
            if (stuckCheckTicksLeft <= 0) {
                Location current = bee.getLocation();
                double dx = current.getX() - stuckCheckAnchor.getX();
                double dz = current.getZ() - stuckCheckAnchor.getZ();
                double moved = Math.sqrt(dx * dx + dz * dz);
                boolean landedStable = ticksSinceGroundOrWaterContact < LANDED_GRACE_TICKS;
                boolean intentionalLanding = (landedStable && groomingActive)
                        || (shelterFoundThisEpisode
                                && MotorMapping.isSeekingShelterActive(latestHygroMotor))
                        || tasteArrived;
                double effectiveStuckThreshold = STUCK_DISPLACEMENT_THRESHOLD_BLOCKS * fatigueSpeedFraction();
                if (moved < effectiveStuckThreshold && !intentionalLanding) {
                    consecutiveStuckCount++;
                    recoveryBoostTicksLeft = RECOVERY_BOOST_TICKS;
                    if (consecutiveStuckCount == 1) {
                        Vector commandedDirection = heading != null ? heading : bee.getLocation().getDirection();
                        recoveryEscapeDirection = horizontalOpposite(commandedDirection);
                    } else {
                        recoveryEscapeDirection = randomHorizontalDirection();
                    }
                    plugin.getLogger().info(String.format(Locale.ROOT,
                            "[ControlLoop] recuperação (tentativa %d): só %.2f blocos em %d ticks "
                                    + "(limiar efetivo %.2f, fadiga %.2f) — empurrão pra cima e pra longe (%s)",
                            consecutiveStuckCount, moved, STUCK_CHECK_TICKS, effectiveStuckThreshold,
                            fatigueSpeedFraction(), recoveryEscapeDirection));
                    plugin.getLogger().info("[Geometria] (na recuperação) "
                            + geometryDescription(bee, tasteSensor.findNearestFood(bee,
                                    TasteSensor.FOOD_SEARCH_RADIUS_BLOCKS
                                            + energyTracker.hungerFactor() * TasteSensor.HUNGRY_SEARCH_RADIUS_EXTRA_BLOCKS)));
                } else {
                    consecutiveStuckCount = 0;
                }
                stuckCheckAnchor = bee.getLocation();
                stuckCheckTicksLeft = STUCK_CHECK_TICKS;
            }
        }

        Vector velocityToApply;
        Vector heatAvoid = heatAvoidDirection;
        if (heatAvoid != null) {
            velocityToApply = MotorMapping.heatAvoidVelocity(heatAvoid);
        } else if (recoveryBoostTicksLeft > 0) {
            velocityToApply = new Vector(
                    recoveryEscapeDirection.getX() * RECOVERY_BOOST_HORIZONTAL_BLOCKS_PER_TICK,
                    RECOVERY_BOOST_BLOCKS_PER_TICK,
                    recoveryEscapeDirection.getZ() * RECOVERY_BOOST_HORIZONTAL_BLOCKS_PER_TICK);
            recoveryBoostTicksLeft--;
            if (recoveryBoostTicksLeft == 0) {
                obstacleGraceTicksLeft = OBSTACLE_GRACE_TICKS;
            }
        } else if (dodgeBoostTicksLeft > 0) {
            velocityToApply = new Vector(
                    dodgeDirection.getX() * DODGE_BOOST_HORIZONTAL_BLOCKS_PER_TICK,
                    0,
                    dodgeDirection.getZ() * DODGE_BOOST_HORIZONTAL_BLOCKS_PER_TICK);
            dodgeBoostTicksLeft--;
        } else if (escapeLatchTicksLeft > 0) {
            velocityToApply = latestVelocity;
        } else {
            boolean obstacleGrace = obstacleGraceTicksLeft > 0;
            if (obstacleGrace) {
                obstacleGraceTicksLeft--;
            }
            velocityToApply = applyHungerModifiers(bee, latestVelocity, obstacleGrace);
            velocityToApply = applyColdModifiers(bee, velocityToApply, obstacleGrace);
            velocityToApply = applyForagingSearchModifiers(bee, velocityToApply, obstacleGrace);
            if (sleepState.isDrowsy()) {
                velocityToApply = limitSpeedAndHeight(
                        bee, velocityToApply, SleepState.DROWSY_SPEED_FRACTION, SleepState.DROWSY_CEILING_BLOCKS);
            }
        }
        bee.setVelocity(velocityToApply);
        lastAppliedVelocity = velocityToApply;

        if (velocityToApply.lengthSquared() > 1.0E-6) {
            Vector dir = velocityToApply.clone().normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
            bee.setRotation(yaw, bee.getLocation().getPitch());
        }

        if (visualize && tickCount % VISUALIZE_EVERY_TICKS == 0) {
            visualizer.render(bee, latestMotor, latestBristleMotor, latestHygroMotor, latestJohnstonMotor,
                    latestEscapeMotor, latestTasteMotor, latestThermoMotor, heatNear, coldNear, coldSeverity);
            statusLabel.update(bee, latestBristleMotor, latestHygroMotor, latestJohnstonMotor, latestTasteMotor,
                    groomingEpisodeIsDodge, shelterFoundThisEpisode, escapeLatchTicksLeft > 0,
                    tasteTargetHeldByPlayer, energyTracker.level(),
                    latestThermoMotor, heatNear, coldNear, heatAvoidDirection != null,
                    sleepState.isDrowsy(), sleepState.isWokenAtNight(), coldSeverity);
        }
        if (tickCount % HUD_EVERY_TICKS == 0) {
            LiveHud.update(plugin, latestMotor, latestActiveDn, latestBristleMotor, latestHygroMotor,
                    latestJohnstonMotor, latestEscapeMotor, latestTasteMotor, latestThermoMotor,
                    energyTracker.level(), sleepLabel());
        }

        double realLight = bee.getLocation().getBlock().getLightLevel() / 15.0;
        Double forced = forcedLight;
        double light = forced != null ? forced : realLight;
        boolean raining = bee.getWorld().hasStorm();

        if (tickCount % LOG_EVERY_TICKS == 0) {
            String grooming = latestBristleMotor.has("grooming")
                    ? String.format(Locale.ROOT, "%.3f", latestBristleMotor.get("grooming").getAsDouble())
                    : "-";
            String hygrotaxis = latestHygroMotor.has("hygrotaxis")
                    ? String.format(Locale.ROOT, "%.3f", latestHygroMotor.get("hygrotaxis").getAsDouble())
                    : "-";
            String startle = latestJohnstonMotor.has("startle")
                    ? String.format(Locale.ROOT, "%.3f", latestJohnstonMotor.get("startle").getAsDouble())
                    : "-";
            String escapeDrive = latestEscapeMotor.has("escape_drive")
                    ? String.format(Locale.ROOT, "%.3f", latestEscapeMotor.get("escape_drive").getAsDouble())
                    : "-";
            String appetite = latestTasteMotor.has("appetite")
                    ? String.format(Locale.ROOT, "%.3f", latestTasteMotor.get("appetite").getAsDouble())
                    : "-";
            String thermal = latestThermoMotor.has("thermal")
                    ? String.format(Locale.ROOT, "%.3f", latestThermoMotor.get("thermal").getAsDouble())
                    : "-";
            double loggedHunger = energyTracker.hungerFactor();
            double loggedFoodSearchRadius = TasteSensor.FOOD_SEARCH_RADIUS_BLOCKS
                    + loggedHunger * TasteSensor.HUNGRY_SEARCH_RADIUS_EXTRA_BLOCKS;
            TasteSensor.FoodTarget loggedFood = seekingFood
                    ? tasteSensor.findNearestFood(bee, loggedFoodSearchRadius)
                    : null;
            String tasteDist = loggedFood != null
                    ? String.format(Locale.ROOT, "%.2f", loggedFood.location().distance(bee.getLocation()))
                    : "-";
            ThermalSensor.Reading loggedThermal = thermalSensor.read(bee);
            plugin.getLogger().info(String.format(Locale.ROOT,
                    "[ControlLoop] light=%.2f (real=%.2f, forçado=%s) vel=%s applied=%s trocas=%d falhas=%d "
                            + "proximity=%s grooming=%s onGround=%s raining=%s hygrotaxis=%s "
                            + "hostileMob=%s startle=%s looming=%s escapeDrive=%s food=%s appetite=%s "
                            + "tasteDist=%s hunger=%.2f energy=%.2f seekingFood=%s foodSearchRadius=%.2f "
                            + "heatNear=%s coldNear=%s coldSeverity=%.2f thermal=%s",
                    light, realLight, forced, latestVelocity, lastAppliedVelocity, exchangeCount, exchangeFailures,
                    touchSensor.isNearSomething(bee), grooming, bee.isOnGround(), raining, hygrotaxis,
                    alarmSensor.isHostileMobNearby(bee), startle, loomingSensor.isLoomingThreat(), escapeDrive,
                    loggedFood != null, appetite, tasteDist, loggedHunger, energyTracker.level(), seekingFood,
                    loggedFoodSearchRadius,
                    loggedThermal.heat(), loggedThermal.cold(),
                    loggedThermal.coldSeverity(), thermal));
            plugin.getLogger().info("[Geometria] " + geometryDescription(bee, loggedFood));
        }
        tickCount++;

        if (!exchangeInFlight.compareAndSet(false, true)) {
            return;
        }

        double dorsalLight = bee.getLocation().getBlock().getLightFromSky() / 15.0;
        boolean damage = damageTracker.consumeRecentDamage();
        boolean touchContact = touchSensor.consumeContact();
        boolean touchProximity = touchSensor.isNearSomething(bee);
        if (touchContact) {
            plugin.getLogger().info("[TouchSensor] touch_contact = true "
                    + "(deslocamento real bem abaixo do esperado pela velocidade comandada)");
        }
        boolean alarmExplosion = alarmSensor.consumeExplosion();
        boolean alarmHostileMob = alarmSensor.isHostileMobNearby(bee);
        boolean soundMusic = alarmSensor.isMusicNearby(bee);
        if (alarmExplosion) {
            plugin.getLogger().info("[AlarmSensor] alarm_explosion = true (explosão perto da abelha)");
        }
        boolean loomingThreat = loomingSensor.isLoomingThreat();
        // Sensores que varrem blocos/entidades rodam aqui, na thread principal: chamados na thread
        // da ponte, o Paper derruba a troca (AsyncCatcher).
        Vector nearestThreatFleeDirection = loomingSensor.fleeDirectionAwayFromNearestThreat(bee);
        double foodSearchRadius = TasteSensor.FOOD_SEARCH_RADIUS_BLOCKS
                + energyTracker.hungerFactor() * TasteSensor.HUNGRY_SEARCH_RADIUS_EXTRA_BLOCKS;
        seekingFood = energyTracker.isSeekingFood() || satietyGateSuppressedForExperiment;
        TasteSensor.FoodTarget nearestFoodTarget = seekingFood
                ? tasteSensor.findNearestFood(bee, foodSearchRadius)
                : null;
        boolean foodContact = nearestFoodTarget != null;
        foodVisible = foodContact;
        foodTargetLocation = nearestFoodTarget != null ? nearestFoodTarget.location() : null;
        ThermalSensor.Reading thermal = thermalSensor.read(bee);
        heatNear = thermal.heat();
        coldNear = thermal.cold();
        coldSeverity = thermal.coldSeverity();
        tasteTargetHeldByPlayer = nearestFoodTarget != null && nearestFoodTarget.heldByPlayer();
        tasteArrived = nearestFoodTarget != null
                && nearestFoodTarget.location().distance(bee.getLocation()) < MotorMapping.TASTE_ARRIVAL_THRESHOLD_BLOCKS;
        energyTracker.tick(lastAppliedVelocity.length(), tasteArrived);
        long tMs = System.currentTimeMillis();
        Vector currentHeading = heading != null ? heading : bee.getLocation().getDirection();

        if (MotorMapping.isEscapeActive(latestEscapeMotor)) {
            boolean fleeingAlready = escapeLatchTicksLeft > 0;
            escapeLatchTicksLeft = ESCAPE_LATCH_TICKS;
            if (nearestThreatFleeDirection != null) {
                escapeLatchDirection = nearestThreatFleeDirection;
            } else if (!fleeingAlready) {
                escapeLatchDirection = currentHeading;
            }
        } else if (escapeLatchTicksLeft > 0) {
            escapeLatchTicksLeft--;
        }
        boolean escapeLatched = escapeLatchTicksLeft > 0;
        boolean loudSound = (alarmExplosion || alarmHostileMob || soundMusic)
                && latestJohnstonMotor.has("startle")
                && latestJohnstonMotor.get("startle").getAsDouble() > WAKE_STARTLE_THRESHOLD;
        sleepState.tick(bee.getWorld(), loudSound || escapeLatched || damage);
        heatAvoidDirection = (!escapeLatched && thermal.hazardAwayDirection() != null
                && MotorMapping.isThermalActive(latestThermoMotor))
                ? thermal.hazardAwayDirection() : null;

        boolean sendMuteThisTime = muteDirty;
        List<String> muteToSend = sendMuteThisTime ? List.copyOf(mutedGroups) : null;
        boolean sendStimulateThisTime = stimulateDirty;
        BridgeClient.StimulateSpec stimulateToSend = sendStimulateThisTime ? directedStimulus : null;

        boolean touchLesionedNow = touchLesioned;
        boolean damageToSend = (touchLesionedNow || escapeLesioned) ? false : damage;
        boolean touchContactToSend = touchLesionedNow ? false : touchContact;
        boolean touchProximityToSend = touchLesionedNow ? false : touchProximity;
        boolean loomingThreatToSend = escapeLesioned ? false : loomingThreat;
        boolean rainingToSend = hygroLesioned ? false : raining;
        boolean foodContactToSend = tasteLesioned ? false : foodContact;
        boolean thermoHeatToSend = thermoLesioned ? false : thermal.heat();
        boolean thermoColdToSend = thermoLesioned ? false : thermal.cold();
        boolean alarmExplosionToSend = johnstonLesioned ? false : alarmExplosion;
        boolean alarmHostileMobToSend = johnstonLesioned ? false : alarmHostileMob;
        boolean soundMusicToSend = johnstonLesioned ? false : soundMusic;

        bridgeExecutor.submit(() -> {
            try {
                if (bridge == null) {
                    bridge = new BridgeClient(bridgeHost, bridgePort);
                }
                JsonObject response = bridge.sendSensorAndReceiveMotor(
                        light, dorsalLight, damageToSend, touchContactToSend, touchProximityToSend,
                        rainingToSend, alarmExplosionToSend, alarmHostileMobToSend, soundMusicToSend,
                        loomingThreatToSend,
                        foodContactToSend, thermoHeatToSend, thermoColdToSend, tMs, muteToSend, stimulateToSend);
                if (sendMuteThisTime) {
                    muteDirty = false;
                }
                if (sendStimulateThisTime) {
                    stimulateDirty = false;
                }
                Vector newHeading = MotorMapping.rotatedHeading(response, currentHeading);

                JsonObject responseForMotor = response;
                if (bristleSuppressedForExperiment || groomingEpisodeIsDodge) {
                    responseForMotor = response.deepCopy();
                    responseForMotor.remove("bristle_motor");
                }

                boolean touchingGroundOrWater = bee.isOnGround() || bee.isInWater();
                boolean groomingActiveNow =
                        MotorMapping.isGroomingActive(responseForMotor.getAsJsonObject("bristle_motor"));
                boolean shelterActiveNow =
                        MotorMapping.isSeekingShelterActive(responseForMotor.getAsJsonObject("hygro_motor"));
                if (!(groomingActiveNow || shelterActiveNow)) {
                    ticksSinceGroundOrWaterContact = LANDED_GRACE_TICKS;
                    shelterFoundThisEpisode = false;
                    hasTouchedThisEpisode = false;
                } else if (touchingGroundOrWater) {
                    ticksSinceGroundOrWaterContact = 0;
                    hasTouchedThisEpisode = true;
                    if (shelterActiveNow && !shelterFoundThisEpisode
                            && ShelterSensor.hasShelterAbove(bee.getLocation())) {
                        shelterFoundThisEpisode = true;
                        Location loc = bee.getLocation();
                        plugin.getLogger().info(String.format(Locale.ROOT,
                                "[ControlLoop] abrigo encontrado em (%.1f, %.1f, %.1f)",
                                loc.getX(), loc.getY(), loc.getZ()));
                    }
                } else {
                    ticksSinceGroundOrWaterContact++;
                    if (ticksSinceGroundOrWaterContact >= SHELTER_ABANDON_TICKS && shelterFoundThisEpisode) {
                        shelterFoundThisEpisode = false;
                    }
                }
                boolean landed = ticksSinceGroundOrWaterContact < LANDED_GRACE_TICKS;
                boolean sheltered = shelterFoundThisEpisode;

                Vector shelterDirectionHint = (shelterActiveNow && !sheltered)
                        ? ShelterSensor.findNearbyShelterDirection(bee.getLocation(), SHELTER_LOOK_AHEAD_BLOCKS)
                        : null;

                Vector escapeDirectionHint = escapeLatched ? escapeLatchDirection : null;

                Vector tasteDisplacementHint = nearestFoodTarget != null
                        ? nearestFoodTarget.location().toVector().subtract(bee.getLocation().toVector())
                        : null;

                latestVelocity = MotorMapping.toVelocity(
                        responseForMotor, newHeading, landed, sheltered, hasTouchedThisEpisode,
                        escapeLatched,
                        shelterDirectionHint, escapeDirectionHint, tasteDisplacementHint,
                        nearestFoodTarget != null && nearestFoodTarget.heldByPlayer());
                heading = newHeading;
                JsonObject motor = response.getAsJsonObject("motor");
                if (motor != null) {
                    latestMotor = motor;
                }
                if (response.has("active_dn")) {
                    latestActiveDn = response.get("active_dn").getAsInt();
                }
                JsonObject bristleMotor = response.getAsJsonObject("bristle_motor");
                if (bristleMotor != null) {
                    latestBristleMotor = bristleMotor;
                }
                JsonObject hygroMotor = response.getAsJsonObject("hygro_motor");
                if (hygroMotor != null) {
                    latestHygroMotor = hygroMotor;
                }
                JsonObject johnstonMotor = response.getAsJsonObject("johnston_motor");
                if (johnstonMotor != null) {
                    latestJohnstonMotor = johnstonMotor;
                }
                JsonObject escapeMotor = response.getAsJsonObject("escape_motor");
                if (escapeMotor != null) {
                    latestEscapeMotor = escapeMotor;
                }
                JsonObject tasteMotor = response.getAsJsonObject("taste_motor");
                if (tasteMotor != null) {
                    latestTasteMotor = tasteMotor;
                }
                JsonObject thermoMotor = response.getAsJsonObject("thermo_motor");
                if (thermoMotor != null) {
                    latestThermoMotor = thermoMotor;
                }
                exchangeCount++;
            } catch (IOException e) {
                exchangeFailures++;
                plugin.getLogger().log(Level.WARNING,
                        "Troca com o simulador falhou — tentando reconectar na próxima. " + e.getMessage());
                closeQuietly();
            } finally {
                exchangeInFlight.set(false);
            }
        });
    }

    private double fatigueSpeedFraction() {
        double hunger = energyTracker.hungerFactor();
        double hungerFraction = hunger > 0.0
                ? 1.0 - hunger * (1.0 - EnergyTracker.MIN_SPEED_FRACTION)
                : 1.0;
        double cold = coldSeverity;
        double coldFraction = cold > 0.0
                ? 1.0 - cold * (1.0 - ThermalSensor.COLD_LETHARGIC_SPEED_FRACTION)
                : 1.0;
        double drowsyFraction = sleepState.isDrowsy() ? SleepState.DROWSY_SPEED_FRACTION : 1.0;
        return hungerFraction * coldFraction * drowsyFraction;
    }

    private double foodTargetMinWorldY() {
        Location target = foodTargetLocation;
        return target == null ? Double.NEGATIVE_INFINITY : target.getY() + FOOD_TARGET_CLEARANCE_BLOCKS;
    }

    private Vector applyHungerModifiers(Bee bee, Vector velocity, boolean obstacleGrace) {
        double hunger = energyTracker.hungerFactor();
        if (hunger <= 0.0) {
            return velocity;
        }
        double speedFraction = 1.0 - hunger * (1.0 - EnergyTracker.MIN_SPEED_FRACTION);
        double ceiling = obstacleGrace
                ? Double.MAX_VALUE
                : EnergyTracker.STARVING_CEILING_BLOCKS + (1.0 - hunger) * EnergyTracker.FED_CEILING_EXTRA_BLOCKS;
        return limitSpeedAndHeight(bee, velocity, speedFraction, ceiling, foodTargetMinWorldY());
    }

    private Vector applyColdModifiers(Bee bee, Vector velocity, boolean obstacleGrace) {
        double cold = coldSeverity;
        if (cold <= 0.0) {
            return velocity;
        }
        double speedFraction = 1.0 - cold * (1.0 - ThermalSensor.COLD_LETHARGIC_SPEED_FRACTION);
        double ceiling = obstacleGrace
                ? Double.MAX_VALUE
                : ThermalSensor.COLD_LETHARGIC_CEILING_BLOCKS + (1.0 - cold) * ThermalSensor.COLD_WARM_CEILING_EXTRA_BLOCKS;
        return limitSpeedAndHeight(bee, velocity, speedFraction, ceiling, foodTargetMinWorldY());
    }

    private Vector applyForagingSearchModifiers(Bee bee, Vector velocity, boolean obstacleGrace) {
        if (energyTracker.hungerFactor() <= 0.0 || foodVisible || obstacleGrace || !seekingFood) {
            return velocity;
        }
        return limitSpeedAndHeight(bee, velocity, 1.0, EnergyTracker.STARVING_CEILING_BLOCKS);
    }

    private String sleepLabel() {
        if (sleepState.isDrowsy()) {
            return "sonolenta";
        }
        if (sleepState.isWokenAtNight()) {
            return "desperta";
        }
        return sleepState.isNight() ? "-" : "dia";
    }

    // F25, F11, F13
    private static final double GROUND_RAYTRACE_DISTANCE_BLOCKS = 384.0;

    private Vector limitSpeedAndHeight(Bee bee, Vector velocity, double speedFraction, double ceiling) {
        return limitSpeedAndHeight(bee, velocity, speedFraction, ceiling, Double.NEGATIVE_INFINITY);
    }

    private Vector limitSpeedAndHeight(
            Bee bee, Vector velocity, double speedFraction, double ceiling, double minWorldY) {
        Vector adjusted = velocity.clone();
        adjusted.setX(adjusted.getX() * speedFraction);
        adjusted.setZ(adjusted.getZ() * speedFraction);

        Location location = bee.getLocation();
        RayTraceResult ground = bee.getWorld().rayTraceBlocks(
                location, new Vector(0, -1, 0), GROUND_RAYTRACE_DISTANCE_BLOCKS, FluidCollisionMode.NEVER, true);
        if (ground != null) {
            double groundY = ground.getHitPosition().getY();
            double height = location.getY() - groundY;
            ceiling = Math.max(ceiling, minWorldY - groundY);
            if (height > ceiling) {
                adjusted.setY(Math.min(adjusted.getY(), -EnergyTracker.HUNGRY_DESCENT_BLOCKS_PER_TICK));
            } else if (adjusted.getY() > 0) {
                adjusted.setY(Math.min(adjusted.getY(), (ceiling - height) * 0.2));
            }
        }
        return adjusted;
    }

    private String geometryDescription(Bee bee, TasteSensor.FoodTarget food) {
        Location loc = bee.getLocation();
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "pos=%.2f,%.2f,%.2f abaixo=%s", loc.getX(), loc.getY(), loc.getZ(),
                loc.clone().subtract(0, 0.1, 0).getBlock().getType()));
        Vector horizontal = new Vector(lastAppliedVelocity.getX(), 0, lastAppliedVelocity.getZ());
        if (horizontal.lengthSquared() > 1.0E-10) {
            Location ahead = loc.clone().add(horizontal.normalize().multiply(0.6));
            sb.append(" frente=").append(ahead.getBlock().getType())
                    .append(" frente+1=").append(ahead.clone().add(0, 1, 0).getBlock().getType());
        } else {
            sb.append(" frente=- (parada)");
        }
        if (food != null) {
            Location target = food.location();
            sb.append(String.format(Locale.ROOT, " alvo=%.2f,%.2f,%.2f dy=%.2f",
                    target.getX(), target.getY(), target.getZ(), target.getY() - loc.getY()));
            sb.append(" noAlvo=").append(target.getBlock().getType())
                    .append(" sobAlvo=").append(target.clone().subtract(0, 0.5, 0).getBlock().getType());
        }
        return sb.toString();
    }

    private static Vector horizontalOpposite(Vector direction) {
        Vector horizontal = new Vector(direction.getX(), 0, direction.getZ());
        if (horizontal.lengthSquared() < 1.0E-6) {
            return new Vector(0, 0, 0);
        }
        return horizontal.normalize().multiply(-1);
    }

    private static Vector randomHorizontalDirection() {
        double angle = STUCK_RECOVERY_RNG.nextDouble() * 2 * Math.PI;
        return new Vector(Math.cos(angle), 0, Math.sin(angle));
    }

    private void closeQuietly() {
        if (bridge != null) {
            try {
                bridge.close();
            } catch (IOException ignored) {
            }
            bridge = null;
        }
    }

    private Bee findMarkedBee() {
        for (var world : plugin.getServer().getWorlds()) {
            var found = world.getEntitiesByClass(Bee.class).stream()
                    .filter(marker::isMarked)
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
        }
        return null;
    }
}
