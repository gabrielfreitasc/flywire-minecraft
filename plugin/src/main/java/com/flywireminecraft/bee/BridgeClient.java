package com.flywireminecraft.bee;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Cliente TCP da ponte com o simulador (L5). Protocolo JSON-lines — ver
 * docs/02-arquitetura.md e sim/src/flywire_sim/server.py.
 *
 * <p>O simulador é servidor; este cliente nunca bloqueia o tick do jogo
 * esperando o simulador terminar passos extras (RN-06). Cada chamada a
 * {@link #sendSensorAndReceiveMotor} é uma troca síncrona rápida — o
 * simulador responde com o ÚLTIMO vetor motor já computado pela thread de
 * simulação, não um calculado na hora.
 *
 * <p>Scaffold da F3. Uso real (ler sensores, aplicar vetor motor à abelha)
 * é da F4.
 */
public final class BridgeClient implements AutoCloseable {

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

    /**
     * Envia uma leitura de sensores e retorna o vetor motor mais recente.
     *
     * @throws IOException se a conexão cair — quem chama decide se reconecta
     *     ou segue sem atuar neste tick; nunca esperar aqui.
     */
    public JsonObject sendSensorAndReceiveMotor(double light, double dorsalLight, boolean damage, long tMs)
            throws IOException {
        JsonObject sensor = new JsonObject();
        sensor.addProperty("t_ms", tMs);
        sensor.addProperty("light", light);
        sensor.addProperty("dorsal_light", dorsalLight);
        sensor.addProperty("damage", damage);

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
