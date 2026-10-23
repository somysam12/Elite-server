#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <curl/curl.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>
#include <openssl/sha.h>
#include <openssl/md5.h>
#include <json.hpp>
#include "oxorany.h"
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include "decrypt.h"

#define LOG_TAG "KC"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::ordered_json;

// ═══════════════════════════════════════════════════════════════════════
//  GLOBALS
// ═══════════════════════════════════════════════════════════════════════
static std::string g_exdate  = "NULL";
static std::string g_userKey;
static std::string g_serial;
static char        g_enc[64] = {};
static bool        bValid    = false;
static std::string g_Token;
static std::string g_Auth;
static time_t      rng       = 0;
static std::string g_licKey;

static std::string readLicKey() {
    FILE* f = fopen("/storage/emulated/0/Android/ZeroKill.lic", "r");
    if (!f) return "";
    char buf[512]; std::string key;
    while (fgets(buf, sizeof(buf), f)) {
        std::string line(buf);
        if (line.size() > 4 && line.substr(0,4) == "KEY=") {
            key = line.substr(4);
            while (!key.empty() && (key.back()=='\n'||key.back()=='\r'||key.back()==' '))
                key.pop_back();
            break;
        }
    }
    fclose(f); return key;
}

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    curl_global_init(CURL_GLOBAL_ALL);
    g_licKey = readLicKey();
    return JNI_VERSION_1_6;
}

#define EXPECTED_SIGNATURE oxorany("77f05d53ce8bf1855caef38ce87f13a8bb2b1b2cdd2d48da9d3ba897eac4549e")

// ═══════════════════════════════════════════════════════════════════════
//  HELPERS
// ═══════════════════════════════════════════════════════════════════════
struct MemoryStruct { char* memory; size_t size; };

static size_t WriteMemoryCallback(void* contents, size_t size, size_t nmemb, void* userp) {
    size_t realsize = size * nmemb;
    auto* mem = (MemoryStruct*)userp;
    mem->memory = (char*)realloc(mem->memory, mem->size + realsize + 1);
    if (!mem->memory) return 0;
    memcpy(&(mem->memory[mem->size]), contents, realsize);
    mem->size += realsize;
    mem->memory[mem->size] = 0;
    return realsize;
}

static std::string CalcMD5(const std::string& s) {
    std::string result;
    unsigned char hash[MD5_DIGEST_LENGTH];
    char tmp[4];
    MD5_CTX md5;
    MD5_Init(&md5);
    MD5_Update(&md5, s.c_str(), s.length());
    MD5_Final(hash, &md5);
    for (unsigned char i : hash) { sprintf(tmp, "%02x", i); result += tmp; }
    return result;
}

static void hexEncode(const unsigned char* in, int len, char* out) {
    for (int i = 0; i < len; i++) sprintf(out + i*2, "%02x", in[i]);
    out[len*2] = 0;
}

// ── Device info ──────────────────────────────────────────────────────
static std::string getAndroidId(JNIEnv* env, jobject ctx) {
    jclass cc = env->FindClass("android/content/Context");
    jmethodID getCR = env->GetMethodID(cc, "getContentResolver", "()Landroid/content/ContentResolver;");
    jobject cr = env->CallObjectMethod(ctx, getCR);
    jclass sc = env->FindClass("android/provider/Settings$Secure");
    jmethodID gs = env->GetStaticMethodID(sc, "getString", "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;");
    auto s = (jstring)env->CallStaticObjectMethod(sc, gs, cr, env->NewStringUTF("android_id"));
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c); env->ReleaseStringUTFChars(s, c); return r;
}
static std::string getModel(JNIEnv* env) {
    jclass bc = env->FindClass("android/os/Build");
    jfieldID f = env->GetStaticFieldID(bc, "MODEL", "Ljava/lang/String;");
    auto s = (jstring)env->GetStaticObjectField(bc, f);
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c); env->ReleaseStringUTFChars(s, c); return r;
}
static std::string getBrand(JNIEnv* env) {
    jclass bc = env->FindClass("android/os/Build");
    jfieldID f = env->GetStaticFieldID(bc, "BRAND", "Ljava/lang/String;");
    auto s = (jstring)env->GetStaticObjectField(bc, f);
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c); env->ReleaseStringUTFChars(s, c); return r;
}
static std::string getPkg(JNIEnv* env, jobject ctx) {
    jclass cc = env->FindClass("android/content/Context");
    jmethodID m = env->GetMethodID(cc, "getPackageName", "()Ljava/lang/String;");
    auto s = (jstring)env->CallObjectMethod(ctx, m);
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c); env->ReleaseStringUTFChars(s, c); return r;
}
static std::string makeUUID(JNIEnv* env, const std::string& input) {
    jclass uc = env->FindClass("java/util/UUID");
    jmethodID fb = env->GetStaticMethodID(uc, "nameUUIDFromBytes", "([B)Ljava/util/UUID;");
    jmethodID ts = env->GetMethodID(uc, "toString", "()Ljava/lang/String;");
    jbyteArray ba = env->NewByteArray(input.size());
    env->SetByteArrayRegion(ba, 0, input.size(), (jbyte*)input.c_str());
    jobject uuid = env->CallStaticObjectMethod(uc, fb, ba);
    auto s = (jstring)env->CallObjectMethod(uuid, ts);
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c); env->ReleaseStringUTFChars(s, c); return r;
}

// ═══════════════════════════════════════════════════════════════════════
//  JNI EXPORTS — MainActivity
// ═══════════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_MainActivity_exdate(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_exdate.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_MainActivity_ZENINOP(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_enc);
}

// ═══════════════════════════════════════════════════════════════════════
//  JNI EXPORTS — LoginActivity
// ═══════════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_LoginActivity_GetKey(JNIEnv* env, jobject) {
    return env->NewStringUTF(oxorany("https://t.me/"));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bgmi_LoginActivity_nativeVerifySignature(JNIEnv* env, jobject, jobject context) {
    jclass contextClass = env->GetObjectClass(context);
    jmethodID midGetPM  = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject pm          = env->CallObjectMethod(context, midGetPM);
    jmethodID midGetPkg = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    jstring pkgName     = (jstring)env->CallObjectMethod(context, midGetPkg);
    jclass pmClass      = env->GetObjectClass(pm);
    jmethodID midGetInfo = env->GetMethodID(pmClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    jobject pkgInfo     = env->CallObjectMethod(pm, midGetInfo, pkgName, 64);
    jclass piCls        = env->GetObjectClass(pkgInfo);
    jfieldID fidSig     = env->GetFieldID(piCls, "signatures", "[Landroid/content/pm/Signature;");
    jobjectArray sigs   = (jobjectArray)env->GetObjectField(pkgInfo, fidSig);
    if (!sigs) return JNI_FALSE;
    jsize cnt = env->GetArrayLength(sigs);
    for (jsize i = 0; i < cnt; i++) {
        jobject sig  = env->GetObjectArrayElement(sigs, i);
        jclass sc    = env->GetObjectClass(sig);
        jmethodID tb = env->GetMethodID(sc, "toByteArray", "()[B");
        jbyteArray bytes = (jbyteArray)env->CallObjectMethod(sig, tb);
        jsize len    = env->GetArrayLength(bytes);
        jbyte* buf   = env->GetByteArrayElements(bytes, nullptr);
        unsigned char hash[SHA256_DIGEST_LENGTH];
        SHA256((unsigned char*)buf, len, hash);
        env->ReleaseByteArrayElements(bytes, buf, 0);
        char hex[65]; hexEncode(hash, SHA256_DIGEST_LENGTH, hex);
        if (strcasecmp(hex, EXPECTED_SIGNATURE) == 0) return JNI_TRUE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_LoginActivity_nativeApkKillUrl(JNIEnv* env, jclass) {
    return env->NewStringUTF(oxorany("https://zerokill.authapi.xyz/server/apk_kill.php"));
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_LoginActivity_nativeApkDownloadUrl(JNIEnv* env, jclass) {
    return env->NewStringUTF(oxorany("https://zerokill.authapi.xyz/uploads/apk/"));
}

// ═══════════════════════════════════════════════════════════════════════
//  LOGIN — FrozenFire JSON protocol
// ═══════════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_LoginActivity_Check(JNIEnv* env, jclass, jobject mContext, jstring mUserKey) {
    const char* keyC = env->GetStringUTFChars(mUserKey, nullptr);
    std::string user_key(keyC);
    env->ReleaseStringUTFChars(mUserKey, keyC);

    std::string hwid = user_key;
    hwid += getAndroidId(env, mContext);
    hwid += getModel(env);
    hwid += getBrand(env);
    std::string UUID = makeUUID(env, hwid);
    std::string errMsg;

    bValid = false;

    for (int attempt = 0; attempt < 3; attempt++) {

        MemoryStruct chunk{};
        chunk.memory = (char*)malloc(1);
        chunk.size   = 0;

        CURL* curl = curl_easy_init();
        if (!curl) { errMsg = "curl init failed"; free(chunk.memory); continue; }

        char lol[256];
        sprintf(lol, "%s", oxorany("https://zerokill.authapi.xyz/server"));

        curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, "POST");
        curl_easy_setopt(curl, CURLOPT_URL, lol);
        curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
        curl_easy_setopt(curl, CURLOPT_DEFAULT_PROTOCOL, "https");
        curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 15L);
        curl_easy_setopt(curl, CURLOPT_TIMEOUT, 20L);
        curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);

        struct curl_slist* headers = nullptr;
        headers = curl_slist_append(headers, "Accept: application/json");
        headers = curl_slist_append(headers, "Content-Type: application/x-www-form-urlencoded");
        headers = curl_slist_append(headers, "Charset: UTF-8");
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

        char data[4096];
        sprintf(data, "game=PUBG&user_key=%s&serial=%s", user_key.c_str(), UUID.c_str());
        curl_easy_setopt(curl, CURLOPT_POST, 1);
        curl_easy_setopt(curl, CURLOPT_POSTFIELDS, data);
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteMemoryCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, (void*)&chunk);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYSTATUS, 0);
        curl_easy_setopt(curl, CURLOPT_USERAGENT, "");

        CURLcode res = curl_easy_perform(curl);
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);

        if (res != CURLE_OK) {
            errMsg = curl_easy_strerror(res);
            free(chunk.memory);
            if (attempt < 2) { usleep(800000); continue; }
            break;
        }

        if (!chunk.memory || chunk.size == 0) {
            errMsg = "Server not responding";
            free(chunk.memory);
            if (attempt < 2) { usleep(800000); continue; }
            break;
        }

        try {
            json result = json::parse(chunk.memory);
            free(chunk.memory);

            auto STATUS = std::string{"status"};
            if (result.contains(STATUS) && result[STATUS] == true) {

                if (!result.contains("data") || result["data"].is_null()) {
                    errMsg = "Server data is null";
                    if (attempt < 2) { usleep(800000); continue; }
                } else {
                    auto dataObj = result["data"];

                    g_Token = dataObj.contains("token") && !dataObj["token"].is_null()
                              ? dataObj["token"].get<std::string>() : "";

                    std::string enc = dataObj.contains("Enc") && !dataObj["Enc"].is_null()
                                      ? dataObj["Enc"].get<std::string>() : "";

                    g_exdate = dataObj.contains("EXP") && !dataObj["EXP"].is_null()
                               ? dataObj["EXP"].get<std::string>() : "NULL";

                    rng = dataObj.contains("rng") && !dataObj["rng"].is_null()
                          ? dataObj["rng"].get<time_t>() : 0;

                    if (rng + 30 > time(nullptr)) {
                        std::string auth = "PUBG";
                        auth += "-"; auth += user_key;
                        auth += "-"; auth += UUID;
                        auth += "-";
                        std::string license = oxorany("Vm8Lk7Uj2JmsjCPVPVjrLa7zgfx3uz9E");
                        auth += license;
                        g_Auth = CalcMD5(auth);
                        bValid = (g_Token == g_Auth);
                        if (bValid) {
                            g_userKey = user_key;
                            g_serial  = UUID;
                            g_licKey  = user_key;
                            strncpy(g_enc, enc.c_str(), sizeof(g_enc) - 1);
                        }
                    } else {
                        errMsg = "Server time mismatch, try again";
                        if (attempt < 2) { usleep(800000); continue; }
                    }
                }
            } else {
                auto REASON = std::string{"reason"};
                errMsg = result.contains(REASON) && !result[REASON].is_null()
                         ? result[REASON].get<std::string>()
                         : "Key is invalid or expired";
            }
        } catch (json::exception&) {
            free(chunk.memory);
            errMsg = "Invalid server response";
            if (attempt < 2) { usleep(800000); continue; }
        }

        break;
    }

    if (bValid) return env->NewStringUTF(oxorany("OK"));
    return env->NewStringUTF(errMsg.c_str());
}

// Login token verify — anti-smali-patch: checks bValid then clears it
extern "C" JNIEXPORT jboolean JNICALL
Java_com_bgmi_LoginActivity_nativeVerifyLoginToken(JNIEnv* env, jclass, jstring token) {
    if (!bValid || !token) return JNI_FALSE;
    const char* c = env->GetStringUTFChars(token, nullptr);
    bool ok = (strcmp(c, oxorany("OK")) == 0);
    env->ReleaseStringUTFChars(token, c);
    if (ok) bValid = false;
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_LoginActivity_nativeGetSerial(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_serial.c_str());
}

// ═══════════════════════════════════════════════════════════════════════
//  JNI EXPORTS — Downtwo (lib download)
// ═══════════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_utils_Downtwo_Version(JNIEnv* env, jclass) {
    return env->NewStringUTF(oxorany("https://github.com/somysam12/KingCheatsAuto/releases/download/KingCheats/version.txt"));
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_utils_Downtwo_Link(JNIEnv* env, jclass) {
    return env->NewStringUTF(oxorany("https://github.com/somysam12/KingCheatsAuto/releases/download/KingCheats/ZeroKill.zip"));
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_utils_Downtwo_UnlockUrl(JNIEnv* env, jclass) {
    return env->NewStringUTF(oxorany("https://zerokill.authapi.xyz/secure/unlock"));
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_utils_Downtwo_AesKey(JNIEnv* env, jclass) {
    return env->NewStringUTF(oxorany("5c3d9f2a8b1e4c6079d2f3a4b5c6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8"));
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_utils_Downtwo_HmacKey(JNIEnv* env, jclass) {
    return env->NewStringUTF(oxorany("X9kL2mQ7vR4nB1eY3pT8wS5uJ6dA0cF"));
}

// ═══════════════════════════════════════════════════════════════════════
//  JNI EXPORTS — ArkApplication SDK key
// ═══════════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_ArkApplication_getSdkKey(JNIEnv* env, jclass) {
    return env->NewStringUTF(oxorany("HCORE"));
}

// ═══════════════════════════════════════════════════════════════════════
//  JNI EXPORTS — IntegrityGuard (signature hash)
// ═══════════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jstring JNICALL
Java_com_bgmi_utils_IntegrityGuard_expectedSigHash(JNIEnv* env, jclass) {
    return env->NewStringUTF(EXPECTED_SIGNATURE);
}
