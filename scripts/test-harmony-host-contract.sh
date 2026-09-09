#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d /tmp/podjs-host-contract-XXXXXX)"
trap 'rm -f -- "$test_dir/contract-test"; rmdir -- "$test_dir"' EXIT
"${CXX:-clang++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined -g \
  -I"$project_root/crates/podjs-runtime/include" \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-host-contract.test.cpp" -o "$test_dir/contract-test"
"$test_dir/contract-test"
"${CXX:-clang++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined -g \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-guest-storage.test.cpp" -o "$test_dir/contract-test"
"$test_dir/contract-test"
"${CXX:-clang++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined -g \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-journal-storage.test.cpp" -o "$test_dir/contract-test"
"$test_dir/contract-test"
"${CXX:-clang++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined -g \
  -I"$project_root/crates/podjs-runtime/include" \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-runtime-events.test.cpp" -o "$test_dir/contract-test"
"$test_dir/contract-test"
