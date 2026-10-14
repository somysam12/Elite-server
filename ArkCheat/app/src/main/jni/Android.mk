LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := shaitaan

# ═══════════════════════════════════════════════════════════════════════════════
#  HIKARI OBFUSCATION COMPILER OVERRIDE
#  Uses Hikari LLVM 8 for all compilation passes:
#    IndirectBranch, FunctionWrapper, BogusControlFlow, ControlFlowFlattening,
#    InstructionSubstitution, StringEncryption, BasicBlockSplit,
#    AntiClassDump, FunctionCallObfuscate
# ═══════════════════════════════════════════════════════════════════════════════
HIKARI_PATH := /opt/hikari-build/bin
LOCAL_CC    := $(HIKARI_PATH)/clang
LOCAL_CXX   := $(HIKARI_PATH)/clang++

# Hikari obfuscation passes
HIKARI_FLAGS := \
    -mllvm -enable-bcfobf    \
    -mllvm -enable-cffobf    \
    -mllvm -enable-subobf    \
    -mllvm -enable-indibran  \
    -mllvm -enable-funcwra   \
    -mllvm -enable-strcry    \
    -mllvm -enable-fco       \
    -mllvm -enable-splitobf  \
    -mllvm -enable-acdobf    \
    -mllvm -bcf_loop=2       \
    -mllvm -bcf_prob=60      \
    -mllvm -sub_loop=2

LOCAL_CFLAGS := -Wno-error=format-security -fvisibility=hidden -ffunction-sections -fdata-sections -w
LOCAL_CFLAGS += -fno-rtti -fno-exceptions -fpermissive
LOCAL_CFLAGS += $(HIKARI_FLAGS)

LOCAL_CPPFLAGS += -Wno-error=format-security -fvisibility=hidden -ffunction-sections -fdata-sections -w -s -std=c++17
LOCAL_CPPFLAGS += -Wno-error=c++11-narrowing -fms-extensions -fno-rtti -fno-exceptions -fpermissive
LOCAL_CPPFLAGS += $(HIKARI_FLAGS)

LOCAL_LDFLAGS += -Wl,--gc-sections,--strip-all, -llog
LOCAL_ARM_MODE := arm

LOCAL_C_INCLUDES := $(LOCAL_PATH)/
LOCAL_C_INCLUDES += $(LOCAL_PATH)/includes
LOCAL_C_INCLUDES += $(LOCAL_PATH)/includes/curl/curl-android-$(TARGET_ARCH_ABI)/include
LOCAL_C_INCLUDES += $(LOCAL_PATH)/includes/curl/openssl-android-$(TARGET_ARCH_ABI)/include

CLIENT_SRC_FILES := $(wildcard $(LOCAL_PATH)/*.cpp)

LOCAL_SRC_FILES := $(CLIENT_SRC_FILES:$(LOCAL_PATH)/%=%)

LOCAL_CPP_FEATURES := exceptions
LOCAL_LDLIBS    := -llog -landroid -lz

LOCAL_STATIC_LIBRARIES := libcurl libssl libcrypto

include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libcurl
LOCAL_SRC_FILES := includes/curl/curl-android-$(TARGET_ARCH_ABI)/lib/libcurl.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libssl
LOCAL_SRC_FILES := includes/curl/openssl-android-$(TARGET_ARCH_ABI)/lib/libssl.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libcrypto
LOCAL_SRC_FILES := includes/curl/openssl-android-$(TARGET_ARCH_ABI)/lib/libcrypto.a
include $(PREBUILT_STATIC_LIBRARY)
