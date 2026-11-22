package com.example.tvtrackpad.tvhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Draws the mouse cursor on the TV as an overlay window ("display over other apps").
 * The phone app starts this service over ADB, keeps track of the cursor position itself,
 * and performs clicks with `input tap`, so this only has to draw. Commands, one per line,
 * arrive on 127.0.0.1:38999 through ADB port forwarding:
 *   A x y   show the cursor with its tip at screen pixel x,y
 *   H       hide          P   ping (replies "ok WxH" or "nooverlay WxH")
 */
public class CursorService extends Service {

    static final String TAG = "TVTrackpadCursor";
    static final int PORT = 38999;
    private static final long AUTO_HIDE_MS = 30000;
    private static final int TIP_GAP = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private CursorView view;
    private WindowManager.LayoutParams lp;
    private int sw, sh;
    private volatile ServerSocket server;
    private boolean started;
    private final Runnable autoHide = () -> show(false);

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        foreground();
        if (!started) {
            started = true;
            setUpOverlay();
            Thread t = new Thread(this::serve, "cursor-server");
            t.setDaemon(true);
            t.start();
        } else if (view == null) {
            setUpOverlay();   // overlay permission may have been granted since the first start
        }
        return START_STICKY;
    }

    private void foreground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("cursor", "Cursor", NotificationManager.IMPORTANCE_MIN));
                b = new Notification.Builder(this, "cursor");
            } else {
                b = new Notification.Builder(this);
            }
            Notification n = b.setContentTitle("TV Trackpad cursor")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true).build();
            startForeground(1, n);
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    private void setUpOverlay() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        sw = dm.widthPixels;
        sh = dm.heightPixels;
        int size = Math.round(40 * Math.max(sw, sh) / 1920f);
        CursorView v = new CursorView(this);
        lp = new WindowManager.LayoutParams(size, size,
            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                        : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = sw / 2 + TIP_GAP;
        lp.y = sh / 2 + TIP_GAP;
        // Android 12+ blocks taps that land on another app's overlay unless it is at most 80% opaque.
        // The window sits just beside the click point anyway (TIP_GAP); this is a second safeguard.
        lp.alpha = 0.8f;
        v.setVisibility(View.GONE);
        try {
            wm.addView(v, lp);
            view = v;
        } catch (Exception e) {
            Log.e(TAG, "overlay not allowed", e);
            view = null;
        }
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
        } catch (Exception e) {
            Log.e(TAG, "socket server stopped", e);
        }
    }

    private void client(Socket c) {
        try (Socket sock = c;
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
            sock.setTcpNoDelay(true);
            OutputStream out = sock.getOutputStream();
            String line;
            while ((line = in.readLine()) != null) {
                final String cmd = line.trim();
                if (cmd.equals("P")) {
                    String reply = (view == null ? "nooverlay " : "ok ") + sw + "x" + sh + "\n";
                    out.write(reply.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } else if (!cmd.isEmpty()) {
                    ui.post(() -> handle(cmd));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void handle(String cmd) {
        String[] p = cmd.split(" ");
        try {
            if (p[0].equals("A")) {
                // keep the click point itself outside the overlay window, or the TV drops the tap
                lp.x = Integer.parseInt(p[1]) + TIP_GAP;
                lp.y = Integer.parseInt(p[2]) + TIP_GAP;
                show(true);
                if (view != null) wm.updateViewLayout(view, lp);
                ui.removeCallbacks(autoHide);
                ui.postDelayed(autoHide, AUTO_HIDE_MS);
            } else if (p[0].equals("H")) {
                show(false);
            }
        } catch (Exception ignored) {
        }
    }

    private void show(boolean on) {
        if (view != null) view.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) ui.removeCallbacks(autoHide);
    }

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
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float s = Math.min(w, h) * 0.9f;
            edge.setStrokeWidth(Math.max(1.5f, s / 22f));
            float o = edge.getStrokeWidth() / 2f;
            arrow.reset();
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
