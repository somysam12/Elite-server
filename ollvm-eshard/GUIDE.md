# eshard/obfuscator-llvm — Build & Usage Guide

LLVM pass plugin providing Bogus Control Flow, Control Flow Flattening, Instruction Substitution, String Encryption, and Basic Block Splitting. Works as a plugin with any compatible clang (LLVM 14+).

---

## 1. Prerequisites

```bash
sudo apt-get install -y llvm-14-dev clang-14 cmake ninja-build xxd
```

---

## 2. Build the `.so` Plugin

```bash
cd ollvm-eshard
mkdir build && cd build

cmake .. \
  -GNinja \
  -DLLVM_DIR=/usr/lib/llvm-14/lib/cmake/llvm \
  -DCLANG_EXECUTABLE_PATH=/usr/bin/clang-14 \
  -DCMAKE_BUILD_TYPE=Release

ninja -j$(nproc)
```

Output: `build/libLLVMObfuscator.so`

---

## 3. Available Passes

| Flag | Pass |
|------|------|
| `-mllvm -bcf` | Bogus Control Flow — inserts fake branches to confuse decompilers |
| `-mllvm -fla` | Control Flow Flattening — flattens function structure into a dispatcher loop |
| `-mllvm -sub` | Instruction Substitution — replaces standard arithmetic with equivalent complex ops |
| `-mllvm -sobf` | String Obfuscation — encrypts string literals, decrypts at runtime |
| `-mllvm -split` | Basic Block Splitting — splits blocks to increase complexity |

Intensity tuning (append after the flag):
- `-mllvm -bcf_loop=3` — apply BCF N times (default 1)
- `-mllvm -bcf_prob=70` — percentage of blocks to obfuscate (default 30)
- `-mllvm -sub_loop=3` — apply substitution N times

---

## 4. Usage with Clang Directly

```bash
clang-14 \
  -fpass-plugin=/path/to/build/libLLVMObfuscator.so \
  -mllvm -bcf \
  -mllvm -fla \
  -mllvm -sub \
  -mllvm -sobf \
  your_source.c -o output
```

---

## 5. Usage with Android NDK (CMakeLists.txt)

```cmake
set(OBFUSCATOR_SO "/path/to/ollvm-eshard/build/libLLVMObfuscator.so")

add_compile_options(
  "-fpass-plugin=${OBFUSCATOR_SO}"
  "-mllvm" "-bcf"
  "-mllvm" "-fla"
  "-mllvm" "-sub"
  "-mllvm" "-sobf"
)
```

---

## 6. Usage with Android Gradle (build.gradle)

```groovy
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                // Replace path with actual location of the .so
                def obf = "/path/to/ollvm-eshard/build/libLLVMObfuscator.so"
                cppFlags "-fpass-plugin=${obf} -mllvm -bcf -mllvm -fla -mllvm -sub -mllvm -sobf"
                cFlags   "-fpass-plugin=${obf} -mllvm -bcf -mllvm -fla -mllvm -sub -mllvm -sobf"
            }
        }
    }
}
```

---

## 7. Per-Function Annotations (selective obfuscation)

Apply obfuscation only to specific functions using `__attribute__((annotate(...)))`:

```c
// Enable BCF on this function only
void __attribute__((annotate("bcf"))) sensitive_function() { ... }

// Disable FLA on this function even when -fla flag is set globally
void __attribute__((annotate("nofla"))) fast_function() { ... }
```

Annotation strings match the flag names: `bcf`, `fla`, `sub`, `sobf`, `split`.

---

## 8. LLVM 14 Patches Applied

Two LLVM 14 API breaking changes were patched from the upstream source:

1. **`utils/Utils.cpp` line 53** — `DemotePHIToStack` no longer accepts an iterator; pass the terminator pointer directly.
2. **`bogus/BogusControlFlow.cpp` lines 311 & 347** — `FCmpInst` constructor changed from `BasicBlock*` to `BasicBlock&`; dereferenced the pointer with `*`.
