package com.mundo.fake.service;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Build.VERSION_CODES;

import java.lang.reflect.Method;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;
import black.android.os.BRServiceManager;
import black.android.permission.BRIPermissionManagerStub;

import com.mundo.MundoCore;
import com.mundo.core.GmsCore;
import com.mundo.fake.hook.BinderInvocationStub;
import com.mundo.fake.hook.MethodHook;
import com.mundo.fake.hook.ProxyMethod;
import com.mundo.fake.service.base.PkgMethodProxy;
import com.mundo.fake.service.base.ValueMethodProxy;
import com.mundo.utils.MethodParameterUtils;
import com.mundo.utils.Reflector;
import com.mundo.utils.compat.BuildCompat;

/**
 * Fixed for Android 10–15
 * No mid-game crash
 */
public class IPermissionManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IPermissionManagerProxy";

    private static final String P = "permissionmgr";

    public IPermissionManagerProxy() {
        super(BRServiceManager.get().getService(P));
    }

    @Override
    protected Object getWho() {
        return BRIPermissionManagerStub.get().asInterface(BRServiceManager.get().getService(P));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("permissionmgr");
        // ActivityThread.sPermissionManager is typed IPermissionManager on all API levels (confirmed
        // in AOSP android-16.0.0_r1). The proxy implements IPermissionManager so this always works.
        BRActivityThread.getWithException()._set_sPermissionManager(proxyInvocation);
        Object systemContext = BRActivityThread.get(MundoCore.mainThread()).getSystemContext();
        PackageManager packageManager = BRContextImpl.get(systemContext).mPackageManager();
        if (packageManager != null) {
            if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
                // API 31+: ApplicationPackageManager.mPermissionManager has type
                // android.permission.PermissionManager (concrete class, not IPermissionManager
                // interface). A JDK proxy cannot be assigned to a concrete-class-typed field.
                // Instead, obtain the existing PermissionManager wrapper and inject our proxy
                // into its inner IPermissionManager mPermissionManager field (which IS an
                // interface-typed field and accepts the proxy).
                // Note: On API 35+ ApplicationPackageManager.checkPermission() routes through
                // this PermissionManager instance → cache → ActivityThread.getPermissionManager(),
                // so replacing the inner field ensures that instance-method path is also hooked.
                try {
                    Object permMgr = Reflector.on("android.app.ApplicationPackageManager")
                            .field("mPermissionManager")
                            .get(packageManager);
                    if (permMgr == null) {
                        // The field is lazily initialised; force initialisation by reflection
                        // so we have a live PermissionManager instance to drill into.
                        permMgr = Reflector.on("android.app.ApplicationPackageManager")
                                .method("getPermissionManager")
                                .callByCaller(packageManager);
                    }
                    if (permMgr != null) {
                        Reflector.on("android.permission.PermissionManager")
                                .field("mPermissionManager")
                                .set(permMgr, proxyInvocation);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                // Pre-API 31: ApplicationPackageManager.mPermissionManager is IPermissionManager
                // (an AIDL interface), so the proxy can be set directly.
                try {
                    Reflector.on("android.app.ApplicationPackageManager")
                            .field("mPermissionManager")
                            .set(packageManager, proxyInvocation);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addPermissionAsync", true));
        addMethodHook(new ValueMethodProxy("addPermission", true));
        addMethodHook(new ValueMethodProxy("performDexOpt", true));
        addMethodHook(new ValueMethodProxy("performDexOptIfNeeded", false));
        addMethodHook(new ValueMethodProxy("performDexOptSecondary", true));
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("checkDeviceIdentifierAccess", false));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
        if (BuildCompat.isOreo()) {
            addMethodHook(new ValueMethodProxy("notifyDexLoad", 0));
            addMethodHook(new ValueMethodProxy("notifyPackageUse", 0));
            addMethodHook(new ValueMethodProxy("setInstantAppCookie", false));
            addMethodHook(new ValueMethodProxy("isInstantApp", false));
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("checkPermission")
    public static class CheckPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // IPermissionManager.checkPermission was added in API 35 (Android 15) with signature:
            //   int checkPermission(String packageName, String permissionName,
            //                       String persistentDeviceId, int userId)
            // args[0] = packageName (virtual pkg, e.g. "com.pubg.imobile")
            // args[1] = permissionName (e.g. "android.permission.INTERNET")
            // This method does not exist on API < 35, so on any version the hook fires, use
            // the API 35+ index layout.
            String permission = (args != null && args.length > 1) ? (String) args[1] : null;
            if (isGoogleInternalPermission(permission)) return PackageManager.PERMISSION_GRANTED;
            // Replace args[0] (packageName) virtual pkg → host pkg so the real PM can
            // resolve INTERNET/ACCESS_NETWORK_STATE etc. for the container package.
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkUidPermission")
    public static class CheckUidPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // IPermissionManager.checkUidPermission was added in API 35 with signature:
            //   int checkUidPermission(int uid, String permissionName, int deviceId)
            // args[0] = uid (Integer), args[1] = permissionName, args[2] = deviceId
            // IMPORTANT: args[0] is an int, NOT a String — do NOT call (String) args[0].
            String permission = (args != null && args.length > 1) ? (String) args[1] : null;
            if (isGoogleInternalPermission(permission)) return PackageManager.PERMISSION_GRANTED;
            // Replace args[0] (uid) if it matches the virtual app's UID → host UID.
            // replaceFirstUid scans from index 0 so it targets the uid arg, not deviceId.
            MethodParameterUtils.replaceFirstUid(args);
            return method.invoke(who, args);
        }
    }

    private static boolean isGoogleInternalPermission(String permission) {
        if (permission == null) return false;
        return permission.startsWith("com.google.android.gms.permission.")
            || permission.startsWith("com.google.android.providers.gsf.permission.")
            || permission.equals("com.android.vending.INTENT_VENDING_ONLY")
            || permission.equals("com.android.vending.TOS_ACKED")
            || permission.equals("com.android.vending.BILLING")
            || permission.equals("android.permission.GET_ACCOUNTS")
            || permission.equals("android.permission.MANAGE_ACCOUNTS")
            || permission.equals("android.permission.USE_CREDENTIALS")
            || permission.equals("android.permission.AUTHENTICATE_ACCOUNTS")
            || permission.equals("android.permission.ACCOUNT_MANAGER");
    }

}
