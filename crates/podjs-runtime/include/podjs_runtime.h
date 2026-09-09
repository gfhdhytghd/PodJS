#ifndef PODJS_RUNTIME_H
#define PODJS_RUNTIME_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
typedef struct PodSyncCancellation PodSyncCancellation;

#ifdef __cplusplus
extern "C" {
#endif
PodSyncCancellation *pod_sync_cancellation_new(void);
void pod_sync_cancellation_cancel(const PodSyncCancellation *value);
bool pod_sync_cancellation_is_cancelled(const PodSyncCancellation *value);
void pod_sync_cancellation_free(PodSyncCancellation *value);

#define PODJS_RUNTIME_ABI_VERSION 2
#define PODJS_RUNTIME_MIN_ABI_VERSION 1
/* ABI 2 adds host-only sync handles without changing ABI 1 struct layouts.
 * New runtimes accept host configs 1..2. Packages must request 1..host_abi;
 * the guest sees its requested ABI while the receipt reports both versions. */
#define PODJS_DEFAULT_LOGICAL_WIDTH 240
#define PODJS_DEFAULT_LOGICAL_HEIGHT 240
#define PODJS_MAX_TOUCHES 8

typedef struct PodRuntime PodRuntime;
typedef struct PodSyncFiles PodSyncFiles;
typedef struct PodSyncSession PodSyncSession;
typedef struct PodBackgroundRun PodBackgroundRun;
/* One-shot headless task; config JSON app_id/task_id/source/budget_ms/memory_bytes,
 * optional payload (JSON, at most 64 KiB when serialized) and allowed_methods.
 * Optional host-owned absolute kv_root enables native kv.get/set/delete/keys
 * dispatch instead of polling. All allowed methods must then be KV methods.
 * Host verifies bundle/manifest and method authorization first. Execute on worker;
 * poll/reply/cancel may be concurrent. Close only after all callers return.
 * Execute result JSON is borrowed until close. */
PodBackgroundRun *pod_background_open(const uint8_t *config, size_t length);
const char *pod_background_execute(const PodBackgroundRun *handle);
void pod_background_cancel(const PodBackgroundRun *handle);
/* One serialized poll consumer. Borrowed UTF-8 JSON until next poll/close; NULL
 * means no request. Envelope: id/appId/taskId/method/args/remainingMs.
 * Dispatch only authorized bounded IO; remainingMs is a budget, not wall time. */
const char *pod_background_poll(const PodBackgroundRun *handle);
/* Reply JSON {id,ok:true,value} or {id,ok:false,code}, max 70 KiB envelope.
 * Returns 0 accepted, -1 invalid, -2 unknown/finished. Never retry an accepted
 * response. Host must cancel IO on execution exit; committed writes remain. */
int32_t pod_background_reply(const PodBackgroundRun *handle, const uint8_t *bytes, size_t length);
void pod_background_close(PodBackgroundRun *handle);
/* Host-only authentication. Commands and close must be serialized. Host supplies
 * authenticated pairing key, fresh challenges and manifest-authorized channels.
 * Command response is borrowed until the next command/close. Never guest-expose. */
PodSyncSession *pod_sync_session_open(const uint8_t *config, size_t length);
const char *pod_sync_session_command(PodSyncSession *handle, const uint8_t *bytes, size_t length);
void pod_sync_session_close(PodSyncSession *handle);
/* Pure host state transform. NULL/0 snapshot means absent. Returned allocation
 * is owned; CAS the proposed snapshot before publishing receipts/results. */
char *pod_sync_state_calculate(const uint8_t *snapshot, size_t snapshot_length, const uint8_t *request, size_t request_length);
void pod_sync_state_response_free(char *response);
int32_t pod_sync_message_sha256(const uint8_t *bytes, size_t length, uint8_t *digest);
char *pod_sync_file_request_validate(const uint8_t *bytes, size_t length);
char *pod_sync_file_reply_validate(const uint8_t *request, size_t request_length, const uint8_t *reply, size_t reply_length);
void pod_sync_file_wire_free(char *response);
/* Optional host-only file receiver. Private per-app/per-peer root, serialized
 * IO worker access, authenticated/authorized peers. Command limit: 96 KiB.
 * JSON methods: offer(manifest), missing(transfer_id), chunk(transfer_id,index,
 * data_base64), finish(transfer_id), cancel(transfer_id). Manifest fields use
 * snake_case. Reply {ok,value} or {ok:false,code,message}; valid until next
 * command/close. Open returns NULL on error (pod_runtime_last_error).
 */
PodSyncFiles *pod_sync_files_open(const char *private_root);
const char *pod_sync_files_command(PodSyncFiles *handle, const uint8_t *bytes, size_t length);
void pod_sync_files_close(PodSyncFiles *handle);
typedef struct PodSyncFileSource PodSyncFileSource;
/* Host-approved source; retain descriptor across manifest scan and chunk reads.
 * Borrowed results must be copied before next read/close. No guest paths. */
PodSyncFileSource *pod_sync_file_source_open(const char *path, const char *transfer_id, const char *mime);
PodSyncFileSource *pod_sync_file_source_open_cancellable(const char *path, const char *transfer_id, const char *mime, const PodSyncCancellation *cancellation);
const char *pod_sync_file_source_manifest(const PodSyncFileSource *source);
bool pod_sync_file_source_check(const PodSyncFileSource *source);
const uint8_t *pod_sync_file_source_read(PodSyncFileSource *source, size_t index, size_t *length);
void pod_sync_file_source_close(PodSyncFileSource *source);

typedef enum PodLifecycleState {
  POD_LIFECYCLE_ACTIVE = 0,
  POD_LIFECYCLE_INACTIVE = 1,
  POD_LIFECYCLE_BACKGROUND = 2,
} PodLifecycleState;

typedef enum PodDisplayShape {
  POD_DISPLAY_ROUND = 0,
  POD_DISPLAY_RECT = 1,
} PodDisplayShape;

typedef struct PodRuntimeConfig {
  uint32_t struct_size;
  const char *target_id;
  uint32_t host_abi;
  uint32_t raster_density;
  uint32_t physical_width;
  uint32_t physical_height;
  float display_density;
  uint32_t display_shape;
  float safe_top;
  float safe_right;
  float safe_bottom;
  float safe_left;
  const char *data_dir;
  const char *capabilities_json;
} PodRuntimeConfig;

typedef struct PodTouch {
  uint32_t id;
  float x;
  float y;
} PodTouch;

typedef struct PodInputFrame {
  uint32_t struct_size;
  uint32_t buttons;
  uint32_t analog;
  const PodTouch *touches;
  uint32_t touch_count;
  int32_t rotary_primary_millidegrees;
  int32_t rotary_secondary_millidegrees;
} PodInputFrame;

typedef struct PodDrawList {
  const uint32_t *words;
  size_t word_count;
  uint64_t content_hash;
  uint64_t frame_number;
  int32_t changed;
} PodDrawList;

typedef struct PodAccessibilitySnapshot {
  const uint8_t *json;
  size_t byte_length;
  uint64_t content_hash;
  uint64_t frame_number;
  int32_t changed;
} PodAccessibilitySnapshot;

typedef struct PodTextureView {
  int32_t handle;
  uint64_t revision;
  const uint8_t *pixels;
  size_t byte_length;
  uint32_t width;
  uint32_t height;
  uint32_t pixel_format;
  const uint8_t *palette;
  size_t palette_length;
  int32_t linear;
} PodTextureView;

typedef struct PodFontView {
  uint32_t slot;
  uint32_t cell_width;
  uint32_t cell_height;
  uint32_t baseline;
  uint32_t line_height;
  uint32_t raster_density;
  uint32_t glyph_count;
  const uint8_t *bitmap;
  size_t bitmap_length;
} PodFontView;

uint32_t pod_runtime_abi_version(void);
/* Unix host file IO gate. All guest filesystem execution and host publication
 * for a runtime data root must share it. Busy must defer work, not bypass it. */
typedef struct PodGuestIo PodGuestIo;
PodGuestIo *pod_guest_io_open(const char *runtime_data_root);
int32_t pod_guest_io_try_enter(PodGuestIo *gate);
void pod_guest_io_leave(PodGuestIo *gate);
void pod_guest_io_close(PodGuestIo *gate);
/* Acquired gate; source is a host-private artifact, path is guest-relative.
 * No overwrite; 16 MiB guest files quota. Error can follow successful publish. */
int32_t pod_guest_publish(const PodGuestIo *gate, const char *source, const char *path, uint64_t size, const char *sha256);
int32_t pod_guest_publish_cancellable(const PodGuestIo *gate, const char *source, const char *path, uint64_t size, const char *sha256, const PodSyncCancellation *cancellation);
typedef struct PodAccessibilityText {
  const uint8_t *bytes;
  size_t byte_length;
} PodAccessibilityText;
typedef struct PodAccessibilityTree {
  size_t node_count;
  uint64_t content_hash;
  uint64_t frame_number;
} PodAccessibilityTree;
typedef struct PodAccessibilityNode {
  int32_t id, parent_id;
  /* text=0, button=1, image=2, header=3, link=4, checkbox=5, switch=6,
   * adjustable=7, list=8, listitem=9. */
  int32_t role;
  uint16_t state;
  uint8_t actions;
  int32_t left, top, right, bottom;
  PodAccessibilityText label, value, hint;
} PodAccessibilityNode;
/* Allocation-free committed tree reads. These do not consume JSON's changed
 * cursor: each native consumer compares its own hash. index is document order.
 * Text is length-delimited UTF-8, NOT NUL-terminated; null bytes means absent,
 * non-null plus length 0 means explicitly empty. Borrowed text remains valid
 * until the next pod_runtime_snapshot, set_accessibility_enabled or destruction.
 * Serialize these reads with all runtime mutations; copy text before unlocking. */
int32_t pod_runtime_accessibility_tree(PodRuntime *runtime, PodAccessibilityTree *out);
int32_t pod_runtime_accessibility_node(PodRuntime *runtime, size_t index, PodAccessibilityNode *out);
const char *pod_runtime_last_error(void);
PodRuntime *pod_runtime_create(const PodRuntimeConfig *config);
uint32_t pod_runtime_logical_width(const PodRuntime *runtime);
uint32_t pod_runtime_logical_height(const PodRuntime *runtime);
int32_t pod_runtime_load_pak(PodRuntime *runtime, const uint8_t *bytes, size_t length);
int32_t pod_runtime_validate_package(PodRuntime *runtime, const char *manifest_json);
int32_t pod_runtime_eval_bundle(PodRuntime *runtime, const uint8_t *source, size_t length,
                                const char *label);
int32_t pod_runtime_set_lifecycle(PodRuntime *runtime, uint32_t state);
int32_t pod_runtime_set_theme(PodRuntime *runtime, const char *theme);
/* External event admission: JSON object <= 1 MiB, at most 256 queued events and
 * 4 MiB total existing queue bytes. Returns -2 when full without enqueuing;
 * retain/retry after the guest drains events. Internal lifecycle/input events
 * share the queue but are not governed by this external-admission API. */
int32_t pod_runtime_post_event(PodRuntime *runtime, const char *json_object);
/* Host executor only. False until package validation and successful mounting,
 * and after a failed pre-mount revalidation. Mounted package authority is
 * immutable until runtime destruction. Never reads guest-supplied lists. */
bool pod_runtime_has_capability(PodRuntime *runtime, const char *name);
int32_t pod_runtime_frame(PodRuntime *runtime, const PodInputFrame *input);
int32_t pod_runtime_snapshot(PodRuntime *runtime, PodDrawList *out);
/* Opt in to primary-output semantics. Collection is off by default. */
int32_t pod_runtime_set_accessibility_enabled(PodRuntime *runtime, int32_t enabled);
/* Read the last draw-committed semantic tree without drawing or applying pending
 * mutations. UTF-8 JSON schema 1; bytes remain valid until the next query or
 * runtime destruction. changed reports a semantic-content hash change, not a
 * paint/frame change. bounds are clipped logical coordinates, ids retain their
 * native generation, parentId 0 denotes the virtual host root.
 * state: disabled=1, selected=2, checked=4, mixed=8, expanded=16, busy=32,
 * hasChecked=64, hasExpanded=128. actions: activate=1, increment=2, decrement=4. */
int32_t pod_runtime_accessibility_snapshot(PodRuntime *runtime, PodAccessibilitySnapshot *out);
/* Queue an action from this content_hash: 1 activate, 2 increment, 4 decrement.
 * Rejects stale snapshots, detached/deleted nodes and revoked actions.
 * Success means queued for guest delivery, not callback completion. */
int32_t pod_runtime_accessibility_action(PodRuntime *runtime, int32_t node_id,
                                       uint64_t content_hash, int32_t action);
/* Rasterize the current DrawList to tightly packed RGBA8. `scale` is 1..4;
 * length must equal logical_width * scale * logical_height * scale * 4. */
int32_t pod_runtime_render_rgba(PodRuntime *runtime, uint32_t scale,
                                uint8_t *pixels, size_t length);
/* Incremental equivalent for a persistent framebuffer. The first call draws a
 * complete frame; later calls repaint only damage regions when possible. */
int32_t pod_runtime_render_rgba_incremental(PodRuntime *runtime, uint32_t scale,
                                            uint8_t *pixels, size_t length);
/* Alpha-composited incremental RGBA8. The buffer is cleared transparent and
 * stores premultiplied RGB with source-over alpha. Other raster APIs retain
 * their opaque-black behavior. */
int32_t pod_runtime_render_rgba_transparent_incremental(
    PodRuntime *runtime, uint32_t scale, uint8_t *pixels, size_t length);
int32_t pod_runtime_texture(PodRuntime *runtime, uint32_t slot, PodTextureView *out);
/* Resolve a generation-tagged live texture handle. Returns 1 for a stale or
 * missing handle; unlike pod_runtime_texture this does not enumerate slots. */
int32_t pod_runtime_texture_for_handle(PodRuntime *runtime, int32_t handle,
                                       PodTextureView *out);
int32_t pod_runtime_font(PodRuntime *runtime, uint32_t slot, PodFontView *out);

/* Native host effects and networking. Returned strings are owned by the
 * runtime and remain valid until the next poll of the same kind. */
const char *pod_runtime_poll_effect(PodRuntime *runtime);
const char *pod_runtime_poll_net_command(PodRuntime *runtime);
int32_t pod_runtime_complete_http(PodRuntime *runtime, int32_t handle, uint32_t status,
                                  const char *url, const char *headers_json,
                                  const uint8_t *body, size_t body_length);
int32_t pod_runtime_fail_http(PodRuntime *runtime, int32_t handle,
                              const char *code, const char *message);

const char *pod_runtime_receipt(PodRuntime *runtime);
void pod_runtime_destroy(PodRuntime *runtime);

#ifdef __cplusplus
}
#endif

#endif
