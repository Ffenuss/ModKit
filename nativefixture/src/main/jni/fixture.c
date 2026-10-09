#include <jni.h>

extern int modkit_fixture_value(void);

JNIEXPORT jint JNICALL Java_dev_modkit_nativefixture_GameActivity_readNativeValue(
        JNIEnv* env, jclass type) {
    (void)env;
    (void)type;
    return modkit_fixture_value();
}

#if !defined(__aarch64__) && !defined(__x86_64__)
JNIEXPORT jlong JNICALL Java_dev_modkit_nativefixture_GameActivity_getStamina(JNIEnv* env, jclass type) {
    (void)env; (void)type; return 11;
}
JNIEXPORT jdouble JNICALL Java_dev_modkit_nativefixture_GameActivity_getMoveSpeed(JNIEnv* env, jclass type) {
    (void)env; (void)type; return 1.0;
}
JNIEXPORT jint JNICALL Java_dev_modkit_nativefixture_GameActivity_getAmmo__I(JNIEnv* env, jclass type, jint slot) {
    (void)env; (void)type; return 10 + slot;
}
JNIEXPORT jint JNICALL Java_dev_modkit_nativefixture_GameActivity_getAmmo__J(JNIEnv* env, jclass type, jlong slot) {
    (void)env; (void)type; return 20 + (jint)slot;
}
#endif

JNIEXPORT jint JNICALL Java_dev_modkit_nativefixture_GameActivity_getBullets(JNIEnv* env, jclass type, jstring slot) {
    (void)env; (void)type; (void)slot; return 13;
}
