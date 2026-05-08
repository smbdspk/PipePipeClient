package us.shandian.giga.postprocessing;

import android.content.Context;
import android.net.Uri;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;

import org.schabi.newpipe.streams.io.SharpStream;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import us.shandian.giga.io.CircularFileWriter;
import us.shandian.giga.util.BilibiliTempHelper;

import java.io.IOException;

public class BiliBiliMp4Muxer extends Postprocessing{


    public BiliBiliMp4Muxer() {
        super(true, true, BILIBILI_MUXER);
    }

    @Override
    int process(String source, Context context, SharpStream out, SharpStream... sources) throws IOException {
        return OK_RESULT;
    }
    public int mux(StoredFileHelper storage, Context context, SharpStream out, SharpStream... sources) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        int read;
        String source = storage.source;

        SharpStream audioOut = null;
        try {
            audioOut = new StoredFileHelper(context, Uri.parse(storage.sourceTree),
                    Uri.parse(BilibiliTempHelper.tmpAudioName(source)), "audio").getStream();
            while ((read = sources[1].read(buffer)) > 0) {
                audioOut.write(buffer, 0, read);
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

        String video = FFmpegKitConfig.getSafParameter(context, Uri.parse(source), "rw");
        String audio = FFmpegKitConfig.getSafParameterForRead(context, Uri.parse(BilibiliTempHelper.tmpAudioName(source)));
        String temp = FFmpegKitConfig.getSafParameter(context, Uri.parse(BilibiliTempHelper.tmpVideoName(source)), "rw");

        FFmpegSession session = FFmpegKit.execute(
                String.format("-i %s -i %s -strict -2 -c copy -y %s", video, audio, temp));
        if (session.getReturnCode().isValueError()) {
            throw new IOException("Bilibili mux step 1 failed: " + session.getFailStackTrace());
        }

        temp = FFmpegKitConfig.getSafParameter(context, Uri.parse(BilibiliTempHelper.tmpVideoName(source)), "rw");
        video = FFmpegKitConfig.getSafParameter(context, Uri.parse(source), "w");

        session = FFmpegKit.execute(
                String.format("-i %s -strict -2 -c copy -y %s", temp, video));
        if (session.getReturnCode().isValueError()) {
            throw new IOException("Bilibili mux step 2 failed: " + session.getFailStackTrace());
        }

        return OK_RESULT;
    }
}
