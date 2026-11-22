package com.example.tvtrackpad;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Wi-Fi remote for Google TV / Android TV. Talks to the TV's own debugging service
 * (ADB over the network, port 5555) to press keys, tap and swipe anywhere, type text,
 * using the swipe-remote UI. The UI is assets/index.html.
 */
@SuppressLint("SetJavaScriptEnabled")
public class MainActivity extends Activity {

    private static final int ADB_PORT = 5555;

    // TV-side cursor helper (tvhelper module, bundled as assets/tvhelper.apk)
    private static final String HELPER_PKG = "com.example.tvtrackpad.tvhelper";
    private static final String HELPER_SVC = HELPER_PKG + "/" + HELPER_PKG + ".CursorService";
    private static final int HELPER_VERSION = 1, CURSOR_PORT = 38999;
    private volatile AdbConnection.Stream cursor;
    private final LinkedBlockingQueue<String> mouseCmds = new LinkedBlockingQueue<>();
    private Thread mouseThread;

    private WebView web;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newCachedThreadPool();

    private volatile AdbKeys keys;
    private volatile AdbConnection conn;
    private volatile AdbConnection.Stream shell;
    private volatile String tvIp, tvName;
    private volatile boolean connecting, userDisconnect;
    private final LinkedBlockingQueue<String> cmds = new LinkedBlockingQueue<>();
    private Thread cmdThread;

    private volatile String lastStatus = "Tap Connect to find your TV";
    private volatile boolean lastConnected;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        prefs = getSharedPreferences("tvpad", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);   // don't let the phone's font-size setting push the layout off screen
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        bg.execute(() -> {
            try {
                keys = AdbKeys.loadOrCreate(getFilesDir());
                log("key ready");
            } catch (Exception e) {
                log("key error " + e);
                status("Couldn't create a security key: " + e.getMessage(), false);
                return;
            }
            String last = prefs.getString("lastIp", null);
            if (last != null) ui.post(() -> connect(last, true));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        log("resume");
        String last = prefs.getString("lastIp", null);
        if (conn == null && !connecting && !userDisconnect && last != null && keys != null) connect(last, true);
    }

    @Override
    protected void onDestroy() {
        disconnect(true);
        if (cmdThread != null) cmdThread.interrupt();
        bg.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------ connecting

    private void connect(String ip, boolean quiet) {
        if (connecting) return;
        if (keys == null) { status("Still starting – try again in a second", false); return; }
        connecting = true;
        userDisconnect = false;
        disconnect(false);
        status("Connecting to " + ip + "…", false);
        bg.execute(() -> {
            try {
                AdbConnection c = AdbConnection.connect(ip, ADB_PORT, keys, new AdbConnection.Listener() {
                    @Override public void onAuthPrompt() {
                        log("auth prompt shown on TV");
                        status("On the TV: tick 'Always allow' and choose Allow", false);
                    }
                    @Override public void onClosed(Exception why) {
                        log("connection closed: " + why);
                        onLost(ip);
                    }
                });
                log("connected " + ip + " banner=" + c.banner);
                AdbConnection.Stream sh = c.open("shell:");
                sh.discard = true;
                String model = "";
                try { model = new String(c.exec("getprop ro.product.model")).trim(); } catch (Exception ignored) {}
                conn = c;
                shell = sh;
                tvIp = ip;
                tvName = model.isEmpty() ? ip : model;
                prefs.edit().putString("lastIp", ip).apply();
                rememberIp(ip);
                startCmdThread();
                status("Connected to " + tvName, true);
            } catch (Exception e) {
                log("connect failed " + ip + ": " + e);
                String m = String.valueOf(e.getMessage());
                boolean unreachable = e instanceof ConnectException || e instanceof NoRouteToHostException
                    || (e instanceof SocketTimeoutException && m.toLowerCase(Locale.ROOT).contains("connect"));
                if (unreachable)
                    status(quiet ? "TV not found at " + ip + " – tap Connect"
                                 : "Can't reach " + ip + " – is USB debugging on? (Connect › First-time setup)", false);
                else if (m.contains("auth") || e instanceof java.io.EOFException || e instanceof java.net.SocketException)
                    status("The TV didn't allow it – tap Connect and choose Allow on the TV", false);
                else
                    status("Couldn't connect: " + m, false);
            } finally {
                connecting = false;
            }
        });
    }

    private void onLost(String ip) {
        conn = null;
        shell = null;
        cursor = null;
        if (userDisconnect) { status("Disconnected – tap Connect", false); return; }
        status("Connection lost – reconnecting…", false);
        ui.postDelayed(() -> { if (conn == null && !userDisconnect) connect(ip, false); }, 1500);
    }

    private void disconnect(boolean byUser) {
        if (byUser) userDisconnect = true;
        AdbConnection c = conn;
        conn = null;
        shell = null;
        cursor = null;
        cmds.clear();
        mouseCmds.clear();
        if (c != null) c.close();
    }

    private void rememberIp(String ip) {
        Set<String> s = new LinkedHashSet<>(prefs.getStringSet("ips", Collections.emptySet()));
        s.add(ip);
        prefs.edit().putStringSet("ips", s).apply();
    }

    // ------------------------------------------------------------ connect dialogs

    private void showConnectMenu() {
        final List<String> known = new ArrayList<>(prefs.getStringSet("ips", Collections.emptySet()));
        List<String> labels = new ArrayList<>();
        labels.add("🔍  Find my TV on Wi-Fi");
        labels.add("⌨️  Enter the TV's IP address");
        for (String ip : known) labels.add("📺  " + ip + (ip.equals(tvIp) && conn != null ? "  (connected)" : ""));
        labels.add("❓  First-time setup on the TV");
        new AlertDialog.Builder(this)
            .setTitle("Connect to TV")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which == 0) scan();
                else if (which == 1) askIp();
                else if (which < 2 + known.size()) connect(known.get(which - 2), false);
                else showSetupHelp();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSetupHelp() {
        new AlertDialog.Builder(this)
            .setTitle("First-time setup on the TV")
            .setMessage("1. On the TV open Settings › System › About (on some TVs: Settings › Device Preferences › About).\n\n"
                + "2. Select 'Android TV OS build' 7 times until it says you're a developer.\n\n"
                + "3. Go back to Settings › System › Developer options and turn on 'USB debugging' "
                + "(and 'Network debugging' or 'ADB over network' if your TV has it).\n\n"
                + "4. Make sure the phone and TV are on the same Wi-Fi, then tap Connect › Find my TV.\n\n"
                + "5. The TV asks 'Allow debugging?' – tick 'Always allow from this computer' and choose Allow.")
            .setPositiveButton("Find my TV", (d, w) -> scan())
            .setNegativeButton("Close", null)
            .show();
    }

    private void askIp() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        e.setHint("e.g. 192.168.1.50");
        e.setText(prefs.getString("lastIp", ""));
        e.setSelection(e.getText().length());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(e);
        new AlertDialog.Builder(this)
            .setTitle("TV's IP address")
            .setMessage("On the TV: Settings › Network & Internet › your Wi-Fi network › IP address")
            .setView(box)
            .setPositiveButton("Connect", (d, w) -> {
                String ip = e.getText().toString().trim();
                if (ip.contains(":")) ip = ip.substring(0, ip.indexOf(':'));
                if (!ip.isEmpty()) connect(ip, false);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Looks for anything on this phone's Wi-Fi subnet that answers on the ADB port. */
    private void scan() {
        String self = localIp();
        if (self == null) { status("Connect the phone to Wi-Fi first", false); return; }
        String prefix = self.substring(0, self.lastIndexOf('.') + 1);
        List<String> found = new ArrayList<>();
        ArrayAdapter<String> list = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("Searching your Wi-Fi…")
            .setAdapter(list, (d, which) -> connect(found.get(which), false))
            .setNegativeButton("Close", null)
            .show();
        log("scan " + prefix + "0/24 from " + self);
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            final String ip = prefix + i;
            if (ip.equals(self)) continue;
            pool.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(ip, ADB_PORT), 700);
                    log("found " + ip);
                    ui.post(() -> {
                        if (found.contains(ip)) return;
                        found.add(ip);
                        list.add("📺  " + ip);
                    });
                } catch (Exception ignored) {}
            });
        }
        pool.shutdown();
        bg.execute(() -> {
            try { pool.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            ui.post(() -> {
                if (!dlg.isShowing()) return;
                dlg.setTitle(found.isEmpty() ? "No TV found – turn on USB debugging (Connect › First-time setup) or enter the IP"
                                             : "Tap your TV");
            });
        });
    }

    private static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (n.startsWith("wlan") || n.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ commands

    /** One worker writes commands into a single TV shell, so they run in order. Key presses in a row are merged. */
    private synchronized void startCmdThread() {
        if (cmdThread != null && cmdThread.isAlive()) return;
        cmdThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String c = cmds.take();
                    if (c.startsWith("K:")) {
                        StringBuilder b = new StringBuilder("input keyevent ").append(c.substring(2));
                        String nx;
                        while ((nx = cmds.peek()) != null && nx.startsWith("K:")) {
                            cmds.poll();
                            b.append(' ').append(nx.substring(2));
                        }
                        c = b.toString();
                    }
                    AdbConnection.Stream sh = shell;
                    if (sh != null) sh.write(c + "\n");
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    log("command failed: " + e);
                }
            }
        }, "tv-commands");
        cmdThread.setDaemon(true);
        cmdThread.start();
    }

    private void cmd(String c) {
        if (conn == null) {
            status(connecting ? "Still connecting…" : "Not connected – tap Connect", false);
            return;
        }
        cmds.add(c);
    }

    // ------------------------------------------------------------ on-screen cursor

    private static final java.util.regex.Pattern MOUSE_CMD =
        java.util.regex.Pattern.compile("^[MVHCLS]( -?\\d{1,5}){0,2}$");

    private void mouse(String c) {
        if (conn == null) {
            status(connecting ? "Still connecting…" : "Not connected – tap Connect", false);
            return;
        }
        if (!MOUSE_CMD.matcher(c).matches()) return;
        mouseCmds.add(c);
        startMouseThread();
    }

    /** Sends cursor commands to the TV helper; consecutive moves are merged so the cursor never lags. */
    private synchronized void startMouseThread() {
        if (mouseThread != null && mouseThread.isAlive()) return;
        mouseThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String c = mouseCmds.take();
                    if (c.startsWith("M ")) {
                        int dx = 0, dy = 0;
                        String nx = c;
                        do {
                            if (nx != c) mouseCmds.poll();
                            String[] p = nx.split(" ");
                            dx += Integer.parseInt(p[1]);
                            dy += Integer.parseInt(p[2]);
                        } while ((nx = mouseCmds.peek()) != null && nx.startsWith("M "));
                        if (dx == 0 && dy == 0) continue;
                        c = "M " + dx + " " + dy;
                    }
                    AdbConnection.Stream s = cursorStream();
                    if (s == null) { mouseCmds.clear(); continue; }
                    try {
                        s.write(c + "\n");
                    } catch (Exception e) {
                        log("cursor write failed: " + e);
                        cursor = null;
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    log("mouse thread: " + e);
                }
            }
        }, "tv-mouse");
        mouseThread.setDaemon(true);
        mouseThread.start();
    }

    /** Link to the helper's socket on the TV; installs and enables the helper the first time. */
    private AdbConnection.Stream cursorStream() {
        AdbConnection.Stream s = cursor;
        if (s != null && !s.closed) return s;
        AdbConnection c = conn;
        if (c == null) return null;
        try {
            s = openCursor(c, 1);
            if (s == null) {
                installHelperIfNeeded(c);
                enableHelper(c);
                status("Starting the cursor on the TV…", true);
                s = openCursor(c, 16);
            }
            if (s == null) {
                status("The TV didn't start the cursor helper – see Settings › Share log", true);
                return null;
            }
            s.discard = true;
            cursor = s;
            status("Connected to " + tvName + " – cursor ready", true);
            return s;
        } catch (Exception e) {
            log("cursor setup failed: " + e);
            status("Cursor setup failed: " + e.getMessage(), conn != null);
            return null;
        }
    }

    private AdbConnection.Stream openCursor(AdbConnection c, int tries) throws InterruptedException {
        for (int i = 0; i < tries; i++) {
            try {
                return c.open("tcp:" + CURSOR_PORT);
            } catch (java.io.IOException e) {
                if (i + 1 < tries) Thread.sleep(500);
            }
        }
        return null;
    }

    private void installHelperIfNeeded(AdbConnection c) throws Exception {
        String have = new String(c.exec("pm list packages --show-versioncode " + HELPER_PKG));
        log("helper on TV: " + have.trim());
        if (have.contains("package:" + HELPER_PKG + " versionCode:" + HELPER_VERSION)) return;
        status("Installing the cursor helper on the TV…", true);
        byte[] apk = readAsset("tvhelper.apk");
        AdbConnection.Stream s = c.open("exec:cmd package install -r -S " + apk.length);
        s.write(apk);
        String res = new String(s.readAll(90000)).trim();
        log("install: " + res);
        if (!res.contains("Success")) throw new java.io.IOException("TV wouldn't install it (" + res + ")");
    }

    private void enableHelper(AdbConnection c) throws Exception {
        String cur = new String(c.exec("settings get secure enabled_accessibility_services")).trim();
        if (cur.equals("null")) cur = "";
        log("accessibility services: " + cur);
        if (!cur.contains(HELPER_PKG + "/")) {
            String v = cur.isEmpty() ? HELPER_SVC : cur + ":" + HELPER_SVC;
            c.exec("settings put secure enabled_accessibility_services " + v);
        }
        c.exec("settings put secure accessibility_enabled 1");
    }

    private byte[] readAsset(String name) throws java.io.IOException {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String shellQuote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    // ------------------------------------------------------------ JavaScript bridge

    private class Bridge {
        @JavascriptInterface public void key(int keycode) { cmd("K:" + keycode); }

        /** Cursor commands: "M dx dy", "V", "H", "C", "L", "S dx dy". */
        @JavascriptInterface public void mouse(String c) { MainActivity.this.mouse(c); }

        @JavascriptInterface public void longKey(int keycode) { cmd("input keyevent --longpress " + keycode); }

        @JavascriptInterface public void text(String t) {
            if (t == null || t.isEmpty()) return;
            StringBuilder ascii = new StringBuilder();
            for (char ch : t.toCharArray()) {
                if (ch == '‘' || ch == '’') ch = '\'';
                else if (ch == '“' || ch == '”') ch = '"';
                else if (ch == '–' || ch == '—') ch = '-';
                if (ch >= 32 && ch < 127) ascii.append(ch);
            }
            if (ascii.length() == 0) return;
            cmd("input text " + shellQuote(ascii.toString().replace(" ", "%s")));
        }

        @JavascriptInterface public boolean connected() { return conn != null; }

        @JavascriptInterface public void connect() { runOnUiThread(MainActivity.this::showConnectMenu); }

        @JavascriptInterface public void disconnect() {
            log("user disconnect");
            MainActivity.this.disconnect(true);
            status("Disconnected – tap Connect", false);
        }

        @JavascriptInterface public void shareLog() {
            String text = logText();
            runOnUiThread(() -> startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "TV Trackpad log")
                    .putExtra(Intent.EXTRA_TEXT, text), "Share log")));
        }

        @JavascriptInterface public void ready() { status(lastStatus, lastConnected); }

        @JavascriptInterface public void setDim(boolean on, double level) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                // dim relative to the phone's current brightness, so it always ends up darker
                float target = (float) Math.max(0.01, Math.min(1.0, systemBrightness() * level));
                lp.screenBrightness = on ? target : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                log("dim " + on + (on ? " -> " + target : ""));
                getWindow().setAttributes(lp);
            });
        }

        @JavascriptInterface public void keepAwake(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void setShowOnLock(boolean on) { runOnUiThread(() -> setShowWhenLocked(on)); }
    }

    /** Phone's normal brightness as 0..1 (falls back to 0.5 if the system won't say). */
    private float systemBrightness() {
        try {
            int v = android.provider.Settings.System.getInt(getContentResolver(),
                android.provider.Settings.System.SCREEN_BRIGHTNESS);
            return Math.max(0.02f, Math.min(1f, v / 255f));
        } catch (Exception e) {
            return 0.5f;
        }
    }

    // ------------------------------------------------------------ status + log

    private void status(String text, boolean connected) {
        lastStatus = text;
        lastConnected = connected;
        log("status: " + text);
        runOnUiThread(() -> web.evaluateJavascript(
            "window.onTvStatus&&onTvStatus(" + JSONObject.quote(text) + "," + connected + ")", null));
    }

    private final ArrayDeque<String> logBuf = new ArrayDeque<>();
    private final long t0 = SystemClock.elapsedRealtime();

    private synchronized void log(String m) {
        logBuf.addLast(String.format(Locale.UK, "%7.2fs  %s", (SystemClock.elapsedRealtime() - t0) / 1000.0, m));
        while (logBuf.size() > 400) logBuf.removeFirst();
    }

    private synchronized String logText() {
        StringBuilder b = new StringBuilder("TV Trackpad (Wi-Fi) log – ")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE).append('\n');
        for (String l : logBuf) b.append(l).append('\n');
        return b.toString();
    }
}
