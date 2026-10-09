# Native files and cache classification (T17)

**`watchrss-files-4` treats every regular file beneath `cache/` as refetchable, with the existing exact database-lock rules taking precedence.** Nothing from cache is published into PodJS business files. The private snapshot still records and hashes these files; `files-report.json` records class counts and `unrecognizedCacheFiles` for paths outside the old named cache shapes. Source links, special files, unreadable inventories and concurrent mutations still fail closed. This change does not weaken stable-byte checks for user assets.

The audit uses native Android_WatchRSS source, the installed v40 APK with SHA256 `decf31983a9936262eb76c89987acf10d8ba49d15de747d2f6dd61be973a11b7`, its bundled library implementations, and PodJS revisions 7e516a9 (v1000), 367c706 (v1001), 5ee2c64 (v1002). Decompilation evidence and the direct/indirect writer inventory live in the app checkout `.codex-tmp/t17/`. A reachable writer is distinguished from an observed writer. The formal non-debuggable sandbox was not read.

## Why T1 did not detect this

T1 `dry-run.log` and `interrupt-dry-run.log` use `tar -cf - databases files shared_prefs`. Its 137-file successful replay **did not include native cache**. Existing default Coil cache or temporary files can therefore explain the discrepancy without having appeared after T1. Formal v1002 logged only the blocker, so no individual path or producer is confirmed for that failure.

## Native writers and exact dispositions

| Producer and source | Path shape | files-3 outcome | files-4 outcome / reachability |
| --- | --- | --- | --- |
| ArticleContentStore | `files/article_content/<file>`; `.write-*.part` | asset / incomplete | unchanged; atomic body writes |
| RssOfflineStore / RssFetchService | `files/offline/rss/<item>/<file>`; sibling `.tmp` | asset / incomplete | unchanged; downloaded user offline media |
| BiliRepository | `files/offline/bili/<key>_<cid>_q<qn>.mp4`; `.tmp` | refetchable / incomplete | unchanged |
| ReaderResourceStore | `files/reader_assets/{fonts,backgrounds,variants}/<file>`; `.part`, `.part.meta`, `.tmp` | asset / incomplete | unchanged; catalogs and bytes still verified |
| WatchNoteAssetSyncPayload | `files/notes/assets/<safe-name>`; temp files | asset / incomplete | unchanged; precise existing filename rule |
| WatchCloudCache | `files/watch-cloud-cache/**` | cloud-unverified (or incomplete) | unchanged; binding blocker is preserved |
| AppLogger | `files/app_log.txt` | private-log | unchanged; init/redaction/append/trim overwrite this one file, no rotated/crash filename found |
| TestAppContainer | exact allowlisted activity/smoke prefixes + UUID + `.preferences_pb` | private-test-state | unchanged; protobuf checked; debug-produced state can remain across upgrades |
| DataStoreFile / PreferencesFile | `files/datastore/settings.preferences_pb.preferences_pb`, `bili_cache.preferences_pb.preferences_pb`, legacy single-suffix names and temp suffixes | delegated to store converter | unchanged; unknown stores retain their own policy, no new blanket business exemption |
| ProfileInstaller / ProfileVerifier | `files/profileinstaller_profileWrittenFor_lastUpdateTime.dat`, `files/profileInstalled` | platform-metadata, excluded from business snapshot | unchanged; NativeBackup includes both exact files |
| AppLocalesStorageHelper (bundled AndroidX core/appcompat) | `files/androidx.appcompat.app.AppCompatDelegate.application_locales_record_file` | unknown | exact private-platform-state; opt-in library locale persistence, no native `setApplicationLocales` / autoStoreLocales caller found |
| ActivityChooserModel | `files/activity_choser_model_history.xml` (library default spelling) | unknown | exact private-platform-state; no native ShareActionProvider/ActivityChooserModel caller found. Custom history names remain unknown |
| Coil 2.5.0 SingletonDiskCache / DiskLruCache | `cache/image_cache/{journal,journal.bkp,journal.tmp,<hex>.0,<hex>.1,<hex>.0.tmp,<hex>.1.tmp}` | unknown, except generic tmp incomplete | refetchable; default AsyncImage/rememberAsyncImagePainter loader uses this cache |
| Coil SourceImageSource | `cache/tmp<random>.tmp` (File.createTempFile default suffix) | incomplete | refetchable; decode spill files can survive a killed process |
| Native RssInlineImageLoader | `cache/rss_images/coil/**` | refetchable, tmp incomplete | refetchable; explicit Coil directory beneath recognized RSS cache |
| Native RssImageLoader | `cache/rss_images/<hash>.img`, `.tmp` | refetchable / incomplete | refetchable |
| BiliPlaybackCacheManager / Media3 SimpleCache | `cache/bili_stream_cache/**`, including index, UID and span files | refetchable / incomplete | refetchable; Media3 itself uses the directory supplied by the app |
| DouyinPreloadManager / DouyinPlaybackPreviewCache | `cache/douyin_preload/**`, `cache/douyin_preview_snapshots/**` | refetchable / incomplete | refetchable |
| AppUpdateDownloader | `cache/updates/**` | refetchable / incomplete | refetchable |
| ExoPlayerTtsAudioPlayer | `cache/tts_<timestamp>.mp3` | refetchable | unchanged. TTS engines are remote/system engines; no bundled on-device model writer found |
| Media3 Util.createTempFile | `cache/<caller-prefix><random>.tmp` | incomplete; other suffixes unknown | refetchable; library utility, supplied media paths remain app-controlled |
| TypefaceCompatUtil | `cache/.font<pid>-<tid>-<index>` | unknown | refetchable; bundled font loading helper |
| SQLiteCopyOpenHelper | `cache/room-copy-helper<random>.tmp`; `files/watchrss.db.lck` when prepackaged DB copy is enabled | incomplete / unknown | cache refetchable; **files lock remains unknown** because native Room builder has no createFromAsset/createFromFile/createFromInputStream; not a native reachable writer |
| FrameworkSQLiteOpenHelper / ProcessLock | `cache/watchrss.db.lck`; cache + absolute DB name paths under exact source/target package identities | exact database-lock | unchanged. Other `.lck`, foreign package/user paths remain refetchable in cache; no files wildcard exemption |
| NanoHTTPD default temp manager | `cache/NanoHTTPD-<random>` via `java.io.tmpdir` | unknown | refetchable; multipart/request body spill, default manager deletes on close |
| Guava FileBackedOutputStream / TempFileCreator | `cache/FileBackedOutputStream<random>.tmp`, other caller prefixes | incomplete or unknown | refetchable; bundled utility; no native direct spill caller found |
| ExifInterface / Java/Kotlin temp helpers | `cache/temp<random>tmp`, default temp suffixes / caller suffixes | unknown or incomplete | refetchable; temp storage chosen from runtime `java.io.tmpdir` |
| OkHttp 4.12.0 Cache / DiskLruCache | caller-chosen `journal*`, MD5 `.0/.1` entries | depends on supplied root; root cache unknown except existing named caches | no native `.cache(Cache(...))` configuration found; Coil has its own disk cache. Any cache-root path is refetchable, a custom files-root cache is **not** blanket allowed |
| WebView / Chromium provider | predominantly `app_webview/**`, plus `shared_prefs/WebViewChromiumPrefs.xml(.bak)`; provider-dependent cache files | outside file converter / cache unknown | native WebViews may initialize provider; arbitrary `cache/**` refetchable. OEM/provider paths under `files/**` are not assumed legitimate |
| FileProvider, WebViewAssetLoader, EncryptedFile | caller-provided existing paths | no own fixed writer | these APIs are not autonomous disk caches; no new wildcard |
| Crash/log/model libraries | none found configured as a sandbox file writer | n/a | native logging is AppLogger/Logcat/in-memory DebugLogBuffer; no Crashlytics/Sentry/Bugly/ACRA/on-device inference/model download dependency or configured handler found. System crash/dropbox/tombstone output is outside these roots |

Android supplies app cache as `java.io.tmpdir` before Application startup; this is verified against [AOSP ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/5460282/core/java/android/app/ActivityThread.java). This explains indirect Java/Kotlin/NanoHTTPD spill files in cache. OEM firmware and the dynamically installed WebView provider are not bundled in the native APK, so the APK audit cannot prove their exhaustive behavior.

## PodJS release writes before admission

| Component | v1000 / v1001 | v1002 / T17 | Classification |
| --- | --- | --- | --- |
| Application / main Activity | Application logs and constructs native recovery view before runtime; no JS/runtime/browser created until admission | same | Logcat, no own files/cache payload |
| ProfileInstaller startup | first-frame + 5–6 second initializer can write two files markers if packaged baseline profile succeeds | removed automatic initializer, explicitly scheduled only after admission | exact metadata excluded from business snapshot, retained in NativeBackup |
| Room converter | source DB opened read-only; private snapshot/Room progress/target DB inside `files/package-takeover/generations/<g>` | same; physical-column schema fixed in v1001 | excluded control subtree; Room snapshot remains strict |
| Store/file converter | snapshot, part, completed, reports, KV and target sealed store under generation | same | excluded control subtree; all failed v1000/v1001/v1002 candidates are retained/excluded |
| Backup publisher | snapshot evidence after SNAPSHOTTED; seal/move/receipt only inside ACTIVATING | same | control subtree and `.podjs-takeover-backup`; any prior backup tree vetoes reclassification |
| WorkManager | initializer removed; pending system job can still initialize via Configuration.Provider before admission, migrating DB/prefs and creating SQLite cache locks | same | DB/prefs/no_backup stay strict; exact lock classification retained. Lack of DEBUG logs in v1002 does not prove absence |
| OkHttp PlatformInitializer | bundled initializer, no disk Cache configured by host | same | no own files/cache writer |
| WebView, ExoPlayer, speech, text raster, guest file/KV services | created through admitted runtime/service path | same | denied before storage admission; post-admission paths resolve into admitted generation |
| Android graphics/fonts / temp / install / OEM | may create cache/temp/graphics state before admission; code_cache is outside this converter | same | cache fallback; unknown files still block, including profiling artifacts such as `files/perf.data_jit_app_cache` unless separately reviewed |

No PodJS application-level unknown files-root writer was found on the normal pre-admission path across the three revisions. This is a source conclusion, not a formal runtime observation. Production v1002's exact unknown path remains pending new release diagnostics.

## Retry and diagnostics

The only compatibility transition is unchanged Room/stores plus `watchrss-files-3` to `watchrss-files-4`, from BLOCKED/unknown_user_files, with the old private file report containing only that blocker. A fresh generation is journaled before any snapshot, retaining every old candidate. The coordinator rejects a receipt in the journal, a known blocked/resume phase other than VERIFYING, an active pointer, any generation link/non-directory or activation/native-backup receipt in **any** retained generation. The Android publisher additionally rejects existing backup/recovery/restore trees (including partial or dangling-link trees). Source must be present and pass its detection gates. All other BLOCKED reasons and unrelated version changes retain their semantics. Legacy v1002 journals lack blockedFromPhase; exact version/report compatibility plus negative publication evidence is required. New blockers persist blockedFromPhase explicitly.

Unknown files produce one release `PodTakeoverFiles` line per completed copy/report, with total count and at most 20 sorted relative paths. Segments longer than 32 characters, numeric identifiers, hex-like identifiers and UUIDs become `<id:N>`; control characters and punctuation outside the safe path alphabet are escaped into underscores. No content, absolute sandbox path or unfiltered exception message is emitted. Full source paths/hashes remain only in private snapshot receipts. Private platform state is snapshotted stably and retained in the recovery tree, never imported into guest business files. **NativeBackup source is unchanged and byte-equality with recovery remains mandatory.**
