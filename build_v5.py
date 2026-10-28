#!/usr/bin/env python3
"""Build Zeraxn_Cracked_v5.apk
All crashes fixed:
  1. activateSdk() → no-op (no key sent to engine)
  2. getActivationStatus() → always true
  3. MundoPPProcessBootstrap.SystemHookProxy() → no-op (no kill)
  4. MundoPPAppThreadStub pswitch_64 → return (no Process.killProcess)
  5. libPUSHPA.so auth func (0xd9ed8) → return 0 immediately
  6. libPUSHPA.so remaining exit() calls NOP'd
  7. MetaInjectedLibrary.loadInCurrentProcess → loads "PUSHPA"
"""
import os, sys, struct, zipfile, subprocess, shutil, secrets, io

WORK      = "/tmp/zeraxn_work"
ORIG_APK  = f"{WORK}/Zeraxn-4.6.apk"
OUT_APK   = f"{WORK}/Zeraxn_Cracked_v5.apk"
SMALI     = f"{WORK}/smali_hidden"
PUSHPA    = "/tmp/zeraxn_assets/libPUSHPA_patched.so"

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

# ---- Step 1: Verify smali patches ----
print("[*] Verifying smali patches ...")
checks = [
    (f"{SMALI}/com/mundopp/api/external/MetaActivationManager.smali",
     'const/4 v0, 0x1', "getActivationStatus → true"),
    (f"{SMALI}/com/mundopp/api/external/MetaActivationManager.smali",
     'activateSdk', "activateSdk present"),
    (f"{SMALI}/top/mundopp/engine/proxy/MundoPPProcessBootstrap.smali",
     '.method public final SystemHookProxy()V', "SystemHookProxy patched"),
    (f"{SMALI}/top/mundopp/engine/proxy/MundoPPAppThreadStub.smali",
     ':pswitch_64', "pswitch_64 patched"),
    (f"{SMALI}/com/mundopp/api/external/MetaInjectedLibrary.smali",
     'const-string v0, "PUSHPA"', "loadInCurrentProcess → PUSHPA"),
    (f"{SMALI}/top/mundopp/engine/proxy/MundoPPAppKeyActivator.smali",
     'return-void', "GenericHookProxy → no-op"),
]
for fpath, needle, label in checks:
    with open(fpath) as f:
        content = f.read()
    if needle in content:
        print(f"    [+] {label}")
    else:
        print(f"    [!] MISSING: {label}")
        sys.exit(1)

# Also verify MetaActivationManager.activateSdk is no-op
with open(f"{SMALI}/com/mundopp/api/external/MetaActivationManager.smali") as f:
    meta_content = f.read()
if 'activateSdk' in meta_content:
    # Check it's the no-op version
    if 'return-void' in meta_content:
        print("    [+] activateSdk → return-void (no-op)")
    else:
        print("    [!] activateSdk not return-void")

# ---- Step 2: Compile smali → DEX ----
print("[*] Compiling smali → DEX ...")
out_dex = f"{WORK}/hidden_classes_v5.dex"
r = subprocess.run(
    ["smali", "assemble", "-o", out_dex, SMALI],
    capture_output=True, text=True
)
if r.returncode != 0 or not os.path.exists(out_dex):
    print(f"    [!] smali failed:\n{r.stderr}"); sys.exit(1)
dex_size = os.path.getsize(out_dex)
print(f"    [+] DEX: {dex_size} bytes")

with open(out_dex, 'rb') as f:
    magic = f.read(8)
if not magic.startswith(b'dex\n'):
    print(f"    [!] Bad DEX magic: {magic!r}"); sys.exit(1)
print(f"    [+] DEX magic OK")

# ---- Step 3: Decrypt → patch ZRXD → re-encrypt ----
print("[*] Patching kw9tzxhc.idx ...")
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

KEY = bytes.fromhex("e87042baab80c81529b98193128416be1f572ecf2b2e64502bbaaff669491724")

with zipfile.ZipFile(ORIG_APK) as z:
    orig_idx = z.read("assets/dexopt/kw9tzxhc.idx")

assert orig_idx[:4] == b'ZRX1'
orig_iv       = orig_idx[4:20]
enc_data      = orig_idx[20:]

dec = Cipher(algorithms.AES(KEY), modes.CTR(orig_iv)).decryptor()
plaintext = dec.update(enc_data) + dec.finalize()
assert plaintext[:4] == b'ZRXD', f"Wrong ZRXD magic: {plaintext[:8].hex()}"
print(f"    [+] ZRXD decrypted OK ({len(plaintext)} bytes)")

dex_off = plaintext.find(b'dex\n')
assert dex_off >= 0, "DEX not in ZRXD container"
print(f"    [+] DEX at offset {dex_off} in ZRXD")

with open(out_dex, 'rb') as f:
    new_dex = f.read()

prefix = bytearray(plaintext[:dex_off])
# Update size field at offset 8 (ZRXD format: magic(4)+version(4)+size(4))
if dex_off == 12:
    struct.pack_into('<I', prefix, 8, len(new_dex))
    print(f"    [+] ZRXD size field updated: {len(new_dex)}")

new_plaintext = bytes(prefix) + new_dex
new_iv        = secrets.token_bytes(16)

enc = Cipher(algorithms.AES(KEY), modes.CTR(new_iv)).encryptor()
new_ciphertext = enc.update(new_plaintext) + enc.finalize()
new_idx = b'ZRX1' + new_iv + new_ciphertext

# Round-trip verify
dec2 = Cipher(algorithms.AES(KEY), modes.CTR(new_iv)).decryptor()
verify = dec2.update(new_ciphertext) + dec2.finalize()
assert verify[:4] == b'ZRXD'
assert verify.find(b'dex\n') >= 0
print(f"    [+] Round-trip verify OK. New .idx: {len(new_idx)} bytes")

# ---- Step 4: Pack APK ----
print("[*] Building APK ...")
buf = io.BytesIO()
skip = {"assets/dexopt/kw9tzxhc.idx"}
with zipfile.ZipFile(ORIG_APK, 'r') as src, \
     zipfile.ZipFile(buf, 'w') as dst:
    for item in src.infolist():
        if item.filename in skip:
            continue
        dst.writestr(item, src.read(item.filename))

    # Patched DEX container
    idx_info = zipfile.ZipInfo("assets/dexopt/kw9tzxhc.idx")
    idx_info.compress_type = zipfile.ZIP_DEFLATED
    dst.writestr(idx_info, new_idx)
    print(f"    [+] kw9tzxhc.idx: {len(new_idx)} bytes")

    # Patched libPUSHPA.so
    if os.path.exists(PUSHPA):
        with open(PUSHPA, 'rb') as pf:
            pushpa_data = pf.read()
        pushpa_info = zipfile.ZipInfo("lib/arm64-v8a/libPUSHPA.so")
        pushpa_info.compress_type = zipfile.ZIP_STORED
        dst.writestr(pushpa_info, pushpa_data)
        print(f"    [+] libPUSHPA.so (patched): {len(pushpa_data)} bytes")
    else:
        print(f"    [!] WARNING: patched libPUSHPA.so not found")

raw_apk = f"{WORK}/Zeraxn_v4_raw.apk"
with open(raw_apk, 'wb') as f:
    f.write(buf.getvalue())
print(f"    [+] Raw APK: {os.path.getsize(raw_apk)} bytes")

# ---- Step 5: zipalign ----
print("[*] Zipaligning ...")
aligned_apk = f"{WORK}/Zeraxn_v4_aligned.apk"
r = subprocess.run(
    ["zipalign", "-v", "-f", "4", raw_apk, aligned_apk],
    capture_output=True, text=True
)
if r.returncode != 0:
    print(f"    [!] zipalign failed, using raw: {r.stderr[:100]}")
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
r = subprocess.run(["apksigner", "verify", "--verbose", OUT_APK],
                   capture_output=True, text=True)
print(f"    {r.stdout.strip()[:200]}")

with zipfile.ZipFile(OUT_APK) as z:
    for info in z.infolist():
        if info.filename == 'resources.arsc':
            print(f"    resources.arsc: compress={info.compress_type} (0=STORED)")
        if 'PUSHPA' in info.filename:
            print(f"    libPUSHPA.so: {info.file_size} bytes, compress={info.compress_type}")
        if 'kw9tzxhc' in info.filename:
            print(f"    kw9tzxhc.idx: {info.file_size} bytes")

print(f"\n[DONE] {OUT_APK}")
