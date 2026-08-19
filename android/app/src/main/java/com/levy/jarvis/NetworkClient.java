package com.levy.jarvis;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class NetworkClient {
    public static class Response {
        public boolean ignored;
        public String heard = "";
        public String reply = "";
        public byte[] audio = new byte[0];
        public List<JSONObject> actions = new ArrayList<>();
    }

    private NetworkClient() {}

    private static InetAddress broadcastAddress(Context context) throws Exception {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        DhcpInfo dhcp = wifi.getDhcpInfo();
        if (dhcp == null) return InetAddress.getByName("255.255.255.255");
        int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
        byte[] quads = new byte[4];
        for (int k = 0; k < 4; k++) quads[k] = (byte)((broadcast >> k * 8) & 0xFF);
        return InetAddress.getByAddress(quads);
    }

    public static String discover(Context context) {
        for (int attempt = 0; attempt < 3; attempt++) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(2500);
                byte[] msg = JarvisConfig.DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
                DatagramPacket p = new DatagramPacket(
                        msg, msg.length, broadcastAddress(context), JarvisConfig.DISCOVERY_PORT);
                socket.send(p);

                byte[] buf = new byte[256];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                socket.receive(response);
                String text = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
                if (text.startsWith(JarvisConfig.DISCOVERY_RESPONSE)) {
                    return response.getAddress().getHostAddress();
                }
            } catch (Exception ignored) {
            } finally {
                if (socket != null) socket.close();
            }
        }
        return null;
    }

    public static Response send(String host, byte[] wav) throws Exception {
        URL url = new URL("http://" + host + ":" + JarvisConfig.HTTP_PORT + "/v1/utterance");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(7000);
        c.setReadTimeout(190000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "audio/wav");
        c.setRequestProperty("X-Jarvis-Secret", JarvisConfig.SECRET);
        c.setFixedLengthStreamingMode(wav.length);

        try (OutputStream os = c.getOutputStream()) {
            os.write(wav);
        }

        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) > 0) out.write(b, 0, n);
        String body = out.toString("UTF-8");
        if (code != 200) throw new Exception("HTTP " + code + ": " + body);

        JSONObject o = new JSONObject(body);
        Response r = new Response();
        r.ignored = o.optBoolean("ignored", false);
        r.heard = o.optString("heard", "");
        r.reply = o.optString("reply", "");

        String audio = o.optString("audio", "");
        if (!audio.isEmpty()) r.audio = Base64.decode(audio, Base64.DEFAULT);

        JSONArray actions = o.optJSONArray("actions");
        if (actions != null) {
            for (int i = 0; i < actions.length(); i++) {
                JSONObject a = actions.optJSONObject(i);
                if (a != null) r.actions.add(a);
            }
        }
        return r;
    }
}
