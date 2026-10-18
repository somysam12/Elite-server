/**
 * ArkCheat — Native Security Gate (upgraded with fbcloneMundo features)
 * Added: Anti-sniffer, DEX integrity, improved lib whitelist (nativeLibraryDir scan),
 *        anti-clone with /data/data check, anti-memory-dump with exact path match
 */
#include <jni.h>
#include <string>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <csignal>
#include <unistd.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <android/log.h>
#include <dlfcn.h>
#include "oxorany.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SEC", __VA_ARGS__)

static std::string get_prop(const char* key) {
    char val[PROP_VALUE_MAX] = {};
    __system_property_get(key, val);
    return std::string(val);
}

// ── 1. Anti-Debug (TracerPid) ────────────────────────────────────────
static bool check_debug() {
    char buf[256] = {};
    FILE* fp = fopen(oxorany("/proc/self/status"), "r");
    if (fp) {
        while (fgets(buf, sizeof(buf), fp)) {
            if (strncmp(buf, oxorany("TracerPid:"), 10) == 0) {
                fclose(fp);
                return atoi(buf + 10) != 0;
            }
        }
        fclose(fp);
    }
    return false;
}

// ── 2. Anti-Hook (scan /proc/self/maps) ──────────────────────────────
static bool check_hooks() {
    FILE* fp = fopen(oxorany("/proc/self/maps"), "r");
    if (!fp) return false;
    static const char* badlibs[] = {
        "frida", "linjector", "xposed", "edxposed",
        "substrate", "il2cpp_dumper", "gadget",
        "hluda", "re.frida", "libinject.so",
        nullptr
    };
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        for (int i = 0; badlibs[i]; i++) {
            if (strstr(line, badlibs[i])) { fclose(fp); return true; }
        }
    }
    fclose(fp);
    return false;
}

// ── 3. Anti-Emulator ─────────────────────────────────────────────────
static bool check_emulator() {
    if (get_prop(oxorany("ro.kernel.qemu")) == "1") return true;
    struct stat st{};
    if (stat(oxorany("/dev/socket/genyd"), &st) == 0) return true;
    if (stat(oxorany("/dev/socket/baseband_genyd"), &st) == 0) return true;
    if (stat(oxorany("/data/app/com.bluestacks.home"), &st) == 0) return true;
    if (stat(oxorany("/data/data/com.bignox.app.store"), &st) == 0) return true;
    std::string fp   = get_prop(oxorany("ro.build.fingerprint"));
    std::string prod = get_prop(oxorany("ro.product.model"));
    std::string mfr  = get_prop(oxorany("ro.product.manufacturer"));
    if (fp.find(oxorany("generic"))     != std::string::npos) return true;
    if (fp.find(oxorany("unknown"))     != std::string::npos) return true;
    if (prod.find(oxorany("sdk"))       != std::string::npos) return true;
    if (mfr.find(oxorany("Genymotion")) != std::string::npos) return true;
    if (sysconf(_SC_NPROCESSORS_ONLN) < 2) return true;
    return false;
}

// ── 4. Anti-Crack-Tools ──────────────────────────────────────────────
static bool check_crack_tools() {
    struct stat st{};
    static const char* tools[] = {
        "/data/local/tmp/frida-server",
        "/data/local/tmp/re.frida.server",
        "/data/local/tmp/linjector",
        "/data/local/tmp/hluda-server",
        "/sdcard/Android/.nikgapps",
        nullptr
    };
    for (int i = 0; tools[i]; i++)
        if (stat(tools[i], &st) == 0) return true;
    return false;
}

// ── 5. Anti-Clone ────────────────────────────────────────────────────
static bool check_cloned(JNIEnv* env, jobject ctx) {
    if (!ctx) return false;
    jclass ctxClass = env->GetObjectClass(ctx);
    jmethodID mid = env->GetMethodID(ctxClass, "getFilesDir", "()Ljava/io/File;");
    if (!mid) return false;
    jobject fileObj = env->CallObjectMethod(ctx, mid);
    if (!fileObj) return false;
    jclass fileClass = env->GetObjectClass(fileObj);
    jmethodID pathMid = env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    jstring pathStr = (jstring)env->CallObjectMethod(fileObj, pathMid);
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    bool isClone = (strstr(path, oxorany("/user/0/"))    == nullptr
                 && strstr(path, oxorany("/user_de/0/")) == nullptr
                 && strstr(path, oxorany("/data/data/")) == nullptr);
    env->ReleaseStringUTFChars(pathStr, path);
    return isClone;
}

// ── 6. Anti-Dump ─────────────────────────────────────────────────────
static bool check_memory_dump() {
    char buf[64];
    snprintf(buf, sizeof(buf), oxorany("/proc/%d/fd"), getpid());
    DIR* d = opendir(buf);
    if (!d) return false;
    dirent* e;
    bool found = false;
    char selfMem[64], selfPmap[64];
    snprintf(selfMem,  sizeof(selfMem),  oxorany("/proc/%d/mem"),     getpid());
    snprintf(selfPmap, sizeof(selfPmap), oxorany("/proc/%d/pagemap"), getpid());
    while ((e = readdir(d)) != nullptr) {
        char link[256], target[256];
        snprintf(link, sizeof(link), oxorany("/proc/%d/fd/%s"), getpid(), e->d_name);
        ssize_t n = readlink(link, target, sizeof(target)-1);
        if (n > 0) {
            target[n] = '\0';
            if (strcmp(target, selfMem) == 0 || strcmp(target, selfPmap) == 0 ||
                strcmp(target, oxorany("/proc/self/mem")) == 0 ||
                strcmp(target, oxorany("/proc/self/pagemap")) == 0) {
                found = true; break;
            }
        }
    }
    closedir(d);
    return found;
}

// ── 7. Lib Whitelist (nativeLibraryDir scan — fbclone method) ────────
static bool check_extra_libs(JNIEnv* env, jobject ctx) {
    if (!ctx) return false;
    jclass ctxCls = env->GetObjectClass(ctx);
    jmethodID getAppInfo = env->GetMethodID(ctxCls, "getApplicationInfo",
        "()Landroid/content/pm/ApplicationInfo;");
    jobject appInfo = env->CallObjectMethod(ctx, getAppInfo);
    if (!appInfo) return false;
    jclass aiCls = env->GetObjectClass(appInfo);
    jfieldID nldField = env->GetFieldID(aiCls, "nativeLibraryDir", "Ljava/lang/String;");
    jstring nldStr = (jstring)env->GetObjectField(appInfo, nldField);
    if (!nldStr) return false;
    const char* nld = env->GetStringUTFChars(nldStr, nullptr);

    static const char* allowed[] = {
        "libshaitaan.so",
        "libc++_shared.so",
        "libEliteCore.so",
        "libxdfw8yegr.so",   // Mundo Lvl10 virtual engine
        nullptr
    };

    DIR* dir = opendir(nld);
    env->ReleaseStringUTFChars(nldStr, nld);
    if (!dir) return false;

    dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_type != DT_REG) continue;
        const char* name = entry->d_name;
        size_t len = strlen(name);
        if (len < 4 || strcmp(name + len - 3, ".so") != 0) continue;
        bool ok = false;
        for (int i = 0; allowed[i]; i++) {
            if (strcmp(name, allowed[i]) == 0) { ok = true; break; }
        }
        if (!ok) { closedir(dir); return true; }
    }
    closedir(dir);
    return false;
}

// ── 8. Anti-Sniffer (NEW — from fbclone) ─────────────────────────────
static bool check_sniffer() {
    const char* sniffer_pkgs[] = {
        oxorany("com.guoshi.httpcanary"),
        oxorany("com.guoshi.httpcanary.premium"),
        oxorany("app.greyshirts.sslcapture"),
        oxorany("com.minhui.networkcapture"),
        oxorany("com.evbadroid.wicapdroid"),
        oxorany("jp.co.taosoftware.android.packetcapture"),
        oxorany("com.emanuelef.remote_capture"),
        oxorany("com.packagesniffer.frtparlwtstd"),
        nullptr
    };
    for (int i = 0; sniffer_pkgs[i]; i++) {
        std::string path = std::string(oxorany("/data/data/")) + sniffer_pkgs[i];
        struct stat st;
        if (stat(path.c_str(), &st) == 0) {
            LOGE("Sniffer detected: %s", sniffer_pkgs[i]);
            return true;
        }
    }
    // System proxy check (Charles/Burp set this)
    std::string proxy = get_prop(oxorany("http.proxyHost"));
    if (!proxy.empty()) {
        LOGE("System proxy set: %s", proxy.c_str());
        return true;
    }
    return false;
}

// ── 9. DEX Integrity (NEW — from fbclone) ────────────────────────────
// Crashes if cracker swaps DEX with old version or strips panel classes
static bool check_dex_integrity(JNIEnv* env, jobject ctx) {
    if (!ctx) return false;
    jclass ctxCls = env->GetObjectClass(ctx);
    jmethodID getCL = env->GetMethodID(ctxCls, "getClassLoader", "()Ljava/lang/ClassLoader;");
    if (!getCL) return true;
    jobject loader = env->CallObjectMethod(ctx, getCL);
    if (!loader) return true;
    jclass clCls = env->GetObjectClass(loader);
    jmethodID loadCls = env->GetMethodID(clCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!loadCls) return true;

    // PanelCrypto must exist with decryptSignedResponse
    {
        jstring n = env->NewStringUTF(oxorany("com.bgmi.utils.PanelCrypto"));
        jobject c = env->CallObjectMethod(loader, loadCls, n);
        env->DeleteLocalRef(n);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return true; }
        if (!c) return true;
        jmethodID m = env->GetStaticMethodID((jclass)c,
            oxorany("decryptSignedResponse"),
            oxorany("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(c);
        if (!m) return true;
    }

    // HeartbeatManager must exist
    {
        jstring n = env->NewStringUTF(oxorany("com.bgmi.HeartbeatManager"));
        jobject c = env->CallObjectMethod(loader, loadCls, n);
        env->DeleteLocalRef(n);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return true; }
        if (!c) return true;
        env->DeleteLocalRef(c);
    }

    // IntegrityGuard must exist with verifyOrDie
    {
        jstring n = env->NewStringUTF(oxorany("com.bgmi.utils.IntegrityGuard"));
        jobject c = env->CallObjectMethod(loader, loadCls, n);
        env->DeleteLocalRef(n);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return true; }
        if (!c) return true;
        jmethodID m = env->GetStaticMethodID((jclass)c,
            oxorany("verifyOrDie"),
            oxorany("(Landroid/content/Context;)V"));
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(c);
        if (!m) return true;
    }

    return false;
}

// ── Combined gate ─────────────────────────────────────────────────────
bool ff_security_ok(JNIEnv* env, jobject ctx) {
    LOGE(">> security gate start");
    if (check_debug())                       { LOGE("FAIL: check_debug");        return false; }
    if (check_hooks())                       { LOGE("FAIL: check_hooks");        return false; }
    if (check_crack_tools())                 { LOGE("FAIL: check_crack_tools");  return false; }
    if (check_sniffer())                     { LOGE("FAIL: check_sniffer");      return false; }
    if (check_emulator())                    { LOGE("FAIL: check_emulator");     return false; }
    if (ctx && check_cloned(env, ctx))       { LOGE("FAIL: check_cloned");       return false; }
    if (check_memory_dump())                 { LOGE("FAIL: check_memory_dump");  return false; }
    if (ctx && check_extra_libs(env, ctx))   { LOGE("FAIL: check_extra_libs");   return false; }
    if (ctx && check_dex_integrity(env, ctx)){ LOGE("FAIL: check_dex_integrity");return false; }
    LOGE(">> all checks PASSED");
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bgmi_SplashActivity_nativeSecurityCheck(JNIEnv* env, jobject, jobject ctx) {
    LOGE("nativeSecurityCheck CALLED");
    jboolean r = (jboolean) ff_security_ok(env, ctx);
    LOGE("nativeSecurityCheck RESULT=%d", r);
    return r;
}

// Periodic recheck (context-free, safe for background threads)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_bgmi_HeartbeatManager_nativeQuickSecurityCheck(JNIEnv*, jclass) {
    if (check_debug())       return JNI_FALSE;
    if (check_hooks())       return JNI_FALSE;
    if (check_crack_tools()) return JNI_FALSE;
    if (check_sniffer())     return JNI_FALSE;
    if (check_emulator())    return JNI_FALSE;
    if (check_memory_dump()) return JNI_FALSE;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bgmi_utils_IntegrityGuard_nativeCrash(JNIEnv*, jclass) {
    raise(SIGSEGV);
    abort();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bgmi_HeartbeatManager_nativeIntegrityOk(JNIEnv* env, jclass, jobject ctx) {
    if (check_hooks())             return JNI_FALSE;
    if (check_debug())             return JNI_FALSE;
    if (check_sniffer())           return JNI_FALSE;
    if (ctx && check_extra_libs(env, ctx)) return JNI_FALSE;
    return JNI_TRUE;
}
