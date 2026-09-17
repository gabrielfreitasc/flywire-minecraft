package com.flywireminecraft.bee;

import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import org.bukkit.Bukkit;
import org.bukkit.entity.Bee;

/**
 * Remove os objetivos de IA nativa da abelha que competem com o controle por
 * velocidade — hipótese do usuário (16/09/2026) pro resultado nulo do
 * experimento dia/noite (F6): a IA nativa estaria mascarando um efeito real.
 *
 * <p>{@code setAI(false)} já foi testado e descartado na F4 — congela a
 * física inteira, não só a decisão ({@link VelocityProbe#runVelocityNoAI}).
 * A Mob Goal API do Paper ({@link MobGoals}) remove objetivos específicos
 * sem tocar em {@code setAI}. Testado isolado via
 * {@code /flywirebee spike no-competing-goals}: 28,84 de 30 blocos em 5s
 * (96%, igual ao modo com IA ligada da F4) — a física não trava.
 *
 * <p>Remove só o que compete com locomoção: vagar aleatório, ir atrás de
 * flor/polinizar (o "vazamento" já visto na F5), ir/localizar/entrar na
 * colmeia (candidato mais forte pro confundidor dia/noite — vanilla bee
 * tenta voltar pra colmeia à noite). Mantém ataque/fúria/dor/crescer plantação
 * — não competem com locomoção no nosso cenário.
 *
 * <p>**Sem volta pela API pública** — não existe forma de re-registrar a
 * implementação vanilla original a partir do {@code GoalKey}. Abelha nova
 * ({@code /flywirebee kill} + {@code give}) nasce com os goals default.
 */
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
