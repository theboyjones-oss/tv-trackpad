package com.example.tvtrackpad;   // <-- change this to match your project's package name

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ArrayAdapter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Loads assets/index.html in a WebView and turns the phone into a Bluetooth
 * keyboard + mouse (HID device) that the Google TV pairs with like any accessory.
 * The page calls window.Android.mouse/key/consumer/connect/disconnect/ready.
 */
@SuppressLint({"MissingPermission", "SetJavaScriptEnabled"})
public class MainActivity extends Activity {

    private static final int ID_KEYBOARD = 1, ID_MOUSE = 2, ID_CONSUMER = 3;

    // Composite HID report descriptor: keyboard (id 1), mouse w/ wheel + pan (id 2), media keys (id 3)
    private static final byte[] DESCRIPTOR = bytes(
        // Keyboard
        0x05,0x01, 0x09,0x06, 0xA1,0x01, 0x85,ID_KEYBOARD,
        0x05,0x07, 0x19,0xE0, 0x29,0xE7, 0x15,0x00, 0x25,0x01, 0x75,0x01, 0x95,0x08, 0x81,0x02,
        0x95,0x01, 0x75,0x08, 0x81,0x01,
        0x95,0x05, 0x75,0x01, 0x05,0x08, 0x19,0x01, 0x29,0x05, 0x91,0x02,   // LED output report
        0x95,0x01, 0x75,0x03, 0x91,0x03,
        0x95,0x06, 0x75,0x08, 0x15,0x00, 0x26,0xFF,0x00, 0x05,0x07, 0x19,0x00, 0x2A,0xFF,0x00, 0x81,0x00,
        0xC0,
        // Mouse
        0x05,0x01, 0x09,0x02, 0xA1,0x01, 0x85,ID_MOUSE, 0x09,0x01, 0xA1,0x00,
        0x05,0x09, 0x19,0x01, 0x29,0x03, 0x15,0x00, 0x25,0x01, 0x95,0x03, 0x75,0x01, 0x81,0x02,
        0x95,0x01, 0x75,0x05, 0x81,0x03,
        0x05,0x01, 0x09,0x30, 0x09,0x31, 0x09,0x38, 0x15,0x81, 0x25,0x7F, 0x75,0x08, 0x95,0x03, 0x81,0x06,
        0x05,0x0C, 0x0A,0x38,0x02, 0x15,0x81, 0x25,0x7F, 0x75,0x08, 0x95,0x01, 0x81,0x06,
        0xC0, 0xC0,
        // Consumer control (Home, Back, volume, play/pause…)
        0x05,0x0C, 0x09,0x01, 0xA1,0x01, 0x85,ID_CONSUMER,
        0x15,0x00, 0x26,0xFF,0x03, 0x19,0x00, 0x2A,0xFF,0x03, 0x75,0x10, 0x95,0x01, 0x81,0x00,
        0xC0
    );

    private WebView web;
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private BluetoothDevice pendingBond;
    private boolean proxyRequested;
    private SharedPreferences prefs;
    private String lastStatus = "Starting…";
    private boolean lastConnected = false;
    private final Executor exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // auto-reconnect when the TV drops us without the user asking
    private BluetoothDevice wanted;          // device the user wants to stay connected to
    private boolean userDisconnect;
    private int retries;
    private long connectedAt;
    private static final int MAX_RETRIES = 4;

    private final List<BluetoothDevice> scanDevices = new ArrayList<>();
    private ArrayAdapter<String> scanList;
    private AlertDialog scanDialog;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);   // the page can switch this off in its settings
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        prefs = getSharedPreferences("tvpad", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, f);

        List<String> ask = new ArrayList<>(java.util.Arrays.asList(missingPermissions()));
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ask.add(Manifest.permission.POST_NOTIFICATIONS);   // optional: shows the "connected" notification
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), 1);
        if (missingPermissions().length == 0) startHid();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (missingPermissions().length == 0) startHid();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (missingPermissions().length == 0) startHid();
        else status("Bluetooth permission is needed – allow it in Settings", false);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        stopService(new Intent(this, KeepAliveService.class));
        if (hid != null) {
            try { if (host != null) hid.disconnect(host); hid.unregisterApp(); } catch (Exception ignored) {}
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
        }
        super.onDestroy();
    }

    private String[] missingPermissions() {
        String[] need = Build.VERSION.SDK_INT >= 31
            ? new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        List<String> out = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) out.add(p);
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------ HID setup

    private void startHid() {
        if (adapter == null) { status("This phone has no Bluetooth", false); return; }
        if (!adapter.isEnabled()) {
            status("Turn Bluetooth on", false);
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        if (proxyRequested) return;
        proxyRequested = true;
        status("Setting up Bluetooth…", false);

        // Foreground service keeps the app (and the TV connection) alive while the screen is off
        try { startForegroundService(new Intent(this, KeepAliveService.class)); }
        catch (Exception e) { /* not allowed right now – the remote still works while open */ }

        boolean ok = adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                hid = (BluetoothHidDevice) proxy;
                BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "TV Trackpad", "Phone trackpad and keyboard", "TV Trackpad",
                    BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
                BluetoothHidDeviceAppQosSettings qos = new BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250,
                    BluetoothHidDeviceAppQosSettings.MAX);
                hid.registerApp(sdp, null, qos, exec, callback);
            }
            @Override public void onServiceDisconnected(int profile) {
                hid = null; host = null; proxyRequested = false;
                status("Bluetooth service stopped", false);
            }
        }, BluetoothProfile.HID_DEVICE);
        if (!ok) status("This phone doesn't support Bluetooth keyboard/mouse mode", false);
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean registered) {
            if (!registered) { status("Not registered – reopen the app", false); return; }
            status("Ready – tap Connect", false);
            BluetoothDevice target = plugged;
            if (target == null) {
                String addr = prefs.getString("last", null);
                if (addr != null && BluetoothAdapter.checkBluetoothAddress(addr)) target = adapter.getRemoteDevice(addr);
            }
            if (target != null && target.getBondState() == BluetoothDevice.BOND_BONDED) {
                status("Reconnecting to " + name(target) + "…", false);
                wanted = target; userDisconnect = false;
                hid.connect(target);
            }
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                wanted = device;
                connectedAt = SystemClock.elapsedRealtime();
                prefs.edit().putString("last", device.getAddress()).apply();
                status("Connected to " + name(device), true);
                // count the link as stable once it has stayed up for a while
                ui.postDelayed(() -> { if (host != null && SystemClock.elapsedRealtime() - connectedAt >= 9000) retries = 0; }, 10000);
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status("Connecting to " + name(device) + "…", false);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (device.equals(host)) host = null;
                if (host != null) return;
                if (!userDisconnect && device.equals(wanted) && retries < MAX_RETRIES) {
                    retries++;
                    status("Connection dropped – reconnecting (" + retries + "/" + MAX_RETRIES + ")…", false);
                    ui.postDelayed(() -> {
                        if (hid != null && host == null && device.equals(wanted) && !userDisconnect) hid.connect(device);
                    }, 1200L * retries);
                } else if (!userDisconnect && device.equals(wanted)) {
                    status("Keeps dropping – on the TV remove TV Trackpad from Remotes & Accessories, unpair the TV on this phone, then pair again", false);
                } else {
                    status("Disconnected – tap Connect", false);
                }
            }
        }

        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && (id == ID_KEYBOARD || id == ID_MOUSE || id == ID_CONSUMER)) {
                int len = id == ID_KEYBOARD ? 8 : id == ID_MOUSE ? 5 : 2;
                hid.replyReport(device, type, id, new byte[len]);
            } else if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id == ID_KEYBOARD) {
                hid.replyReport(device, type, id, new byte[1]);
            } else {
                hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            }
        }

        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            hid.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
        }

        @Override public void onSetProtocol(BluetoothDevice device, byte protocol) { /* report mode only; accept */ }

        @Override public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) { /* keyboard LEDs – ignore */ }
    };

    private void connectTo(BluetoothDevice d) {
        if (hid == null) { status("Bluetooth not ready yet", false); return; }
        if (adapter.isDiscovering()) adapter.cancelDiscovery();   // scanning makes new links flaky
        if (d.equals(host)) { status("Connected to " + name(d), true); return; }
        wanted = d; userDisconnect = false; retries = 0;
        if (host != null) hid.disconnect(host);
        if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
            status("Connecting to " + name(d) + "…", false);
            hid.connect(d);
        } else {
            pendingBond = d;
            status("Pairing with " + name(d) + " – accept on the TV", false);
            d.createBond();
        }
    }

    // ------------------------------------------------------------ device picker

    private void showPicker() {
        if (adapter == null || !adapter.isEnabled()) { startHid(); return; }
        final List<BluetoothDevice> bonded = new ArrayList<>(adapter.getBondedDevices());
        List<String> labels = new ArrayList<>();
        for (BluetoothDevice d : bonded) labels.add(name(d));
        labels.add("🔍  Search for my TV…");
        labels.add("📡  Make phone visible (pair from the TV)");

        new AlertDialog.Builder(this)
            .setTitle("Connect to TV")
            .setItems(labels.toArray(new String[0]), (dlg, which) -> {
                if (which < bonded.size()) connectTo(bonded.get(which));
                else if (which == bonded.size()) startScan();
                else makeDiscoverable();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void makeDiscoverable() {
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180);
        startActivity(i);
        status("On the TV: Settings › Remotes & Accessories › Pair accessory", false);
    }

    private void startScan() {
        scanDevices.clear();
        scanList = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        scanDialog = new AlertDialog.Builder(this)
            .setTitle("Searching… (TV must be on)")
            .setAdapter(scanList, (dlg, which) -> {
                adapter.cancelDiscovery();
                connectTo(scanDevices.get(which));
            })
            .setNegativeButton("Stop", (dlg, w) -> adapter.cancelDiscovery())
            .show();
        if (adapter.isDiscovering()) adapter.cancelDiscovery();
        adapter.startDiscovery();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            switch (a) {
                case BluetoothDevice.ACTION_FOUND: {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null || scanDevices.contains(d) || scanList == null) return;
                    scanDevices.add(d);
                    scanList.add(name(d));
                    break;
                }
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    if (scanDialog != null && scanDialog.isShowing())
                        scanDialog.setTitle(scanDevices.isEmpty() ? "Nothing found – try 'Make phone visible'" : "Tap your TV");
                    break;
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                    if (d != null && d.equals(pendingBond)) {
                        if (st == BluetoothDevice.BOND_BONDED) { pendingBond = null; connectTo(d); }
                        else if (st == BluetoothDevice.BOND_NONE) { pendingBond = null; status("Pairing failed – try again", false); }
                    }
                    break;
                }
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
                            && missingPermissions().length == 0) startHid();
                    break;
            }
        }
    };

    // ------------------------------------------------------------ reports

    private void send(int id, byte[] data) {
        BluetoothDevice h = host;
        if (hid != null && h != null) hid.sendReport(h, id, data);
    }

    private static int clamp(int v) { return Math.max(-127, Math.min(127, v)); }

    /** Called from JavaScript (on a background thread). */
    private class Bridge {
        @JavascriptInterface public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
            boolean first = true;
            do {   // split big moves into chunks that fit a signed byte
                int cx = clamp(dx), cy = clamp(dy);
                dx -= cx; dy -= cy;
                send(ID_MOUSE, new byte[]{(byte) buttons, (byte) cx, (byte) cy,
                        (byte) (first ? clamp(wheel) : 0), (byte) (first ? clamp(pan) : 0)});
                first = false;
            } while (dx != 0 || dy != 0);
        }

        @JavascriptInterface public void key(int modifier, int code) {
            send(ID_KEYBOARD, new byte[]{(byte) modifier, 0, (byte) code, 0, 0, 0, 0, 0});
            send(ID_KEYBOARD, new byte[8]);
        }

        @JavascriptInterface public void consumer(int usage) {
            send(ID_CONSUMER, new byte[]{(byte) (usage & 0xFF), (byte) ((usage >> 8) & 0xFF)});
            send(ID_CONSUMER, new byte[2]);
        }

        @JavascriptInterface public void connect() { runOnUiThread(MainActivity.this::showPicker); }

        @JavascriptInterface public void disconnect() {
            userDisconnect = true; wanted = null;
            if (hid != null && host != null) hid.disconnect(host);
        }

        @JavascriptInterface public void ready() { status(lastStatus, lastConnected); }

        /** OLED dim mode: drop this window's brightness without touching the system setting. */
        @JavascriptInterface public void setDim(boolean on, double level) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = on
                    ? (float) Math.max(0.004, Math.min(1.0, level))
                    : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
            });
        }

        /** false = let the phone's normal screen timeout turn the screen off. */
        @JavascriptInterface public void keepAwake(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void setShowOnLock(boolean on) {
            runOnUiThread(() -> setShowWhenLocked(on));
        }
    }

    // ------------------------------------------------------------ helpers

    private void status(String text, boolean connected) {
        lastStatus = text; lastConnected = connected;
        runOnUiThread(() -> web.evaluateJavascript(
            "window.onHidStatus&&onHidStatus(" + JSONObject.quote(text) + "," + connected + ")", null));
    }

    private String name(BluetoothDevice d) {
        String n = null;
        try { n = d.getName(); } catch (SecurityException ignored) {}
        return (n == null || n.isEmpty()) ? d.getAddress() : n;
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }
}
