package io.github.icmrnbw.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.icmrnbw.tvremote.protocol.ClientIdentity;
import io.github.icmrnbw.tvremote.protocol.DlnaClient;
import io.github.icmrnbw.tvremote.protocol.NetworkScanner;
import io.github.icmrnbw.tvremote.protocol.PairingSession;
import io.github.icmrnbw.tvremote.protocol.RemoteClient;
import io.github.icmrnbw.tvremote.protocol.VestelClient;

public class MainActivity extends Activity implements RemoteClient.Listener {
    private static final String SERVICE_TYPE = "_androidtvremote2._tcp.";
    private static final int BACKGROUND = Color.rgb(0x12, 0x12, 0x14);
    private static final int BUTTON = Color.rgb(0x2A, 0x2A, 0x2E);
    private static final int ROSSO = Color.rgb(0xC6, 0x28, 0x28);
    private static final int TEXT = Color.rgb(0xEE, 0xEE, 0xEE);
    private static final int MUTED_TEXT = Color.rgb(0x9A, 0x9A, 0xA0);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private ClientIdentity identity;
    private volatile RemoteClient remote;
    private volatile VestelClient vestel;
    // TVs whose port 56789 is only DIAL: apps via DIAL, volume via DLNA, nothing else.
    private volatile boolean dialOnly;
    private volatile DlnaClient dlna;
    private TextView status;
    private LinearLayout numberPad;

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicastLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        setContentView(buildRemote());
        net.execute(this::loadIdentity);
    }

    @Override
    protected void onStart() {
        super.onStart();
        String host = prefs.getString("host", null);
        if (host == null) {
            showTvPicker();
        } else {
            connect(host);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopDiscovery();
        net.execute(this::closeClients);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.shutdown();
    }

    // --- Connection ---

    private void loadIdentity() {
        try {
            String key = prefs.getString("key", null);
            String cert = prefs.getString("cert", null);
            if (key != null && cert != null) {
                identity = ClientIdentity.load(Base64.decode(key, Base64.NO_WRAP), Base64.decode(cert, Base64.NO_WRAP));
                return;
            }
            identity = ClientIdentity.generate("atvremote");
            prefs.edit()
                    .putString("key", Base64.encodeToString(identity.encodedKey(), Base64.NO_WRAP))
                    .putString("cert", Base64.encodeToString(identity.encodedCertificate(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            setStatus("Couldn't create this phone's security key: " + e.getMessage());
        }
    }

    private void connect(String host) {
        setStatus("Connecting to " + host + "…");
        net.execute(() -> {
            closeClients();
            boolean knownVestel = host.equals(prefs.getString("host", null))
                    && "vestel".equals(prefs.getString("kind", null));
            if (knownVestel && connectVestel(host)) return;
            if (identity == null) return;
            RemoteClient client = new RemoteClient(host, identity, deviceName(), this);
            try {
                client.connect();
                remote = client;
                prefs.edit().putString("host", host).putString("kind", "atv").apply();
            } catch (RemoteClient.NotPairedException e) {
                startPairing(host);
            } catch (IOException e) {
                // Not an Android TV; maybe a Vestel-made Smart TV.
                if (connectVestel(host)) return;
                setStatus("Can't reach the TV at " + host + ". Check that it's on and on the same Wi‑Fi. Tap “TV” to pick another.");
            }
        });
    }

    /** Vestel TVs have no session: if the Smart Center port answers, we're good to go. */
    private boolean connectVestel(String host) {
        if (!VestelClient.isReachable(host)) return false;
        prefs.edit().putString("host", host).putString("kind", "vestel").apply();
        if (VestelClient.hasSmartCenter(host)) {
            dialOnly = false;
            vestel = new VestelClient(host, reason -> setStatus("The TV didn't take that button (" + reason
                    + "). Check that Virtual Remote is turned on in the TV's settings."));
            setStatus("Connected to the Smart TV at " + host
                    + ". If buttons do nothing, turn on “Virtual Remote” in the TV's settings.");
            return true;
        }
        dialOnly = true;
        vestel = new VestelClient(host, reason -> setStatus("The TV didn't respond (" + reason + ")."));
        dlna = DlnaClient.find(host, null, new DlnaClient.Listener() {
            @Override public void onVolume(int volume, boolean muted) {
                setStatus(muted ? "Muted" : "Volume " + volume);
            }
            @Override public void onSendFailed(String reason) {
                setStatus("The TV didn't change the volume (" + reason + ").");
            }
        });
        setStatus("Connected to the Smart TV at " + host + ". This TV lets the app change "
                + (dlna != null ? "the volume, " : "") + "open YouTube and Netflix, and close them with Home or Back.");
        return true;
    }

    private void closeClients() {
        RemoteClient r = remote;
        remote = null;
        if (r != null) r.close();
        VestelClient v = vestel;
        vestel = null;
        if (v != null) v.close();
        DlnaClient d = dlna;
        dlna = null;
        if (d != null) d.close();
    }

    private void startPairing(String host) {
        setStatus("Pairing with " + host + "… look at the TV for a code.");
        PairingSession pairing = new PairingSession(host, identity, deviceName());
        try {
            pairing.start();
        } catch (IOException e) {
            pairing.close();
            setStatus("The TV didn't start pairing: " + e.getMessage());
            return;
        }
        ui.post(() -> askForCode(host, pairing, null));
    }

    private void askForCode(String host, PairingSession pairing, String error) {
        if (isFinishing()) {
            pairing.close();
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(6), new InputFilter.AllCaps()});
        input.setHint("e.g. 4F2A9C");
        input.setGravity(Gravity.CENTER);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Enter the code on your TV")
                .setMessage(error != null ? error : "Your TV is showing a 6 character code.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Pair", (d, w) -> {
                    String code = input.getText().toString();
                    net.execute(() -> finishPairing(host, pairing, code));
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    net.execute(pairing::close);
                    setStatus("Pairing cancelled. Tap “TV” to try again.");
                })
                .show();
        input.requestFocus();
    }

    private void finishPairing(String host, PairingSession pairing, String code) {
        try {
            pairing.finish(code);
            pairing.close();
            setStatus("Paired! Connecting…");
            connect(host);
        } catch (PairingSession.WrongCodeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("The TV rejected")) {
                pairing.close();
                startPairing(host);
            } else {
                ui.post(() -> askForCode(host, pairing, e.getMessage() + ". Try again."));
            }
        } catch (IOException e) {
            pairing.close();
            setStatus("Pairing failed: " + e.getMessage());
        }
    }

    private void send(int keyCode) {
        VestelClient v = vestel;
        if (v != null && dialOnly) {
            sendLimited(v, keyCode);
            return;
        }
        if (v != null) {
            int code = vestelCode(keyCode);
            if (code > 0) {
                v.sendKey(code);
            } else {
                setStatus("That button isn't available on this TV.");
            }
            return;
        }
        RemoteClient r = remote;
        if (r == null) {
            setStatus("Not connected. Tap “TV” to connect.");
            return;
        }
        r.sendKey(keyCode);
    }

    private void launch(String link) {
        VestelClient v = vestel;
        if (v != null) {
            if (link.contains("youtube")) {
                v.launchApp("YouTube");
            } else if (link.contains("netflix")) {
                v.launchApp("Netflix");
            } else {
                setStatus("This TV can only open YouTube and Netflix from the app.");
            }
            return;
        }
        RemoteClient r = remote;
        if (r != null) r.launchApp(link);
    }

    private void sendLimited(VestelClient v, int keyCode) {
        DlnaClient d = dlna;
        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_BACK:
                v.stopApps();
                return;
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (d != null) d.volumeUp();
                break;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (d != null) d.volumeDown();
                break;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (d != null) d.toggleMute();
                break;
            default:
                setStatus("This TV doesn't let apps press that button yet. Volume, YouTube, Netflix, Home and Back work.");
                return;
        }
        if (d == null) setStatus("This TV doesn't let apps change the volume.");
    }

    /** Android key codes to the codes Vestel's virtual remote uses; -1 if there's none. */
    private static int vestelCode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return VestelClient.DIGIT_0 + (keyCode - KeyEvent.KEYCODE_0);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_POWER: return VestelClient.POWER;
            case KeyEvent.KEYCODE_TV_INPUT: return VestelClient.SOURCE;
            case KeyEvent.KEYCODE_VOLUME_MUTE: return VestelClient.MUTE;
            case KeyEvent.KEYCODE_SETTINGS: return VestelClient.MENU;
            case KeyEvent.KEYCODE_DPAD_UP: return VestelClient.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return VestelClient.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return VestelClient.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return VestelClient.RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER: return VestelClient.OK;
            case KeyEvent.KEYCODE_BACK: return VestelClient.BACK;
            case KeyEvent.KEYCODE_HOME: return VestelClient.HOME;
            case KeyEvent.KEYCODE_MENU: return VestelClient.QUICK_MENU;
            case KeyEvent.KEYCODE_VOLUME_UP: return VestelClient.VOLUME_UP;
            case KeyEvent.KEYCODE_VOLUME_DOWN: return VestelClient.VOLUME_DOWN;
            case KeyEvent.KEYCODE_CHANNEL_UP: return VestelClient.CHANNEL_UP;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: return VestelClient.CHANNEL_DOWN;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return VestelClient.PLAY;
            case KeyEvent.KEYCODE_MEDIA_REWIND: return VestelClient.REWIND;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return VestelClient.FORWARD;
            case KeyEvent.KEYCODE_GUIDE: return VestelClient.EPG;
            case KeyEvent.KEYCODE_INFO: return VestelClient.INFO;
            default: return -1;
        }
    }

    private static String deviceName() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    @Override
    public void onReady(String tvName) {
        prefs.edit().putString("tvName", tvName).apply();
        setStatus("Connected to " + (tvName.isEmpty() ? "your TV" : tvName));
    }

    @Override
    public void onPowerChanged(boolean on) {
        if (!on) setStatus("TV is on standby. Press power to wake it.");
    }

    @Override
    public void onVolumeChanged(int level, int max, boolean muted) {
        // The buttons already give feedback on the TV itself.
    }

    @Override
    public void onDisconnected(String reason) {
        setStatus("Disconnected: " + reason + ". Tap “TV” to reconnect.");
        remote = null;
    }

    private void setStatus(String text) {
        ui.post(() -> status.setText(text));
    }

    // --- Finding TVs on the network ---

    /** One row in the TV list: found by mDNS (service) or by the network scan (device). */
    private static final class Candidate {
        NsdServiceInfo service;
        NetworkScanner.Device device;

        String key() {
            return service != null ? "nsd:" + service.getServiceName() : device.ip;
        }

        boolean controllable() {
            return service != null || device.isAndroidTv() || device.isVestel();
        }

        @Override
        public String toString() {
            if (service != null) return service.getServiceName() + "\nAndroid TV / Google TV";
            return device.ip + "\n" + device.describe();
        }
    }

    private void showTvPicker() {
        List<Candidate> rows = new ArrayList<>();
        ArrayAdapter<Candidate> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        TextView empty = new TextView(this);
        empty.setText("Searching your Wi‑Fi for TVs… (takes about 15 seconds)");
        empty.setPadding(dp(24), dp(16), dp(24), dp(16));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(empty);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Choose your TV")
                .setView(box)
                .setNeutralButton("Enter IP address", (d, w) -> askForIp())
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> stopDiscovery())
                .show();

        list.setOnItemClickListener((parent, view, position, id) -> {
            Candidate c = rows.get(position);
            dialog.dismiss();
            if (c.service != null) {
                resolveAndConnect(c.service);
            } else if (c.device.isAndroidTv() || c.device.isVestel()) {
                connect(c.device.ip);
            } else {
                explainUnsupported(c.device);
            }
        });

        // Android TVs first, then other TVs, then anything else that answered.
        Runnable sort = () -> {
            Collections.sort(rows, (a, b) -> rank(a) - rank(b));
            adapter.notifyDataSetChanged();
        };
        startDiscovery(service -> {
            Candidate c = new Candidate();
            c.service = service;
            for (Candidate r : rows) if (r.key().equals(c.key())) return;
            rows.add(c);
            sort.run();
            empty.setText("Tap your TV:");
        });

        new Thread(() -> {
            NetworkScanner.Progress progress = device -> ui.post(() -> {
                for (Candidate r : rows) {
                    if (r.device == device) {
                        sort.run();
                        return;
                    }
                }
                Candidate c = new Candidate();
                c.device = device;
                rows.add(c);
                sort.run();
            });
            String done;
            try {
                List<NetworkScanner.Device> devices = NetworkScanner.scan(progress);
                boolean supported = false;
                for (NetworkScanner.Device d : devices) supported |= d.isAndroidTv() || d.isVestel();
                done = supported ? "Tap your TV:"
                        : devices.isEmpty() ? "Nothing answered on this Wi‑Fi. Is the TV on and on the same network?"
                        : "No supported TV found. Tap a device to see what it is.";
            } catch (IOException e) {
                done = e.getMessage();
            }
            String message = done;
            ui.post(() -> {
                boolean nsdFound = false;
                for (Candidate r : rows) nsdFound |= r.service != null;
                empty.setText(nsdFound ? "Tap your TV:" : message);
            });
        }, "tv-scan").start();
    }

    private static int rank(Candidate c) {
        if (c.controllable()) return 0;
        for (String kind : c.device.kinds) if (kind.endsWith("TV")) return 1;
        return c.device.kinds.isEmpty() ? 3 : 2;
    }

    private void explainUnsupported(NetworkScanner.Device device) {
        new AlertDialog.Builder(this)
                .setTitle("Not an Android TV")
                .setMessage("This device at " + device.ip + " looks like:\n\n" + device.describe()
                        + "\n\nThis app controls Android TV / Google TV only. If this is your TV, "
                        + "tap “Check this TV” and send the result to Claude so the app can be adapted to it.")
                .setPositiveButton("Check this TV", (d, w) -> inspect(device))
                .setNeutralButton("Try anyway", (d, w) -> connect(device.ip))
                .setNegativeButton("Back", (d, w) -> showTvPicker())
                .show();
    }

    private void inspect(NetworkScanner.Device device) {
        TextView text = new TextView(this);
        text.setPadding(dp(24), dp(16), dp(24), dp(16));
        text.setTextIsSelectable(true);
        text.setText("Checking " + device.ip + "… this takes up to a minute. Keep the app open.");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        String[] result = new String[1];
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("TV check")
                .setView(scroll)
                .setPositiveButton("Copy", null)
                .setNegativeButton("Close", null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (result[0] == null) return;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("TV check", result[0]));
            ((Button) v).setText("Copied");
        });
        new Thread(() -> {
            String report = NetworkScanner.inspect(device, percent ->
                    ui.post(() -> text.setText("Checking " + device.ip + "… " + percent + "%")));
            ui.post(() -> {
                result[0] = report;
                text.setText(report + "\nTap Copy and paste this to Claude.");
            });
        }, "tv-inspect").start();
    }

    private interface Found {
        void onFound(NsdServiceInfo service);
    }

    private void startDiscovery(Found found) {
        stopDiscovery();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("tv-discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int code) {}
            @Override public void onStopDiscoveryFailed(String type, int code) {}
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onServiceFound(NsdServiceInfo service) { ui.post(() -> found.onFound(service)); }
            @Override public void onServiceLost(NsdServiceInfo service) {}
        };
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    private void stopDiscovery() {
        if (discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (IllegalArgumentException ignored) {
            }
            discovery = null;
        }
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }

    @SuppressWarnings("deprecation")
    private void resolveAndConnect(NsdServiceInfo service) {
        setStatus("Finding " + service.getServiceName() + "…");
        nsd.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo s, int errorCode) {
                setStatus("Couldn't find that TV's address. Try entering its IP address.");
            }

            @Override
            public void onServiceResolved(NsdServiceInfo s) {
                ui.post(() -> connect(s.getHost().getHostAddress()));
            }
        });
    }

    private void askForIp() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("192.168.1.50");
        input.setText(prefs.getString("host", ""));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("TV's IP address")
                .setMessage("Find it on the TV under Settings → Network & Internet → your Wi‑Fi.")
                .setView(box)
                .setPositiveButton("Connect", (d, w) -> {
                    String host = input.getText().toString().trim();
                    if (!host.isEmpty()) connect(host);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- Remote layout ---

    private View buildRemote() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));

        LinearLayout header = row();
        status = new TextView(this);
        status.setTextColor(MUTED_TEXT);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setText("Starting…");
        header.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button tv = pill("TV", BUTTON);
        tv.setOnClickListener(v -> showTvPicker());
        header.addView(tv, new LinearLayout.LayoutParams(dp(72), dp(44)));
        root.addView(header);

        root.addView(gap(16));
        LinearLayout top = row();
        top.addView(key("Power", KeyEvent.KEYCODE_POWER, ROSSO, false), weight());
        top.addView(key("Input", KeyEvent.KEYCODE_TV_INPUT, BUTTON, false), weight());
        top.addView(key("Mute", KeyEvent.KEYCODE_VOLUME_MUTE, BUTTON, false), weight());
        top.addView(key("⚙", KeyEvent.KEYCODE_SETTINGS, BUTTON, false), weight());
        root.addView(top);

        root.addView(gap(20));
        root.addView(dpad());

        root.addView(gap(20));
        LinearLayout nav = row();
        nav.addView(key("↩ Back", KeyEvent.KEYCODE_BACK, BUTTON, false), weight());
        nav.addView(key("⌂ Home", KeyEvent.KEYCODE_HOME, BUTTON, false), weight());
        nav.addView(key("☰ Menu", KeyEvent.KEYCODE_MENU, BUTTON, false), weight());
        root.addView(nav);

        root.addView(gap(12));
        LinearLayout rockers = row();
        rockers.addView(rocker("VOL", KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN), column());
        LinearLayout media = new LinearLayout(this);
        media.setOrientation(LinearLayout.VERTICAL);
        media.addView(key("⏯", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, BUTTON, false), fill(64));
        LinearLayout seek = row();
        seek.addView(key("⏪", KeyEvent.KEYCODE_MEDIA_REWIND, BUTTON, true), weight());
        seek.addView(key("⏩", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, BUTTON, true), weight());
        media.addView(seek, fill(64));
        rockers.addView(media, column());
        rockers.addView(rocker("CH", KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN), column());
        root.addView(rockers);

        root.addView(gap(16));
        root.addView(label("Apps"));
        LinearLayout apps = row();
        apps.addView(app("YouTube", "https://www.youtube.com"), weight());
        apps.addView(app("Netflix", "netflix://"), weight());
        apps.addView(app("Prime", "https://app.primevideo.com"), weight());
        apps.addView(app("Disney+", "https://www.disneyplus.com"), weight());
        root.addView(apps);

        root.addView(gap(16));
        Button numbers = pill("123  Number pad", BUTTON);
        numbers.setOnClickListener(v -> numberPad.setVisibility(
                numberPad.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        root.addView(numbers, fill(48));
        numberPad = new LinearLayout(this);
        numberPad.setOrientation(LinearLayout.VERTICAL);
        numberPad.setVisibility(View.GONE);
        for (int r = 0; r < 3; r++) {
            LinearLayout line = row();
            for (int c = 1; c <= 3; c++) {
                int n = r * 3 + c;
                line.addView(key(String.valueOf(n), KeyEvent.KEYCODE_0 + n, BUTTON, false), weight());
            }
            numberPad.addView(line);
        }
        LinearLayout last = row();
        last.addView(key("Guide", KeyEvent.KEYCODE_GUIDE, BUTTON, false), weight());
        last.addView(key("0", KeyEvent.KEYCODE_0, BUTTON, false), weight());
        last.addView(key("Info", KeyEvent.KEYCODE_INFO, BUTTON, false), weight());
        numberPad.addView(last);
        root.addView(numberPad);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private View dpad() {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER_HORIZONTAL);
        int size = dp(76);
        LinearLayout up = row();
        up.setGravity(Gravity.CENTER);
        up.addView(key("▲", KeyEvent.KEYCODE_DPAD_UP, BUTTON, true), new LinearLayout.LayoutParams(size, size));
        LinearLayout middle = row();
        middle.setGravity(Gravity.CENTER);
        middle.addView(key("◀", KeyEvent.KEYCODE_DPAD_LEFT, BUTTON, true), new LinearLayout.LayoutParams(size, size));
        Button ok = key("OK", KeyEvent.KEYCODE_DPAD_CENTER, ROSSO, false);
        ok.setBackground(round(ROSSO, size / 2f));
        middle.addView(ok, new LinearLayout.LayoutParams(size, size));
        middle.addView(key("▶", KeyEvent.KEYCODE_DPAD_RIGHT, BUTTON, true), new LinearLayout.LayoutParams(size, size));
        LinearLayout down = row();
        down.setGravity(Gravity.CENTER);
        down.addView(key("▼", KeyEvent.KEYCODE_DPAD_DOWN, BUTTON, true), new LinearLayout.LayoutParams(size, size));
        pad.addView(up);
        pad.addView(middle);
        pad.addView(down);
        return pad;
    }

    private View rocker(String name, int upKey, int downKey) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(key("＋", upKey, BUTTON, true), fill(64));
        TextView text = label(name);
        text.setGravity(Gravity.CENTER);
        column.addView(text);
        column.addView(key("－", downKey, BUTTON, true), fill(64));
        return column;
    }

    /** A remote button. Repeating buttons keep sending while held, like a real remote. */
    private Button key(String text, int keyCode, int color, boolean repeats) {
        Button b = pill(text, color);
        if (!repeats) {
            b.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                send(keyCode);
            });
            return b;
        }
        Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            send(keyCode);
            ui.postDelayed(repeat[0], 120);
        };
        b.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    send(keyCode);
                    ui.postDelayed(repeat[0], 450);
                    return true;
                case MotionEvent.ACTION_UP:
                    v.performClick();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    ui.removeCallbacks(repeat[0]);
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    private Button app(String name, String link) {
        Button b = pill(name, BUTTON);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            launch(link);
        });
        return b;
    }

    private Button pill(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(color, dp(14)));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(MUTED_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        return p;
    }

    private LinearLayout.LayoutParams column() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout.LayoutParams fill(int heightDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        return p;
    }

    private View gap(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
