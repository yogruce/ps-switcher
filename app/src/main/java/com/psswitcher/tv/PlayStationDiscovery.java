package com.psswitcher.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Automatically discovers and wakes up PS4, PS5, and future PlayStation (PS6) consoles
 * over the local network using:
 * 1. Sony DDP (Device Discovery Protocol) on UDP ports 987 (PS4) and 9302 (PS5+)
 * 2. SSDP / UPnP M-SEARCH multicast discovery (future-proof for PS6 & Sony devices)
 * 3. Wake-on-LAN (Magic Packet) + DDP WAKEUP packet
 */
public class PlayStationDiscovery {

    public static final int PORT_PS4 = 987;
    public static final int PORT_PS5 = 9302;
    public static final int PORT_SSDP = 1900;

    // Known Sony Interactive Entertainment MAC OUI prefixes (for ARP/WoL fallback)
    private static final String[] SONY_MAC_PREFIXES = {
        "00:04:1f", "00:13:15", "00:15:c1", "00:19:c5", "00:1d:0d", "00:1f:a7",
        "00:24:8d", "00:d9:d1", "28:0d:fc", "2c:cc:44", "70:9e:29", "78:c8:81",
        "90:cdd:3", "a8:e3:ee", "bc:60:a7", "c8:63:f1", "f8:46:1c", "f8:d0:ac",
        "fc:0f:e6", "00:e4:21", "1c:98:c1", "40:b8:9a", "7c:bb:8a"
    };

    public static class ConsoleInfo {
        public String ip;
        public String name;
        public String model; // PS4, PS5, PS6 / PlayStation
        public String hostId; // MAC or Host ID from DDP
        public int port;
        public boolean isStandby;
        public int statusCode;

        @Override
        public String toString() {
            String state = isStandby ? "Dinlenme Modu (Standby)" : "Açık";
            return (name != null && !name.isEmpty() ? name : model) + " (" + ip + " - " + state + ")";
        }
    }

    /**
     * Scans the local network for any PS4, PS5, or future PlayStation console.
     */
    public static List<ConsoleInfo> discoverConsoles(Context context, int timeoutMs) {
        Map<String, ConsoleInfo> found = new HashMap<>();
        List<InetAddress> broadcastAddresses = getBroadcastAddresses(context);

        // 1. Query PS5 / Next-Gen port (9302) and PS4 port (987) via DDP
        int[] ddpPorts = {PORT_PS5, PORT_PS4};
        for (int port : ddpPorts) {
            queryDdpPort(broadcastAddresses, port, timeoutMs / 2, found);
        }

        // 2. If saved IP exists in preferences, also unicast directly to it (works even if broadcast is blocked by router)
        SharedPreferences prefs = context.getSharedPreferences("ps_switcher_prefs", Context.MODE_PRIVATE);
        String savedIp = prefs.getString("saved_ps_ip", null);
        if (savedIp != null && !savedIp.isEmpty() && !found.containsKey(savedIp)) {
            try {
                List<InetAddress> directList = Collections.singletonList(InetAddress.getByName(savedIp));
                queryDdpPort(directList, PORT_PS5, 350, found);
                queryDdpPort(directList, PORT_PS4, 350, found);
            } catch (Exception ignored) {
            }
        }

        // 3. SSDP discovery fallback for future PlayStation models (PS6 / Sony UPnP)
        if (found.isEmpty()) {
            discoverViaSsdp(timeoutMs / 2, found);
        }

        return new ArrayList<>(found.values());
    }

    private static void queryDdpPort(List<InetAddress> targets, int port, int timeoutMs, Map<String, ConsoleInfo> found) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(Math.max(250, timeoutMs));

            String searchPayload =
                    "SRCH * HTTP/1.1\r\n" +
                    "device-discovery-protocol-version:00030010\r\n\r\n";
            byte[] data = searchPayload.getBytes(StandardCharsets.UTF_8);

            // Also send v2 protocol header for PS4 compatibility
            String searchPayloadV2 =
                    "SRCH * HTTP/1.1\r\n" +
                    "device-discovery-protocol-version:00020020\r\n\r\n";
            byte[] dataV2 = searchPayloadV2.getBytes(StandardCharsets.UTF_8);

            for (InetAddress addr : targets) {
                try {
                    socket.send(new DatagramPacket(data, data.length, addr, port));
                    socket.send(new DatagramPacket(dataV2, dataV2.length, addr, port));
                } catch (Exception ignored) {
                }
            }

            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                String senderIp = packet.getAddress().getHostAddress();

                ConsoleInfo info = parseDdpResponse(senderIp, port, response);
                if (info != null) {
                    found.put(senderIp, info);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private static ConsoleInfo parseDdpResponse(String ip, int port, String response) {
        if (response == null || !response.contains("HTTP/1.1")) {
            return null;
        }
        ConsoleInfo info = new ConsoleInfo();
        info.ip = ip;
        info.port = port;
        info.model = (port == PORT_PS4) ? "PS4" : "PS5";

        String[] lines = response.split("\r?\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("HTTP/1.1")) {
                if (trimmed.contains("620")) {
                    info.statusCode = 620;
                    info.isStandby = true;
                } else if (trimmed.contains("200")) {
                    info.statusCode = 200;
                    info.isStandby = false;
                }
            } else {
                int colon = trimmed.indexOf(':');
                if (colon > 0) {
                    String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String val = trimmed.substring(colon + 1).trim();
                    switch (key) {
                        case "host-name":
                            info.name = val;
                            break;
                        case "host-type":
                            info.model = val;
                            break;
                        case "host-id":
                            info.hostId = val;
                            break;
                    }
                }
            }
        }
        if (info.name == null || info.name.isEmpty()) {
            info.name = info.model;
        }
        return info;
    }

    private static void discoverViaSsdp(int timeoutMs, Map<String, ConsoleInfo> found) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(Math.max(250, timeoutMs));
            InetAddress multicast = InetAddress.getByName("239.255.255.250");
            String msg =
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: ssdp:all\r\n\r\n";
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, multicast, PORT_SSDP));

            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                String resp = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                String lower = resp.toLowerCase(Locale.ROOT);
                if (lower.contains("playstation") || lower.contains("ps4") || lower.contains("ps5") || lower.contains("ps6") || lower.contains("sony interactive")) {
                    String ip = pkt.getAddress().getHostAddress();
                    if (!found.containsKey(ip)) {
                        ConsoleInfo info = new ConsoleInfo();
                        info.ip = ip;
                        info.port = PORT_PS5;
                        info.model = lower.contains("ps6") ? "PS6" : (lower.contains("ps4") ? "PS4" : "PlayStation");
                        info.name = info.model;
                        info.isStandby = false;
                        found.put(ip, info);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    /**
     * Sends both Sony DDP WAKEUP packets (PS4/PS5/PS6) and standard Wake-on-LAN Magic Packets.
     */
    public static void wakeConsole(Context context, ConsoleInfo console, String userCredential) {
        SharedPreferences prefs = context.getSharedPreferences("ps_switcher_prefs", Context.MODE_PRIVATE);
        if (userCredential == null || userCredential.isEmpty()) {
            userCredential = prefs.getString("ps_credential", "0000000000000000000000000000000000000000000000000000000000000000");
        }

        List<InetAddress> broadcastAddresses = getBroadcastAddresses(context);

        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);

            String[] protoVersions = {"00030010", "00020020"};
            int[] ports = (console != null && console.port > 0)
                    ? new int[]{console.port, PORT_PS5, PORT_PS4}
                    : new int[]{PORT_PS5, PORT_PS4};

            for (String proto : protoVersions) {
                String wakePayload =
                        "WAKEUP * HTTP/1.1\r\n" +
                        "client-type:vr\r\n" +
                        "auth-type:C\r\n" +
                        "model:w\r\n" +
                        "app-type:r\r\n" +
                        "user-credential:" + userCredential + "\r\n" +
                        "device-discovery-protocol-version:" + proto + "\r\n\r\n";
                byte[] data = wakePayload.getBytes(StandardCharsets.UTF_8);

                if (console != null && console.ip != null) {
                    InetAddress targetIp = InetAddress.getByName(console.ip);
                    for (int p : ports) {
                        socket.send(new DatagramPacket(data, data.length, targetIp, p));
                    }
                }

                for (InetAddress bcast : broadcastAddresses) {
                    for (int p : ports) {
                        try {
                            socket.send(new DatagramPacket(data, data.length, bcast, p));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            // Also send Wake-on-LAN (Magic Packet) if hostId / MAC is known or found in ARP table
            String mac = (console != null && console.hostId != null) ? console.hostId : prefs.getString("saved_ps_mac", null);
            if (mac == null && console != null && console.ip != null) {
                mac = lookupMacFromArp(console.ip);
            }
            if (mac != null) {
                sendMagicPacket(socket, broadcastAddresses, mac);
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private static void sendMagicPacket(DatagramSocket socket, List<InetAddress> broadcasts, String macAddress) {
        byte[] macBytes = parseMac(macAddress);
        if (macBytes == null) return;

        byte[] magic = new byte[6 + 16 * macBytes.length];
        for (int i = 0; i < 6; i++) {
            magic[i] = (byte) 0xFF;
        }
        for (int i = 6; i < magic.length; i += macBytes.length) {
            System.arraycopy(macBytes, 0, magic, i, macBytes.length);
        }

        for (InetAddress bcast : broadcasts) {
            try {
                socket.send(new DatagramPacket(magic, magic.length, bcast, 9));
                socket.send(new DatagramPacket(magic, magic.length, bcast, 7));
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] parseMac(String mac) {
        String clean = mac.replaceAll("[^0-9A-Fa-f]", "");
        if (clean.length() != 12) return null;
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String lookupMacFromArp(String ip) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/net/arp"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] splitted = line.split(" +");
                if (splitted.length >= 4 && ip.equals(splitted[0])) {
                    String mac = splitted[3];
                    if (mac.matches("..:..:..:..:..:..") && !mac.equals("00:00:00:00:00:00")) {
                        return mac;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (br != null) br.close();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static List<InetAddress> getBroadcastAddresses(Context context) {
        List<InetAddress> list = new ArrayList<>();
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }

        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                DhcpInfo dhcp = wifi.getDhcpInfo();
                if (dhcp != null && dhcp.ipAddress != 0) {
                    int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
                    byte[] quads = new byte[4];
                    for (int k = 0; k < 4; k++) {
                        quads[k] = (byte) ((broadcast >> (k * 8)) & 0xFF);
                    }
                    InetAddress addr = InetAddress.getByAddress(quads);
                    if (!list.contains(addr)) {
                        list.add(addr);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (broadcast != null && !list.contains(broadcast)) {
                        list.add(broadcast);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }
}
