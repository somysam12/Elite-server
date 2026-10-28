#!/usr/bin/env python3
"""Build Zeraxn_Cracked_v9.apk
NEW vs v8:
  - REVERTED patches 2&3 (pthread_create restored) — they caused app not to open
  - libxm2dk9dm.so patched with 9 patches:
    * 0x893c4: b.eq → b (bypass abort on hash mismatch) — KEPT from v8
    * 0x92b54: BL abort → NOP (thread_func_1 integrity abort)
    * 0x930c8..0x9320c: 7x BL abort → NOP (thread_func_2 integrity abort cluster)
"""
import os, sys, struct, zipfile, subprocess, shutil, secrets, io

WORK      = "/tmp/zeraxn_work"
V7_APK    = "/root/.claude/uploads/959a03d3-3e7d-5535-8fd3-92f92234031e/7266c951-ZeraxLoader_v7_gamecrash_fix.apk"
ORIG_APK  = f"{WORK}/Zeraxn-4.6.apk"
OUT_APK   = f"{WORK}/Zeraxn_Cracked_v9.apk"
SMALI     = f"{WORK}/smali_hidden"
PUSHPA    = "/tmp/zeraxn_assets/libPUSHPA_patched.so"
XM2DK9DM = "/tmp/libxm2dk9dm_v9_patched.so"

JKS_PATH  = "/root/.claude/uploads/959a03d3-3e7d-5535-8fd3-92f92234031e/zen.jks"
JKS_ALIAS = "SLAYTWINZ@66"
JKS_PASS  = "TWINZZZGODD@6"

# ---- Step 0: Locate keystore ----
if not os.path.exists(JKS_PATH):
    import glob
    for c in glob.glob("/root/.claude/uploads/**/*.jks", recursive=True) + \
             glob.glob("/tmp/**/*.jks", recursive=True):
        if 'zen' in c.lower() or 'slay' in c.lower():
            JKS_PATH = c; break
    if not os.path.exists(JKS_PATH):
        print(f"ERROR: keystore not found"); sys.exit(1)
print(f"[*] Keystore: {JKS_PATH}")

if not os.path.exists(V7_APK):
    print(f"ERROR: v7 APK not found at {V7_APK}"); sys.exit(1)
print(f"[*] Base APK: v7 ({os.path.getsize(V7_APK)} bytes)")

if not os.path.exists(XM2DK9DM):
    print(f"ERROR: patched libxm2dk9dm.so not found at {XM2DK9DM}"); sys.exit(1)
print(f"[*] Patched libxm2dk9dm.so: {os.path.getsize(XM2DK9DM)} bytes")

# ---- Verify integrity patches in libxm2dk9dm_v9_patched.so ----
print("[*] Verifying libxm2dk9dm.so patches ...")
with open(XM2DK9DM, 'rb') as f:
    lib = f.read()
patches = [
    (0x893c4, b'\x57\x00\x00\x14', "b.eq→b (bypass abort)"),
    (0x92b54, b'\x1f\x20\x03\xd5', "NOP abort (thread_func_1)"),
    (0x930c8, b'\x1f\x20\x03\xd5', "NOP abort cluster1"),
    (0x930d8, b'\x1f\x20\x03\xd5', "NOP abort cluster2"),
    (0x9311c, b'\x1f\x20\x03\xd5', "NOP abort cluster3"),
    (0x93154, b'\x1f\x20\x03\xd5', "NOP abort cluster4"),
    (0x931bc, b'\x1f\x20\x03\xd5', "NOP abort cluster5"),
    (0x931f4, b'\x1f\x20\x03\xd5', "NOP abort cluster6"),
    (0x9320c, b'\x1f\x20\x03\xd5', "NOP abort cluster7"),
]
for off, expected, name in patches:
    actual = lib[off:off+4]
    ok = actual == expected
    print(f"    {'[+]' if ok else '[!]'} 0x{off:x} {name}: {actual.hex()} {'OK' if ok else 'MISMATCH'}")
    if not ok:
        sys.exit(1)

# Also verify pthread_create calls are NOT patched (v8 regression fix)
for off, expected, name in [
    (0x86e78, b'\x9a\xed\x01\x94', "pthread_create native_Check (restored)"),
    (0x8a348, b'\x66\xe0\x01\x94', "pthread_create JNI_OnLoad (restored)"),
]:
    actual = lib[off:off+4]
    ok = actual == expected
    print(f"    {'[+]' if ok else '[!]'} 0x{off:x} {name}: {actual.hex()} {'OK' if ok else 'MISMATCH'}")
    if not ok:
        sys.exit(1)

# ---- Step 1: Verify smali patches ----
print("[*] Verifying smali patches ...")
checks = [
    (f"{SMALI}/com/mundopp/api/external/MetaActivationManager.smali",
     'const/4 v0, 0x1', "getActivationStatus → true"),
    (f"{SMALI}/top/mundopp/engine/proxy/MundoPPProcessBootstrap.smali",
     '.method public final SystemHookProxy()V', "SystemHookProxy → no-op"),
    (f"{SMALI}/top/mundopp/engine/proxy/MundoPPAppThreadStub.smali",
     ':pswitch_64', "pswitch_64 patched"),
    (f"{SMALI}/com/mundopp/api/external/MetaInjectedLibrary.smali",
     'const-string v0, "PUSHPA"', "loadInCurrentProcess → PUSHPA"),
    (f"{SMALI}/top/mundopp/engine/proxy/MundoPPAppKeyActivator.smali",
     'ParcelInvocationHandler__DISABLED', "ParcelInvocationHandler → no-op"),
]
for fpath, needle, label in checks:
    with open(fpath) as f:
        content = f.read()
    if needle in content:
        print(f"    [+] {label}")
    else:
        print(f"    [!] MISSING: {label}")
        sys.exit(1)

import re
with open(f"{SMALI}/top/mundopp/engine/proxy/MundoPPAppKeyActivator.smali") as f:
    kact = f.read()
m = re.search(r'\.method public static final NotificationHookProxy\(\)V\s+\.registers \d+\s+return-void\s+\.end method', kact)
if m:
    print("    [+] NotificationHookProxy()V → no-op")
else:
    print("    [!] NotificationHookProxy()V not patched")
    sys.exit(1)

# ---- Step 2: Compile smali → DEX ----
print("[*] Compiling smali → DEX ...")
out_dex = f"{WORK}/hidden_classes_v9.dex"
r = subprocess.run(
    ["smali", "assemble", "-o", out_dex, SMALI],
    capture_output=True, text=True
)
if r.returncode != 0 or not os.path.exists(out_dex):
    print(f"    [!] smali failed:\n{r.stderr}"); sys.exit(1)
print(f"    [+] DEX: {os.path.getsize(out_dex)} bytes")

with open(out_dex, 'rb') as f:
    if not f.read(8).startswith(b'dex\n'):
        print("    [!] Bad DEX magic"); sys.exit(1)
print("    [+] DEX magic OK")

# ---- Step 3: Decrypt ZRXD from ORIGINAL APK, patch with new DEX, re-encrypt ----
print("[*] Patching kw9tzxhc.idx (from original APK) ...")
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

KEY = bytes.fromhex("e87042baab80c81529b98193128416be1f572ecf2b2e64502bbaaff669491724")

with zipfile.ZipFile(ORIG_APK) as z:
    orig_idx = z.read("assets/dexopt/kw9tzxhc.idx")

assert orig_idx[:4] == b'ZRX1'
orig_iv  = orig_idx[4:20]
enc_data = orig_idx[20:]

dec = Cipher(algorithms.AES(KEY), modes.CTR(orig_iv)).decryptor()
plaintext = dec.update(enc_data) + dec.finalize()
assert plaintext[:4] == b'ZRXD', f"Wrong ZRXD magic: {plaintext[:8].hex()}"
print(f"    [+] ZRXD decrypted OK ({len(plaintext)} bytes)")

dex_off = plaintext.find(b'dex\n')
assert dex_off >= 0, "DEX not in ZRXD"
print(f"    [+] DEX at offset {dex_off}")

with open(out_dex, 'rb') as f:
    new_dex = f.read()

prefix = bytearray(plaintext[:dex_off])
if dex_off == 12:
    struct.pack_into('<I', prefix, 8, len(new_dex))
    print(f"    [+] ZRXD size updated: {len(new_dex)}")

new_plaintext = bytes(prefix) + new_dex
new_iv = secrets.token_bytes(16)
enc = Cipher(algorithms.AES(KEY), modes.CTR(new_iv)).encryptor()
new_ciphertext = enc.update(new_plaintext) + enc.finalize()
new_idx = b'ZRX1' + new_iv + new_ciphertext

dec2 = Cipher(algorithms.AES(KEY), modes.CTR(new_iv)).decryptor()
verify = dec2.update(new_ciphertext) + dec2.finalize()
assert verify[:4] == b'ZRXD' and verify.find(b'dex\n') >= 0
print(f"    [+] Round-trip OK. New .idx: {len(new_idx)} bytes")

# ---- Step 4: Pack APK from v7 base ----
print("[*] Building APK from v7 base ...")
buf = io.BytesIO()
skip = {"assets/dexopt/kw9tzxhc.idx", "lib/arm64-v8a/libxm2dk9dm.so",
        "META-INF/ZERAX.SF", "META-INF/ZERAX.RSA", "META-INF/MANIFEST.MF"}

with zipfile.ZipFile(V7_APK, 'r') as src, \
     zipfile.ZipFile(buf, 'w') as dst:
    for item in src.infolist():
        if item.filename in skip:
            continue
        data = src.read(item.filename)
        new_item = zipfile.ZipInfo(item.filename)
        new_item.compress_type = item.compress_type
        dst.writestr(new_item, data)

    # Patched hidden DEX
    idx_info = zipfile.ZipInfo("assets/dexopt/kw9tzxhc.idx")
    idx_info.compress_type = zipfile.ZIP_DEFLATED
    dst.writestr(idx_info, new_idx)
    print(f"    [+] kw9tzxhc.idx: {len(new_idx)} bytes")

    # Patched libxm2dk9dm.so (integrity bypass + thread abort NOP)
    with open(XM2DK9DM, 'rb') as xf:
        xm_data = xf.read()
    xm_info = zipfile.ZipInfo("lib/arm64-v8a/libxm2dk9dm.so")
    xm_info.compress_type = zipfile.ZIP_STORED
    dst.writestr(xm_info, xm_data)
    print(f"    [+] libxm2dk9dm.so: {len(xm_data)} bytes (v9 patched)")

    # libPUSHPA.so offline
    with open(PUSHPA, 'rb') as pf:
        pushpa_data = pf.read()
    pushpa_info = zipfile.ZipInfo("lib/arm64-v8a/libPUSHPA.so")
    pushpa_info.compress_type = zipfile.ZIP_STORED
    dst.writestr(pushpa_info, pushpa_data)
    print(f"    [+] libPUSHPA.so: {len(pushpa_data)} bytes")

raw_apk = f"{WORK}/Zeraxn_v9_raw.apk"
with open(raw_apk, 'wb') as f:
    f.write(buf.getvalue())
print(f"    [+] Raw APK: {os.path.getsize(raw_apk)} bytes")

# ---- Step 5: zipalign ----
print("[*] Zipaligning ...")
aligned_apk = f"{WORK}/Zeraxn_v9_aligned.apk"
r = subprocess.run(["zipalign", "-v", "-f", "4", raw_apk, aligned_apk],
                   capture_output=True, text=True)
if r.returncode != 0:
    print(f"    [!] zipalign failed, using raw: {r.stderr[:80]}")
    shutil.copy(raw_apk, aligned_apk)
else:
    print(f"    [+] Aligned: {os.path.getsize(aligned_apk)} bytes")

# ---- Step 6: Sign ----
print("[*] Signing ...")
r = subprocess.run(
    ["apksigner", "sign",
     "--ks", JKS_PATH,
     "--ks-key-alias", JKS_ALIAS,
     "--ks-pass", f"pass:{JKS_PASS}",
     "--key-pass", f"pass:{JKS_PASS}",
     "--out", OUT_APK,
     aligned_apk],
    capture_output=True, text=True
)
if r.returncode != 0:
    print(f"    [!] apksigner failed: {r.stderr[:500]}"); sys.exit(1)
print(f"    [+] Signed: {OUT_APK} ({os.path.getsize(OUT_APK)} bytes)")

# ---- Step 7: Verify ----
print("[*] Verifying ...")
r = subprocess.run(["apksigner", "verify", "--verbose", OUT_APK], capture_output=True, text=True)
print(f"    {r.stdout.strip()[:200]}")
with zipfile.ZipFile(OUT_APK) as z:
    for info in z.infolist():
        if info.filename == 'classes.dex':
            print(f"    classes.dex: {info.file_size} bytes (v7)")
        if 'PUSHPA' in info.filename:
            print(f"    libPUSHPA.so: {info.file_size} bytes")
        if 'xm2dk9dm' in info.filename:
            print(f"    libxm2dk9dm.so: {info.file_size} bytes (v9 patched)")
        if 'kw9tzxhc' in info.filename:
            print(f"    kw9tzxhc.idx: {info.file_size} bytes")
print(f"\n[DONE] {OUT_APK}")
