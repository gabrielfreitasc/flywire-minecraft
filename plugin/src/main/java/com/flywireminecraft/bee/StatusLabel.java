package com.flywireminecraft.bee;

import com.google.gson.JsonObject;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Bee;
import org.bukkit.entity.EntityType;

/**
 * F9 (25/09/2026, pedido do usuário) — "balão de texto" acima da abelha:
 * uma {@link ArmorStand} invisível, marcador (sem física/colisão), com
 * nametag sempre visível, seguindo a abelha e mostrando em português qual
 * circuito REAL está no controle agora — não é flavor text inventado, é a
 * tradução direta da mesma prioridade que {@link MotorMapping#toVelocity}
 * usa (escape > hygro > grooming > taste > phototaxis — EXCETO quando o
 * alvo do taste é um jogador segurando comida, aí taste vence grooming,
 * ver bug real de 26/09/2026 na docstring de {@code pickMessage}). O nome técnico do canal (ex.:
 * "grooming", "hygrotaxis") NÃO aparece na mensagem (pedido do usuário,
 * 25/09/2026 — só o texto em português, sem parênteses) — quem quiser
 * conferir o canal real usa o HUD ("Flywire Bee Live") ou o log.
 *
 * <p><b>Cor de cada mensagem casa com a cor da partícula do circuito em
 * {@link ActivityVisualizer#CIRCUITS}</b> (roxo=escape, azul-petróleo=hygro,
 * dourado≈marrom de grooming, vermelho=johnston, amarelo=ocelar) — mesma
 * composição visual, só que em texto.
 *
 * <p><b>`startle` (johnston) é mostrado como informativo, NÃO como
 * controle</b> — ao contrário dos outros três, o canal ainda não move a
 * abelha de verdade (telemetria pura, ver `johnston_motor.py`). Mensagem
 * usa verbo mais fraco ("percebendo", não "fugindo") pra não sugerir um
 * efeito que não existe. {@code STARTLE_LABEL_THRESHOLD} é só cosmético —
 * errar aqui não corrompe nenhum resultado científico, diferente dos
 * limiares em `MotorMapping`.
 *
 * <p><b>Fuga usa a trava temporal do `ControlLoop`</b>
 * ({@code ESCAPE_LATCH_TICKS}, ~2,5s), não o valor cru de {@code escape_drive}
 * — pedido do usuário (25/09/2026): o sinal pode cair rápido demais pra dar
 * pra ler "Medo — fugindo!" no balão antes de sumir.
 *
 * <p><b>"Faminta!" (F11, 26/09/2026) é informativo, não vem de canal
 * nenhum do simulador</b> — usa {@link EnergyTracker} (proxy de
 * engenharia, ver docstring de lá: neurônios de fome/saciedade sinalizam
 * por hormônio, sem saída sináptica no conectoma). Prioridade BAIXA (só
 * abaixo do texto padrão) — energia baixa não é uma decisão de
 * comportamento ainda, só um aviso.
 */
final class StatusLabel {

    private static final double HEIGHT_OFFSET_BLOCKS = 1.0;
    private static final double STARTLE_LABEL_THRESHOLD = 0.5; // só cosmético, ver docstring
    private static final double LOW_ENERGY_LABEL_THRESHOLD = 0.2; // só cosmético, ver docstring

    // F11 (26/09/2026, pedido do usuário) — barra de fome ABAIXO da abelha:
    // 5 ícones que vão esvaziando conforme o percentual do EnergyTracker.
    // Só caracteres do plano básico do Unicode (●/○) — emoji fora do BMP
    // (ex.: drumstick) não renderiza na fonte padrão do Minecraft.
    // Deslocamento Y é estimativa visual (nameplate de marker stand
    // renderiza um pouco acima da posição do stand) — ajustar se ficar
    // colado/longe demais.
    private static final double HUNGER_BAR_OFFSET_BLOCKS = -0.6;
    private static final int HUNGER_ICONS = 5;
    private static final String HUNGER_ICON_FULL = "●";
    private static final String HUNGER_ICON_EMPTY = "○";

    private ArmorStand stand;
    private ArmorStand hungerStand;

    /** Roda na thread principal (chamado de {@code ControlLoop::onTick}). */
    void update(
            Bee bee, JsonObject bristleMotor, JsonObject hygroMotor, JsonObject johnstonMotor,
            JsonObject tasteMotor, boolean groomingEpisodeIsDodge, boolean sheltered, boolean escapeActive,
            boolean tasteFollowingPlayer, double energyLevel
    ) {
        if (stand == null || !stand.isValid()) {
            stand = spawn(bee, HEIGHT_OFFSET_BLOCKS);
        }
        stand.teleport(bee.getLocation().add(0, HEIGHT_OFFSET_BLOCKS, 0));
        stand.setCustomName(pickMessage(bristleMotor, hygroMotor, johnstonMotor, tasteMotor,
                groomingEpisodeIsDodge, sheltered, escapeActive, tasteFollowingPlayer, energyLevel));

        if (hungerStand == null || !hungerStand.isValid()) {
            hungerStand = spawn(bee, HUNGER_BAR_OFFSET_BLOCKS);
        }
        hungerStand.teleport(bee.getLocation().add(0, HUNGER_BAR_OFFSET_BLOCKS, 0));
        hungerStand.setCustomName(hungerBar(energyLevel));
    }

    /** Chamar em {@code ControlLoop::stop} — não deixa os marcadores sobrando no mundo. */
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

    /**
     * 5 ícones, cheios conforme {@code ceil(energia * 5)} — qualquer
     * energia acima de zero mostra pelo menos um ícone cheio, só 0% zera a
     * barra. Cheios em dourado, vazios em cinza escuro (vira vermelho
     * quando só sobra 1 ícone, mesmo limiar de aviso do "Faminta!").
     */
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
        created.setMarker(true); // sem hitbox/colisão/física
        created.setGravity(false);
        created.setSmall(true);
        created.setCustomNameVisible(true);
        created.setPersistent(false); // não sobrevive a restart do servidor — recriado no próximo start()
        return created;
    }

    private String pickMessage(
            JsonObject bristleMotor, JsonObject hygroMotor, JsonObject johnstonMotor, JsonObject tasteMotor,
            boolean groomingEpisodeIsDodge, boolean sheltered, boolean escapeActive, boolean tasteFollowingPlayer,
            double energyLevel
    ) {
        // F9/AD-20 — mesma ordem de prioridade de MotorMapping.toVelocity:
        // o texto tem que bater com o que está REALMENTE no controle.
        // `escapeActive` já vem com a trava temporal aplicada (ver
        // ControlLoop.ESCAPE_LATCH_TICKS) — não lê escape_motor cru, senão
        // o balão piscaria "fugindo" por menos de uma troca.
        if (escapeActive) {
            return ChatColor.DARK_PURPLE + "Medo — fugindo!";
        }
        if (MotorMapping.isSeekingShelterActive(hygroMotor)) {
            return sheltered
                    ? ChatColor.DARK_AQUA + "Abrigada da chuva"
                    : ChatColor.DARK_AQUA + "Não gosta de chuva — buscando abrigo";
        }
        boolean tasteActive = MotorMapping.isTasteSeekingActive(tasteMotor);
        if (tasteActive && tasteFollowingPlayer) {
            // F10 — bug real, achado do usuário (26/09/2026): o balão
            // ainda checava grooming ANTES de taste incondicionalmente,
            // sem saber que MotorMapping.toVelocity já inverte essa
            // ordem quando o alvo é o jogador segurando comida (mesmo
            // conflito inerente de touch_proximity, ver docstring de lá)
            // — o texto mostrava "Incomodada" enquanto ela na verdade
            // seguia a comida. Movido pra ANTES de grooming, mesma
            // ordem exata de MotorMapping.
            return ChatColor.LIGHT_PURPLE + "Com fome — seguindo a comida";
        }
        if (MotorMapping.isGroomingActive(bristleMotor)) {
            return groomingEpisodeIsDodge
                    ? ChatColor.GOLD + "Incomodada — desviando"
                    : ChatColor.GOLD + "Incomodada — parando pra se limpar";
        }
        if (tasteActive) {
            // Caso bloco/item parado — grooming já teve prioridade acima,
            // mesma ordem de MotorMapping.
            return ChatColor.LIGHT_PURPLE + "Com fome — indo comer";
        }
        if (johnstonMotor != null && johnstonMotor.has("startle")
                && johnstonMotor.get("startle").getAsDouble() > STARTLE_LABEL_THRESHOLD) {
            // Informativo só — startle ainda não controla o movimento
            // (telemetria pura), verbo mais fraco de propósito.
            return ChatColor.RED + "Percebendo som/vibração por perto";
        }
        if (energyLevel < LOW_ENERGY_LABEL_THRESHOLD) {
            // F11 — informativo só, prioridade mais baixa de todas (ver
            // docstring da classe) — energia baixa ainda não muda
            // comportamento de voo, só avisa.
            return ChatColor.DARK_GRAY + "Faminta!";
        }
        return ChatColor.YELLOW + "Buscando luz";
    }
}
