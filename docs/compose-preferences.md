# Compose 1.3.3-5 preference migration contract

Frozen source: `bb2c8e7b288d8572032bc20bbf07897cc8badcc9`. 94 exact keys or bounded key families.

SharedPreferences: `shared_prefs/<store>.xml`. Library-scoped stores use `account_<64-hex scope>_<store>.xml` when not the legacy owner. AES-SIV AAD is the original logical name passed to AndroidX, without the account prefix. AccountDataContext prefixes only the actual SharedPreferences filename. DataStores use the double suffix shown in the JSON contract. Empty encrypted platform accounts contain only Tink keysets and migrate as empty accounts.

The machine-readable contract is `platforms/android/runtime/src/main/resources/dev/podjs/runtime/compose-preferences.json`. Run `python scripts/verify-compose-preferences.py --source <frozen Compose checkout>`. Any new writer or modified reviewed writer fails this gate. Runtime tests exercise every row through the actual mappings/policy and reject unknown keys.

## Workspace selection

Compute Compose SHA-256 of `<environment>:user:<UTF-16 length>:<userId>` (or `unbound:guest`). `watchrss_library_ownership.legacy_owner` chooses root versus `databases/accounts/<scope>/watchrss.db`. Account identity and ownership are captured from fixed preference snapshots before opening Room. Only that workspace is imported. Other workspaces remain byte-identical in the sealed NativeBackup and fixed source snapshots. No IDs are merged; no login, refresh, cloud registration, platform request or server ACK is invented. The selected account state and PodJS `watchrss.library.owner` bind the imported library. Missing/decryption-invalid workspace identity fails closed.

## Key inventory

| Store | Key / pattern | Decision | Target / reason |
|---|---|---|---|
| `settings` | `cache_limit_bytes` | mapped | watchrss.settings.cacheLimitMb |
| `settings` | `oobe_seen_version` | mapped | watchrss.onboarding.oobeSeenVersion |
| `settings` | `privacy_policy_agreed_version` | mapped | watchrss.onboarding.privacyPolicyAgreedVersion |
| `settings` | `llm_prompt_preset` | mapped | watchrss.settings.llmPromptPreset (+1) |
| `settings` | `reading_font_size_sp` | mapped | watchrss.settings.fontSizeSp |
| `settings` | `media_playback_start_volume_limit_percent` | mapped | watchrss.settings.playbackStartVolumeLimit |
| `settings` | `rss_inline_image_prefetch_mode` | mapped | watchrss.settings.mediaCacheMode |
| `settings` | `reader_page_seconds` | mapped | watchrss.settings.readerPageSeconds |
| `settings` | `reader_page_mode` | mapped | watchrss.settings.readerPageMode |
| `settings` | `reading_theme_dark` | mapped | watchrss.reader-presets.selection.mode (only when reader_theme_mode is absent) |
| `settings` | `reader_auto_scroll_enabled` | mapped | watchrss.settings.readerAutoStart (scroll mode) |
| `settings` | `share_use_system` | mapped | watchrss.settings.shareUseSystem |
| `settings` | `phone_connection_enabled` | mapped | watchrss.settings.phoneConnectionEnabled |
| `settings` | `sync_media_keep_alive_enabled` | mapped | watchrss.settings.syncMediaKeepAlive |
| `settings` | `media_volume_control_enabled` | mapped | watchrss.settings.mediaVolumeControl |
| `settings` | `media_volume_guard_enabled` | mapped | watchrss.settings.mediaVolumeGuard |
| `settings` | `llm_enabled` | mapped | watchrss.settings.llmEnabled |
| `settings` | `llm_auto_summarize` | mapped | watchrss.settings.llmAutoSummarize |
| `settings` | `llm_show_token_usage` | mapped | watchrss.settings.llmShowTokenUsage |
| `settings` | `reader_auto_page_enabled` | mapped | watchrss.settings.readerAutoStart (page mode) |
| `settings` | `reader_tap_to_scroll` | retained | Native reader gesture/start toggles have no matching PodJS settings; retain in NativeBackup; page mode/seconds/scroll start migrate independently |
| `settings` | `reader_auto_scroll_lines_per_second` | mapped | watchrss.settings.readerScrollSpeed |
| `settings` | `tts_speed` | mapped | watchrss.settings.speechRate and watchrss.tts.config.v1.speed |
| `settings` | `llm_provider` | mapped | watchrss.settings.llmProvider |
| `settings` | `llm_model` | mapped | watchrss.settings.llmModel |
| `settings` | `llm_base_url` | mapped | watchrss.settings.llmBaseUrl |
| `settings` | `llm_system_prompt` | mapped | watchrss.settings.llmSystemPrompt |
| `settings` | `tts_model` | mapped | watchrss.tts.config.v1.model |
| `settings` | `tts_voice_id` | mapped | watchrss.tts.config.v1.voiceId |
| `settings` | `tts_base_url` | mapped | watchrss.tts.config.v1.baseUrl |
| `settings` | `tts_engine` | mapped | watchrss.tts.config.v1.engine |
| `settings` | `temp_original_mode_hint_attempts_[1-9][0-9]*` | mapped | watchrss.temporary-original-hints.v1 |
| `settings` | `temp_original_mode_hint_last_toast_[1-9][0-9]*` | mapped | watchrss.temporary-original-hints.v1 |
| `bili_cache` | `bili_search_history` | mapped | watchrss.bili.search-history.v1 |
| `bili_cache` | `bili_local_interaction_state_json` | mapped | watchrss.bili.interactions.v1 |
| `bili_cache` | `bili_playback_progress_json` | mapped | watchrss.bili.playback-progress.v1 |
| `bili_cache` | `bili_feed_cache_v2:.+` | retained | Derived recommendation cache/dedup for a native request session; retain, rebuild via PodJS API on explicit use |
| `bili_cache` | `bili_feed_shown_v1:.+` | retained | Derived recommendation cache/dedup for a native request session; retain, rebuild via PodJS API on explicit use |
| `reader_preset_state` | `active_preset_id` | mapped | watchrss.reader-presets.selection.lightPresetId fallback |
| `reader_preset_state` | `light_preset_id` | mapped | watchrss.reader-presets.selection.lightPresetId |
| `reader_preset_state` | `dark_preset_id` | mapped | watchrss.reader-presets.selection.darkPresetId |
| `reader_preset_state` | `reader_theme_mode` | mapped | watchrss.reader-presets.selection.mode |
| `reader_preset_state` | `dark_follows_light` | mapped | watchrss.reader-presets.selection.darkFollowsLight |
| `reader_preset_state` | `watch_theme_schedule_mode` | mapped | watchrss.reader-presets.schedule.mode |
| `reader_preset_state` | `watch_light_start_minutes` | mapped | watchrss.reader-presets.schedule.lightStartMinutes |
| `reader_preset_state` | `watch_dark_start_minutes` | mapped | watchrss.reader-presets.schedule.darkStartMinutes |
| `reader_preset_state` | `watch_sun_latitude` | mapped | watchrss.reader-presets.schedule.latitude |
| `reader_preset_state` | `watch_sun_longitude` | mapped | watchrss.reader-presets.schedule.longitude |
| `reader_preset_state` | `watch_sun_location_updated_at` | mapped | watchrss.reader-presets.schedule.locationUpdatedAt |
| `home_entry_placement` | `notes_sort_order` | mapped | watchrss.home.notes-sort-order |
| `home_entry_placement` | `notes_pinned` | mapped | watchrss.home.notes-pinned |
| `app_resume_state` | `intent_uri` | mapped | watchrss.resume-state.v1 (known routes or explicit home fallback) |
| `watch_device_identity` | `device_id` | mapped | Room snapshot device_identity |
| `watchrss_installation_identity` | `install_id` | mapped | watchrss.telemetry.install-id |
| `watchrss_installation_identity` | `first_installed_at` | retained | NativeBackup installation metadata |
| `watchrss_daily_telemetry` | `days` | mapped | watchrss.sync.account-telemetry.v1 (JSON rows) |
| `watchrss_daily_telemetry` | `day_[0-9]{4}-[0-9]{2}-[0-9]{2}` | mapped | watchrss.sync.account-telemetry.v1 (JSON rows) |
| `watchrss_download_telemetry` | `release_oobe_recorded` | mapped | watchrss.telemetry.release-oobe-recorded |
| `watchrss_library_ownership` | `legacy_owner` | retained | Select current Compose workspace; preserve original binding in NativeBackup; no merging |
| `watchrss_transparency` | `initial_app_disclosure_v1` | mapped | watchrss.transparency.<key>; seen disclosures stay seen |
| `watchrss_transparency` | `bili_disclosure_v1` | mapped | watchrss.transparency.<key>; seen disclosures stay seen |
| `watchrss_transparency` | `douyin_disclosure_v1` | mapped | watchrss.transparency.<key>; seen disclosures stay seen |
| `watchrss_announcement` | `dismissed_version` | mapped | announcement.dismissed_version |
| `watchrss_push` | `last_seen_notification_id` | mapped | announcement.last_seen_notification_id |
| `watchrss_account_state` | `state_json` | mapped | secure state_json and watchrss.library.owner; no refresh or account registration during migration |
| `bili_account_store` | `account_json` | mapped | secure watchrss.bili.session.v1; logical store name is source AES-SIV AAD |
| `douyin_account_store` | `cookie` | mapped | secure douyin.cookie; empty initialized accounts preserve empty state |
| `llm_secure_prefs` | `llm_api_key` | retained | Current PodJS LLM uses account backend, not native direct-provider key; retain sealed and in NativeBackup |
| `tts_secure_prefs` | `tts_api_key_minimax` | mapped | secure watchrss.tts.api-key.minimax |
| `tts_secure_prefs` | `tts_api_key_azure` | mapped | secure watchrss.tts.api-key.azure |
| `tts_secure_prefs` | `tts_api_key_doubao` | mapped | secure watchrss.tts.api-key.doubao |
| `douyin_watch_history` | `watched_ids` | mapped | douyin.watched.v1 |
| `douyin_watch_history` | `history_json` | mapped | douyin.history.v1 |
| `douyin_recent_window` | `recent_window_json` | mapped | douyin.recent-window.v1 |
| `douyin_feed_cache` | `feed_cache_json` | mapped | douyin.feed.v1 |
| `douyin_request_cooldown` | `feed.until` | retained | Transient native endpoint backoff; no request made during migration |
| `douyin_request_cooldown` | `detail.until` | retained | Transient native endpoint backoff; no request made during migration |
| `douyin_request_cooldown` | `feed.strikes` | retained | Transient native endpoint backoff |
| `douyin_request_cooldown` | `detail.strikes` | retained | Transient native endpoint backoff |
| `watchrss_cloud_controls` | `automatic` | retained | Native cloud opt-in is not PodJS v2 authorization; preserve without enabling an online switch |
| `watchrss_cloud_controls` | `network` | retained | Native transfer policy has no equivalent current PodJS switch; preserve unchanged |
| `watchrss_cloud_state` | `([a-f0-9]{64}_)?sequence` | retained | Native per-workspace/per-user ACK and hashes must not seed a PodJS v2 ACK; preserve and rebuild under existing guards |
| `watchrss_cloud_state` | `([a-f0-9]{64}_)?applied_full_notes_v3` | retained | Native per-workspace/per-user ACK and hashes must not seed a PodJS v2 ACK; preserve and rebuild under existing guards |
| `watchrss_cloud_state` | `([a-f0-9]{64}_)?applied_state` | retained | Native per-workspace/per-user ACK and hashes must not seed a PodJS v2 ACK; preserve and rebuild under existing guards |
| `watchrss_cloud_state` | `([a-f0-9]{64}_)?content_hash_full` | retained | Native per-workspace/per-user ACK and hashes must not seed a PodJS v2 ACK; preserve and rebuild under existing guards |
| `watchrss_cloud_state` | `([a-f0-9]{64}_)?content_hash_state` | retained | Native per-workspace/per-user ACK and hashes must not seed a PodJS v2 ACK; preserve and rebuild under existing guards |
| `watchrss_cloud_state` | `applied` | retained | Historical read compatibility; preserve; not a v2 ACK |
| `watchrss_cloud_state` | `content_hash` | retained | Historical read compatibility; preserve; not a v2 ACK |
| `watchrss_cloud_keys` | `account_[a-f0-9]{24}(_v[1-9][0-9]*)?` | mapped | secure watchrss.cloud.keys.<full user hash> untrusted legacy versions; authenticated commitments required before use |
| `watchrss_cloud_keys` | `account_current_[a-f0-9]{24}` | retained | Native preferred version is not a verified PodJS key version |
| `watchrss_cloud_keys` | `device_private_watchrss_cloud_ecdh_[a-f0-9]{24}` | retained | Preserve native device key material and original Android Keystore alias; no fabricated PodJS device authorization |
| `watchrss_cloud_keys` | `device_public_watchrss_cloud_ecdh_[a-f0-9]{24}` | retained | Preserve native device key material and original Android Keystore alias; no fabricated PodJS device authorization |
| `novel_reading_history` | `chapter_[a-f0-9]{64}` | retained | Native online novel continue-chapter URL; PodJS imported-book progress already migrates through Room; retain online-navigation hint without fabricating book identity |
| `WebViewChromiumPrefs` | `lastVersionCodeUsed` | retained | Platform WebView version metadata, never business settings |

## Preservation

Retained keys remain in NativeBackup. Encrypted values also stay in sealed per-store receipts; reports contain counts only. Inactive stores are parsed with their canonical schema but do not contribute target values. Unknown keys still block admission, including inside inactive stores. Unknown files are never deleted or admitted by a generic account prefix. Native sync ACKs, automatic cloud opt-in and device keys never authorize PodJS v2 cloud writes. Legacy account keys are staged in the existing untrusted legacy slot and require authenticated commitments/attestation before use.

## Pending native phone article transfers

Compose `phoneconnection/bluetooth/ArticleReceiveCheckpoint.kt` writes only `phone-article-checkpoints/<64-hex peer-owner>/<64-hex manifest>/<nonnegative Int>.bin` (also below the selected account files directory). The peer supplies the manifest again; these chunks have not yet completed the durable article merge. Preserve these exact files byte-for-byte in NativeBackup and `files-source`. Do not turn chunks into articles, sync state or ACKs. A different filename, malformed hash, extra directory level or out-of-range index remains unknown and blocks admission. Re-import from the phone remains the supported completion path; the original pending chunks are retained for recovery.

The source gate also pins the complete frozen Compose revision and rejects changed or new main/SDK source files. Indirect callers and provider lists therefore require key-catalogue review even when their preference helper itself did not change.
