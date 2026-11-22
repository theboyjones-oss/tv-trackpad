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
    private static final int HELPER_VERSION = 4, CURSOR_PORT = 38999;
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
    //
    // The TV helper only draws the arrow (an overlay window). The phone keeps the cursor
    // position and clicks/scrolls with `input tap` / `input swipe` through the TV shell.

    private static final java.util.regex.Pattern MOUSE_CMD =
        java.util.regex.Pattern.compile("^[MVHCLS]( -?\\d{1,5}){0,2}$");
    private int scrW = 1920, scrH = 1080;
    private float cx = 960, cy = 540, unit = 1f;
    private boolean sizeKnown;

    private void mouse(String c) {
        if (conn == null) {
            status(connecting ? "Still connecting…" : "Not connected – tap Connect", false);
            return;
        }
        if (!MOUSE_CMD.matcher(c).matches()) return;
        mouseCmds.add(c);
        startMouseThread();
    }

    private synchronized void startMouseThread() {
        if (mouseThread != null && mouseThread.isAlive()) return;
        mouseThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String c = mouseCmds.take();
                    AdbConnection.Stream s = cursorStream();
                    if (s == null) { mouseCmds.clear(); continue; }
                    String[] p = c.split(" ");
                    int x, y;
                    switch (p[0]) {
                        case "M": {
                            // merge queued moves so the arrow never lags behind the finger
                            float dx = Integer.parseInt(p[1]), dy = Integer.parseInt(p[2]);
                            String nx;
                            while ((nx = mouseCmds.peek()) != null && nx.startsWith("M ")) {
                                mouseCmds.poll();
                                String[] q = nx.split(" ");
                                dx += Integer.parseInt(q[1]);
                                dy += Integer.parseInt(q[2]);
                            }
                            cx = clamp(cx + dx * unit, 0, scrW - 1);
                            cy = clamp(cy + dy * unit, 0, scrH - 1);
                            send(s, "A " + Math.round(cx) + " " + Math.round(cy));
                            break;
                        }
                        case "V":
                            send(s, "A " + Math.round(cx) + " " + Math.round(cy));
                            break;
                        case "H":
                            send(s, "H");
                            break;
                        case "C": {
                            String r = new String(conn.exec("input tap " + Math.round(cx) + " " + Math.round(cy))).trim();
                            log("click " + Math.round(cx) + "," + Math.round(cy) + (r.isEmpty() ? "" : " -> " + r));
                            break;
                        }
                        case "L":
                            x = Math.round(cx); y = Math.round(cy);
                            cmd("input swipe " + x + " " + y + " " + x + " " + y + " 900");
                            break;
                        case "S": {
                            x = Math.round(cx); y = Math.round(cy);
                            int x2 = Math.round(clamp(cx + Integer.parseInt(p[1]) * unit, 1, scrW - 2));
                            int y2 = Math.round(clamp(cy + Integer.parseInt(p[2]) * unit, 1, scrH - 2));
                            if (Math.abs(x2 - x) > 2 || Math.abs(y2 - y) > 2)
                                cmd("input swipe " + x + " " + y + " " + x2 + " " + y2 + " 250");
                            break;
                        }
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

    private void send(AdbConnection.Stream s, String line) {
        try {
            s.write(line + "\n");
        } catch (Exception e) {
            log("cursor write failed: " + e);
            cursor = null;
        }
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Link to the helper on the TV; installs, permits and starts it the first time. */
    private AdbConnection.Stream cursorStream() {
        AdbConnection.Stream s = cursor;
        if (s != null && !s.closed) return s;
        AdbConnection c = conn;
        if (c == null) return null;
        try {
            status("Starting the cursor on the TV…", true);
            installHelperIfNeeded(c);
            String comp = HELPER_PKG + "/.CursorService";
            log("appops: " + new String(c.exec("appops set " + HELPER_PKG + " SYSTEM_ALERT_WINDOW allow")).trim());
            log("start: " + new String(c.exec("am start-foreground-service -n " + comp)).trim());
            s = openCursor(c, 16);
            if (s == null) {
                diagnoseHelper(c);
                status("The TV didn't start the cursor helper – see Settings › Share log", true);
                return null;
            }
            s.write("P\n");
            String pong = readLine(s, 3000);
            log("helper says: " + pong);
            s.discard = true;
            if (pong == null) {
                s.close();
                diagnoseHelper(c);
                status("The cursor helper didn't answer – see Settings › Share log", true);
                return null;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)").matcher(pong);
            if (m.find()) {
                int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2));
                if (w > 0 && h > 0) {
                    if (!sizeKnown || w != scrW || h != scrH) { cx = w / 2f; cy = h / 2f; }
                    scrW = w; scrH = h; sizeKnown = true;
                    unit = Math.max(w, h) / 1920f;
                }
            }
            if (pong.startsWith("nooverlay")) {
                diagnoseHelper(c);
                status("The TV won't let the cursor draw on screen – see Settings › Share log", true);
                s.close();
                return null;
            }
            cursor = s;
            status("Connected to " + tvName + " – cursor ready", true);
            return s;
        } catch (Exception e) {
            log("cursor setup failed: " + e);
            status("Cursor setup failed: " + e.getMessage(), conn != null);
            return null;
        }
    }

    private static String readLine(AdbConnection.Stream s, long ms) throws InterruptedException {
        StringBuilder b = new StringBuilder();
        long end = SystemClock.elapsedRealtime() + ms;
        while (true) {
            long left = end - SystemClock.elapsedRealtime();
            if (left <= 0) return null;
            byte[] d = s.q.poll(left, TimeUnit.MILLISECONDS);
            if (d == null || d.length == 0) return null;
            b.append(new String(d, java.nio.charset.StandardCharsets.UTF_8));
            int i = b.indexOf("\n");
            if (i >= 0) return b.substring(0, i).trim();
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
        // earlier versions registered an accessibility service the TV never ran – tidy it away
        String cur = new String(c.exec("settings get secure enabled_accessibility_services")).trim();
        if (cur.contains(HELPER_PKG + "/")) {
            StringBuilder rest = new StringBuilder();
            for (String part : cur.split(":"))
                if (!part.startsWith(HELPER_PKG + "/") && !part.isEmpty() && !part.equals("null"))
                    rest.append(rest.length() > 0 ? ":" : "").append(part);
            c.exec(rest.length() == 0 ? "settings delete secure enabled_accessibility_services"
                                      : "settings put secure enabled_accessibility_services " + rest);
        }
        Thread.sleep(1000);
    }

    /** Writes what the TV says about the helper into the log, for when it won't start. */
    private void diagnoseHelper(AdbConnection c) {
        try {
            log("android: " + new String(c.exec("getprop ro.build.version.release")).trim()
                + " low_ram=" + new String(c.exec("getprop ro.config.low_ram")).trim());
            log("overlay appop: " + new String(c.exec("appops get " + HELPER_PKG + " SYSTEM_ALERT_WINDOW")).trim());
            log("helper process: " + new String(c.exec("pidof " + HELPER_PKG)).trim());
            String svc = new String(c.exec("dumpsys activity services " + HELPER_PKG)).trim();
            log("services: " + (svc.length() > 600 ? svc.substring(0, 600) : svc));
            String lc = new String(c.exec("logcat -d -t 600")).trim();
            StringBuilder b = new StringBuilder();
            for (String line : lc.split("\n"))
                if (line.contains("TVTrackpadCursor") || line.contains("tvhelper") || line.contains("FATAL"))
                    b.append(line.trim()).append('\n');
            String l = b.toString();
            log("logcat: " + (l.length() > 2500 ? l.substring(l.length() - 2500) : l));
        } catch (Exception e) {
            log("diagnose failed: " + e);
        }
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
