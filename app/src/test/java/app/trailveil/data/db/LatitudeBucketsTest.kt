package app.trailveil.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatitudeBucketsTest {
    @Test
    fun `a bucket is never negative, which is what makes the padding value impossible`() {
        // The padding slot only stays inert because no real row can carry it. TrackPointEntity
        // requires latitude in [-90, 90], so the whole legal range is checked here rather than
        // asserted in prose.
        listOf(-90.0, -89.9999, -45.0, 0.0, 25.0330, 89.9999, 90.0).forEach { latitude ->
            assertTrue(
                "latitude $latitude produced ${LatitudeBuckets.of(latitude)}",
                LatitudeBuckets.of(latitude) >= 0,
            )
        }
        assertTrue(LatitudeBuckets.PADDING_BUCKET < 0)
    }

    @Test
    fun `a band covers every bucket it touches, and the padding fills the rest`() {
        val south = 25.0300
        val north = 25.0360
        val buckets = requireNotNull(LatitudeBuckets.covering(south, north))

        assertEquals(LatitudeBuckets.MAX_BUCKETS, buckets.size)
        // Both edges present: an off-by-one at either end drops a stripe of the viewport, and the
        // rows in it would simply not be drawn - extra fog, which no leak audit catches.
        assertTrue(LatitudeBuckets.of(south) in buckets.toList())
        assertTrue(LatitudeBuckets.of(north) in buckets.toList())
        val real = buckets.filter { it != LatitudeBuckets.PADDING_BUCKET }
        assertEquals(LatitudeBuckets.of(north) - LatitudeBuckets.of(south) + 1, real.size)
        assertEquals(real.sorted(), real)
        assertEquals(real.distinct(), real)
    }

    @Test
    fun `a band given north-first covers the same buckets`() {
        assertEquals(
            LatitudeBuckets.covering(25.0300, 25.0360)!!.toList(),
            LatitudeBuckets.covering(25.0360, 25.0300)!!.toList(),
        )
    }

    @Test
    fun `a band too tall to spell as equalities asks for the range query instead`() {
        // The caller must fall back rather than truncate: a truncated list silently drops the
        // stripes it could not fit, which is an under-read the fog audits cannot see.
        assertNull(LatitudeBuckets.covering(0.0, 90.0))
        val widest = (LatitudeBuckets.MAX_BUCKETS - 1) / LatitudeBuckets.BUCKETS_PER_DEGREE
        assertEquals(
            LatitudeBuckets.MAX_BUCKETS,
            requireNotNull(LatitudeBuckets.covering(0.0, widest)).size,
        )
        assertNull(LatitudeBuckets.covering(0.0, widest + 1.0 / LatitudeBuckets.BUCKETS_PER_DEGREE))
    }

    @Test
    fun `the SQL the trigger and the migration use computes the same bucket as the Kotlin`() {
        // The bucket exists in three places - this function, MIGRATION_6_7's backfill UPDATE, and
        // the trigger - and a disagreement between them is exactly the silent under-read the
        // trigger exists to prevent. CAST(x AS INTEGER) truncates toward zero and floor() does not,
        // so they agree only because a bucket is never negative; that is asserted above, and this
        // pins the arithmetic itself.
        listOf(-90.0, -33.8688, 0.0, 25.0330, 51.5074, 89.9).forEach { latitude ->
            val sqlEquivalent = ((latitude + 90.0) * 500.0).toInt()
            assertEquals(
                "latitude $latitude disagrees between Kotlin and the SQL expression",
                sqlEquivalent,
                LatitudeBuckets.of(latitude),
            )
        }
    }
}
