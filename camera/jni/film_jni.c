#include <jni.h>
#include <stdint.h>
#include "of6k_engine.h"

static jlong nativeLoad(JNIEnv *env, jclass cls, jstring flmPath, jstring texDir) {
    (void)cls;
    const char *p = (*env)->GetStringUTFChars(env, flmPath, NULL);
    const char *t = (*env)->GetStringUTFChars(env, texDir, NULL);
    struct Of6kFilm *m = of6k_load_film(p, t);
    (*env)->ReleaseStringUTFChars(env, flmPath, p);
    (*env)->ReleaseStringUTFChars(env, texDir, t);
    return (jlong)(intptr_t)m;
}

static void nativeFree(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    of6k_free_film((struct Of6kFilm *)(intptr_t)h);
}

static jstring nativeName(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    return (*env)->NewStringUTF(env, of6k_film_name((struct Of6kFilm *)(intptr_t)h));
}

static jint nativeProcess(JNIEnv *env, jclass cls, jlong h, jstring in, jstring out,
                          jint scale, jint quality) {
    (void)cls;
    const char *pi = (*env)->GetStringUTFChars(env, in, NULL);
    const char *po = (*env)->GetStringUTFChars(env, out, NULL);
    int r = of6k_process((struct Of6kFilm *)(intptr_t)h, pi, po, scale, quality);
    (*env)->ReleaseStringUTFChars(env, in, pi);
    (*env)->ReleaseStringUTFChars(env, out, po);
    return r;
}

static JNINativeMethod kMethods[] = {
    {"nativeLoad",    "(Ljava/lang/String;Ljava/lang/String;)J",      nativeLoad},
    {"nativeFree",    "(J)V",                                        nativeFree},
    {"nativeName",    "(J)Ljava/lang/String;",                       nativeName},
    {"nativeProcess", "(JLjava/lang/String;Ljava/lang/String;II)I",  nativeProcess},
};

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_4) != JNI_OK) return -1;
    jclass c = (*env)->FindClass(env, "com/openfilm6k/camera/FilmLib");
    if (!c) return -1;
    if ((*env)->RegisterNatives(env, c, kMethods,
            sizeof kMethods / sizeof kMethods[0])) return -1;
    return JNI_VERSION_1_4;
}
