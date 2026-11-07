package net.xhfo.tvwled;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
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
    private static final String TAG = "TVWLED";
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
    private long lastBrightnessAt, lastMainBrightnessAt;
    private long lastCommandAt;
    /** 2 = session protocol (udp_sess_*), 1 = legacy udp_new token. Firmware decides which answers. */
    private int protocol;
    private int keepAliveMisses;

    YeelightAmbient(String address) throws Exception {
        LayoutConfig.validateAddress(address);
        this.address = address;
        this.target = InetAddress.getByName(address);
    }

    String address() { return address; }
    String model() { return model; }
    boolean connected() { return !token.isEmpty(); }

    /**
     * Asks for a token. Firmware answers exactly one of {@code udp_new} (legacy) or
     * {@code udp_sess_new} (session); the screen light bar pro rejects the legacy method with
     * {@code invalid parameter} and only accepts the session form.
     */
    synchronized boolean connect(int timeoutMs) {
        closeSocket();
        token = "";
        protocol = 0;
        keepAliveMisses = 0;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(Math.max(120, timeoutMs));
            String[][] attempts = {{"udp_new", "1"}, {"udp_sess_new", "2"}};
            for (String[] attempt : attempts) {
                send(new JSONObject().put("id", next()).put("method", attempt[0])
                        .put("params", new org.json.JSONArray()).toString() + "\r\n");
                long deadline = System.currentTimeMillis() + Math.max(200, timeoutMs / 2);
                while (System.currentTimeMillis() < deadline) {
                    String reply = receive(Math.max(80, timeoutMs));
                    if (reply == null) break;
                    Matcher matcher = TOKEN.matcher(reply);
                    if (matcher.find() && reply.contains("token")) {
                        token = matcher.group(1);
                        protocol = Integer.parseInt(attempt[1]);
                        lastRgb = -1; lastBrightness = -1; lastMainBrightness = -1;
                        Matcher name = MODEL.matcher(reply);
                        if (name.find() && model.isEmpty()) model = name.group(1).trim();
                        Log.i(TAG, "yeelight token acquired via " + attempt[0]);
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
        protocol = 0;
        return false;
    }

    /** Back LED colour in 0xRRGGBB plus its own brightness percentage; repeats are dropped. */
    synchronized void sendColor(int rgb, int brightnessPercent) {
        if (!connected()) return;
        int color = rgb & 0xFFFFFF, level = clamp(brightnessPercent, 1, 100);
        long now = System.currentTimeMillis();
        // The bar drops back to full brightness on power and colour changes, and UDP has no
        // receipt, so the level rides along with every colour instead of being set once.
        if (level != lastBrightness || color != lastRgb || now - lastBrightnessAt > 15000) {
            send(ambientBrightnessCommand(next(), level, token));
            lastBrightness = level;
            lastBrightnessAt = now;
        }
        if (sameColor(color, lastRgb, 2)) return;
        send(ambientColorCommand(next(), color, token));
        lastRgb = color;
        lastCommandAt = System.currentTimeMillis();
    }

    /** Switches the front light, used by presets that want the desk lamp on or off. */
    synchronized void setMainPower(boolean on) {
        sendCommand(String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"set_power\", \"params\" : [\"%s\", \"smooth\", 500], \"token\":\"%s\" }\r\n",
                next(), on ? "on" : "off", token));
    }

    /** Colour temperature in kelvin; firmware accepts 1700-6500. */
    synchronized void setMainCt(int kelvin) {
        sendCommand(String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"set_ct_abx\", \"params\" : [%d, \"smooth\", 500], \"token\":\"%s\" }\r\n",
                next(), Math.max(1700, Math.min(6500, kelvin)), token));
    }

    /** Front light brightness only; the colour temperature and power state are left alone. */
    synchronized void sendMainBrightness(int percent) {
        if (!connected()) return;
        int level = clamp(percent, 1, 100);
        long now = System.currentTimeMillis();
        if (level == lastMainBrightness && now - lastMainBrightnessAt < 15000) return;
        lastMainBrightness = level;
        lastMainBrightnessAt = now;
        sendCommand(mainBrightnessCommand(next(), level, token));
    }

    /** Colour-temperature mode for the back LED: the bar works as a plain white light. */
    synchronized void setAmbientCt(int kelvin) {
        sendCommand(String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"bg_set_ct_abx\", \"params\" : [%d, \"smooth\", 500], \"token\":\"%s\" }\r\n",
                next(), Math.max(1700, Math.min(6500, kelvin)), token));
    }

    synchronized void setAmbientBrightness(int percent) {
        if (!connected()) return;
        send(ambientBrightnessCommand(next(), clamp(percent, 1, 100), token));
    }

    synchronized void setAmbientPower(boolean on) {
        Log.i(TAG,"yeelight power "+(on?"on":"off")+" connected="+connected());
        sendCommand(ambientPowerCommand(next(), on, token));
    }

    static String ambientPowerCommand(int id, boolean on, String token) {
        return String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"bg_set_power\", \"params\" : [\"%s\", \"sudden\", 0], \"token\":\"%s\" }\r\n",
                id, on ? "on" : "off", token);
    }

    /**
     * Keeps the session alive. The session form is answered with {@code ["ok"]}, which is the only
     * way to notice that the device dropped us; the legacy form is sent without waiting.
     *
     * @return true when the session still answers
     */
    synchronized boolean keepAlive() {
        if (!connected()) return false;
        String method = protocol == 2 ? "udp_sess_keep_alive" : "udp_keep_alive";
        send(String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"%s\", \"params\" : [\"keeplive_interval\",\"10\"], \"token\":\"%s\" }\r\n",
                next(), method, token));
        if (protocol != 2) return true;
        String reply = receive(350);
        if (reply == null) {
            keepAliveMisses++;
            if (keepAliveMisses >= 2) { token = ""; protocol = 0; keepAliveMisses = 0; Log.w(TAG, "yeelight session lost; re-handshaking"); }
            return false;
        }
        keepAliveMisses = 0;
        Matcher rotated = TOKEN.matcher(reply);
        if (rotated.find()) token = rotated.group(1);
        return true;
    }

    int protocol() { return protocol; }

    /** Two levels of slack: the sampled colour wobbles by one on a still picture. */
    static boolean sameColor(int a, int b, int tolerance) {
        if (b < 0) return false;
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= tolerance
            && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= tolerance
            && Math.abs((a & 255) - (b & 255)) <= tolerance;
    }

    static String ambientColorCommand(int id, int rgb, String token) {
        return String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"bg_set_rgb\", \"params\" : [%d, \"sudden\", 0], \"token\":\"%s\" }\r\n",
                id, rgb & 0xFFFFFF, token);
    }

    static String ambientCtCommand(int id, int kelvin, String token) {
        return String.format(Locale.ROOT,
                "{ \"id\":%d, \"method\" : \"bg_set_ct_abx\", \"params\" : [%d, \"smooth\", 500], \"token\":\"%s\" }\r\n",
                id, Math.max(1700, Math.min(6500, kelvin)), token);
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
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        try {
            DatagramSocket active = socket;
            if (active != null && !active.isClosed()) {
                active.send(new DatagramPacket(data, data.length, target, LAN_PORT));
                return;
            }
            // A concurrent re-handshake can replace the socket between closeSocket() and the new
            // one; a one-shot socket keeps a late "power off" from being dropped on the floor.
            try (DatagramSocket fallback = new DatagramSocket()) {
                fallback.send(new DatagramPacket(data, data.length, target, LAN_PORT));
            }
        } catch (Exception failure) {
            Log.w(TAG, "yeelight send failed: " + failure.getMessage());
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

    @Override public synchronized void close() { token = ""; lastRgb = -1; lastBrightness = -1; lastMainBrightness = -1; lastBrightnessAt = 0; lastMainBrightnessAt = 0; closeSocket(); }

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
