package com.joe.remote;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Google's own TV remote protocol (v2, what the Google TV remote in the Google Home app speaks), used for one
 * thing: the phone app's power button. While the TV is off, Android's Low Power Standby cuts MonolithicOS off
 * the network, but Google's remote service is let through, on 6466 (the remote) and 6467 (pairing). The TV
 * itself only pairs: the service turns away remote connections from the TV's own addresses.
 *
 * Both are TLS with a certificate of our own, which the TV trusts once it's paired: the TV shows a 6-character
 * code, and the client proves it saw it. MonolithicOS pairs itself (reading the code off its own screen, see
 * the TV's PhoneWake) and gives that identity to the phone app. Messages are protobuf, each after a varint
 * length; just enough of it is written by hand here. The same file is in both apps (only the package differs).
 */
final class TvRemote {
    static final int REMOTE = 6466, PAIRING = 6467;
    private static final int KEY_POWER = 26, SHORT = 3, OK = 200;

    private final PrivateKey key;
    private final X509Certificate cert;
    /** The TV's own certificate (SHA-256, hex) from the last connection: how it's recognised at a new address. */
    volatile String seen;

    TvRemote(byte[] pkcs8, byte[] certDer) throws IOException {
        try {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(certDer));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /**
     * A new key and its self-signed certificate: {PKCS#8 key, DER certificate}. Made once per TV. Its name is
     * its own: Google's service keeps one pairing per certificate name, so a second client with the same name
     * replaces the first (seen 29 Oct, with a test client called the same).
     */
    static byte[][] identity() throws IOException {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair k = g.generateKeyPair();
            String name = String.format("MonolithicOS Remote %04x", new SecureRandom().nextInt(0x10000));
            return new byte[][]{k.getPrivate().getEncoded(), certificate(k, name)};
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    // ---- power ----

    /**
     * Turns the TV on if it's off. True: it was off and has been told to come on. False: it was already on.
     * Throws if the TV can't be reached or won't talk to us (not paired, or unpaired since).
     */
    boolean wake(String host, String model, String vendor) throws IOException {
        return power(host, model, vendor, true);
    }

    /** Turns the TV off if it's on, as its own remote would. True: it was on and has been told to go off. */
    boolean sleep(String host, String model, String vendor) throws IOException {
        return power(host, model, vendor, false);
    }

    /** Power, unless the TV is already that way: it's a toggle, so never pressed blind. */
    private boolean power(String host, String model, String vendor, boolean on) throws IOException {
        try (Link l = new Link(host, REMOTE)) {
            l.s.setSoTimeout(5000);
            while (true) {
                Proto m = new Proto(l.read());
                if (m.has(1)) {   // remote_configure: say who we are
                    Proto info = new Proto().string(1, model).string(2, vendor).varint(3, 1).string(4, "1")
                            .string(5, "com.joe.remote").string(6, "1.0");
                    l.write(new Proto().message(1, new Proto().varint(1, 622).message(2, info)));
                } else if (m.has(2)) {   // remote_set_active
                    l.write(new Proto().message(2, new Proto().varint(1, 622)));
                } else if (m.has(8)) {   // ping
                    l.write(new Proto().message(9, new Proto().varint(1, new Proto(m.bytes(8)).num(1))));
                } else if (m.has(40)) {   // remote_start: whether the TV is on
                    if ((new Proto(m.bytes(40)).num(1) != 0) == on) return false;
                    // while the TV is on, it sets up a virtual remote (an input device) for us after this and takes
                    // it away when we hang up: a key sent before it's there, or followed by hanging up at once, is
                    // lost (seen 29 Oct). Waking from off goes another way, and only needs a moment to land.
                    if (!on) stay(l, 500);
                    l.write(new Proto().message(10, new Proto().varint(1, KEY_POWER).varint(2, SHORT)));
                    stay(l, on ? 300 : 1000);
                    return true;
                } else if (m.has(3)) {
                    throw new IOException("the TV said no");
                }
            }
        }
    }

    /** Stays on the line for ms, answering pings. */
    private static void stay(Link l, long ms) throws IOException {
        long end = System.nanoTime() / 1_000_000 + ms;
        try {
            for (long left; (left = end - System.nanoTime() / 1_000_000) > 0; ) {
                l.s.setSoTimeout((int) left);
                Proto m = new Proto(l.read());
                if (m.has(8)) l.write(new Proto().message(9, new Proto().varint(1, new Proto(m.bytes(8)).num(1))));
            }
        } catch (java.net.SocketTimeoutException done) {
        } catch (EOFException gone) {   // the TV hung up: whatever it does next, it does without us
        }
    }

    /** The certificate of whatever answers on host's remote port (paired or not), or null if nothing does. */
    String id(String host) {
        seen = null;
        try (Link l = new Link(host, REMOTE)) {
            return seen;
        } catch (IOException | RuntimeException e) {
            return seen;   // turned down after showing its certificate: still that TV
        }
    }

    // ---- pairing ----

    /** Up to the TV showing its code; then finish(code). Closing without finishing cancels it on the TV. */
    Pairing pair(String host, String name) throws IOException {
        Link l = new Link(host, PAIRING);
        try {
            l.s.setSoTimeout(10000);
            Proto hex6 = new Proto().varint(1, 3).varint(2, 6);   // HEXADECIMAL, 6 symbols
            l.pairing(new Proto().message(10, new Proto().string(1, "atvremote").string(2, name)), 11);
            l.pairing(new Proto().message(20, new Proto().message(1, hex6).varint(3, 1)), 20);   // we type it in
            l.pairing(new Proto().message(30, new Proto().message(1, hex6).varint(2, 1)), 31);
            return new Pairing(l);
        } catch (IOException | RuntimeException e) {
            l.close();
            throw e;
        }
    }

    /** Nothing answered at that address at all (as opposed to the TV turning us down). */
    static final class Unreachable extends IOException {
        Unreachable(IOException cause) { super(cause); }
    }

    /**
     * Something answered and turned our certificate down: not paired (any more), or another device. It shows
     * up as a TLS alert, or just the connection dropped mid-handshake or before the first message, so all of
     * those count (a timeout doesn't: that's a slow network).
     */
    static final class Rejected extends IOException {
        Rejected(IOException cause) { super(cause); }
    }

    private static String fingerprint(X509Certificate c) {
        try {
            StringBuilder h = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(c.getEncoded())) h.append(String.format(Locale.US, "%02x", b));
            return h.toString();
        } catch (java.security.GeneralSecurityException e) {
            return null;
        }
    }

    final class Pairing implements Closeable {
        private final Link l;

        private Pairing(Link l) { this.l = l; }

        /** False: that isn't the code on screen (nothing was sent). Throws if the TV turns it down. */
        boolean finish(String code) throws IOException {
            code = code.trim().toUpperCase(Locale.US);
            if (!code.matches("[0-9A-F]{6}")) return false;
            RSAPublicKey mine = (RSAPublicKey) cert.getPublicKey(), tv = (RSAPublicKey) l.tv.getPublicKey();
            byte[] secret;
            try {
                MessageDigest d = MessageDigest.getInstance("SHA-256");
                d.update(unsigned(mine.getModulus()));
                d.update(unsigned(mine.getPublicExponent()));
                d.update(unsigned(tv.getModulus()));
                d.update(unsigned(tv.getPublicExponent()));
                d.update(unsigned(new BigInteger(code.substring(2), 16), 2));
                secret = d.digest();
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException(e);
            }
            // the code's first byte is the hash's first byte: a check that we read it right
            if ((secret[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) return false;
            l.pairing(new Proto().message(40, new Proto().bytes(1, secret)), 41);
            return true;
        }

        @Override public void close() { l.close(); }
    }

    // ---- the connection ----

    private final class Link implements Closeable {
        final SSLSocket s;
        final InputStream in;
        final OutputStream out;
        X509Certificate tv;

        Link(String host, int port) throws IOException {
            X509TrustManager any = new X509TrustManager() {   // the TV's certificate is its own self-signed one
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {
                    tv = c[0];
                    seen = fingerprint(c[0]);
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            try {
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                ks.setKeyEntry("me", key, new char[0], new Certificate[]{cert});
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, new char[0]);
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(kmf.getKeyManagers(), new TrustManager[]{any}, new SecureRandom());
                Socket raw = new Socket();
                try {
                    raw.connect(new InetSocketAddress(host, port), 2500);
                } catch (IOException e) {
                    raw.close();
                    throw new Unreachable(e);
                }
                s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
                s.setSoTimeout(5000);
                try {
                    s.startHandshake();
                } catch (java.net.SocketTimeoutException e) {
                    s.close();
                    throw e;
                } catch (IOException e) {
                    s.close();
                    throw new Rejected(e);
                }
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException(e);
            }
            in = s.getInputStream();
            out = s.getOutputStream();
        }

        void write(Proto m) throws IOException {
            byte[] b = m.toBytes();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            putVarint(o, b.length);
            o.write(b);
            out.write(o.toByteArray());
            out.flush();
        }

        private boolean heard;

        byte[] read() throws IOException {
            if (!heard) {   // the first message is where TLS 1.3 tells a client it was turned down
                try {
                    byte[] b = readMessage();
                    heard = true;
                    return b;
                } catch (java.net.SocketTimeoutException e) {
                    throw e;
                } catch (IOException e) {
                    throw new Rejected(e);
                }
            }
            return readMessage();
        }

        private byte[] readMessage() throws IOException {
            long n = 0;
            for (int shift = 0; ; shift += 7) {
                int c = in.read();
                if (c < 0) throw new EOFException();
                n |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) break;
            }
            if (n > 1 << 20) throw new IOException("too long");
            byte[] b = new byte[(int) n];
            for (int at = 0; at < b.length; ) {
                int r = in.read(b, at, b.length - at);
                if (r < 0) throw new EOFException();
                at += r;
            }
            return b;
        }

        /** A pairing step: protocol 2, status OK, and the reply must be OK and carry field `expect`. */
        void pairing(Proto body, int expect) throws IOException {
            write(new Proto().varint(1, 2).varint(2, OK).append(body));
            Proto r = new Proto(read());
            if (r.num(2) != OK) throw new IOException("the TV said " + r.num(2));
            if (!r.has(expect)) throw new IOException("unexpected reply");
        }

        @Override public void close() {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    // ---- protobuf, by hand ----

    /** Writes fields in order, or reads one message's fields (varints and length-delimited only). */
    static final class Proto {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();
        private final java.util.Map<Integer, Object> f = new java.util.HashMap<>();

        Proto() { }

        Proto(byte[] b) throws IOException {
            int[] at = {0};
            while (at[0] < b.length) {
                long tag = readVarint(b, at);
                int field = (int) (tag >>> 3), type = (int) (tag & 7);
                if (type == 0) {
                    f.put(field, readVarint(b, at));
                } else if (type == 2) {
                    int n = (int) readVarint(b, at);
                    if (n < 0 || at[0] + n > b.length) throw new IOException("bad message");
                    byte[] v = new byte[n];
                    System.arraycopy(b, at[0], v, 0, n);
                    at[0] += n;
                    f.put(field, v);
                } else if (type == 1) {
                    at[0] += 8;
                } else if (type == 5) {
                    at[0] += 4;
                } else {
                    throw new IOException("bad message");
                }
            }
        }

        boolean has(int field) { return f.containsKey(field); }

        long num(int field) { Object v = f.get(field); return v instanceof Long ? (Long) v : 0; }

        byte[] bytes(int field) { Object v = f.get(field); return v instanceof byte[] ? (byte[]) v : new byte[0]; }

        Proto varint(int field, long v) {
            putVarint(o, (long) field << 3);
            putVarint(o, v);
            return this;
        }

        Proto bytes(int field, byte[] v) {
            putVarint(o, ((long) field << 3) | 2);
            putVarint(o, v.length);
            o.write(v, 0, v.length);
            return this;
        }

        Proto string(int field, String v) { return bytes(field, (v == null ? "" : v).getBytes(StandardCharsets.UTF_8)); }

        Proto message(int field, Proto m) { return bytes(field, m.toBytes()); }

        Proto append(Proto m) { byte[] b = m.toBytes(); o.write(b, 0, b.length); return this; }

        byte[] toBytes() { return o.toByteArray(); }

        private static long readVarint(byte[] b, int[] at) throws IOException {
            long n = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (at[0] >= b.length) throw new IOException("bad message");
                int c = b[at[0]++] & 0xFF;
                n |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) return n;
            }
            throw new IOException("bad message");
        }
    }

    private static void putVarint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    /** A number's bytes, big-endian, without the sign byte Java adds. */
    private static byte[] unsigned(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length > 1 && b[0] == 0) return java.util.Arrays.copyOfRange(b, 1, b.length);
        return b;
    }

    /** Exactly `len` bytes, big-endian (the code's last 4 hex digits are 2 bytes, leading zeros kept). */
    private static byte[] unsigned(BigInteger n, int len) {
        byte[] b = unsigned(n), out = new byte[len];
        System.arraycopy(b, Math.max(0, b.length - len), out, Math.max(0, len - b.length), Math.min(len, b.length));
        return out;
    }

    // ---- a self-signed certificate, in DER by hand (Android has no public API for making one) ----

    private static byte[] certificate(KeyPair k, String name) throws java.security.GeneralSecurityException {
        byte[] sha256Rsa = der(0x30, der(0x06, new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B}),
                new byte[]{0x05, 0x00});
        byte[] cn = der(0x30, der(0x31, der(0x30, der(0x06, new byte[]{0x55, 0x04, 0x03}),
                der(0x0C, name.getBytes(StandardCharsets.UTF_8)))));
        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        byte[] validity = der(0x30, der(0x17, utc.format(new Date(System.currentTimeMillis() - 86_400_000L)).getBytes(StandardCharsets.US_ASCII)),
                der(0x18, "20991231235959Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7F;
        serial[0] |= 0x01;
        byte[] tbs = der(0x30, der(0xA0, der(0x02, new byte[]{2})), der(0x02, serial), sha256Rsa, cn, validity, cn,
                k.getPublic().getEncoded());
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(k.getPrivate());
        sig.update(tbs);
        byte[] s = sig.sign(), bits = new byte[s.length + 1];
        System.arraycopy(s, 0, bits, 1, s.length);
        return der(0x30, tbs, sha256Rsa, der(0x03, bits));
    }

    private static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        int n = body.size();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        if (n < 0x80) {
            o.write(n);
        } else if (n < 0x100) {
            o.write(0x81);
            o.write(n);
        } else {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n);
        }
        byte[] b = body.toByteArray();
        o.write(b, 0, b.length);
        return o.toByteArray();
    }
}
