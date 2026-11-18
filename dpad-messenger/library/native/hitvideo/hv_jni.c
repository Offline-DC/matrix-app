/*
 * JNI surface of libhitvideo.so for com.offline.dpadmessenger.ui.util.HitVideoNative.
 */
#include <jni.h>
#include <stdint.h>
#include "hv_core.h"

#define FN(name) Java_com_offline_dpadmessenger_ui_util_HitVideoNative_##name

JNIEXPORT jlong JNICALL FN(open)(JNIEnv *env, jclass cls, jstring jpath, jint threads, jboolean fast, jobjectArray errOut) {
    (void) cls;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return 0;
    char err[256] = {0};
    HV *hv = hv_open(path, threads, fast ? 1 : 0, err, sizeof(err));
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (!hv && errOut && (*env)->GetArrayLength(env, errOut) > 0) {
        jstring js = (*env)->NewStringUTF(env, err);
        (*env)->SetObjectArrayElement(env, errOut, 0, js);
    }
    return (jlong) (intptr_t) hv;
}

JNIEXPORT jintArray JNICALL FN(info)(JNIEnv *env, jclass cls, jlong h) {
    (void) cls;
    int out[HV_INFO_LEN];
    hv_info((HV *) (intptr_t) h, out);
    jintArray arr = (*env)->NewIntArray(env, HV_INFO_LEN);
    if (arr) (*env)->SetIntArrayRegion(env, arr, 0, HV_INFO_LEN, (const jint *) out);
    return arr;
}

JNIEXPORT jboolean JNICALL FN(setOutput)(JNIEnv *env, jclass cls, jlong h, jint w, jint hgt) {
    (void) env; (void) cls;
    return hv_set_output((HV *) (intptr_t) h, w, hgt) == 0 ? JNI_TRUE : JNI_FALSE;
}

/* Writes one I420 frame into the direct buffer. Returns its pts in microseconds,
 * -1 at the end of the video, -2 on error. */
JNIEXPORT jlong JNICALL FN(nextFrame)(JNIEnv *env, jclass cls, jlong h, jobject buf) {
    (void) cls;
    uint8_t *dst = (uint8_t *) (*env)->GetDirectBufferAddress(env, buf);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buf);
    if (!dst || cap <= 0) return -2;
    int64_t pts = 0;
    int r = hv_next((HV *) (intptr_t) h, dst, (int) cap, &pts);
    if (r > 0) return (jlong) pts;
    return r == 0 ? -1 : -2;
}

JNIEXPORT void JNICALL FN(close)(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    hv_close((HV *) (intptr_t) h);
}
