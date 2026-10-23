package io.github.icmrnbw.tvremote.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remote for TVs built by Vestel and sold under many brands (Toshiba, JVC, Hitachi,
 * Telefunken, Finlux... and some Rosso sets). They take "virtual remote" key presses as
 * small XML posts to their Smart Center endpoint, and launch YouTube / Netflix via DIAL.
 * The TV only listens while "Virtual Remote" is enabled in its settings.
 */
public final class VestelClient {
    public static final int PORT = 56789;

    public static final int POWER = 1012;
    public static final int MUTE = 1013;
    public static final int VOLUME_UP = 1016;
    public static final int VOLUME_DOWN = 1017;
    public static final int INFO = 1018;
    public static final int DOWN = 1019;
    public static final int UP = 1020;
    public static final int LEFT = 1021;
    public static final int RIGHT = 1022;
    public static final int STOP = 1024;
    public static final int PLAY = 1025;
    public static final int REWIND = 1027;
    public static final int FORWARD = 1028;
    public static final int CHANNEL_UP = 1032;
    public static final int CHANNEL_DOWN = 1033;
    public static final int BACK = 1010;
    public static final int EXIT = 1037;
    public static final int QUICK_MENU = 1043;
    public static final int HOME = 1046;
    public static final int EPG = 1047;
    public static final int MENU = 1048;
    public static final int PAUSE = 1049;
    public static final int OK = 1053;
    public static final int SOURCE = 1056;
    public static final int DIGIT_0 = 1000;

    public interface Listener {
        void onSendFailed(String reason);
    }

    private final String host;
    private final Listener listener;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    public VestelClient(String host, Listener listener) {
        this.host = host;
        this.listener = listener;
    }

    /** True if something answers on the Smart Center port. Blocks briefly. */
    public static boolean isReachable(String host) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, PORT), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void sendKey(int code) {
        post("/apps/SmartCenter", "<?xml version='1.0' ?><remote><key code='" + code + "'/></remote>");
    }

    /** Starts a DIAL app such as "YouTube" or "Netflix". */
    public void launchApp(String dialName) {
        post("/apps/" + dialName, "");
    }

    public void close() {
        sender.shutdownNow();
    }

    private void post(String path, String body) {
        try {
            sender.execute(() -> {
                try {
                    int status = postNow(path, body);
                    if (status >= 400) listener.onSendFailed("The TV answered " + status);
                } catch (IOException e) {
                    listener.onSendFailed(e.getMessage() != null ? e.getMessage() : "No answer from the TV");
                }
            });
        } catch (RuntimeException ignored) {
            // Already closed.
        }
    }

    int postNow(String path, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + PORT + path).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain; charset=ISO-8859-1");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                byte[] buf = new byte[1024];
                while (in.read(buf) > 0) {
                    // drain so the connection can be reused
                }
                in.close();
            }
            return status;
        } finally {
            c.disconnect();
        }
    }
}
