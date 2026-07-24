LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := yuv_camera_static
LOCAL_SRC_FILES := \
    third_party/libyuv/source/convert.cc \
    third_party/libyuv/source/convert_argb.cc \
    third_party/libyuv/source/convert_from.cc \
    third_party/libyuv/source/planar_functions.cc \
    third_party/libyuv/source/rotate.cc \
    third_party/libyuv/source/rotate_argb.cc \
    third_party/libyuv/source/rotate_common.cc \
    third_party/libyuv/source/row_common.cc \
    third_party/libyuv/source/scale_common.cc \
    third_party/libyuv/source/video_common.cc
LOCAL_C_INCLUDES := $(LOCAL_PATH)/third_party/libyuv/include
LOCAL_CPPFLAGS := \
    -std=c++17 \
    -fPIC \
    -ffunction-sections \
    -fdata-sections \
    -DLIBYUV_DISABLE_X86 \
    -DLIBYUV_DISABLE_NEON \
    -DLIBYUV_DISABLE_MSA \
    -DLIBYUV_DISABLE_LSX \
    -DLIBYUV_DISABLE_LASX \
    -DLIBYUV_DISABLE_RVV \
    -DLIBYUV_DISABLE_SME \
    -DLIBYUV_DISABLE_SVE
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := image_processing_util_jni
LOCAL_ALLOW_UNDEFINED_VERSION_SCRIPT_SYMBOLS := true
LOCAL_SRC_FILES := third_party/androidx/camera-core/image_processing_util_jni.cc
LOCAL_C_INCLUDES := $(LOCAL_PATH)/third_party/libyuv/include
LOCAL_CPPFLAGS := \
    -std=c++17 \
    -O3 \
    -flto \
    -fPIC \
    -fno-exceptions \
    -fno-rtti \
    -fomit-frame-pointer \
    -fdata-sections \
    -ffunction-sections
LOCAL_LDFLAGS := \
    -flto \
    -Wl,--gc-sections \
    -Wl,--undefined-version \
    -Wl,--version-script=$(LOCAL_PATH)/third_party/androidx/camera-core/jni.lds \
    -Wl,-z,max-page-size=16384 \
    -Wl,-z,common-page-size=16384
LOCAL_STATIC_LIBRARIES := yuv_camera_static
LOCAL_LDLIBS := -llog -landroid -ljnigraphics
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := surface_util_jni
LOCAL_ALLOW_UNDEFINED_VERSION_SCRIPT_SYMBOLS := true
LOCAL_SRC_FILES := third_party/androidx/camera-core/surface_util_jni.cc
LOCAL_CPPFLAGS := \
    -std=c++17 \
    -O3 \
    -flto \
    -fPIC \
    -fno-exceptions \
    -fno-rtti \
    -fomit-frame-pointer \
    -fdata-sections \
    -ffunction-sections
LOCAL_LDFLAGS := \
    -flto \
    -Wl,--gc-sections \
    -Wl,--undefined-version \
    -Wl,--version-script=$(LOCAL_PATH)/third_party/androidx/camera-core/jni.lds \
    -Wl,-z,max-page-size=16384 \
    -Wl,-z,common-page-size=16384
LOCAL_LDLIBS := -landroid
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := androidx.graphics.path
LOCAL_SRC_FILES := \
    third_party/androidx/graphics-path/Conic.cpp \
    third_party/androidx/graphics-path/PathIterator.cpp \
    third_party/androidx/graphics-path/pathway.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/third_party/androidx/graphics-path
LOCAL_CPPFLAGS := \
    -std=c++17 \
    -Wno-unused-command-line-argument \
    -fno-exceptions \
    -fno-unwind-tables \
    -fno-asynchronous-unwind-tables \
    -fno-rtti \
    -ffast-math \
    -ffp-contract=fast \
    -fvisibility-inlines-hidden \
    -fvisibility=hidden \
    -fomit-frame-pointer \
    -ffunction-sections \
    -fdata-sections
LOCAL_LDFLAGS := \
    -Wl,--hash-style=both \
    -Wl,--gc-sections \
    -Wl,-Bsymbolic-functions \
    -Wl,--version-script=$(LOCAL_PATH)/third_party/androidx/graphics-path/libandroidx.graphics.path.map \
    -Wl,-z,max-page-size=16384 \
    -Wl,-z,common-page-size=16384 \
    -nostdlib++
include $(BUILD_SHARED_LIBRARY)
