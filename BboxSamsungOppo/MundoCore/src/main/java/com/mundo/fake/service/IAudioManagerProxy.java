package com.mundo.fake.service;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import black.android.media.BRIAudioServiceStub;
import black.android.os.BRServiceManager;
import com.mundo.MundoCore;
import com.mundo.app.BActivityThread;
import com.mundo.fake.hook.BinderInvocationStub;
import com.mundo.fake.hook.MethodHook;
import com.mundo.fake.hook.ProxyMethod;
import com.mundo.utils.MethodParameterUtils;

/**
 * @author gm
 * @function
 * @date :2024/4/20 20:48
 **/
public class IAudioManagerProxy extends BinderInvocationStub {
    public IAudioManagerProxy() {
        super(BRServiceManager.get().getService(Context.AUDIO_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIAudioServiceStub.get().asInterface(BRServiceManager.get().getService(Context.AUDIO_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // ── shared helpers ────────────────────────────────────────────────────────

    static AudioManager getAm() {
        return (AudioManager) MundoCore.getContext().getSystemService(Context.AUDIO_SERVICE);
    }

    static IBinder findBinder(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof IBinder) return (IBinder) a;
        }
        return null;
    }

    // ── cross-version BT SCO helpers ──────────────────────────────────────────
    // Android 12 (API 31) deprecated startBluetoothSco/setBluetoothScoOn in favour of
    // setCommunicationDevice().  All BT-SCO state changes go through these helpers so
    // every code path uses the right API regardless of OS version.

    static void startBtSco(AudioManager am) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo sco = findBtScoDevice(am);
            if (sco != null) {
                am.setCommunicationDevice(sco);
                return;
            }
        }
        am.startBluetoothSco();
        am.setBluetoothScoOn(true);
    }

    static void stopBtSco(AudioManager am) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice();
        } else {
            am.stopBluetoothSco();
            am.setBluetoothScoOn(false);
        }
    }

    static boolean isBtScoOn(AudioManager am) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo d = am.getCommunicationDevice();
            return d != null && d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        }
        return am.isBluetoothScoOn();
    }

    static boolean isBtScoAvailable(AudioManager am) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return findBtScoDevice(am) != null;
        }
        return am.isBluetoothScoAvailableOffCall();
    }

    private static AudioDeviceInfo findBtScoDevice(AudioManager am) {
        // Check both input and output device lists; headsets appear on both sides.
        int[] masks = {AudioManager.GET_DEVICES_INPUTS, AudioManager.GET_DEVICES_OUTPUTS};
        for (int mask : masks) {
            for (AudioDeviceInfo d : am.getDevices(mask)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return d;
            }
        }
        return null;
    }

    // ── setMode ───────────────────────────────────────────────────────────────
    // BUG (original): when a hosted app enters MODE_IN_COMMUNICATION (mic open),
    // Android suspends A2DP and audio falls to the device speaker unless SCO is
    // started explicitly.  Inside BBox the per-app SCO lifecycle is broken by the
    // virtualised binder context, so we start SCO ourselves here.
    //
    // BUG (crash-leak): if the app crashes without resetting to MODE_NORMAL, the
    // communication mode (and our SCO session) stay active for the whole BBox host
    // process.  We attach a DeathRecipient to the caller's IBinder so the cleanup
    // runs automatically when the virtualized process dies.

    @ProxyMethod("setMode")
    public static class setMode extends MethodHook {
        private static volatile boolean sBtScoStartedByUs = false;
        private static volatile IBinder sCallerBinder;
        private static volatile IBinder.DeathRecipient sDeathRecipient;

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            Object result = method.invoke(who, args);

            if (args == null || args.length == 0 || !(args[0] instanceof Integer)) return result;
            int mode = (int) args[0];

            try {
                AudioManager am = getAm();
                if (am == null) return result;

                boolean commMode = mode == AudioManager.MODE_IN_COMMUNICATION
                        || mode == AudioManager.MODE_IN_CALL;

                if (commMode && !isBtScoOn(am) && isBtScoAvailable(am)) {
                    startBtSco(am);
                    sBtScoStartedByUs = true;
                    attachDeathCleanup(am, findBinder(args));
                } else if (mode == AudioManager.MODE_NORMAL && sBtScoStartedByUs) {
                    detachDeathCleanup();
                    stopBtSco(am);
                    sBtScoStartedByUs = false;
                }
            } catch (Exception ignored) {}
            return result;
        }

        static void attachDeathCleanup(final AudioManager am, IBinder binder) {
            if (binder == null) return;
            detachDeathCleanup();
            sCallerBinder = binder;
            sDeathRecipient = new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    try {
                        if (sBtScoStartedByUs) {
                            stopBtSco(am);
                            sBtScoStartedByUs = false;
                        }
                    } catch (Exception ignored) {}
                    sCallerBinder = null;
                    sDeathRecipient = null;
                }
            };
            try { binder.linkToDeath(sDeathRecipient, 0); } catch (Exception ignored) {}
        }

        static void detachDeathCleanup() {
            IBinder b = sCallerBinder;
            IBinder.DeathRecipient dr = sDeathRecipient;
            sCallerBinder = null;
            sDeathRecipient = null;
            if (b != null && dr != null) {
                try { b.unlinkToDeath(dr, 0); } catch (Exception ignored) {}
            }
        }
    }

    // ── startBluetoothSco ────────────────────────────────────────────────────
    // On Android 12+, startBluetoothSco() is deprecated and has no effect unless the
    // SCO device is also registered via setCommunicationDevice().  Route to the new
    // API on those versions so BT mic/audio always works.

    @ProxyMethod("startBluetoothSco")
    public static class startBluetoothSco extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    AudioManager am = getAm();
                    if (am != null && !isBtScoOn(am)) startBtSco(am);
                } catch (Exception ignored) {}
                return null;
            }
            return method.invoke(who, args);
        }
    }

    // ── stopBluetoothSco ─────────────────────────────────────────────────────

    @ProxyMethod("stopBluetoothSco")
    public static class stopBluetoothSco extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    AudioManager am = getAm();
                    if (am != null) stopBtSco(am);
                } catch (Exception ignored) {}
                return null;
            }
            return method.invoke(who, args);
        }
    }

    // ── setBluetoothScoOn ────────────────────────────────────────────────────
    // BUG: on Android 12+, setBluetoothScoOn() is a no-op; must use setCommunicationDevice.
    // BUG (pre-12): calling setBluetoothScoOn(true) without first calling startBluetoothSco()
    // silently fails to establish the SCO link on some devices.  We ensure startBluetoothSco
    // is called first.

    @ProxyMethod("setBluetoothScoOn")
    public static class setBluetoothScoOn extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            boolean on = args != null && args.length > 0 && Boolean.TRUE.equals(args[0]);
            try {
                AudioManager am = getAm();
                if (am != null) {
                    if (on) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (!isBtScoOn(am)) startBtSco(am);
                            return null;
                        }
                        // Pre-API-31: establish SCO link before routing audio through it
                        if (!am.isBluetoothScoOn() && am.isBluetoothScoAvailableOffCall()) {
                            am.startBluetoothSco();
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        stopBtSco(am);
                        return null;
                    }
                }
            } catch (Exception ignored) {}
            return method.invoke(who, args);
        }
    }

    // ── requestAudioFocus ────────────────────────────────────────────────────
    // Track IAudioFocusDispatcher bindings so abandonAudioFocus can clean up our
    // linkToDeath registrations.  AudioService already handles binder-death → focus
    // release natively; we keep this map purely for our own reference counting.

    @ProxyMethod("requestAudioFocus")
    public static class requestAudioFocus extends MethodHook {
        static final ConcurrentHashMap<IBinder, IBinder.DeathRecipient> sFocusHolders =
                new ConcurrentHashMap<>();

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            Object result = method.invoke(who, args);
            IBinder fd = findBinder(args);
            if (fd != null && !sFocusHolders.containsKey(fd)) {
                final IBinder fdFinal = fd;
                IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sFocusHolders.remove(fdFinal);
                    }
                };
                if (sFocusHolders.putIfAbsent(fd, dr) == null) {
                    try { fd.linkToDeath(dr, 0); } catch (Exception ignored) {}
                }
            }
            return result;
        }
    }

    // ── abandonAudioFocus ────────────────────────────────────────────────────

    @ProxyMethod("abandonAudioFocus")
    public static class abandonAudioFocus extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            IBinder fd = findBinder(args);
            if (fd != null) {
                IBinder.DeathRecipient dr = requestAudioFocus.sFocusHolders.remove(fd);
                if (dr != null) {
                    try { fd.unlinkToDeath(dr, 0); } catch (Exception ignored) {}
                }
            }
            return method.invoke(who, args);
        }
    }

    // ── volume methods: strip FLAG_SHOW_UI ────────────────────────────────────
    // When a hosted app adjusts volume, the system volume panel pops up showing
    // BBox's identity rather than the game's.  Strip FLAG_SHOW_UI (bit 0) from the
    // flags argument so volume changes are silent from the user's perspective.
    // All other flags (FLAG_PLAY_SOUND, FLAG_VIBRATE, etc.) are preserved.

    private static final int FLAG_SHOW_UI = AudioManager.FLAG_SHOW_UI; // 0x1

    private static void stripFlag(Object[] args, int index) {
        if (args != null && index < args.length && args[index] instanceof Integer) {
            args[index] = (int) args[index] & ~FLAG_SHOW_UI;
        }
    }

    @ProxyMethod("adjustStreamVolume")
    public static class adjustStreamVolume extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            stripFlag(args, 2); // (streamType, direction, flags, pkg)
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setStreamVolume")
    public static class setStreamVolume extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            stripFlag(args, 2); // (streamType, index, flags, pkg)
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("adjustSuggestedStreamVolume")
    public static class adjustSuggestedStreamVolume extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            stripFlag(args, 2); // (direction, suggestedStream, flags, pkg)
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("adjustVolume")
    public static class adjustVolume extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            stripFlag(args, 1); // (direction, flags, pkg)
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("adjustMasterVolume")
    public static class adjustMasterVolume extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            stripFlag(args, 1); // (direction, flags, pkg)
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("adjustLocalOrRemoteStreamVolume")
    public static class adjustLocalOrRemoteStreamVolume extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setMasterVolume")
    public static class setMasterVolume extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            stripFlag(args, 1); // (volume_float, flags, pkg)
            return method.invoke(who, args);
        }
    }

    // ── setMicrophoneMute ────────────────────────────────────────────────────
    // BUG: the previous implementation used replaceLastUserId() which replaces the
    // LAST Integer arg.  On Android 12+ the signature gained a 4th parameter:
    //   setMicrophoneMute(boolean on, String pkg, int userId, int portId)
    // so replaceLastUserId() was corrupting portId instead of userId.
    // userId is always at index 2 regardless of Android version.

    @ProxyMethod("setMicrophoneMute")
    public static class setMicrophoneMute extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            // Replace userId at known index 2, never the last int
            if (args != null && args.length > 2 && args[2] instanceof Integer) {
                args[2] = MundoCore.getHostUserId();
            }
            return method.invoke(who, args);
        }
    }

    // ── remaining pass-through stubs ─────────────────────────────────────────

    @ProxyMethod("unregisterAudioFocusClient")
    public static class unregisterAudioFocusClient extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("registerRemoteControlClient")
    public static class registerRemoteControlClient extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("disableSafeMediaVolume")
    public static class disableSafeMediaVolume extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setSpeakerphoneOn")
    public static class setSpeakerphoneOn extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setWiredDeviceConnectionState")
    public static class setWiredDeviceConnectionState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("avrcpSupportsAbsoluteVolume")
    public static class avrcpSupportsAbsoluteVolume extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setRingerModeInternal")
    public static class setRingerModeInternal extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setRingerModeExternal")
    public static class setRingerModeExternal extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("isHardwareDetected")
    public static class isHardwareDetected extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            return method.invoke(who, args);
        }
    }
}
