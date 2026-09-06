package app.trailveil.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        announced.announce(7L, A_REASON)
        assertTrue(announced.wasAnnounced(7L))
    }

    @Test
    fun aLaterAnnouncementDoesNotEraseAnEarlierOne() {
        // The failure this rules out: a single-slot record, which is what the resume-claim guard
        // uses. Announce two explorations in one runtime and the first must still be remembered --
        // otherwise reopening resumes the exploration the user was told about first.
        val announced = AnnouncedInterruptions()
        announced.announce(4L, A_REASON)
        announced.announce(7L, A_REASON)
        assertTrue("the first announcement was forgotten", announced.wasAnnounced(4L))
        assertTrue(announced.wasAnnounced(7L))
    }

    @Test
    fun anExplorationThisRuntimeSaidNothingAboutIsNotRemembered() {
        // The P4-041 direction. A process the system killed and restarted announces nothing, so this
        // must answer false for everything and leave that recovery exactly as it was.
        val announced = AnnouncedInterruptions()
        announced.announce(4L, A_REASON)
        assertFalse(announced.wasAnnounced(7L))
    }

    @Test
    fun theAbsentSessionIdIsNotAnExploration() {
        // 0 is the "no session" value the repository uses for an unset row id. Recording it would
        // make `wasAnnounced(0)` true and could end a session that never existed.
        val announced = AnnouncedInterruptions()
        announced.announce(0L, A_REASON)
        assertFalse(announced.wasAnnounced(0L))
        assertNull(announced.reasonFor(0L))
    }

    @Test
    fun theRememberedReasonIsTheOneAnnouncedFirst() {
        // `V02-014`. The reason is not decoration: the repair writes it onto the history screen,
        // where the user reads it. A second announcement about the same exploration cannot make the
        // first one un-said, so it must not be able to relabel it either. And the reason has to
        // survive alongside the fact, because a caller that got "announced" with no reason would
        // have to invent one — which is how `device_restarted` came to be written for a full disk.
        val announced = AnnouncedInterruptions()
        announced.announce(7L, "storage_failure")
        announced.announce(7L, "location_stream_failure")

        assertEquals("storage_failure", announced.reasonFor(7L))
        assertTrue(announced.wasAnnounced(7L))
        // An exploration nothing was said about has no reason to give, and that null is what leaves
        // the P4-041 recovery exactly as it was.
        assertNull(announced.reasonFor(4L))
    }

    private companion object {
        const val A_REASON = "storage_failure"
    }
}
