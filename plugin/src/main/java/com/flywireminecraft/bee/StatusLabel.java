package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Bee;
import org.bukkit.entity.EntityType;

final class StatusLabel {
    private static final double HEIGHT_OFFSET_BLOCKS = 1.0;
    private static final double STARTLE_LABEL_THRESHOLD = 0.5;
    private static final double LOW_ENERGY_LABEL_THRESHOLD = 0.2;
    // F21
    private static final double COLD_LETHARGIC_LABEL_THRESHOLD = 0.6;

    // F11
    private static final double HUNGER_BAR_OFFSET_BLOCKS = -0.6;
    private static final int HUNGER_ICONS = 5;
    private static final String HUNGER_ICON_FULL = "●";
    private static final String HUNGER_ICON_EMPTY = "○";

    private ArmorStand stand;
    private ArmorStand hungerStand;

    /** Roda na thread principal (chamado de ControlLoop::onTick). */
    void update(
            Bee bee, JsonObject bristleMotor, JsonObject hygroMotor, JsonObject johnstonMotor,
            JsonObject tasteMotor, boolean groomingEpisodeIsDodge, boolean sheltered, boolean escapeActive,
            boolean tasteFollowingPlayer, double energyLevel,
            JsonObject thermoMotor, boolean heatNear, boolean coldNear, boolean heatAvoiding,
            boolean drowsy, boolean wokenAtNight, double coldSeverity
    ) {
        if (stand == null || !stand.isValid()) {
            stand = spawn(bee, HEIGHT_OFFSET_BLOCKS);
        }
        stand.teleport(bee.getLocation().add(0, HEIGHT_OFFSET_BLOCKS, 0));
        stand.setCustomName(pickMessage(bristleMotor, hygroMotor, johnstonMotor, tasteMotor,
                thermoMotor, heatNear, coldNear, heatAvoiding, drowsy, wokenAtNight,
                groomingEpisodeIsDodge, sheltered, escapeActive, tasteFollowingPlayer, energyLevel, coldSeverity));

        if (hungerStand == null || !hungerStand.isValid()) {
            hungerStand = spawn(bee, HUNGER_BAR_OFFSET_BLOCKS);
        }
        hungerStand.teleport(bee.getLocation().add(0, HUNGER_BAR_OFFSET_BLOCKS, 0));
        hungerStand.setCustomName(hungerBar(energyLevel));
    }

    /** Chamar em ControlLoop::stop — não deixa os marcadores sobrando no mundo. */
    void remove() {
        if (stand != null) {
            stand.remove();
            stand = null;
        }
        if (hungerStand != null) {
            hungerStand.remove();
            hungerStand = null;
        }
    }

    private String hungerBar(double energyLevel) {
        int filled = (int) Math.ceil(Math.max(0.0, Math.min(1.0, energyLevel)) * HUNGER_ICONS);
        ChatColor fullColor = filled <= 1 ? ChatColor.RED : ChatColor.GOLD;
        StringBuilder bar = new StringBuilder();
        bar.append(fullColor);
        bar.append(HUNGER_ICON_FULL.repeat(filled));
        bar.append(ChatColor.DARK_GRAY);
        bar.append(HUNGER_ICON_EMPTY.repeat(HUNGER_ICONS - filled));
        return bar.toString();
    }

    private ArmorStand spawn(Bee bee, double yOffset) {
        Location loc = bee.getLocation().add(0, yOffset, 0);
        ArmorStand created = (ArmorStand) bee.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        created.setInvisible(true);
        created.setMarker(true);
        created.setGravity(false);
        created.setSmall(true);
        created.setCustomNameVisible(true);
        created.setPersistent(false);
        return created;
    }

    private String pickMessage(
            JsonObject bristleMotor, JsonObject hygroMotor, JsonObject johnstonMotor, JsonObject tasteMotor,
            JsonObject thermoMotor, boolean heatNear, boolean coldNear, boolean heatAvoiding,
            boolean drowsy, boolean wokenAtNight, boolean groomingEpisodeIsDodge, boolean sheltered, boolean escapeActive, boolean tasteFollowingPlayer,
            double energyLevel, double coldSeverity
    ) {
        if (escapeActive) {
            return ChatColor.DARK_PURPLE + "Medo — fugindo!";
        }
        if (heatAvoiding) {
            return ChatColor.GOLD + "Muito calor — fugindo do perigo!";
        }
        if (MotorMapping.isSeekingShelterActive(hygroMotor)) {
            String shelterPart = sheltered ? "Abrigada da chuva" : "Não gosta de chuva — buscando abrigo";
            if (coldSeverity > COLD_LETHARGIC_LABEL_THRESHOLD) {
                return ChatColor.DARK_AQUA + shelterPart + ChatColor.AQUA + " — letárgica de frio";
            }
            if (coldNear) {
                return ChatColor.DARK_AQUA + shelterPart + ChatColor.AQUA + " — sente frio";
            }
            return ChatColor.DARK_AQUA + shelterPart;
        }
        boolean tasteActive = MotorMapping.isTasteSeekingActive(tasteMotor);
        if (tasteActive && tasteFollowingPlayer) {
            return ChatColor.LIGHT_PURPLE + "Com fome — seguindo a comida";
        }
        if (MotorMapping.isGroomingActive(bristleMotor)) {
            return groomingEpisodeIsDodge
                    ? ChatColor.GOLD + "Incomodada — desviando"
                    : ChatColor.GOLD + "Incomodada — parando pra se limpar";
        }
        if (tasteActive) {
            return ChatColor.LIGHT_PURPLE + "Com fome — indo comer";
        }
        if (drowsy) {
            return ChatColor.DARK_BLUE + "Sonolenta — quase dormindo";
        }
        if (coldSeverity > COLD_LETHARGIC_LABEL_THRESHOLD) {
            return ChatColor.AQUA + "Letárgica de frio";
        }
        if (johnstonMotor != null && johnstonMotor.has("startle")
                && johnstonMotor.get("startle").getAsDouble() > STARTLE_LABEL_THRESHOLD) {
            return ChatColor.RED + "Percebendo som/vibração por perto";
        }
        if (MotorMapping.isThermalActive(thermoMotor)) {
            if (heatNear) {
                return ChatColor.GOLD + "Sente muito calor";
            }
            if (coldNear) {
                return ChatColor.AQUA + "Sente frio";
            }
        }
        if (energyLevel < LOW_ENERGY_LABEL_THRESHOLD) {
            return ChatColor.DARK_GRAY + "Faminta!";
        }
        if (wokenAtNight) {
            return ChatColor.BLUE + "Acordada — atenta, sem sono";
        }
        return ChatColor.YELLOW + "Buscando luz";
    }
}
