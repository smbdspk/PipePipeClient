package org.schabi.newpipe.util;

import org.junit.Test;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;

public class FilenameUtilsTest {

    private static class TestStreamInfo extends StreamInfo {
        private String name;
        private String uploaderName;
        private String id;
        private DateWrapper uploadDate;

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getUploaderName() {
            return uploaderName;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public DateWrapper getUploadDate() {
            return uploadDate;
        }
    }

    @Test
    public void testBuildFilename() {
        TestStreamInfo info = new TestStreamInfo();
        info.name = "Test Video /?<>|";
        info.uploaderName = "Test Channel:";
        info.id = "video_id";

        Calendar cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.set(2023, Calendar.OCTOBER, 5, 12, 0, 0);
        info.uploadDate = new DateWrapper(cal);

        // test basic title
        assertEquals("Test Video _____", FilenameUtils.buildFilename("{title}", info));

        // test all tokens
        assertEquals("Test Channel_ - Test Video _____ - 2023-10-05 (202310) [video_id]",
                FilenameUtils.buildFilename("{channel} - {title} - {date} ({date_short}) [{id}]", info));

        // test null upload date
        info.uploadDate = null;
        assertEquals("Test Channel_ - Test Video _____ -  () [video_id]",
                FilenameUtils.buildFilename("{channel} - {title} - {date} ({date_short}) [{id}]", info));

        // test null uploader
        info.uploaderName = null;
        info.uploadDate = null;
        assertEquals("- Test Video _____", FilenameUtils.buildFilename("{channel} - {title}", info));

        // test fallback on empty result
        assertEquals("Test Video _____", FilenameUtils.buildFilename("   ", info));
    }
}
