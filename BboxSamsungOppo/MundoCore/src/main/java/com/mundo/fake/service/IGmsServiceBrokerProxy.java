package com.mundo.fake.service;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;

import black.android.app.BRIServiceConnectionBaklava;
import black.android.app.BRIServiceConnectionO;
import com.mundo.MundoCore;
import com.mundo.app.BActivityThread;
import com.mundo.utils.Slog;
import com.mundo.utils.compat.BuildCompat;

/**
 * Intercepts the GMS service broker binder before callerPackageName leaves
 * the virtual process. Replaces the virtual package name (e.g. "com.pubg.imobile")
 * with the host package name ("com.bgmi") in the GetServiceRequest SafeParcel so
 * the GMS broker's cross-process getPackagesForUid check passes.
 *
 * Wired in via IActivityManagerProxy.BindService for GMS-targeted binds.
 */
public class IGmsServiceBrokerProxy implements IBinder {

    private static final String TAG = "GmsBrokerProxy";
    private final IBinder mReal;

    private IGmsServiceBrokerProxy(IBinder real) {
        mReal = real;
    }

    /**
     * Wrap a ServiceConnection so that when the GMS broker binder arrives in
     * onServiceConnected, it is wrapped with IGmsServiceBrokerProxy before being
     * forwarded to the original connection.
     */
    public static IServiceConnection wrapConnection(final IServiceConnection base) {
        return new IServiceConnection.Stub() {

            @Override
            public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                // Android 16 (Baklava): IServiceConnection.connected gains an extra
                // IBinderSession parameter — handle it manually.
                if (BuildCompat.isBaklava() && code == IBinder.FIRST_CALL_TRANSACTION) {
                    data.enforceInterface("android.app.IServiceConnection");
                    ComponentName name = data.readInt() != 0
                            ? ComponentName.CREATOR.createFromParcel(data) : null;
                    IBinder service = data.readStrongBinder();
                    data.readStrongBinder(); // IBinderSession — skip
                    boolean dead = data.readInt() != 0;
                    relay(name, service, dead);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }

            @Override
            public void connected(ComponentName name, IBinder service) throws RemoteException {
                relay(name, service, false);
            }

            private void relay(ComponentName name, IBinder service, boolean dead) {
                IBinder proxied = service != null ? new IGmsServiceBrokerProxy(service) : null;
                try {
                    if (BuildCompat.isBaklava()) {
                        BRIServiceConnectionBaklava.get(base).connected(name, proxied, null, dead);
                    } else if (BuildCompat.isOreo()) {
                        BRIServiceConnectionO.get(base).connected(name, proxied, dead);
                    } else {
                        base.connected(name, proxied);
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "relay: forward to original connection failed", e);
                }
            }
        };
    }

    // ---- IBinder: intercept transact, delegate everything else ----

    @Override
    public boolean transact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        // Transaction 1 = IGmsServiceBroker.getService — patch callerPackageName
        if (code == IBinder.FIRST_CALL_TRANSACTION) {
            try {
                byte[] raw = data.marshall();
                byte[] patched = patch(raw);
                if (patched != raw) {
                    Parcel nd = Parcel.obtain();
                    try {
                        nd.unmarshall(patched, 0, patched.length);
                        nd.setDataPosition(0);
                        return mReal.transact(code, nd, reply, flags);
                    } finally {
                        nd.recycle();
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "transact: patch failed, passing through", e);
            }
        }
        return mReal.transact(code, data, reply, flags);
    }

    /**
     * In the raw Parcel bytes, find the Parcel-encoded virtual package name string
     * and replace it with the host package name. Then update the SafeParcel outer
     * size field so the GMS broker parser doesn't overrun the object boundary.
     *
     * SafeParcel layout after the AIDL interface token:
     *   [int 1]           non-null Parcelable marker
     *   [int outerSize]   GetServiceRequest SafeParcel object byte count ← adjust this
     *   [fields ...]      including callerPackageName as a Parcel string
     */
    private static byte[] patch(byte[] raw) {
        String vPkg = BActivityThread.getAppPackageName(); // "com.pubg.imobile"
        String hPkg = MundoCore.getHostPkg();              // "com.bgmi"
        if (vPkg == null || hPkg == null || vPkg.equals(hPkg)) return raw;

        byte[] needle = parcelStr(vPkg);
        int pos = find(raw, needle);
        if (pos < 0) return raw; // virtual pkg not present in this parcel

        byte[] repl = parcelStr(hPkg);
        int delta = repl.length - needle.length; // negative: host name is shorter

        // Build patched byte array
        byte[] out = new byte[raw.length + delta];
        System.arraycopy(raw, 0, out, 0, pos);
        System.arraycopy(repl, 0, out, pos, repl.length);
        System.arraycopy(raw, pos + needle.length, out, pos + repl.length,
                raw.length - pos - needle.length);

        // Update the SafeParcel outer size field.
        // The AIDL interface token is a Parcel string:
        //   [int descCharCount][UTF-16LE chars + null term, 4-byte padded]
        // Followed by [int 1] (non-null marker), then [int outerSize].
        try {
            int dl = le32(out, 0);
            if (dl > 0 && dl < 512) {
                int dp = ((dl * 2 + 2) + 3) & ~3; // padded descriptor byte length
                int so = 4 + dp + 4;               // skip descriptor + non-null marker
                if (so + 4 <= out.length) {
                    putLE32(out, so, le32(out, so) + delta);
                }
            }
        } catch (Exception ignored) {
            // Non-fatal: broker may tolerate a slightly off size field
        }

        Slog.d(TAG, "patched callerPackageName: " + vPkg + " → " + hPkg);
        return out;
    }

    /** Encode a String exactly as Android Parcel.writeString() does. */
    private static byte[] parcelStr(String s) {
        byte[] chars = s.getBytes(StandardCharsets.UTF_16LE);
        int payload = chars.length + 2;        // UTF-16LE chars + 2-byte null terminator
        int padded  = (payload + 3) & ~3;      // round up to 4-byte alignment
        byte[] r = new byte[4 + padded];
        putLE32(r, 0, s.length());
        System.arraycopy(chars, 0, r, 4, chars.length);
        // null terminator and padding initialised to 0 by new byte[]
        return r;
    }

    private static int find(byte[] h, byte[] n) {
        outer:
        for (int i = 0, end = h.length - n.length; i <= end; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static int  le32(byte[] b, int o)      { return (b[o]&0xFF)|((b[o+1]&0xFF)<<8)|((b[o+2]&0xFF)<<16)|((b[o+3]&0xFF)<<24); }
    private static void putLE32(byte[] b, int o, int v) { b[o]=(byte)v; b[o+1]=(byte)(v>>8); b[o+2]=(byte)(v>>16); b[o+3]=(byte)(v>>24); }

    // ---- IBinder delegation ----

    @Override public String getInterfaceDescriptor() throws RemoteException { return mReal.getInterfaceDescriptor(); }
    @Override public boolean pingBinder()     { return mReal.pingBinder(); }
    @Override public boolean isBinderAlive()  { return mReal.isBinderAlive(); }
    @Override public IInterface queryLocalInterface(String descriptor) { return mReal.queryLocalInterface(descriptor); }
    @Override public void dump(FileDescriptor fd, String[] args) throws RemoteException { mReal.dump(fd, args); }
    @Override public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException { mReal.dumpAsync(fd, args); }
    @Override public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException { mReal.linkToDeath(recipient, flags); }
    @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return mReal.unlinkToDeath(recipient, flags); }
}
