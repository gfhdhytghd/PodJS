#ifndef PODJS_APPLE_JOURNAL_H
#define PODJS_APPLE_JOURNAL_H
#include <stddef.h>
#include <stdint.h>
typedef struct PodAppleJournal PodAppleJournal;
PodAppleJournal *pod_apple_journal_open(const char *root, const char *lock_path);
PodAppleJournal *pod_apple_journal_open_bounded(const char *root, const char *lock_path, size_t limit);
/* read: 1 present (including empty), 0 absent, -1 error; caller frees bytes. */
int pod_apple_journal_read(PodAppleJournal *, uint8_t **bytes, size_t *length);
/* CAS: 1 durable commit, 0 conflict, -1 error (commit may be uncertain). */
int pod_apple_journal_cas(PodAppleJournal *, int expected_present, const uint8_t *expected,
    size_t expected_length, const uint8_t *desired, size_t desired_length);
void pod_apple_journal_free(uint8_t *bytes);
void pod_apple_journal_close(PodAppleJournal *);
#endif
