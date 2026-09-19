package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Locale;

/**
 * Painel "Flywire Bee Live" (F6, pedido do usuário 17/09/2026) — sidebar do
 * Bukkit, mostra canais reais do vetor motor ({@code motor.py::decode()}) em
 * tempo real pra todos os jogadores online enquanto {@code control start}
 * estiver rodando.
 *
 * <p><b>Só mostra canal que existe de verdade no dado</b> — nada de rótulo
 * inventado (ver `CONVENCOES.md`). Por decisão do usuário: `phototaxis`
 * (validado, F4, p=0,0014), `yaw_steering` (em validação, RN-08/AD-16) e
 * `active_dn` (quantos descendentes dispararam na janela).
 *
 * <p><b>Aparece no canto SUPERIOR DIREITO da tela</b> — é onde o Minecraft
 * renderiza a sidebar do scoreboard nativamente; não existe slot de canto
 * superior esquerdo sem resource pack customizado.
 *
 * <p>Truque de "entry invisível + prefixo de time": a identidade de cada
 * linha (só usada pra ordenar, nunca aparece) é uma string de código de cor
 * sem texto visível ({@code §0}, {@code §1}, ...), registrada uma vez com
 * score fixo. O TEXTO visível vem do prefixo do {@link Team} correspondente,
 * atualizável a cada chamada sem remover/recriar a linha — evita flicker.
 * Os números do placar em si (score) ficam visíveis ao lado — limitação
 * conhecida da API de scoreboard vanilla, não escondida aqui.
 */
final class LiveHud {

    private static final String OBJECTIVE_NAME = "flywirebeelive";
    private static final String[] LINE_ENTRIES = {"§0", "§1", "§2"};

    private static Scoreboard board;
    private static Objective objective;

    private LiveHud() {
    }

    /** Roda na thread principal (chamado de {@code ControlLoop::onTick}). */
    static void update(Plugin plugin, JsonObject motor, int activeDn) {
        ensureBoard();

        setLine(0, "phototaxis", formatChannel(motor, "phototaxis"));
        setLine(1, "yaw_steering", formatChannel(motor, "yaw_steering"));
        setLine(2, "active_dn", String.valueOf(activeDn));

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getScoreboard() != board) {
                player.setScoreboard(board);
            }
        }
    }

    /** Tira o painel de todo mundo e devolve o scoreboard principal — chamar em {@code ControlLoop::stop}. */
    static void clear(Plugin plugin) {
        if (board == null) {
            return;
        }
        Scoreboard main = Bukkit.getServer().getScoreboardManager().getMainScoreboard();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getScoreboard() == board) {
                player.setScoreboard(main);
            }
        }
    }

    private static String formatChannel(JsonObject motor, String channel) {
        if (motor == null || !motor.has(channel)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%+.3f", motor.get(channel).getAsDouble());
    }

    private static void ensureBoard() {
        if (board != null) {
            return;
        }
        board = Bukkit.getServer().getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, OBJECTIVE_NAME);
        objective.setDisplayName("Flywire Bee Live");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = LINE_ENTRIES.length;
        for (int i = 0; i < LINE_ENTRIES.length; i++) {
            Team team = board.registerNewTeam("hud" + i);
            team.addEntry(LINE_ENTRIES[i]);
            objective.getScore(LINE_ENTRIES[i]).setScore(score--);
        }
    }

    private static void setLine(int index, String label, String value) {
        Team team = board.getEntryTeam(LINE_ENTRIES[index]);
        if (team != null) {
            team.setPrefix(label + ": " + value + " ");
        }
    }
}
