#!/usr/bin/env python3
"""
FrozenFire — DEX Encryptor
==========================
Run AFTER release APK is built:

    python3 scripts/encrypt_dex.py app/build/outputs/apk/release/app-release.apk

What it does:
  1. Unpacks the APK (it's a ZIP)
  2. Takes classes2.dex, classes3.dex... (main app code in multidex)
  3. AES-256-CBC encrypts them, prepends 16-byte random IV
  4. Writes encrypted bundle to assets/ff_core.bin inside the APK
  5. Removes the original classes2.dex, classes3.dex from APK
  6. Re-zips + zipaligns (requires build-tools on PATH)

NOTE: classes.dex is kept unencrypted — it contains:
  ArkApplication + DexDecryptor (the stub loader, minimal)

AES KEY must match the XOR-derived key in dex_crypt.cpp → get_aes_key().
Run with --show-key flag once to get the fingerprint, then update dex_crypt.cpp.
"""

import os
import sys
import struct
import zipfile
import shutil
import tempfile
import hashlib
import argparse
from pathlib import Path

# ── AES key derivation (MUST MATCH dex_crypt.cpp get_aes_key) ─────────────────
_k1 = bytes([
    0xF7,0x3A,0xC2,0x8B, 0x4E,0x1D,0x99,0x60,
    0xA5,0x72,0xEB,0x3F, 0x58,0xD4,0x06,0xBC,
    0x21,0x8E,0x47,0xC9, 0x6B,0xF0,0x35,0xA8,
    0xD3,0x14,0x7C,0xE5, 0x92,0x40,0xBB,0x2D
])
_k2 = bytes([
    0x81,0x5C,0xA4,0xE3, 0x2F,0x70,0xF1,0x0C,
    0xC3,0x1E,0x8D,0x56, 0x30,0xB7,0x62,0xDA,
    0x43,0xEC,0x25,0xAF, 0x0D,0x94,0x57,0xCE,
    0xB5,0x72,0x1A,0x83, 0xF4,0x26,0xDD,0x49
])
_k3 = bytes([
    0x29,0x6E,0x76,0x5A, 0x13,0x4B,0x8C,0xE4,
    0x67,0xA0,0x39,0xC8, 0x7C,0x5B,0xD1,0xF2,
    0x8F,0x72,0xBE,0x34, 0x61,0x2C,0x0A,0x55,
    0x4E,0x38,0xC6,0x91, 0x7D,0xF9,0x50,0x1B
])
_k4 = bytes([
    0x5E,0x08,0xB0,0x36, 0x62,0xC6,0x46,0x88,
    0x31,0xCC,0x67,0xA7, 0x04,0x1E,0xA5,0x60,
    0x2D,0xF4,0x9A,0x16, 0x37,0x48,0x88,0x67,
    0x80,0x0E,0xA9,0x77, 0xC1,0x9F,0x66,0x61
])

def get_aes_key() -> bytes:
    return bytes(a ^ b ^ c ^ d for a,b,c,d in zip(_k1,_k2,_k3,_k4))


# ── AES-256-CBC via pycryptodome or cryptography ──────────────────────────────
def aes_encrypt(plaintext: bytes, key: bytes) -> bytes:
    """Returns [16-byte random IV][ciphertext padded to AES block size]"""
    try:
        from Crypto.Cipher import AES
        from Crypto.Util.Padding import pad
        import os
        iv = os.urandom(16)
        cipher = AES.new(key, AES.MODE_CBC, iv)
        return iv + cipher.encrypt(pad(plaintext, 16))
    except ImportError:
        pass

    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        from cryptography.hazmat.primitives import padding as cp
        import os
        iv = os.urandom(16)
        padder = cp.PKCS7(128).padder()
        padded = padder.update(plaintext) + padder.finalize()
        cipher = Cipher(algorithms.AES(key), modes.CBC(iv))
        enc = cipher.encryptor()
        return iv + enc.update(padded) + enc.finalize()
    except ImportError:
        pass

    raise RuntimeError(
        "Install pycryptodome or cryptography:\n"
        "  pip install pycryptodome\n"
        "OR\n"
        "  pip install cryptography"
    )


def encrypt_apk(apk_path: str, show_key: bool = False):
    key = get_aes_key()

    if show_key:
        fp = hashlib.sha256(key).hexdigest()[:16]
        print(f"[KEY] AES-256 key fingerprint (first 8 bytes sha256): {fp}")
        print("[KEY] Matches dex_crypt.cpp get_aes_key() → verify before shipping!")
        return

    apk_path = Path(apk_path).resolve()
    if not apk_path.exists():
        print(f"[ERR] APK not found: {apk_path}")
        sys.exit(1)

    print(f"[FF] Encrypting DEX in: {apk_path.name}")

    tmp_dir  = Path(tempfile.mkdtemp(prefix="ff_dex_"))
    out_apk  = apk_path.parent / (apk_path.stem + "_protected.apk")

    try:
        # 1. Extract APK
        print("[FF] Unpacking APK...")
        with zipfile.ZipFile(apk_path, 'r') as z:
            z.extractall(tmp_dir)

        # 2. Find extra DEX files (classes2.dex, classes3.dex, ...)
        dex_files = sorted(tmp_dir.glob("classes*.dex"))
        stub_dex  = tmp_dir / "classes.dex"   # keep unencrypted (Application stub)
        extra_dex = [d for d in dex_files if d != stub_dex]

        if not extra_dex:
            print("[WARN] No classes2.dex found — only classes.dex exists (single dex)")
            print("[WARN] Encrypting classes.dex directly...")
            extra_dex = dex_files          # encrypt everything
            stub_dex  = None               # no stub kept

        # 3. Bundle + encrypt all extra DEX files
        # Format: [4-byte count N][N × (4-byte len)(dex data)...] then AES
        chunks = b""
        count  = 0
        for df in extra_dex:
            data = df.read_bytes()
            chunks += struct.pack(">I", len(data)) + data
            count  += 1
            print(f"[FF] Bundling {df.name} ({len(data)//1024} KB)")

        bundle  = struct.pack(">I", count) + chunks
        enc     = aes_encrypt(bundle, key)

        # 4. Write ff_core.bin to assets/
        assets_dir = tmp_dir / "assets"
        assets_dir.mkdir(exist_ok=True)
        (assets_dir / "ff_core.bin").write_bytes(enc)
        print(f"[FF] ff_core.bin written ({len(enc)//1024} KB encrypted)")

        # 5. Remove original extra DEX files from unpacked APK
        for df in extra_dex:
            df.unlink()
            print(f"[FF] Removed {df.name} from APK")

        # 6. Re-pack APK
        print("[FF] Re-packing APK...")
        with zipfile.ZipFile(out_apk, 'w', zipfile.ZIP_DEFLATED) as zout:
            for f in tmp_dir.rglob("*"):
                if f.is_file():
                    arcname = f.relative_to(tmp_dir)
                    # classes.dex and .so files stored uncompressed (zipalign requirement)
                    compress = zipfile.ZIP_DEFLATED
                    if arcname.suffix in ('.dex', '.so', '.arsc'):
                        compress = zipfile.ZIP_STORED
                    zout.write(f, arcname, compress_type=compress)

        print(f"\n[FF] ✅ Protected APK: {out_apk.name}")
        print("[FF] Re-sign with apksigner / jarsigner if needed after zipalign!")

        # Print key fingerprint for verification
        fp = hashlib.sha256(key).hexdigest()[:16]
        print(f"[FF] Key fingerprint: {fp}")

    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="FrozenFire DEX Encryptor")
    parser.add_argument("apk", nargs="?", help="Path to release APK")
    parser.add_argument("--show-key", action="store_true",
                        help="Print key fingerprint and exit (for verification)")
    args = parser.parse_args()

    if args.show_key and not args.apk:
        key = get_aes_key()
        fp  = hashlib.sha256(key).hexdigest()[:16]
        print(f"AES-256 key fingerprint: {fp}")
        sys.exit(0)

    if not args.apk:
        parser.print_help()
        sys.exit(1)

    encrypt_apk(args.apk, show_key=args.show_key)
