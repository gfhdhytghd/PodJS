#define _GNU_SOURCE 1
#include "podjs_journal.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#define LIMIT (18u * 1024u * 1024u)
struct PodAppleJournal { int root; int lock; size_t limit; };
static int private_file(int fd, struct stat *st) {
  return fstat(fd, st) == 0 && S_ISREG(st->st_mode) && st->st_nlink == 1 &&
    st->st_uid == geteuid() && (st->st_mode & 077) == 0 && st->st_size >= 0 && st->st_size <= LIMIT;
}
static int remove_staging(int root) {
  int fd = openat(root, "snapshot.tmp", O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
  if (fd < 0) return errno == ENOENT ? 0 : -1;
  struct stat st; int valid = private_file(fd, &st); close(fd);
  if (!valid) return -1;
  return unlinkat(root, "snapshot.tmp", 0);
}
PodAppleJournal *pod_apple_journal_open(const char *root, const char *lock_path) {
  return pod_apple_journal_open_bounded(root, lock_path, 4u * 1024u * 1024u);
}
PodAppleJournal *pod_apple_journal_open_bounded(const char *root, const char *lock_path, size_t limit) {
  if (!root || !lock_path || root[0] != '/' || !root[1] || limit == 0 || limit > LIMIT) return NULL;
  int lease = open(lock_path, O_RDWR | O_CREAT | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC, 0600);
  if (lease < 0) return NULL;
  struct stat st;
  if (!private_file(lease, &st) || flock(lease, LOCK_EX | LOCK_NB) != 0) { close(lease); return NULL; }
  if (mkdir(root, 0700) != 0 && errno != EEXIST) { close(lease); return NULL; }
  int directory = open(root, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
  if (directory < 0) { close(lease); return NULL; }
  char *parent_path = strdup(root);
  if (!parent_path) { close(directory); close(lease); return NULL; }
  size_t path_length = strlen(parent_path);
  while (path_length > 1 && parent_path[path_length - 1] == '/') parent_path[--path_length] = 0;
  char *slash = strrchr(parent_path, '/');
  if (slash == parent_path) slash[1] = 0; else *slash = 0;
  int parent = open(parent_path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); free(parent_path);
  if (parent < 0) { close(directory); close(lease); return NULL; }
  int parent_synced = fsync(parent); close(parent);
  if (fstat(directory, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != geteuid() || (st.st_mode & 077) != 0 ||
      parent_synced != 0 || remove_staging(directory) != 0 || fsync(directory) != 0) { close(directory); close(lease); return NULL; }
  PodAppleJournal *value = malloc(sizeof(*value));
  if (!value) { close(directory); close(lease); return NULL; }
  value->root = directory; value->lock = lease; value->limit = limit; return value;
}
int pod_apple_journal_read(PodAppleJournal *value, uint8_t **bytes, size_t *length) {
  if (!value || !bytes || !length) return -1;
  *bytes = NULL; *length = 0;
  int fd = openat(value->root, "snapshot", O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
  if (fd < 0) return errno == ENOENT ? 0 : -1;
  struct stat st;
  if (!private_file(fd, &st) || (size_t)st.st_size > value->limit) { close(fd); return -1; }
  size_t size = (size_t)st.st_size, offset = 0;
  uint8_t *result = malloc(size ? size : 1);
  if (!result) { close(fd); return -1; }
  while (offset < size) {
    ssize_t count = read(fd, result + offset, size - offset);
    if (count < 0 && errno == EINTR) continue;
    if (count <= 0) { free(result); close(fd); return -1; }
    offset += (size_t)count;
  }
  uint8_t extra; ssize_t count;
  do { count = read(fd, &extra, 1); } while (count < 0 && errno == EINTR);
  close(fd);
  if (count != 0) { free(result); return -1; }
  *bytes = result; *length = size; return 1;
}
int pod_apple_journal_cas(PodAppleJournal *value, int present, const uint8_t *expected,
    size_t expected_length, const uint8_t *desired, size_t desired_length) {
  if (!value || expected_length > value->limit || desired_length > value->limit ||
      (expected_length && !expected) || (desired_length && !desired)) return -1;
  uint8_t *current = NULL; size_t length = 0;
  int found = pod_apple_journal_read(value, &current, &length);
  if (found < 0) return -1;
  int matches = found == (present ? 1 : 0) && (!found || (length == expected_length &&
      (!length || memcmp(current, expected, length) == 0)));
  free(current); if (!matches) return 0;
  if (remove_staging(value->root) != 0) return -1;
  int fd = openat(value->root, "snapshot.tmp", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
  if (fd < 0) return -1;
  size_t offset = 0;
  while (offset < desired_length) {
    ssize_t count = write(fd, desired + offset, desired_length - offset);
    if (count < 0 && errno == EINTR) continue;
    if (count <= 0) { close(fd); return -1; }
    offset += (size_t)count;
  }
  int synced = fsync(fd); close(fd); if (synced != 0) return -1;
  if (renameat(value->root, "snapshot.tmp", value->root, "snapshot") != 0 || fsync(value->root) != 0) return -1;
  return 1;
}
void pod_apple_journal_free(uint8_t *bytes) { free(bytes); }
void pod_apple_journal_close(PodAppleJournal *value) {
  if (!value) return;
  close(value->root); close(value->lock); free(value);
}
