# Local test gate

`./test-gate` in this SDK runs the complete existing default mixed unit chain,
companion host units, the full Android runtime JVM suite, and the independent
host regression scripts listed in `scripts/unit-commands.json`. The app's
`./test-gate` runs this SDK gate followed by the app's complete Bun suite,
offline Python units/selftest and lint. Each repository has its own lock and
runs sequentially under `capmem 8G`. No device or release matrix is started.

The first nonzero exit code in manifest order is returned. All remaining unit
entries and lint still run. Each entry keeps its unedited log and Bun/JUnit XML.
`timings.json` records queue/setup, command wall time and build/test breakdown;
`compile.jsonl` records compiler and Gradle task times. Build and test times in
parallel children are accumulated durations, not additive wall time.

Options: `--repo-only`, `--tests-only`, `--lint-only`, `--workers 1|2`,
`--output /absolute/evidence/path`, `--fresh`. `--fresh` bypasses native/Java and
immutable fixture caches. Gradle test actions always run; dependency builds stay
incremental. There is no unconditional `--rerun-tasks`. Existing scale switches
and all assertions remain unchanged.

A dedicated Gradle daemon registry is inside `.cache/test-gate/gradle-home`.
The local Gradle build cache stays in `.cache/test-gate/gradle-build-cache`.
Dependency caches and distributions reuse the user's configured Gradle home.
The daemon has a 2 GiB heap, JVM tests have a 512 MiB heap and one fork, and
Gradle has at most two workers. Every daemon is stopped in `finally` before the
capmem scope ends. The registry is never shared with ordinary Gradle sessions.
Delete `.cache/test-gate` after a run to reclaim test caches and reports.

Run `bun install --frozen-lockfile` in the SDK, PocketJS submodule and app before
using a fresh worktree. Run `bun scripts/link-pocket.ts` in the SDK after every
install; the gate also restores these package links during setup. PocketJS host imports also need its ignored generated
`framework/src/styles.generated.ts` mirror from the matching build setup.
Keep the same mirror and external reference fixtures for comparisons.
App external paths use the documented `WRSS_SYNCV2_SPEC_ROOT`,
`PODJS_SYNC_VECTORS_DIR` and `ANDROID_WATCHRSS_ROOT` variables.

Java compiler cache keys include explicit/implicit owned sources, JDK modules,
compiler identity, flags, classpath content and environment compiler options.
Native keys additionally include compiler-discovered transitive/system headers
and linked libraries. Successful compiler output is copied into a private
`mktemp` destination. Only `.class` files and native binaries are cached; test
results, mutable files, databases produced by assertions, and reports are not.
The app's separate immutable seed cache copies database seeds, bytes and bundles
into private test roots; fresh-schema, first-publication, migration and encoder
regressions continue executing their product actions.

Lint checks only; it never fixes files or creates a warning baseline. ESLint
uses content caching. Android lint, Clippy, Ruff, ShellCheck and clang-tidy remain
blocking. Missing tools or native compile contexts fail the gate. Inspect lint
logs before publishing; existing failures must be assigned to the coordinator.

Swift host units use the pinned, already-cached Swift 6.0.3 image filesystem.
Docker exports a stopped SDK; no test runs in the Docker daemon cgroup.
An unprivileged user/mount namespace runs with read-only source binds,
dropped capabilities, two Swift build jobs and the caller capmem cgroup.
The SDK and incremental Swift scratch files stay in the reclaimable cache.
No physical device nodes are bound into that namespace.

Swift scratch paths include the external Rust archive content hash. SwiftPM
tracks its package sources and flags; a Rust-only change therefore also forces
a new native link rather than reusing an old XCTest binary.

The notebook model/data/resources unit children of `test:scaffold` run as
explicit manifest entries. Scaffold install/package roundtrips stay outside
the unit gate. Unknown owned unit files are discovered across the repository.

Setup restores the notebook runtime links with its existing link script.
Cargo uses `--no-fail-fast` to collect all workspace binaries on failures.
Recorded build/setup durations cover instrumented helper/Gradle/fixture work;
the test command remainder includes uninstrumented startup and Cargo/Swift
compilation. It is an envelope, not a pure test CPU duration.
App `--repo-only` is labelled `app-repo-checks`; it is a diagnostic scope, not
the complete framework-plus-app gate.

The Harmony host-contract and background-store chains retain their first
nonzero exit and continue independent programs after failures. Dependent
programs only run after successful fixture builds. Original test sources,
compiler flags and assertions are unchanged. `native-chain.jsonl` records
per-program build/test duration and status. Each child has an 1800s watchdog.

The complete app Bun invocation uses a 30-second default watchdog, as approved
for physical-disk host tests. Explicit per-test deadlines and product timing
assertions still apply. This is a command argument only: direct `bun test` and
targeted diagnostic invocations retain Bun's original 5-second default.
