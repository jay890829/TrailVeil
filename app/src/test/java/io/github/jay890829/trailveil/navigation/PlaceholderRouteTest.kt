package io.github.jay890829.trailveil.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderRouteTest {
    @Test
    fun placeholderRouteIsStable() {
        assertEquals("placeholder", PlaceholderRoute)
        assertTrue(PlaceholderRoute.matches(Regex("[a-z][a-z0-9_-]*")))
    }
}
