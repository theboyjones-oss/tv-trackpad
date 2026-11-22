package com.example.tvtrackpad;

import android.util.Base64;

import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * The phone's ADB identity: an RSA-2048 key pair kept in app storage, so the TV's
 * "Always allow from this computer" choice keeps working across app restarts and updates.
 */
final class AdbKeys {

    private static final byte[] SHA1_DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final RSAPrivateKey priv;
    private final RSAPublicKey pub;

    private AdbKeys(PrivateKey priv, PublicKey pub) {
        this.priv = (RSAPrivateKey) priv;
        this.pub = (RSAPublicKey) pub;
    }

    static AdbKeys loadOrCreate(File dir) throws Exception {
        File priFile = new File(dir, "adbkey"), pubFile = new File(dir, "adbkey.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priFile.exists() && pubFile.exists()) {
            try {
                return new AdbKeys(
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priFile.toPath()))),
                    kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pubFile.toPath()))));
            } catch (Exception corrupt) { /* fall through and make a new pair */ }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        Files.write(priFile.toPath(), kp.getPrivate().getEncoded());
        Files.write(pubFile.toPath(), kp.getPublic().getEncoded());
        return new AdbKeys(kp.getPrivate(), kp.getPublic());
    }

    /** PKCS#1 v1.5 signature of the 20-byte token, treated as a SHA-1 digest (what adbd verifies). */
    byte[] sign(byte[] token) {
        BigInteger n = priv.getModulus();
        int len = (n.bitLength() + 7) / 8;
        byte[] block = new byte[len];
        int tLen = SHA1_DIGEST_INFO.length + token.length;
        int sep = len - tLen - 1;
        block[0] = 0;
        block[1] = 1;
        for (int i = 2; i < sep; i++) block[i] = (byte) 0xFF;
        block[sep] = 0;
        System.arraycopy(SHA1_DIGEST_INFO, 0, block, sep + 1, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, block, sep + 1 + SHA1_DIGEST_INFO.length, token.length);
        BigInteger s = new BigInteger(1, block).modPow(priv.getPrivateExponent(), n);
        return bigEndian(s, len);
    }

    /** Public key in adbd's own format: base64(struct RSAPublicKey) + " name" + NUL. */
    byte[] adbPublicKey(String name) {
        BigInteger n = pub.getModulus();
        int words = 64, len = words * 4;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + len + len + 4).order(ByteOrder.LITTLE_ENDIAN);
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(len * 8 * 2).mod(n);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, len));
        b.put(littleEndian(rr, len));
        b.putInt(pub.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " " + name + "\u0000";
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] bigEndian(BigInteger x, int len) {
        byte[] raw = x.toByteArray(), out = new byte[len];
        int copy = Math.min(raw.length, len);
        System.arraycopy(raw, raw.length - copy, out, len - copy, copy);
        return out;
    }

    private static byte[] littleEndian(BigInteger x, int len) {
        byte[] be = bigEndian(x, len), le = new byte[len];
        for (int i = 0; i < len; i++) le[i] = be[len - 1 - i];
        return le;
    }
}
