package org.schabi.newpipe.util.selection;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import org.schabi.newpipe.download.BulkDownloadDialog;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.List;

/** Shared bulk download dialog launcher used by every list's selection toolbar. */
public final class SelectionDownloadHelper {

    private SelectionDownloadHelper() {
    }

    public static void showDownloadDialog(@NonNull final Fragment fragment,
                                          @NonNull final List<StreamInfoItem> streams) {
        if (streams.isEmpty() || !fragment.isAdded()) {
            return;
        }
        BulkDownloadDialog.newInstance(streams)
                .show(fragment.getParentFragmentManager(), BulkDownloadDialog.TAG);
    }
}
