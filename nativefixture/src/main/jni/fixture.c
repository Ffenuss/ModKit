#include <jni.h>

extern int modkit_fixture_value(void);

JNIEXPORT jint JNICALL Java_dev_modkit_nativefixture_GameActivity_readNativeValue(
        JNIEnv* env, jclass type) {
    (void)env;
    (void)type;
    return modkit_fixture_value();
}
