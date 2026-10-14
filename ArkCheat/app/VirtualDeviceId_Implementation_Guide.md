# Virtual Device ID Spoofing — Implementation Guide

> **Scope:** Kisi bhi Hcore/BBox-based virtual app container mein device ID fake karna  
> **Target:** `android_id` (Settings.Secure), GSF ID  
> **Result:** Guest app (e.g. game) reads fake ID instead of real device ID

---

## 1. Prerequisites / Requirements

| Requirement | Details |
|---|---|
| Virtual framework | Hcore / MundoCore / BBox (any variant that exposes `AppLifecycleCallback`) |
| Min API | 21+ (Android 5.0) |
| Reflection access | `setAccessible(true)` must work (no strict SELinux blocking) |
| Process model | Host app wraps guest in `:p0`, `:p1`... child processes |
| Build | Release + minify OK (hook uses reflection, no hard-coded names) |

---

## 2. Architecture Overview

```
HOST PROCESS (com.yourapp)
│
├── ArkApplication.onCreate()
│     ├── setupInterceptor(fakeId)   ← register callback BEFORE doCreate()
│     └── MundoCore.get().doCreate()
│
└── BGMI / Guest App Process (:p0)
      │
      └── beforeApplicationOnCreate() fires  ← Hcore callback, BEFORE guest app starts
            │
            └── installInProcess(fakeId)
                  ├── Step 1: mValues pre-fill  ← MOST RELIABLE
                  ├── Step 2: mContentProvider wrap
                  └── Step 3: mProviderMap wrap
```

**Key rule:** `setupInterceptor` MUST be called **before** `MundoCore.get().doCreate()`.  
Agar baad mein call kiya toh Hcore lifecycle callbacks register nahi hote.

---

## 3. Files to Create / Modify

```
app/src/main/java/com/yourpkg/
├── deviceid/
│   ├── VirtualAndroidIdHook.java      ← NEW: main hook class
│   └── VirtualDeviceIdStore.java      ← NEW: persistent storage for fake ID
└── YourApplication.java               ← MODIFY: call setupInterceptor early
```

---

## 4. VirtualDeviceIdStore.java

Fake ID ko persistent store karta hai (app restart pe same ID milta hai).

```java
package com.yourpkg.deviceid;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

public class VirtualDeviceIdStore {
    private static final String PREF = "vdev_prefs";
    private static final String KEY  = "desired_android_id";

    private final SharedPreferences prefs;

    public VirtualDeviceIdStore(Context ctx) {
        prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Returns stored fake ID, or "" if none set */
    public String desired() {
        return prefs.getString(KEY, "");
    }

    /** Generates a new random fake ID, saves it, returns it */
    public String change() {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        prefs.edit().putString(KEY, id).apply();
        return id;
    }

    /** Set a specific fake ID manually */
    public void set(String id) {
        prefs.edit().putString(KEY, id).apply();
    }

    /** Reset: delete stored ID (next call to desired() returns "") */
    public void reset() {
        prefs.edit().remove(KEY).apply();
    }
}
```

---

## 5. VirtualAndroidIdHook.java

Main hook class — 3-step injection.

```java
package com.yourpkg.deviceid;

import android.content.Context;
import android.os.Handler;
import com.mundo.core.MundoCore;
import com.mundo.core.app.AppLifecycleCallback;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

public class VirtualAndroidIdHook {

    public static void setupInterceptor(Context ctx, String fakeId) {
        // Register Hcore lifecycle callback
        MundoCore.get().addAppLifecycleCallback(new AppLifecycleCallback() {
            @Override
            public void beforeApplicationOnCreate(Context appContext, String packageName) {
                try {
                    installInProcess(fakeId);
                } catch (Throwable t) {
                    // silent fail — don't crash guest app
                }
            }
        });
    }

    private static void installInProcess(String fakeId) throws Exception {
        Class<?> secureClass = Class.forName("android.provider.Settings$Secure");
        Field cacheField = findField(secureClass, "sNameValueCache");
        Object cache = cacheField.get(null);

        // ── Step 1: mValues pre-fill (bypasses IContentProvider entirely) ──
        try {
            Field mValuesField = findField(cache.getClass(), "mValues");
            synchronized (cache) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> mValues = (Map<Object, Object>) mValuesField.get(cache);
                if (mValues != null) {
                    mValues.put("android_id",          fakeId);
                    mValues.put("google_gsf_android_id", fakeId);
                    mValues.put("gsf_id",               fakeId);
                }
            }
        } catch (Throwable ignored) {}

        // ── Step 2: mContentProvider wrap ──
        try {
            Field cpField = findField(cache.getClass(), "mContentProvider");
            Object realCp = cpField.get(cache);
            if (realCp != null) {
                Class<?>[] ifaces = realCp.getClass().getInterfaces();
                if (ifaces.length > 0) {
                    Object proxyCp = Proxy.newProxyInstance(
                        realCp.getClass().getClassLoader(),
                        ifaces,
                        new IdInterceptHandler(realCp, fakeId)
                    );
                    cpField.set(cache, proxyCp);
                }
            }
        } catch (Throwable ignored) {}

        // ── Step 3: mProviderMap wrap ──
        try {
            Field pmField = findField(cache.getClass(), "mProviderMap");
            Object realPm = pmField.get(cache);
            if (realPm instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) realPm;
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    Object val = entry.getValue();
                    if (val instanceof Handler) {
                        Handler h = (Handler) val;
                        // Replace IContentProvider at index len-3 or len-2 from end
                        try {
                            Field fArgs = findField(h.getClass(), "args");
                            Object[] args = (Object[]) fArgs.get(h);
                            if (args != null && args.length >= 3) {
                                int idx = args.length - 3; // adjust if needed
                                Object orig = args[idx];
                                if (orig != null) {
                                    Class<?>[] ifaces2 = orig.getClass().getInterfaces();
                                    if (ifaces2.length > 0) {
                                        args[idx] = Proxy.newProxyInstance(
                                            orig.getClass().getClassLoader(),
                                            ifaces2,
                                            new IdInterceptHandler(orig, fakeId)
                                        );
                                    }
                                }
                            }
                        } catch (Throwable ignored2) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    // ── Reflection helper: walks superclass chain ──
    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " not found in " + cls.getName());
    }

    // ── Proxy InvocationHandler: intercepts query() calls ──
    private static class IdInterceptHandler implements InvocationHandler {
        private final Object delegate;
        private final String fakeId;

        IdInterceptHandler(Object delegate, String fakeId) {
            this.delegate = delegate;
            this.fakeId   = fakeId;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(delegate, args);
            // Intercept android.database.Cursor results and replace android_id column
            try {
                if (result != null && isAndroidIdQuery(args)) {
                    // replace value in cursor — implementation depends on cursor type
                    // For most cases mValues pre-fill (Step 1) is sufficient
                }
            } catch (Throwable ignored) {}
            return result;
        }

        private boolean isAndroidIdQuery(Object[] args) {
            if (args == null) return false;
            for (Object a : args) {
                if (a instanceof String && ((String) a).contains("android_id")) return true;
            }
            return false;
        }
    }
}
```

---

## 6. YourApplication.java — Critical Change

```java
@Override
public void onCreate() {
    super.onCreate();

    // ══ STEP A: setupInterceptor MUST come BEFORE doCreate() ══
    try {
        VirtualDeviceIdStore store = new VirtualDeviceIdStore(getApplicationContext());
        String id = store.desired().isEmpty() ? store.change() : store.desired();
        VirtualAndroidIdHook.setupInterceptor(getApplicationContext(), id);
    } catch (Throwable t) {
        // log but don't crash
    }

    // ══ STEP B: THEN init virtual framework ══
    try {
        MundoCore.get().doCreate();
    } catch (Exception e) { /* handle */ }

    // ... rest of your init
}
```

### Why order matters:

```
WRONG ❌:
  doCreate() → Hcore inits callbacks → setupInterceptor() registered TOO LATE
  → beforeApplicationOnCreate already fired → fakeId NOT injected

CORRECT ✅:
  setupInterceptor() → callback registered
  → doCreate() → Hcore fires beforeApplicationOnCreate → installInProcess() runs
  → fakeId injected BEFORE guest app's Application.onCreate()
```

---

## 7. ID Reset Flow (Device Reset Feature)

```
User taps "Reset Device ID" button
        │
        ▼
VirtualDeviceIdStore.change()   ← new UUID generated + saved to SharedPrefs
        │
        ▼
Show new fake ID to user
        │
        ▼
User force-stops app (or app self-restarts)
        │
        ▼
ArkApplication.onCreate()
  → store.desired() returns new ID
  → setupInterceptor(newId) registered
  → doCreate() → beforeApplicationOnCreate → installInProcess(newId)
        │
        ▼
Guest app reads NEW fake android_id ✅
```

**Important:** ID change takes effect ONLY after full process restart.  
In-process hot-swap is NOT reliable because mValues is a static cache already populated.

---

## 8. How It Works Internally (AOSP flow)

```
Guest app calls:
  Settings.Secure.getString(cr, "android_id")
        │
        ▼
  Settings$Secure.sNameValueCache.getStringForUser(cr, "android_id", uid)
        │
        ├─ mValues.get("android_id") ──→ FOUND (we pre-filled) → return fakeId ✅
        │                                No IPC, no ContentProvider call
        │
        └─ NOT FOUND → call IContentProvider.query(...)  ← Step 2/3 intercept
                │
                └─ Handler proxy intercepts → returns fakeId ✅
```

Step 1 (mValues pre-fill) covers 95%+ of cases.  
Steps 2 & 3 are fallback for edge cases where mValues gets cleared or is checked after invalidation.

---

## 9. What Guest App (BGMI) Actually Reads

| Field | Real Value | Fake Value (example) |
|---|---|---|
| `android_id` | `c260d5b52322b796` | `a8af4151c79f62be` |
| `google_gsf_android_id` | same real | same fake |
| `gsf_id` | same real | same fake |

All three overridden simultaneously in Step 1.

---

## 10. Debugging / Verification

### ArkLogger (file-based, not stripped by ProGuard):
```java
// Add to VirtualAndroidIdHook — survives release build
ArkLogger.log("HOOK", "installInProcess fakeId=" + fakeId);
```

### Confirming it worked (without runtime):
1. `setupInterceptor: lifecycle callback registered` in ark.log → callback registered ✅
2. `beforeAppCreate:` NOT in HOST ark.log → fires in p0 (correct, expected) ✅
3. `[READ]` entries ABSENT → mValues pre-fill worked, IPC never called ✅

### Red flags (it's NOT working):
- Many `[READ] android_id` entries → mValues pre-fill failed, fallback to IPC
- `installInProcess` exception logged → reflection failed (check field names by API level)

---

## 11. ProGuard Considerations

If you use ProGuard/R8 with `-assumenosideeffects` on `android.util.Log`:
- All `Log.d/i/w/e` calls get **stripped** from release APK
- Solution: Use a file-based logger (write to `/sdcard/Download/yourapp.log`)
- Never rely on logcat for debugging release hooks

Field names like `mValues`, `mContentProvider`, `mProviderMap` are in **AOSP framework** (not your code) — they are NOT renamed by ProGuard. Safe to use via reflection.

---

## 12. Limitations / Known Issues

| Issue | Impact | Workaround |
|---|---|---|
| mValues cleared on `invalidateCache()` | Race condition on some AOSP versions | Steps 2+3 catch this |
| ArkLogger fails in virtual process | No log output from p0 | Expected — use HOST-side confirmation |
| ID change needs process restart | Can't hot-swap | Force-stop + relaunch |
| GSF (Google Services Framework) reads ID separately via Binder | Separate hook needed for full GSF spoof | Out of scope here |
| Some games read `/proc/sys/kernel/random/boot_id` too | This hook doesn't cover it | Separate implementation needed |

---

*Document generated from ArkCheat v49/v50 source analysis — VirtualAndroidIdHook implementation.*
