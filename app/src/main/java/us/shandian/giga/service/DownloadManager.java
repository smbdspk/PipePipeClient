package us.shandian.giga.service;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.Mission;
import us.shandian.giga.get.PendingFetchMission;
import us.shandian.giga.get.sqlite.FinishedMissionStore;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import us.shandian.giga.util.Utility;

import static org.schabi.newpipe.BuildConfig.DEBUG;
import static us.shandian.giga.get.DownloadMission.ERROR_NOTHING;
import static us.shandian.giga.get.DownloadMission.ERROR_PROGRESS_LOST;

public class DownloadManager {
    private static final String TAG = DownloadManager.class.getSimpleName();

    enum NetworkState {Unavailable, Operating, MeteredOperating}

    public final static int SPECIAL_NOTHING = 0;
    public final static int SPECIAL_PENDING = 1;
    public final static int SPECIAL_FINISHED = 2;
    public final static int MESSAGE_SKIPPED = 5;

    public static final String TAG_AUDIO = "audio";
    public static final String TAG_VIDEO = "video";
    private static final String DOWNLOADS_METADATA_FOLDER = "pending_downloads";

    private final FinishedMissionStore mFinishedMissionStore;

    private final ArrayList<DownloadMission> mMissionsPending = new ArrayList<>();
    private final ArrayList<FinishedMission> mMissionsFinished = new ArrayList<>();
    private final CountDownLatch mInitLatch = new CountDownLatch(1);

    private final Handler mHandler;
    private final File mPendingMissionsDir;
    private final Context mContext;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DownloadManager-Worker");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private NetworkState mLastNetworkStatus = NetworkState.Unavailable;

    int mPrefMaxRetry;
    boolean mPrefMeteredDownloads;
    boolean mPrefQueueLimit = true;
    public static final int FETCH_LOOKAHEAD_LIMIT = 1;
    private boolean mSelfMissionsControl;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    StoredDirectoryHelper mMainStorageAudio;
    StoredDirectoryHelper mMainStorageVideo;

    void awaitInit() {
        try {
            mInitLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Create a new instance
     *
     * @param context Context for the data source for finished downloads
     * @param handler Thread required for Messaging
     */
    DownloadManager(@NonNull Context context, Handler handler, StoredDirectoryHelper storageVideo, StoredDirectoryHelper storageAudio) {
        if (DEBUG) {
            Log.d(TAG, "new DownloadManager instance. 0x" + Integer.toHexString(this.hashCode()));
        }

        mContext = context.getApplicationContext();
        mFinishedMissionStore = new FinishedMissionStore(context);
        mHandler = handler;
        mMainStorageAudio = storageAudio;
        mMainStorageVideo = storageVideo;
        mPendingMissionsDir = getPendingDir(context);

        mExecutor.submit(() -> {
            writeLock.lock();
            try {
                mMissionsFinished.addAll(loadFinishedMissions());
                loadPendingMissions(mContext);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load missions", e);
            } finally {
                writeLock.unlock();
                mInitLatch.countDown();
            }
            runMissions();
        });
    }

    private static File getPendingDir(@NonNull Context context) {
        File dir = context.getExternalFilesDir(DOWNLOADS_METADATA_FOLDER);
        if (testDir(dir)) return dir;

        dir = new File(context.getFilesDir(), DOWNLOADS_METADATA_FOLDER);
        if (testDir(dir)) return dir;

        throw new RuntimeException("path to pending downloads are not accessible");
    }

    private static boolean testDir(@Nullable File dir) {
        if (dir == null) return false;

        try {
            if (!Utility.mkdir(dir, false)) {
                Log.e(TAG, "testDir() cannot create the directory in path: " + dir.getAbsolutePath());
                return false;
            }

            File tmp = new File(dir, ".tmp");
            if (!tmp.createNewFile()) return false;
            return tmp.delete();// if the file was created, SHOULD BE deleted too
        } catch (Exception e) {
            Log.e(TAG, "testDir() failed: " + dir.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * Loads finished missions from the data source.
     */
    private ArrayList<FinishedMission> loadFinishedMissions() {
        return mFinishedMissionStore.loadFinishedMissions();
    }

    private void loadPendingMissions(Context ctx) {
        File[] subs = mPendingMissionsDir.listFiles();

        if (subs == null) {
            Log.e(TAG, "listFiles() returned null");
            return;
        }
        if (subs.length < 1) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Loading pending downloads from directory: " + mPendingMissionsDir.getAbsolutePath());
        }

        File tempDir = pickAvailableTemporalDir(ctx);
        Log.i(TAG, "using '" + tempDir + "' as temporal directory");
        cleanOrphanedTempFiles(tempDir);

        for (File sub : subs) {
            if (!sub.isFile()) continue;
            if (sub.getName().equals(".tmp")) continue;

            DownloadMission mis = Utility.readFromFile(sub);
            if (mis == null) {
                //noinspection ResultOfMethodCallIgnored
                sub.delete();
                continue;
            }

            // DON'T delete missions that are truly finished - let them be moved to finished list
            if (mis.isFinished()) {
                // Move to finished missions instead of deleting
                setFinished(mis);
                //noinspection ResultOfMethodCallIgnored
                sub.delete();
                continue;
            }

            // DON'T delete missions with storage issues - try to recover them
            if (mis.hasInvalidStorage() && mis.errCode != ERROR_PROGRESS_LOST) {
                if (mis.storage == null) {
                    sub.delete();
                    continue;
                }
                if (mis.storage.isInvalid()) {
                    Log.w(TAG, "Skipping mission with invalid storage: " + mis.storage.getName());
                    mis.errCode = DownloadMission.ERROR_FILE_CREATION;
                    mis.errObject = null;
                    mis.threads = new Thread[0];
                    mis.metadata = sub;
                    mis.maxRetry = mPrefMaxRetry;
                    mis.mHandler = mHandler;
                    mis.context = ctx;
                    mMissionsPending.add(mis);
                    continue;
                }
            }

            mis.threads = new Thread[0];

            if (mis instanceof PendingFetchMission) {
                PendingFetchMission pfm = (PendingFetchMission) mis;
                pfm.setDownloadManager(this);
                mis.metadata = sub;
                mis.maxRetry = mPrefMaxRetry;
                mis.mHandler = mHandler;
                mis.context = ctx;
                if (!pfm.pendingFetch && mis.storage != null && !mis.storage.isInvalid()) {
                    try {
                        mis.storage = StoredFileHelper.deserialize(mis.storage, ctx);
                    } catch (Exception ex) {
                        Log.e(TAG, "Failed to deserialize PFM storage for " + mis.storage.toString(), ex);
                    }
                    // If a Bilibili mission crashed during postprocessing, the output file
                    // may be corrupt (CircularFileWriter rewrites in-place). Clean up
                    // orphaned sidecar files and reset to re-fetch cleanly.
                    if (mis.psAlgorithm != null
                            && us.shandian.giga.postprocessing.Postprocessing.BILIBILI_MUXER.equals(mis.psAlgorithm.name)
                            && mis.psState == 1) {
                        Log.w(TAG, "Resetting Bilibili PFM that crashed during postprocessing: "
                                + pfm.sourceName);
                        if (mis.storage != null && !mis.storage.isInvalid()) {
                            us.shandian.giga.util.Utility.removeTempFileOfDownloadedVideo(mis.storage);
                            mis.storage.delete();
                        }
                        pfm.pendingFetch = true;
                        mis.psState = 0;
                        mis.errCode = ERROR_NOTHING;
                        mis.errObject = null;
                        mis.enqueued = true;
                        mis.running = false;
                    }
                }
                mMissionsPending.add(mis);
                continue;
            }

            boolean exists;
            try {
                mis.storage = StoredFileHelper.deserialize(mis.storage, ctx);
                exists = !mis.storage.isInvalid() && mis.storage.existsAsFile();
            } catch (Exception ex) {
                Log.e(TAG, "Failed to load the file source of " + mis.storage.toString(), ex);
                // Don't invalidate storage immediately - try to recover first
                exists = false;
            }

            if (mis.isPsRunning()) {
                if (mis.psAlgorithm.worksOnSameFile) {
                    // Incomplete post-processing results in a corrupted download file
                    if (exists && mis.storage.isDirect() && !mis.storage.delete())
                        Log.w(TAG, "Unable to delete incomplete download file: " + sub.getPath());
                }
                mis.psState = 0;
                mis.errCode = DownloadMission.ERROR_POSTPROCESSING_STOPPED;
            } else if (!exists) {
                tryRecover(mis);
                // Keep the mission even if recovery fails - don't reset to ERROR_PROGRESS_LOST
                // This allows user to see the failed download and potentially retry
                if (mis.isInitialized() && mis.errCode == ERROR_NOTHING) {
                    mis.resetState(true, true, ERROR_PROGRESS_LOST);
                }
            }

            if (mis.psAlgorithm != null) {
                mis.psAlgorithm.cleanupTemporalDir();
                mis.psAlgorithm.setTemporalDir(tempDir);
            }

            mis.metadata = sub;
            mis.maxRetry = mPrefMaxRetry;
            mis.mHandler = mHandler;
            mis.context = ctx;

            mMissionsPending.add(mis);
        }

        if (mMissionsPending.size() > 1)
            Collections.sort(mMissionsPending, Comparator.comparingLong(Mission::getTimestamp));
    }


    private static final java.util.concurrent.atomic.AtomicLong sMetadataSequence = new java.util.concurrent.atomic.AtomicLong();

    private File createUniqueMetadataFile(long timestamp) {
        long seq = sMetadataSequence.incrementAndGet();
        File file = new File(mPendingMissionsDir, timestamp + "_" + seq);
        try {
            if (file.createNewFile()) {
                return file;
            }
        } catch (IOException ignored) {
        }

        File fallback = new File(mPendingMissionsDir, timestamp + "_" + UUID.randomUUID().toString());
        try {
            if (fallback.createNewFile()) {
                return fallback;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create pending mission metadata file", e);
        }
        return fallback;
    }

    /**
     * Start a new download mission
     *
     * @param mission the new download mission to add and run (if possible)
     */
    void startMission(DownloadMission mission) {
        awaitInit();
        mission.timestamp = System.currentTimeMillis();
        mission.mHandler = mHandler;
        mission.maxRetry = mPrefMaxRetry;
        mission.metadata = createUniqueMetadataFile(mission.timestamp);

        Utility.writeToFile(mission.metadata, mission);

        boolean shouldStart;
        writeLock.lock();
        try {
            mSelfMissionsControl = true;
            mMissionsPending.add(mission);

            if (mission.storage == null) {
                mission.errCode = DownloadMission.ERROR_FILE_CREATION;
                if (mission.errObject != null)
                    mission.errObject = new IOException("DownloadMission.storage == NULL");
                shouldStart = false;
            } else {
                shouldStart = canDownloadInCurrentNetwork() && (!mPrefQueueLimit || getRunningDownloadsCount() < getMaxConcurrentDownloads());
            }
        } finally {
            writeLock.unlock();
        }

        if (shouldStart) {
            mission.start();
        }

        runMissions();
    }


    void addPendingFetchMission(PendingFetchMission mission) {
        addPendingFetchMission(mission, true);
    }

    void addPendingFetchMission(PendingFetchMission mission, boolean triggerRunMissions) {
        awaitInit();
        mission.timestamp = System.currentTimeMillis();
        mission.mHandler = mHandler;
        mission.maxRetry = mPrefMaxRetry;
        mission.setDownloadManager(this);
        mission.context = mContext;
        mission.metadata = createUniqueMetadataFile(mission.timestamp);

        Utility.writeToFile(mission.metadata, mission);

        writeLock.lock();
        try {
            mSelfMissionsControl = true;
            mMissionsPending.add(mission);
        } finally {
            writeLock.unlock();
        }

        if (triggerRunMissions) {
            runMissions();
        }
    }

    void addPendingFetchMissions(List<PendingFetchMission> missions) {
        if (missions == null || missions.isEmpty()) return;
        awaitInit();
        for (PendingFetchMission mission : missions) {
            mission.timestamp = System.currentTimeMillis();
            mission.mHandler = mHandler;
            mission.maxRetry = mPrefMaxRetry;
            mission.setDownloadManager(this);
            mission.context = mContext;
            mission.metadata = createUniqueMetadataFile(mission.timestamp);
            Utility.writeToFile(mission.metadata, mission);
        }

        writeLock.lock();
        try {
            mSelfMissionsControl = true;
            mMissionsPending.addAll(missions);
        } finally {
            writeLock.unlock();
        }

        runMissions();
    }

    public void notifySkipped(String filename) {
        mHandler.obtainMessage(MESSAGE_SKIPPED, filename).sendToTarget();
    }

    public void resumeMission(DownloadMission mission) {
        synchronized (this) {
            if (mission.running) return;

            // PendingFetchMission in fetch phase has its own slot-based concurrency
            // (activeFetchCount), so let it through — startFetch() will re-enqueue
            // if no slots are available.
            boolean isPendingFetch = mission instanceof PendingFetchMission
                    && ((PendingFetchMission) mission).pendingFetch;
            if (!isPendingFetch && mPrefQueueLimit && getRunningDownloadsCount() >= getMaxConcurrentDownloads()) {
                mission.setEnqueued(true);
                return;
            }

            mission.start();
        }
    }

    public void pauseMission(DownloadMission mission) {
        if (mission.running) {
            mission.setEnqueued(false);
            mission.pause();
        }
    }

    public void deleteMission(Mission mission) {
        awaitInit();
        boolean isFinished = false;
        writeLock.lock();
        try {
            if (mission instanceof DownloadMission) {
                mMissionsPending.remove(mission);
            } else if (mission instanceof FinishedMission) {
                mMissionsFinished.remove(mission);
                isFinished = true;
            }
        } finally {
            writeLock.unlock();
        }
        mission.delete();
        if (isFinished) {
            mFinishedMissionStore.deleteMission(mission);
        }
    }

    public void deleteAllErroredMissions() {
        awaitInit();
        final ArrayList<DownloadMission> toDelete;
        readLock.lock();
        try {
            toDelete = new ArrayList<>();
            for (DownloadMission mission : mMissionsPending) {
                if (mission.errCode != DownloadMission.ERROR_NOTHING
                        && !mission.running && !mission.isFinished()) {
                    toDelete.add(mission);
                }
            }
        } finally {
            readLock.unlock();
        }
        mExecutor.execute(() -> {
            for (DownloadMission mission : toDelete) {
                writeLock.lock();
                try {
                    if (!mMissionsPending.contains(mission)) continue;
                    mMissionsPending.remove(mission);
                } finally {
                    writeLock.unlock();
                }
                mission.delete();
            }
            notifyQueueUpdated();
        });
    }

    public void deleteAllFetchMissions() {
        awaitInit();
        final ArrayList<DownloadMission> toDelete;
        readLock.lock();
        try {
            toDelete = new ArrayList<>();
            for (DownloadMission mission : mMissionsPending) {
                if (mission instanceof PendingFetchMission
                        && ((PendingFetchMission) mission).pendingFetch) {
                    toDelete.add(mission);
                }
            }
        } finally {
            readLock.unlock();
        }
        mExecutor.execute(() -> {
            for (DownloadMission mission : toDelete) {
                writeLock.lock();
                try {
                    if (!mMissionsPending.contains(mission)) continue;
                    mMissionsPending.remove(mission);
                } finally {
                    writeLock.unlock();
                }
                mission.delete();
            }
            notifyQueueUpdated();
        });
    }

    public void forgetMission(StoredFileHelper storage) {
        awaitInit();
        Mission mission = null;
        boolean isFinished = false;
        writeLock.lock();
        try {
            DownloadMission pending = getPendingMission(storage);
            if (pending != null) {
                mission = pending;
                mMissionsPending.remove(pending);
            } else {
                int idx = getFinishedMissionIndex(storage);
                if (idx >= 0) {
                    mission = mMissionsFinished.get(idx);
                    isFinished = true;
                    mMissionsFinished.remove(idx);
                }
            }
            if (mission != null) {
                mission.storage = null;
            }
        } finally {
            writeLock.unlock();
        }

        if (mission != null) {
            mission.delete();
            if (isFinished) {
                mFinishedMissionStore.deleteMission(mission);
            }
        }
    }

    public void removePendingFetchMissionsBySource(@NonNull final String sourceUrl,
                                                    @Nullable final PendingFetchMission exclude) {
        awaitInit();
        final List<DownloadMission> toDelete = new ArrayList<>();
        writeLock.lock();
        try {
            final Iterator<DownloadMission> it = mMissionsPending.iterator();
            while (it.hasNext()) {
                final DownloadMission mission = it.next();
                if (mission == exclude) {
                    continue;
                }
                if (mission instanceof PendingFetchMission) {
                    final PendingFetchMission pfm = (PendingFetchMission) mission;
                    if (sourceUrl.equals(pfm.sourceUrl)) {
                        it.remove();
                        toDelete.add(mission);
                    }
                }
            }
        } finally {
            writeLock.unlock();
        }
        for (Mission mission : toDelete) {
            mission.delete();
        }
    }

    public void forgetMissionsBySource(@NonNull final String sourceUrl,
                                       @Nullable final Mission exclude) {
        awaitInit();
        final List<Mission> pendingDelete = new ArrayList<>();
        final List<FinishedMission> finishedDelete = new ArrayList<>();
        writeLock.lock();
        try {
            final Iterator<DownloadMission> it = mMissionsPending.iterator();
            while (it.hasNext()) {
                final DownloadMission mission = it.next();
                if (mission == exclude) {
                    continue;
                }
                if (mission instanceof PendingFetchMission) {
                    final PendingFetchMission pfm = (PendingFetchMission) mission;
                    if (sourceUrl.equals(pfm.sourceUrl)) {
                        it.remove();
                        pendingDelete.add(mission);
                    }
                } else if (sourceUrl.equals(mission.source)) {
                    it.remove();
                    pendingDelete.add(mission);
                }
            }
            for (int i = mMissionsFinished.size() - 1; i >= 0; i--) {
                final FinishedMission mission = mMissionsFinished.get(i);
                if (sourceUrl.equals(mission.source)) {
                    mMissionsFinished.remove(i);
                    finishedDelete.add(mission);
                }
            }
        } finally {
            writeLock.unlock();
        }
        for (Mission mission : pendingDelete) {
            mission.delete();
        }
        for (FinishedMission mission : finishedDelete) {
            mFinishedMissionStore.deleteMission(mission);
        }
    }

    public void tryRecover(DownloadMission mission) {
        if (mission.storage == null) return;
        if (!mission.storage.isInvalid() && mission.storage.existsAsFile()) return;

        StoredDirectoryHelper mainStorage = getMainStorage(mission.storage.getTag());

        if (!mission.storage.isInvalid() && mission.storage.create()) return;

        // using javaIO cannot recreate the file
        // using SAF in older devices (no tree available)
        //
        // force the user to pick again the save path
        mission.storage.invalidate();

        if (mainStorage == null) return;

        // if the user has changed the save path before this download, the original save path will be lost
        StoredFileHelper newStorage = mainStorage.createFile(mission.storage.getName(), mission.storage.getType());

        if (newStorage != null) mission.storage = newStorage;
    }


    /**
     * Get a pending mission by its path
     *
     * @param storage where the file possible is stored
     * @return the mission or null if no such mission exists
     */
    @Nullable
    private DownloadMission getPendingMission(StoredFileHelper storage) {
        for (DownloadMission mission : mMissionsPending) {
            if (mission.storage != null && mission.storage.equals(storage)) {
                return mission;
            }
        }
        return null;
    }

    /**
     * Get the index into {@link #mMissionsFinished} of a finished mission by its path, return
     * {@code -1} if there is no such mission. This function also checks if the matched mission's
     * file exists, and, if it does not, the related mission is forgotten about (like in {@link
     * #loadFinishedMissions()}) and {@code -1} is returned.
     *
     * @param storage where the file would be stored
     * @return the mission index or -1 if no such mission exists
     */
    private int getFinishedMissionIndex(StoredFileHelper storage) {
        for (int i = 0; i < mMissionsFinished.size(); i++) {
            if (mMissionsFinished.get(i).storage != null
                    && mMissionsFinished.get(i).storage.equals(storage)) {
                return i;
            }
        }
        return -1;
    }

    private int checkFinishedMissionValid(StoredFileHelper storage) {
        int idx;
        readLock.lock();
        try {
            idx = getFinishedMissionIndex(storage);
        } finally {
            readLock.unlock();
        }
        if (idx < 0) return -1;
        final StoredFileHelper missionStorage;
        readLock.lock();
        try {
            missionStorage = idx < mMissionsFinished.size() ? mMissionsFinished.get(idx).storage : null;
        } finally {
            readLock.unlock();
        }

        final boolean fileExists = (storage != null && !storage.isInvalid() && storage.existsAsFile() && storage.length() > 0)
                || (missionStorage != null && missionStorage.existsAsFile() && missionStorage.length() > 0);
        if (!fileExists) {
            FinishedMission toDelete = null;
            writeLock.lock();
            try {
                if (idx < mMissionsFinished.size()
                        && mMissionsFinished.get(idx).storage.equals(storage)) {
                    if (DEBUG) {
                        Log.d(TAG, "matched downloaded file removed: " + storage.getName());
                    }
                    toDelete = mMissionsFinished.get(idx);
                    mMissionsFinished.remove(idx);
                }
            } finally {
                writeLock.unlock();
            }
            if (toDelete != null) {
                mFinishedMissionStore.deleteMission(toDelete);
            }
            return -1;
        }
        return idx;
    }

    private Mission getAnyMission(StoredFileHelper storage) {
        readLock.lock();
        try {
            Mission mission = getPendingMission(storage);
            if (mission != null) return mission;

            int idx = getFinishedMissionIndex(storage);
            if (idx >= 0) return mMissionsFinished.get(idx);
        } finally {
            readLock.unlock();
        }

        return null;
    }

    int getRunningMissionsCount() {
        awaitInit();
        int count = 0;
        readLock.lock();
        try {
            for (DownloadMission mission : mMissionsPending) {
                if (mission.running && !mission.isPsFailed() && !mission.isFinished())
                    count++;
            }
        } finally {
            readLock.unlock();
        }

        return count;
    }

    int getRunningDownloadsCount() {
        awaitInit();
        int count = 0;
        readLock.lock();
        try {
            for (DownloadMission mission : mMissionsPending) {
                if (mission.running && !mission.isPsFailed() && !mission.isFinished()
                        && !(mission instanceof PendingFetchMission
                             && ((PendingFetchMission) mission).pendingFetch)) {
                    count++;
                }
            }
        } finally {
            readLock.unlock();
        }

        return count;
    }

    public int getMaxConcurrentDownloads() {
        return PendingFetchMission.getMaxConcurrentFetches();
    }

    public void setMaxConcurrentDownloads(int count) {
        PendingFetchMission.setMaxConcurrentFetches(count);
    }

    public boolean isQueueLimitEnabled() {
        return mPrefQueueLimit;
    }

    public void setQueueLimitEnabled(boolean enabled) {
        mPrefQueueLimit = enabled;
        runMissions();
    }

    public void pauseAllMissions(boolean force) {
        awaitInit();
        List<DownloadMission> toPause = new ArrayList<>();
        writeLock.lock();
        try {
            for (DownloadMission mission : mMissionsPending) {
                if (!mission.running || mission.isPsRunning() || mission.isFinished()) continue;

                if (force) {
                    mission.init = null;
                    mission.threads = new Thread[0];
                }

                toPause.add(mission);
            }
        } finally {
            writeLock.unlock();
        }
        for (DownloadMission mission : toPause) {
            mission.pause();
        }
    }

    public void startAllMissions() {
        awaitInit();
        final ArrayList<DownloadMission> toStart;
        writeLock.lock();
        try {
            mSelfMissionsControl = true;
            toStart = new ArrayList<>(mMissionsPending);
        } finally {
            writeLock.unlock();
        }
        mExecutor.execute(() -> {
            refreshConnectivityStatus();
            for (DownloadMission mission : toStart) {
                if (mission.running || mission.isCorrupt() || mission.isFinished()) continue;
                mission.setEnqueued(true);
            }
            runMissions();
            notifyQueueUpdated();
        });
    }

    public void retryAllErrorMissions() {
        awaitInit();
        final ArrayList<DownloadMission> toRetry;
        writeLock.lock();
        try {
            mSelfMissionsControl = true;
            toRetry = new ArrayList<>(mMissionsPending);
        } finally {
            writeLock.unlock();
        }
        mExecutor.execute(() -> {
            refreshConnectivityStatus();
            for (DownloadMission mission : toRetry) {
                if (mission.running) continue;
                if (mission.errCode == DownloadMission.ERROR_NOTHING) continue;
                if (mission.isFinished()) continue;

                boolean deadUrls = mission.urls == null || mission.urls.length == 0;
                boolean existedBefore = mission.storage != null
                        && !mission.storage.isInvalid()
                        && mission.storage.existsAsFile();

                if (mission instanceof PendingFetchMission) {
                    PendingFetchMission pfm = (PendingFetchMission) mission;
                    if (pfm.pendingFetch || pfm.done == 0 || pfm.isPsFailed()
                            || deadUrls || !existedBefore) {
                        pfm.refetch(false);
                    } else {
                        mission.errCode = DownloadMission.ERROR_NOTHING;
                        mission.errObject = null;
                        mission.setEnqueued(true);
                    }
                } else {
                    String missionTag = mission.storage != null && mission.storage.getTag() != null
                            ? mission.storage.getTag()
                            : (mission.kind == 'a' ? TAG_AUDIO : TAG_VIDEO);
                    boolean canUsePendingFetch = mission.source != null
                            && !mission.source.isEmpty()
                            && getMainStorage(missionTag) != null;

                    if (canUsePendingFetch
                            && (mission.done == 0 || mission.isPsFailed() || deadUrls)) {
                        convertToPendingFetchMission(mission, false);
                    } else {
                        tryRecover(mission);
                        if (!existedBefore && mission.storage != null && !mission.storage.isInvalid()) {
                            mission.done = 0;
                            mission.resetState(true, true, DownloadMission.ERROR_NOTHING);
                        }
                        if (mission.storage != null && !mission.storage.isInvalid()
                                && !deadUrls && !mission.isPsFailed()) {
                            mission.errCode = DownloadMission.ERROR_NOTHING;
                            mission.errObject = null;
                            mission.setEnqueued(true);
                        }
                    }
                }
            }
            runMissions();
            notifyQueueUpdated();
        });
    }

    public void convertToPendingFetchMission(@NonNull DownloadMission mission) {
        convertToPendingFetchMission(mission, true);
    }

    public void convertToPendingFetchMission(@NonNull DownloadMission mission, boolean triggerRunMissions) {
        writeLock.lock();
        try {
            mMissionsPending.remove(mission);
        } finally {
            writeLock.unlock();
        }
        mission.delete();

        try {
            int serviceId = org.schabi.newpipe.extractor.NewPipe
                    .getServiceByUrl(mission.source).getServiceId();
            boolean audioOnly = mission.kind == 'a';
            String qualityLabel = "Best";
            if (!audioOnly && mission.recoveryInfo != null && mission.recoveryInfo.length > 0
                    && mission.recoveryInfo[0] != null
                    && mission.recoveryInfo[0].getDesired() != null
                    && !mission.recoveryInfo[0].getDesired().isEmpty()) {
                qualityLabel = mission.recoveryInfo[0].getDesired();
            }
            String name = mission.storage != null && !mission.storage.isInvalid()
                    ? mission.storage.getName() : mission.source;
            String tag = audioOnly ? TAG_AUDIO : TAG_VIDEO;

            StoredFileHelper placeholderStorage = new StoredFileHelper(null, name,
                    StoredFileHelper.DEFAULT_MIME, tag);
            PendingFetchMission pfm = new PendingFetchMission(
                    serviceId, mission.source, name,
                    audioOnly, qualityLabel,
                    PendingFetchMission.BEHAVIOR_OVERWRITE, placeholderStorage);

            addPendingFetchMission(pfm, triggerRunMissions);
        } catch (final Exception ignored) {
            Log.w(TAG, "Failed to convert mission to PFM", ignored);
        }
    }

    /**
     * Set a pending download as finished
     *
     * @param mission the desired mission
     */
    void setFinished(DownloadMission mission) {
        boolean shouldPersist;
        writeLock.lock();
        try {
            mMissionsPending.remove(mission);
            shouldPersist = mission.storage != null
                    && mission.storage.srcName != null
                    && !mission.storage.srcName.endsWith(".tmp");
            if (shouldPersist) {
                mMissionsFinished.add(0, new FinishedMission(mission));
            }
        } finally {
            writeLock.unlock();
        }
        if (shouldPersist) {
            mFinishedMissionStore.addFinishedMission(mission);
        }
    }

    /**
     * runs one or multiple missions in from queue if possible
     *
     * @return true if one or multiple missions are running, otherwise, false
     */
    public boolean runMissions() {
        awaitInit();
        final ArrayList<DownloadMission> toStart;
        readLock.lock();
        try {
            if (mMissionsPending.size() < 1) return false;
            if (!canDownloadInCurrentNetwork()) return false;

            int runningDownloads = 0;
            int activeFetches = 0;
            int resolvedWaitingDownloads = 0;

            for (DownloadMission mission : mMissionsPending) {
                if (mission.isFinished()) continue;

                boolean isFetch = mission instanceof PendingFetchMission
                        && ((PendingFetchMission) mission).pendingFetch;

                if (mission.running) {
                    if (isFetch) {
                        activeFetches++;
                    } else if (!mission.isPsFailed()) {
                        runningDownloads++;
                    }
                } else if (mission.enqueued && !isFetch) {
                    resolvedWaitingDownloads++;
                }
            }

            toStart = new ArrayList<>();
            int maxConcurrentDownloads = getMaxConcurrentDownloads();
            int downloadsToStartBudget = mPrefQueueLimit ? Math.max(0, maxConcurrentDownloads - runningDownloads) : Integer.MAX_VALUE;
            int regularDownloadsAdded = 0;

            // Pipeline capacity for fetches:
            // When queue limit is enabled: maxConcurrentDownloads active slots + FETCH_LOOKAHEAD_LIMIT lookahead buffer in flight/ready.
            // When queue limit is disabled: fetches can proceed up to maxConcurrentFetches.
            int pipelineCapacity = maxConcurrentDownloads + FETCH_LOOKAHEAD_LIMIT;
            int inPipeline = runningDownloads + resolvedWaitingDownloads + activeFetches;
            int maxConcurrentFetches = PendingFetchMission.getMaxConcurrentFetches();
            int fetchBudget = mPrefQueueLimit
                    ? Math.min(Math.max(0, pipelineCapacity - inPipeline), Math.max(0, maxConcurrentFetches - activeFetches))
                    : Math.max(0, maxConcurrentFetches - activeFetches);
            int fetchesAdded = 0;

            for (DownloadMission mission : mMissionsPending) {
                if (mission.running || !mission.enqueued || mission.isFinished() || mission.isPsRunning())
                    continue;

                boolean isFetch = mission instanceof PendingFetchMission
                        && ((PendingFetchMission) mission).pendingFetch;

                if (!isFetch) {
                    if (regularDownloadsAdded < downloadsToStartBudget) {
                        toStart.add(mission);
                        regularDownloadsAdded++;
                    }
                } else {
                    if (fetchesAdded < fetchBudget) {
                        toStart.add(mission);
                        fetchesAdded++;
                    }
                }
            }
        } finally {
            readLock.unlock();
        }

        boolean flag = false;
        for (DownloadMission mission : toStart) {
            resumeMission(mission);
            if (mission.errCode != ERROR_NOTHING
                    && !(mission instanceof PendingFetchMission)) continue;
            flag = true;
        }
        return flag;
    }

    public MissionIterator getIterator() {
        awaitInit();
        mSelfMissionsControl = true;
        return new MissionIterator();
    }

    /**
     * Forget all finished downloads, but, doesn't delete any file
     */
    public void forgetFinishedDownloads() {
        awaitInit();
        List<FinishedMission> toDelete;
        writeLock.lock();
        try {
            toDelete = new ArrayList<>(mMissionsFinished);
            mMissionsFinished.clear();
        } finally {
            writeLock.unlock();
        }
        for (FinishedMission mission : toDelete) {
            mFinishedMissionStore.deleteMission(mission);
        }
    }

    private void notifyQueueUpdated() {
        if (mHandler != null) {
            mHandler.obtainMessage(DownloadManagerService.MESSAGE_QUEUE_UPDATED).sendToTarget();
        }
    }

    void refreshConnectivityStatus() {
        if (mContext == null) return;
        ConnectivityManager cm = ContextCompat.getSystemService(mContext, ConnectivityManager.class);
        if (cm == null) return;

        NetworkInfo info = cm.getActiveNetworkInfo();
        boolean connected = info != null && info.isConnected();
        if (!connected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                connected = caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
        }

        if (!connected) {
            mLastNetworkStatus = NetworkState.Unavailable;
        } else {
            mLastNetworkStatus = cm.isActiveNetworkMetered()
                    ? NetworkState.MeteredOperating
                    : NetworkState.Operating;
        }
    }

    private boolean canDownloadInCurrentNetwork() {
        if (mLastNetworkStatus == NetworkState.Unavailable
                || (mPrefMeteredDownloads && mLastNetworkStatus == NetworkState.MeteredOperating)) {
            refreshConnectivityStatus();
        }
        if (mLastNetworkStatus == NetworkState.Unavailable) return false;
        return !(mPrefMeteredDownloads && mLastNetworkStatus == NetworkState.MeteredOperating);
    }

    void handleConnectivityState(NetworkState currentStatus, boolean updateOnly) {
        if (currentStatus == mLastNetworkStatus) return;

        mLastNetworkStatus = currentStatus;
        if (currentStatus == NetworkState.Unavailable) return;

        if (!mSelfMissionsControl || updateOnly) {
            return;
        }

        awaitInit();

        boolean isMetered = mPrefMeteredDownloads && mLastNetworkStatus == NetworkState.MeteredOperating;

        List<DownloadMission> toPause = new ArrayList<>();

        readLock.lock();
        try {
            for (DownloadMission mission : mMissionsPending) {
                if (mission.isCorrupt() || mission.isPsRunning()) continue;
                if (mission.running && isMetered) {
                    toPause.add(mission);
                }
            }
        } finally {
            readLock.unlock();
        }

        for (DownloadMission mission : toPause) mission.pause();

        if (!isMetered) {
            runMissions();
        }
    }

    void updateMaximumAttempts() {
        awaitInit();
        writeLock.lock();
        try {
            for (DownloadMission mission : mMissionsPending) mission.maxRetry = mPrefMaxRetry;
        } finally {
            writeLock.unlock();
        }
    }

    public boolean canRecoverMission(DownloadMission mission) {
        if (mission == null) return false;

        // Can recover missions with progress lost or storage issues
        return mission.errCode == ERROR_PROGRESS_LOST ||
                mission.storage == null ||
                !mission.storage.existsAsFile();
    }

    public MissionState checkForExistingMission(StoredFileHelper storage) {
        awaitInit();
        readLock.lock();
        try {
            final DownloadMission pending = getPendingMission(storage);
            if (pending != null) {
                if (pending.isFinished()) {
                    return MissionState.Finished;
                }
                return pending.running ? MissionState.PendingRunning : MissionState.Pending;
            }
        } finally {
            readLock.unlock();
        }

        if (checkFinishedMissionValid(storage) >= 0) return MissionState.Finished;
        return MissionState.None;
    }

    private static boolean isDirectoryAvailable(File directory) {
        return directory != null && directory.canWrite() && directory.exists();
    }

    public static File pickAvailableTemporalDir(@NonNull Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (isDirectoryAvailable(dir)) return dir;

        dir = ctx.getFilesDir();
        if (isDirectoryAvailable(dir)) return dir;

        // this never should happen
        dir = ctx.getDir("muxing_tmp", Context.MODE_PRIVATE);
        if (isDirectoryAvailable(dir)) return dir;

        // fallback to cache dir
        dir = ctx.getCacheDir();
        if (isDirectoryAvailable(dir)) return dir;

        throw new RuntimeException("Not temporal directories are available");
    }

    private static void cleanOrphanedTempFiles(@Nullable File tempDir) {
        if (tempDir == null || !tempDir.isDirectory()) return;
        try {
            File[] files = tempDir.listFiles((dir, name) -> name.endsWith(".tmp"));
            if (files != null) {
                for (File file : files) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to clean orphaned temp files in: " + tempDir, e);
        }
    }

    @Nullable
    public StoredDirectoryHelper getMainStorage(@NonNull String tag) {
        if (tag.equals(TAG_AUDIO)) return mMainStorageAudio;
        if (tag.equals(TAG_VIDEO)) return mMainStorageVideo;

        Log.w(TAG, "Unknown download category, not [audio video]: " + tag);

        return null;// this never should happen
    }

    public class MissionIterator extends DiffUtil.Callback {
        final Object FINISHED = new Object();
        final Object PENDING = new Object();

        ArrayList<Object> snapshot;
        ArrayList<Object> current;
        ArrayList<Mission> hidden;

        boolean hasFinished = false;

        private MissionIterator() {
            hidden = new ArrayList<>(2);
            current = null;
            snapshot = getSpecialItems();
        }

        private ArrayList<Object> getSpecialItems() {
            readLock.lock();
            try {
                ArrayList<Mission> pending = new ArrayList<>(mMissionsPending);
                ArrayList<Mission> finished = new ArrayList<>(mMissionsFinished);
                List<Mission> remove = new ArrayList<>(hidden);

                remove.removeIf(mission -> {
                    if (mission instanceof DownloadMission) {
                        DownloadMission dm = (DownloadMission) mission;
                        if (canRecoverMission(dm)) {
                            return false;
                        }
                    }
                    return pending.remove(mission) || finished.remove(mission);
                });

                int fakeTotal = pending.size();
                if (fakeTotal > 0) fakeTotal++;

                fakeTotal += finished.size();
                if (finished.size() > 0) fakeTotal++;

                ArrayList<Object> list = new ArrayList<>(fakeTotal);
                if (pending.size() > 0) {
                    list.add(PENDING);
                    list.addAll(pending);
                }
                if (finished.size() > 0) {
                    list.add(FINISHED);
                    list.addAll(finished);
                }

                hasFinished = finished.size() > 0;

                return list;
            } finally {
                readLock.unlock();
            }
        }

        public MissionItem getItem(int position) {
            Object object = snapshot.get(position);

            if (object == PENDING) return new MissionItem(SPECIAL_PENDING);
            if (object == FINISHED) return new MissionItem(SPECIAL_FINISHED);

            return new MissionItem(SPECIAL_NOTHING, (Mission) object);
        }

        public int getSpecialAtItem(int position) {
            Object object = snapshot.get(position);

            if (object == PENDING) return SPECIAL_PENDING;
            if (object == FINISHED) return SPECIAL_FINISHED;

            return SPECIAL_NOTHING;
        }


        public void start() {
            current = getSpecialItems();
        }

        public void end() {
            snapshot = current;
            current = null;
        }

        public void hide(Mission mission) {
            hidden.add(mission);
        }

        public void unHide(Mission mission) {
            hidden.remove(mission);
        }

        public boolean hasFinishedMissions() {
            return hasFinished;
        }

        /**
         * Check if exists missions running and paused. Corrupted and hidden missions are not counted
         *
         * @return two-dimensional array contains the current missions state.
         * 1° entry: true if has at least one mission running
         * 2° entry: true if has at least one mission paused
         */
        public boolean[] hasValidPendingMissions() {
            boolean running = false;
            boolean paused = false;

            readLock.lock();
            try {
                for (DownloadMission mission : mMissionsPending) {
                    if (hidden.contains(mission) || mission.isCorrupt())
                        continue;

                    if (mission.running)
                        running = true;
                    else
                        paused = true;
                }
            } finally {
                readLock.unlock();
            }

            return new boolean[]{running, paused};
        }

        public boolean hasErrorMissions() {
            readLock.lock();
            try {
                for (DownloadMission mission : mMissionsPending) {
                    if (hidden.contains(mission))
                        continue;
                    if (mission.errCode != DownloadMission.ERROR_NOTHING && !mission.running
                            && !mission.isFinished())
                        return true;
                }
            } finally {
                readLock.unlock();
            }
            return false;
        }

        public boolean hasPendingFetchMissions() {
            readLock.lock();
            try {
                for (DownloadMission mission : mMissionsPending) {
                    if (hidden.contains(mission))
                        continue;
                    if (mission instanceof PendingFetchMission
                            && ((PendingFetchMission) mission).pendingFetch)
                        return true;
                }
            } finally {
                readLock.unlock();
            }
            return false;
        }


        @Override
        public int getOldListSize() {
            return snapshot.size();
        }

        @Override
        public int getNewListSize() {
            return current.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return snapshot.get(oldItemPosition) == current.get(newItemPosition);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            Object x = snapshot.get(oldItemPosition);
            Object y = current.get(newItemPosition);

            if (x instanceof Mission && y instanceof Mission) {
                StoredFileHelper sx = ((Mission) x).storage;
                StoredFileHelper sy = ((Mission) y).storage;
                return sx != null && sx.equals(sy);
            }

            return false;
        }
    }

    public static class MissionItem {
        public int special;
        public Mission mission;

        MissionItem(int s, Mission m) {
            special = s;
            mission = m;
        }

        MissionItem(int s) {
            this(s, null);
        }
    }

}
