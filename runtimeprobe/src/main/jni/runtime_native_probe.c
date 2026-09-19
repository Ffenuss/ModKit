#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <string.h>

static const char* base_name(const char* path) {
    if (path == NULL) return NULL;
    const char* slash = strrchr(path, '/');
    return slash == NULL ? path : slash + 1;
}

static int valid_module(const char* value) {
    if (value == NULL) return 0;
    const size_t length = strlen(value);
    if (length == 0 || length > 255) return 0;
    if (strchr(value, '/') != NULL || strchr(value, '\\') != NULL) return 0;
    if (strstr(value, "..") != NULL) return 0;
    if (length < 3 || strcmp(value + length - 3, ".so") != 0) return 0;
    return 1;
}

static int valid_symbol(const char* value) {
    if (value == NULL) return 0;
    const size_t length = strlen(value);
    if (length == 0 || length > 1024) return 0;
    for (size_t index = 0; index < length; index++) {
        const unsigned char ch = (unsigned char)value[index];
        if (ch <= 0x20 || ch == 0x7f) return 0;
    }
    return 1;
}

JNIEXPORT jlong JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativeResolveLoadedSymbol(
        JNIEnv* env,
        jclass clazz,
        jstring module_name,
        jstring symbol_name) {
    (void)clazz;
    if (module_name == NULL || symbol_name == NULL) return 0;

    const char* module = (*env)->GetStringUTFChars(env, module_name, NULL);
    if (module == NULL) return 0;
    const char* symbol = (*env)->GetStringUTFChars(env, symbol_name, NULL);
    if (symbol == NULL) {
        (*env)->ReleaseStringUTFChars(env, module_name, module);
        return 0;
    }

    jlong result = 0;
    if (valid_module(module) && valid_symbol(symbol)) {
        dlerror();
        void* handle = dlopen(module, RTLD_NOW | RTLD_NOLOAD);
        if (handle != NULL) {
            dlerror();
            void* address = dlsym(handle, symbol);
            const char* lookup_error = dlerror();
            if (lookup_error == NULL && address != NULL) {
                Dl_info info;
                memset(&info, 0, sizeof(info));
                if (dladdr(address, &info) != 0) {
                    const char* actual = base_name(info.dli_fname);
                    if (actual != NULL && strcmp(actual, module) == 0) {
                        result = (jlong)(uintptr_t)address;
                    }
                }
            }
            dlclose(handle);
        }
    }

    (*env)->ReleaseStringUTFChars(env, symbol_name, symbol);
    (*env)->ReleaseStringUTFChars(env, module_name, module);
    return result;
}
