package org.schabi.newpipe.download;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.util.SerializedCache;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.reactivex.rxjava3.schedulers.Schedulers;
import us.shandian.giga.get.PendingFetchMission;
import us.shandian.giga.service.DownloadManagerService;

public class BulkDownloadDialog extends DialogFragment {

    public static final String TAG = "BULK_DOWNLOAD";

    public enum ExistingFileBehavior {
        SKIP,
        OVERWRITE,
        UNIQUE_NAME
    }

    public interface Listener {
        void onBulkDownloadConfirmed(boolean audioOnly,
                                     @NonNull String qualityLabel,
                                     @NonNull ExistingFileBehavior behavior,
                                     int fetchThreads);
    }

    private static final String ARG_ITEM_COUNT = "bulk_item_count";
    private static final String ARG_SERVICE_IDS = "bulk_service_ids";
    private static final String ARG_URLS = "bulk_urls";
    private static final String ARG_NAMES = "bulk_names";
    private static final String ARG_CACHE_KEY = "bulk_cache_key";
    private static final int INLINE_BUNDLE_LIMIT = 200;
    private static final int IPC_BATCH_CHUNK_SIZE = 500;

    static final String PREF_QUALITY_LABEL = "bulk_download_quality_label";
    static final String PREF_FETCH_THREADS = "bulk_download_fetch_threads";

    static final String[] QUALITY_LABELS = {
            "Best", "1080p", "720p", "480p", "360p", "240p"
    };

    private static final class BulkDownloadPayload implements Serializable {
        private static final long serialVersionUID = 1L;
        final int[] serviceIds;
        final ArrayList<String> urls;
        final ArrayList<String> names;

        BulkDownloadPayload(final int[] serviceIds,
                            final ArrayList<String> urls,
                            final ArrayList<String> names) {
            this.serviceIds = serviceIds;
            this.urls = urls;
            this.names = names;
        }
    }

    @Nullable
    private Listener listener;

    public static BulkDownloadDialog newInstance(@NonNull final List<StreamInfoItem> streams) {
        final BulkDownloadDialog dialog = new BulkDownloadDialog();
        final ArrayList<String> urls = new ArrayList<>(streams.size());
        final ArrayList<String> names = new ArrayList<>(streams.size());
        final int[] rawServiceIds = new int[streams.size()];
        int validCount = 0;

        for (final StreamInfoItem item : streams) {
            if (item == null || item.getUrl() == null || item.getName() == null) {
                continue;
            }
            rawServiceIds[validCount] = item.getServiceId();
            urls.add(item.getUrl());
            names.add(item.getName());
            validCount++;
        }

        final int[] serviceIds = validCount == rawServiceIds.length
                ? rawServiceIds : Arrays.copyOf(rawServiceIds, validCount);

        final Bundle args = new Bundle();
        args.putInt(ARG_ITEM_COUNT, validCount);
        if (validCount <= INLINE_BUNDLE_LIMIT) {
            args.putIntArray(ARG_SERVICE_IDS, serviceIds);
            args.putStringArrayList(ARG_URLS, urls);
            args.putStringArrayList(ARG_NAMES, names);
        } else {
            final String cacheKey = SerializedCache.getInstance().put(
                    new BulkDownloadPayload(serviceIds, urls, names),
                    BulkDownloadPayload.class);
            if (cacheKey != null) {
                args.putString(ARG_CACHE_KEY, cacheKey);
            }
        }
        dialog.setArguments(args);
        return dialog;
    }

    public static BulkDownloadDialog newInstance(final int itemCount) {
        final BulkDownloadDialog dialog = new BulkDownloadDialog();
        final Bundle args = new Bundle();
        args.putInt(ARG_ITEM_COUNT, itemCount);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);
        final Fragment parent = getParentFragment();
        if (parent instanceof Listener) {
            listener = (Listener) parent;
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
        final Bundle args = getArguments();
        final int count = args == null ? 0 : args.getInt(ARG_ITEM_COUNT, 0);

        final View view = requireActivity().getLayoutInflater()
                .inflate(R.layout.dialog_bulk_download, null);

        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());

        final RadioGroup formatGroup = view.findViewById(R.id.bulkFormatGroup);
        final String lastType = prefs.getString(
                getString(R.string.last_used_download_type),
                getString(R.string.last_download_type_video_key));
        final boolean defaultAudio =
                lastType.equals(getString(R.string.last_download_type_audio_key));

        if (savedInstanceState == null) {
            formatGroup.check(defaultAudio ? R.id.bulkRadioAudio : R.id.bulkRadioVideo);
        }

        final LinearLayout qualitySection = view.findViewById(R.id.bulkQualitySection);
        final Spinner qualitySpinner = view.findViewById(R.id.bulkQualitySpinner);

        final ArrayAdapter<String> qualityAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                QUALITY_LABELS);
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(qualityAdapter);

        if (savedInstanceState == null) {
            final String saved = prefs.getString(PREF_QUALITY_LABEL, QUALITY_LABELS[0]);
            for (int i = 0; i < QUALITY_LABELS.length; i++) {
                if (QUALITY_LABELS[i].equals(saved)) {
                    qualitySpinner.setSelection(i);
                    break;
                }
            }
        }

        final boolean isAudio =
                formatGroup.getCheckedRadioButtonId() == R.id.bulkRadioAudio;
        qualitySection.setVisibility(isAudio ? View.GONE : View.VISIBLE);

        formatGroup.setOnCheckedChangeListener((group, checkedId) ->
                qualitySection.setVisibility(
                        checkedId == R.id.bulkRadioAudio ? View.GONE : View.VISIBLE));

        final RadioGroup behaviorGroup = view.findViewById(R.id.bulkBehaviorGroup);
        if (savedInstanceState == null) {
            behaviorGroup.check(R.id.bulkRadioSkip);
        }

        final SeekBar fetchThreadsBar = view.findViewById(R.id.bulkFetchThreads);
        final TextView fetchThreadsCount = view.findViewById(R.id.bulkFetchThreadsCount);
        final int savedThreads = prefs.getInt(PREF_FETCH_THREADS, 3);
        fetchThreadsCount.setText(String.valueOf(savedThreads));
        fetchThreadsBar.setMax(9);
        fetchThreadsBar.setProgress(savedThreads - 1);
        fetchThreadsBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(@NonNull final SeekBar seekBar, final int progress,
                                          final boolean fromUser) {
                fetchThreadsCount.setText(String.valueOf(progress + 1));
            }

            @Override
            public void onStartTrackingTouch(@NonNull final SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(@NonNull final SeekBar seekBar) { }
        });

        final String title = getResources().getQuantityString(
                R.plurals.bulk_download_dialog_title, count, count);

        return new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.start_download, (dialog, which) -> {
                    final boolean audioOnly =
                            formatGroup.getCheckedRadioButtonId() == R.id.bulkRadioAudio;
                    final String quality = audioOnly
                            ? QUALITY_LABELS[0]
                            : (String) qualitySpinner.getSelectedItem();
                    final int fetchThreads = fetchThreadsBar.getProgress() + 1;

                    prefs.edit()
                            .putString(
                                    getString(R.string.last_used_download_type),
                                    audioOnly
                                    ? getString(R.string.last_download_type_audio_key)
                                    : getString(R.string.last_download_type_video_key))
                            .putString(PREF_QUALITY_LABEL, quality)
                            .putInt(PREF_FETCH_THREADS, fetchThreads)
                            .apply();

                    final ExistingFileBehavior behavior;
                    final int bid = behaviorGroup.getCheckedRadioButtonId();
                    if (bid == R.id.bulkRadioOverwrite) {
                        behavior = ExistingFileBehavior.OVERWRITE;
                    } else if (bid == R.id.bulkRadioUniqueName) {
                        behavior = ExistingFileBehavior.UNIQUE_NAME;
                    } else {
                        behavior = ExistingFileBehavior.SKIP;
                    }

                    if (listener != null) {
                        listener.onBulkDownloadConfirmed(audioOnly, quality, behavior, fetchThreads);
                    } else {
                        enqueueFromArguments(audioOnly, quality, behavior, fetchThreads);
                    }
                })
                .create();
    }

    private void enqueueFromArguments(final boolean audioOnly,
                                      @NonNull final String qualityLabel,
                                      @NonNull final ExistingFileBehavior behavior,
                                      final int fetchThreads) {
        final Bundle args = getArguments();
        final Context ctx = getContext();
        if (args == null || ctx == null) {
            return;
        }

        int[] serviceIds = args.getIntArray(ARG_SERVICE_IDS);
        ArrayList<String> urls = args.getStringArrayList(ARG_URLS);
        ArrayList<String> names = args.getStringArrayList(ARG_NAMES);

        if (serviceIds == null || urls == null || names == null) {
            final String cacheKey = args.getString(ARG_CACHE_KEY);
            if (cacheKey != null) {
                final BulkDownloadPayload payload = SerializedCache.getInstance()
                        .take(cacheKey, BulkDownloadPayload.class);
                if (payload != null) {
                    serviceIds = payload.serviceIds;
                    urls = payload.urls;
                    names = payload.names;
                }
            }
        }

        if (serviceIds == null || urls == null || names == null || urls.isEmpty()) {
            return;
        }

        final int fileBehavior;
        switch (behavior) {
            case OVERWRITE:
                fileBehavior = PendingFetchMission.BEHAVIOR_OVERWRITE;
                break;
            case UNIQUE_NAME:
                fileBehavior = PendingFetchMission.BEHAVIOR_UNIQUE_NAME;
                break;
            default:
                fileBehavior = PendingFetchMission.BEHAVIOR_SKIP;
                break;
        }

        PendingFetchMission.setMaxConcurrentFetches(fetchThreads);
        final Context appContext = ctx.getApplicationContext();
        final int[] finalServiceIds = serviceIds;
        final ArrayList<String> finalUrls = urls;
        final ArrayList<String> finalNames = names;

        Toast.makeText(appContext, R.string.download_has_started, Toast.LENGTH_SHORT).show();

        Schedulers.io().scheduleDirect(() -> {
            final int total = finalServiceIds.length;
            for (int offset = 0; offset < total; offset += IPC_BATCH_CHUNK_SIZE) {
                final int end = Math.min(offset + IPC_BATCH_CHUNK_SIZE, total);
                final int[] chunkServiceIds = Arrays.copyOfRange(finalServiceIds, offset, end);
                final ArrayList<String> chunkUrls = new ArrayList<>(finalUrls.subList(offset, end));
                final ArrayList<String> chunkNames = new ArrayList<>(finalNames.subList(offset, end));
                DownloadManagerService.addPendingFetchMissions(appContext,
                        chunkServiceIds, chunkUrls, chunkNames,
                        audioOnly, qualityLabel, fileBehavior);
            }
        });
    }
}
