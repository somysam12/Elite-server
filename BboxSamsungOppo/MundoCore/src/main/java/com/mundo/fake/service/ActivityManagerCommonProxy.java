package com.mundo.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;

import com.mundo.core.env.BEnvironment;
import java.io.File;
import java.lang.reflect.Method;

import com.mundo.MundoCore;
import com.mundo.app.BActivityThread;
import com.mundo.core.GmsCore;
import com.mundo.fake.hook.MethodHook;
import com.mundo.fake.hook.ProxyMethod;
import com.mundo.fake.provider.FileProviderHandler;
import com.mundo.proxy.GmsExternalActivity;
import com.mundo.proxy.ProxyWebActivity;
import com.mundo.proxy.FacebookLoginHelper;
import com.mundo.utils.ComponentUtils;
import com.mundo.utils.FileUtils;
import com.mundo.utils.MethodParameterUtils;
import com.mundo.utils.Slog;
import com.mundo.utils.compat.BuildCompat;
import com.mundo.utils.compat.StartActivityCompat;
import static android.content.pm.PackageManager.GET_META_DATA;
import org.lsposed.lsparanoid.Obfuscate;
/**
 * Created by @jagdish_vip on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@Obfuscate
public class ActivityManagerCommonProxy {
    
    public static final String TAG = "ActivityManagerCommonProxy";

    @ProxyMethod("startActivity")
    public static class StartActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            Intent intent = getIntent(args);
            assert intent != null;
            
            if (intent.getParcelableExtra("_G_|_target_") != null) {
                return method.invoke(who, args);
            }
            if (ComponentUtils.isRequestInstall(intent)) {
                File file = FileProviderHandler.convertFile(BActivityThread.getApplication(), intent.getData());
                if (MundoCore.get().requestInstallPackage(file)) {
                    return 0;
                }
                intent.setData(FileProviderHandler.convertFileUri(BActivityThread.getApplication(), intent.getData()));
                return method.invoke(who, args);
            }
            String dataString = intent.getDataString();
            if (dataString != null && dataString.equals("package:" + BActivityThread.getAppPackageName())) {
                intent.setData(Uri.parse("package:" + MundoCore.getHostPkg()));
            }

            // ---- Facebook Login Fix ----
            // 1. Guest is launching the FB OAuth Custom Tab (to a real browser):
            //    remember who is logging in so the fbconnect:// redirect can be
            //    routed back into this guest, then let it open in real Chrome
            //    (real browser = FB does NOT reject it like a WebView).
            if (FacebookLoginHelper.isFacebookOAuthIntent(intent)) {
                FacebookLoginHelper.recordLoginTarget(
                        BActivityThread.getAppPackageName(), BActivityThread.getUserId());
                String browser = FacebookLoginHelper.getBestCustomTabBrowser(BActivityThread.getApplication());
                if (browser != null) {
                    intent.setPackage(browser);
                }
                return method.invoke(who, args);
            }
            // 2. A raw fbconnect:// / fb{APP_ID}:// redirect surfacing inside the
            //    guest: resolve it against the guest and route it in-space.
            if (FacebookLoginHelper.isFacebookRedirect(intent)
                    || (intent.getScheme() != null && intent.getScheme().startsWith("fb")
                        && !"http".equals(intent.getScheme()) && !"https".equals(intent.getScheme()))) {
                Intent fbIntent = new Intent(intent);
                fbIntent.setComponent(null);
                fbIntent.setPackage(BActivityThread.getAppPackageName());
                ResolveInfo fbInfo = MundoCore.getBPackageManager().resolveActivity(
                        fbIntent, FileUtils.FileMode.MODE_IWUSR,
                        StartActivityCompat.getResolvedType(args), BActivityThread.getUserId());
                if (fbInfo != null) {
                    fbIntent.setComponent(new ComponentName(
                            fbInfo.activityInfo.packageName, fbInfo.activityInfo.name));
                    fbIntent.setExtrasClassLoader(who.getClass().getClassLoader());
                    MundoCore.getBActivityManager().startActivityAms(
                            BActivityThread.getUserId(), fbIntent,
                            StartActivityCompat.getResolvedType(args),
                            StartActivityCompat.getResultTo(args),
                            StartActivityCompat.getResultWho(args),
                            StartActivityCompat.getRequestCode(args),
                            StartActivityCompat.getFlags(args),
                            StartActivityCompat.getOptions(args));
                    return 0;
                }
            }
            // ---- End Facebook Login Fix ----

            ResolveInfo resolveInfo = MundoCore.getBPackageManager().resolveActivity(intent, FileUtils.FileMode.MODE_IWUSR, StartActivityCompat.getResolvedType(args), BActivityThread.getUserId());
            if (resolveInfo == null) {
                String origPackage = intent.getPackage();
                if (intent.getPackage() == null && intent.getComponent() == null) {
                    intent.setPackage(BActivityThread.getAppPackageName());
                } else {
                    origPackage = intent.getPackage();
                }
                resolveInfo = MundoCore.getBPackageManager().resolveActivity(intent, FileUtils.FileMode.MODE_IWUSR, StartActivityCompat.getResolvedType(args), BActivityThread.getUserId());
                if (resolveInfo == null) {
                    intent.setPackage(origPackage);
                    // Route GMS intents through GmsExternalActivity so the result
                    // can be relayed back into the virtual guest's onActivityResult.
                    IBinder resultTo = StartActivityCompat.getResultTo(args);
                    int requestCode = StartActivityCompat.getRequestCode(args);
                    if (resultTo != null && requestCode >= 0 && isGmsIntent(intent)) {
                        return launchViaGmsExternal(intent, resultTo,
                                StartActivityCompat.getResultWho(args), requestCode);
                    }
                    return method.invoke(who, args);
                }
            }


            intent.setExtrasClassLoader(who.getClass().getClassLoader());
            intent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name));
            MundoCore.getBActivityManager().startActivityAms(BActivityThread.getUserId(),StartActivityCompat.getIntent(args),StartActivityCompat.getResolvedType(args),StartActivityCompat.getResultTo(args),
		    StartActivityCompat.getResultWho(args),StartActivityCompat.getRequestCode(args),StartActivityCompat.getFlags(args),StartActivityCompat.getOptions(args));
            return 0;
        }

        private Intent getIntent(Object[] args) {
            int index;
            if (BuildCompat.isR()) {
                index = 3;
            } else {
                index = 2;
            }
            if (args[index] instanceof Intent) {
                return (Intent) args[index];
            }
            for (Object arg : args) {
                if (arg instanceof Intent) {
                    return (Intent) arg;
                }
            }
            return null;
        }

        private static boolean isGmsIntent(Intent intent) {
            if (intent == null) return false;
            ComponentName comp = intent.getComponent();
            String pkg = comp != null ? comp.getPackageName() : intent.getPackage();
            if (pkg == null) return false;
            return GmsCore.isGoogleAppOrService(pkg) || pkg.startsWith("com.google.");
        }

        private static int launchViaGmsExternal(Intent gmsIntent, final IBinder resultTo,
                final String resultWho, final int requestCode) {
            ResultReceiver receiver = new ResultReceiver(
                    new Handler(Looper.getMainLooper())) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    Intent data = resultData != null
                            ? (Intent) resultData.getParcelable("data") : null;
                    MundoCore.getBActivityManager().sendActivityResult(
                            resultTo, resultWho, requestCode, data, resultCode);
                }
            };
            Intent wrapper = new Intent(MundoCore.getContext(), GmsExternalActivity.class);
            wrapper.putExtra(GmsExternalActivity.KEY_TARGET, gmsIntent);
            wrapper.putExtra(GmsExternalActivity.KEY_RECEIVER, receiver);
            // Bypass marker so the re-intercepted call falls straight through.
            wrapper.putExtra("_G_|_target_", Boolean.TRUE);
            wrapper.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            MundoCore.getContext().startActivity(wrapper);
            return 0;
        }
    }

    @ProxyMethod("startActivities")
    public static class StartActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int index = getIntents();
            Intent[] intents = (Intent[]) args[index++];
            String[] resolvedTypes = (String[]) args[index++];
            IBinder resultTo = (IBinder) args[index++];
            Bundle options = (Bundle) args[index];
            // todo ??
            if (!ComponentUtils.isSelf(intents)) {
                return method.invoke(who, args);
            }

            for (Intent intent : intents) {
                intent.setExtrasClassLoader(who.getClass().getClassLoader());
            }
            return MundoCore.getBActivityManager().startActivities(BActivityThread.getUserId(),intents, resolvedTypes, resultTo, options);
        }

        public int getIntents() {
            if (BuildCompat.isR()) {
                return 3;
            }
            return 2;
        }
    }

    @ProxyMethod("startIntentSenderForResult")
    public static class StartIntentSenderForResult extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityResumed")
    public static class ActivityResumed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MundoCore.getBActivityManager().onActivityResumed((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityDestroyed")
    public static class ActivityDestroyed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MundoCore.getBActivityManager().onActivityDestroyed((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("finishActivity")
    public static class FinishActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MundoCore.getBActivityManager().onFinishActivity((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getAppTasks")
    public static class GetAppTasks extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getCallingPackage")
    public static class getCallingPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return MundoCore.getBActivityManager().getCallingPackage((IBinder) args[0], BActivityThread.getUserId());
        }
    }

    @ProxyMethod("getCallingActivity")
    public static class getCallingActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return MundoCore.getBActivityManager().getCallingActivity((IBinder) args[0], BActivityThread.getUserId());
        }
    }
}
