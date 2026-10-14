#!/bin/bash
# NDK_CCACHE wrapper: applies VM obfuscation then routes through Hikari OLLVM.
#
# For source files containing __attribute__((annotate("vmobf"))):
#   1. Emit LLVM IR via NDK clang (arm64 target, O0 so VM sees multi-block CFG)
#   2. Apply VM obfuscation pass via system opt (LLVM 14)
#   3. Compile obfuscated IR → object via NDK clang (O2 so dispatch loop is tight)
#   NOTE: Hikari is skipped for VM files — the VM obfuscation itself is sufficient.
#
# For all other source files:
#   Pass straight through to Hikari with all BCF/CFF/SUB flags.
#
# $1 = original NDK clang binary (used for IR emit and final object compile)
# $2+ = original clang arguments from ndk-build

NDK_CLANG="$1"
shift  # drop NDK_CCACHE-prepended compiler arg

HIKARI=/opt/hikari-build/bin/clang++
OPT=/usr/lib/llvm-14/bin/opt
VM_PLUGIN=/opt/libVmObfPass.so

# Use clang (not clang++) for C files
if [[ "$*" == *"-std=c"* ]] && [[ "$*" != *"-std=c++"* ]]; then
  HIKARI=/opt/hikari-build/bin/clang
fi

# ── Detect if this is a compilation step with a source file ──────────────────
IS_COMPILE=0
SRC_FILE=""
OUT_OBJ=""
PREV=""
for arg in "$@"; do
  if [[ "$PREV" == "-o" ]]; then
    OUT_OBJ="$arg"
  fi
  if [[ "$arg" == "-c" ]]; then
    IS_COMPILE=1
  fi
  if [[ "$arg" == *.cpp ]] || [[ "$arg" == *.c ]] || [[ "$arg" == *.cc ]]; then
    SRC_FILE="$arg"
  fi
  PREV="$arg"
done

# ── Check if source contains vmobf annotation (fast grep) ────────────────────
if [[ $IS_COMPILE -eq 1 ]] && [[ -n "$SRC_FILE" ]] && [[ -n "$OUT_OBJ" ]] \
   && [[ -f "$SRC_FILE" ]] && [[ -f "$VM_PLUGIN" ]] && [[ -f "$OPT" ]] \
   && grep -qF 'vmobf' "$SRC_FILE" 2>/dev/null; then

  IR_TMP=$(mktemp /tmp/vmobf_XXXXXX.ll)
  IR_VM=$(mktemp /tmp/vmobf_vm_XXXXXX.ll)

  # Build IR-emit args: same flags but -S -emit-llvm -O0, no -c, different -o
  # Strip all -mllvm <hikari-flag> pairs — NDK clang doesn't support them
  HIKARI_OPT_PATTERN="^-(enable-bcfobf|enable-cffobf|enable-subobf|enable-indibran|bcf_loop|bcf_prob|sub_loop)"
  EMIT_ARGS=()
  SKIP_NEXT=0
  SKIP_MLLVM=0
  for arg in "$@"; do
    if [[ $SKIP_NEXT -eq 1 ]]; then SKIP_NEXT=0; continue; fi
    if [[ $SKIP_MLLVM -eq 1 ]]; then
      SKIP_MLLVM=0
      [[ "$arg" =~ $HIKARI_OPT_PATTERN ]] && continue
      EMIT_ARGS+=("-mllvm" "$arg"); continue
    fi
    if [[ "$arg" == "-mllvm" ]]; then SKIP_MLLVM=1; continue; fi
    if [[ "$arg" == "-c" ]]; then continue; fi
    if [[ "$arg" == "-o" ]]; then
      EMIT_ARGS+=("-S" "-emit-llvm" "-o" "$IR_TMP")
      SKIP_NEXT=1; continue
    fi
    if [[ "$arg" == "-O"* ]]; then continue; fi
    EMIT_ARGS+=("$arg")
  done
  EMIT_ARGS+=("-O0")

  "$NDK_CLANG" "${EMIT_ARGS[@]}" 2>/dev/null
  EMIT_EXIT=$?

  if [[ $EMIT_EXIT -eq 0 ]] && [[ -s "$IR_TMP" ]]; then
    "$OPT" -load-pass-plugin="$VM_PLUGIN" \
           -passes="function(vm-obf)" \
           -S "$IR_TMP" -o "$IR_VM" 2>/dev/null
    OPT_EXIT=$?

    if [[ $OPT_EXIT -eq 0 ]] && [[ -s "$IR_VM" ]]; then
      # Compile VM-obfuscated IR → object with NDK clang at O2
      # Keep original flags (target triple, includes, defines) but swap source
      FINAL_ARGS=()
      SKIP_NEXT=0
      SKIP_MLLVM=0
      for arg in "$@"; do
        if [[ $SKIP_NEXT -eq 1 ]]; then SKIP_NEXT=0; continue; fi
        if [[ $SKIP_MLLVM -eq 1 ]]; then
          SKIP_MLLVM=0
          [[ "$arg" =~ $HIKARI_OPT_PATTERN ]] && continue
          FINAL_ARGS+=("-mllvm" "$arg"); continue
        fi
        if [[ "$arg" == "-mllvm" ]]; then SKIP_MLLVM=1; continue; fi
        if [[ "$arg" == "-o" ]]; then
          FINAL_ARGS+=("-o"); SKIP_NEXT=1; FINAL_ARGS+=("$OUT_OBJ"); continue
        fi
        if [[ "$arg" == *.cpp ]] || [[ "$arg" == *.c ]] || [[ "$arg" == *.cc ]]; then continue; fi
        if [[ "$arg" == "-O"* ]]; then continue; fi
        if [[ "$arg" == "-c" ]]; then continue; fi
        FINAL_ARGS+=("$arg")
      done
      FINAL_ARGS+=("-O2" "-c" "-x" "ir" "$IR_VM")

      "$NDK_CLANG" "${FINAL_ARGS[@]}"
      FINAL_EXIT=$?
      rm -f "$IR_TMP" "$IR_VM"
      exit $FINAL_EXIT
    fi
  fi

  rm -f "$IR_TMP" "$IR_VM"
  # Fall through to Hikari on any failure
fi

# ── Normal path: straight to Hikari with all OLLVM flags ─────────────────────
exec "$HIKARI" "$@"
