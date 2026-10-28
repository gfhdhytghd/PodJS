#!/usr/bin/env bash
# Real host runtime only. No adb, production URL, account or application DB.
set -euo pipefail
cd "$(dirname "$0")/.."
cargo run -p podjs-runtime --example job-pump-soak --release -- "$@"
