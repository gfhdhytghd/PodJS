/* Offline QuickJS 0.12.2 bytecode phase probe. Not linked into PodJS.
 * Includes the pinned engine to measure the internal global-function closure
 * step separately. It deliberately does NOT execute a PodJS bundle without its
 * host: use the Rust production-runtime harness / Android PodJSPerf for that.
 * profiled_read follows JS_ReadObject2 (including its checksum and atom cleanup).
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <zlib.h>
#include "quickjs.c"

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

static JSValue profiled_read(JSContext *ctx, const uint8_t *buf, size_t len,
                             double *atoms_ms, unsigned *atom_count) {
    BCReaderState state, *s = &state;
    memset(s, 0, sizeof(*s));
    ctx->binary_object_count++;
    ctx->binary_object_size += len;
    s->ctx = ctx;
    s->buf_start = s->ptr = buf;
    s->buf_end = buf + len;
    s->allow_bytecode = 1;
    s->first_atom = JS_ATOM_END;
    double began = now_ms();
    int failed = JS_ReadObjectAtoms(s);
    *atoms_ms = now_ms() - began; /* includes built-in bytecode checksum */
    *atom_count = s->idx_to_atom_count;
    JSValue obj = failed ? JS_EXCEPTION : JS_ReadObjectRec(s);
    js_free(ctx, s->sab_tab);
    bc_reader_free(s);
    return obj;
}

static int error(JSContext *ctx, const char *stage) {
    JSValue exception = JS_GetException(ctx);
    const char *text = JS_ToCString(ctx, exception);
    fprintf(stderr, "%s: %s\n", stage, text ? text : "unknown");
    JS_FreeCString(ctx, text);
    JS_FreeValue(ctx, exception);
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 2 || argc > 4) {
        fprintf(stderr, "usage: profile-bytecode main.qbc [iterations] [raw-deflate-file]\n");
        return 2;
    }
    int iterations = argc > 2 ? atoi(argv[2]) : 11;
    if (iterations < 1 || iterations > 25) return 2;
    struct stat file;
    if (stat(argv[1], &file) || file.st_size < 5) return 2;
    for (int i = 0; i < iterations; i++) {
        const char *path = argc == 4 ? argv[3] : argv[1];
        double began = now_ms();
        int fd = open(path, O_RDONLY);
        struct stat input;
        if (fd < 0 || fstat(fd, &input) || input.st_size <= 0) return 2;
        uint8_t *mapping = mmap(NULL, input.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (mapping == MAP_FAILED) return 2;
        uint8_t *bytes = mapping;
        if (argc == 4) {
            bytes = malloc(file.st_size);
            if (!bytes) return 2;
            z_stream z = {0};
            z.next_in = mapping;
            z.avail_in = input.st_size;
            z.next_out = bytes;
            z.avail_out = file.st_size;
            if (inflateInit2(&z, -15) != Z_OK) return 2;
            int result = inflate(&z, Z_FINISH);
            int valid = result == Z_STREAM_END && z.total_out == (size_t)file.st_size;
            inflateEnd(&z);
            if (!valid) return 2;
        }
        double asset_ms = now_ms() - began;
        JSRuntime *rt = JS_NewRuntime();
        JSContext *ctx = JS_NewContext(rt);
        if (!rt || !ctx) return 2;
        JS_SetMaxStackSize(rt, 2 * 1024 * 1024);
        double atoms_ms = 0;
        unsigned atom_count = 0;
        began = now_ms();
        JSValue compiled = profiled_read(ctx, bytes, file.st_size, &atoms_ms, &atom_count);
        double read_ms = now_ms() - began;
        if (JS_IsException(compiled)) return error(ctx, "read");
        if (JS_VALUE_GET_TAG(compiled) != JS_TAG_FUNCTION_BYTECODE) return 2;
        began = now_ms();
        JSValue function = js_closure(ctx, compiled, NULL, NULL); /* consumes compiled */
        double instantiate_ms = now_ms() - began;
        if (JS_IsException(function)) return error(ctx, "instantiate");
        JSMemoryUsage memory;
        JS_ComputeMemoryUsage(rt, &memory);
        printf("K7_READ iter=%d mode=%s bytes=%lld asset_ms=%.3f read_object_ms=%.3f checksum_atoms_ms=%.3f instantiate_ms=%.3f atoms=%u functions=%lld code_bytes=%lld\n",
            i+1, argc == 4 ? "deflate" : "stored", (long long)file.st_size,
            asset_ms, read_ms, atoms_ms, instantiate_ms, atom_count,
            (long long)memory.js_func_count, (long long)memory.js_func_code_size);
        JS_FreeValue(ctx, function);
        JS_FreeContext(ctx);
        JS_FreeRuntime(rt);
        if (bytes != mapping) free(bytes);
        munmap(mapping, input.st_size);
    }
    return 0;
}
