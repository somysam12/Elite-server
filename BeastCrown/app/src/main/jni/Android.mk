LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := shaitaan

# ═══════════════════════════════════════════════════════════════════════════════
#  HIKARI OBFUSCATION — all compilation routed through Hikari LLVM 8 via
#  NDK_CCACHE=/opt/hikari-wrapper.sh set in Application.mk
#    BogusControlFlow, ControlFlowFlattening, InstructionSubstitution,
#    IndirectBranch, StringEncryption, BasicBlockSplit,
#    AntiClassDump, FunctionCallObfuscate, FunctionWrapper
# ═══════════════════════════════════════════════════════════════════════════════

HIKARI_FLAGS := \
    -mllvm -enable-bcfobf    \
    -mllvm -enable-cffobf    \
    -mllvm -enable-subobf    \
    -mllvm -enable-indibran  \
    -mllvm -enable-strcry    \
    -mllvm -enable-splitobf  \
    -mllvm -bcf_loop=1       \
    -mllvm -bcf_prob=30      \
    -mllvm -sub_loop=1

LOCAL_CFLAGS := -Wno-error=format-security -fvisibility=hidden -ffunction-sections -fdata-sections -w
LOCAL_CFLAGS += -fno-rtti -fno-exceptions -fpermissive

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
