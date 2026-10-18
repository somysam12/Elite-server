package com.bgmi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HeartbeatManager — GameZone crack-prevention loop.
 *
 * SECURITY: This class holds NO keys. The entire encrypted heartbeat
 * (AES-256-GCM + HMAC-SHA256) is performed inside the native .so
 * (Java_com_gamezone_HeartbeatManager_nativeBeat), where the AES key,
 * HMAC key and XOR keys live oxorany-obfuscated. Nothing sensitive is
 * ever compiled into classes.dex — a decompile shows only JNI calls.
 *
 * Every 60s the native beat is sent with the SAME serial the panel
 * registered at login (nativeGetSerial), so the device always matches.
 *
 * nativeBeat() return (q):
 *    7  = valid            → reset strikes
 *    1  = warn             → skip (no strike)
 *   <0  = network/unset    → transient, no strike (never crashes on this)
 *   else(2..6,0)= invalid/expired/blocked/device-mismatch → strike
 *
 * 2-STRIKE CRASH: 1st bad beat = skip, 2nd consecutive = killProcess().
 */
public class HeartbeatManager {

    private static final String TAG = "HB";

    private static final String PREFS = "ark_hb";
    private static final String P_KEY = "k1";
    private static final String P_DEV = "d1";
    private static final String STORE_XOR = "ARK_9F2B";

    private static volatile int     badBeat  = 0;
    private static volatile boolean running  = false;
    private static ScheduledExecutorService sched;
    private static Context appCtx;

    // Native integrity (security_checks.cpp) — libnull.so / sniffer / resign check
    static native boolean nativeIntegrityOk(Context ctx);
    // Native encrypted heartbeat — all crypto + secrets live in the .so
    static native int nativeBeat(String key, String serial, String pkg);

    static { System.loadLibrary("shaitaan"); }

    // ── Call on login SUCCESS. `serial` MUST be nativeGetSerial() so the
    //    heartbeat device matches what the panel registered at login. ──────
    public static void onLoginSuccess(Context ctx, String key, String serial) {
        appCtx = ctx.getApplicationContext();
        saveKey(ctx, key, serial);
        badBeat = 0;
        start(ctx);
    }

    public static void stop() {
        running = false;
        if (sched != null && !sched.isShutdown()) sched.shutdownNow();
    }

    private static void start(final Context ctx) {
        if (running) return;
        running = true;
        sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ark_hb"); t.setDaemon(true); return t;
        });
        // first beat @10s, then every 60s
        sched.scheduleWithFixedDelay(() -> {
            try {
                boolean ok;
                try { ok = nativeIntegrityOk(ctx); }
                catch (Throwable t) { ok = true; }   // don't crash on link error alone
                if (!ok) { crash("integrity"); return; }

                String key = loadKey(ctx), dev = loadDev(ctx);
                if (key == null || key.isEmpty() || dev == null) { bad("no_key"); return; }

                String pkg = ctx.getPackageName();
                int q;
                try { q = nativeBeat(key, dev, pkg); }
                catch (Throwable t) { q = -1; }       // link/native error = transient

                if (q == 7)      { badBeat = 0; }     // valid
                else if (q == 1) { /* warn: skip */ }
                else if (q < 0)  { /* network / endpoint unset: transient, no strike */ }
                else             { bad("q" + q); }    // 2..6/0 = invalid → strike
            } catch (Exception e) {
                // network error = transient, not a crash
                Log.w(TAG, "beat: " + e.getMessage());
            }
        }, 10L, 60L, TimeUnit.SECONDS);
    }

    // ── 2-strike ─────────────────────────────────────────────────────────
    private static void bad(String why) {
        badBeat++;
        Log.e(TAG, "bad#" + badBeat + " " + why);
        if (badBeat >= 2) crash(why);   // 1st = skip, 2nd = crash
    }

    private static void crash(String why) {
        running = false;
        if (sched != null) sched.shutdownNow();
        new Handler(Looper.getMainLooper()).post(() -> {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        });
    }

    // ── Key/serial storage (XOR obfuscated) ──────────────────────────────
    private static void saveKey(Context ctx, String k, String d) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString(P_KEY, xEnc(k)); e.putString(P_DEV, xEnc(d)); e.apply();
    }
    private static String loadKey(Context ctx) {
        // Primary: read from .lic file (shared with native)
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader(android.os.Environment.getExternalStorageDirectory()
                    + "/Android/BeastCrown.lic"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("KEY=") && line.length() > 4) {
                    br.close();
                    return line.substring(4).trim();
                }
            }
            br.close();
        } catch (Exception ignored) {}
        String v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(P_KEY, null);
        return v != null ? xDec(v) : null;
    }
    private static String loadDev(Context ctx) {
        String v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(P_DEV, null);
        return v != null ? xDec(v) : null;
    }

    // ── XOR obfuscation for local storage only (not a secret) ────────────
    private static String xEnc(String s) {
        byte[] d = s.getBytes(StandardCharsets.UTF_8), k = STORE_XOR.getBytes(StandardCharsets.UTF_8);
        byte[] o = new byte[d.length];
        for (int i = 0; i < d.length; i++) o[i] = (byte)(d[i] ^ k[i % k.length]);
        return Base64.encodeToString(o, Base64.NO_WRAP);
    }
    private static String xDec(String s) {
        try {
            byte[] d = Base64.decode(s, Base64.NO_WRAP), k = STORE_XOR.getBytes(StandardCharsets.UTF_8);
            byte[] o = new byte[d.length];
            for (int i = 0; i < d.length; i++) o[i] = (byte)(d[i] ^ k[i % k.length]);
            return new String(o, StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }
}
