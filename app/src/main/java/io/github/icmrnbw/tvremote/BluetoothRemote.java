package io.github.icmrnbw.tvremote;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes the phone a Bluetooth keyboard + media remote (HID device) for TVs that have no
 * network remote API but accept Bluetooth remotes and keyboards. The caller must hold
 * BLUETOOTH_CONNECT (Android 12+) before using it.
 */
@TargetApi(28)
@SuppressLint("MissingPermission")
final class BluetoothRemote {
    interface Listener {
        void onBluetoothStatus(String text);
        void onBluetoothConnected(String name, String address);
        void onBluetoothDisconnected();
    }

    private static final int KEYBOARD_REPORT = 1;
    private static final int CONSUMER_REPORT = 2;

    // Report 1: keyboard (modifiers, reserved, 6 keys). Report 2: one 16-bit consumer usage.
    private static final byte[] DESCRIPTOR = {
            0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01, (byte) 0x85, KEYBOARD_REPORT,
            0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, (byte) 0x95, 0x08, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01,
            (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x26, (byte) 0xFF, 0x00,
            0x05, 0x07, 0x19, 0x00, 0x2A, (byte) 0xFF, 0x00, (byte) 0x81, 0x00,
            (byte) 0xC0,
            0x05, 0x0C, 0x09, 0x01, (byte) 0xA1, 0x01, (byte) 0x85, CONSUMER_REPORT,
            0x15, 0x00, 0x26, (byte) 0xFF, 0x03, 0x19, 0x00, 0x2A, (byte) 0xFF, 0x03,
            0x75, 0x10, (byte) 0x95, 0x01, (byte) 0x81, 0x00,
            (byte) 0xC0,
    };

    private final Context context;
    private final Listener listener;
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private boolean registered;
    private String pendingAddress;
    private BluetoothDevice connected;

    BluetoothRemote(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** Registers the phone as a remote; connects to {@code address} if given, else waits for the TV. */
    void start(String address) {
        pendingAddress = address;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            listener.onBluetoothStatus("This phone has no Bluetooth.");
            return;
        }
        if (!adapter.isEnabled()) {
            listener.onBluetoothStatus("Turn on Bluetooth on the phone, then tap “TV” again.");
            return;
        }
        if (hid != null) {
            if (registered && address != null) connect(address);
            return;
        }
        adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                hid = (BluetoothHidDevice) proxy;
                BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                        "Rosso Remote", "Phone remote", "Rosso Remote",
                        BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
                hid.registerApp(sdp, null, null, context.getMainExecutor(), callback);
            }

            @Override
            public void onServiceDisconnected(int profile) {
                hid = null;
                registered = false;
            }
        }, BluetoothProfile.HID_DEVICE);
        listener.onBluetoothStatus("Starting Bluetooth remote…");
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice device, boolean isRegistered) {
            registered = isRegistered;
            if (!isRegistered) return;
            if (pendingAddress != null) {
                connect(pendingAddress);
            } else {
                listener.onBluetoothStatus("Bluetooth remote ready. Pair the TV with this phone.");
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connected = device;
                listener.onBluetoothConnected(nameOf(device), device.getAddress());
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && device.equals(connected)) {
                connected = null;
                listener.onBluetoothDisconnected();
            }
        }
    };

    void connect(String address) {
        pendingAddress = address;
        if (hid == null || !registered) return;
        listener.onBluetoothStatus("Connecting by Bluetooth…");
        if (!hid.connect(adapter.getRemoteDevice(address))) {
            listener.onBluetoothStatus("Couldn't start a Bluetooth connection to the TV.");
        }
    }

    List<BluetoothDevice> pairedDevices() {
        return adapter == null ? new ArrayList<>() : new ArrayList<>(adapter.getBondedDevices());
    }

    static String nameOf(BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.isEmpty() ? device.getAddress() : name;
    }

    boolean isConnected() {
        return connected != null;
    }

    /** Presses and releases the key; false if this button has no Bluetooth equivalent. */
    boolean sendKey(int keyCode) {
        if (connected == null) return false;
        int keyboard = keyboardUsage(keyCode);
        if (keyboard > 0) {
            hid.sendReport(connected, KEYBOARD_REPORT, new byte[] {0, 0, (byte) keyboard, 0, 0, 0, 0, 0});
            hid.sendReport(connected, KEYBOARD_REPORT, new byte[8]);
            return true;
        }
        int consumer = consumerUsage(keyCode);
        if (consumer > 0) {
            hid.sendReport(connected, CONSUMER_REPORT, new byte[] {(byte) consumer, (byte) (consumer >> 8)});
            hid.sendReport(connected, CONSUMER_REPORT, new byte[2]);
            return true;
        }
        return false;
    }

    void stop() {
        if (hid != null) {
            if (connected != null) hid.disconnect(connected);
            hid.unregisterApp();
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
        }
        hid = null;
        registered = false;
        connected = null;
    }

    /** USB HID keyboard page usages: what TVs reliably understand for arrows, OK and digits. */
    private static int keyboardUsage(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) return 0x1E + (keyCode - KeyEvent.KEYCODE_1);
        switch (keyCode) {
            case KeyEvent.KEYCODE_0: return 0x27;
            case KeyEvent.KEYCODE_DPAD_UP: return 0x52;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0x51;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0x50;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0x4F;
            case KeyEvent.KEYCODE_DPAD_CENTER: return 0x28; // Enter
            default: return 0;
        }
    }

    /** Consumer page usages, as Bluetooth TV remotes send them. */
    private static int consumerUsage(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_POWER: return 0x30;
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_MENU: return 0x40;
            case KeyEvent.KEYCODE_INFO: return 0x60;
            case KeyEvent.KEYCODE_GUIDE: return 0x8D;
            case KeyEvent.KEYCODE_TV_INPUT: return 0x89;
            case KeyEvent.KEYCODE_CHANNEL_UP: return 0x9C;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: return 0x9D;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return 0xB3;
            case KeyEvent.KEYCODE_MEDIA_REWIND: return 0xB4;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return 0xCD;
            case KeyEvent.KEYCODE_VOLUME_MUTE: return 0xE2;
            case KeyEvent.KEYCODE_VOLUME_UP: return 0xE9;
            case KeyEvent.KEYCODE_VOLUME_DOWN: return 0xEA;
            case KeyEvent.KEYCODE_HOME: return 0x223;
            case KeyEvent.KEYCODE_BACK: return 0x224;
            default: return 0;
        }
    }
}
