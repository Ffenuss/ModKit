LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := modkit_runtime_probe
LOCAL_SRC_FILES := runtime_native_probe.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)
