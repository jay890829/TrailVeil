package app.trailveil.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

        // Deliberately NOT asserting `buckets.size == MAX_BUCKETS`: covering() builds
        // IntArray(MAX_BUCKETS) unconditionally whenever it returns non-null, so that assertion
        // cannot fail for any input. It read as a pin and was decoration.
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
        // The boundary itself: the widest band that still fits, and the next bucket beyond it.
        // Asserted through the count of REAL buckets, because the array's own length is fixed by
        // construction and says nothing.
        val widest = (LatitudeBuckets.MAX_BUCKETS - 1) / LatitudeBuckets.BUCKETS_PER_DEGREE
        val atTheLimit = requireNotNull(LatitudeBuckets.covering(0.0, widest))
        assertEquals(
            LatitudeBuckets.MAX_BUCKETS,
            atTheLimit.count { it != LatitudeBuckets.PADDING_BUCKET },
        )
        assertNull(LatitudeBuckets.covering(0.0, widest + 1.0 / LatitudeBuckets.BUCKETS_PER_DEGREE))
    }

    @Test
    fun `the arity follows the band it is meant to express`() {
        // MAX_BUCKETS and BUCKETS_PER_DEGREE were two independent literals tethered by a comment,
        // so retuning the granularity would have silently changed how tall a band still qualifies
        // for the equality route. The arity is derived now, and this pins the relationship rather
        // than the number: a band just inside MAX_BAND_DEGREES must be expressible, and one just
        // outside must fall back.
        assertEquals(
            LatitudeBuckets.MAX_BUCKETS,
            (LatitudeBuckets.MAX_BAND_DEGREES * LatitudeBuckets.BUCKETS_PER_DEGREE).toInt(),
        )
        val inside = LatitudeBuckets.MAX_BAND_DEGREES - 2.0 / LatitudeBuckets.BUCKETS_PER_DEGREE
        assertNotNull(LatitudeBuckets.covering(0.0, inside))
        assertNull(LatitudeBuckets.covering(0.0, LatitudeBuckets.MAX_BAND_DEGREES + 0.01))
    }

    @Test
    fun `flooring and SQL truncation agree, because a bucket is never negative`() {
        // SQLite's CAST(x AS INTEGER) truncates toward zero; Kotlin's floor() rounds down. They
        // differ for negative operands and agree for non-negative ones, and `latitude + 90` is
        // non-negative for every latitude the entity permits - so the two spellings of the bucket
        // are interchangeable. That is the property; it is what lets the trigger and the migration
        // use CAST at all.
        //
        // This deliberately does NOT retype the bucket size. An earlier version wrote
        // `* 500.0` here, which quietly made BUCKETS_PER_DEGREE unchangeable: retuning the
        // granularity turned this test red for no reason connected to correctness.
        //
        // Not caught here, and it is the more dangerous half: whether the SQL that actually
        // reached the database says the same thing. That needs the database, and
        // `LatitudeBucketDerivationTest#theTriggersArithmeticAgreesWithTheKotlinAtEveryKindOfLatitude`
        // reads the trigger back out of `sqlite_master` and evaluates it.
        listOf(-90.0, -33.8688, 0.0, 25.0330, 51.5074, 89.9).forEach { latitude ->
            val truncated = ((latitude + 90.0) * LatitudeBuckets.BUCKETS_PER_DEGREE).toInt()
            assertEquals(
                "latitude $latitude disagrees between flooring and truncation",
                truncated,
                LatitudeBuckets.of(latitude),
            )
        }
    }
}
