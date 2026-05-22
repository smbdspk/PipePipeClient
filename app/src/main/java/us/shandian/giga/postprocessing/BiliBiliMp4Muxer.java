package us.shandian.giga.postprocessing;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;

import org.schabi.newpipe.streams.io.SharpStream;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import us.shandian.giga.io.CircularFileWriter;
import us.shandian.giga.util.BilibiliTempHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class BiliBiliMp4Muxer extends Postprocessing{


    public BiliBiliMp4Muxer() {
        super(true, true, BILIBILI_MUXER);
    }

    @Override
    int process(String source, Context context, SharpStream out, SharpStream... sources) throws IOException {
        return OK_RESULT;
    }

    private DocumentFile findOrCreateDoc(Context context, DocumentFile tree,
                                          String filename) throws IOException {
        DocumentFile doc = StoredDirectoryHelper.findFileSAFHelper(context, tree, filename);
        if (doc == null) {
            doc = tree.createFile(StoredFileHelper.DEFAULT_MIME, filename);
        }
        if (doc == null) {
            throw new IOException("Cannot create temp file: " + filename);
        }
        return doc;
    }

    public int mux(StoredFileHelper storage, Context context, SharpStream out, SharpStream... sources) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        int read;
        String source = storage.source;
        String filename = storage.getName();

        boolean isDirect = storage.isDirect();
        boolean hasTree = storage.sourceTree != null;

        String tmpAudioFilename = BilibiliTempHelper.tmpAudioName(filename);
        String tmpVideoFilename = BilibiliTempHelper.tmpVideoName(filename);

        SharpStream audioOut = null;
        File cacheAudioFile = null;
        File cacheVideoTempFile = null;

        try {
            if (isDirect) {
                final File parentDir = storage.ioFile.getParentFile();
                audioOut = new StoredFileHelper(context, Uri.parse(storage.sourceTree),
                        Uri.fromFile(new File(parentDir, tmpAudioFilename)),
                        "audio").getStream();
            } else if (hasTree) {
                Uri treeUri = Uri.parse(storage.sourceTree);
                DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
                if (tree == null) {
                    throw new IOException("Cannot access download directory tree for Bilibili muxing");
                }
                DocumentFile audioDoc = findOrCreateDoc(context, tree, tmpAudioFilename);
                StoredFileHelper audioHelper = new StoredFileHelper(context, treeUri,
                        audioDoc.getUri(), "audio");
                audioOut = audioHelper.getStream();
            } else {
                File cacheDir = context.getCacheDir();
                cacheAudioFile = new File(cacheDir, tmpAudioFilename);
                FileOutputStream fos = new FileOutputStream(cacheAudioFile);
                try {
                    while ((read = sources[1].read(buffer)) > 0) {
                        fos.write(buffer, 0, read);
                    }
                } finally {
                    fos.close();
                }
            }

            if (audioOut != null) {
                while ((read = sources[1].read(buffer)) > 0) {
                    audioOut.write(buffer, 0, read);
                }
            }
        } finally {
            if (audioOut != null) {
                audioOut.close();
            }
        }

        buffer = new byte[8 * 1024];
        while ((read = sources[0].read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        ((CircularFileWriter)out).finalizeFile();

        String video;
        String audio;
        String temp;

        if (isDirect) {
            File parentDir = storage.ioFile.getParentFile();
            video = FFmpegKitConfig.getSafParameter(context, Uri.parse(source), "rw");
            audio = FFmpegKitConfig.getSafParameterForRead(context,
                    Uri.fromFile(new File(parentDir, tmpAudioFilename)));
            temp = FFmpegKitConfig.getSafParameter(context,
                    Uri.fromFile(new File(parentDir, tmpVideoFilename)), "rw");
        } else if (hasTree) {
            Uri treeUri = Uri.parse(storage.sourceTree);
            DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
            if (tree == null) {
                throw new IOException("Cannot access download directory tree for Bilibili muxing");
            }
            DocumentFile audioDoc = findOrCreateDoc(context, tree, tmpAudioFilename);
            Uri audioUri = audioDoc.getUri();

            DocumentFile videoTempDoc = findOrCreateDoc(context, tree, tmpVideoFilename);
            Uri videoTempUri = videoTempDoc.getUri();

            video = FFmpegKitConfig.getSafParameter(context, Uri.parse(source), "rw");
            audio = FFmpegKitConfig.getSafParameterForRead(context, audioUri);
            temp = FFmpegKitConfig.getSafParameter(context, videoTempUri, "rw");
        } else {
            File cacheDir = context.getCacheDir();
            cacheVideoTempFile = new File(cacheDir, tmpVideoFilename);
            cacheVideoTempFile.createNewFile();

            video = FFmpegKitConfig.getSafParameter(context, Uri.parse(source), "rw");
            audio = cacheAudioFile.getAbsolutePath();
            temp = cacheVideoTempFile.getAbsolutePath();
        }

        FFmpegSession session = FFmpegKit.execute(
                String.format("-i %s -i %s -strict -2 -c copy -y %s", video, audio, temp));
        if (session.getReturnCode().isValueError() && session.getFailStackTrace() != null) {
            throw new IOException("Bilibili mux step 1 failed: " + session.getFailStackTrace());
        }

        if (isDirect) {
            File parentDir = storage.ioFile.getParentFile();
            temp = FFmpegKitConfig.getSafParameter(context,
                    Uri.fromFile(new File(parentDir, tmpVideoFilename)), "rw");
            video = FFmpegKitConfig.getSafParameter(context, Uri.parse(source), "w");
        } else if (hasTree) {
            Uri treeUri = Uri.parse(storage.sourceTree);
            DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
            DocumentFile videoTempDoc = findOrCreateDoc(context, tree, tmpVideoFilename);
            Uri videoTempUri = videoTempDoc.getUri();

            temp = FFmpegKitConfig.getSafParameter(context, videoTempUri, "rw");
            video = FFmpegKitConfig.getSafParameter(context, Uri.parse(source), "w");
        } else {
            temp = cacheVideoTempFile.getAbsolutePath();
            video = FFmpegKitConfig.getSafParameter(context, Uri.parse(source), "w");
        }

        session = FFmpegKit.execute(
                String.format("-i %s -strict -2 -c copy -y %s", temp, video));
        if (session.getReturnCode().isValueError() && session.getFailStackTrace() != null) {
            throw new IOException("Bilibili mux step 2 failed: " + session.getFailStackTrace());
        }

        if (cacheAudioFile != null) {
            cacheAudioFile.delete();
        }
        if (cacheVideoTempFile != null) {
            cacheVideoTempFile.delete();
        }

        return OK_RESULT;
    }
}