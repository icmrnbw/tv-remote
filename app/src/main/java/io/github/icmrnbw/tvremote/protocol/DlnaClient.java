package io.github.icmrnbw.tvremote.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Volume and mute through the TV's DLNA media renderer (UPnP RenderingControl). Works on
 * TVs that have no remote-control API but can play media pushed from a phone.
 */
public final class DlnaClient {
    private static final String RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";
    // Where these renderers usually describe themselves when SSDP isn't available.
    private static final int[] DESCRIPTION_PORTS = {2870, 2875, 49152, 1400};

    public interface Listener {
        /** volume is -1 when unknown. */
        void onVolume(int volume, boolean muted);
        void onSendFailed(String reason);
    }

    private final String controlUrl;
    private final Listener listener;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private int lastSet = -1;
    private long lastSetAt;
    private String lastReply = "";

    private DlnaClient(String controlUrl, Listener listener) {
        this.controlUrl = controlUrl;
        this.listener = listener;
    }

    /**
     * Finds the renderer's volume control. {@code descriptionUrl} is the SSDP location if
     * known, else null to try the usual ports. Returns null if the TV has none. Blocks.
     */
    public static DlnaClient find(String host, String descriptionUrl, Listener listener) {
        if (descriptionUrl != null) {
            String control = controlUrlFrom(descriptionUrl);
            if (control != null) return new DlnaClient(control, listener);
        }
        for (int port : DESCRIPTION_PORTS) {
            String control = controlUrlFrom("http://" + host + ":" + port + "/dmr.xml");
            if (control == null) control = controlUrlFrom("http://" + host + ":" + port + "/description.xml");
            if (control != null) return new DlnaClient(control, listener);
        }
        return null;
    }

    static String controlUrlFrom(String descriptionUrl) {
        try {
            String xml = http("GET", descriptionUrl, null, null);
            return controlUrlIn(xml, descriptionUrl);
        } catch (IOException e) {
            return null;
        }
    }

    static String controlUrlIn(String xml, String descriptionUrl) throws IOException {
        Matcher service = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL).matcher(xml);
        while (service.find()) {
            String block = service.group(1);
            if (!block.contains(RENDERING_CONTROL)) continue;
            Matcher control = Pattern.compile("<controlURL>\\s*([^<]+?)\\s*</controlURL>").matcher(block);
            if (control.find()) return new URL(new URL(descriptionUrl), control.group(1)).toString();
        }
        return null;
    }

    public void volumeUp() {
        changeVolume(+2);
    }

    public void volumeDown() {
        changeVolume(-2);
    }

    public void toggleMute() {
        run(() -> {
            String current = value(soap("GetMute", ""), "CurrentMute");
            boolean muted = "1".equals(current) || "true".equalsIgnoreCase(current);
            soap("SetMute", "<DesiredMute>" + (muted ? 0 : 1) + "</DesiredMute>");
            listener.onVolume(lastSet, !muted);
        });
    }

    /** The level we last set, e.g. saved from an earlier session; -1 if unknown. */
    public void setKnownVolume(int volume) {
        run(() -> lastSet = volume);
    }

    public void close() {
        sender.shutdownNow();
    }

    private void changeVolume(int step) {
        run(() -> {
            Integer reported = readVolume();
            // Some renderers report a stale level after a change, or 0 whenever they're idle
            // (the user's Rosso does). Trust our own last setting for quick repeated presses,
            // and whenever the TV claims 0.
            boolean recent = lastSet >= 0 && System.currentTimeMillis() - lastSetAt < 10_000;
            int base;
            if (recent || (lastSet >= 0 && (reported == null || reported == 0))) {
                base = lastSet;
            } else if (reported != null) {
                base = reported;
            } else {
                throw new IOException("couldn't read the volume, the TV said: " + lastReply);
            }
            int volume = Math.max(0, Math.min(100, base + step));
            soap("SetVolume", "<DesiredVolume>" + volume + "</DesiredVolume>");
            lastSet = volume;
            lastSetAt = System.currentTimeMillis();
            listener.onVolume(volume, false);
        });
    }

    /** The level the TV reports, or null if its answer has no readable number. */
    private Integer readVolume() throws IOException {
        String reply = soap("GetVolume", "");
        lastReply = reply.length() > 300 ? reply.substring(0, 300) : reply;
        String v = value(reply, "CurrentVolume");
        try {
            return v == null ? null : Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private interface Action {
        void run() throws IOException;
    }

    private void run(Action action) {
        try {
            sender.execute(() -> {
                try {
                    action.run();
                } catch (IOException e) {
                    listener.onSendFailed(e.getMessage() != null ? e.getMessage() : "No answer from the TV");
                }
            });
        } catch (RuntimeException ignored) {
            // Already closed.
        }
    }

    private String soap(String action, String arguments) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + RENDERING_CONTROL + "\">"
                + "<InstanceID>0</InstanceID><Channel>Master</Channel>" + arguments
                + "</u:" + action + "></s:Body></s:Envelope>";
        return http("POST", controlUrl, body, "\"" + RENDERING_CONTROL + "#" + action + "\"");
    }

    private static String value(String xml, String tag) {
        // The tag may carry a namespace prefix and attributes, e.g. <CurrentVolume dt:dt="ui2">.
        Matcher m = Pattern.compile("<(?:[\\w.-]+:)?" + tag + "(?:\\s[^>]*)?>([^<]*)<").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String http(String method, String url, String body, String soapAction) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod(method);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                c.setRequestProperty("SOAPACTION", soapAction);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            if (status >= 400) throw new IOException("The TV answered " + status);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
