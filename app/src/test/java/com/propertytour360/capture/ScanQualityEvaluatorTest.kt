package com.propertytour360.capture

import com.propertytour360.capture.util.ScanQualityEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ScanQualityEvaluatorTest {
    @Test fun goodEvidencePasses() {
        val dir = Files.createTempDirectory("scan-quality").toFile()
        dir.resolve("intrinsics.json").writeText("{}")
        dir.resolve("capture_summary.json").writeText("""{"poseCount":120,"planeSnapshotCount":8,"depthFrames":12,"durationSeconds":45}""")
        val report = ScanQualityEvaluator.evaluate(dir)
        assertEquals("GOOD_DRAFT", report.status)
        assertTrue(report.score >= 80)
    }

    @Test fun weakEvidenceRequestsRescan() {
        val dir = Files.createTempDirectory("scan-quality-weak").toFile()
        dir.resolve("capture_summary.json").writeText("""{"poseCount":5,"planeSnapshotCount":0,"depthFrames":0,"durationSeconds":3}""")
        assertEquals("RESCAN_RECOMMENDED", ScanQualityEvaluator.evaluate(dir).status)
    }
}
