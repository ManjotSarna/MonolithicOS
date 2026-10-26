package com.joe.home;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * MonolithicOS talking to its own TV's ADB, so it can do what the computer installer does (switch Google's
 * home off, grant its permissions, debloat, and undo all of it) with no computer. Once USB debugging is on, a
 * Google TV listens for ADB on its network port; this connects to it on the TV itself, signs in with a key of
 * its own (the TV asks "Allow USB debugging?" the first time: tick Always allow), and runs shell commands.
 *
 * Just enough of the ADB protocol: CNXN, AUTH (token signature, then the public key), shell streams, and
 * sync SEND for pushing a file. One command at a time; not thread-safe (use it from one worker thread).
 */
final class Adb implements Closeable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX = 256 * 1024;
    private static final int TOKEN = 1, SIGNATURE = 2, PUBLIC_KEY = 3;
    static final int PORT = 5555;

    /** USB debugging is off (nothing listening), or the TV said no. */
    static final class Closed extends IOException {
        Closed(String m) { super(m); }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private Adb(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /**
     * Connects and signs in. waitMs: how long to wait for someone to press Allow when the TV doesn't know this
     * key yet (0: don't offer the key, fail instead).
     */
    static Adb connect(Context c, int waitMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
        } catch (IOException e) {
            s.close();
            throw new Closed("USB debugging is off");
        }
        s.setSoTimeout(8000);
        Adb a = new Adb(s);
        try {
            a.handshake(c, waitMs);
            return a;
        } catch (IOException | RuntimeException e) {
            a.close();
            throw e;
        }
    }

    private void handshake(Context c, int waitMs) throws IOException {
        send(CNXN, VERSION, MAX, "host::\0".getBytes(StandardCharsets.UTF_8));
        KeyPair keys = keys(c);
        boolean signed = false;
        while (true) {
            Msg m = read();
            if (m.cmd == CNXN) return;   // signed in
            if (m.cmd != AUTH || m.arg0 != TOKEN) throw new IOException("unexpected " + Integer.toHexString(m.cmd));
            if (!signed) {   // try the key the TV may know already
                signed = true;
                send(AUTH, SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                continue;
            }
            if (waitMs <= 0) throw new Closed("not allowed yet");
            // a key the TV doesn't know: it asks on screen whether to allow it
            send(AUTH, PUBLIC_KEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
            socket.setSoTimeout(waitMs);
            Msg ok;
            try {
                ok = read();
            } catch (java.net.SocketTimeoutException e) {
                throw new Closed("nobody pressed Allow");
            }
            socket.setSoTimeout(8000);
            if (ok.cmd == CNXN) return;
            throw new Closed("the TV said no");
        }
    }

    /** Runs a shell command and returns what it printed (stdout and stderr together). */
    String shell(String cmd) throws IOException {
        int local = nextId++;
        send(OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue;   // something left from another stream
            if (m.cmd == OKAY) {
                remote = m.arg0;
            } else if (m.cmd == WRTE) {
                text.write(m.data);
                send(OKAY, local, m.arg0, null);
            } else if (m.cmd == CLSE) {
                if (remote != 0) send(CLSE, local, remote, null);
                return text.toString("UTF-8").replace("\r", "").trim();
            }
        }
    }

    /** Copies data to a file on the TV (the sync protocol's SEND), readable by everyone. */
    void push(byte[] data, String path) throws IOException {
        int local = nextId++;
        send(OPEN, local, 0, "sync:\0".getBytes(StandardCharsets.UTF_8));
        int remote = expect(local, OKAY).arg0;
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        byte[] spec = (path + ",0644").getBytes(StandardCharsets.UTF_8);
        req.write(syncHeader("SEND", spec.length));
        req.write(spec);
        for (int at = 0; at < data.length; at += 64 * 1024) {
            int n = Math.min(64 * 1024, data.length - at);
            req.write(syncHeader("DATA", n));
            req.write(data, at, n);
        }
        req.write(syncHeader("DONE", (int) (System.currentTimeMillis() / 1000)));
        byte[] all = req.toByteArray();
        for (int at = 0; at < all.length; at += MAX / 2) {   // in ADB packets, each acknowledged
            int n = Math.min(MAX / 2, all.length - at);
            byte[] chunk = new byte[n];
            System.arraycopy(all, at, chunk, 0, n);
            send(WRTE, local, remote, chunk);
            expect(local, OKAY);
        }
        Msg reply = expect(local, WRTE);   // "OKAY" or "FAIL" + why
        send(OKAY, local, remote, null);
        String status = new String(reply.data, 0, Math.min(4, reply.data.length), StandardCharsets.US_ASCII);
        send(WRTE, local, remote, syncHeader("QUIT", 0));
        try { expect(local, OKAY); } catch (IOException ignored) { }
        send(CLSE, local, remote, null);
        if (!"OKAY".equals(status)) throw new IOException("push failed: " + new String(reply.data, StandardCharsets.UTF_8));
    }

    private static byte[] syncHeader(String id, int value) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.put(id.getBytes(StandardCharsets.US_ASCII));
        b.putInt(value);
        return b.array();
    }

    private Msg expect(int local, int cmd) throws IOException {
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue;
            if (m.cmd == cmd) return m;
            if (m.cmd == CLSE) throw new IOException("closed");
        }
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---- messages ----

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length, sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad length");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---- the key ----

    /** This TV's key for MonolithicOS, made once and kept in its own files. */
    private static synchronized KeyPair keys(Context c) throws IOException {
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        try {
            KeyFactory f = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                return new KeyPair(f.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        f.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair k = g.generateKeyPair();
            try (FileOutputStream o = new FileOutputStream(priv)) { o.write(k.getPrivate().getEncoded()); }
            try (FileOutputStream o = new FileOutputStream(pub)) { o.write(k.getPublic().getEncoded()); }
            return k;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream i = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = i.read(buf)) > 0; ) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    // adbd checks the token with RSA_verify(NID_sha1, ...): PKCS#1 v1.5 over SHA-1's DigestInfo and the token
    private static final byte[] SHA1_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, key);
            c.update(SHA1_INFO);
            return c.doFinal(token);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** The public key the way adbd reads it: Android's RSAPublicKey struct, base64, then a name. */
    private static byte[] publicKey(RSAPublicKey k) {
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        int words = 2048 / 32;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(r32.subtract(n.mod(r32).modInverse(r32)).intValue());   // -1 / n[0] mod 2^32
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        BigInteger rr = BigInteger.ONE.shiftLeft(2 * 2048).mod(n);       // (2^2048)^2 mod n
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " MonolithicOS@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
