LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := Unreal
LOCAL_SRC_FILES := unreal.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
