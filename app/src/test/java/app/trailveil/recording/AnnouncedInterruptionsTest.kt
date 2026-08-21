package app.trailveil.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `P4-048`. What this runtime has told the user about, and why it may not forget.
 *
 * The sibling guard [AbandonedResumeClaims] is a single slot on purpose. This one may not be,
 * because the two forget in opposite directions: forgetting a resume claim costs one extra offer,
 * while forgetting an announcement resumes an exploration the user was told had ended. That
 * asymmetry is the reason the two classes differ at all, so it is asserted here rather than left as
 * a comment.
 */
class AnnouncedInterruptionsTest {

    @Test
    fun anExplorationThisRuntimeAnnouncedIsRemembered() {
        val announced = AnnouncedInterruptions()
        assertFalse(announced.wasAnnounced(7L))
        announced.announce(7L)
        assertTrue(announced.wasAnnounced(7L))
    }

    @Test
    fun aLaterAnnouncementDoesNotEraseAnEarlierOne() {
        // The failure this rules out: a single-slot record, which is what the resume-claim guard
        // uses. Announce two explorations in one runtime and the first must still be remembered --
        // otherwise reopening resumes the exploration the user was told about first.
        val announced = AnnouncedInterruptions()
        announced.announce(4L)
        announced.announce(7L)
        assertTrue("the first announcement was forgotten", announced.wasAnnounced(4L))
        assertTrue(announced.wasAnnounced(7L))
    }

    @Test
    fun anExplorationThisRuntimeSaidNothingAboutIsNotRemembered() {
        // The P4-041 direction. A process the system killed and restarted announces nothing, so this
        // must answer false for everything and leave that recovery exactly as it was.
        val announced = AnnouncedInterruptions()
        announced.announce(4L)
        assertFalse(announced.wasAnnounced(7L))
    }

    @Test
    fun theAbsentSessionIdIsNotAnExploration() {
        // 0 is the "no session" value the repository uses for an unset row id. Recording it would
        // make `wasAnnounced(0)` true and could end a session that never existed.
        val announced = AnnouncedInterruptions()
        announced.announce(0L)
        assertFalse(announced.wasAnnounced(0L))
    }
}
