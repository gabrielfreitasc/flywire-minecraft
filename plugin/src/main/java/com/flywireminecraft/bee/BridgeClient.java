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

/** Cliente TCP da ponte com o simulador (L5). */
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
            boolean touchContact, boolean touchProximity, boolean raining,
            boolean alarmExplosion, boolean alarmHostileMob, boolean soundMusic,
            boolean loomingThreat, boolean foodContact, boolean thermoHeat, boolean thermoCold, long tMs
    ) throws IOException {
        return sendSensorAndReceiveMotor(
                light, dorsalLight, damage, touchContact, touchProximity, raining,
                alarmExplosion, alarmHostileMob, soundMusic, loomingThreat, foodContact, thermoHeat, thermoCold,
                tMs, null, null);
    }

    public JsonObject sendSensorAndReceiveMotor(
            double light, double dorsalLight, boolean damage,
            boolean touchContact, boolean touchProximity, boolean raining,
            boolean alarmExplosion, boolean alarmHostileMob, boolean soundMusic,
            boolean loomingThreat, boolean foodContact, boolean thermoHeat, boolean thermoCold, long tMs,
            Collection<String> mute, StimulateSpec stimulate
    ) throws IOException {
        JsonObject sensor = new JsonObject();
        sensor.addProperty("t_ms", tMs);
        sensor.addProperty("light", light);
        sensor.addProperty("dorsal_light", dorsalLight);
        sensor.addProperty("damage", damage);
        sensor.addProperty("touch_contact", touchContact);
        sensor.addProperty("touch_proximity", touchProximity);
        sensor.addProperty("raining", raining);
        sensor.addProperty("alarm_explosion", alarmExplosion);
        sensor.addProperty("alarm_hostile_mob", alarmHostileMob);
        sensor.addProperty("sound_music", soundMusic);
        sensor.addProperty("looming_threat", loomingThreat);
        sensor.addProperty("food_contact", foodContact);
        sensor.addProperty("thermo_heat", thermoHeat);
        sensor.addProperty("thermo_cold", thermoCold);
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
