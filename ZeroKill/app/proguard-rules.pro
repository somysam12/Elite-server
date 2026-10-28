# ═══════════════════════════════════════════════════════════════════════════════
#  ArkCheat — ProGuard / R8 Maximum Protection Rules
# ═══════════════════════════════════════════════════════════════════════════════

# ── R8 Full Mode: aggressive inlining, class merging ─────────────────────────
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively
-repackageclasses 'a'          # flatten everything into single package 'a'
-optimizationpasses 7

# ── Strip ALL debug metadata ──────────────────────────────────────────────────
-renamesourcefileattribute ''
-keepattributes Exceptions,Signature,InnerClasses

# ── Remove all logging (no logcat leaks) ─────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(...);
}
-assumenosideeffects class java.io.PrintStream { public void println(...); public void print(...); }

# ── Keep JNI native methods — renaming breaks native linkage ─────────────────
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ── App entry points (must survive R8) ───────────────────────────────────────
-keep class com.bgmi.ArkApplication { *; }
-keep class com.bgmi.DexDecryptor   { *; }
-keep class com.bgmi.SplashActivity { *; }
-keep class com.bgmi.LoginActivity  { *; }
-keep class com.bgmi.MainActivity   { *; }
-keep class com.bgmi.BaseActivity   { *; }
-keep class com.bgmi.OverlayService { *; }
-keep class com.bgmi.utils.**       { *; }

# ── Virtual space SDK ─────────────────────────────────────────────────────────
# REMOVED (Hcore): -keep class com.mundo.**         { *; }
-keep class net_62v.**           { *; }
-keep class black.**             { *; }
# REMOVED (Hcore): -dontwarn com.mundo.**
-dontwarn net_62v.**
-dontwarn black.**

# ── Reflection helpers ────────────────────────────────────────────────────────
-keep class me.weishu.**             { *; }
-keep class com.github.CodingGay.** { *; }
-dontwarn me.weishu.**

# ── LSParanoid string obfuscation ─────────────────────────────────────────────
-keep @io.michaelrocks.paranoid.Obfuscate class * { *; }
-keep class io.michaelrocks.paranoid.**            { *; }

# ── AndroidX / Material ───────────────────────────────────────────────────────
-keep class androidx.core.content.res.ResourcesCompat { *; }
-keep class com.google.android.material.bottomsheet.BottomSheetDialog { *; }

# ── Zip4j ─────────────────────────────────────────────────────────────────────
-keep class net.lingala.zip4j.** { *; }
-dontwarn net.lingala.zip4j.**

# ── Standard safety keeps ─────────────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.**
-ignorewarnings

# ── Custom obfuscation dictionary ─────────────────────────────────────────────
-obfuscationdictionary       obfuscation-dict.txt
-classobfuscationdictionary  obfuscation-dict.txt
-packageobfuscationdictionary obfuscation-dict.txt

# ════════════════════════════════════════════════════════════════════════
#  Mundo Lvl10 — Full keeps (REQUIRED — do not remove)
# ════════════════════════════════════════════════════════════════════════
-keep class net_62v.**                          { *; }
-keep class top.bienvenido.**                   { *; }
-keep class * implements net_62v.external.IMundoProcessCallback { *; }
-keepclassmembers class * implements net_62v.external.IMundoProcessCallback { *; }
-dontwarn top.bienvenido.**

# ArkCheat specific
-keep class com.bgmi.HeartbeatManager       { *; }
-keep class com.bgmi.OverlayService         { *; }
-keepclassmembers class com.bgmi.HeartbeatManager {
    static native <methods>;
    public static <methods>;
}

# Remove old Hcore keeps if any (they're gone but kept for safety)
# REMOVED (Hcore): # -keep class com.mundo.**   ← DO NOT ADD THIS
