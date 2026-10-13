# Download Subsystem Architecture & Fork Documentation

This document describes the architecture of the download subsystem in PipePipe, covering the features introduced in the `feat-bulk-naming-rebased-v2` branch: **configurable filename templates**, **multi-select & bulk downloads**, **concurrency & performance hardening**, and **SAF storage integrity**.

---

## 1. Overview & Architecture

The download subsystem is split between UI components in `org.schabi.newpipe` and core engine execution in `us.shandian.giga`:

```
UI & Coordination:
  org.schabi.newpipe.fragments.list.BaseListInfoFragment   <-- Multi-select controller & action bar
  org.schabi.newpipe.download.BulkDownloadDialog           <-- Format, quality, thread & conflict options
  org.schabi.newpipe.download.DownloadDialog               <-- Single-download dialog & conflict handling
  org.schabi.newpipe.info_list.InfoListAdapter             <-- Checkbox overlay rendering
  org.schabi.newpipe.util.FilenameUtils                    <-- Template builder & charset sanitizer

Core Engine & Service:
  us.shandian.giga.service.DownloadManagerService          <-- Bound service, IPC API & notification manager
  us.shandian.giga.service.DownloadManager                 <-- Thread-safe queue, mission state, async init
  us.shandian.giga.get.DownloadMission                     <-- Base mission unit (HTTP/HLS/SABR execution)
  us.shandian.giga.get.PendingFetchMission                 <-- Lazy stream resolution for bulk downloads
  us.shandian.giga.io.CircularFileWriter                   <-- Chunk caching & buffered file stream
  us.shandian.giga.streams.io.StoredDirectoryHelper        <-- SAF directory access & exact file lookup
  us.shandian.giga.streams.io.StoredFileHelper             <-- DocumentFile & java.io.File abstraction
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
1. **Entering Multi-Select**: In channel videos, channel tabs (Shorts), or playlists, user taps **Select videos** from the toolbar menu.
2. **State Management**: `BaseListInfoFragment` manages `selectedItems` (`List<StreamInfoItem>`).
   - Tapping an item toggles its selection in `InfoListAdapter` via `SelectionStateProvider`.
   - Action bar title updates with the selected item count.
   - Back press is handled by `OnBackPressedCallback`, cleanly exiting multi-select mode.
3. **Bulk Configuration**: Tapping **Download selected** opens `BulkDownloadDialog`:
   - **Format**: Video vs Audio-only.
   - **Quality**: Target tier (Best, 1080p, 720p, 480p, 360p, 240p).
   - **Fetch Threads**: Slider for parallel stream resolution (1 to 10 threads, default 3).
   - **Existing File Behavior**:
     - `SKIP`: Discards item if destination file already exists.
     - `OVERWRITE`: Overwrites existing file.
     - `UNIQUE_NAME`: Creates `filename (1).ext`.
4. **Queueing**: `BaseListInfoFragment` exits multi-select and offloads batch enqueueing to `DownloadManagerService.addPendingFetchMissions()`.

### `PendingFetchMission` Lifecycle
Unlike single downloads where `StreamInfo` is fetched before opening `DownloadDialog`, bulk downloads avoid resolving 50+ streams upfront:
1. `PendingFetchMission` is created with placeholder storage holding `sourceUrl`, `sourceServiceId`, and user options.
2. Enqueued in `DownloadManager.mMissionsPending`.
3. `startFetch()` executes up to `maxConcurrentFetches` via `ExtractorHelper.getStreamInfo()`.
4. Upon successful resolution, `promoteToDownload(StreamInfo)` creates real storage, assigns streams/muxers, and starts download.
5. If expired/stale URLs occur during postprocessing (e.g. empty chunk error), `refetch()` automatically refreshes stream URLs without losing the mission.

---

## 4. DownloadManager Concurrency & Performance

### Thread-Safety Architecture
- Monolithic `synchronized(this)` has been replaced by `ReentrantReadWriteLock` (`rwLock`, `readLock`, `writeLock`).
- Mission collections (`mMissionsPending`, `mMissionsFinished`) use `CopyOnWriteArrayList` combined with internal indexing lookups for $O(1)$ ID queries.
- **Rule**: Never invoke `mission.start()` or `mission.pause()` while holding locks. Collect missions inside the lock and trigger actions outside.

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
- The commits on this branch are organized into 4 logical atomic units:
  1. `feat(download): configurable filename template engine and charset sanitization`
  2. `feat(ui): multi-select stream items and bulk download UI`
  3. `feat(download): native bulk download engine with pending-fetch missions`
  4. `perf(download): concurrency overhaul, thread safety, ANR elimination, and SAF integrity`
- See `fork_changes_audit_and_squash_plan.md` in the project brain/artifacts for the complete squashing breakdown and command scripts.
