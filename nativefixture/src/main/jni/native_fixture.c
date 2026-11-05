#include <jni.h>

/*
 * A small, deterministic machine-code fixture for testing the existing
 * exact-bytes native patch engine. All versions return two movement units.
 * The native function is deliberately not a controller / ModKit adapter.
 */
#if defined(__x86_64__) || defined(__i386__)
__attribute__((naked, noinline, used, visibility("default")))
int native_fixture_step(void) {
    __asm__ volatile ("movl $2, %eax\n\tret");
}
#elif defined(__aarch64__)
__attribute__((naked, noinline, used, visibility("default")))
int native_fixture_step(void) {
    __asm__ volatile ("mov w0, #2\n\tret");
}
#elif defined(__arm__)
__attribute__((naked, noinline, used, visibility("default")))
int native_fixture_step(void) {
    __asm__ volatile ("mov r0, #2\n\tbx lr");
}
#else
#error "Unsupported native fixture architecture"
#endif

JNIEXPORT jint JNICALL
Java_dev_modkit_nativefixture_NativeGameActivity_nativeStep(
        JNIEnv* env, jobject self) {
    (void)env;
    (void)self;
    return (jint)native_fixture_step();
}
