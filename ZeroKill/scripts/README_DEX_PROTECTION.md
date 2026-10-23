# FrozenFire DEX Protection System

## Architecture

```
Release Build Flow:
─────────────────────────────────────────────────────────
  1. assembleRelease (R8 + ProGuard + LSParanoid)
           ↓
  2. encryptReleaseDex (Gradle task, auto-runs)
     → python3 scripts/encrypt_dex.py app-release.apk
           ↓
  3. Output: app-release_protected.apk
     ├── classes.dex       [UNENCRYPTED stub: ArkApplication + DexDecryptor]
     ├── assets/ff_core.bin [AES-256-CBC encrypted: all other DEX files]
     └── lib/arm64-v8a/libshaitaan.so [JNI: AES key + decrypt + anti-debug]

Runtime Flow:
─────────────────────────────────────────────────────────
  App Launch
    → ArkApplication.attachBaseContext()
    → DexDecryptor.install(ctx)
         → Read assets/ff_core.bin
         → nativeDecrypt() [JNI AES-256 + anti-debug check]
         → Write decrypted DEX to getCacheDir()
         → Inject into PathClassLoader.dexElements
    → SplashActivity (now loadable from injected DEX)
```

## Protection Layers

| Layer | Tool | What It Does |
|-------|------|-------------|
| 1 | R8 Full Mode | Renames classes, methods, fields |
| 2 | ProGuard rules | Flattens all to package 'x', strips debug info |
| 3 | LSParanoid (global) | Encrypts ALL string literals at compile time |
| 4 | DEX encryption | AES-256-CBC encrypts classes2.dex+ |
| 5 | Native AES key | Key split across 4 XOR arrays in C++, never in Java |
| 6 | Anti-debug | ptrace check in JNI before decryption |
| 7 | Anti-Frida | /proc/self/maps scan for frida/linjector |
| 8 | APK signature pin | SHA-256 cert check (existing, in main.cpp) |

## Manual encryption (if Gradle task fails)

```bash
# Install crypto lib first
pip install pycryptodome

# Encrypt
python3 scripts/encrypt_dex.py app/build/outputs/apk/release/app-release.apk

# Optional: verify key fingerprint matches dex_crypt.cpp
python3 scripts/encrypt_dex.py --show-key
```

## Changing the AES Key

If you want a different encryption key:

1. Edit `_k1`, `_k2`, `_k3`, `_k4` arrays in `dex_crypt.cpp`
2. Copy the SAME arrays to `encrypt_dex.py` (keep both in sync!)
3. The XOR of all 4 arrays = actual 256-bit AES key
4. Rebuild native lib + re-encrypt DEX
