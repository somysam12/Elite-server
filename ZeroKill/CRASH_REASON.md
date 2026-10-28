# ZeroKill APK — Startup Crash Root Cause & Fix

## Problem
App crashed immediately on launch (before splash screen) after renaming the package to `com.visionpro`.

## Root Cause: JNI Symbol Mismatch

`libZeroKill.so` exports native functions bound to the **original source package** `com.bgmi`:

```
Java_com_bgmi_ArkApplication_getSdkKey
Java_com_bgmi_DexDecryptor_nativeDecrypt
Java_com_bgmi_MainActivity_nativeInit
...
```

Android's JNI linker resolves native methods by looking up a symbol named `Java_<package>_<class>_<method>` where `<package>` uses underscores instead of dots. If the Java class package does not match the `.so` symbol prefix, the JNI lookup throws `UnsatisfiedLinkError` at class-load time — which is an `Error`, not an `Exception`, so it propagates uncaught and kills the process.

## What We Changed and Why It Broke

| Change | Effect |
|--------|--------|
| `namespace = "com.visionpro"` | Recompiles all Java sources under package `com.visionpro` — JNI symbols become `Java_com_visionpro_*` |
| `applicationId = "com.visionpro"` | Only changes the installed package name on device — **no effect on JNI** |

Setting `namespace = "com.visionpro"` was the mistake. It made the compiled classes use `com.visionpro` as their Java package, but `libZeroKill.so` still exports `Java_com_bgmi_*` — mismatch → crash.

## Fix Applied

```groovy
// app/build.gradle
namespace     'com.bgmi'       // ← keeps Java class package = com.bgmi (matches .so)
applicationId 'com.visionpro'  // ← installs on device as com.visionpro
```

Key insight: **`namespace` and `applicationId` are independent in AGP 7+.**
- `namespace` controls the Java/Kotlin package of generated `R` and `BuildConfig` classes, and how source files are compiled — it must match the JNI symbol prefix in the `.so`.
- `applicationId` is only the Android manifest package name — what the launcher and Play Store see.

Java sources remain in `com/bgmi/` with `package com.bgmi;` declarations. ProGuard `-keep` rules also reference `com.bgmi.*`. The app installs as `com.visionpro` on device while all internal JNI bindings resolve correctly against `com.bgmi`.

## Proguard Keep Rules (must stay as com.bgmi)

```
-keep class com.bgmi.ArkApplication  { *; }
-keep class com.bgmi.DexDecryptor    { *; }
-keep class com.bgmi.SplashActivity  { *; }
-keep class com.bgmi.LoginActivity   { *; }
-keep class com.bgmi.MainActivity    { *; }
-keep class com.bgmi.BaseActivity    { *; }
-keep class com.bgmi.OverlayService  { *; }
-keep class com.bgmi.utils.**        { *; }
-keep class com.bgmi.HeartbeatManager { *; }
```

## Rule: Never Change namespace
Do **not** change `namespace` from `com.bgmi` in `app/build.gradle`. The `.so` JNI symbols are compiled into the native library and cannot be changed without recompiling NDK sources.
