package us.shandian.giga.get;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.FilenameUtils;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.R;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import us.shandian.giga.postprocessing.Postprocessing;
import us.shandian.giga.service.DownloadManager;
import us.shandian.giga.service.DownloadManagerService;
import us.shandian.giga.util.BilibiliTempHelper;
import us.shandian.giga.util.Utility;

public class PendingFetchMission extends DownloadMission {

    private static final String TAG = "PendingFetchMission";
    private static final long serialVersionUID = 2L;

    public static final int ERROR_FETCH_FAILED = 2000;
    public static final int ERROR_NO_STORAGE = 2001;

    public static final int BEHAVIOR_SKIP = 0;
    public static final int BEHAVIOR_OVERWRITE = 1;
    public static final int BEHAVIOR_UNIQUE_NAME = 2;

    private static final int MAX_CONCURRENT_FETCHES = 3;
    private static final AtomicInteger activeFetchCount = new AtomicInteger(0);
    private static volatile int maxConcurrentFetches = MAX_CONCURRENT_FETCHES;

    public static void setMaxConcurrentFetches(int max) {
        maxConcurrentFetches = Math.max(1, max);
    }

    public boolean pendingFetch = true;
    public int sourceServiceId;
    public String sourceUrl;
    public String sourceName;
    public boolean audioOnly;
    public String qualityLabel;
    public int existingFileBehavior;

    public transient volatile Disposable fetchDisposable;

    private transient DownloadManager downloadManager;

    public PendingFetchMission(final int sourceServiceId,
                               @NonNull final String sourceUrl,
                               @NonNull final String sourceName,
                               final boolean audioOnly,
                               @NonNull final String qualityLabel,
                               final int existingFileBehavior,
                               @NonNull final StoredFileHelper placeholderStorage) {
        super(new String[]{""}, placeholderStorage, audioOnly ? 'a' : 'v', null, null);
        this.sourceServiceId = sourceServiceId;
        this.sourceUrl = sourceUrl;
        this.sourceName = sourceName;
        this.audioOnly = audioOnly;
        this.qualityLabel = qualityLabel;
        this.existingFileBehavior = existingFileBehavior;
        this.enqueued = true;
    }

    public void setDownloadManager(@NonNull final DownloadManager dm) {
        this.downloadManager = dm;
    }

    @Override
    public void start() {
        if (pendingFetch) {
            startFetch();
            return;
        }
        super.start();
    }

    @Override
    public boolean hasInvalidStorage() {
        if (pendingFetch) return false;
        return super.hasInvalidStorage();
    }

    @Override
    public boolean isCorrupt() {
        if (pendingFetch) {
            return errCode != ERROR_NOTHING
                    && errCode != ERROR_FETCH_FAILED
                    && errCode != ERROR_NO_STORAGE;
        }
        return super.isCorrupt();
    }

    public void startFetch() {
        if (fetchDisposable != null && !fetchDisposable.isDisposed()) return;

        errCode = ERROR_NOTHING;
        errObject = null;

        if (activeFetchCount.get() >= maxConcurrentFetches) {
            enqueued = true;
            running = false;
            return;
        }

        final StoredDirectoryHelper dir = openStorageDir();
        if (dir == null) {
            pendingFetch = false;
            errCode = ERROR_NO_STORAGE;
            errObject = null;
            running = false;
            enqueued = false;
            if (mHandler != null) {
                mHandler.obtainMessage(DownloadManagerService.MESSAGE_ERROR, this).sendToTarget();
            }
            return;
        }

        running = true;
        activeFetchCount.incrementAndGet();

        if (mHandler != null) {
            mHandler.obtainMessage(DownloadManagerService.MESSAGE_RUNNING, this).sendToTarget();
        }

        fetchDisposable = ExtractorHelper.getStreamInfo(sourceServiceId, sourceUrl, false)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(this::onFetchSuccess, this::onFetchError);
    }

    @Override
    void doRecover(int errorCode) {
        if (!pendingFetch) {
            refetch();
            return;
        }
        super.doRecover(errorCode);
    }

    public void refetch() {
        if (fetchDisposable != null && !fetchDisposable.isDisposed()) {
            fetchDisposable.dispose();
            fetchDisposable = null;
        }

        if (!pendingFetch) {
            if (psAlgorithm != null && psAlgorithm.name != null
                    && psAlgorithm.name.equals(Postprocessing.BILIBILI_MUXER)
                    && storage != null && !storage.isInvalid()) {
                Utility.removeTempFileOfDownloadedVideo(storage);
            }
            if (storage != null && !storage.isInvalid()) {
                storage.delete();
            }
        }

        urls = new String[]{""};
        offsets = new long[]{0};
        blocks = null;
        current = 0;
        done = 0;
        length = 0;
        nearLength = 0;
        unknownLength = false;
        psAlgorithm = null;
        psState = 0;
        existingFileBehavior = BEHAVIOR_OVERWRITE;

        storage = new StoredFileHelper(null, sourceName,
                StoredFileHelper.DEFAULT_MIME,
                audioOnly ? DownloadManager.TAG_AUDIO : DownloadManager.TAG_VIDEO);

        pendingFetch = true;
        errCode = ERROR_NOTHING;
        errObject = null;
        enqueued = true;
        running = false;

        writeThisToFile();
        startFetch();
        if (downloadManager != null) {
            downloadManager.runMissions();
        }
    }

    @Nullable
    StoredDirectoryHelper openStorageDir() {
        if (downloadManager == null) return null;
        return downloadManager.getMainStorage(audioOnly ? DownloadManager.TAG_AUDIO : DownloadManager.TAG_VIDEO);
    }

    private void onFetchSuccess(@NonNull final StreamInfo info) {
        fetchDisposable = null;
        activeFetchCount.decrementAndGet();
        String bilibiliTmpBase = null;
        try {
            bilibiliTmpBase = promoteToDownload(info);
        } catch (final Exception e) {
            Log.e(TAG, "Error promoting mission for " + sourceUrl, e);
            cleanupBilibiliTempFiles(bilibiliTmpBase);
            pendingFetch = false;
            errCode = ERROR_FETCH_FAILED;
            errObject = e;
            running = false;
            enqueued = false;
            if (mHandler != null) {
                mHandler.obtainMessage(DownloadManagerService.MESSAGE_ERROR, this).sendToTarget();
            }
        } finally {
            if (downloadManager != null) {
                downloadManager.runMissions();
            }
        }
    }

    private void onFetchError(@NonNull final Throwable error) {
        Log.e(TAG, "Fetch failed for " + sourceUrl, error);
        fetchDisposable = null;
        activeFetchCount.decrementAndGet();
        pendingFetch = false;
        running = false;
        errCode = ERROR_FETCH_FAILED;
        errObject = error instanceof Exception ? (Exception) error : new Exception(error);
        enqueued = true;
        if (mHandler != null) {
            mHandler.obtainMessage(DownloadManagerService.MESSAGE_ERROR, this).sendToTarget();
        }
        if (downloadManager != null) {
            downloadManager.runMissions();
        }
    }

    @Nullable
    private String promoteToDownload(@NonNull final StreamInfo info) throws IOException {
        if (context == null) {
            throw new IOException("No context available");
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String template = prefs.getString(
                context.getString(R.string.download_filename_template_key),
                context.getString(R.string.download_filename_template_default_value));
        final String baseName = FilenameUtils.buildFilename(context, template, info);

        final StreamResolution resolution = resolveStreams(info);
        final String ext = resolveExtension(resolution.primary);
        final String fullFilename = baseName + "." + ext;
        final String mime = resolveMime(resolution.primary);

        final StoredDirectoryHelper dir = openStorageDir();
        if (dir == null) {
            throw new IOException("Download folder not configured");
        }

        final StoredFileHelper storage = createStorage(dir, fullFilename, mime, info.getUrl());
        if (storage == null) {
            return null;
        }

        String bilibiliTmpBase = setupBilibiliPostprocessing(dir, fullFilename, resolution, info);

        final String[] urls;
        final MissionRecoveryInfo[] recoveryInfo;
        if (resolution.secondary == null) {
            urls = new String[]{resolution.primary.getContent()};
            recoveryInfo = new MissionRecoveryInfo[]{new MissionRecoveryInfo(resolution.primary)};
        } else {
            urls = new String[]{resolution.primary.getContent(), resolution.secondary.getContent()};
            recoveryInfo = new MissionRecoveryInfo[]{
                    new MissionRecoveryInfo(resolution.primary),
                    new MissionRecoveryInfo(resolution.secondary)
            };
        }

        try {
            applyResolvedState(resolution, urls, recoveryInfo, storage, kindFromStream(info, resolution), mime, prefs);
            return bilibiliTmpBase;
        } catch (final Exception e) {
            cleanupBilibiliTempFiles(bilibiliTmpBase);
            throw e;
        }
    }

    private StreamResolution resolveStreams(@NonNull final StreamInfo info) throws IOException {
        Stream primary;
        Stream secondary = null;
        String psName = null;

        if (audioOnly) {
            final List<AudioStream> downloadableAudio =
                    ListHelper.filterDownloadableAudioStreams(info.getAudioStreams());
            if (downloadableAudio.isEmpty()) {
                throw new IOException("No audio streams available for " + info.getName());
            }
            final int audioIdx = ListHelper.getHighestQualityAudioIndex(null, downloadableAudio);
            primary = audioIdx >= 0 ? downloadableAudio.get(audioIdx) : downloadableAudio.get(0);

            if (info.getService() == ServiceList.NicoNico) {
                psName = Postprocessing.NICONICO_MUXER;
            } else if (primary.getFormat() == MediaFormat.M4A
                    && info.getService() != ServiceList.BiliBili) {
                psName = Postprocessing.ALGORITHM_M4A_NO_DASH;
            } else if (primary.getFormat() == MediaFormat.WEBMA_OPUS) {
                psName = Postprocessing.ALGORITHM_OGG_FROM_WEBM_DEMUXER;
            }
        } else {
            final List<VideoStream> sortedVideoStreams = ListHelper.getSortedStreamVideosList(
                    context, info.getVideoStreams(), info.getVideoOnlyStreams(),
                    false, true);

            if (sortedVideoStreams.isEmpty()) {
                throw new IOException("No video streams for " + info.getName());
            }

            final int videoIdx = ListHelper.getDownloadResolutionIndex(
                    context, qualityLabel, sortedVideoStreams);
            final VideoStream videoStream = sortedVideoStreams.get(videoIdx < 0 ? 0 : videoIdx);
            primary = videoStream;

            if (videoStream.isVideoOnly() && videoStream.getFormat() != null) {
                final List<AudioStream> downloadableAudio =
                        ListHelper.filterDownloadableAudioStreams(info.getAudioStreams());
                if (!downloadableAudio.isEmpty()) {
                    final int ai = ListHelper.getHighestQualityAudioIndex(null, downloadableAudio);
                    secondary = ai >= 0 ? downloadableAudio.get(ai) : downloadableAudio.get(0);
                }
            }

            if (secondary != null) {
                if (info.getService() == ServiceList.BiliBili) {
                    psName = Postprocessing.BILIBILI_MUXER;
                } else if (info.getService() == ServiceList.NicoNico) {
                    psName = Postprocessing.NICONICO_MUXER;
                } else if (videoStream.getFormat() == MediaFormat.MPEG_4) {
                    psName = Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER;
                } else {
                    psName = Postprocessing.ALGORITHM_WEBM_MUXER;
                }
            }
        }

        return new StreamResolution(primary, secondary, psName, null);
    }

    @Nullable
    private StoredFileHelper createStorage(@NonNull final StoredDirectoryHelper dir,
                                           @NonNull final String fullFilename,
                                           @NonNull final String mime,
                                           @NonNull final String sourceUrl) throws IOException {
        switch (existingFileBehavior) {
            case BEHAVIOR_SKIP:
                if (dir.fileExists(fullFilename)) {
                    notifySkipped(fullFilename);
                    downloadManager.deleteMission(this);
                    return null;
                }
                if (downloadManager != null) {
                    downloadManager.forgetMissionsBySource(sourceUrl, this);
                }
                return dir.createFile(fullFilename, mime);
            case BEHAVIOR_UNIQUE_NAME:
                return dir.createUniqueFile(fullFilename, mime);
            case BEHAVIOR_OVERWRITE:
            default:
                final StoredFileHelper storage = dir.createFile(fullFilename, mime);
                if (downloadManager != null) {
                    downloadManager.forgetMission(storage);
                    downloadManager.forgetMissionsBySource(sourceUrl, this);
                }
                return storage;
        }
    }

    @Nullable
    private String setupBilibiliPostprocessing(@NonNull final StoredDirectoryHelper dir,
                                                 @NonNull final String fullFilename,
                                                 @NonNull final StreamResolution resolution,
                                                 @NonNull final StreamInfo info) {
        if (resolution.psName != null && resolution.secondary != null
                && !audioOnly && info.getService() == ServiceList.BiliBili) {
            BilibiliTempHelper.createSidecarFiles(dir, fullFilename);
            return fullFilename;
        }
        return null;
    }

    private char kindFromStream(@NonNull final StreamInfo info, @NonNull final StreamResolution resolution) {
        if (audioOnly) return 'a';

        if (resolution.primary instanceof VideoStream) {
            return 'v';
        }
        return 'v';
    }

    private void applyResolvedState(@NonNull final StreamResolution resolution,
                                     @NonNull final String[] urls,
                                     @NonNull final MissionRecoveryInfo[] recoveryInfo,
                                     @NonNull final StoredFileHelper storage,
                                     final char kind,
                                     @NonNull final String mime,
                                     @NonNull final SharedPreferences prefs) {
        this.source = sourceUrl;
        this.urls = urls;
        this.offsets = new long[urls.length];
        this.kind = kind;
        this.storage = storage;
        this.nearLength = 0;
        this.recoveryInfo = recoveryInfo;
        this.threadCount = prefs.getInt(
                context.getString(R.string.default_download_threads), 3);

        if (resolution.psName != null) {
            this.psAlgorithm = Postprocessing.getAlgorithm(resolution.psName, resolution.psArgs);
            this.psAlgorithm.setTemporalDir(DownloadManager.pickAvailableTemporalDir(context));
        }

        this.pendingFetch = false;
        this.running = false;
        this.enqueued = true;
        this.errCode = ERROR_NOTHING;
        this.errObject = null;

        writeThisToFile();

        super.start();
    }

    @NonNull
    private static String resolveExtension(@NonNull final Stream stream) {
        if (stream.getFormat() == MediaFormat.WEBMA_OPUS) {
            return "opus";
        }
        final String suffix = stream.getFormat() != null ? stream.getFormat().suffix : null;
        return suffix != null && !suffix.isEmpty() ? suffix : "mp4";
    }

    @NonNull
    private static String resolveMime(@NonNull final Stream stream) {
        if (stream.getFormat() == MediaFormat.WEBMA_OPUS) {
            return "audio/ogg";
        }
        final String mime = stream.getFormat() != null ? stream.getFormat().mimeType : null;
        return mime != null && !mime.isEmpty() ? mime : StoredFileHelper.DEFAULT_MIME;
    }

    private void notifySkipped(@NonNull final String filename) {
        if (downloadManager != null) {
            downloadManager.notifySkipped(filename);
        }
    }

    private void cleanupBilibiliTempFiles(@Nullable final String tmpBase) {
        if (tmpBase == null) return;
        try {
            final StoredDirectoryHelper dir = openStorageDir();
            BilibiliTempHelper.cleanupSidecarFiles(dir, tmpBase);
        } catch (final Exception ignored) { }
    }

    static class StreamResolution {
        final Stream primary;
        @Nullable final Stream secondary;
        @Nullable final String psName;
        @Nullable final String[] psArgs;

        StreamResolution(@NonNull Stream primary,
                         @Nullable Stream secondary,
                         @Nullable String psName,
                         @Nullable String[] psArgs) {
            this.primary = primary;
            this.secondary = secondary;
            this.psName = psName;
            this.psArgs = psArgs;
        }
    }
}