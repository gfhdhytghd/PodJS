#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/test-tools/unit-chain.sh
source "$project_root/scripts/test-tools/unit-chain.sh"
test_dir="$(mktemp -d /tmp/podjs-background-store-XXXXXX)"
trap 'rm -f -- "$test_dir/napi/native_api.h" "$test_dir/store.node" "$test_dir/companion.node" "$test_dir/journal.cjs" "$test_dir/companion.cjs" "$test_dir/incoming-storage"; rmdir -- "$test_dir/napi" "$test_dir"' EXIT
mkdir "$test_dir/napi"
ln -s /usr/include/node/node_api.h "$test_dir/napi/native_api.h"
store_ready=false
if record_unit build store "${CXX:-clang++}" -std=c++17 -Wall -Wextra -fPIC -shared -pthread \
  -I/usr/include/node -I"$test_dir" -DNODE_GYP_MODULE_NAME=background_store_test \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-background-store-addon.cpp" -o "$test_dir/store.node"; then
    store_ready=true
    record_unit test store-api node "$project_root/tests/harmony-background-store.test.cjs" "$test_dir/store.node" || true
fi
if record_unit build journal bun build "$project_root/platforms/harmony/entry/src/main/ets/BackgroundJournal.ts" \
  --target=node --format=cjs --outfile "$test_dir/journal.cjs"; then
    if "$store_ready"; then
        record_unit test background-journal node "$project_root/tests/harmony-background-journal-native.test.cjs" "$test_dir/store.node" "$test_dir/journal.cjs" || true
    fi
fi
state_ready=false
if record_unit build state bun build "$project_root/tests/fixtures/companion-state-exports.ts" \
  --target=node --format=cjs --outfile "$test_dir/companion.cjs"; then
    state_ready=true
    if "$store_ready"; then
        record_unit test state-store node "$project_root/tests/harmony-companion-state-native.test.cjs" "$test_dir/store.node" "$test_dir/companion.cjs" || true
    fi
fi
if record_unit build companion "${CXX:-clang++}" -std=c++17 -Wall -Wextra -fPIC -shared -pthread \
  -I/usr/include/node -I"$test_dir" -DPODJS_FILE_STORAGE_HOST_TEST \
  "$project_root/platforms/harmony/companion/src/main/cpp/napi_init.cpp" -lcrypto -o "$test_dir/companion.node"; then
    if "$state_ready"; then
        record_unit test state-companion node "$project_root/tests/harmony-companion-state-native.test.cjs" "$test_dir/companion.node" "$test_dir/companion.cjs" companion-only || true
        record_unit test outbox node "$project_root/tests/harmony-companion-outbox-native.test.cjs" "$test_dir/companion.node" "$test_dir/companion.cjs" || true
    fi
    record_unit test pairing-lease node "$project_root/tests/harmony-companion-pairing-lease-native.test.cjs" "$test_dir/companion.node" || true
    if "$state_ready"; then
        record_unit test file-requests node "$project_root/tests/harmony-companion-file-requests-native.test.cjs" "$test_dir/companion.node" "$test_dir/companion.cjs" || true
        record_unit test incoming-napi node "$project_root/tests/harmony-incoming-file-napi.test.cjs" "$test_dir/companion.node" "$test_dir/companion.cjs" || true
    fi
fi
if record_unit build incoming-native "${CXX:-clang++}" -std=c++17 -Wall -Wextra "$project_root/tests/harmony-incoming-file-storage.test.cpp" -lcrypto -o "$test_dir/incoming-storage"; then
    record_unit test incoming-native "$test_dir/incoming-storage" || true
fi
exit "$PODJS_UNIT_FIRST_FAILURE"
