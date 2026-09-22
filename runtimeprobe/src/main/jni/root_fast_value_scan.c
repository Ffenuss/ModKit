#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define MARKER "MODKIT_ROOT_FAST_SCAN_V1"
#define MAX_REGIONS 4096
#define CHUNK_BYTES (2 * 1024 * 1024)
#define QUICK_REGION_CAP (16ULL * 1024ULL * 1024ULL)

typedef struct {
    uint64_t start;
    uint64_t end;
    uint64_t file_offset;
    int priority;
    char path[512];
} region;

static int compare_region(const void* left, const void* right) {
    const region* a = (const region*)left;
    const region* b = (const region*)right;
    if (a->priority != b->priority) return a->priority - b->priority;
    if (a->start < b->start) return -1;
    if (a->start > b->start) return 1;
    return 0;
}

static int path_noise(const char* path) {
    if (path == NULL || *path == '\0') return 0;
    return strncmp(path, "[stack", 6) == 0 ||
           strstr(path, "jit-cache") != NULL ||
           strstr(path, "dalvik-jit") != NULL ||
           strstr(path, "gralloc") != NULL ||
           strstr(path, "kgsl") != NULL ||
           strstr(path, "dmabuf") != NULL ||
           strncmp(path, "/dev/", 5) == 0;
}

static int priority_for_path(const char* path) {
    if (path == NULL || *path == '\0') return 0;
    if (strcmp(path, "[heap]") == 0) return 0;
    if (strncmp(path, "[anon:", 6) == 0) return 0;
    if (strstr(path, "libil2cpp") != NULL) return 1;
    if (strncmp(path, "/data/app/", 10) == 0) return 1;
    if (strncmp(path, "/data/user/", 11) == 0) return 1;
    return 2;
}

static int parse_positive_int(const char* text, int* out) {
    char* end = NULL;
    errno = 0;
    long value = strtol(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' || value <= 0 || value > (1L << 30)) {
        return 0;
    }
    *out = (int)value;
    return 1;
}

static int parse_u64(const char* text, uint64_t* out) {
    char* end = NULL;
    errno = 0;
    unsigned long long value = strtoull(text, &end, 0);
    if (errno != 0 || end == text || *end != '\0') return 0;
    *out = (uint64_t)value;
    return 1;
}

static uint64_t load_le(const unsigned char* p, int width) {
    uint64_t value = 0;
    for (int i = 0; i < width; i++) {
        value |= ((uint64_t)p[i]) << (i * 8);
    }
    return value;
}

int main(int argc, char** argv) {
    puts(MARKER);
    fflush(stdout);

    if (argc != 6) {
        puts("ERROR\tUSAGE\tExpected: pid width bits max_bytes max_hits");
        return 2;
    }

    int pid = 0;
    int width = 0;
    int max_hits = 0;
    uint64_t wanted = 0;
    uint64_t max_bytes = 0;
    if (!parse_positive_int(argv[1], &pid) ||
        !parse_positive_int(argv[2], &width) ||
        !parse_u64(argv[3], &wanted) ||
        !parse_u64(argv[4], &max_bytes) ||
        !parse_positive_int(argv[5], &max_hits)) {
        puts("ERROR\tARGUMENT\tInvalid scan arguments.");
        return 2;
    }
    if (!(width == 4 || width == 8)) {
        puts("ERROR\tWIDTH\tOnly 4-byte and 8-byte values are supported.");
        return 2;
    }
    if (max_hits > 50000) max_hits = 50000;

    char maps_path[64];
    char mem_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    snprintf(mem_path, sizeof(mem_path), "/proc/%d/mem", pid);

    FILE* maps = fopen(maps_path, "r");
    if (maps == NULL) {
        printf("ERROR\tMAPS\tCould not open maps errno=%d\n", errno);
        return 3;
    }

    region regions[MAX_REGIONS];
    size_t region_count = 0;
    char line[2048];
    while (fgets(line, sizeof(line), maps) != NULL && region_count < MAX_REGIONS) {
        unsigned long long start = 0;
        unsigned long long end = 0;
        unsigned long long file_offset = 0;
        char perms[8] = {0};
        char path[512] = {0};
        int fields = sscanf(
            line,
            "%llx-%llx %7s %llx %*s %*s %511[^\n]",
            &start,
            &end,
            perms,
            &file_offset,
            path);
        if (fields < 4 || start >= end) continue;
        if (perms[0] != 'r' || perms[1] != 'w' || perms[3] != 'p') continue;

        char* normalized = path;
        while (*normalized == ' ' || *normalized == '\t') normalized++;
        if (path_noise(normalized)) continue;

        region* out = &regions[region_count++];
        out->start = (uint64_t)start;
        out->end = (uint64_t)end;
        out->file_offset = (uint64_t)file_offset;
        out->priority = priority_for_path(normalized);
        snprintf(out->path, sizeof(out->path), "%s", normalized);
    }
    fclose(maps);

    if (region_count == 0) {
        puts("ERROR\tRANGES\tNo writable private ranges.");
        return 4;
    }
    qsort(regions, region_count, sizeof(region), compare_region);

    int mem = open(mem_path, O_RDONLY | O_CLOEXEC);
    if (mem < 0) {
        printf("ERROR\tMEM\tCould not open process memory errno=%d\n", errno);
        return 5;
    }

    unsigned char* buffer = (unsigned char*)malloc(CHUNK_BYTES + 8);
    if (buffer == NULL) {
        close(mem);
        puts("ERROR\tALLOC\tCould not allocate scan buffer.");
        return 6;
    }

    uint64_t scanned = 0;
    int scanned_regions = 0;
    int hits = 0;
    int truncated = 0;

    for (size_t r = 0; r < region_count && hits < max_hits; r++) {
        region* current = &regions[r];
        uint64_t region_size = current->end - current->start;
        uint64_t region_budget = region_size;
        if (max_bytes != 0 && region_budget > QUICK_REGION_CAP) {
            region_budget = QUICK_REGION_CAP;
        }
        if (max_bytes != 0) {
            if (scanned >= max_bytes) {
                truncated = 1;
                break;
            }
            uint64_t remaining = max_bytes - scanned;
            if (region_budget > remaining) region_budget = remaining;
        }
        if (region_budget < (uint64_t)width) continue;

        uint64_t offset_in_region = 0;
        int touched = 0;
        while (offset_in_region + (uint64_t)width <= region_budget && hits < max_hits) {
            size_t request = CHUNK_BYTES;
            uint64_t remain = region_budget - offset_in_region;
            if ((uint64_t)request > remain) request = (size_t)remain;
            if (request < (size_t)width) break;

            uint64_t address = current->start + offset_in_region;
            ssize_t got = pread(mem, buffer, request, (off_t)address);
            if (got <= 0) {
                offset_in_region += 4096;
                continue;
            }
            touched = 1;
            size_t usable = (size_t)got;
            size_t start_index = 0;
            uint64_t misalignment = address % (uint64_t)width;
            if (misalignment != 0) {
                start_index = (size_t)((uint64_t)width - misalignment);
            }
            for (size_t i = start_index; i + (size_t)width <= usable; i += (size_t)width) {
                uint64_t bits = load_le(buffer + i, width);
                uint64_t mask = width == 4 ? 0xffffffffULL : UINT64_MAX;
                if ((bits & mask) == (wanted & mask)) {
                    printf("HIT\t0x%" PRIx64 "\t0x%" PRIx64 "\n", address + i, bits & mask);
                    hits++;
                    if (hits >= max_hits) {
                        truncated = 1;
                        break;
                    }
                }
            }
            scanned += (uint64_t)got;
            offset_in_region += (uint64_t)got;
            if (max_bytes != 0 && scanned >= max_bytes) {
                truncated = 1;
                break;
            }
        }
        if (touched) scanned_regions++;
        if (max_bytes != 0 && scanned >= max_bytes) break;
    }

    free(buffer);
    close(mem);

    printf("END\t%d\t%" PRIu64 "\t%d\t%d\n", hits, scanned, scanned_regions, truncated);
    fflush(stdout);
    return 0;
}
