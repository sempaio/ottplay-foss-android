package play.ott.nativeapp.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class ResumePositionTest {
    @Test fun `short clip resumes in its last five seconds until nearly complete`() {
        assertEquals(4_000, resumePosition(4_000, 8_000))
        assertEquals(7_000, resumePosition(7_000, 8_000))
        assertEquals(0, resumePosition(7_800, 8_000))
    }
    @Test fun `completed film starts over and valid long form offset is retained`() {
        assertEquals(900_000, resumePosition(900_000, 3_600_000))
        assertEquals(0, resumePosition(3_598_000, 3_600_000))
        assertEquals(0, resumePosition(-1, 8_000))
        assertEquals(0, resumePosition(8_500, 8_000))
    }
}
