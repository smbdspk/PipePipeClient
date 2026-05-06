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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

public class BulkDownloadDialog extends DialogFragment {

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
    static final String PREF_QUALITY_LABEL = "bulk_download_quality_label";
    static final String PREF_FETCH_THREADS = "bulk_download_fetch_threads";

    static final String[] QUALITY_LABELS = {
            "Best", "1080p", "720p", "480p", "360p", "240p"
    };

    private Listener listener;

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
        if (!(parent instanceof Listener)) {
            throw new IllegalStateException(
                    "Parent fragment "
                    + (parent == null ? "null" : parent.getClass().getSimpleName())
                    + " must implement BulkDownloadDialog.Listener");
        }
        listener = (Listener) parent;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
        final int count = getArguments() == null ? 0
                : getArguments().getInt(ARG_ITEM_COUNT, 0);

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

                    listener.onBulkDownloadConfirmed(audioOnly, quality, behavior, fetchThreads);
                })
                .create();
    }
}