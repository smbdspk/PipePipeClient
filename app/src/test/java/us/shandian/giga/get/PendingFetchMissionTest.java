package us.shandian.giga.get;

import org.junit.Test;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PendingFetchMissionTest {

    @Test
    public void testMaxConcurrentFetches() {
        PendingFetchMission.setMaxConcurrentFetches(5);
        assertEquals(5, PendingFetchMission.getMaxConcurrentFetches());

        // lower bound check (at least 1)
        PendingFetchMission.setMaxConcurrentFetches(0);
        assertEquals(1, PendingFetchMission.getMaxConcurrentFetches());

        PendingFetchMission.setMaxConcurrentFetches(3);
        assertEquals(3, PendingFetchMission.getMaxConcurrentFetches());
    }

    @Test
    public void testDoRecoverWhenDoneIsZeroCallsRefetch() {
        StoredFileHelper placeholder = new StoredFileHelper(null, "test.mp4",
                StoredFileHelper.DEFAULT_MIME, "v");
        PendingFetchMission mission = new PendingFetchMission(
                0, "https://youtube.com/watch?v=123", "test", false, "Best",
                PendingFetchMission.BEHAVIOR_SKIP, placeholder);

        // Simulate promoted mission where no bytes have been downloaded yet (done == 0)
        mission.pendingFetch = false;
        mission.done = 0;
        mission.urls = new String[]{"https://expired.googlevideo.com/videoplayback"};

        // When 403 occurs on a fresh download, doRecover should auto-heal via refetch()
        mission.doRecover(DownloadMission.ERROR_HTTP_FORBIDDEN);

        // Mission should reset back to pendingFetch state with empty URL and reset blocks
        assertTrue("Mission should reset back to pendingFetch = true", mission.pendingFetch);
        assertEquals("URL should be cleared for fresh fetch", "", mission.urls[0]);
    }

    @Test
    public void testDoRecoverWhenDoneGreaterThanZeroDelegatesToSuperRecover() {
        StoredFileHelper placeholder = new StoredFileHelper(null, "test.mp4",
                StoredFileHelper.DEFAULT_MIME, "v");
        PendingFetchMission mission = new PendingFetchMission(
                0, "https://youtube.com/watch?v=123", "test", false, "Best",
                PendingFetchMission.BEHAVIOR_SKIP, placeholder);

        mission.pendingFetch = false;
        mission.done = 1048576L; // 1 MB downloaded
        mission.urls = new String[]{"https://expired.googlevideo.com/videoplayback"};
        mission.recoveryInfo = new MissionRecoveryInfo[]{
                new MissionRecoveryInfo(org.schabi.newpipe.extractor.MediaFormat.M4A, null, false, 0, 'a', null, null, false, false, -1, null)
        };

        // When 403 occurs on a partially downloaded mission with recoveryInfo,
        // it must delegate to super.doRecover (in-place recovery) and NOT wipe via refetch()
        mission.doRecover(DownloadMission.ERROR_HTTP_FORBIDDEN);

        // pendingFetch must NOT be reset to true
        assertFalse("Mission should NOT be reset to pendingFetch when done > 0", mission.pendingFetch);
        assertEquals("URL should NOT be wiped", "https://expired.googlevideo.com/videoplayback", mission.urls[0]);
        assertEquals("Downloaded bytes must be preserved", 1048576L, mission.done);
    }

    @Test
    public void testRefetchWithTriggerRunMissionsFalse() {
        StoredFileHelper placeholder = new StoredFileHelper(null, "test.mp4",
                StoredFileHelper.DEFAULT_MIME, "v");
        PendingFetchMission mission = new PendingFetchMission(
                0, "https://youtube.com/watch?v=123", "test", false, "Best",
                PendingFetchMission.BEHAVIOR_SKIP, placeholder);

        mission.pendingFetch = false;
        mission.done = 0;
        mission.errCode = DownloadMission.ERROR_HTTP_FORBIDDEN;
        mission.enqueued = false;

        mission.refetch(false);

        assertTrue("Mission should be reset to pendingFetch = true", mission.pendingFetch);
        assertTrue("Mission should be re-enqueued", mission.enqueued);
        assertEquals("Error code should be cleared", DownloadMission.ERROR_NOTHING, mission.errCode);
        assertEquals("URL should be cleared", "", mission.urls[0]);
    }

    @Test
    public void testDeadMissionWithEmptyUrlsIsNotFinished() {
        StoredFileHelper placeholder = new StoredFileHelper(null, "test.mp4",
                StoredFileHelper.DEFAULT_MIME, "v");
        DownloadMission mission = new DownloadMission(
                new String[]{"https://example.com/stream.mp4"}, placeholder, 'v', null, null);

        // Simulate DownloadMissionRecover / doRecover marking mission as dead (urls = new String[0])
        mission.urls = new String[0];
        mission.current = 0;
        mission.errCode = DownloadMission.ERROR_RESOURCE_GONE;

        assertFalse("Dead mission with empty urls must NOT be considered finished",
                mission.isFinished());
        assertFalse("Dead mission with empty urls must NOT be considered corrupt",
                mission.isCorrupt());

        // Verify normal completion still reports finished
        mission.urls = new String[]{"https://example.com/stream.mp4"};
        mission.current = 1;
        mission.errCode = DownloadMission.ERROR_NOTHING;
        assertTrue("Completed mission should be considered finished", mission.isFinished());
    }

    @Test
    public void testResetStateWithRollbackResetsDoneAndCheckpoints() {
        StoredFileHelper placeholder = new StoredFileHelper(null, "test.mp4",
                StoredFileHelper.DEFAULT_MIME, "v");
        DownloadMission mission = new DownloadMission(
                new String[]{"https://example.com/video.mp4", "https://example.com/audio.m4a"},
                placeholder, 'v', null, null);

        mission.done = 524288L;
        mission.current = 1;
        mission.blocks = new int[]{-1, 0};
        mission.sabrStarted = true;

        // Non-rollback reset preserves done and current
        mission.resetState(false, false, DownloadMission.ERROR_NOTHING);
        assertEquals("Non-rollback reset must preserve done", 524288L, mission.done);
        assertEquals("Non-rollback reset must preserve current", 1, mission.current);
        assertNull("Blocks should be cleared", mission.blocks);

        // Rollback reset clears done, current, and SABR/HLS state
        mission.blocks = new int[]{-1, 0};
        mission.resetState(true, false, DownloadMission.ERROR_PROGRESS_LOST);
        assertEquals("Rollback reset must clear done to 0", 0L, mission.done);
        assertEquals("Rollback reset must reset current to 0", 0, mission.current);
        assertFalse("Rollback reset must clear sabrStarted", mission.sabrStarted);
        assertEquals(DownloadMission.ERROR_PROGRESS_LOST, mission.errCode);
    }

    @Test
    public void testPauseFromInitThreadDoesNotInterruptCurrentThread() {
        StoredFileHelper placeholder = new StoredFileHelper(null, "test.mp4",
                StoredFileHelper.DEFAULT_MIME, "v");
        DownloadMission mission = new DownloadMission(
                new String[]{"https://example.com/stream.mp4"}, placeholder, 'v', null, null);

        mission.running = true;
        mission.init = Thread.currentThread();

        mission.pause();

        assertFalse("pause() called from init thread must NOT self-interrupt current thread",
                Thread.currentThread().isInterrupted());
        assertFalse("Mission should no longer be running", mission.running);
        assertNull("init reference should be cleared", mission.init);
    }

    @Test
    public void testRefetchClearsDeadStateAndResourceMetadata() {
        StoredFileHelper placeholder = new StoredFileHelper(null, "test.mp4",
                StoredFileHelper.DEFAULT_MIME, "v");
        PendingFetchMission mission = new PendingFetchMission(
                0, "https://youtube.com/watch?v=123", "test", false, "Best",
                PendingFetchMission.BEHAVIOR_SKIP, placeholder);

        mission.pendingFetch = false;
        mission.done = 2048L;
        mission.urls = new String[0];
        mission.resourceDeliveryMethods = new String[]{"SABR"};
        mission.resourceManifestUrls = new String[]{null};
        mission.resourceIsUrls = new boolean[]{true};
        mission.sabrStarted = true;
        mission.errCode = DownloadMission.ERROR_RESOURCE_GONE;
        mission.enqueued = false;

        mission.refetch(false);

        assertTrue("Mission should reset to pendingFetch = true", mission.pendingFetch);
        assertTrue("Mission should be re-enqueued", mission.enqueued);
        assertEquals("Done bytes should reset to 0 on refetch", 0L, mission.done);
        assertNull("resourceDeliveryMethods should be cleared", mission.resourceDeliveryMethods);
        assertNull("resourceManifestUrls should be cleared", mission.resourceManifestUrls);
        assertNull("resourceIsUrls should be cleared", mission.resourceIsUrls);
        assertFalse("sabrStarted should be cleared", mission.sabrStarted);
        assertEquals("Error code should be cleared", DownloadMission.ERROR_NOTHING, mission.errCode);
        assertEquals("URL should reset to single empty placeholder", 1, mission.urls.length);
    }
}
