LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := ctsshare
LOCAL_SRC_FILES := main.cpp
LOCAL_CPPFLAGS := -std=c++17 -fno-exceptions -fno-rtti -fno-threadsafe-statics -fvisibility=hidden
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
