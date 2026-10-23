package com.bgmi.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.security.MessageDigest;

public class IntegrityGuard {

    public static native String expectedSigHash();

    public static void verifyOrDie(Context ctx) {
        try {
            Signature[] sigs = ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES)
                .signatures;
            if (sigs == null || sigs.length == 0) kill();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(sigs[0].toByteArray());
            String actual = PanelCrypto.bytesToHex(md.digest());
            String expected = expectedSigHash();
            if (!actual.equalsIgnoreCase(expected)) kill();
        } catch (Exception e) {
            kill();
        }
    }

    private static void kill() {
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }
}
