LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := modkit_native_fixture
LOCAL_SRC_FILES := native_fixture.c
LOCAL_CFLAGS := -fno-lto
include $(BUILD_SHARED_LIBRARY)
