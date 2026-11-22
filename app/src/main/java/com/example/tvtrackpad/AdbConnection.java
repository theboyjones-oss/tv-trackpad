package com.example.tvtrackpad;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal ADB client over TCP (the same protocol `adb connect <ip>:5555` uses).
 * Supports the RSA handshake and multiplexed streams (shell:, exec:).
 */
final class AdbConnection implements Closeable {

    interface Listener {
        void onAuthPrompt();          // the TV is showing "Allow debugging?"
        void onClosed(Exception why);
    }

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
                             OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final byte[] EOF = new byte[0];

    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Listener listener;
    private volatile boolean closed;
    private int maxPayload = 4096;
    String banner = "";

    private AdbConnection(Socket s, Listener l) throws IOException {
        sock = s;
        listener = l;
        in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream(), 65536));
        out = s.getOutputStream();
    }

    static AdbConnection connect(String host, int port, AdbKeys keys, Listener l) throws Exception {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), 4000);
        s.setSoTimeout(10000);
        AdbConnection c = new AdbConnection(s, l);
        try {
            c.handshake(keys);
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) {}
            throw e;
        }
        s.setSoTimeout(0);
        Thread t = new Thread(c::readLoop, "adb-reader");
        t.setDaemon(true);
        t.start();
        return c;
    }

    private void handshake(AdbKeys keys) throws Exception {
        send(CNXN, VERSION, MAX_DATA, "host::\u0000".getBytes(StandardCharsets.UTF_8));
        boolean sentSig = false, sentKey = false;
        while (true) {
            Msg m = readMsg();
            if (m.cmd == CNXN) {
                maxPayload = Math.max(1024, Math.min(m.arg1, MAX_DATA));
                banner = new String(m.data, StandardCharsets.UTF_8);
                return;
            }
            if (m.cmd == AUTH && m.arg0 == 1) {             // TOKEN
                if (!sentSig) {
                    send(AUTH, 2, 0, keys.sign(m.data));     // SIGNATURE – works once the TV trusts us
                    sentSig = true;
                } else if (!sentKey) {
                    send(AUTH, 3, 0, keys.adbPublicKey("tvtrackpad@" + android.os.Build.MODEL.replace(' ', '_')));
                    sentKey = true;
                    sock.setSoTimeout(120000);              // give the person time to answer on the TV
                    listener.onAuthPrompt();
                } else {
                    throw new IOException("auth-refused");
                }
            }
        }
    }

    private void readLoop() {
        Exception why = null;
        try {
            while (!closed) {
                Msg m = readMsg();
                Stream s = streams.get(m.arg1);
                switch (m.cmd) {
                    case OKAY:
                        if (s != null) { s.remoteId = m.arg0; s.ready.release(); }
                        break;
                    case WRTE:
                        if (s != null && !s.discard) s.q.add(m.data);
                        send(OKAY, m.arg1, m.arg0, null);
                        break;
                    case CLSE:
                        if (s != null) {
                            streams.remove(m.arg1);
                            if (!s.closed) { s.closed = true; send(CLSE, s.localId, m.arg0, null); }
                            s.q.add(EOF);
                            s.ready.release(10);
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            why = e;
        }
        boolean wasOpen = !closed;
        shutdown();
        if (wasOpen) listener.onClosed(why);
    }

    Stream open(String destination) throws IOException, InterruptedException {
        if (closed) throw new IOException("not connected");
        Stream s = new Stream(nextId.getAndIncrement());
        streams.put(s.localId, s);
        send(OPEN, s.localId, 0, (destination + "\u0000").getBytes(StandardCharsets.UTF_8));
        if (!s.ready.tryAcquire(10, TimeUnit.SECONDS) || s.closed) {
            streams.remove(s.localId);
            throw new IOException("TV refused " + destination);
        }
        return s;
    }

    /** Runs a command and returns its full output (no terminal, so binary output like PNGs is intact). */
    byte[] exec(String command) throws IOException, InterruptedException {
        Stream s = open("exec:" + command);
        return s.readAll(20000);
    }

    boolean isOpen() { return !closed; }

    @Override public void close() {
        closed = true;
        shutdown();
    }

    private void shutdown() {
        closed = true;
        try { sock.close(); } catch (IOException ignored) {}
        for (Stream s : streams.values()) { s.closed = true; s.q.add(EOF); s.ready.release(10); }
        streams.clear();
    }

    // ------------------------------------------------------------ wire format

    private static final class Msg { int cmd, arg0, arg1; byte[] data; }

    private Msg readMsg() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(); m.arg0 = b.getInt(); m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt();                                  // checksum (not checked)
        int magic = b.getInt();
        if (magic != ~m.cmd) throw new IOException("bad ADB header");
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("bad ADB length " + len);
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xFF;
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        if (len > 0) b.put(data);
        synchronized (out) {
            out.write(b.array());
            out.flush();
        }
    }

    // ------------------------------------------------------------ streams

    final class Stream implements Closeable {
        final int localId;
        volatile int remoteId;
        volatile boolean closed;
        volatile boolean discard;                      // drop incoming output (fire-and-forget shells)
        final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        final Semaphore ready = new Semaphore(0);

        Stream(int id) { localId = id; }

        void write(byte[] data) throws IOException, InterruptedException {
            for (int off = 0; off < data.length; off += maxPayload) {
                if (closed) throw new IOException("stream closed");
                int n = Math.min(maxPayload, data.length - off);
                byte[] chunk = new byte[n];
                System.arraycopy(data, off, chunk, 0, n);
                send(WRTE, localId, remoteId, chunk);
                if (!ready.tryAcquire(10, TimeUnit.SECONDS)) throw new IOException("TV stopped responding");
                if (closed) throw new IOException("stream closed");
            }
        }

        void write(String s) throws IOException, InterruptedException { write(s.getBytes(StandardCharsets.UTF_8)); }

        byte[] readAll(long timeoutMs) throws IOException, InterruptedException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) { close(); throw new IOException("timed out"); }
                byte[] d = q.poll(left, TimeUnit.MILLISECONDS);
                if (d == null) continue;
                if (d == EOF) break;
                bos.write(d, 0, d.length);
            }
            return bos.toByteArray();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            streams.remove(localId);
            try { send(CLSE, localId, remoteId, null); } catch (IOException ignored) {}
            q.add(EOF);
        }
    }

}
