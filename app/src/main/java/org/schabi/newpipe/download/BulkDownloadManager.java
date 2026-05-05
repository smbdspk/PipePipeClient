package org.schabi.newpipe.download;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.FilenameUtils;
import org.schabi.newpipe.util.ListHelper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import us.shandian.giga.get.MissionRecoveryInfo;
import us.shandian.giga.service.DownloadManager;
import us.shandian.giga.service.DownloadManagerService;
import us.shandian.giga.postprocessing.Postprocessing;

/**
 * Manages bulk downloads initiated from multi-select mode in
 * {@link org.schabi.newpipe.fragments.list.channel.ChannelVideosFragment} and
 * {@link org.schabi.newpipe.fragments.list.playlist.PlaylistFragment}.
 *
 * <p>Fetches {@link StreamInfo} for each selected item asynchronously (up to
 * {@link #MAX_CONCURRENT_FETCHES} at a time), selects appropriate streams, applies the user's
 * conflict-resolution preference, and enqueues missions via
 * {@link DownloadManagerService#startMission}. Progress is reported through a persistent
 * notification.</p>
 */
public class BulkDownloadManager {

    private static final String TAG = "BulkDownloadManager";

    /** Notification channel ID for bulk download progress. */
    static final String NOTIF_CHANNEL_ID = "bulk_download_channel";
    /** Notification ID for the persistent progress notification. */
    static final int NOTIF_ID = 1101;

    /** Maximum simultaneous StreamInfo fetches. */
    private static final int MAX_CONCURRENT_FETCHES = 3;

    // -----------------------------------------------------------------------
    // Per-item outcome
    // -----------------------------------------------------------------------

    private enum Outcome { SUCCESS, SKIPPED, FAILED }

    private static final class ItemResult {
        final Outcome outcome;
        ItemResult(final Outcome outcome) { this.outcome = outcome; }
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    /**
     * Fire-and-forget: starts the asynchronous bulk download pipeline.
     *
     * <p>Must be called with {@link Context#getApplicationContext()} so that
     * the manager outlives the originating fragment.</p>
     */
    public static void startBulkDownload(@NonNull final Context context,
                                         @NonNull final List<StreamInfoItem> items,
                                         final boolean audioOnly,
                                         @NonNull final String qualityLabel,
                                         @NonNull final BulkDownloadDialog.ExistingFileBehavior behavior) {
        new BulkDownloadManager(context, items, audioOnly, qualityLabel, behavior).run();
    }

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------

    private final Context context;
    private final List<StreamInfoItem> items;
    private final boolean audioOnly;
    private final String qualityLabel;
    private final BulkDownloadDialog.ExistingFileBehavior behavior;

    private final int total;
    private final AtomicInteger done = new AtomicInteger(0);
    private final AtomicInteger succeeded = new AtomicInteger(0);
    private final AtomicInteger skipped = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);

    private NotificationManagerCompat notifManager;
    private NotificationCompat.Builder notifBuilder;

    private BulkDownloadManager(@NonNull final Context context,
                                 @NonNull final List<StreamInfoItem> items,
                                 final boolean audioOnly,
                                 @NonNull final String qualityLabel,
                                 @NonNull final BulkDownloadDialog.ExistingFileBehavior behavior) {
        this.context = context.getApplicationContext();
        this.items = items;
        this.audioOnly = audioOnly;
        this.qualityLabel = qualityLabel;
        this.behavior = behavior;
        this.total = items.size();
    }

    // -----------------------------------------------------------------------
    // Pipeline
    // -----------------------------------------------------------------------

    private void run() {
        // Validate storage before starting the pipeline
        final StoredDirectoryHelper storageDir = openStorageDir();
        if (storageDir == null) {
            // Both audio and video storage paths are unset — tell the user and abort
            AndroidSchedulers.mainThread().scheduleDirect(() ->
                    Toast.makeText(context,
                            context.getString(R.string.bulk_download_no_folder),
                            Toast.LENGTH_LONG).show());
            return;
        }

        ensureNotificationChannel();
        showInitialNotification();

        Observable.fromIterable(items)
                .flatMap(item -> fetchAndEnqueue(item, storageDir)
                        .subscribeOn(Schedulers.io()), MAX_CONCURRENT_FETCHES)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> onItemDone(result),
                        error  -> {
                            Log.e(TAG, "Unexpected pipeline error", error);
                            showCompletionNotification();
                        },
                        this::showCompletionNotification
                );
    }

    // -----------------------------------------------------------------------
    // Per-item fetch + enqueue
    // -----------------------------------------------------------------------

    private Observable<ItemResult> fetchAndEnqueue(@NonNull final StreamInfoItem item,
                                                    @NonNull final StoredDirectoryHelper dir) {
        return ExtractorHelper
                .getStreamInfo(item.getServiceId(), item.getUrl(), false)
                .toObservable()
                .map(info -> enqueue(info, dir))
                .onErrorReturn(e -> {
                    Log.e(TAG, "Failed to fetch stream info for " + item.getUrl(), e);
                    return new ItemResult(Outcome.FAILED);
                });
    }

    /**
     * Selects appropriate streams, resolves filename, handles conflict, and calls
     * {@link DownloadManagerService#startMission}.
     */
    @NonNull
    private ItemResult enqueue(@NonNull final StreamInfo info,
                                @NonNull final StoredDirectoryHelper dir) {
        try {
            return enqueueInternal(info, dir);
        } catch (final Exception e) {
            Log.e(TAG, "Error queuing download for " + info.getUrl(), e);
            return new ItemResult(Outcome.FAILED);
        }
    }

    @NonNull
    private ItemResult enqueueInternal(@NonNull final StreamInfo info,
                                        @NonNull final StoredDirectoryHelper dir)
            throws IOException {

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final int threads = prefs.getInt(context.getString(R.string.default_download_threads), 3);

        // ── Build filename from template (no extension yet) ──────────────
        final String template = prefs.getString(
                context.getString(R.string.download_filename_template_key),
                context.getString(R.string.download_filename_template_default_value));
        final String baseName = FilenameUtils.buildFilename(template, info);

        // ── Select streams and derive muxer parameters ───────────────────
        final Stream primary;
        final Stream secondary; // null when no muxing needed
        final char kind;
        String psName = null;
        final String[] psArgs = null;
        long nearLength = 0;

        if (audioOnly) {
            // Always use best available downloadable audio stream
            final List<AudioStream> downloadableAudio =
                    ListHelper.filterDownloadableAudioStreams(info.getAudioStreams());
            if (downloadableAudio.isEmpty()) {
                Log.w(TAG, "No downloadable audio streams for " + info.getUrl());
                return new ItemResult(Outcome.FAILED);
            }
            // Pick highest quality audio (index from getHighestQualityAudioIndex is package-private;
            // filterDownloadableAudioStreams returns best-first by convention, so index 0 is safest)
            final int audioIdx = ListHelper.getHighestQualityAudioIndex(null, downloadableAudio);
            primary = audioIdx >= 0 ? downloadableAudio.get(audioIdx) : downloadableAudio.get(0);
            secondary = null;
            kind = 'a';

            if (info.getService() == ServiceList.NicoNico) {
                psName = Postprocessing.NICONICO_MUXER;
            } else if (primary.getFormat() == MediaFormat.M4A
                    && info.getService() != ServiceList.BiliBili) {
                psName = Postprocessing.ALGORITHM_M4A_NO_DASH;
            } else if (primary.getFormat() == MediaFormat.WEBMA_OPUS) {
                psName = Postprocessing.ALGORITHM_OGG_FROM_WEBM_DEMUXER;
            }

        } else {
            // Video path: build sorted list (same filtering as DownloadDialog)
            final List<VideoStream> sortedVideoStreams = ListHelper.getSortedStreamVideosList(
                    context, info.getVideoStreams(), info.getVideoOnlyStreams(),
                    false /* descending */, true /* prefer video-only */);

            if (sortedVideoStreams.isEmpty()) {
                Log.w(TAG, "No video streams for " + info.getUrl());
                return new ItemResult(Outcome.FAILED);
            }

            final int videoIdx = ListHelper.getDownloadResolutionIndex(
                    context, qualityLabel, sortedVideoStreams);
            if (videoIdx < 0) {
                Log.w(TAG, "Could not resolve video stream for quality=" + qualityLabel);
                return new ItemResult(Outcome.FAILED);
            }

            final VideoStream videoStream = sortedVideoStreams.get(videoIdx);
            primary = videoStream;
            kind = 'v';

            // Secondary audio stream — only needed for video-only streams
            Stream sec = null;
            if (videoStream.isVideoOnly()) {
                final List<AudioStream> downloadableAudio =
                        ListHelper.filterDownloadableAudioStreams(info.getAudioStreams());
                if (!downloadableAudio.isEmpty()) {
                    final int ai = ListHelper.getHighestQualityAudioIndex(null, downloadableAudio);
                    sec = ai >= 0 ? downloadableAudio.get(ai) : downloadableAudio.get(0);
                }
            }
            secondary = sec;

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
                // nearLength is best-effort; we skip the size fetch here to keep it simple
            }
        }

        // ── Derive full filename with extension ───────────────────────────
        final String ext = resolveExtension(primary);
        final String fullFilename = baseName + "." + ext;
        final String mime = resolveMime(primary);

        // ── Conflict resolution ───────────────────────────────────────────
        final StoredFileHelper storage;
        switch (behavior) {
            case SKIP:
                if (dir.fileExists(fullFilename)) {
                    return new ItemResult(Outcome.SKIPPED);
                }
                storage = dir.createFile(fullFilename, mime);
                break;
            case OVERWRITE:
                storage = dir.createFile(fullFilename, mime);
                break;
            case UNIQUE_NAME:
                storage = dir.createUniqueFile(fullFilename, mime);
                break;
            default:
                storage = dir.createFile(fullFilename, mime);
        }

        if (storage == null || !storage.canWrite()) {
            Log.e(TAG, "Cannot write to storage for " + fullFilename);
            return new ItemResult(Outcome.FAILED);
        }

        // ── Build urls[] and recoveryInfo[] ──────────────────────────────
        final String[] urls;
        final MissionRecoveryInfo[] recoveryInfo;
        if (secondary == null) {
            urls = new String[]{ primary.getContent() };
            recoveryInfo = new MissionRecoveryInfo[]{ new MissionRecoveryInfo(primary) };
        } else {
            urls = new String[]{ primary.getContent(), secondary.getContent() };
            recoveryInfo = new MissionRecoveryInfo[]{
                    new MissionRecoveryInfo(primary),
                    new MissionRecoveryInfo(secondary)
            };
        }

        // ── Bilibili sidecar: pre-create .tmp.mp4 and .tmp placeholder files ─
        // BiliBiliMp4Muxer.mux() opens these by URI before downloading is done;
        // the files must already exist (with write permission granted) or the
        // muxer fails immediately. This mirrors what DownloadDialog does at
        // lines 935-936 and 1001-1002 of its checkSelectedDownload() path.
        if (!audioOnly && info.getService() == ServiceList.BiliBili) {
            dir.createFile(fullFilename.replace(".mp4", ".tmp.mp4"), "video/mp4");
            dir.createFile(fullFilename.replace(".mp4", ".tmp"),
                    String.valueOf(org.schabi.newpipe.extractor.MediaFormat.M4A));
        }

        DownloadManagerService.startMission(context, urls, storage, kind, threads,
                info.getUrl(), psName, psArgs, nearLength, recoveryInfo);

        return new ItemResult(Outcome.SUCCESS);
    }

    // -----------------------------------------------------------------------
    // Notification helpers
    // -----------------------------------------------------------------------

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    context.getString(R.string.bulk_download_notif_done),
                    NotificationManager.IMPORTANCE_LOW);
            final NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
        notifManager = NotificationManagerCompat.from(context);
    }

    private void showInitialNotification() {
        notifBuilder = new NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(
                        R.string.bulk_download_notif_preparing, total))
                .setProgress(total, 0, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        notifManager.notify(NOTIF_ID, notifBuilder.build());
    }

    private void onItemDone(@NonNull final ItemResult result) {
        switch (result.outcome) {
            case SUCCESS:  succeeded.incrementAndGet(); break;
            case SKIPPED:  skipped.incrementAndGet();  break;
            default:       failed.incrementAndGet();   break;
        }
        final int d = done.incrementAndGet();
        notifBuilder
                .setContentTitle(context.getString(
                        R.string.bulk_download_notif_progress, d, total))
                .setProgress(total, d, false);
        notifManager.notify(NOTIF_ID, notifBuilder.build());
    }

    private void showCompletionNotification() {
        final NotificationCompat.Builder fin = new NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.bulk_download_notif_done))
                .setContentText(context.getString(
                        R.string.bulk_download_notif_summary,
                        succeeded.get(), skipped.get(), failed.get()))
                .setOngoing(false)
                .setAutoCancel(true);
        notifManager.notify(NOTIF_ID, fin.build());
    }

    // -----------------------------------------------------------------------
    // Storage helpers
    // -----------------------------------------------------------------------

    /**
     * Opens the {@link StoredDirectoryHelper} for the configured download path.
     * Returns {@code null} if the relevant preference is unset or the URI is invalid.
     */
    @Nullable
    private StoredDirectoryHelper openStorageDir() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String prefKey = audioOnly
                ? context.getString(R.string.download_path_audio_key)
                : context.getString(R.string.download_path_video_key);
        final String tag = audioOnly ? DownloadManager.TAG_AUDIO : DownloadManager.TAG_VIDEO;

        final String uriStr = prefs.getString(prefKey, null);
        if (uriStr == null || uriStr.isEmpty()) {
            // Try the other path as a last resort (some users only set one)
            final String altKey = audioOnly
                    ? context.getString(R.string.download_path_video_key)
                    : context.getString(R.string.download_path_audio_key);
            final String altUri = prefs.getString(altKey, null);
            if (altUri == null || altUri.isEmpty()) {
                return null;
            }
            return tryOpenDir(Uri.parse(altUri), tag);
        }
        return tryOpenDir(Uri.parse(uriStr), tag);
    }

    @Nullable
    private StoredDirectoryHelper tryOpenDir(@NonNull final Uri uri, @NonNull final String tag) {
        try {
            return new StoredDirectoryHelper(context, uri, tag);
        } catch (final IOException e) {
            Log.e(TAG, "Cannot open download directory: " + uri, e);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Stream metadata helpers
    // -----------------------------------------------------------------------

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
}
