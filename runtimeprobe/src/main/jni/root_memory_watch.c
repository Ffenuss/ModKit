#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <inttypes.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef __WALL
#define __WALL 0x40000000
#endif

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

#ifndef NT_ARM_HW_WATCH
#define NT_ARM_HW_WATCH 0x403
#endif

#ifndef TRAP_HWBKPT
#define TRAP_HWBKPT 4
#endif

#define MODKIT_ROOT_WATCH_V1 "MODKIT_ROOT_WATCH_V1"
#define MAX_THREADS 256
#define MAX_HW_REGS 16
#define MAX_UNIQUE_HITS 128
#define REFRESH_THREADS_MS 200

typedef struct {
    uint64_t addr;
    uint32_t ctrl;
    uint32_t pad;
} modkit_hwdebug_reg;

typedef struct {
    uint32_t dbg_info;
    uint32_t pad;
    modkit_hwdebug_reg dbg_regs[MAX_HW_REGS];
} modkit_hwdebug_state;

typedef struct {
    uint64_t regs[31];
    uint64_t sp;
    uint64_t pc;
    uint64_t pstate;
} modkit_user_pt_regs;

typedef struct {
    pid_t tid;
    int active;
    int slot;
    modkit_hwdebug_state original;
    size_t original_len;
} watched_thread;

typedef struct {
    uint64_t pc;
    uint64_t fault_address;
    uint32_t count;
    pid_t last_tid;
} watch_hit;

static watched_thread g_threads[MAX_THREADS];
static size_t g_thread_count = 0;
static watch_hit g_hits[MAX_UNIQUE_HITS];
static size_t g_hit_count = 0;
static uint32_t g_total_traps = 0;
static int g_truncated = 0;
static volatile sig_atomic_t g_stop_requested = 0;

static void request_stop(int signal_number) {
    (void)signal_number;
    g_stop_requested = 1;
}

static int64_t monotonic_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
    return ((int64_t)now.tv_sec * 1000LL) +
            ((int64_t)now.tv_nsec / 1000000LL);
}

static void sleep_ms(long value) {
    if (value <= 0) return;
    struct timespec req;
    req.tv_sec = value / 1000;
    req.tv_nsec = (value % 1000) * 1000000L;
    while (nanosleep(&req, &req) != 0 && errno == EINTR) {
    }
}

static int parse_pid(const char* text, pid_t* out) {
    if (text == NULL || *text == '\0' || out == NULL) return 0;
    char* end = NULL;
    errno = 0;
    long value = strtol(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' ||
            value <= 0 || value > 1L << 30) {
        return 0;
    }
    *out = (pid_t)value;
    return 1;
}

static int parse_u64_hex(const char* text, uint64_t* out) {
    if (text == NULL || *text == '\0' || out == NULL) return 0;
    char* end = NULL;
    errno = 0;
    unsigned long long value = strtoull(text, &end, 0);
    if (errno != 0 || end == text || *end != '\0' || value == 0ULL) {
        return 0;
    }
    *out = (uint64_t)value;
    return 1;
}

static int parse_int_bounded(
        const char* text,
        int min_value,
        int max_value,
        int* out) {
    if (text == NULL || *text == '\0' || out == NULL) return 0;
    char* end = NULL;
    errno = 0;
    long value = strtol(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' ||
            value < min_value || value > max_value) {
        return 0;
    }
    *out = (int)value;
    return 1;
}

static int find_thread(pid_t tid) {
    for (size_t index = 0; index < g_thread_count; index++) {
        if (g_threads[index].tid == tid) return (int)index;
    }
    return -1;
}

static int wait_for_stop(pid_t tid, int timeout_ms) {
    const int64_t deadline = monotonic_ms() + timeout_ms;
    while (monotonic_ms() < deadline) {
        int status = 0;
        pid_t result = waitpid(tid, &status, __WALL | WNOHANG);
        if (result == tid) {
            if (WIFSTOPPED(status)) return 1;
            return 0;
        }
        if (result < 0 && errno != EINTR) return 0;
        sleep_ms(2);
    }
    return 0;
}

#if defined(__aarch64__)
static uint32_t arm64_watch_ctrl(uint64_t address, int width, uint64_t* aligned) {
    const uint64_t base = address & ~7ULL;
    const unsigned offset = (unsigned)(address - base);
    if (width <= 0 || width > 8 || offset + (unsigned)width > 8U) {
        return 0U;
    }
    unsigned bas = width == 8 ? 0xffU : ((1U << width) - 1U);
    bas <<= offset;

    /*
     * DBGWCR_EL1 fields exposed through NT_ARM_HW_WATCH:
     * E=1, PAC=EL0(2), LSC=load+store(3), BAS=selected bytes.
     */
    const uint32_t enabled = 1U;
    const uint32_t pac_el0 = 2U << 1;
    const uint32_t load_store = 3U << 3;
    const uint32_t byte_select = bas << 5;
    *aligned = base;
    return enabled | pac_el0 | load_store | byte_select;
}

static int arm_watchpoint(
        pid_t tid,
        uint64_t address,
        int width,
        watched_thread* output) {
    if (output == NULL) return 0;

    modkit_hwdebug_state state;
    memset(&state, 0, sizeof(state));
    struct iovec iov;
    iov.iov_base = &state;
    iov.iov_len = sizeof(state);
    if (ptrace(
            PTRACE_GETREGSET,
            tid,
            (void*)(uintptr_t)NT_ARM_HW_WATCH,
            &iov) != 0) {
        return 0;
    }

    unsigned slot_count = state.dbg_info & 0xffU;
    if (slot_count == 0U) return 0;
    if (slot_count > MAX_HW_REGS) slot_count = MAX_HW_REGS;

    int slot = -1;
    for (unsigned index = 0; index < slot_count; index++) {
        if ((state.dbg_regs[index].ctrl & 1U) == 0U) {
            slot = (int)index;
            break;
        }
    }
    if (slot < 0) return 0;

    uint64_t aligned = 0;
    const uint32_t ctrl = arm64_watch_ctrl(address, width, &aligned);
    if (ctrl == 0U) return 0;

    output->original = state;
    output->original_len = iov.iov_len;
    output->slot = slot;

    state.dbg_regs[slot].addr = aligned;
    state.dbg_regs[slot].ctrl = ctrl;

    struct iovec set_iov;
    set_iov.iov_base = &state;
    set_iov.iov_len = iov.iov_len;
    if (ptrace(
            PTRACE_SETREGSET,
            tid,
            (void*)(uintptr_t)NT_ARM_HW_WATCH,
            &set_iov) != 0) {
        return 0;
    }
    return 1;
}

static int restore_watchpoint(const watched_thread* thread) {
    if (thread == NULL || !thread->active || thread->original_len == 0) {
        return 1;
    }
    modkit_hwdebug_state state = thread->original;
    struct iovec iov;
    iov.iov_base = &state;
    iov.iov_len = thread->original_len;
    return ptrace(
            PTRACE_SETREGSET,
            thread->tid,
            (void*)(uintptr_t)NT_ARM_HW_WATCH,
            &iov) == 0;
}

static int read_pc(pid_t tid, uint64_t* pc) {
    if (pc == NULL) return 0;
    modkit_user_pt_regs regs;
    memset(&regs, 0, sizeof(regs));
    struct iovec iov;
    iov.iov_base = &regs;
    iov.iov_len = sizeof(regs);
    if (ptrace(
            PTRACE_GETREGSET,
            tid,
            (void*)(uintptr_t)NT_PRSTATUS,
            &iov) != 0) {
        return 0;
    }
    *pc = regs.pc;
    return regs.pc != 0ULL;
}
#else
static int arm_watchpoint(
        pid_t tid,
        uint64_t address,
        int width,
        watched_thread* output) {
    (void)tid;
    (void)address;
    (void)width;
    (void)output;
    return 0;
}

static int restore_watchpoint(const watched_thread* thread) {
    (void)thread;
    return 1;
}

static int read_pc(pid_t tid, uint64_t* pc) {
    (void)tid;
    (void)pc;
    return 0;
}
#endif

static int seize_thread(pid_t tid, uint64_t address, int width) {
    if (tid <= 0) return 0;
    if (find_thread(tid) >= 0) return 1;
    if (g_thread_count >= MAX_THREADS) {
        g_truncated = 1;
        return 0;
    }

    if (ptrace(PTRACE_SEIZE, tid, NULL, NULL) != 0) {
        if (errno == ESRCH) return 0;
        return -1;
    }
    if (ptrace(PTRACE_INTERRUPT, tid, NULL, NULL) != 0) {
        ptrace(PTRACE_DETACH, tid, NULL, NULL);
        return -1;
    }
    if (!wait_for_stop(tid, 1000)) {
        ptrace(PTRACE_DETACH, tid, NULL, NULL);
        return -1;
    }

    watched_thread thread;
    memset(&thread, 0, sizeof(thread));
    thread.tid = tid;
    thread.slot = -1;
    thread.active = 1;

    if (!arm_watchpoint(tid, address, width, &thread)) {
        ptrace(PTRACE_DETACH, tid, NULL, NULL);
        return -2;
    }

    g_threads[g_thread_count++] = thread;
    if (ptrace(PTRACE_CONT, tid, NULL, NULL) != 0) {
        watched_thread* stored =
                &g_threads[g_thread_count - 1];
        (void)restore_watchpoint(stored);
        (void)ptrace(PTRACE_DETACH, tid, NULL, NULL);
        stored->active = 0;
        return -1;
    }
    return 1;
}

static int enumerate_threads(pid_t pid, uint64_t address, int width) {
    char path[64];
    const int written = snprintf(path, sizeof(path), "/proc/%d/task", pid);
    if (written <= 0 || (size_t)written >= sizeof(path)) return -1;

    DIR* dir = opendir(path);
    if (dir == NULL) return -1;

    int added = 0;
    struct dirent* entry = NULL;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char* end = NULL;
        errno = 0;
        long raw = strtol(entry->d_name, &end, 10);
        if (errno != 0 || end == entry->d_name || *end != '\0' ||
                raw <= 0 || raw > 1L << 30) {
            continue;
        }
        const pid_t tid = (pid_t)raw;
        if (find_thread(tid) >= 0) continue;
        const int result = seize_thread(tid, address, width);
        if (result > 0) {
            added++;
        } else if (result == -2 && g_thread_count == 0) {
            closedir(dir);
            return -2;
        }
    }
    closedir(dir);
    return added;
}

static void mark_thread_dead(pid_t tid) {
    const int index = find_thread(tid);
    if (index >= 0) g_threads[index].active = 0;
}

static void record_hit(pid_t tid, uint64_t pc, uint64_t fault_address) {
    if (pc == 0ULL) return;
    g_total_traps++;
    for (size_t index = 0; index < g_hit_count; index++) {
        if (g_hits[index].pc == pc) {
            if (g_hits[index].count < UINT32_MAX) {
                g_hits[index].count++;
            }
            g_hits[index].last_tid = tid;
            g_hits[index].fault_address = fault_address;
            return;
        }
    }
    if (g_hit_count >= MAX_UNIQUE_HITS) {
        g_truncated = 1;
        return;
    }
    g_hits[g_hit_count].pc = pc;
    g_hits[g_hit_count].fault_address = fault_address;
    g_hits[g_hit_count].count = 1U;
    g_hits[g_hit_count].last_tid = tid;
    g_hit_count++;
}

static int compare_hits(const void* left, const void* right) {
    const watch_hit* a = (const watch_hit*)left;
    const watch_hit* b = (const watch_hit*)right;
    if (a->count < b->count) return 1;
    if (a->count > b->count) return -1;
    if (a->pc < b->pc) return -1;
    if (a->pc > b->pc) return 1;
    return 0;
}

static void cleanup_threads(void) {
    for (size_t index = 0; index < g_thread_count; index++) {
        watched_thread* thread = &g_threads[index];
        if (!thread->active) continue;

        int status = 0;
        pid_t waited =
                waitpid(
                        thread->tid,
                        &status,
                        __WALL | WNOHANG);
        int stopped =
                waited == thread->tid &&
                WIFSTOPPED(status);

        if (!stopped) {
            if (ptrace(
                    PTRACE_INTERRUPT,
                    thread->tid,
                    NULL,
                    NULL) == 0) {
                stopped =
                        wait_for_stop(
                                thread->tid,
                                750);
            } else if (errno == ESRCH) {
                thread->active = 0;
                continue;
            }
        }

        if (stopped) {
            (void)restore_watchpoint(thread);
            (void)ptrace(
                    PTRACE_DETACH,
                    thread->tid,
                    NULL,
                    NULL);
            thread->active = 0;
        }
    }
}

int main(int argc, char** argv) {
    puts(MODKIT_ROOT_WATCH_V1);
    fflush(stdout);
    (void)prctl(PR_SET_PDEATHSIG, SIGTERM);
    signal(SIGTERM, request_stop);
    signal(SIGINT, request_stop);

#if !defined(__aarch64__)
    puts("ERROR\tUNSUPPORTED_ABI\tHardware watch tracing currently requires arm64-v8a.");
    return 3;
#else
    if (argc != 6) {
        puts("ERROR\tUSAGE\tExpected: pid address width duration_ms max_events");
        return 2;
    }

    pid_t pid = 0;
    uint64_t address = 0ULL;
    int width = 0;
    int duration_ms = 0;
    int max_events = 0;
    if (!parse_pid(argv[1], &pid) ||
            !parse_u64_hex(argv[2], &address) ||
            !parse_int_bounded(argv[3], 1, 8, &width) ||
            !parse_int_bounded(argv[4], 250, 15000, &duration_ms) ||
            !parse_int_bounded(argv[5], 1, 10000, &max_events)) {
        puts("ERROR\tARGUMENT\tInvalid tracing arguments.");
        return 2;
    }
    if (!(width == 1 || width == 2 || width == 4 || width == 8)) {
        puts("ERROR\tWIDTH\tWatch width must be 1, 2, 4 or 8 bytes.");
        return 2;
    }

    uint64_t aligned = 0ULL;
    if (arm64_watch_ctrl(address, width, &aligned) == 0U) {
        puts("ERROR\tALIGNMENT\tThe watched value crosses one hardware watchpoint block.");
        return 2;
    }

    const int initial = enumerate_threads(pid, address, width);
    if (initial == -2) {
        puts("ERROR\tNO_HW_WATCH\tThe kernel exposes no free ARM64 hardware watchpoint.");
        cleanup_threads();
        return 5;
    }
    if (g_thread_count == 0) {
        printf("ERROR\tPTRACE\tCould not attach to target threads (errno=%d).\n", errno);
        cleanup_threads();
        return 4;
    }

    printf("INFO\tTHREADS\t%zu\n", g_thread_count);
    printf("INFO\tWATCH\t0x%" PRIx64 "\t%d\t%d\n",
            address,
            width,
            duration_ms);
    fflush(stdout);

    const int64_t started = monotonic_ms();
    int64_t last_refresh = started;

    while (!g_stop_requested &&
            monotonic_ms() - started < duration_ms &&
            g_total_traps < (uint32_t)max_events) {
        int status = 0;
        const pid_t tid = waitpid(-1, &status, __WALL | WNOHANG);
        if (tid == 0) {
            const int64_t now = monotonic_ms();
            if (now - last_refresh >= REFRESH_THREADS_MS) {
                (void)enumerate_threads(pid, address, width);
                last_refresh = now;
            }
            sleep_ms(2);
            continue;
        }
        if (tid < 0) {
            if (errno == EINTR) continue;
            if (errno == ECHILD) break;
            printf("ERROR\tWAIT\twaitpid failed (errno=%d).\n", errno);
            cleanup_threads();
            return 6;
        }

        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            mark_thread_dead(tid);
            continue;
        }
        if (!WIFSTOPPED(status)) continue;

        const int signal_number = WSTOPSIG(status);
        if (signal_number == SIGTRAP) {
            siginfo_t info;
            memset(&info, 0, sizeof(info));
            const int info_ok =
                    ptrace(PTRACE_GETSIGINFO, tid, NULL, &info) == 0;
            if (info_ok && info.si_code == TRAP_HWBKPT) {
                uint64_t pc = 0ULL;
                if (read_pc(tid, &pc)) {
                    record_hit(
                            tid,
                            pc,
                            (uint64_t)(uintptr_t)info.si_addr);
                }
                if (ptrace(PTRACE_CONT, tid, NULL, NULL) != 0) {
                    mark_thread_dead(tid);
                }
                continue;
            }
        }

        if (ptrace(
                PTRACE_CONT,
                tid,
                NULL,
                (void*)(uintptr_t)signal_number) != 0) {
            mark_thread_dead(tid);
        }
    }

    cleanup_threads();

    qsort(
            g_hits,
            g_hit_count,
            sizeof(g_hits[0]),
            compare_hits);

    for (size_t index = 0; index < g_hit_count; index++) {
        const watch_hit* hit = &g_hits[index];
        printf(
                "HIT\t0x%" PRIx64 "\t%u\t%d\t0x%" PRIx64 "\n",
                hit->pc,
                hit->count,
                hit->last_tid,
                hit->fault_address);
    }
    if (g_total_traps >= (uint32_t)max_events) {
        g_truncated = 1;
    }
    printf(
            "END\t%zu\t%u\t%d\n",
            g_hit_count,
            g_total_traps,
            g_truncated);
    fflush(stdout);
    return 0;
#endif
}
