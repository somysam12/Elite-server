package com.mundo.fake.service;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.BRAppOpsManager;
import black.android.os.BRServiceManager;
import black.com.android.internal.app.BRIAppOpsServiceStub;
import com.mundo.MundoCore;
import com.mundo.fake.hook.BinderInvocationStub;
import com.mundo.fake.hook.MethodHook;
import com.mundo.fake.hook.ProxyMethod;
import com.mundo.utils.MethodParameterUtils;
import com.mundo.core.GmsCore;

/**
 * Created by @jagdish_vip on 4/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class IAppOpsManagerProxy extends BinderInvocationStub {
    public IAppOpsManagerProxy() {
        super(BRServiceManager.get().getService(Context.APP_OPS_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder call = BRServiceManager.get().getService(Context.APP_OPS_SERVICE);
        return BRIAppOpsServiceStub.get().asInterface(call);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRAppOpsManager.get(null)._check_mService() != null) {
            AppOpsManager appOpsManager = (AppOpsManager) MundoCore.getContext().getSystemService(Context.APP_OPS_SERVICE);
            try {
                BRAppOpsManager.get(appOpsManager)._set_mService(getProxyInvocation());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        replaceSystemService(Context.APP_OPS_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Do not replace GMS package names — they must reach real AppOps under their own identity
        if (!isGmsCall(args)) {
            MethodParameterUtils.replaceFirstAppPkg(args);
        }
        MethodParameterUtils.replaceLastUid(args);
        return super.invoke(proxy, method, args);
    }

    private static boolean isGmsCall(Object[] args) {
        if (args == null) return false;
        for (Object a : args) {
            if (a instanceof String && GmsCore.isGoogleAppOrService((String) a)) return true;
        }
        return false;
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("noteProxyOperation")
    public static class NoteProxyOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                return AppOpsManager.MODE_ALLOWED;
            }
        }
    }

    @ProxyMethod("checkPackage")
    public static class CheckPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                return AppOpsManager.MODE_ALLOWED;
            }
        }
    }

    @ProxyMethod("checkOperation")
    public static class CheckOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("noteOperation")
    public static class NoteOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Do NOT pass through to the real system.
            // If getNameForUid returns the virtual package name ("com.pubg.imobile"), GMS
            // dynamite constructs noteOperation(op, hostUid, "com.pubg.imobile"). The system
            // rejects this because hostUid != uid-of-com.pubg.imobile → SecurityException.
            // Returning MODE_ALLOWED unconditionally is the pattern used by every upstream
            // reference: editech-dev/vortex-one (cc4093a), JDKaintscared/BlackBox (f7bc7e9),
            // and ALEX5402/NewBlackbox (89b5983). The getNameForUid hook removal is the root-
            // cause fix; this is a defence-in-depth guard that prevents the same crash if
            // any other code path passes a virtual package name into noteOperation.
            return AppOpsManager.MODE_ALLOWED;
        }
    }
}
