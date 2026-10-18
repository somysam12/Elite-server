package com.bgmi;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.multidex.MultiDex;

import com.mundo.MundoCore;
import com.mundo.app.configuration.ClientConfiguration;
import com.bgmi.utils.IntegrityGuard;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;

@SuppressWarnings({"unused","deprecation"})
public class ArkApplication extends Application {

    static {
        try { System.loadLibrary("shaitaan"); }
        catch (UnsatisfiedLinkError ignored) {}
    }

    private static final String TAG = "ArkApp";

    @Override
    protected void attachBaseContext(Context base) {
        // Bypass hidden API restrictions early — must run before any reflection on hidden APIs.
        // Fixes crashes on Android 15/16 where FreeReflection alone is insufficient.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { HiddenApiBypass.addHiddenApiExemptions("L"); }
            catch (Throwable t) { Log.w(TAG, "HiddenApiBypass failed: " + t.getMessage()); }
        }

        super.attachBaseContext(base);
        IntegrityGuard.verifyOrDie(base);
        MultiDex.install(base);

        // Decrypt + load the panel dex payload
        try { DexDecryptor.install(base); }
        catch (Throwable e) { Log.e(TAG, "Dex: " + e.getMessage()); }

        // DIAGNOSTIC: log lib state at every process start (dual-lib: list all .so in loaderDir)
        try {
            File loaderDir = new File(base.getFilesDir(), "loader");
            java.io.File[] soFiles = loaderDir.listFiles(f -> f.getName().endsWith(".so"));
            int count = (soFiles != null) ? soFiles.length : 0;
            Log.d(TAG, "PROCESS START — loaderDir=" + loaderDir.exists()
                  + " soFiles=" + count
                  + " process=" + getProcessNameCompat());
        } catch (Exception ignored) {}

        try {
            MundoCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override public String getHostPackageName() { return base.getPackageName(); }
                @Override public boolean isEnableDaemonService() { return false; }
                @Override public boolean requestInstallPackage(File file) { return false; }
            });
        } catch (Exception e) {
            Log.e(TAG, "attachBaseContext: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try { MundoCore.get().doCreate(); }
        catch (Exception e) { Log.e(TAG, "MundoCore: " + e.getMessage()); }
        try { com.mundo.core.system.api.MetaActivationManager.activateSdk("HCORE"); }
        catch (Exception e) { Log.e(TAG, "SDK: " + e.getMessage()); }
        try { disableFirebase(); } catch (Exception ignored) {}
    }

    private String getProcessNameCompat() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28)
                return Application.getProcessName();
            int pid = android.os.Process.myPid();
            android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses())
                if (p.pid == pid) return p.processName;
        } catch (Exception ignored) {}
        return "?";
    }

    private void disableFirebase() throws Exception {
        Class<?> fb = Class.forName("com.google.firebase.messaging.FirebaseMessaging");
        java.lang.reflect.Method m = fb.getDeclaredMethod("setAutoInitEnabled", boolean.class);
        m.invoke(fb.getMethod("getInstance").invoke(null), false);
    }
}
