package com.zerokill;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * CROZN BYPASS — Runtime DEX Decryptor
 *
 * Bundle format (decrypted output from native AES):
 *   [4 bytes BE: N count] + N × { [4 bytes BE: len][dex bytes] }
 *
 * API 26+  → InMemoryDexClassLoader (no disk write, safer)
 * API 24-25 → DexClassLoader via getCacheDir()
 */
public final class DexDecryptor {

    private static final String TAG   = "FF_DEX";
    private static final String ASSET = "ff_core.bin";

    // JNI in dex_crypt.cpp
    static native byte[] nativeDecrypt(byte[] encryptedData);

    public static void install(Context base) {
        try {
            byte[] enc = readAsset(base, ASSET);
            if (enc == null) {
                Log.d(TAG, "Dev build — no ff_core.bin");
                return;
            }

            byte[] bundle = nativeDecrypt(enc);
            if (bundle == null || bundle.length < 4) {
                Log.e(TAG, "Decrypt failed — kill");
                android.os.Process.killProcess(android.os.Process.myPid());
                return;
            }

            // Parse multi-DEX bundle
            ByteBuffer buf = ByteBuffer.wrap(bundle).order(ByteOrder.BIG_ENDIAN);
            int count = buf.getInt();
            byte[][] dexes = new byte[count][];
            for (int i = 0; i < count; i++) {
                int len = buf.getInt();
                dexes[i] = new byte[len];
                buf.get(dexes[i]);
            }

            if (Build.VERSION.SDK_INT >= 26) {
                injectInMemory(base, dexes);
            } else {
                injectViaDisk(base, dexes);
            }
            Log.d(TAG, "DEX injected OK (" + count + " dex files)");

        } catch (Throwable t) {
            Log.e(TAG, "install: " + t.getMessage());
        }
    }

    // ── API 26+: InMemoryDexClassLoader (never written to disk) ──────────────
    @SuppressWarnings("NewApi")
    private static void injectInMemory(Context ctx, byte[][] dexes) throws Exception {
        ByteBuffer[] bufs = new ByteBuffer[dexes.length];
        for (int i = 0; i < dexes.length; i++)
            bufs[i] = ByteBuffer.wrap(dexes[i]);

        ClassLoader parent = ctx.getClassLoader();
        dalvik.system.InMemoryDexClassLoader loader =
                new dalvik.system.InMemoryDexClassLoader(bufs, parent);
        mergeDexElements(parent, loader);
    }

    // ── API 24-25: DexClassLoader via private cache dir ──────────────────────
    private static void injectViaDisk(Context ctx, byte[][] dexes) throws Exception {
        ClassLoader parent = ctx.getClassLoader();
        for (int i = 0; i < dexes.length; i++) {
            File f = new File(ctx.getCodeCacheDir(), "ff_" + i + ".dex");
            writeFile(dexes[i], f);
            dalvik.system.DexClassLoader loader = new dalvik.system.DexClassLoader(
                    f.getAbsolutePath(),
                    ctx.getCodeCacheDir().getAbsolutePath(),
                    null, parent);
            mergeDexElements(parent, loader);
            f.delete();
        }
    }

    // ── Merge loader's dexElements into parent ClassLoader ───────────────────
    @SuppressWarnings({"unchecked","rawtypes"})
    private static void mergeDexElements(ClassLoader parent, ClassLoader extra) throws Exception {
        Field plField  = findField(parent.getClass(), "pathList");
        Object pl      = plField.get(parent);
        Field eleField = findField(pl.getClass(), "dexElements");
        Object[] existing = (Object[]) eleField.get(pl);

        Field plField2  = findField(extra.getClass(), "pathList");
        Object pl2      = plField2.get(extra);
        Field eleField2 = findField(pl2.getClass(), "dexElements");
        Object[] newEle = (Object[]) eleField2.get(pl2);

        Object[] merged = (Object[]) Array.newInstance(
                existing.getClass().getComponentType(),
                existing.length + newEle.length);
        System.arraycopy(newEle,    0, merged, 0,              newEle.length);
        System.arraycopy(existing,  0, merged, newEle.length,  existing.length);
        eleField.set(pl, merged);
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name + " not in " + cls);
    }

    private static byte[] readAsset(Context ctx, String name) {
        try (InputStream is = ctx.getAssets().open(name);
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] tmp = new byte[32768]; int n;
            while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
            return buf.toByteArray();
        } catch (IOException e) { return null; }
    }

    private static void writeFile(byte[] data, File f) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
    }
}
