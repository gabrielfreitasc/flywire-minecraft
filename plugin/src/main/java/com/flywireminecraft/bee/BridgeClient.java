package com.flywireminecraft.bee;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Cliente TCP da ponte com o simulador (L5). Protocolo JSON-lines — ver
 * docs/02-arquitetura.md e sim/src/flywire_sim/server.py.
 *
 * <p>O simulador é servidor; este cliente nunca bloqueia o tick do jogo
 * esperando o simulador terminar passos extras (RN-06). Cada chamada a
 * {@link #sendSensorAndReceiveMotor} é uma troca síncrona rápida — o
 * simulador responde com o ÚLTIMO vetor motor já computado pela thread de
 * simulação, não um calculado na hora.
 */
public final class BridgeClient implements AutoCloseable {

    /** F5 — estimulação dirigida: grupo nomeado + amplitude. group=null limpa o estímulo. */
    public record StimulateSpec(String group, double amplitude) {
    }

    private final Gson gson = new Gson();
    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;

    public BridgeClient(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(socket.getOutputStream(), false, StandardCharsets.UTF_8);
    }

    /** Sem alterar mute/stimulate atuais — ver sobrecarga completa. */
    public JsonObject sendSensorAndReceiveMotor(
            double light, double dorsalLight, boolean damage,
            boolean touchContact, boolean touchProximity, long tMs
    ) throws IOException {
        return sendSensorAndReceiveMotor(
                light, dorsalLight, damage, touchContact, touchProximity, tMs, null, null);
    }

    /**
     * Envia sensores e, opcionalmente, muda o estado de silenciamento
     * ({@code mute}, F5 — ferramenta de lesão por comando) e/ou de
     * estimulação dirigida ({@code stimulate}, F5). {@code null} em
     * qualquer um dos dois significa "não mudar o que já está configurado
     * no simulador" — não é o mesmo que "limpar" (ver `server.py`).
     *
     * <p>F7/AD-17 — {@code touchContact} (borda, esbarrou em bloco) e
     * {@code touchProximity} (nível, algo perto agora) são a família de
     * sensores de toque decidida pelo usuário (20/09/2026, ver
     * {@link TouchSensor}). Enviados no protocolo desde já; o simulador
     * (`server.py`) ainda não os consome — não existe um segundo `Engine`
     * pro subcircuito `bristle` rodando ainda (RN-09/RN-08 já validaram o
     * circuito isoladamente, ver docs/03-roadmap-fases.md F7). Mesmo estado
     * que `damage` já estava antes desta mudança: chega, não é usado.
     *
     * @throws IOException se a conexão cair — quem chama decide se reconecta
     *     ou segue sem atuar neste tick; nunca esperar aqui.
     */
    public JsonObject sendSensorAndReceiveMotor(
            double light, double dorsalLight, boolean damage,
            boolean touchContact, boolean touchProximity, long tMs,
            Collection<String> mute, StimulateSpec stimulate
    ) throws IOException {
        JsonObject sensor = new JsonObject();
        sensor.addProperty("t_ms", tMs);
        sensor.addProperty("light", light);
        sensor.addProperty("dorsal_light", dorsalLight);
        sensor.addProperty("damage", damage);
        sensor.addProperty("touch_contact", touchContact);
        sensor.addProperty("touch_proximity", touchProximity);
        if (mute != null) {
            JsonArray muteArray = new JsonArray();
            mute.forEach(muteArray::add);
            sensor.add("mute", muteArray);
        }
        if (stimulate != null) {
            JsonObject stimulateObj = new JsonObject();
            stimulateObj.addProperty("group", stimulate.group());
            stimulateObj.addProperty("amplitude", stimulate.amplitude());
            sensor.add("stimulate", stimulateObj);
        }

        out.print(gson.toJson(sensor));
        out.print('\n');
        out.flush();

        String line = in.readLine();
        if (line == null) {
            throw new IOException("conexão com o simulador fechada");
        }
        return gson.fromJson(line, JsonObject.class);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
