package us.shandian.giga.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;

public final class BilibiliTempHelper {

    private static final String TAG = "BilibiliTempHelper";

    private BilibiliTempHelper() {
    }

    public static String tmpVideoExt(@NonNull String baseExt) {
        return ".tmp" + baseExt;
    }

    public static String tmpAudioExt() {
        return ".tmp";
    }

    public static String tmpVideoName(@NonNull String filename) {
        String ext = Utility.getFileExt(filename);
        if (ext == null) ext = ".mp4";
        return filename.replace(ext, tmpVideoExt(ext));
    }

    public static String tmpAudioName(@NonNull String filename) {
        String ext = Utility.getFileExt(filename);
        if (ext == null) ext = ".mp4";
        return filename.replace(ext, tmpAudioExt());
    }

    public static void createSidecarFiles(@NonNull StoredDirectoryHelper dir,
                                          @NonNull String filename) {
        dir.createFile(tmpVideoName(filename), "video/mp4");
        dir.createFile(tmpAudioName(filename), String.valueOf(org.schabi.newpipe.extractor.MediaFormat.M4A));
    }

    public static void cleanupSidecarFiles(@Nullable StoredDirectoryHelper dir,
                                           @NonNull String filename) {
        if (dir == null) return;
        try {
            String ext = Utility.getFileExt(filename);
            if (ext == null) ext = ".mp4";
            dir.remove(filename.replace(ext, tmpVideoExt(ext)));
            dir.remove(filename.replace(ext, tmpAudioExt()));
        } catch (Exception e) {
            Log.w(TAG, "Failed to cleanup sidecar files for " + filename, e);
        }
    }

    public static void cleanupSidecarFilesFileIO(@Nullable StoredFileHelper storedFileHelper) {
        Utility.removeTempFileOfDownloadedVideo(storedFileHelper);
    }
}