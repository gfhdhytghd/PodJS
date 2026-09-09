#include "runtime_test.h"
#include "../../../../crates/podjs-runtime/include/podjs_runtime.h"
#include <string.h>

void *pod_test_runtime_open(const char *source, const char *manifest) {
    return pod_test_runtime_open_at(source, manifest, NULL);
}
void *pod_test_runtime_open_at(const char *source, const char *manifest, const char *data_dir) {
    PodRuntimeConfig config = {0};
    config.struct_size = sizeof(config); config.target_id = "watchos-watch";
    config.host_abi = PODJS_RUNTIME_ABI_VERSION; config.raster_density = 1;
    config.physical_width = 240; config.physical_height = 240;
    config.display_density = 1; config.capabilities_json = "[]";
    config.data_dir = data_dir;
    PodRuntime *runtime = pod_runtime_create(&config);
    if (!runtime) return NULL;
    if (pod_runtime_load_pak(runtime, (const uint8_t *)"pak", 3) != 0 ||
        pod_runtime_validate_package(runtime, manifest) != 0 ||
        pod_runtime_eval_bundle(runtime, (const uint8_t *)source, strlen(source), "test:///main.js") != 0) {
        pod_runtime_destroy(runtime); return NULL;
    }
    return runtime;
}
int32_t pod_test_runtime_frame(void *runtime) { return pod_runtime_frame(runtime, NULL); }
void pod_test_runtime_close(void *runtime) { pod_runtime_destroy(runtime); }
