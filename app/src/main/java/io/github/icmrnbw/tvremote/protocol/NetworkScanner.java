package io.github.icmrnbw.tvremote.protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds TVs on the local network without relying on mDNS: probes every address in the
 * phone's subnet for the ports smart TV platforms listen on, and asks UPnP devices to
 * describe themselves. Tells Android TVs apart from TVs this app can't control.
 */
public final class NetworkScanner {

    public static final class Device {
        public final String ip;
        public final Set<String> kinds = new LinkedHashSet<>();
        public String upnpServer;

        Device(String ip) { this.ip = ip; }

        public synchronized boolean isAndroidTv() {
            return kinds.contains(ANDROID_TV);
        }

        public synchronized String describe() {
            String what = kinds.isEmpty() ? "Unknown device" : String.join(" / ", kinds);
            return upnpServer == null ? what : what + " (" + upnpServer + ")";
        }
    }

    public interface Progress {
        void onDevice(Device device);
    }

    static final String ANDROID_TV = "Android TV / Google TV";

    private static final int[] PORTS = {6466, 8060, 3000, 3001, 8001, 8002, 36669, 1925, 8009};
    private static final String[] PORT_KINDS = {
            ANDROID_TV, "Roku TV", "LG webOS TV", "LG webOS TV", "Samsung TV", "Samsung TV",
            "Hisense VIDAA TV", "Philips TV", "Chromecast built-in"};

    private NetworkScanner() {}

    /** The phone's own IPv4 address on Wi‑Fi (or any LAN), with its prefix length. */
    public static InterfaceAddress localAddress() throws IOException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            for (InterfaceAddress a : nic.getInterfaceAddresses()) {
                InetAddress ip = a.getAddress();
                if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) return a;
            }
        }
        return null;
    }

    /** Scans the /24 around the phone's address. Blocks for several seconds. */
    public static List<Device> scan(Progress progress) throws IOException {
        InterfaceAddress local = localAddress();
        if (local == null) throw new IOException("This phone isn't on Wi‑Fi");
        byte[] own = local.getAddress().getAddress();
        List<String> hosts = new ArrayList<>();
        for (int i = 1; i < 255; i++) {
            if (i == (own[3] & 0xFF)) continue;
            hosts.add((own[0] & 0xFF) + "." + (own[1] & 0xFF) + "." + (own[2] & 0xFF) + "." + i);
        }
        return scan(hosts, progress);
    }

    public static List<Device> scan(List<String> hosts, Progress progress) {
        Map<String, Device> found = Collections.synchronizedMap(new TreeMap<>());
        Thread upnp = new Thread(() -> askUpnp(found, progress), "upnp-search");
        upnp.start();

        ExecutorService pool = Executors.newFixedThreadPool(64);
        for (String host : hosts) {
            for (int p = 0; p < PORTS.length; p++) {
                final int port = PORTS[p];
                final String kind = PORT_KINDS[p];
                pool.execute(() -> {
                    if (isOpen(host, port)) {
                        Device d = device(found, host);
                        synchronized (d) {
                            d.kinds.add(kind);
                        }
                        if (progress != null) progress.onDevice(d);
                    }
                });
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.SECONDS);
            upnp.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (found) {
            return new ArrayList<>(found.values());
        }
    }

    private static Device device(Map<String, Device> found, String ip) {
        synchronized (found) {
            Device d = found.get(ip);
            if (d == null) {
                d = new Device(ip);
                found.put(ip, d);
            }
            return d;
        }
    }

    private static boolean isOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** SSDP search: TVs answer with a SERVER line that usually names their platform. */
    private static void askUpnp(Map<String, Device> found, Progress progress) {
        String search = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: ssdp:all\r\n\r\n";
        byte[] request = search.getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int i = 0; i < 2; i++) socket.send(new DatagramPacket(request, request.length, group, 1900));
            long end = System.currentTimeMillis() + 4000;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String text = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
                String server = header(text, "server");
                if (server == null) continue;
                Device d = device(found, reply.getAddress().getHostAddress());
                synchronized (d) {
                    if (d.upnpServer == null) d.upnpServer = server;
                    String hint = platformHint(server);
                    if (hint != null) d.kinds.add(hint);
                }
                if (progress != null) progress.onDevice(d);
            }
        } catch (IOException ignored) {
            // Multicast blocked on this network; the port scan still runs.
        }
    }

    static String platformHint(String server) {
        String s = server.toLowerCase(Locale.US);
        if (s.contains("roku")) return "Roku TV";
        if (s.contains("webos")) return "LG webOS TV";
        if (s.contains("tizen") || s.contains("samsung")) return "Samsung TV";
        if (s.contains("vidaa") || s.contains("hisense")) return "Hisense VIDAA TV";
        if (s.contains("android")) return "Android device";
        return null;
    }

    private static String header(String response, String name) {
        for (String line : response.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }
}
