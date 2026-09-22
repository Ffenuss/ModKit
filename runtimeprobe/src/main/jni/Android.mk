LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := modkit_runtime_probe
LOCAL_SRC_FILES := runtime_native_probe.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
include $(CLEAR_VARS)
LOCAL_MODULE := modkit_root_memory_watch
LOCAL_SRC_FILES := root_memory_watch.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
include $(BUILD_EXECUTABLE)
endif


include $(CLEAR_VARS)
LOCAL_MODULE := modkit_root_fast_value_scan
LOCAL_SRC_FILES := root_fast_value_scan.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
include $(BUILD_EXECUTABLE)
