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
import android.content.Intent;
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

public class MainActivity extends Activity implements RemoteClient.Listener, BluetoothRemote.Listener {
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
    private android.widget.FrameLayout adSlot;
    private Ads ads;

    private static final int REQUEST_BLUETOOTH = 1;
    private static final int REQUEST_DISCOVERABLE = 2;
    private BluetoothRemote bluetooth;
    private String pendingBluetoothAddress;
    private boolean pendingPairNew;

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicastLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        setContentView(buildRemote());
        ads = new Ads(this, adSlot);
        ads.start();
        net.execute(this::loadIdentity);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ads.resume();
    }

    @Override
    protected void onPause() {
        ads.pause();
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        String host = prefs.getString("host", null);
        boolean useBluetooth = prefs.getBoolean("useBluetooth", false);
        if (host == null && !useBluetooth) {
            showTvPicker();
            return;
        }
        // On Bluetooth TVs the Wi‑Fi link still opens YouTube / Netflix.
        if (host != null) connect(host);
        if (useBluetooth) startBluetooth(prefs.getString("btAddress", null), false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopDiscovery();
        net.execute(this::closeClients);
        if (bluetooth != null) {
            bluetooth.stop();
            bluetooth = null;
        }
    }

    @Override
    protected void onDestroy() {
        ads.destroy();
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
            setStatus(getString(R.string.status_key_failed, e.getMessage()));
        }
    }

    private void connect(String host) {
        setStatus(getString(R.string.status_connecting, host));
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
                setStatus(getString(R.string.status_unreachable, host));
            }
        });
    }

    /** Vestel TVs have no session: if the Smart Center port answers, we're good to go. */
    private boolean connectVestel(String host) {
        if (!VestelClient.isReachable(host)) return false;
        prefs.edit().putString("host", host).putString("kind", "vestel").apply();
        if (VestelClient.hasSmartCenter(host)) {
            dialOnly = false;
            vestel = new VestelClient(host, reason -> setStatus(getString(R.string.status_vestel_key_failed, reason)));
            setStatus(getString(R.string.status_vestel_connected, host));
            return true;
        }
        dialOnly = true;
        vestel = new VestelClient(host, reason -> setStatus(getString(R.string.status_no_response, reason)));
        dlna = DlnaClient.find(host, null, new DlnaClient.Listener() {
            @Override public void onVolume(int volume, boolean muted) {
                // This TV doesn't report its volume reliably, so remember what we set.
                if (!muted && volume > 0) prefs.edit().putInt("volume", volume).apply();
                setStatus(muted ? getString(R.string.status_muted) : volume >= 0
                        ? getString(R.string.status_volume, volume) : getString(R.string.status_sound_on));
            }
            @Override public void onSendFailed(String reason) {
                setStatus(getString(R.string.status_volume_failed, reason));
            }
        });
        if (dlna != null) dlna.setKnownVolume(prefs.getInt("volume", -1));
        setStatus(getString(dlna != null ? R.string.status_limited_with_volume : R.string.status_limited, host));
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
        setStatus(getString(R.string.status_pairing, host));
        PairingSession pairing = new PairingSession(host, identity, deviceName());
        try {
            pairing.start();
        } catch (IOException e) {
            pairing.close();
            setStatus(getString(R.string.status_pairing_not_started, e.getMessage()));
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
        input.setHint(R.string.pair_hint);
        input.setGravity(Gravity.CENTER);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.pair_title)
                .setMessage(error != null ? error : getString(R.string.pair_message))
                .setView(box)
                .setCancelable(false)
                .setPositiveButton(R.string.pair_button, (d, w) -> {
                    String code = input.getText().toString();
                    net.execute(() -> finishPairing(host, pairing, code));
                })
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    net.execute(pairing::close);
                    setStatus(getString(R.string.status_pairing_cancelled));
                })
                .show();
        input.requestFocus();
    }

    private void finishPairing(String host, PairingSession pairing, String code) {
        try {
            pairing.finish(code);
            pairing.close();
            setStatus(getString(R.string.status_paired));
            connect(host);
        } catch (PairingSession.WrongCodeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("The TV rejected")) {
                pairing.close();
                startPairing(host);
            } else {
                ui.post(() -> askForCode(host, pairing, wrongCodeMessage(e.getMessage())));
            }
        } catch (IOException e) {
            pairing.close();
            setStatus(getString(R.string.status_pairing_failed, e.getMessage()));
        }
    }

    /** The pairing errors the user can fix, in the app's language. */
    private String wrongCodeMessage(String reason) {
        if (reason != null && reason.startsWith("The code is")) return getString(R.string.pair_code_format);
        if (reason != null && reason.startsWith("That code doesn't match")) return getString(R.string.pair_code_mismatch);
        return getString(R.string.pair_try_again, reason);
    }

    private void send(int keyCode) {
        if (bluetooth != null && bluetooth.isConnected()) {
            if (!bluetooth.sendKey(keyCode)) setStatus(getString(R.string.status_no_bt_equivalent));
            return;
        }
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
                setStatus(getString(R.string.status_button_unavailable));
            }
            return;
        }
        RemoteClient r = remote;
        if (r == null) {
            setStatus(getString(R.string.status_not_connected));
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
                setStatus(getString(R.string.status_only_youtube_netflix));
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
                setStatus(getString(R.string.status_limited_button));
                return;
        }
        if (d == null) setStatus(getString(R.string.status_no_volume));
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
        setStatus(getString(R.string.status_connected_to, tvName.isEmpty() ? getString(R.string.your_tv) : tvName));
    }

    @Override
    public void onPowerChanged(boolean on) {
        if (!on) setStatus(getString(R.string.status_standby));
    }

    @Override
    public void onVolumeChanged(int level, int max, boolean muted) {
        // The buttons already give feedback on the TV itself.
    }

    @Override
    public void onDisconnected(String reason) {
        setStatus(getString(R.string.status_disconnected, reason));
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
        empty.setText(R.string.picker_searching);
        empty.setPadding(dp(24), dp(16), dp(24), dp(16));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Button useBluetooth = pill(getString(R.string.picker_use_bluetooth), BUTTON);
        useBluetooth.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams btParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        btParams.setMargins(dp(24), dp(12), dp(24), 0);
        box.addView(useBluetooth, btParams);
        box.addView(empty);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.picker_title)
                .setView(box)
                .setNeutralButton(R.string.picker_enter_ip, (d, w) -> askForIp())
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(d -> stopDiscovery())
                .show();

        useBluetooth.setOnClickListener(v -> {
            dialog.dismiss();
            startBluetooth(null, true);
        });

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
            empty.setText(R.string.picker_tap_tv);
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
                done = getString(supported ? R.string.picker_tap_tv
                        : devices.isEmpty() ? R.string.picker_nothing
                        : R.string.picker_none_supported);
            } catch (IOException e) {
                done = getString(R.string.error_no_wifi);
            }
            String message = done;
            ui.post(() -> {
                boolean nsdFound = false;
                for (Candidate r : rows) nsdFound |= r.service != null;
                empty.setText(nsdFound ? getString(R.string.picker_tap_tv) : message);
            });
        }, "tv-scan").start();
    }

    // --- Bluetooth remote ---

    /** Starts the phone's Bluetooth remote; then connects to {@code address} or offers a TV list. */
    private void startBluetooth(String address, boolean chooseTv) {
        if (Build.VERSION.SDK_INT < 28) {
            setStatus(getString(R.string.bt_needs_android9));
            return;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            String[] needed = {"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"};
            for (String permission : needed) {
                if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    pendingBluetoothAddress = address;
                    pendingPairNew = chooseTv;
                    requestPermissions(needed, REQUEST_BLUETOOTH);
                    return;
                }
            }
        }
        if (bluetooth == null) bluetooth = new BluetoothRemote(this, this);
        bluetooth.start(address);
        if (chooseTv) ui.postDelayed(this::showBluetoothTvs, 600);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_BLUETOOTH) return;
        for (int r : results) {
            if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                setStatus(getString(R.string.bt_permission_needed));
                return;
            }
        }
        startBluetooth(pendingBluetoothAddress, pendingPairNew);
    }

    private void showBluetoothTvs() {
        if (bluetooth == null || isFinishing()) return;
        List<android.bluetooth.BluetoothDevice> devices = bluetooth.pairedDevices();
        List<String> names = new ArrayList<>();
        for (android.bluetooth.BluetoothDevice d : devices) names.add(BluetoothRemote.nameOf(d));
        new AlertDialog.Builder(this)
                .setTitle(R.string.bt_title)
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    String address = devices.get(which).getAddress();
                    prefs.edit().putBoolean("useBluetooth", true).putString("btAddress", address).apply();
                    bluetooth.connect(address);
                })
                .setMessage(names.isEmpty() ? getString(R.string.bt_no_paired) : null)
                .setPositiveButton(R.string.bt_pair_new, (d, w) -> makeDiscoverable())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void makeDiscoverable() {
        Intent visible = new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivityForResult(visible, REQUEST_DISCOVERABLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DISCOVERABLE) return;
        if (resultCode == RESULT_CANCELED) {
            setStatus(getString(R.string.bt_not_visible));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.bt_pair_title)
                .setMessage(getString(R.string.bt_pair_message, deviceName()))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    @Override
    public void onBluetoothStatus(String text) {
        setStatus(text);
    }

    @Override
    public void onBluetoothConnected(String name, String address) {
        prefs.edit().putBoolean("useBluetooth", true).putString("btAddress", address).apply();
        setStatus(getString(R.string.bt_connected_to, name));
    }

    @Override
    public void onBluetoothDisconnected() {
        setStatus(getString(R.string.bt_disconnected));
    }

    private static int rank(Candidate c) {
        if (c.controllable()) return 0;
        for (String kind : c.device.kinds) if (kind.endsWith("TV")) return 1;
        return c.device.kinds.isEmpty() ? 3 : 2;
    }

    private void explainUnsupported(NetworkScanner.Device device) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.unsupported_title)
                .setMessage(getString(R.string.unsupported_message, device.ip, device.describe()))
                .setPositiveButton(R.string.check_tv, (d, w) -> inspect(device))
                .setNeutralButton(R.string.try_anyway, (d, w) -> connect(device.ip))
                .setNegativeButton(R.string.back, (d, w) -> showTvPicker())
                .show();
    }

    private void inspect(NetworkScanner.Device device) {
        TextView text = new TextView(this);
        text.setPadding(dp(24), dp(16), dp(24), dp(16));
        text.setTextIsSelectable(true);
        text.setText(getString(R.string.check_running, device.ip));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        String[] result = new String[1];
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.check_title)
                .setView(scroll)
                .setPositiveButton(R.string.copy, null)
                .setNegativeButton(R.string.close, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (result[0] == null) return;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("TV check", result[0]));
            ((Button) v).setText(R.string.copied);
        });
        new Thread(() -> {
            String report = NetworkScanner.inspect(device, percent ->
                    ui.post(() -> text.setText(getString(R.string.check_progress, device.ip, percent))));
            ui.post(() -> {
                result[0] = report;
                text.setText(report + "\n" + getString(R.string.check_done_hint));
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
        setStatus(getString(R.string.status_finding, service.getServiceName()));
        nsd.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo s, int errorCode) {
                setStatus(getString(R.string.status_resolve_failed));
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
                .setTitle(R.string.ip_title)
                .setMessage(R.string.ip_message)
                .setView(box)
                .setPositiveButton(R.string.connect, (d, w) -> {
                    String host = input.getText().toString().trim();
                    if (!host.isEmpty()) connect(host);
                })
                .setNegativeButton(R.string.cancel, null)
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
        status.setText(R.string.status_starting);
        header.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button tv = pill(getString(R.string.key_tv), BUTTON);
        tv.setOnClickListener(v -> showTvPicker());
        header.addView(tv, new LinearLayout.LayoutParams(dp(72), dp(44)));
        root.addView(header);

        root.addView(gap(16));
        LinearLayout top = row();
        top.addView(key(getString(R.string.key_power), KeyEvent.KEYCODE_POWER, ROSSO, false), weight());
        top.addView(key(getString(R.string.key_input), KeyEvent.KEYCODE_TV_INPUT, BUTTON, false), weight());
        top.addView(key(getString(R.string.key_mute), KeyEvent.KEYCODE_VOLUME_MUTE, BUTTON, false), weight());
        top.addView(key("⚙", KeyEvent.KEYCODE_SETTINGS, BUTTON, false), weight());
        root.addView(top);

        root.addView(gap(20));
        root.addView(dpad());

        root.addView(gap(20));
        LinearLayout nav = row();
        nav.addView(key(getString(R.string.key_back), KeyEvent.KEYCODE_BACK, BUTTON, false), weight());
        nav.addView(key(getString(R.string.key_home), KeyEvent.KEYCODE_HOME, BUTTON, false), weight());
        nav.addView(key(getString(R.string.key_menu), KeyEvent.KEYCODE_MENU, BUTTON, false), weight());
        root.addView(nav);

        root.addView(gap(12));
        LinearLayout rockers = row();
        rockers.addView(rocker(getString(R.string.key_vol), KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN), column());
        LinearLayout media = new LinearLayout(this);
        media.setOrientation(LinearLayout.VERTICAL);
        media.addView(key("⏯", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, BUTTON, false), fill(64));
        LinearLayout seek = row();
        seek.addView(key("⏪", KeyEvent.KEYCODE_MEDIA_REWIND, BUTTON, true), weight());
        seek.addView(key("⏩", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, BUTTON, true), weight());
        media.addView(seek, fill(64));
        rockers.addView(media, column());
        rockers.addView(rocker(getString(R.string.key_ch), KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN), column());
        root.addView(rockers);

        root.addView(gap(16));
        root.addView(label(getString(R.string.label_apps)));
        LinearLayout apps = row();
        apps.addView(app("YouTube", "https://www.youtube.com"), weight());
        apps.addView(app("Netflix", "netflix://"), weight());
        apps.addView(app("Prime", "https://app.primevideo.com"), weight());
        apps.addView(app("Disney+", "https://www.disneyplus.com"), weight());
        root.addView(apps);

        root.addView(gap(16));
        Button numbers = pill(getString(R.string.key_numbers), BUTTON);
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
        last.addView(key(getString(R.string.key_guide), KeyEvent.KEYCODE_GUIDE, BUTTON, false), weight());
        last.addView(key("0", KeyEvent.KEYCODE_0, BUTTON, false), weight());
        last.addView(key(getString(R.string.key_info), KeyEvent.KEYCODE_INFO, BUTTON, false), weight());
        numberPad.addView(last);
        root.addView(numberPad);

        root.addView(gap(12));
        TextView about = label(getString(R.string.about_link));
        about.setGravity(Gravity.CENTER);
        about.setPadding(dp(4), dp(12), dp(4), dp(12));
        about.setOnClickListener(v -> showAbout());
        root.addView(about, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);

        // The banner sits below the scrolling remote, never on top of a button.
        adSlot = new android.widget.FrameLayout(this);
        adSlot.setPadding(0, dp(8), 0, 0);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BACKGROUND);
        screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        screen.addView(adSlot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Android 15+ draws apps edge to edge: keep the remote clear of the status and navigation bars.
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        return screen;
    }

    private void showAbout() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        AlertDialog.Builder about = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name) + " " + version)
                .setMessage(R.string.about_message)
                .setPositiveButton(R.string.privacy_policy, (d, w) -> openLink(getString(R.string.privacy_url)))
                .setNegativeButton(R.string.close, null);
        if (ads.privacyOptionsRequired()) {
            about.setNeutralButton(R.string.ad_choices, (d, w) -> ads.showPrivacyOptions());
        }
        about.show();
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            setStatus(url);
        }
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
