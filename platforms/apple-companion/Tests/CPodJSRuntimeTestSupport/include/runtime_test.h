#include <stdint.h>
void *pod_test_runtime_open(const char *source, const char *manifest);
void *pod_test_runtime_open_at(const char *source, const char *manifest, const char *data_dir);
int32_t pod_test_runtime_frame(void *runtime);
void pod_test_runtime_close(void *runtime);
