# Download Subsystem Architecture & Fork Documentation

This document describes the architecture of the download subsystem in PipePipe, covering the features introduced in the `feat-bulk-naming-rebased-v3` branch: **configurable filename templates**, **bulk download configuration & queue engine**, **concurrency & performance hardening**, and **SAF storage integrity**.

---

## 1. Overview & Architecture

The download subsystem is split between UI components in `org.schabi.newpipe` and core engine execution in `us.shandian.giga`:

```
UI & Coordination:
  org.schabi.newpipe.util.selection.SelectionToolbar       <-- Upstream universal multi-select toolbar
  org.schabi.newpipe.util.selection.StreamSelectionActions <-- Dispatches selection actions across all lists
  org.schabi.newpipe.util.selection.SelectionDownloadHelper<-- Opens BulkDownloadDialog for selected streams
  org.schabi.newpipe.download.BulkDownloadDialog           <-- Format, quality, thread & conflict options
  org.schabi.newpipe.download.DownloadDialog               <-- Single-download dialog & conflict handling
  org.schabi.newpipe.util.FilenameUtils                    <-- Template builder & charset sanitizer
  org.schabi.newpipe.util.VideoStreamSelector              <-- Resolution selection (incl. bulk quality labels)
  org.schabi.newpipe.util.AudioStreamSelector              <-- Audio stream filtering & bitrate ranking

Core Engine & Service:
  us.shandian.giga.service.DownloadManagerService          <-- Bound service, IPC API & notification manager
  us.shandian.giga.service.DownloadManager                 <-- Thread-safe queue, mission state, async init
  us.shandian.giga.get.DownloadMission                     <-- Base mission unit (HTTP/HLS/SABR execution)
  us.shandian.giga.get.PendingFetchMission                 <-- Lazy stream resolution for bulk downloads
  us.shandian.giga.io.CircularFileWriter                   <-- Chunk caching & buffered file stream
  org.schabi.newpipe.streams.io.StoredDirectoryHelper      <-- SAF directory access & exact file lookup
  org.schabi.newpipe.streams.io.StoredFileHelper           <-- DocumentFile & java.io.File abstraction
```

---

## 2. Configurable Filename Templates

Filename generation is handled by `FilenameUtils.buildFilename(Context, String template, StreamInfo)`.

### Supported Tokens

| Token | Description | Example |
|---|---|---|
| `{title}` | Stream / video title (sanitized) | `Never Gonna Give You Up` |
| `{channel}` | Uploader / channel name | `Rick Astley` |
| `{date}` | Full upload date (`YYYY-MM-DD`) | `2009-10-25` |
| `{date_short}` | Short upload date (`YYYYMM`) | `200910` |
| `{id}` | Video / stream ID (normalized, sans `#hash` or `?p=1`) | `dQw4w9WgXcQ` |

### Charset Sanitization & Safety
* Default invalid characters (`/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`) are stripped and replaced with `_`.
* Custom user charsets configured in Settings are compiled safely; if an invalid regex is supplied, it falls back to `PATTERN_MOST_SPECIAL` without crashing.

---

## 3. Multi-Select & Bulk Downloads

### UI Flow
1. **Universal Multi-Select**: Across all stream list views (Channel Videos, Channel Tabs, Remote Playlists, Kiosk, Search, Feed / What's New, Watch History, and Local Playlists), upstream's `SelectionController` and `SelectionToolbar` manage multi-selection (via toolbar **Multi-select** or item context menu).
2. **Download Action**: Tapping **Download** (`R.id.action_selection_download`) in `SelectionToolbar` invokes `StreamSelectionActions.handle()` -> `SelectionDownloadHelper.showDownloadDialog(Fragment, List<StreamInfoItem>)`.
3. **Bulk Configuration**: `SelectionDownloadHelper` opens `BulkDownloadDialog` (retaining selected stream metadata in its `Bundle` arguments across configuration changes and immediate selection mode exit):
   - **Format**: Video vs Audio-only.
   - **Quality**: Target tier (`Best`, `1080p`, `720p`, `480p`, `360p`, `240p`) resolved via `VideoStreamSelector.getDownloadResolutionIndex()`.
   - **Fetch Threads**: Slider for parallel stream resolution and concurrent downloads (1 to 10 threads, default 3).
   - **Existing File Behavior**:
     - `SKIP`: Discards item if destination file already exists.
     - `OVERWRITE`: Overwrites existing file.
     - `UNIQUE_NAME`: Creates `filename (1).ext`.
4. **Queueing**: Confirming `BulkDownloadDialog` configures `PendingFetchMission.setMaxConcurrentFetches(fetchThreads)` and offloads batch enqueueing to `DownloadManagerService.addPendingFetchMissions()`.

### `PendingFetchMission` Lifecycle & Bulk Fetch Concurrency
Unlike single downloads where `StreamInfo` is fetched before opening `DownloadDialog`, bulk downloads avoid resolving 50+ streams upfront:
1. `PendingFetchMission` is created with placeholder storage holding `sourceUrl`, `sourceServiceId`, and user options.
2. Enqueued in `DownloadManager.mMissionsPending`.
3. `startFetch()` executes up to `maxConcurrentFetches` (default 3) via `ExtractorHelper.getStreamInfo()`.
4. Upon successful resolution, `promoteToDownload(StreamInfo)` creates real storage, assigns streams/muxers, and hands off the mission to `DownloadManager.runMissions()` to be scheduled for download according to active queue limits.
5. If expired/stale URLs occur during postprocessing (e.g. empty chunk error), `refetch()` automatically refreshes stream URLs without losing the mission.

### Bulk Fetch Scheduling, Pipelined Lookahead & Auto-Healing
- **Concurrent Downloads & Pipelined Lookahead Window**:
  - `DownloadManager` supports concurrent active byte downloads up to `maxConcurrentDownloads` (configured by the bulk download dialog concurrency slider `fetchThreads`, default 3, up to 10).
  - To prevent background fetches from running too far ahead of active byte downloads and expiring ephemeral signed URLs (YouTube/Bilibili 403s), `runMissions()` limits in-flight pipeline capacity to:
    $$\text{pipelineCapacity} = \text{maxConcurrentDownloads} + \text{FETCH\_LOOKAHEAD\_LIMIT}$$
  - Up to `maxConcurrentDownloads` downloads run simultaneously in parallel.
  - During initial ramp-up, up to `maxConcurrentDownloads` fetches start in parallel to populate all download slots without delay.
  - Once active download slots are saturated, `FETCH_LOOKAHEAD_LIMIT` (1 item) is pre-fetched and held in ready reserve.
  - Subsequent items $D+2 \dots M$ remain dormant in `Queued` state until an active download slot nears completion.
  - As soon as ANY active download completes, the pre-buffered item begins downloading with **zero gap**, and the next item begins pre-fetching.
  - If `mPrefQueueLimit` is disabled, downloads and fetches proceed up to `maxConcurrentFetches` without queue restriction.
- **Auto-Healing & Reactive Recovery on 403 Forbidden / 410 Gone**:
  - The sliding lookahead window ensures metadata fetches stay strictly just-in-time, preventing URLs from idling in the queue and expiring.
  - If an upstream stream URL does expire or fail with `HTTP 403 Forbidden` or `HTTP 410 Gone`:
    - If no bytes have been written yet (`done == 0`), `doRecover()` auto-heals by invoking `refetch()`, which resets metadata and queues a fresh fetch through `runMissions()`.
    - If bytes have already been downloaded (`done > 0`), `doRecover()` delegates to NewPipe's `super.doRecover(errorCode)` (`DownloadMissionRecover`) to refresh stream URLs in-place without wiping or restarting partially downloaded files.
  - This design avoids redundant polling timers or arbitrary staleness deadlines (`isStale()`), relying on the pipeline capacity to prevent expiration and native reactive recovery to auto-heal.
- **Queue Limit Budgeting**: `runMissions()` calculates separate budgets for byte downloads (`downloadsToStartBudget = mPrefQueueLimit ? max(0, maxConcurrentDownloads - runningDownloads) : ∞`) and pending fetches (`pipelineCapacity = maxConcurrentDownloads + FETCH_LOOKAHEAD_LIMIT`).
- **Atomic Slot Acquisition**: `activeFetchCount` uses atomic compare-and-swap (CAS) loops (`compareAndSet`) to prevent race conditions during rapid dispatch across threads.
- **Slot Leak Prevention**: Pausing, deleting, or refetching a mission (`pause()`, `delete()`, `refetch()`) cleanly disposes in-flight RxJava subscriptions (`fetchDisposable`) and atomically decrements `activeFetchCount`.
- **Chain Scheduling (No Queue Freezes)**: Calling `downloadManager.runMissions()` when a fetch finishes (`onFetchSuccess()`, `onFetchError()`) or when an active fetch is paused/deleted ensures that subsequent queued missions immediately claim the freed fetch slot without requiring manual user intervention ("Start downloads").
- **Poison Pill Elimination**: When stream resolution fails, `onFetchError()` marks the mission with `running = false` and `enqueued = false` (rather than leaving `enqueued = true`). This prevents errored missions from freezing queue iteration under `mPrefQueueLimit`.
- **Burst Prevention in Bulk Retries and Mass Starts**:
  - `startAllMissions()` marks pending missions as `enqueued = true` and dispatches via `runMissions()`, never calling `mission.start()` unthrottled in a loop.
  - `retryAllErrorMissions()` resets errored missions without triggering in-loop cascade scheduling (`refetch(false)`, `convertToPendingFetchMission(..., false)`), then invokes `runMissions()` once at the end.
  - Concurrency bounds (`maxConcurrentDownloads`, `pipelineCapacity`, `maxConcurrentFetches`) are strictly enforced, eliminating 403 / 429 rate limit storms on upstream extractors/CDNs.
  - **Connectivity Freshness & Immediate Dispatch**:
    - `DownloadManagerService` initializes network state synchronously in `onCreate()` (`handleConnectivityState(true)`), listens to `onCapabilitiesChanged()` in `mNetworkStateListenerL`, and falls back to `NetworkCapabilities.NET_CAPABILITY_INTERNET` when `getActiveNetworkInfo()` lags on Wi-Fi/VPN handoffs.
    - `DownloadManager.canDownloadInCurrentNetwork()`, `startAllMissions()`, and `retryAllErrorMissions()` dynamically refresh `mLastNetworkStatus` via `refreshConnectivityStatus()` and activate `mSelfMissionsControl = true`, ensuring `runMissions()` never aborts on a stale `NetworkState.Unavailable` value.
    - `MissionsFragment` explicitly starts `DownloadManagerService` (`startService`) alongside `bindService` so background downloads and bulk retries continue uninterrupted when leaving the Downloads screen.
  - **Download Progress Preservation & Dead-Mission Recovery**:
    - `DownloadManager.tryRecover()` returns early when `!mission.storage.isInvalid() && mission.storage.existsAsFile()`, preventing `StoredFileHelper.create()` from invalidating existing Java I/O files (`createNewFile() == false`) or creating empty duplicate SAF files (`video (1).mp4`).
    - For partially downloaded missions (`done > 0`) with valid existing files and non-empty `urls`, retrying preserves existing files and progress (`blocks`, checkpoints) and resumes in-place (`MissionAdapter.recoverMission()` / `DownloadMissionRecover`).
    - Dead missions (`urls.length == 0` after failed recovery) are excluded from `DownloadMission.isFinished()` (`urls.length > 0` guard) and are cleanly re-fetched via `PendingFetchMission.refetch()` or `convertToPendingFetchMission()` (preserving target resolution when `mainStorage` is configured).
    - `DownloadMission.pause()` avoids self-interrupting `Thread.currentThread()` when invoked from `init` (`DownloadInitializer` -> `doRecover` -> `refetch`).
    - `PendingFetchMission` resolves HLS manifest/audio fallbacks and SABR-compatible secondary audio streams (`SabrDownloadStreamHelper.audioStreamsForVideo`), populates `resourceDeliveryMethods` / `resourceManifestUrls` / `resourceIsUrls` / `resourceRequestHeaders` (plugin custom HTTP stream headers), and clears `psAlgorithm` for HLS/SABR missions.
  - **Async UI Synchronization (`MESSAGE_QUEUE_UPDATED` & `MESSAGE_RUNNING`)**:
    - Asynchronous `mExecutor` batch operations (`startAllMissions()`, `retryAllErrorMissions()`, `deleteAllErroredMissions()`, `deleteAllFetchMissions()`) emit `DownloadManagerService.MESSAGE_QUEUE_UPDATED` upon completion.
    - `MissionAdapter` handles both `MESSAGE_QUEUE_UPDATED` and `MESSAGE_RUNNING` on the main thread to refresh DiffUtil snapshots, item titles/colors (`bindMissionAppearance`), status labels, and toolbar buttons (`mRetryButton`, `mStartButton`, `mPauseButton`, `mDeleteErrored`, `mDeleteFetches`) immediately.
- **UI State Distinction**: `MissionAdapter` renders distinct states for pending fetch missions:
  - Error: `R.string.bulk_download_fetch_error` ("Fetch failed", marquee off).
  - Fetching: `R.string.bulk_download_fetching` ("Fetching...", marquee on).
  - Enqueued: `R.string.queued` ("Queued", marquee off).
  - Paused: `R.string.paused` ("Paused", marquee off).

---

## 4. DownloadManager Concurrency & Performance

### Thread-Safety Architecture
- Monolithic `synchronized(this)` has been replaced by `ReentrantReadWriteLock` (`rwLock`, `readLock`, `writeLock`).
- Mission collections (`mMissionsPending`, `mMissionsFinished`) use `CopyOnWriteArrayList` combined with internal indexing lookups for $O(1)$ ID queries.
- **Rule**: Never invoke `mission.start()` or `mission.pause()` while holding locks. Collect missions inside the lock and trigger actions outside.
- Concurrency separation: `getRunningDownloadsCount()` counts active byte downloads (excluding pending fetches), while `getRunningMissionsCount()` counts all running missions. `runMissions()` uses this distinction to schedule byte downloads according to `mPrefQueueLimit` while pipelining pending fetches according to `FETCH_LOOKAHEAD_LIMIT`.

### Non-Blocking Database Initialization (ANR Elimination)
- Loading finished missions (`FinishedMissionStore.loadFinishedMissions()`) performs database queries and SAF permission checks.
- Handled asynchronously via `asyncInit()` on a background thread.
- Public methods call `awaitInit()`, preventing main-thread lockups and ANRs during startup or when opening download dialogs.

### IPC & Notification Debouncing
- Rapid progress updates from concurrent downloads can overwhelm the Android Binder IPC queue.
- Progress notifications are throttled using a 250ms interval per mission (`lastProgressNotifyTime`), keeping the UI responsive while maintaining real-time status.

---

## 5. Storage Access Framework (SAF) & Conflict Handling

### Exact File Detection
In `StoredDirectoryHelper.findFileSAFHelper()`:
- Queries child documents with `{COLUMN_DISPLAY_NAME, COLUMN_DOCUMENT_ID}` without arbitrary SQL `WHERE` clauses (which fail or behave case-sensitively across third-party document providers).
- Performs exact, case-insensitive display name matching (`filename.equalsIgnoreCase(docName)`), avoiding false-positive prefix matches against `.part` or `.tmp` files.
- Falls back to `tree.findFile(filename)` if the cursor query is unsupported.

### Conflict & Overwrite Confirmation
When initiating a download:
1. `mainStorage.findFile(filename)` checks if the file exists on disk.
2. `downloadManager.checkForExistingMission(storage)` checks for active/completed missions.
3. If an existing file is detected:
   - **Completed mission**: `showConflictDialog()` prompts: Overwrite, Generate unique name, or Cancel.
   - **Unrelated existing file**: `showOverwriteUnrelatedDialog()` prompts: Overwrite, Generate unique name, or Cancel.
   - Files are **never truncated or overwritten silently**.

---

## 6. Upstream Rebase & Squashing Guidelines

When preparing changes for upstream PRs or rebasing onto newer `upstream/dev` releases:
- The commits on `feat-bulk-naming-rebased-v3` are organized into 4 logical atomic units:
  1. `feat(download): configurable filename template engine and charset sanitization`
  2. `fix(io): SAF document tree traversal, storage stream integrity, and conflict resolution`
  3. `feat(download): native bulk download queue engine and concurrency overhaul`
  4. `feat(ui): bulk download dialog integration and async conflict handling`
- See `fork_changes_audit_and_squash_plan.md` in the project brain/artifacts for the complete squashing breakdown and rebase history.
