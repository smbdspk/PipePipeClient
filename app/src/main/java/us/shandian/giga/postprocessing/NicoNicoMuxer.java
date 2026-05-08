package us.shandian.giga.postprocessing;

import android.content.Context;
import android.net.Uri;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import org.schabi.newpipe.streams.io.SharpStream;
import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.io.CircularFileWriter;

import java.io.IOException;
import java.net.URLDecoder;

public class NicoNicoMuxer extends Postprocessing{
    NicoNicoMuxer() {
        super(true, true, NICONICO_MUXER);
    }

    @Override
    int process(String source, Context context, SharpStream out, SharpStream... sources) throws IOException {
        ((CircularFileWriter)out).finalizeFile();
        return OK_RESULT;
    }
    int download(String source, Context context, String[] urls, DownloadMission mission) throws IOException {
        String stream = FFmpegKitConfig.getSafParameter(context, Uri.parse(source), "w");

        String[] parts = urls[0].split("#cookie=", 2);
        String url = parts[0];
        String cookie;
        String length;

        if (parts.length > 1) {
            String additionalParam = URLDecoder.decode(parts[1]);
            int lengthIdx = additionalParam.indexOf("&length=");
            if (lengthIdx >= 0) {
                cookie = additionalParam.substring(0, lengthIdx);
                length = additionalParam.substring(lengthIdx + "&length=".length());
            } else {
                cookie = additionalParam;
                length = null;
            }
        } else {
            cookie = null;
            length = null;
        }

        String audioUrl = urls.length > 1 ? urls[1] : "";
        String filter = "";
        if (url.contains("audio")) {
            filter = "-bsf:a aac_adtstoasc";
        }

        StringBuilder cmd = new StringBuilder();
        if (cookie != null) {
            cmd.append(String.format("-headers \"Cookie: %s\" ", cookie));
        }
        cmd.append(String.format("-protocol_whitelist \"file,http,https,tcp,tls,httpproxy,crypto\" -i \"%s\"", url));
        if (!audioUrl.isEmpty()) {
            cmd.append(String.format(" -headers \"Cookie: %s\" -i \"%s\"", cookie, audioUrl));
        }
        if (length != null) {
            cmd.append(String.format(" -t %s", length));
        }
        cmd.append(String.format(" -c copy %s %s", filter, stream));

        FFmpegSession session = FFmpegKit.execute(cmd.toString());
        if (session.getReturnCode().isValueError()) {
            throw new IOException("NicoNico mux failed: " + session.getFailStackTrace());
        }

        return OK_RESULT;
    }
}