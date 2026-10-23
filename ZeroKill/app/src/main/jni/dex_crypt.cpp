/**
 * FrozenFire — AES-256-CBC DEX Decryptor
 * ========================================
 * Key: XOR of 4 arrays → never stored plaintext anywhere
 * IV:  first 16 bytes of encrypted blob (random per encryption)
 * Security checks run before decryption — if any fail, returns null
 */
#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include <openssl/aes.h>
#include <openssl/evp.h>
#include "oxorany.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FF_CRYPT", __VA_ARGS__)

// Forward declaration from security_checks.cpp
extern bool ff_security_ok(JNIEnv* env, jobject ctx);

// ── AES-256 key split across 4 arrays (XOR'd at runtime) ─────────────────────
// Changing key: edit all 4 arrays here AND the same arrays in build.gradle task
static const uint8_t _k1[32] = {
    0xF7,0x3A,0xC2,0x8B, 0x4E,0x1D,0x99,0x60,
    0xA5,0x72,0xEB,0x3F, 0x58,0xD4,0x06,0xBC,
    0x21,0x8E,0x47,0xC9, 0x6B,0xF0,0x35,0xA8,
    0xD3,0x14,0x7C,0xE5, 0x92,0x40,0xBB,0x2D
};
static const uint8_t _k2[32] = {
    0x81,0x5C,0xA4,0xE3, 0x2F,0x70,0xF1,0x0C,
    0xC3,0x1E,0x8D,0x56, 0x30,0xB7,0x62,0xDA,
    0x43,0xEC,0x25,0xAF, 0x0D,0x94,0x57,0xCE,
    0xB5,0x72,0x1A,0x83, 0xF4,0x26,0xDD,0x49
};
static const uint8_t _k3[32] = {
    0x29,0x6E,0x76,0x5A, 0x13,0x4B,0x8C,0xE4,
    0x67,0xA0,0x39,0xC8, 0x7C,0x5B,0xD1,0xF2,
    0x8F,0x72,0xBE,0x34, 0x61,0x2C,0x0A,0x55,
    0x4E,0x38,0xC6,0x91, 0x7D,0xF9,0x50,0x1B
};
static const uint8_t _k4[32] = {
    0x5E,0x08,0xB0,0x36, 0x62,0xC6,0x46,0x88,
    0x31,0xCC,0x67,0xA7, 0x04,0x1E,0xA5,0x60,
    0x2D,0xF4,0x9A,0x16, 0x37,0x48,0x88,0x67,
    0x80,0x0E,0xA9,0x77, 0xC1,0x9F,0x66,0x61
};

// Derive key at runtime — key never in memory until this function is called
__attribute__((noinline))
static void derive_key(uint8_t out[32]) {
    // Bogus XOR to confuse static analysis
    volatile uint8_t noise = (uint8_t)(uintptr_t)&out ^ 0x5A;
    for (int i = 0; i < 32; i++) {
        out[i] = _k1[i] ^ _k2[i] ^ _k3[i] ^ _k4[i] ^ (noise & 0);
    }
    (void)noise;
}

// ── AES-256-CBC decrypt ───────────────────────────────────────────────────────
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_zerokill_DexDecryptor_nativeDecrypt(
        JNIEnv* env, jclass, jbyteArray encrypted_data) {

    // Security gate — all checks must pass
    if (!ff_security_ok(env, nullptr)) {
        LOGE("Security gate failed");
        // Corrupt return to prevent partial extraction
        jbyteArray junk = env->NewByteArray(4);
        return junk;
    }

    jsize data_len = env->GetArrayLength(encrypted_data);
    if (data_len <= 16) return nullptr;

    jbyte* data = env->GetByteArrayElements(encrypted_data, nullptr);
    if (!data) return nullptr;

    // IV = first 16 bytes
    uint8_t iv[16];
    memcpy(iv, data, 16);

    // Derive AES key
    uint8_t key[32];
    derive_key(key);

    // Decrypt with OpenSSL AES-256-CBC
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) {
        env->ReleaseByteArrayElements(encrypted_data, data, JNI_ABORT);
        return nullptr;
    }

    const uint8_t* cipher = (const uint8_t*)(data + 16);
    int clen = (int)(data_len - 16);
    std::vector<uint8_t> plain(clen + AES_BLOCK_SIZE);

    int len1 = 0, len2 = 0;
    EVP_DecryptInit_ex(ctx, EVP_aes_256_cbc(), nullptr, key, iv);
    EVP_DecryptUpdate(ctx, plain.data(), &len1, cipher, clen);
    int ok = EVP_DecryptFinal_ex(ctx, plain.data() + len1, &len2);
    EVP_CIPHER_CTX_free(ctx);

    // Wipe key immediately from stack
    memset(key, 0, sizeof(key));
    memset(iv,  0, sizeof(iv));

    env->ReleaseByteArrayElements(encrypted_data, data, JNI_ABORT);

    if (ok <= 0) {
        LOGE("AES decrypt failed — tampered blob?");
        return nullptr;
    }

    int total = len1 + len2;
    jbyteArray result = env->NewByteArray(total);
    env->SetByteArrayRegion(result, 0, total, (jbyte*)plain.data());

    // Wipe plaintext from native heap
    memset(plain.data(), 0, plain.size());
    return result;
}
