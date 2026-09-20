#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define MAX_PATCHED_SLOTS 8192
#define MAX_JNI_ONLOAD_DLSYM_SLOTS 16
#define MAX_JNI_ONLOAD_WRAPPERS 32
#define MAX_REGISTER_NATIVE_METHODS 4096
#define SCAN_INTERVAL_US 250000

typedef void* (*dlsym_fn)(void*, const char*);
typedef jint (JNICALL *register_natives_fn)(
        JNIEnv*,
        jclass,
        const JNINativeMethod*,
        jint);
typedef jint (JNICALL *jni_onload_fn)(JavaVM*, void*);

typedef struct {
    void** slot;
    void* original;
    int original_prot;
} patched_slot;

typedef struct {
    jni_onload_fn original;
    void* address;
    char module[256];
    unsigned long generation;
    int in_use;
} jni_onload_pending_slot;

static JavaVM* g_vm = NULL;
static jclass g_trace_buffer_class = NULL;
static jmethodID g_append_dlsym = NULL;
static jmethodID g_append_register_native_class = NULL;

static pthread_mutex_t g_hook_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_code_patch_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_scan_thread;
static atomic_int g_trace_active = 0;
static atomic_int g_scan_thread_started = 0;
static atomic_int g_inflight = 0;
static atomic_int g_incomplete = 0;
static atomic_int g_restore_failed = 0;
static patched_slot g_slots[MAX_PATCHED_SLOTS];
static size_t g_slot_count = 0;
static _Atomic(dlsym_fn) g_real_dlsym = NULL;
static _Thread_local int g_in_dlsym_wrapper = 0;

static pthread_mutex_t g_jni_hook_lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_int g_jni_trace_active = 0;
static atomic_int g_jni_inflight = 0;
static atomic_int g_jni_incomplete = 0;
static atomic_int g_jni_restore_failed = 0;
static atomic_ulong g_jni_generation = 0;
static patched_slot g_jni_onload_slots[MAX_JNI_ONLOAD_DLSYM_SLOTS];
static size_t g_jni_onload_slot_count = 0;
static register_natives_fn* g_register_natives_slot = NULL;
static _Atomic(register_natives_fn) g_real_register_natives = NULL;
static int g_register_natives_original_prot = 0;
static _Thread_local int g_in_art_dlsym_wrapper = 0;
static _Thread_local int g_in_register_natives_wrapper = 0;
static pthread_mutex_t g_jni_onload_wrapper_lock =
        PTHREAD_MUTEX_INITIALIZER;
static jni_onload_pending_slot
        g_jni_onload_pending[MAX_JNI_ONLOAD_WRAPPERS];

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

static int app_owned_path(const char* path) {
    if (path == NULL || path[0] == '\0') return 0;
    const char* name = base_name(path);
    if (name != NULL &&
            strcmp(name, "libmodkit_runtime_probe.so") == 0) {
        return 0;
    }
    return strncmp(path, "/data/app/", 10) == 0 ||
            strncmp(path, "/data/user/", 11) == 0 ||
            strncmp(path, "/data/data/", 11) == 0;
}

static int art_runtime_path(const char* path) {
    if (path == NULL || path[0] == '\0') return 0;
    const char* name = base_name(path);
    if (name == NULL || strcmp(name, "libart.so") != 0) return 0;
    return strncmp(path, "/apex/", 6) == 0 ||
            strncmp(path, "/system/", 8) == 0 ||
            strncmp(path, "/system_ext/", 12) == 0;
}

static uintptr_t runtime_address(uintptr_t base, uintptr_t value) {
    if (base == 0 || value >= base) return value;
    if (value > UINTPTR_MAX - base) return 0;
    return base + value;
}

static int is_jump_slot_relocation(uint64_t info) {
#if defined(__aarch64__)
    return ELF64_R_TYPE(info) == R_AARCH64_JUMP_SLOT;
#elif defined(__arm__)
    return ELF32_R_TYPE((uint32_t)info) == R_ARM_JUMP_SLOT;
#elif defined(__x86_64__)
    return ELF64_R_TYPE(info) == R_X86_64_JUMP_SLOT;
#elif defined(__i386__)
    return ELF32_R_TYPE((uint32_t)info) == R_386_JMP_SLOT;
#else
    return 0;
#endif
}

static int query_protection(void* address) {
    FILE* maps = fopen("/proc/self/maps", "r");
    if (maps == NULL) return 0;

    const uintptr_t target = (uintptr_t)address;
    char line[1024];
    int result = 0;
    while (fgets(line, sizeof(line), maps) != NULL) {
        unsigned long start = 0;
        unsigned long end = 0;
        char perms[5] = {0};
        if (sscanf(line, "%lx-%lx %4s", &start, &end, perms) != 3) {
            continue;
        }
        if (target < (uintptr_t)start || target >= (uintptr_t)end) {
            continue;
        }
        if (perms[0] == 'r') result |= PROT_READ;
        if (perms[1] == 'w') result |= PROT_WRITE;
        if (perms[2] == 'x') result |= PROT_EXEC;
        break;
    }
    fclose(maps);
    return result;
}

static int change_page_protection(void* address, int prot) {
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return 0;
    uintptr_t page = (uintptr_t)address &
            ~((uintptr_t)page_size - 1u);
    return mprotect((void*)page, (size_t)page_size, prot) == 0;
}

typedef struct {
    const char* module_name;
    uintptr_t binary_virtual_address;
    size_t length;
    void* address;
    int found;
} code_patch_lookup;

static int locate_code_patch_target(
        struct dl_phdr_info* info,
        size_t size,
        void* data) {
    (void)size;
    code_patch_lookup* lookup = (code_patch_lookup*)data;
    if (lookup == NULL || info == NULL) return 0;

    const char* actual = base_name(info->dlpi_name);
    if (actual == NULL ||
            strcmp(actual, lookup->module_name) != 0) {
        return 0;
    }

    const uintptr_t base = (uintptr_t)info->dlpi_addr;
    if (lookup->binary_virtual_address > UINTPTR_MAX - base) {
        return 1;
    }
    const uintptr_t target =
            base + lookup->binary_virtual_address;
    if (lookup->length == 0 ||
            lookup->length - 1 > UINTPTR_MAX - target) {
        return 1;
    }
    const uintptr_t last =
            target + lookup->length - 1;

    for (ElfW(Half) index = 0;
            index < info->dlpi_phnum;
            index++) {
        const ElfW(Phdr)* phdr =
                &info->dlpi_phdr[index];
        if (phdr->p_type != PT_LOAD ||
                (phdr->p_flags & PF_X) == 0) {
            continue;
        }
        if ((uintptr_t)phdr->p_vaddr > UINTPTR_MAX - base) {
            continue;
        }
        const uintptr_t start =
                base + (uintptr_t)phdr->p_vaddr;
        if ((uintptr_t)phdr->p_memsz > UINTPTR_MAX - start) {
            continue;
        }
        const uintptr_t end =
                start + (uintptr_t)phdr->p_memsz;
        if (target >= start &&
                target < end &&
                last >= target &&
                last < end) {
            lookup->address = (void*)target;
            lookup->found = 1;
            return 1;
        }
    }
    return 1;
}

static int change_code_range_protection(
        void* address,
        size_t length,
        int prot) {
    if (address == NULL || length == 0) return 0;
    const long raw_page_size = sysconf(_SC_PAGESIZE);
    if (raw_page_size <= 0) return 0;
    const uintptr_t page_size =
            (uintptr_t)raw_page_size;
    const uintptr_t mask = page_size - 1u;
    const uintptr_t start =
            (uintptr_t)address & ~mask;
    const uintptr_t last_byte =
            (uintptr_t)address + length - 1u;
    if (last_byte < (uintptr_t)address) return 0;
    const uintptr_t end = last_byte & ~mask;

    for (uintptr_t page = start; ; page += page_size) {
        if (mprotect(
                (void*)page,
                (size_t)page_size,
                prot) != 0) {
            return 0;
        }
        if (page == end) break;
        if (page > UINTPTR_MAX - page_size) return 0;
    }
    return 1;
}

static int slot_already_patched(void** slot) {
    for (size_t index = 0; index < g_slot_count; index++) {
        if (g_slots[index].slot == slot) return 1;
    }
    return 0;
}

static void* wrapper_pointer(void);
static void* art_dlsym_wrapper_pointer(void);
static jint invoke_jni_onload_slot(
        size_t slot_index,
        JavaVM* vm,
        void* reserved);
static jint JNICALL modkit_trace_register_natives(
        JNIEnv* env,
        jclass clazz,
        const JNINativeMethod* methods,
        jint method_count);

static int ensure_real_dlsym(void) {
    if (atomic_load(&g_real_dlsym) != NULL) return 1;

    union {
        void* object;
        dlsym_fn function;
    } conversion;
    conversion.object = dlsym(RTLD_DEFAULT, "dlsym");
    if (conversion.function == NULL) return 0;

    atomic_store(&g_real_dlsym, conversion.function);
    return 1;
}

static int patch_slot(void** slot) {
    if (slot == NULL) {
        atomic_store(&g_incomplete, 1);
        return 0;
    }
    if (slot_already_patched(slot)) return 1;
    if (g_slot_count >= MAX_PATCHED_SLOTS) {
        atomic_store(&g_incomplete, 1);
        return 0;
    }

    void* original = __atomic_load_n(slot, __ATOMIC_SEQ_CST);
    if (original == NULL || original == wrapper_pointer()) {
        atomic_store(&g_incomplete, 1);
        return 0;
    }

    const int original_prot = query_protection(slot);
    if (original_prot == 0) {
        atomic_store(&g_incomplete, 1);
        return 0;
    }

    int write_prot = original_prot | PROT_WRITE;
    if (!change_page_protection(slot, write_prot)) {
        atomic_store(&g_incomplete, 1);
        return 0;
    }

    __atomic_store_n(
            slot,
            wrapper_pointer(),
            __ATOMIC_SEQ_CST);

    if (!change_page_protection(slot, original_prot)) {
        __atomic_store_n(slot, original, __ATOMIC_SEQ_CST);
        atomic_store(&g_incomplete, 1);
        if (!change_page_protection(slot, original_prot)) {
            atomic_store(&g_restore_failed, 1);
            atomic_store(&g_trace_active, 0);
        }
        return 0;
    }

    g_slots[g_slot_count].slot = slot;
    g_slots[g_slot_count].original = original;
    g_slots[g_slot_count].original_prot = original_prot;
    g_slot_count++;
    return 1;
}

static int jni_onload_slot_already_patched(void** slot) {
    for (size_t index = 0; index < g_jni_onload_slot_count; index++) {
        if (g_jni_onload_slots[index].slot == slot) return 1;
    }
    return 0;
}

static int patch_jni_onload_dlsym_slot(void** slot) {
    if (slot == NULL) {
        atomic_store(&g_jni_incomplete, 1);
        return 0;
    }
    if (jni_onload_slot_already_patched(slot)) return 1;
    if (g_jni_onload_slot_count >= MAX_JNI_ONLOAD_DLSYM_SLOTS) {
        atomic_store(&g_jni_incomplete, 1);
        return 0;
    }

    void* original = __atomic_load_n(slot, __ATOMIC_SEQ_CST);
    if (original == NULL || original == art_dlsym_wrapper_pointer()) {
        atomic_store(&g_jni_incomplete, 1);
        return 0;
    }

    const int original_prot = query_protection(slot);
    if (original_prot == 0 ||
            !change_page_protection(
                    slot,
                    original_prot | PROT_WRITE)) {
        atomic_store(&g_jni_incomplete, 1);
        return 0;
    }

    __atomic_store_n(
            slot,
            art_dlsym_wrapper_pointer(),
            __ATOMIC_SEQ_CST);
    if (!change_page_protection(slot, original_prot)) {
        __atomic_store_n(slot, original, __ATOMIC_SEQ_CST);
        atomic_store(&g_jni_incomplete, 1);
        if (!change_page_protection(slot, original_prot)) {
            atomic_store(&g_jni_restore_failed, 1);
        }
        return 0;
    }

    g_jni_onload_slots[g_jni_onload_slot_count].slot = slot;
    g_jni_onload_slots[g_jni_onload_slot_count].original = original;
    g_jni_onload_slots[g_jni_onload_slot_count].original_prot =
            original_prot;
    g_jni_onload_slot_count++;
    return 1;
}

static int patch_register_natives_slot(JNIEnv* env) {
    if (env == NULL || *env == NULL) {
        atomic_store(&g_jni_incomplete, 1);
        return 0;
    }

    struct JNINativeInterface* table =
            (struct JNINativeInterface*)(uintptr_t)(*env);
    register_natives_fn* slot = &table->RegisterNatives;
    register_natives_fn original =
            __atomic_load_n(slot, __ATOMIC_SEQ_CST);
    if (original == NULL ||
            original == modkit_trace_register_natives) {
        atomic_store(&g_jni_incomplete, 1);
        return 0;
    }

    const int original_prot = query_protection((void*)slot);
    if (original_prot == 0 ||
            !change_page_protection(
                    (void*)slot,
                    original_prot | PROT_WRITE)) {
        atomic_store(&g_jni_incomplete, 1);
        return 0;
    }

    __atomic_store_n(
            slot,
            modkit_trace_register_natives,
            __ATOMIC_SEQ_CST);
    if (!change_page_protection((void*)slot, original_prot)) {
        __atomic_store_n(slot, original, __ATOMIC_SEQ_CST);
        atomic_store(&g_jni_incomplete, 1);
        if (!change_page_protection((void*)slot, original_prot)) {
            atomic_store(&g_jni_restore_failed, 1);
        }
        return 0;
    }

    g_register_natives_slot = slot;
    atomic_store(&g_real_register_natives, original);
    g_register_natives_original_prot = original_prot;
    return 1;
}

static int patch_relocations(
        uintptr_t base,
        const ElfW(Sym)* symtab,
        const char* strtab,
        size_t strsz,
        const void* relocations,
        size_t relocation_bytes,
        int use_rela) {
    if (symtab == NULL || strtab == NULL || relocations == NULL) return 0;
    const size_t relocation_size =
            use_rela ? sizeof(ElfW(Rela)) : sizeof(ElfW(Rel));
    if (relocation_size == 0 ||
            relocation_bytes % relocation_size != 0) {
        atomic_store(&g_incomplete, 1);
        return 0;
    }

    int patched = 0;

    if (use_rela) {
        const size_t count = relocation_bytes / sizeof(ElfW(Rela));
        const ElfW(Rela)* entries = (const ElfW(Rela)*)relocations;
        for (size_t index = 0; index < count; index++) {
            if (!is_jump_slot_relocation(
                    (uint64_t)entries[index].r_info)) {
                continue;
            }
#if defined(__LP64__)
            const size_t symbol_index = ELF64_R_SYM(entries[index].r_info);
#else
            const size_t symbol_index = ELF32_R_SYM(entries[index].r_info);
#endif
            const ElfW(Sym)* symbol = &symtab[symbol_index];
            if ((size_t)symbol->st_name >= strsz) continue;
            const char* name = strtab + symbol->st_name;
            if (strcmp(name, "dlsym") != 0) continue;
            void** slot = (void**)runtime_address(
                    base,
                    (uintptr_t)entries[index].r_offset);
            if (patch_slot(slot)) patched++;
        }
    } else {
        const size_t count = relocation_bytes / sizeof(ElfW(Rel));
        const ElfW(Rel)* entries = (const ElfW(Rel)*)relocations;
        for (size_t index = 0; index < count; index++) {
            if (!is_jump_slot_relocation(
                    (uint64_t)entries[index].r_info)) {
                continue;
            }
#if defined(__LP64__)
            const size_t symbol_index = ELF64_R_SYM(entries[index].r_info);
#else
            const size_t symbol_index = ELF32_R_SYM(entries[index].r_info);
#endif
            const ElfW(Sym)* symbol = &symtab[symbol_index];
            if ((size_t)symbol->st_name >= strsz) continue;
            const char* name = strtab + symbol->st_name;
            if (strcmp(name, "dlsym") != 0) continue;
            void** slot = (void**)runtime_address(
                    base,
                    (uintptr_t)entries[index].r_offset);
            if (patch_slot(slot)) patched++;
        }
    }

    return patched;
}

static int patch_module(
        struct dl_phdr_info* info,
        size_t size,
        void* data) {
    (void)size;
    (void)data;
    if (!atomic_load(&g_trace_active)) return 1;
    if (info == NULL || !app_owned_path(info->dlpi_name)) return 0;

    const uintptr_t base = (uintptr_t)info->dlpi_addr;
    const ElfW(Dyn)* dynamic = NULL;
    size_t dynamic_count = 0;

    for (ElfW(Half) index = 0; index < info->dlpi_phnum; index++) {
        const ElfW(Phdr)* phdr = &info->dlpi_phdr[index];
        if (phdr->p_type != PT_DYNAMIC) continue;
        dynamic = (const ElfW(Dyn)*)runtime_address(
                base,
                (uintptr_t)phdr->p_vaddr);
        dynamic_count = (size_t)(phdr->p_memsz / sizeof(ElfW(Dyn)));
        break;
    }
    if (dynamic == NULL || dynamic_count == 0) return 0;

    const ElfW(Sym)* symtab = NULL;
    const char* strtab = NULL;
    size_t strsz = 0;
    const void* jmprel = NULL;
    size_t pltrelsz = 0;
    int use_rela = 0;
    int have_pltrel = 0;

    for (size_t index = 0; index < dynamic_count; index++) {
        const ElfW(Dyn)* entry = &dynamic[index];
        if (entry->d_tag == DT_NULL) break;
        switch (entry->d_tag) {
            case DT_SYMTAB:
                symtab = (const ElfW(Sym)*)runtime_address(
                        base,
                        (uintptr_t)entry->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = (const char*)runtime_address(
                        base,
                        (uintptr_t)entry->d_un.d_ptr);
                break;
            case DT_STRSZ:
                strsz = (size_t)entry->d_un.d_val;
                break;
            case DT_JMPREL:
                jmprel = (const void*)runtime_address(
                        base,
                        (uintptr_t)entry->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                pltrelsz = (size_t)entry->d_un.d_val;
                break;
            case DT_PLTREL:
                if (entry->d_un.d_val == DT_RELA) {
                    have_pltrel = 1;
                    use_rela = 1;
                } else if (entry->d_un.d_val == DT_REL) {
                    have_pltrel = 1;
                    use_rela = 0;
                } else {
                    atomic_store(&g_incomplete, 1);
                    return 0;
                }
                break;
            default:
                break;
        }
    }

    if (symtab == NULL || strtab == NULL || strsz == 0 ||
            jmprel == NULL || pltrelsz == 0 || !have_pltrel) {
        return 0;
    }

    patch_relocations(
            base,
            symtab,
            strtab,
            strsz,
            jmprel,
            pltrelsz,
            use_rela);
    return 0;
}

static int patch_jni_onload_relocations(
        uintptr_t base,
        const ElfW(Sym)* symtab,
        const char* strtab,
        size_t strsz,
        const void* relocations,
        size_t relocation_bytes,
        int use_rela) {
    if (symtab == NULL || strtab == NULL || relocations == NULL) return 0;
    const size_t relocation_size =
            use_rela ? sizeof(ElfW(Rela)) : sizeof(ElfW(Rel));
    if (relocation_size == 0 ||
            relocation_bytes % relocation_size != 0) {
        atomic_store(&g_jni_incomplete, 1);
        return 0;
    }

    int patched = 0;
    if (use_rela) {
        const size_t count = relocation_bytes / sizeof(ElfW(Rela));
        const ElfW(Rela)* entries = (const ElfW(Rela)*)relocations;
        for (size_t index = 0; index < count; index++) {
            if (!is_jump_slot_relocation(
                    (uint64_t)entries[index].r_info)) {
                continue;
            }
#if defined(__LP64__)
            const size_t symbol_index =
                    ELF64_R_SYM(entries[index].r_info);
#else
            const size_t symbol_index =
                    ELF32_R_SYM(entries[index].r_info);
#endif
            const ElfW(Sym)* symbol = &symtab[symbol_index];
            if ((size_t)symbol->st_name >= strsz) continue;
            const char* name = strtab + symbol->st_name;
            if (strcmp(name, "dlsym") != 0) continue;
            void** slot = (void**)runtime_address(
                    base,
                    (uintptr_t)entries[index].r_offset);
            if (patch_jni_onload_dlsym_slot(slot)) patched++;
        }
    } else {
        const size_t count = relocation_bytes / sizeof(ElfW(Rel));
        const ElfW(Rel)* entries = (const ElfW(Rel)*)relocations;
        for (size_t index = 0; index < count; index++) {
            if (!is_jump_slot_relocation(
                    (uint64_t)entries[index].r_info)) {
                continue;
            }
#if defined(__LP64__)
            const size_t symbol_index =
                    ELF64_R_SYM(entries[index].r_info);
#else
            const size_t symbol_index =
                    ELF32_R_SYM(entries[index].r_info);
#endif
            const ElfW(Sym)* symbol = &symtab[symbol_index];
            if ((size_t)symbol->st_name >= strsz) continue;
            const char* name = strtab + symbol->st_name;
            if (strcmp(name, "dlsym") != 0) continue;
            void** slot = (void**)runtime_address(
                    base,
                    (uintptr_t)entries[index].r_offset);
            if (patch_jni_onload_dlsym_slot(slot)) patched++;
        }
    }
    return patched;
}

static int patch_art_runtime_module(
        struct dl_phdr_info* info,
        size_t size,
        void* data) {
    (void)size;
    (void)data;
    if (info == NULL || !art_runtime_path(info->dlpi_name)) return 0;

    const uintptr_t base = (uintptr_t)info->dlpi_addr;
    const ElfW(Dyn)* dynamic = NULL;
    size_t dynamic_count = 0;
    for (ElfW(Half) index = 0; index < info->dlpi_phnum; index++) {
        const ElfW(Phdr)* phdr = &info->dlpi_phdr[index];
        if (phdr->p_type != PT_DYNAMIC) continue;
        dynamic = (const ElfW(Dyn)*)runtime_address(
                base,
                (uintptr_t)phdr->p_vaddr);
        dynamic_count =
                (size_t)(phdr->p_memsz / sizeof(ElfW(Dyn)));
        break;
    }
    if (dynamic == NULL || dynamic_count == 0) {
        atomic_store(&g_jni_incomplete, 1);
        return 1;
    }

    const ElfW(Sym)* symtab = NULL;
    const char* strtab = NULL;
    size_t strsz = 0;
    const void* jmprel = NULL;
    size_t pltrelsz = 0;
    int use_rela = 0;
    int have_pltrel = 0;

    for (size_t index = 0; index < dynamic_count; index++) {
        const ElfW(Dyn)* entry = &dynamic[index];
        if (entry->d_tag == DT_NULL) break;
        switch (entry->d_tag) {
            case DT_SYMTAB:
                symtab = (const ElfW(Sym)*)runtime_address(
                        base,
                        (uintptr_t)entry->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = (const char*)runtime_address(
                        base,
                        (uintptr_t)entry->d_un.d_ptr);
                break;
            case DT_STRSZ:
                strsz = (size_t)entry->d_un.d_val;
                break;
            case DT_JMPREL:
                jmprel = (const void*)runtime_address(
                        base,
                        (uintptr_t)entry->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                pltrelsz = (size_t)entry->d_un.d_val;
                break;
            case DT_PLTREL:
                if (entry->d_un.d_val == DT_RELA) {
                    have_pltrel = 1;
                    use_rela = 1;
                } else if (entry->d_un.d_val == DT_REL) {
                    have_pltrel = 1;
                    use_rela = 0;
                } else {
                    atomic_store(&g_jni_incomplete, 1);
                    return 1;
                }
                break;
            default:
                break;
        }
    }

    if (symtab == NULL || strtab == NULL || strsz == 0 ||
            jmprel == NULL || pltrelsz == 0 || !have_pltrel) {
        atomic_store(&g_jni_incomplete, 1);
        return 1;
    }

    patch_jni_onload_relocations(
            base,
            symtab,
            strtab,
            strsz,
            jmprel,
            pltrelsz,
            use_rela);
    return 1;
}

static void scan_loaded_modules(void) {
    pthread_mutex_lock(&g_hook_lock);
    if (atomic_load(&g_trace_active)) {
        dl_iterate_phdr(patch_module, NULL);
    }
    pthread_mutex_unlock(&g_hook_lock);
}

static void* scan_thread_main(void* ignored) {
    (void)ignored;
    while (atomic_load(&g_trace_active)) {
        scan_loaded_modules();
        usleep(SCAN_INTERVAL_US);
    }
    return NULL;
}

static void emit_dlsym_event(
        const char* module,
        const char* symbol,
        void* address) {
    if (g_vm == NULL || g_trace_buffer_class == NULL ||
            g_append_dlsym == NULL || module == NULL ||
            symbol == NULL || address == NULL) {
        return;
    }

    JNIEnv* env = NULL;
    int attached = 0;
    const jint state = (*g_vm)->GetEnv(
            g_vm,
            (void**)&env,
            JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (
                (*g_vm)->AttachCurrentThread(
                        g_vm,
                        &env,
                        NULL) != JNI_OK) {
            return;
        }
        attached = 1;
    } else if (state != JNI_OK || env == NULL) {
        return;
    }

    jstring module_string = (*env)->NewStringUTF(env, module);
    jstring symbol_string = (*env)->NewStringUTF(env, symbol);
    if (module_string != NULL && symbol_string != NULL) {
        (*env)->CallStaticBooleanMethod(
                env,
                g_trace_buffer_class,
                g_append_dlsym,
                module_string,
                symbol_string,
                (jlong)(uintptr_t)address);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }
    if (symbol_string != NULL) {
        (*env)->DeleteLocalRef(env, symbol_string);
    }
    if (module_string != NULL) {
        (*env)->DeleteLocalRef(env, module_string);
    }

    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

static void emit_jni_onload_event(
        const char* module,
        void* address) {
    if (g_vm == NULL || g_trace_buffer_class == NULL ||
            module == NULL || address == NULL) {
        atomic_store(&g_jni_incomplete, 1);
        return;
    }

    JNIEnv* env = NULL;
    int attached = 0;
    const jint state = (*g_vm)->GetEnv(
            g_vm,
            (void**)&env,
            JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(
                g_vm,
                &env,
                NULL) != JNI_OK) {
            atomic_store(&g_jni_incomplete, 1);
            return;
        }
        attached = 1;
    } else if (state != JNI_OK || env == NULL) {
        atomic_store(&g_jni_incomplete, 1);
        return;
    }

    jstring module_string = (*env)->NewStringUTF(env, module);
    if (module_string != NULL) {
        jmethodID append = (*env)->GetStaticMethodID(
                env,
                g_trace_buffer_class,
                "appendJniOnLoad",
                "(Ljava/lang/String;J)Z");
        if (append != NULL) {
            (*env)->CallStaticBooleanMethod(
                    env,
                    g_trace_buffer_class,
                    append,
                    module_string,
                    (jlong)(uintptr_t)address);
        } else {
            (*env)->ExceptionClear(env);
            atomic_store(&g_jni_incomplete, 1);
        }
    } else {
        atomic_store(&g_jni_incomplete, 1);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        atomic_store(&g_jni_incomplete, 1);
    }
    if (module_string != NULL) {
        (*env)->DeleteLocalRef(env, module_string);
    }

    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

static void emit_register_native_event(
        JNIEnv* env,
        jclass clazz,
        const char* module,
        const char* method,
        const char* signature,
        void* address) {
    if (env == NULL || clazz == NULL ||
            g_trace_buffer_class == NULL ||
            g_append_register_native_class == NULL ||
            module == NULL || method == NULL ||
            signature == NULL || address == NULL) {
        atomic_store(&g_jni_incomplete, 1);
        return;
    }
    if ((*env)->ExceptionCheck(env)) {
        atomic_store(&g_jni_incomplete, 1);
        return;
    }

    jstring module_string = (*env)->NewStringUTF(env, module);
    jstring method_string = (*env)->NewStringUTF(env, method);
    jstring signature_string =
            (*env)->NewStringUTF(env, signature);
    if (module_string != NULL &&
            method_string != NULL &&
            signature_string != NULL) {
        (*env)->CallStaticBooleanMethod(
                env,
                g_trace_buffer_class,
                g_append_register_native_class,
                clazz,
                module_string,
                method_string,
                signature_string,
                (jlong)(uintptr_t)address);
    }

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        atomic_store(&g_jni_incomplete, 1);
    }
    if (signature_string != NULL) {
        (*env)->DeleteLocalRef(env, signature_string);
    }
    if (method_string != NULL) {
        (*env)->DeleteLocalRef(env, method_string);
    }
    if (module_string != NULL) {
        (*env)->DeleteLocalRef(env, module_string);
    }
    if (module_string == NULL ||
            method_string == NULL ||
            signature_string == NULL) {
        atomic_store(&g_jni_incomplete, 1);
    }
}

static jint invoke_jni_onload_slot(
        size_t slot_index,
        JavaVM* vm,
        void* reserved) {
    if (slot_index >= MAX_JNI_ONLOAD_WRAPPERS) {
        return JNI_ERR;
    }

    jni_onload_pending_slot pending;
    memset(&pending, 0, sizeof(pending));
    pthread_mutex_lock(&g_jni_onload_wrapper_lock);
    if (g_jni_onload_pending[slot_index].in_use) {
        pending = g_jni_onload_pending[slot_index];
    }
    pthread_mutex_unlock(&g_jni_onload_wrapper_lock);

    if (!pending.in_use || pending.original == NULL) {
        atomic_store(&g_jni_incomplete, 1);
        return JNI_ERR;
    }

    atomic_fetch_add(&g_jni_inflight, 1);
    const jint result = pending.original(vm, reserved);
    if (atomic_load(&g_jni_trace_active) &&
            pending.generation ==
                    atomic_load(&g_jni_generation)) {
        emit_jni_onload_event(
                pending.module,
                pending.address);
    }
    atomic_fetch_sub(&g_jni_inflight, 1);

    pthread_mutex_lock(&g_jni_onload_wrapper_lock);
    if (g_jni_onload_pending[slot_index].in_use &&
            g_jni_onload_pending[slot_index].generation ==
                    pending.generation &&
            g_jni_onload_pending[slot_index].original ==
                    pending.original) {
        memset(
                &g_jni_onload_pending[slot_index],
                0,
                sizeof(g_jni_onload_pending[slot_index]));
    }
    pthread_mutex_unlock(&g_jni_onload_wrapper_lock);
    return result;
}

static jint JNICALL modkit_trace_jni_onload_0(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(0, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_1(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(1, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_2(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(2, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_3(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(3, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_4(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(4, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_5(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(5, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_6(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(6, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_7(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(7, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_8(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(8, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_9(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(9, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_10(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(10, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_11(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(11, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_12(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(12, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_13(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(13, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_14(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(14, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_15(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(15, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_16(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(16, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_17(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(17, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_18(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(18, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_19(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(19, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_20(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(20, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_21(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(21, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_22(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(22, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_23(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(23, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_24(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(24, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_25(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(25, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_26(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(26, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_27(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(27, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_28(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(28, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_29(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(29, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_30(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(30, vm, reserved);
}

static jint JNICALL modkit_trace_jni_onload_31(
        JavaVM* vm,
        void* reserved) {
    return invoke_jni_onload_slot(31, vm, reserved);
}

static jni_onload_fn const
        g_jni_onload_wrappers[MAX_JNI_ONLOAD_WRAPPERS] = {
            modkit_trace_jni_onload_0,
            modkit_trace_jni_onload_1,
            modkit_trace_jni_onload_2,
            modkit_trace_jni_onload_3,
            modkit_trace_jni_onload_4,
            modkit_trace_jni_onload_5,
            modkit_trace_jni_onload_6,
            modkit_trace_jni_onload_7,
            modkit_trace_jni_onload_8,
            modkit_trace_jni_onload_9,
            modkit_trace_jni_onload_10,
            modkit_trace_jni_onload_11,
            modkit_trace_jni_onload_12,
            modkit_trace_jni_onload_13,
            modkit_trace_jni_onload_14,
            modkit_trace_jni_onload_15,
            modkit_trace_jni_onload_16,
            modkit_trace_jni_onload_17,
            modkit_trace_jni_onload_18,
            modkit_trace_jni_onload_19,
            modkit_trace_jni_onload_20,
            modkit_trace_jni_onload_21,
            modkit_trace_jni_onload_22,
            modkit_trace_jni_onload_23,
            modkit_trace_jni_onload_24,
            modkit_trace_jni_onload_25,
            modkit_trace_jni_onload_26,
            modkit_trace_jni_onload_27,
            modkit_trace_jni_onload_28,
            modkit_trace_jni_onload_29,
            modkit_trace_jni_onload_30,
            modkit_trace_jni_onload_31
        };

static void* allocate_jni_onload_wrapper(
        const char* module,
        void* address) {
    if (module == NULL || address == NULL) return NULL;
    const size_t module_length = strlen(module);
    if (module_length == 0 ||
            module_length >=
                    sizeof(g_jni_onload_pending[0].module)) {
        atomic_store(&g_jni_incomplete, 1);
        return NULL;
    }

    union {
        void* object;
        jni_onload_fn function;
    } original;
    original.object = address;
    if (original.function == NULL) return NULL;

    void* wrapper_object = NULL;
    pthread_mutex_lock(&g_jni_onload_wrapper_lock);
    for (size_t index = 0;
            index < MAX_JNI_ONLOAD_WRAPPERS;
            index++) {
        if (g_jni_onload_pending[index].in_use) continue;
        g_jni_onload_pending[index].original =
                original.function;
        g_jni_onload_pending[index].address = address;
        memcpy(
                g_jni_onload_pending[index].module,
                module,
                module_length + 1);
        g_jni_onload_pending[index].generation =
                atomic_load(&g_jni_generation);
        g_jni_onload_pending[index].in_use = 1;

        union {
            jni_onload_fn function;
            void* object;
        } wrapper;
        wrapper.function = g_jni_onload_wrappers[index];
        wrapper_object = wrapper.object;
        break;
    }
    pthread_mutex_unlock(&g_jni_onload_wrapper_lock);

    if (wrapper_object == NULL) {
        atomic_store(&g_jni_incomplete, 1);
    }
    return wrapper_object;
}

static int jni_onload_wrapper_capacity_available(void) {
    int available = 0;
    pthread_mutex_lock(&g_jni_onload_wrapper_lock);
    for (size_t index = 0;
            index < MAX_JNI_ONLOAD_WRAPPERS;
            index++) {
        if (!g_jni_onload_pending[index].in_use) {
            available = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_jni_onload_wrapper_lock);
    return available;
}

static int pending_jni_onload_for_generation(
        unsigned long generation) {
    int count = 0;
    pthread_mutex_lock(&g_jni_onload_wrapper_lock);
    for (size_t index = 0;
            index < MAX_JNI_ONLOAD_WRAPPERS;
            index++) {
        if (g_jni_onload_pending[index].in_use &&
                g_jni_onload_pending[index].generation ==
                        generation) {
            count++;
        }
    }
    pthread_mutex_unlock(&g_jni_onload_wrapper_lock);
    return count;
}

static void* modkit_trace_art_dlsym(
        void* handle,
        const char* symbol) {
    dlsym_fn real = atomic_load(&g_real_dlsym);
    if (real == NULL) return NULL;
    if (g_in_art_dlsym_wrapper) {
        return real(handle, symbol);
    }

    g_in_art_dlsym_wrapper = 1;
    void* result = real(handle, symbol);
    if (result != NULL && symbol != NULL &&
            strcmp(symbol, "JNI_OnLoad") == 0) {
        Dl_info info;
        memset(&info, 0, sizeof(info));
        if (dladdr(result, &info) != 0 &&
                app_owned_path(info.dli_fname)) {
            const char* module = base_name(info.dli_fname);
            if (valid_module(module)) {
                int admitted = 0;
                void* wrapper = NULL;
                pthread_mutex_lock(&g_jni_hook_lock);
                if (atomic_load(&g_jni_trace_active)) {
                    atomic_fetch_add(&g_jni_inflight, 1);
                    admitted = 1;
                    wrapper =
                            allocate_jni_onload_wrapper(
                                    module,
                                    result);
                }
                pthread_mutex_unlock(&g_jni_hook_lock);

                if (admitted) {
                    emit_dlsym_event(module, symbol, result);
                    if (wrapper != NULL) {
                        result = wrapper;
                    }
                    atomic_fetch_sub(&g_jni_inflight, 1);
                }
            }
        }
    }
    g_in_art_dlsym_wrapper = 0;
    return result;
}

static jint JNICALL modkit_trace_register_natives(
        JNIEnv* env,
        jclass clazz,
        const JNINativeMethod* methods,
        jint method_count) {
    register_natives_fn real =
            atomic_load(&g_real_register_natives);
    if (real == NULL) return JNI_ERR;
    if (g_in_register_natives_wrapper) {
        return real(env, clazz, methods, method_count);
    }

    g_in_register_natives_wrapper = 1;
    const unsigned long generation =
            atomic_load(&g_jni_generation);
    const int had_exception =
            env != NULL && (*env)->ExceptionCheck(env);
    atomic_fetch_add(&g_jni_inflight, 1);
    const jint result =
            real(env, clazz, methods, method_count);

    if (atomic_load(&g_jni_trace_active) &&
            generation == atomic_load(&g_jni_generation) &&
            result == JNI_OK && !had_exception &&
            env != NULL && !(*env)->ExceptionCheck(env)) {
        if (method_count > MAX_REGISTER_NATIVE_METHODS) {
            atomic_store(&g_jni_incomplete, 1);
        }
        const jint retained =
                method_count < 0 ? 0 :
                (method_count > MAX_REGISTER_NATIVE_METHODS
                        ? MAX_REGISTER_NATIVE_METHODS
                        : method_count);
        for (jint index = 0; index < retained; index++) {
            const JNINativeMethod* method = &methods[index];
            if (method->name == NULL ||
                    method->signature == NULL ||
                    method->fnPtr == NULL) {
                atomic_store(&g_jni_incomplete, 1);
                continue;
            }
            Dl_info info;
            memset(&info, 0, sizeof(info));
            if (dladdr(method->fnPtr, &info) == 0 ||
                    !app_owned_path(info.dli_fname)) {
                continue;
            }
            const char* module = base_name(info.dli_fname);
            if (!valid_module(module)) {
                atomic_store(&g_jni_incomplete, 1);
                continue;
            }
            emit_register_native_event(
                    env,
                    clazz,
                    module,
                    method->name,
                    method->signature,
                    method->fnPtr);
        }
    } else if (result == JNI_OK &&
            atomic_load(&g_jni_trace_active)) {
        atomic_store(&g_jni_incomplete, 1);
    }

    atomic_fetch_sub(&g_jni_inflight, 1);
    g_in_register_natives_wrapper = 0;
    return result;
}

static void* modkit_trace_dlsym(void* handle, const char* symbol) {
    dlsym_fn real = atomic_load(&g_real_dlsym);
    if (real == NULL) return NULL;
    if (g_in_dlsym_wrapper) {
        return real(handle, symbol);
    }

    g_in_dlsym_wrapper = 1;
    atomic_fetch_add(&g_inflight, 1);
    void* result = real(handle, symbol);

    if (atomic_load(&g_trace_active) &&
            result != NULL && valid_symbol(symbol)) {
        Dl_info info;
        memset(&info, 0, sizeof(info));
        if (dladdr(result, &info) != 0 &&
                app_owned_path(info.dli_fname)) {
            const char* module = base_name(info.dli_fname);
            if (valid_module(module)) {
                emit_dlsym_event(module, symbol, result);
            }
        }
    }

    atomic_fetch_sub(&g_inflight, 1);
    g_in_dlsym_wrapper = 0;
    return result;
}

static void* wrapper_pointer(void) {
    union {
        dlsym_fn function;
        void* object;
    } conversion;
    conversion.function = modkit_trace_dlsym;
    return conversion.object;
}

static void* art_dlsym_wrapper_pointer(void) {
    union {
        dlsym_fn function;
        void* object;
    } conversion;
    conversion.function = modkit_trace_art_dlsym;
    return conversion.object;
}

static int restore_all_slots(void) {
    int ok = 1;
    pthread_mutex_lock(&g_hook_lock);
    for (size_t index = 0; index < g_slot_count; index++) {
        patched_slot* patch = &g_slots[index];
        if (!change_page_protection(
                patch->slot,
                patch->original_prot | PROT_WRITE)) {
            ok = 0;
            continue;
        }
        __atomic_store_n(
                patch->slot,
                patch->original,
                __ATOMIC_SEQ_CST);
        if (!change_page_protection(
                patch->slot,
                patch->original_prot)) {
            ok = 0;
        }
    }
    pthread_mutex_unlock(&g_hook_lock);
    if (!ok) atomic_store(&g_restore_failed, 1);
    return ok;
}

static int restore_jni_hooks_locked(void) {
    int ok = 1;
    for (size_t index = 0;
            index < g_jni_onload_slot_count;
            index++) {
        patched_slot* patch = &g_jni_onload_slots[index];
        if (!change_page_protection(
                patch->slot,
                patch->original_prot | PROT_WRITE)) {
            ok = 0;
            continue;
        }
        __atomic_store_n(
                patch->slot,
                patch->original,
                __ATOMIC_SEQ_CST);
        if (!change_page_protection(
                patch->slot,
                patch->original_prot)) {
            ok = 0;
        }
    }

    if (g_register_natives_slot != NULL) {
        register_natives_fn original =
                atomic_load(&g_real_register_natives);
        if (original == NULL ||
                !change_page_protection(
                        (void*)g_register_natives_slot,
                        g_register_natives_original_prot |
                                PROT_WRITE)) {
            ok = 0;
        } else {
            __atomic_store_n(
                    g_register_natives_slot,
                    original,
                    __ATOMIC_SEQ_CST);
            if (!change_page_protection(
                    (void*)g_register_natives_slot,
                    g_register_natives_original_prot)) {
                ok = 0;
            }
        }
    }

    if (!ok) {
        atomic_store(&g_jni_restore_failed, 1);
    }
    return ok;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_vm = vm;

    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK ||
            env == NULL) {
        return JNI_ERR;
    }
    jclass local = (*env)->FindClass(
            env,
            "io/github/ffenuss/modkit/runtimeprobe/RuntimeNativeTraceBuffer");
    if (local == NULL) {
        (*env)->ExceptionClear(env);
        return JNI_ERR;
    }
    g_trace_buffer_class =
            (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (g_trace_buffer_class == NULL) return JNI_ERR;

    g_append_dlsym = (*env)->GetStaticMethodID(
            env,
            g_trace_buffer_class,
            "appendDlsym",
            "(Ljava/lang/String;Ljava/lang/String;J)Z");
    if (g_append_dlsym == NULL) {
        (*env)->ExceptionClear(env);
        return JNI_ERR;
    }
    g_append_register_native_class = (*env)->GetStaticMethodID(
            env,
            g_trace_buffer_class,
            "appendRegisterNativeClass",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)Z");
    if (g_append_register_native_class == NULL) {
        (*env)->ExceptionClear(env);
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativeStartPassiveDlsymTrace(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;

    if (!ensure_real_dlsym()) {
        return JNI_FALSE;
    }

    pthread_mutex_lock(&g_hook_lock);
    if (atomic_load(&g_trace_active)) {
        pthread_mutex_unlock(&g_hook_lock);
        return JNI_TRUE;
    }
    if (atomic_load(&g_restore_failed) ||
            atomic_load(&g_inflight) != 0) {
        pthread_mutex_unlock(&g_hook_lock);
        return JNI_FALSE;
    }
    g_slot_count = 0;
    atomic_store(&g_incomplete, 0);
    atomic_store(&g_trace_active, 1);
    pthread_mutex_unlock(&g_hook_lock);

    scan_loaded_modules();
    if (atomic_load(&g_restore_failed)) {
        atomic_store(&g_trace_active, 0);
        restore_all_slots();
        return JNI_FALSE;
    }
    if (pthread_create(
            &g_scan_thread,
            NULL,
            scan_thread_main,
            NULL) != 0) {
        atomic_store(&g_trace_active, 0);
        restore_all_slots();
        return JNI_FALSE;
    }
    atomic_store(&g_scan_thread_started, 1);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativeStopPassiveDlsymTrace(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;

    atomic_store(&g_trace_active, 0);
    if (atomic_exchange(&g_scan_thread_started, 0)) {
        pthread_join(g_scan_thread, NULL);
    }

    const int restored = restore_all_slots();

    for (int spin = 0; spin < 200 && atomic_load(&g_inflight) > 0; spin++) {
        usleep(1000);
    }
    if (atomic_load(&g_inflight) > 0) {
        atomic_store(&g_incomplete, 1);
        return JNI_FALSE;
    }
    return restored ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativePassiveDlsymTraceActive(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;
    return atomic_load(&g_trace_active) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativePassiveDlsymHookedSlotCount(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_hook_lock);
    const size_t count = g_slot_count;
    pthread_mutex_unlock(&g_hook_lock);
    return count > (size_t)INT32_MAX ? INT32_MAX : (jint)count;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativePassiveDlsymIncomplete(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;
    return atomic_load(&g_incomplete) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativePassiveDlsymRestoreFailed(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;
    return atomic_load(&g_restore_failed) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativeStartPassiveJniTrace(
        JNIEnv* env,
        jclass clazz) {
    (void)clazz;
    if (!ensure_real_dlsym()) return JNI_FALSE;

    pthread_mutex_lock(&g_jni_hook_lock);
    if (atomic_load(&g_jni_trace_active)) {
        pthread_mutex_unlock(&g_jni_hook_lock);
        return JNI_TRUE;
    }
    if (atomic_load(&g_jni_restore_failed) ||
            atomic_load(&g_jni_inflight) != 0) {
        pthread_mutex_unlock(&g_jni_hook_lock);
        return JNI_FALSE;
    }

    g_jni_onload_slot_count = 0;
    g_register_natives_slot = NULL;
    atomic_store(&g_real_register_natives, NULL);
    g_register_natives_original_prot = 0;
    atomic_store(&g_jni_incomplete, 0);
    atomic_fetch_add(&g_jni_generation, 1);

    const int register_hooked =
            patch_register_natives_slot(env);
    dl_iterate_phdr(patch_art_runtime_module, NULL);
    if (g_jni_onload_slot_count == 0 ||
            !jni_onload_wrapper_capacity_available()) {
        atomic_store(&g_jni_incomplete, 1);
    }
    if (!register_hooked ||
            atomic_load(&g_jni_restore_failed)) {
        restore_jni_hooks_locked();
        pthread_mutex_unlock(&g_jni_hook_lock);
        return JNI_FALSE;
    }

    atomic_store(&g_jni_trace_active, 1);
    pthread_mutex_unlock(&g_jni_hook_lock);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativeStopPassiveJniTrace(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;

    pthread_mutex_lock(&g_jni_hook_lock);
    const int had_inflight =
            atomic_load(&g_jni_inflight) > 0;
    atomic_store(&g_jni_trace_active, 0);
    const unsigned long stopping_generation =
            atomic_load(&g_jni_generation);
    const int restored = restore_jni_hooks_locked();
    atomic_fetch_add(&g_jni_generation, 1);
    pthread_mutex_unlock(&g_jni_hook_lock);

    if (had_inflight ||
            pending_jni_onload_for_generation(
                    stopping_generation) > 0) {
        atomic_store(&g_jni_incomplete, 1);
    }

    for (int spin = 0;
            spin < 200 && atomic_load(&g_jni_inflight) > 0;
            spin++) {
        usleep(1000);
    }
    if (atomic_load(&g_jni_inflight) > 0) {
        atomic_store(&g_jni_incomplete, 1);
        return JNI_FALSE;
    }
    return restored ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativePassiveJniTraceActive(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;
    return atomic_load(&g_jni_trace_active)
            ? JNI_TRUE
            : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativePassiveJniOnLoadHookedSlotCount(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_jni_hook_lock);
    const size_t count = g_jni_onload_slot_count;
    pthread_mutex_unlock(&g_jni_hook_lock);
    return count > (size_t)INT32_MAX
            ? INT32_MAX
            : (jint)count;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativePassiveRegisterNativesHooked(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_jni_hook_lock);
    const int hooked = g_register_natives_slot != NULL;
    pthread_mutex_unlock(&g_jni_hook_lock);
    return hooked ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativePassiveJniOnLoadInvocationReady(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_jni_hook_lock);
    const int hooked = g_jni_onload_slot_count > 0;
    pthread_mutex_unlock(&g_jni_hook_lock);
    return atomic_load(&g_jni_trace_active) &&
            hooked &&
            jni_onload_wrapper_capacity_available()
            ? JNI_TRUE
            : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativePassiveJniIncomplete(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;
    return atomic_load(&g_jni_incomplete)
            ? JNI_TRUE
            : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativePassiveJniRestoreFailed(
        JNIEnv* env,
        jclass clazz) {
    (void)env;
    (void)clazz;
    return atomic_load(&g_jni_restore_failed)
            ? JNI_TRUE
            : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ffenuss_modkit_runtimeprobe_RuntimeNativeBridge_nativePatchCode(
        JNIEnv* env,
        jclass clazz,
        jstring module_name,
        jlong binary_virtual_address,
        jbyteArray expected_bytes,
        jbyteArray replacement_bytes) {
    (void)clazz;
    if (module_name == NULL ||
            expected_bytes == NULL ||
            replacement_bytes == NULL ||
            binary_virtual_address <= 0) {
        return JNI_FALSE;
    }

    const jsize expected_length =
            (*env)->GetArrayLength(env, expected_bytes);
    const jsize replacement_length =
            (*env)->GetArrayLength(env, replacement_bytes);
    if (expected_length <= 0 ||
            expected_length != replacement_length ||
            expected_length > 64 ||
            expected_length % 4 != 0) {
        return JNI_FALSE;
    }

    const char* module =
            (*env)->GetStringUTFChars(
                    env,
                    module_name,
                    NULL);
    if (module == NULL) return JNI_FALSE;
    if (!valid_module(module)) {
        (*env)->ReleaseStringUTFChars(
                env,
                module_name,
                module);
        return JNI_FALSE;
    }

    uint8_t expected[64];
    uint8_t replacement[64];
    (*env)->GetByteArrayRegion(
            env,
            expected_bytes,
            0,
            expected_length,
            (jbyte*)expected);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->ReleaseStringUTFChars(
                env,
                module_name,
                module);
        return JNI_FALSE;
    }
    (*env)->GetByteArrayRegion(
            env,
            replacement_bytes,
            0,
            replacement_length,
            (jbyte*)replacement);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->ReleaseStringUTFChars(
                env,
                module_name,
                module);
        return JNI_FALSE;
    }

    code_patch_lookup lookup;
    memset(&lookup, 0, sizeof(lookup));
    lookup.module_name = module;
    lookup.binary_virtual_address =
            (uintptr_t)binary_virtual_address;
    lookup.length = (size_t)expected_length;

    pthread_mutex_lock(&g_code_patch_lock);
    dl_iterate_phdr(
            locate_code_patch_target,
            &lookup);

    int success = 0;
    if (lookup.found && lookup.address != NULL) {
        uint8_t* target =
                (uint8_t*)lookup.address;
        uint8_t* last =
                target + expected_length - 1;
        const int start_prot =
                query_protection(target);
        const int end_prot =
                query_protection(last);

        if (
            start_prot != 0 &&
            start_prot == end_prot &&
            (start_prot & PROT_READ) != 0 &&
            (start_prot & PROT_EXEC) != 0 &&
            memcmp(
                target,
                expected,
                (size_t)expected_length) == 0
        ) {
            const int writable =
                    start_prot | PROT_WRITE;
            if (change_code_range_protection(
                    target,
                    (size_t)expected_length,
                    writable)) {
                memcpy(
                    target,
                    replacement,
                    (size_t)replacement_length);
                __builtin___clear_cache(
                    (char*)target,
                    (char*)target +
                        replacement_length);
                const int restored =
                        change_code_range_protection(
                            target,
                            (size_t)expected_length,
                            start_prot);
                success =
                        restored &&
                        memcmp(
                            target,
                            replacement,
                            (size_t)replacement_length) == 0;
            }
        }
    }

    pthread_mutex_unlock(&g_code_patch_lock);
    (*env)->ReleaseStringUTFChars(
            env,
            module_name,
            module);
    return success ? JNI_TRUE : JNI_FALSE;
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
