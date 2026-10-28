package com.zerokill.utils;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// Panel crypto — MUST match the panel's secrets.php / native aes_gcm_encrypt format:
//   output = nonce[12] + tag[16] + ciphertext  (base64url, no padding)
public final class PanelCrypto {

    private static final int GCM_NONCE_LEN = 12;
    private static final int GCM_TAG_BITS  = 128;
    private static final int GCM_TAG_LEN   = 16;

    private PanelCrypto() {}

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                + Character.digit(hex.charAt(i + 1), 16));
        return out;
    }

    private static String b64UrlEncode(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] b64UrlDecode(String s) {
        return Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    public static String aesEncrypt(String plaintext, String hexKey) {
        try {
            byte[] key = hexToBytes(hexKey);
            byte[] nonce = new byte[GCM_NONCE_LEN];
            new SecureRandom().nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                   new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] tag = new byte[GCM_TAG_LEN];
            byte[] raw = new byte[ct.length - GCM_TAG_LEN];
            System.arraycopy(ct, ct.length - GCM_TAG_LEN, tag, 0, GCM_TAG_LEN);
            System.arraycopy(ct, 0, raw, 0, ct.length - GCM_TAG_LEN);
            byte[] out = new byte[GCM_NONCE_LEN + GCM_TAG_LEN + raw.length];
            System.arraycopy(nonce, 0, out, 0, GCM_NONCE_LEN);
            System.arraycopy(tag,   0, out, GCM_NONCE_LEN, GCM_TAG_LEN);
            System.arraycopy(raw,   0, out, GCM_NONCE_LEN + GCM_TAG_LEN, raw.length);
            return b64UrlEncode(out);
        } catch (Exception e) {
            return null;
        }
    }

    public static String aesDecrypt(String b64url, String hexKey) {
        try {
            byte[] all = b64UrlDecode(b64url);
            if (all.length < GCM_NONCE_LEN + GCM_TAG_LEN + 1) return null;
            byte[] key   = hexToBytes(hexKey);
            byte[] nonce = new byte[GCM_NONCE_LEN];
            byte[] tag   = new byte[GCM_TAG_LEN];
            System.arraycopy(all, 0, nonce, 0, GCM_NONCE_LEN);
            System.arraycopy(all, GCM_NONCE_LEN, tag, 0, GCM_TAG_LEN);
            int ctLen = all.length - GCM_NONCE_LEN - GCM_TAG_LEN;
            byte[] ctWithTag = new byte[ctLen + GCM_TAG_LEN];
            System.arraycopy(all, GCM_NONCE_LEN + GCM_TAG_LEN, ctWithTag, 0, ctLen);
            System.arraycopy(tag, 0, ctWithTag, ctLen, GCM_TAG_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                   new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] plain = c.doFinal(ctWithTag);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String xorDecode(String encoded) {
        try {
            byte[] raw = Base64.decode(encoded, Base64.DEFAULT);
            byte[] out = new byte[raw.length];
            for (int i = 0; i < raw.length; i++)
                out[i] = (byte) (raw[i] ^ 0xA5);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // Retained for IntegrityGuard (signature-hash hex formatting).
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    public static boolean verifySignedResponse(String response, String hmacKey, String aesKey) {
        int dot = response.lastIndexOf('.');
        if (dot < 0) return false;
        String body = response.substring(0, dot);
        String sig  = response.substring(dot + 1);
        return hmacSha256(body, hmacKey).equals(sig);
    }

    public static String decryptSignedResponse(String response, String hmacKey, String aesKey) {
        int dot = response.lastIndexOf('.');
        if (dot < 0) return null;
        String body = response.substring(0, dot);
        String sig  = response.substring(dot + 1);
        if (!hmacSha256(body, hmacKey).equals(sig)) return null;
        return aesDecrypt(body, aesKey);
    }
}
