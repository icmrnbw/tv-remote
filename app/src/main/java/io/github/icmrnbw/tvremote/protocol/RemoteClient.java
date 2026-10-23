package io.github.icmrnbw.tvremote.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;

/**
 * The remote control connection (port 6466). Answers the TV's handshake and pings on a
 * background thread and sends key presses and app links.
 */
public final class RemoteClient {
    public static final int PORT = 6466;

    public static final int DIRECTION_START_LONG = 1;
    public static final int DIRECTION_END_LONG = 2;
    public static final int DIRECTION_SHORT = 3;

    // Features we support: ping, keys, power, volume, app links.
    private static final long FEATURES = 1 | 2 | 32 | 64 | 512;

    // RemoteMessage fields
    private static final int CONFIGURE = 1;
    private static final int SET_ACTIVE = 2;
    private static final int PING_REQUEST = 8;
    private static final int PING_RESPONSE = 9;
    private static final int KEY_INJECT = 10;
    private static final int START = 40;
    private static final int SET_VOLUME_LEVEL = 50;
    private static final int APP_LINK_LAUNCH = 90;

    /** The TV refused our certificate, so the phone has to pair (again). */
    public static final class NotPairedException extends IOException {
        NotPairedException(Throwable cause) { super("This phone isn't paired with the TV", cause); }
    }

    public interface Listener {
        void onReady(String tvName);
        void onPowerChanged(boolean on);
        void onVolumeChanged(int level, int max, boolean muted);
        void onDisconnected(String reason);
    }

    private final String host;
    private final ClientIdentity identity;
    private final String clientName;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile SSLSocket socket;
    private volatile boolean closed;
    private long activeFeatures = FEATURES;

    public RemoteClient(String host, ClientIdentity identity, String clientName, Listener listener) {
        this.host = host;
        this.identity = identity;
        this.clientName = clientName;
        this.listener = listener;
    }

    /** Connects and waits for the TV's first message, so pairing problems surface here. */
    public void connect() throws IOException {
        byte[] first;
        try {
            socket = identity.connect(host, PORT, 10_000);
            first = Proto.readFramed(socket.getInputStream());
        } catch (ConnectException | NoRouteToHostException | UnknownHostException | SocketTimeoutException e) {
            closeQuietly();
            throw e;
        } catch (IOException e) {
            // Reached the TV but it hung up during or right after the TLS handshake:
            // it doesn't know our certificate.
            closeQuietly();
            throw new NotPairedException(e);
        }
        // The TV pings every few seconds; silence for this long means the link is gone.
        socket.setSoTimeout(30_000);
        handle(first);
        Thread reader = new Thread(this::readLoop, "tv-remote-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public void sendKey(int keyCode) {
        sendKey(keyCode, DIRECTION_SHORT);
    }

    public void sendKey(int keyCode, int direction) {
        send(new Proto.Writer().message(KEY_INJECT, new Proto.Writer()
                .uint(1, keyCode)
                .uint(2, direction)));
    }

    /** Opens a deep link on the TV, e.g. "https://www.youtube.com". */
    public void launchApp(String link) {
        send(new Proto.Writer().message(APP_LINK_LAUNCH, new Proto.Writer().string(1, link)));
    }

    public boolean isConnected() {
        return socket != null && !closed;
    }

    public void close() {
        closed = true;
        closeQuietly();
        writer.shutdownNow();
    }

    private void readLoop() {
        String reason = "Connection lost";
        try {
            InputStream in = socket.getInputStream();
            while (!closed) {
                handle(Proto.readFramed(in));
            }
        } catch (SocketTimeoutException e) {
            reason = "The TV stopped responding";
        } catch (IOException e) {
            if (e.getMessage() != null) reason = e.getMessage();
        }
        if (!closed) {
            closed = true;
            closeQuietly();
            listener.onDisconnected(reason);
        }
    }

    private void handle(byte[] raw) throws IOException {
        Proto.Message msg = Proto.Message.parse(raw);
        Proto.Message part;
        if ((part = msg.message(CONFIGURE)) != null) {
            activeFeatures = FEATURES & part.uint(1, FEATURES);
            Proto.Message info = part.message(2);
            String vendor = info == null ? "" : info.string(2, "");
            String model = info == null ? "" : info.string(1, "");
            send(new Proto.Writer().message(CONFIGURE, new Proto.Writer()
                    .uint(1, activeFeatures)
                    .message(2, new Proto.Writer()
                            .string(1, clientName)
                            .string(2, "Rosso Remote")
                            .uint(3, 1)
                            .string(4, "1")
                            .string(5, "atvremote")
                            .string(6, "1.0.0"))));
            listener.onReady((vendor + " " + model).trim());
        } else if (msg.has(SET_ACTIVE)) {
            send(new Proto.Writer().message(SET_ACTIVE, new Proto.Writer().uint(1, activeFeatures)));
        } else if ((part = msg.message(PING_REQUEST)) != null) {
            send(new Proto.Writer().message(PING_RESPONSE, new Proto.Writer().uint(1, part.uint(1, 0))));
        } else if ((part = msg.message(START)) != null) {
            listener.onPowerChanged(part.uint(1, 0) != 0);
        } else if ((part = msg.message(SET_VOLUME_LEVEL)) != null) {
            listener.onVolumeChanged((int) part.uint(7, 0), (int) part.uint(6, 0), part.uint(8, 0) != 0);
        }
    }

    private void send(Proto.Writer message) {
        final byte[] bytes = message.toByteArray();
        final SSLSocket s = socket;
        if (s == null || closed) return;
        try {
            writer.execute(() -> {
                try {
                    Proto.writeFramed(s.getOutputStream(), bytes);
                } catch (IOException e) {
                    closeQuietly();
                }
            });
        } catch (RuntimeException ignored) {
            // Writer already shut down.
        }
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }
}
