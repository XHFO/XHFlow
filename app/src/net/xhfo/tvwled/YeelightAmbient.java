package net.xhfo.tvwled;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.io.Closeable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Direct LAN control of the Razer-Chroma capable Yeelight ambient light.
 *
 * <p>The screen light bar pro keeps its back LED behind an undocumented token channel: SSDP on
 * 1982 advertises {@code chroma} / {@code udp_new} / {@code bg_set_rgb} support, a JSON
 * {@code udp_new} datagram to port 55444 returns a 32 character token, and every later command
 * carries it. Only {@code bg_*} methods touch the back LED, so the front light stays untouched
 * unless main-light linking is enabled in the configuration.
 */
final class YeelightAmbient implements Closeable {
    static final int LAN_PORT = 55444;
    private static final String SSDP_GROUP = "239.255.255.250";
    private static final int SSDP_PORT = 1982;
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final Pattern LOCATION = Pattern.compile("Location:\\s*yeelight://([0-9.]+):(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPORT = Pattern.compile("support:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL = Pattern.compile("model:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE);

    private final String address;
    private final InetAddress target;
    private DatagramSocket socket;
    private String token = "";
    private String model = "";
    private int messageId = 1;
    private int lastRgb = -1, lastBrightness = -1, lastMainBrightness = -1;
    private long lastCommandAt;

    YeelightAmbient(String address) throws Exception {
        LayoutConfig.validateAddress(address);
        this.address = address;
        this.target = InetAddress.getByName(address);
    }

    String address() { return address; }
    String model() { return model; }
    boolean connected() { return !token.isEmpty(); }

    /** Sends {@code udp_new} and waits for the token reply. Returns false when the device stays silent. */
    synchronized boolean connect(int timeoutMs) {
        closeSocket();
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(Math.max(120, timeoutMs));
            for (String method : new String[]{"udp_new", "udp_sess_new"}) {
                send(new JSONObject().put("id", next()).put("method", method).put("params", new org.json.JSONArray()).toString() + "\r\n");
                long deadline = System.currentTimeMillis() + Math.max(200, timeoutMs / 2);
                while (System.currentTimeMillis() < deadline) {
                    String reply = receive(Math.max(80, timeoutMs));
                    if (reply == null) break;
                    Matcher matcher = TOKEN.matcher(reply);
                    if (matcher.find()) {
                        token = matcher.group(1);
                        Matcher name = MODEL.matcher(reply);
                        if (name.find() && model.isEmpty()) model = name.group(1).trim();
                        return true;
                    }
                    if (reply.contains("\"error\"")) break;
                }
                if (token.length() == 32) return true;
            }
        } catch (Exception ignored) {
            // A silent socket is the normal "not reachable" answer; the caller retries with backoff.
        }
        token = "";
        return false;
    }

    /** Back LED colour in 0xRRGGBB plus its own brightness percentage; repeats are dropped. */
    synchronized void sendColor(int rgb, int brightnessPercent) {
        if (!connected()) return;
        int color = rgb & 0xFFFFFF, level = clamp(brightnessPercent, 1, 100);
        if (level != lastBrightness) {
            send(ambientBrightnessCommand(next(), level, token));
            lastBrightness = level;
        }
        if (color == lastRgb) return;
        send(ambientColorCommand(next(), color, token));
        lastRgb = color;
        lastCommandAt = System.currentTimeMillis();
    }

    /** Front light brightness only; the colour temperature and power state are left alone. */
    synchronized void sendMainBrightness(int percent) {
        if (!connected()) return;
        int level = clamp(percent, 1, 100);
        if (level == lastMainBrightness) return;
        lastMainBrightness = level;
        sendCommand(mainBrightnessCommand(next(), level, token));
    }

    synchronized void setAmbientPower(boolean on) {
        sendCommand(String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"bg_set_power\", \"params\" : [\"%s\", \"sudden\", 0], \"token\":\"%s\" }\r\n",
                next(), on ? "on" : "off", token));
    }

    synchronized void keepAlive() {
        sendCommand(String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"udp_keep_alive\", \"params\" : [\"keeplive_interval\",\"10\"], \"token\":\"%s\" }\r\n",
                next(), token));
    }

    static String ambientColorCommand(int id, int rgb, String token) {
        return String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"bg_set_rgb\", \"params\" : [%d, \"sudden\", 0], \"token\":\"%s\" }\r\n",
                id, rgb & 0xFFFFFF, token);
    }

    static String ambientBrightnessCommand(int id, int percent, String token) {
        return String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"bg_set_bright\", \"params\" : [%d, \"sudden\", 0], \"token\":\"%s\" }\r\n",
                id, clamp(percent, 1, 100), token);
    }

    static String mainBrightnessCommand(int id, int percent, String token) {
        return String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"set_bright\", \"params\" : [%d, \"sudden\", 0], \"token\":\"%s\" }\r\n",
                id, clamp(percent, 1, 100), token);
    }

    private void sendCommand(String command) {
        if (!connected()) return;
        send(command);
    }

    private void send(String message) {
        try {
            DatagramSocket active = socket;
            if (active == null || active.isClosed()) return;
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            active.send(new DatagramPacket(data, data.length, target, LAN_PORT));
        } catch (Exception ignored) {
            // UDP has no delivery receipt; the keep-alive loop re-establishes the token.
        }
    }

    private String receive(int timeoutMs) {
        try {
            DatagramSocket active = socket;
            if (active == null) return null;
            active.setSoTimeout(timeoutMs);
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            active.receive(packet);
            return new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        } catch (Exception timeout) {
            return null;
        }
    }

    private int next() { if (messageId < 1) messageId = 1; return messageId++; }

    private static int clamp(int value, int minimum, int maximum) {
        return value < minimum ? minimum : value > maximum ? maximum : value;
    }

    private void closeSocket() {
        if (socket != null) { try { socket.close(); } catch (Exception ignored) {} socket = null; }
    }

    @Override public synchronized void close() { token = ""; lastRgb = -1; lastBrightness = -1; lastMainBrightness = -1; closeSocket(); }

    // ---- discovery / probe -------------------------------------------------------------

    /** SSDP scan; results are ordered and de-duplicated by location. */
    static List<JSONObject> discover(Context context, int timeoutMs) throws Exception {
        WifiManager.MulticastLock lock = null;
        DatagramSocket socket = null;
        List<JSONObject> found = new ArrayList<>();
        try {
            try {
                WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) { lock = wifi.createMulticastLock("xhflow-ssdp"); lock.setReferenceCounted(false); lock.acquire(); }
            } catch (Exception ignored) {}
            socket = new DatagramSocket();
            socket.setSoTimeout(200);
            byte[] query = ("M-SEARCH * HTTP/1.1\r\nHOST: " + SSDP_GROUP + ":" + SSDP_PORT + "\r\nMAN: \"ssdp:discover\"\r\nST: wifi_bulb\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(query, query.length, InetAddress.getByName(SSDP_GROUP), SSDP_PORT));
            long deadline = System.currentTimeMillis() + Math.max(300, timeoutMs);
            Map<String, JSONObject> byAddress = new LinkedHashMap<>();
            while (System.currentTimeMillis() < deadline) {
                byte[] buffer = new byte[2048];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(packet); } catch (Exception timeout) { continue; }
                String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                Matcher location = LOCATION.matcher(text);
                if (!location.find()) continue;
                String ip = location.group(1);
                if (byAddress.containsKey(ip)) continue;
                Matcher support = SUPPORT.matcher(text);
                String capabilities = support.find() ? support.group(1).trim() : "";
                Matcher model = MODEL.matcher(text);
                byAddress.put(ip, new JSONObject()
                        .put("address", ip)
                        .put("model", model.find() ? model.group(1).trim() : "")
                        .put("ambient", supportsAmbient(capabilities))
                        .put("chroma", capabilities.toLowerCase(Locale.ROOT).contains("chroma"))
                        .put("keepAlive", supportsToken(capabilities)));
            }
            found.addAll(byAddress.values());
            return found;
        } finally {
            if (socket != null) socket.close();
            if (lock != null) { try { lock.release(); } catch (Exception ignored) {} }
        }
    }

    /** Verifies the token channel for a manually entered address. */
    static JSONObject probe(String address, int timeoutMs) throws Exception {
        YeelightAmbient client = new YeelightAmbient(address);
        try {
            boolean reachable = client.connect(timeoutMs);
            return new JSONObject().put("address", address).put("reachable", reachable)
                    .put("ambient", reachable).put("model", client.model());
        } finally {
            client.close();
        }
    }

    private static boolean supportsAmbient(String capabilities) {
        String lower = capabilities.toLowerCase(Locale.ROOT);
        return lower.contains("bg_set_rgb") || lower.contains("bg_set_scene") || lower.contains("bg_set_bright");
    }

    private static boolean supportsToken(String capabilities) {
        String lower = capabilities.toLowerCase(Locale.ROOT);
        return lower.contains("udp_new") || lower.contains("udp_sess_new");
    }
}
