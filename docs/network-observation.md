# Network observation

`discovery.networkSnapshot()` reports the active default route and separate local Wi-Fi availability.
`onNetworkChange()` owns and releases both native callbacks with its subscription. Internet consumers
must require `active && internet && validated`, prefer Wi-Fi/cellular, and distinguish Bluetooth.
A local Wi-Fi observation alone does not establish internet access.

Debuggable Android hosts also observe the global setting `podjs.debug.internet_availability`.
Accepted values are `checking`, `unavailable` (`offline`, `none`), `bluetooth` (`bt`),
and `available` (`online`, `wifi`, `cellular`, `mobile`). Other values remove the override.
Snapshots include the optional `internetStatusOverride` only when enabled. Actual route fields,
including `localWifi`, remain unchanged. Release hosts neither read nor observe this setting.
Changing this setting emits the existing `default/capabilities` notification. Unsubscribe/host close
also unregisters the content observer. This capability only observes status; it changes no network.

Android snapshots carry a host-local, increasing `snapshotSequence` allocated in capture order.
A consumer combining observed and on-demand snapshots should discard results older than
its latest accepted capture. Older hosts can omit the field.
