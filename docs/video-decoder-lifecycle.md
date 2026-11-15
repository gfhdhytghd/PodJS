# Video decoder admission and memory integration

`PodVideoCodecGate.PROCESS` is the process-wide **video** reservation gate.
Audio Media3 adapters do not consume its three video slots. An opaque audio-API
MediaPlayer container conservatively reserves one video slot because it may
contain video. These values are not a count of all audio/video MediaCodec objects.

Memory accounting must use `observe(LifecycleListener)` and the same immutable
`Allocation` through its reserved and released events. Subscription replays live
allocations before subsequent events. Unsubscription is idempotent.

- `id` uniquely identifies a reservation, including failed construction.
- `owner` is the exact object registered with the reclaim registry for session
  players and previews. It must not be replaced with a second player registry.
- `generation` is captured for each session/player construction. Non-session
  opaque operations without a source generation use zero; their `id` remains
  unique. Local inventory covers preserve their job's source generation.
- `priority` describes admission time, not the current player role. Consumers
  must request `requestReclaim(keep, priority)`, which uses the main looper and
  rechecks the owner's current role. `CURRENT` and `keep` are always protected.
- `released` means the underlying codec/retriever release completed, or a failed
  construction rolled back its reservation. It is not emitted on logical
  session retirement or when a release is merely requested.

Callbacks run synchronously with the gate's bookkeeping. They must do bounded,
nonblocking accounting, must not call player APIs, and must not acquire a lock
that is held while calling back into the gate. Listener exceptions cannot deny
codec admission or corrupt its count. Before-allocation byte admission remains
an explicit memory-budget operation; this observer does not implement a PSS cap.

Move one allocation ID from reserved to resident without creating a second
charge. Actual PSS must not be summed with the entire resident ledger again.
Audio memory needs its own byte accounting, even though it does not consume a
video gate slot. Codec release does not release the owner's GPU textures or
pending fences; those keep their separate physical-resource lifetimes.

The swipe fix removes PodVideoLastFrame. Do not restore that class, Bitmap,
ImageView, or a memory-budget entry for it when combining the patches. Decoder
concurrency does not account for all Graphics memory and does not prove a
128 MB process-PSS limit. The memory budget must never directly close the active
player to meet that limit.
