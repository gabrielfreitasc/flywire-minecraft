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
 * L6 — encarnação. Comandos ({@code /flywirebee ...}):
 *
 * <ul>
 *   <li>{@code give} / {@code kill} — spawna/marca ou remove a abelha do
 *       conectoma (ver {@link FlywireBeeMarker}, {@link SpawnItem});</li>
 *   <li>{@code spike <modo>} — spikes técnicos isolados de movimento
 *       (ver {@link VelocityProbe});</li>
 *   <li>{@code control start|stop} — liga/desliga o loop de controle real,
 *       sensor→ponte→motor→velocidade a 20 Hz (ver {@link ControlLoop});</li>
 *   <li>{@code lesion [trials] [segundos]} — experimento de lesão, critério
 *       de saída da F4 (ver {@link LesionExperiment});</li>
 *   <li>{@code daynight [trials] [segundos]} — experimento dia/noite, F6
 *       (ver {@link DayNightExperiment}). Exige abelha ao ar livre —
 *       {@code light} (não {@code dorsal_light}) é quem varia com a hora do
 *       mundo, ver {@code CONVENCOES.md};</li>
 *   <li>{@code visualize <on|off>} — partículas de atividade por grupo,
 *       critério de saída da F5 (ver {@link ActivityVisualizer});</li>
 *   <li>{@code mute <grupo>} / {@code unmute <grupo|all>} — ferramenta de
 *       lesão por comando da F5: silencia de verdade a saída sináptica do
 *       grupo no simulador (diferente do mecanismo de {@code lesion}, que só
 *       zera o sensor de luz — ver {@code server.py});</li>
 *   <li>{@code stimulate <grupo> <amplitude>} / {@code stimulate stop} —
 *       estimulação dirigida da F5: injeta corrente extra num grupo, soma
 *       com o estímulo de luz (não substitui).</li>
 *   <li>{@code goals off} — F6, hipótese do usuário pro nulo do dia/noite:
 *       remove objetivos de IA nativa que competem com locomoção (vagar,
 *       flor, colmeia) via Mob Goal API do Paper, sem tocar em {@code setAI}
 *       (ver {@link CompetingGoals}). Sem volta pela API pública.</li>
 * </ul>
 *
 * <p>Regra dura (ver plugin/README.md e CONVENCOES.md): o plugin NUNCA altera
 * a simulação para "fazer o comportamento aparecer". Se nada emerge, o
 * problema é da hipótese ou do modelo, não do mob.
 */
public final class FlywireBeePlugin extends JavaPlugin {

    /** Nomes válidos tanto para {@code mute}/{@code unmute} quanto para {@code stimulate}. */
    private static final List<String> VALID_GROUPS = List.of(
            "sensory", "DNp", "DNpe", "DNg", "DNge", "DNb", "DNbe", "DNa", "DNae",
            // RN-08/AD-14+AD-15 — categorias de comportamento publicado (Namiki et al.
            // 2018 + BANC connectome). Ver docs/04-regras-de-negocio.md.
            "fast_locomotion", "broad_locomotion", "wing_abdomen_movements", "steering",
            "escape_takeoff", "landing", "flight", "walking", "ocellar", "neuromodulatory"
    );

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
            case "daynight" -> handleDayNight(player, args);
            case "visualize" -> handleVisualize(player, args);
            case "mute" -> handleMute(player, args);
            case "unmute" -> handleUnmute(player, args);
            case "stimulate" -> handleStimulate(player, args);
            case "goals" -> handleGoals(player, args);
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
            case "no-competing-goals" -> probe.runVelocityNoCompetingGoals(bee.get());
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

    private void handleDayNight(Player player, String[] args) {
        Optional<Bee> bee = player.getWorld().getEntitiesByClass(Bee.class).stream()
                .filter(marker::isMarked)
                .findFirst();
        if (bee.isEmpty()) {
            player.sendMessage("Nenhuma abelha do FlyWire encontrada neste mundo. Use /flywirebee give primeiro.");
            return;
        }
        int trials = 20;
        int secondsPerTrial = 10;
        boolean blind = false;
        try {
            if (args.length >= 2) {
                trials = Integer.parseInt(args[1]);
            }
            if (args.length >= 3) {
                secondsPerTrial = Integer.parseInt(args[2]);
            }
            if (args.length >= 4) {
                if (!args[3].equalsIgnoreCase("blind")) {
                    throw new NumberFormatException(args[3]);
                }
                blind = true;
            }
        } catch (NumberFormatException e) {
            player.sendMessage("Uso: /flywirebee daynight [trials=20] [segundosPorTrial=10] [blind]");
            return;
        }
        new DayNightExperiment(this, controlLoop).run(bee.get(), trials, secondsPerTrial, blind, player);
    }

    private void handleVisualize(Player player, String[] args) {
        if (args.length < 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
            player.sendMessage("Uso: /flywirebee visualize <on|off>");
            return;
        }
        boolean on = args[1].equalsIgnoreCase("on");
        controlLoop.setVisualize(on);
        player.sendMessage("Visualização de atividade " + (on ? "ligada" : "desligada") + ".");
    }

    private void handleMute(Player player, String[] args) {
        if (args.length < 2 || !VALID_GROUPS.contains(args[1])) {
            player.sendMessage("Uso: /flywirebee mute <" + String.join("|", VALID_GROUPS) + ">");
            return;
        }
        controlLoop.mute(args[1]);
        player.sendMessage("Grupo '" + args[1] + "' silenciado (leva até ~50ms pra fazer efeito "
                + "visível — janela deslizante do motor). Mutados agora: " + controlLoop.getMutedGroups());
    }

    private void handleUnmute(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("Uso: /flywirebee unmute <grupo|all>");
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            controlLoop.unmuteAll();
            player.sendMessage("Todos os grupos desmutados.");
            return;
        }
        controlLoop.unmute(args[1]);
        player.sendMessage("Grupo '" + args[1] + "' desmutado. Mutados agora: " + controlLoop.getMutedGroups());
    }

    private void handleStimulate(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            controlLoop.stopStimulating();
            player.sendMessage("Estimulação dirigida parada.");
            return;
        }
        if (args.length < 3 || !VALID_GROUPS.contains(args[1])) {
            player.sendMessage("Uso: /flywirebee stimulate <" + String.join("|", VALID_GROUPS)
                    + "> <amplitude> | stimulate stop");
            return;
        }
        double amplitude;
        try {
            amplitude = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("Amplitude inválida: " + args[2]);
            return;
        }
        controlLoop.stimulate(args[1], amplitude);
        player.sendMessage("Estimulando '" + args[1] + "' com amplitude " + amplitude
                + " (soma com o estímulo de luz — não substitui).");
    }

    private void handleGoals(Player player, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("off")) {
            player.sendMessage("Uso: /flywirebee goals off — remove os objetivos de IA nativa que "
                    + "competem com locomoção (vagar, ir pra flor/colmeia). Sem volta: abelha nova "
                    + "(kill + give) tem os goals default de novo. Ver CompetingGoals.java.");
            return;
        }
        Optional<Bee> bee = player.getWorld().getEntitiesByClass(Bee.class).stream()
                .filter(marker::isMarked)
                .findFirst();
        if (bee.isEmpty()) {
            player.sendMessage("Nenhuma abelha do FlyWire encontrada neste mundo. Use /flywirebee give primeiro.");
            return;
        }
        CompetingGoals.disable(bee.get());
        player.sendMessage("Objetivos de IA que competem com locomoção removidos (vagar, ir pra "
                + "flor/polinizar, ir/localizar/entrar na colmeia). Testado isolado via 'spike "
                + "no-competing-goals' — não congela a física, mas ainda não validado se muda o "
                + "resultado do experimento dia/noite.");
    }

    private String usage() {
        return "Uso: /flywirebee give | kill | spike <modo> | control <start|stop> | "
                + "lesion [trials] [segundos] | daynight [trials] [segundos] [blind] | visualize <on|off> | "
                + "mute <grupo> | unmute <grupo|all> | stimulate <grupo> <amplitude> | goals off";
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
