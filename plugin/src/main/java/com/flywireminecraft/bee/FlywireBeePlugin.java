package com.flywireminecraft.bee;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

/**
 * L6 — encarnação. Liga/desliga o plugin, spawna/marca a abelha do conectoma
 * ({@code /flywirebee give}), roda spikes técnicos de movimento
 * ({@code /flywirebee spike}), e liga/desliga o loop de controle real
 * ({@code /flywirebee control start|stop}) — ver {@link ControlLoop}.
 *
 * <p>Regra dura (ver plugin/README.md e CONVENCOES.md): o plugin NUNCA altera
 * a simulação para "fazer o comportamento aparecer". Se nada emerge, o
 * problema é da hipótese ou do modelo, não do mob.
 */
public final class FlywireBeePlugin extends JavaPlugin {

    private BridgeClient startupCheckBridge;
    private SpawnItem spawnItem;
    private FlywireBeeMarker marker;
    private ControlLoop controlLoop;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String host = getConfig().getString("bridge.host", "localhost");
        int port = getConfig().getInt("bridge.port", 8765);

        try {
            startupCheckBridge = new BridgeClient(host, port);
            getLogger().info("Conectado ao simulador em " + host + ":" + port);
        } catch (IOException e) {
            getLogger().log(Level.WARNING,
                    "Não foi possível conectar ao simulador ainda — normal se o container "
                            + "sim não estiver rodando. O loop de controle reconecta sozinho.", e);
        }

        spawnItem = new SpawnItem(this);
        marker = new FlywireBeeMarker(this);
        getServer().getPluginManager().registerEvents(new BeeSpawnListener(spawnItem, marker), this);

        DamageTracker damageTracker = new DamageTracker();
        getServer().getPluginManager().registerEvents(damageTracker, this);
        controlLoop = new ControlLoop(this, marker, damageTracker, host, port);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("flywirebee")) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Só um jogador pode usar esse comando.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(usage());
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give" -> {
                player.getInventory().addItem(spawnItem.create());
                player.sendMessage("Você recebeu o spawner da abelha do FlyWire.");
            }
            case "kill" -> {
                List<Bee> marked = player.getWorld().getEntitiesByClass(Bee.class).stream()
                        .filter(marker::isMarked)
                        .toList();
                marked.forEach(Bee::remove);
                player.sendMessage(!marked.isEmpty()
                        ? "Removida(s) " + marked.size() + " abelha(s) do FlyWire. Pode spawnar outra."
                        : "Nenhuma abelha do FlyWire encontrada neste mundo.");
            }
            case "spike" -> handleSpike(player, args);
            case "control" -> handleControl(player, args);
            case "lesion" -> handleLesion(player, args);
            default -> sender.sendMessage(usage());
        }
        return true;
    }

    private void handleSpike(Player player, String[] args) {
        Optional<Bee> bee = player.getWorld().getEntitiesByClass(Bee.class).stream()
                .filter(marker::isMarked)
                .findFirst();
        if (bee.isEmpty()) {
            player.sendMessage("Nenhuma abelha do FlyWire encontrada neste mundo. Use /flywirebee give primeiro.");
            return;
        }
        String mode = args.length >= 2 ? args[1].toLowerCase() : "velocity-noai";
        VelocityProbe probe = new VelocityProbe(this);
        switch (mode) {
            case "velocity-noai" -> probe.runVelocityNoAI(bee.get());
            case "velocity-ai" -> probe.runVelocityWithAI(bee.get());
            case "pathfinder" -> probe.runPathfinder(bee.get());
            case "teleport" -> probe.runTeleport(bee.get());
            default -> {
                player.sendMessage("Modo desconhecido: " + mode);
                return;
            }
        }
        player.sendMessage("Rodando spike '" + mode + "' por 5s — acompanhe o console do servidor.");
    }

    private void handleControl(Player player, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "start" -> {
                if (controlLoop.isRunning()) {
                    player.sendMessage("Loop de controle já está rodando.");
                    return;
                }
                controlLoop.start();
                player.sendMessage("Loop de controle iniciado — sensor->ponte->motor a 20 Hz.");
            }
            case "stop" -> {
                controlLoop.stop();
                player.sendMessage("Loop de controle parado.");
            }
            default -> player.sendMessage("Uso: /flywirebee control <start|stop>");
        }
    }

    private void handleLesion(Player player, String[] args) {
        Optional<Bee> bee = player.getWorld().getEntitiesByClass(Bee.class).stream()
                .filter(marker::isMarked)
                .findFirst();
        if (bee.isEmpty()) {
            player.sendMessage("Nenhuma abelha do FlyWire encontrada neste mundo. Use /flywirebee give primeiro.");
            return;
        }
        int trials = 20;
        int secondsPerTrial = 10;
        try {
            if (args.length >= 2) {
                trials = Integer.parseInt(args[1]);
            }
            if (args.length >= 3) {
                secondsPerTrial = Integer.parseInt(args[2]);
            }
        } catch (NumberFormatException e) {
            player.sendMessage("Uso: /flywirebee lesion [trials=20] [segundosPorTrial=10]");
            return;
        }
        new LesionExperiment(this, controlLoop).run(bee.get(), trials, secondsPerTrial, player);
    }

    private String usage() {
        return "Uso: /flywirebee give | kill | spike <modo> | control <start|stop> | lesion [trials] [segundos]";
    }

    @Override
    public void onDisable() {
        if (controlLoop != null) {
            controlLoop.stop();
        }
        if (startupCheckBridge != null) {
            try {
                startupCheckBridge.close();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Erro ao fechar conexão com o simulador", e);
            }
        }
    }
}
