package com.mundo.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.VersionedPackage;
import android.os.Build;
import com.mundo.fake.frameworks.BPackageManager;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;

import com.mundo.MundoCore;
import com.mundo.app.BActivityThread;
import com.mundo.core.GmsCore;
import com.mundo.core.env.AppSystemEnv;

import com.mundo.fake.hook.BinderInvocationStub;
import com.mundo.fake.hook.MethodHook;
import com.mundo.fake.hook.ProxyMethod;
import com.mundo.fake.service.base.PkgMethodProxy;
import com.mundo.fake.service.base.ValueMethodProxy;
import com.mundo.utils.MethodParameterUtils;
import com.mundo.utils.Reflector;
import com.mundo.utils.compat.BuildCompat;
import com.mundo.utils.compat.ParceledListSliceCompat;

/**
 * Created by Milk on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class IPackageManagerProxy extends BinderInvocationStub {
    public static final String TAG = "PackageManagerStub";

    public IPackageManagerProxy() {
        super(BRActivityThread.get().sPackageManager().asBinder());
    }

    @Override
    protected Object getWho() {
        return BRActivityThread.get().sPackageManager();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRActivityThread.get()._set_sPackageManager(proxyInvocation);
        replaceSystemService("package");
        Object systemContext = BRActivityThread.get(MundoCore.mainThread()).getSystemContext();
        BRContextImpl.get(systemContext).getPackageManager();
        PackageManager mPackageManager = BRContextImpl.get(systemContext).mPackageManager();
        if (mPackageManager != null) {
            try {
                Reflector.on("android.app.ApplicationPackageManager").field("mPM").set(mPackageManager, proxyInvocation);
            } catch (Exception ignored) { }
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
        if (BuildCompat.isT()) {
            return;
        }
    }
    
    @ProxyMethod("resolveIntent")
    public static class ResolveIntent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            int flags = Integer.parseInt(args[2] + "");
            ResolveInfo resolveInfo = MundoCore.getBPackageManager().resolveIntent(intent, resolvedType, flags, BActivityThread.getUserId());
            if (resolveInfo != null) {
                return resolveInfo;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("resolveService")
    public static class ResolveService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            int flags = Integer.parseInt(args[2] + "");
            ResolveInfo resolveInfo = MundoCore.getBPackageManager().resolveService(intent, flags, resolvedType, BActivityThread.getUserId());
            if (resolveInfo != null) {
                return resolveInfo;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setComponentEnabledSetting")
    public static class SetComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("getPackageInfo")
    public static class GetPackageInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
			if (GmsCore.isGoogleAppOrService(packageName) || GmsCore.setGoogleAppOrService(packageName)) {
				return method.invoke(who, args);
			}
            PackageInfo packageInfo = MundoCore.getBPackageManager().getPackageInfo(packageName, flags, BActivityThread.getUserId());
            if (packageInfo != null) {
                return packageInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getPackageUid")
    public static class GetPackageUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }
    
    @ProxyMethod("setApplicationBlockedSettingAsUser")
    public static class setApplicationBlockedSettingAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUserId(args);
            return method.invoke(who, args);
        }
    }
    
    @ProxyMethod("getPackageUidEtc")
    public static class getPackageUidEtc extends GetPackageUid {

    }

    @ProxyMethod("getProviderInfo")
    public static class GetProviderInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ProviderInfo providerInfo = MundoCore.getBPackageManager().getProviderInfo(componentName, flags, BActivityThread.getUserId());
            if (providerInfo != null)
                return providerInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getReceiverInfo")
    public static class GetReceiverInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo receiverInfo = MundoCore.getBPackageManager().getReceiverInfo(componentName, flags, BActivityThread.getUserId());
            if (receiverInfo != null)
                return receiverInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getActivityInfo")
    public static class GetActivityInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo activityInfo = MundoCore.getBPackageManager().getActivityInfo(componentName, flags, BActivityThread.getUserId());
            if (activityInfo != null)
                return activityInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getServiceInfo")
    public static class GetServiceInfo extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ServiceInfo serviceInfo = MundoCore.getBPackageManager().getServiceInfo(componentName, flags, BActivityThread.getUserId());
            if (serviceInfo != null)
                return serviceInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getInstalledApplications")
    public static class GetInstalledApplications extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[0]);
            List<ApplicationInfo> installedApplications = MundoCore.getBPackageManager().getInstalledApplications(flags, BActivityThread.getUserId());
            return ParceledListSliceCompat.create(installedApplications);
        }
    }

    @ProxyMethod("getInstalledPackages")
    public static class GetInstalledPackages extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[0]);
            List<PackageInfo> installedPackages = MundoCore.getBPackageManager().getInstalledPackages(flags, BActivityThread.getUserId());
            return ParceledListSliceCompat.create(installedPackages);
        }
    }

    @ProxyMethod("getApplicationInfo")
    public static class GetApplicationInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            if (GmsCore.isGoogleAppOrService(packageName)) {
                return method.invoke(who, args);
            }
            ApplicationInfo applicationInfo = MundoCore.getBPackageManager().getApplicationInfo(packageName, flags, BActivityThread.getUserId());
            if (applicationInfo != null) {
                return applicationInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                ApplicationInfo info = (ApplicationInfo) method.invoke(who, args);
                if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && info.targetSdkVersion >= Build.VERSION_CODES.S) {
                    info.targetSdkVersion = Build.VERSION_CODES.R;
                }
                return info;
            }
            return null;
        }
    }

    @ProxyMethod("queryContentProviders")
    public static class QueryContentProviders extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[2]);
            List<ProviderInfo> providers = MundoCore.getBPackageManager().queryContentProviders(BActivityThread.getAppProcessName(), BActivityThread.getBUid(), flags, BActivityThread.getUserId());
            // Merge real GMS content providers so they get initialised in the process
            try {
                Object realResult = method.invoke(who, args);
                if (ParceledListSliceCompat.isParceledListSlice(realResult)) {
                    @SuppressWarnings("unchecked")
                    List<ProviderInfo> realProviders = (List<ProviderInfo>) black.android.content.pm.BRParceledListSlice.get(realResult).getList();
                    if (realProviders != null) {
                        for (ProviderInfo rp : realProviders) {
                            if (GmsCore.isGoogleAppOrService(rp.packageName)) {
                                providers.add(rp);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return ParceledListSliceCompat.create(providers);
        }
    }

    @ProxyMethod("queryIntentActivities")
    public static class QueryIntentActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            if (intent == null) return method.invoke(who, args);
            String scheme = intent.getData() != null ? intent.getData().getScheme() : null;
            // Intercept fbconnect:// (and fb<APP_ID>://) scheme queries from browsers.
            // When Chrome/Samsung Internet resolves the fbconnect:// CCT redirect, we
            // substitute the real system results with the virtual guest's activity so
            // the redirect lands inside the virtual space instead of being unhandled.
            boolean isFbScheme = scheme != null && scheme.startsWith("fb")
                    && !scheme.equals("http") && !scheme.equals("https");
            if (isFbScheme) {
                String guestPkg = com.mundo.proxy.FacebookLoginHelper.getLoginTargetPackage();
                if (guestPkg != null) {
                    List<ResolveInfo> guestResults = MundoCore.getBPackageManager()
                            .queryIntentActivities(intent, 0, null, BActivityThread.getUserId());
                    if (guestResults != null && !guestResults.isEmpty()) {
                        return ParceledListSliceCompat.create(guestResults);
                    }
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("queryIntentReceivers")
    public static class QueryBroadcastReceivers extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            String type = MethodParameterUtils.getFirstParam(args, String.class);
            Integer flags = MethodParameterUtils.getFirstParam(args, Integer.class);
            int flagValue = flags != null ? flags.intValue() : 0;
            List<ResolveInfo> resolves = MundoCore.getBPackageManager().queryBroadcastReceivers(intent, flagValue, type, BActivityThread.getUserId());

            // http://androidxref.com/7.0.0_r1/xref/frameworks/base/core/java/android/app/ApplicationPackageManager.java#872
            if (BuildCompat.isN()) {
                return ParceledListSliceCompat.create(resolves);
            }

            // http://androidxref.com/6.0.1_r10/xref/frameworks/base/core/java/android/app/ApplicationPackageManager.java#699
            return resolves;
        }
    }

    @ProxyMethod("resolveContentProvider")
    public static class ResolveContentProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String authority = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ProviderInfo providerInfo = MundoCore.getBPackageManager().resolveContentProvider(authority, flags, BActivityThread.getUserId());
            if (providerInfo == null) {
                return method.invoke(who, args);
            }
            return providerInfo;
        }
    }

    @ProxyMethod("canRequestPackageInstalls")
    public static class CanRequestPackageInstalls extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getPackagesForUid")
    public static class GetPackagesForUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int uid = MethodParameterUtils.toInt(args[0]);
            if (uid == MundoCore.getHostUid()) {
                args[0] = BActivityThread.getBUid();
                uid = MethodParameterUtils.toInt(args[0]);
            }
            String[] packagesForUid = MundoCore.getBPackageManager().getPackagesForUid(uid);
            return packagesForUid;
        }
    }

    @ProxyMethod("getInstallerPackageName")
    public static class GetInstallerPackageName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return GmsCore.VENDING_PKG;
        }
    }

    @ProxyMethod("getSharedLibraries")
    public static class GetSharedLibraries extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getComponentEnabledSetting")
    public static class getComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            String packageName = componentName.getPackageName();
            ApplicationInfo applicationInfo = MundoCore.getBPackageManager().getApplicationInfo(packageName,0, BActivityThread.getUserId());
            if(applicationInfo != null){
                return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            throw new IllegalArgumentException();
        }
    }
    
    @ProxyMethod("addPackageToPreferred")
    public static class addPackageToPreferred extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }
    
    @ProxyMethod("setSplashScreenTheme")
    public static class SetSplashScreenTheme extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            boolean isXiaomi = BuildCompat.isMIUI() || Build.MANUFACTURER.toLowerCase().contains("xiaomi") || Build.BRAND.toLowerCase().contains("xiaomi") || Build.DISPLAY.toLowerCase().contains("hyperos");
            if (isXiaomi) { return null; }
            try {
                return method.invoke(who, args);
            } catch (SecurityException ignored) {
                return null;
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    return null;
                }
                throw e;
            }
        }
    }
    
    @ProxyMethod("checkSignatures")
    public static class checkSignatures extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args.length == 2 && args[0] instanceof String && args[1] instanceof String) {
                String pkg1 = (String) args[0];
                String pkg2 = (String) args[1];
                PackageManager pm = MundoCore.getPackageManager();
                try {
                    Signature[] sigs1, sigs2;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        PackageInfo pi1 = pm.getPackageInfo(pkg1, PackageManager.GET_SIGNING_CERTIFICATES);
                        PackageInfo pi2 = pm.getPackageInfo(pkg2, PackageManager.GET_SIGNING_CERTIFICATES);
                        sigs1 = pi1.signingInfo != null ? pi1.signingInfo.getApkContentsSigners() : null;
                        sigs2 = pi2.signingInfo != null ? pi2.signingInfo.getApkContentsSigners() : null;
                    } else {
                        PackageInfo pi1 = pm.getPackageInfo(pkg1, PackageManager.GET_SIGNATURES);
                        PackageInfo pi2 = pm.getPackageInfo(pkg2, PackageManager.GET_SIGNATURES);
                        sigs1 = pi1.signatures;
                        sigs2 = pi2.signatures;
                    }
                    if (sigs1 == null || sigs1.length == 0) return (sigs2 == null || sigs2.length == 0) ? 1 : -1;
                    if (sigs2 == null || sigs2.length == 0) return -2;
                    return Arrays.equals(sigs1, sigs2) ? 0 : -3;
                } catch (Exception e) {
                    // fall through
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkUidSignatures")
    public static class checkUidSignatures extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int uid1 = MethodParameterUtils.toInt(args[0]);
            int uid2 = MethodParameterUtils.toInt(args[1]);
            // Only spoof SIGNATURE_MATCH when both UIDs belong to virtual packages
            String[] pkgs1 = MundoCore.getBPackageManager().getPackagesForUid(uid1);
            String[] pkgs2 = MundoCore.getBPackageManager().getPackagesForUid(uid2);
            if (pkgs1 != null && pkgs1.length > 0 && pkgs2 != null && pkgs2.length > 0) {
                return 0;
            }
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                return -1;
            }
        }
    }
    
    @ProxyMethod("clearPackagePersistentPreferredActivities")
    public static class clearPackagePersistentPreferredActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }
    
    @ProxyMethod("clearPackagePreferredActivities")
    public static class clearPackagePreferredActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }
    
    @ProxyMethod("getApplicationBlockedSettingAsUser")
    public static class getApplicationBlockedSettingAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }
    
    @ProxyMethod("setPackageStoppedState")
    public static class setPackageStoppedState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }
    
}
