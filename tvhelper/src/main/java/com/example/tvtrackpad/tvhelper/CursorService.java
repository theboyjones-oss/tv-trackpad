package com.example.tvtrackpad.tvhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Draws a mouse cursor over everything on the TV and performs taps/scrolls where it points.
 * The phone app reaches it through ADB port forwarding to 127.0.0.1:38999 and sends one
 * command per line:
 *   M dx dy   move (shows the cursor)      V   show       H   hide
 *   C         click at the cursor          L   long-press at the cursor
 *   S dx dy   scroll (swipe from the cursor by dx,dy)
 *   P         ping (replies "ok")
 */
public class CursorService extends AccessibilityService {

    static final int PORT = 38999;
    private static final long AUTO_HIDE_MS = 30000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private CursorView view;
    private WindowManager.LayoutParams lp;
    private int sw, sh, size;
    private float x, y, unit;
    private boolean shown;
    private volatile ServerSocket server;
    private final Runnable autoHide = () -> setShown(false);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        sw = dm.widthPixels;
        sh = dm.heightPixels;
        unit = Math.max(sw, sh) / 1920f;           // phone movement feels the same on HD and 4K
        size = Math.round(40 * unit);
        x = sw / 2f;
        y = sh / 2f;

        view = new CursorView(this);
        lp = new WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        view.setVisibility(View.GONE);
        try { wm.addView(view, lp); } catch (Exception e) { view = null; }
        place();

        Thread t = new Thread(this::serve, "cursor-server");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ socket

    private void serve() {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            server = s;
            while (!s.isClosed()) {
                Socket c = s.accept();
                Thread t = new Thread(() -> client(c), "cursor-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (Exception ignored) {
            // service is shutting down, or the port is busy
        }
    }

    private void client(Socket c) {
        try (Socket sock = c;
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
            sock.setTcpNoDelay(true);
            String line;
            while ((line = in.readLine()) != null) {
                final String cmd = line.trim();
                if (cmd.equals("P")) {
                    sock.getOutputStream().write("ok\n".getBytes(StandardCharsets.UTF_8));
                    sock.getOutputStream().flush();
                    continue;
                }
                if (!cmd.isEmpty()) ui.post(() -> handle(cmd));
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ commands (UI thread)

    private void handle(String cmd) {
        String[] p = cmd.split(" ");
        try {
            switch (p[0]) {
                case "M":
                    setShown(true);
                    x = clamp(x + Float.parseFloat(p[1]) * unit, 0, sw - 1);
                    y = clamp(y + Float.parseFloat(p[2]) * unit, 0, sh - 1);
                    place();
                    break;
                case "V": setShown(true); break;
                case "H": setShown(false); return;
                case "C": if (shown) tap(60); else setShown(true); break;
                case "L": if (shown) tap(900); break;
                case "S": if (shown) scroll(Float.parseFloat(p[1]) * unit, Float.parseFloat(p[2]) * unit); break;
                default: return;
            }
        } catch (Exception ignored) {
            return;
        }
        ui.removeCallbacks(autoHide);
        ui.postDelayed(autoHide, AUTO_HIDE_MS);
    }

    private void setShown(boolean on) {
        shown = on;
        if (view != null) view.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) ui.removeCallbacks(autoHide);
    }

    private void place() {
        if (view == null) return;
        lp.x = Math.round(x);
        lp.y = Math.round(y);
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
    }

    private void tap(long ms) {
        Path path = new Path();
        path.moveTo(x, y);
        gesture(path, ms);
    }

    private void scroll(float dx, float dy) {
        // start from the cursor, keep the stroke on screen
        float x2 = clamp(x + dx, 1, sw - 2), y2 = clamp(y + dy, 1, sh - 2);
        if (Math.abs(x2 - x) < 2 && Math.abs(y2 - y) < 2) return;
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x2, y2);
        gesture(path, 220);
    }

    private void gesture(Path path, long ms) {
        GestureDescription g = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, ms))
            .build();
        dispatchGesture(g, null, null);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (view != null) wm.removeView(view); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ------------------------------------------------------------ cursor drawing

    /** Classic arrow pointer, tip at the view's top-left corner, white with a dark outline. */
    private static final class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        CursorView(Context c) {
            super(c);
            fill.setColor(0xFFFFFFFF);
            fill.setStyle(Paint.Style.FILL);
            fill.setShadowLayer(4, 1, 2, 0x88000000);
            edge.setColor(0xFF111111);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeJoin(Paint.Join.ROUND);
            setLayerType(LAYER_TYPE_SOFTWARE, null);    // needed for the shadow
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float s = Math.min(w, h) * 0.9f;
            edge.setStrokeWidth(Math.max(1.5f, s / 22f));
            arrow.reset();
            float o = edge.getStrokeWidth();
            arrow.moveTo(o, o);
            arrow.lineTo(o, s * 0.82f);
            arrow.lineTo(s * 0.23f, s * 0.63f);
            arrow.lineTo(s * 0.38f, s * 0.97f);
            arrow.lineTo(s * 0.52f, s * 0.91f);
            arrow.lineTo(s * 0.37f, s * 0.58f);
            arrow.lineTo(s * 0.64f, s * 0.58f);
            arrow.close();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
        }
    }
}
