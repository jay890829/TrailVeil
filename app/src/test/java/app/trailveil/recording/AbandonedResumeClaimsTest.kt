package app.trailveil.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbandonedResumeClaimsTest {
    @Test
    fun `an exploration is offered to recovery once`() {
        val claims = AbandonedResumeClaims()

        assertTrue(claims.claim(7L))
        // A blocked attempt - no permission, location switched off - must not become a loop, and
        // this is the only thing standing between one offer and one per recomposition.
        assertFalse(claims.claim(7L))
        assertFalse(claims.claim(7L))
    }

    @Test
    fun `a different exploration is a different question`() {
        val claims = AbandonedResumeClaims()
        claims.claim(7L)

        assertTrue(claims.claim(8L))
    }

    @Test
    fun `an attempt cancelled before a durable outcome can be offered again`() {
        val claims = AbandonedResumeClaims()

        assertTrue(claims.claim(7L))
        assertTrue(claims.release(7L))
        assertTrue(claims.claim(7L))
    }

    @Test
    fun `a late cancellation cannot erase a newer exploration claim`() {
        val claims = AbandonedResumeClaims()
        claims.claim(7L)
        claims.claim(8L)

        assertFalse(claims.release(7L))
        assertFalse("the newer claim was erased by the old cancellation", claims.claim(8L))
    }

    @Test
    fun `an unidentified session cannot release a real claim`() {
        val claims = AbandonedResumeClaims()
        claims.claim(7L)

        assertFalse(claims.release(0L))
        assertFalse(claims.release(-1L))
        assertFalse(claims.claim(7L))
    }

    @Test
    fun `only one claim is remembered, and that is the trade`() {
        // One slot, so returning to a session after another was offered gives it a fresh attempt.
        // Asserted rather than left to be discovered: the guard is meant to stop a loop within one
        // screen's lifetime, not to keep a permanent ledger, and a reader deserves to see which.
        val claims = AbandonedResumeClaims()
        claims.claim(7L)
        claims.claim(8L)

        assertTrue(claims.claim(7L))
    }

    @Test
    fun `an unidentified session is never claimed`() {
        val claims = AbandonedResumeClaims()

        assertFalse(claims.claim(0L))
        assertFalse(claims.claim(-1L))
        // And a refusal must not consume the slot a real session will need.
        assertTrue(claims.claim(7L))
    }
}
