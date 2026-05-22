package us.shandian.giga.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.io.IOException;

public final class BilibiliTempHelper {

    private static final String TAG = "BilibiliTempHelper";

    private BilibiliTempHelper() {
    }

    public static String tmpVideoExt(@NonNull String baseExt) {
        return ".tmp" + baseExt;
    }

    public static String tmpAudioExt() {
        return ".tmp.m4a";
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
        dir.createFile(tmpVideoName(filename), StoredFileHelper.DEFAULT_MIME);
        dir.createFile(tmpAudioName(filename), StoredFileHelper.DEFAULT_MIME);
    }

    public static void createSidecarFilesDirectIO(@NonNull File parentDir,
                                                   @NonNull String filename) {
        try {
            new File(parentDir, tmpVideoName(filename)).createNewFile();
            new File(parentDir, tmpAudioName(filename)).createNewFile();
        } catch (final IOException e) {
            Log.w(TAG, "Failed to create sidecar files for " + filename, e);
        }
    }

public static void createSidecarFilesSAF(@NonNull Context context,
                                               @NonNull DocumentFile tree,
                                               @NonNull String filename) {
        try {
            String videoName = tmpVideoName(filename);
            String audioName = tmpAudioName(filename);
            DocumentFile audioDoc = StoredDirectoryHelper.findFileSAFHelper(context, tree, audioName);
            if (audioDoc == null) {
                tree.createFile(StoredFileHelper.DEFAULT_MIME, audioName);
            }
            DocumentFile videoDoc = StoredDirectoryHelper.findFileSAFHelper(context, tree, videoName);
            if (videoDoc == null) {
                tree.createFile(StoredFileHelper.DEFAULT_MIME, videoName);
            }
        } catch (final Exception e) {
            Log.w(TAG, "Failed to create SAF sidecar files for " + filename, e);
        }
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