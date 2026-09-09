#ifndef PODJS_APPLE_SYNC_H
#define PODJS_APPLE_SYNC_H
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
typedef struct PodSyncCancellation PodSyncCancellation;
PodSyncCancellation *pod_sync_cancellation_new(void);
void pod_sync_cancellation_cancel(const PodSyncCancellation *value);
bool pod_sync_cancellation_is_cancelled(const PodSyncCancellation *value);
void pod_sync_cancellation_free(PodSyncCancellation *value);
/* ABI 2 subset of crates/podjs-runtime/include/podjs_runtime.h.
 * The embedding app links its platform slice of libpodjs_runtime. */
typedef struct PodSyncSession PodSyncSession;
uint32_t pod_runtime_abi_version(void);
typedef struct PodRuntime PodRuntime;
const char *pod_runtime_poll_effect(PodRuntime *runtime);
int32_t pod_runtime_post_event(PodRuntime *runtime, const char *json_object);
bool pod_runtime_has_capability(PodRuntime *runtime, const char *name);
typedef struct PodGuestIo PodGuestIo;
PodGuestIo *pod_guest_io_open(const char *runtime_data_root);
int32_t pod_guest_io_try_enter(PodGuestIo *gate);
void pod_guest_io_leave(PodGuestIo *gate);
void pod_guest_io_close(PodGuestIo *gate);
int32_t pod_guest_publish(const PodGuestIo *gate, const char *source, const char *path, uint64_t size, const char *sha256);
int32_t pod_guest_publish_cancellable(const PodGuestIo *gate, const char *source, const char *path, uint64_t size, const char *sha256, const PodSyncCancellation *cancellation);
PodSyncSession *pod_sync_session_open(const uint8_t *config, size_t length);
const char *pod_sync_session_command(PodSyncSession *handle, const uint8_t *bytes, size_t length);
void pod_sync_session_close(PodSyncSession *handle);
char *pod_sync_state_calculate(const uint8_t *snapshot, size_t snapshot_length, const uint8_t *request, size_t request_length);
void pod_sync_state_response_free(char *response);
int32_t pod_sync_message_sha256(const uint8_t *bytes, size_t length, uint8_t *digest);
char *pod_sync_file_request_validate(const uint8_t *bytes, size_t length);
char *pod_sync_file_reply_validate(const uint8_t *request, size_t request_length, const uint8_t *reply, size_t reply_length);
void pod_sync_file_wire_free(char *response);
typedef struct PodSyncFiles PodSyncFiles;
PodSyncFiles *pod_sync_files_open(const char *private_root);
const char *pod_sync_files_command(PodSyncFiles *handle, const uint8_t *bytes, size_t length);
void pod_sync_files_close(PodSyncFiles *handle);
typedef struct PodSyncFileSource PodSyncFileSource;
PodSyncFileSource *pod_sync_file_source_open(const char *path, const char *transfer_id, const char *mime);
PodSyncFileSource *pod_sync_file_source_open_cancellable(const char *path, const char *transfer_id, const char *mime, const PodSyncCancellation *cancellation);
const char *pod_sync_file_source_manifest(const PodSyncFileSource *source);
bool pod_sync_file_source_check(const PodSyncFileSource *source);
const uint8_t *pod_sync_file_source_read(PodSyncFileSource *source, size_t index, size_t *length);
void pod_sync_file_source_close(PodSyncFileSource *source);
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>
/* Sibling lock file stays in place: unlinking a held lock would split owners. */
static inline int pod_apple_sync_file_lease_open(const char *path) {
  int fd = open(path, O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
  if (fd < 0) return -1;
  struct stat st;
  if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_nlink != 1 ||
      st.st_uid != geteuid() || (st.st_mode & 077) != 0 || flock(fd, LOCK_EX | LOCK_NB) != 0) {
    close(fd); return -1;
  }
  return fd;
}
static inline void pod_apple_sync_file_lease_close(int fd) { if (fd >= 0) close(fd); }
#endif
