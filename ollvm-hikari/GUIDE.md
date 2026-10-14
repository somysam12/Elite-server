# Hikari Obfuscator (LLVM 8) — Build & Usage Guide

Hikari is a comprehensive LLVM 8-based obfuscation toolchain built at `/opt/hikari-build/`.
Clang binary: `/opt/hikari-build/bin/clang`

---

## Available Passes & Flags

| Flag (use with `-mllvm`) | Pass | Description |
|--------------------------|------|-------------|
| `-enable-bcfobf` | Bogus Control Flow | Inserts fake branches with always-true conditions |
| `-enable-cffobf` | Control Flow Flattening | Converts function CFG into a flat dispatcher loop |
| `-enable-subobf` | Instruction Substitution | Replaces arithmetic with equivalent complex ops |
| `-enable-strcry` | String Encryption | Encrypts all string literals, decrypts at runtime |
| `-enable-indibran` | Indirect Branching | Hides all branch targets behind a global lookup table |
| `-enable-funcwra` | Function Wrapper | Wraps every call through indirect dispatch, breaks call graphs |
| `-enable-fco` | Function Call Obfuscate | Obfuscates function call addresses |
| `-enable-splitobf` | Basic Block Split | Splits basic blocks to increase complexity |
| `-enable-acdobf` | Anti Class Dump | Strips ObjC class metadata (iOS/macOS targets) |
| `-enable-allobf` | Enable Everything | Enables all passes at once |

---

## Usage with Clang Directly

```bash
/opt/hikari-build/bin/clang \
  -mllvm -enable-bcfobf \
  -mllvm -enable-cffobf \
  -mllvm -enable-subobf \
  -mllvm -enable-indibran \
  -mllvm -enable-funcwra \
  -mllvm -enable-strcry \
  -O1 source.c -o output
```

Or enable everything at once:
```bash
/opt/hikari-build/bin/clang -mllvm -enable-allobf -O1 source.c -o output
```

---

## Usage with Android NDK (CMakeLists.txt)

Hikari (LLVM 8) is a separate toolchain from NDK. Override the compiler in cmake:

```cmake
set(HIKARI_CLANG "/opt/hikari-build/bin/clang")
set(HIKARI_CLANGXX "/opt/hikari-build/bin/clang++")

set(CMAKE_C_COMPILER   ${HIKARI_CLANG})
set(CMAKE_CXX_COMPILER ${HIKARI_CLANGXX})

set(HIKARI_FLAGS
  "-mllvm;-enable-bcfobf"
  "-mllvm;-enable-cffobf"
  "-mllvm;-enable-subobf"
  "-mllvm;-enable-indibran"
  "-mllvm;-enable-strcry"
)

add_compile_options(${HIKARI_FLAGS})
```

---

## Usage with Android Gradle (build.gradle)

```groovy
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                def hikari = "/opt/hikari-build/bin/clang"
                arguments "-DCMAKE_C_COMPILER=${hikari}",
                          "-DCMAKE_CXX_COMPILER=${hikari}++"
                cFlags   "-mllvm -enable-bcfobf -mllvm -enable-cffobf -mllvm -enable-subobf -mllvm -enable-indibran -mllvm -enable-strcry"
                cppFlags "-mllvm -enable-bcfobf -mllvm -enable-cffobf -mllvm -enable-subobf -mllvm -enable-indibran -mllvm -enable-strcry"
            }
        }
    }
}
```

---

## Tuning Options

```bash
# Apply BCF N times (stronger, default 1)
-mllvm -bcf_loop=3

# Percentage of basic blocks to obfuscate (default 30)
-mllvm -bcf_prob=70

# Apply substitution N times (default 1)
-mllvm -sub_loop=2

# AES seed for string encryption
-mllvm -aesSeed=0xDEADBEEF
```

---

## Notes

- Hikari uses LLVM 8 (older toolchain). For NDK C++ projects, link against the NDK's libc++ by
  passing `-I` and `-L` flags pointing to the NDK sysroot.
- For pure C native libraries (JNI), Hikari works out of the box as the compiler.
- For maximum obfuscation combine Hikari (`-enable-indibran -enable-funcwra -enable-bcfobf -enable-cffobf`) with eshard plugin (`-fpass-plugin=.../libLLVMObfuscator.so -mllvm -sobf`).

---

## Binary Location

```
/opt/hikari-build/bin/clang      — C compiler
/opt/hikari-build/bin/clang++    — C++ compiler
/opt/hikari-build/bin/clang-8    — versioned binary
```
