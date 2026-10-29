package us.shandian.giga.postprocessing;

import android.content.Context;
import org.schabi.newpipe.streams.WebMReader.TrackKind;
import org.schabi.newpipe.streams.WebMReader.WebMTrack;
import org.schabi.newpipe.streams.WebMWriter;
import org.schabi.newpipe.streams.io.SharpStream;

import java.io.IOException;

/**
 * @author kapodamy
 */
class WebMMuxer extends Postprocessing {

    WebMMuxer() {
        super(true, true, ALGORITHM_WEBM_MUXER);
    }

    @Override
    int process(String source, Context context, SharpStream out, SharpStream... sources) throws IOException {
        return process(out, sources);
    }

    int process(SharpStream out, SharpStream... sources) throws IOException {
        WebMWriter muxer = new WebMWriter(sources);
        muxer.parseSources();

        int[] indexes = new int[sources.length];
        boolean foundAudio = false;

        for (int i = 0; i < sources.length; i++) {
            WebMTrack[] tracks = muxer.getTracksFromSource(i);
            for (int j = 0; j < tracks.length; j++) {
                if (tracks[j].kind == TrackKind.Audio) {
                    indexes[i] = j;
                    foundAudio = true;
                    break;
                }
            }
        }

        if (!foundAudio) {
            throw new IOException("No audio track found in any source");
        }

        muxer.selectTracks(indexes);
        muxer.build(out);

        return OK_RESULT;
    }

}
