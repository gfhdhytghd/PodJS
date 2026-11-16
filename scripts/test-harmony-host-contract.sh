#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/test-tools/unit-chain.sh
source "$project_root/scripts/test-tools/unit-chain.sh"
test_dir="$(mktemp -d /tmp/podjs-host-contract-XXXXXX)"
trap 'rm -f -- "$test_dir/contract-test"; rmdir -- "$test_dir"' EXIT
if record_unit build contract "${CXX:-clang++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined -g \
  -I"$project_root/crates/podjs-runtime/include" \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-host-contract.test.cpp" -o "$test_dir/contract-test"; then
    record_unit test contract "$test_dir/contract-test" || true
fi
if record_unit build guest-storage "${CXX:-clang++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined -g \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-guest-storage.test.cpp" -o "$test_dir/contract-test"; then
    record_unit test guest-storage "$test_dir/contract-test" || true
fi
if record_unit build journal-storage "${CXX:-clang++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined -g \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-journal-storage.test.cpp" -o "$test_dir/contract-test"; then
    record_unit test journal-storage "$test_dir/contract-test" || true
fi
if record_unit build runtime-events "${CXX:-clang++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined -g \
  -I"$project_root/crates/podjs-runtime/include" \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-runtime-events.test.cpp" -o "$test_dir/contract-test"; then
    record_unit test runtime-events "$test_dir/contract-test" || true
fi
exit "$PODJS_UNIT_FIRST_FAILURE"
