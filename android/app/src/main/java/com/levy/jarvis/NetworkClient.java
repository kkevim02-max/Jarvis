package com.levy.jarvis;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class NetworkClient {
    public interface StreamListener {
        void onMeta(String heard, boolean ignored);
        void onActions(JSONArray actions);
        void onAudio(String text, byte[] wav);
        void onDone();
    }

    private NetworkClient() {}

    private static InetAddress broadcastAddress(Context context) throws Exception {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        DhcpInfo dhcp = wifi.getDhcpInfo();
        if (dhcp == null || dhcp.netmask == 0) return InetAddress.getByName("255.255.255.255");
        int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
        byte[] quads = new byte[4];
        for (int k = 0; k < 4; k++) quads[k] = (byte)((broadcast >> k * 8) & 0xFF);
        return InetAddress.getByAddress(quads);
    }

    public static boolean ping(String host) {
        if (host == null || host.trim().isEmpty()) return false;
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://" + host + ":" + JarvisConfig.HTTP_PORT + "/v2/status");
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(1400);
            c.setReadTimeout(1400);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static String discover(Context context) {
        InetAddress[] targets = new InetAddress[2];
        try { targets[0] = broadcastAddress(context); } catch (Exception ignored) {}
        try { targets[1] = InetAddress.getByName("255.255.255.255"); } catch (Exception ignored) {}

        for (int attempt = 0; attempt < 3; attempt++) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(1300);
                byte[] msg = JarvisConfig.DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
                for (InetAddress target : targets) {
                    if (target == null) continue;
                    DatagramPacket p = new DatagramPacket(msg, msg.length, target, JarvisConfig.DISCOVERY_PORT);
                    socket.send(p);
                }

                byte[] buf = new byte[256];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                socket.receive(response);
                String text = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
                if (text.startsWith(JarvisConfig.DISCOVERY_RESPONSE)) {
                    String host = response.getAddress().getHostAddress();
                    if (ping(host)) return host;
                }
            } catch (Exception ignored) {
            } finally {
                if (socket != null) socket.close();
            }
        }
        return null;
    }

    public static void sendStreaming(String host, byte[] wav, StreamListener listener) throws Exception {
        URL url = new URL("http://" + host + ":" + JarvisConfig.HTTP_PORT + "/v2/utterance");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(5000);
        c.setReadTimeout(210000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "audio/wav");
        c.setRequestProperty("X-Jarvis-Secret", JarvisConfig.SECRET);
        c.setRequestProperty("Connection", "close");
        c.setFixedLengthStreamingMode(wav.length);

        try (OutputStream os = c.getOutputStream()) {
            os.write(wav);
            os.flush();
        }

        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (code != 200) {
            StringBuilder err = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) err.append(line);
            }
            throw new Exception("HTTP " + code + ": " + err);
        }

        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject o = new JSONObject(line);
                String type = o.optString("type", "");
                if ("meta".equals(type)) {
                    listener.onMeta(o.optString("heard", ""), o.optBoolean("ignored", false));
                } else if ("actions".equals(type)) {
                    JSONArray a = o.optJSONArray("actions");
                    if (a != null) listener.onActions(a);
                } else if ("audio".equals(type)) {
                    String b64 = o.optString("audio", "");
                    if (!b64.isEmpty()) {
                        listener.onAudio(o.optString("text", ""), Base64.decode(b64, Base64.DEFAULT));
                    }
                } else if ("done".equals(type)) {
                    listener.onDone();
                }
            }
        } finally {
            c.disconnect();
        }
    }
}
