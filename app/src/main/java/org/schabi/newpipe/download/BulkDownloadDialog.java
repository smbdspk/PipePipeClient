package org.schabi.newpipe.download;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

/**
 * DialogFragment shown when the user confirms a bulk download from multi-select mode.
 * Collects three preferences:
 * <ul>
 *   <li>Format: Video or Audio-only</li>
 *   <li>Quality label: "Best", "1080p", …, "240p" (video only)</li>
 *   <li>Existing-file behaviour: Skip / Overwrite / Generate unique name</li>
 * </ul>
 *
 * <p>The parent fragment <em>must</em> implement {@link Listener}. Use
 * {@link #newInstance(int)} to create an instance and show it via
 * {@code getChildFragmentManager()}.
 */
public class BulkDownloadDialog extends DialogFragment {

    /** What to do when the destination filename already exists on disk. */
    public enum ExistingFileBehavior {
        SKIP,
        OVERWRITE,
        UNIQUE_NAME
    }

    /** Implemented by the fragment that opens this dialog to receive the result. */
    public interface Listener {
        /**
         * Called when the user presses "Download" in the dialog.
         *
         * @param audioOnly    {@code true} if the user chose Audio-only
         * @param qualityLabel the selected quality string, e.g. "720p" or "Best"
         * @param behavior     how to handle files that already exist
         */
        void onBulkDownloadConfirmed(boolean audioOnly,
                                     @NonNull String qualityLabel,
                                     @NonNull ExistingFileBehavior behavior);
    }

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String ARG_ITEM_COUNT = "bulk_item_count";
    /** SharedPreferences key used to persist the last chosen quality label. */
    static final String PREF_QUALITY_LABEL = "bulk_download_quality_label";

    /** Fixed quality options shown in the spinner (video only). */
    static final String[] QUALITY_LABELS = {
            "Best", "1080p", "720p", "480p", "360p", "240p"
    };

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private Listener listener;

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    /**
     * Creates a new instance.
     *
     * @param itemCount the number of selected stream items (used for the dialog title)
     */
    public static BulkDownloadDialog newInstance(final int itemCount) {
        final BulkDownloadDialog dialog = new BulkDownloadDialog();
        final Bundle args = new Bundle();
        args.putInt(ARG_ITEM_COUNT, itemCount);
        dialog.setArguments(args);
        return dialog;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

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

        // ── Format radio ────────────────────────────────────────────────
        final RadioGroup formatGroup = view.findViewById(R.id.bulkFormatGroup);
        final String lastType = prefs.getString(
                getString(R.string.last_used_download_type),
                getString(R.string.last_download_type_video_key));
        final boolean defaultAudio =
                lastType.equals(getString(R.string.last_download_type_audio_key));

        // Only set defaults on first creation; Android restores radio state on rotation.
        if (savedInstanceState == null) {
            formatGroup.check(defaultAudio ? R.id.bulkRadioAudio : R.id.bulkRadioVideo);
        }

        // ── Quality spinner ─────────────────────────────────────────────
        final LinearLayout qualitySection = view.findViewById(R.id.bulkQualitySection);
        final Spinner qualitySpinner = view.findViewById(R.id.bulkQualitySpinner);

        final ArrayAdapter<String> qualityAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                QUALITY_LABELS);
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(qualityAdapter);

        if (savedInstanceState == null) {
            // Restore last chosen quality, defaulting to "Best"
            final String saved = prefs.getString(PREF_QUALITY_LABEL, QUALITY_LABELS[0]);
            for (int i = 0; i < QUALITY_LABELS.length; i++) {
                if (QUALITY_LABELS[i].equals(saved)) {
                    qualitySpinner.setSelection(i);
                    break;
                }
            }
        }

        // ── Quality section visibility ───────────────────────────────────
        // Derived from the current format selection (correct after restore too).
        final boolean isAudio =
                formatGroup.getCheckedRadioButtonId() == R.id.bulkRadioAudio;
        qualitySection.setVisibility(isAudio ? View.GONE : View.VISIBLE);

        formatGroup.setOnCheckedChangeListener((group, checkedId) ->
                qualitySection.setVisibility(
                        checkedId == R.id.bulkRadioAudio ? View.GONE : View.VISIBLE));

        // ── Existing-file behaviour radio ───────────────────────────────
        final RadioGroup behaviorGroup = view.findViewById(R.id.bulkBehaviorGroup);
        if (savedInstanceState == null) {
            behaviorGroup.check(R.id.bulkRadioSkip);
        }

        // ── Build dialog ─────────────────────────────────────────────────
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
                            ? QUALITY_LABELS[0]                            // "Best"
                            : (String) qualitySpinner.getSelectedItem();

                    // Persist choices for next time
                    prefs.edit()
                            .putString(
                                    getString(R.string.last_used_download_type),
                                    audioOnly
                                    ? getString(R.string.last_download_type_audio_key)
                                    : getString(R.string.last_download_type_video_key))
                            .putString(PREF_QUALITY_LABEL, quality)
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

                    listener.onBulkDownloadConfirmed(audioOnly, quality, behavior);
                })
                .create();
    }
}
