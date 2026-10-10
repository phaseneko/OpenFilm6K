LOCAL_PATH := $(call my-dir)

# ---- libjpeg (IJG jpeg-9e, vendored; jpeg-9 merged progressive Huffman into
# jchuff/jdhuff and dropped jidctred — only these files exist) ----
LIBJPEG_SOURCES := \
    jaricom.c jcapimin.c jcapistd.c jcarith.c jccoefct.c jccolor.c jcdctmgr.c \
    jchuff.c jcinit.c jcmainct.c jcmarker.c jcmaster.c jcomapi.c jcparam.c \
    jcprepct.c jcsample.c jctrans.c \
    jdapimin.c jdapistd.c jdarith.c jdatadst.c jdatasrc.c jdcoefct.c jdcolor.c \
    jddctmgr.c jdhuff.c jdinput.c jdmainct.c jdmarker.c jdmaster.c jdmerge.c \
    jdpostct.c jdsample.c jdtrans.c \
    jerror.c jfdctflt.c jfdctfst.c jfdctint.c jidctflt.c jidctfst.c jidctint.c \
    jquant1.c jquant2.c jutils.c jmemmgr.c jmemnobs.c

include $(CLEAR_VARS)
LOCAL_MODULE := of6k
LOCAL_C_INCLUDES := $(LOCAL_PATH)/libjpeg
LOCAL_CFLAGS := -O2 -std=gnu99 -Wno-unused-parameter
LOCAL_SRC_FILES := \
    of6k_engine.c \
    film_jni.c \
    $(addprefix libjpeg/,$(LIBJPEG_SOURCES))
LOCAL_LDLIBS := -llog -lm
include $(BUILD_SHARED_LIBRARY)
