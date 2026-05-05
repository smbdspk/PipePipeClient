package org.schabi.newpipe.util;

import org.junit.Test;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.localization.DateWrapper;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FilenameUtilsTest {

    @Test
    public void testBuildFilename() {
        StreamInfo info = mock(StreamInfo.class);
        when(info.getName()).thenReturn("Test Video /?<>|");
        when(info.getUploaderName()).thenReturn("Test Channel:");
        when(info.getId()).thenReturn("video_id");
        
        Calendar cal = Calendar.getInstance();
        cal.set(2023, Calendar.OCTOBER, 5);
        DateWrapper dateWrapper = mock(DateWrapper.class);
        when(dateWrapper.date()).thenReturn(cal);
        when(info.getUploadDate()).thenReturn(dateWrapper);

        // test basic title
        assertEquals("Test Video ______", FilenameUtils.buildFilename("{title}", info));

        // test all tokens
        assertEquals("Test Channel_ - Test Video ______ - 2023-10-05 (202310) [video_id]",
                FilenameUtils.buildFilename("{channel} - {title} - {date} ({date_short}) [{id}]", info));

        // test null upload date
        when(info.getUploadDate()).thenReturn(null);
        assertEquals("Test Channel_ - Test Video ______ -  () [video_id]",
                FilenameUtils.buildFilename("{channel} - {title} - {date} ({date_short}) [{id}]", info));

        // test null uploader
        when(info.getUploaderName()).thenReturn(null);
        assertEquals(" - Test Video ______", FilenameUtils.buildFilename("{channel} - {title}", info));

        // test fallback on empty result
        assertEquals("Test Video ______", FilenameUtils.buildFilename("   ", info));
    }
}
