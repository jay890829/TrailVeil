package app.trailveil.map

import app.trailveil.map.fog.GeoPoint
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Following a walking user is a different kind of camera move from being sent somewhere, and the
 * difference is what lets one be made without hiding the map first. These pin where the line is.
 */
class FollowCameraTest {
    @Test
    fun aUserWhoHasBarelyMovedDoesNotMoveTheMap() {
        // A 5 m location update at exploration zoom is a couple of pixels. Chasing it would jitter
        // the map under the user and rebuild the fog every few seconds for nothing.
        assertEquals(
            FollowCameraMove.HOLD,
            followCameraMove(
                offsetX = 8.0,
                offsetY = 6.0,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
    }

    @Test
    fun aUserWhoHasWalkedOutOfTheDeadZoneBringsTheMapWithThem() {
        val deadZone = VIEWPORT_WIDTH * FOLLOW_DEAD_ZONE_FRACTION
        assertEquals(
            FollowCameraMove.EASE,
            followCameraMove(
                offsetX = deadZone + 1.0,
                offsetY = 0.0,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
    }

    /** The dead zone is a radius, not a box, so a diagonal drift counts the same as a sideways one. */
    @Test
    fun theDeadZoneIsMeasuredAsADistanceNotPerAxis() {
        val deadZone = VIEWPORT_WIDTH * FOLLOW_DEAD_ZONE_FRACTION
        val component = deadZone * 0.8
        assertEquals(
            "a diagonal drift of ${hypot(component, component)} should have moved the map",
            FollowCameraMove.EASE,
            followCameraMove(
                offsetX = component,
                offsetY = component,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
    }

    /**
     * The bound the smooth path relies on. A follow step is only made without raising the safety
     * cover because it cannot cross more than one viewport; anything further is not a step, and
     * goes back through the ordinary programmed move, which since the A/B generations landed
     * hides the map only when it leaves the committed surround.
     */
    @Test
    fun aLocationOffScreenIsAMoveRatherThanAFollowStep() {
        assertEquals(
            FollowCameraMove.JUMP,
            followCameraMove(
                offsetX = VIEWPORT_WIDTH / 2.0 + 1.0,
                offsetY = 0.0,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
        assertEquals(
            FollowCameraMove.JUMP,
            followCameraMove(
                offsetX = 0.0,
                offsetY = -(VIEWPORT_HEIGHT / 2.0 + 1.0),
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
    }

    /**
     * A projection that cannot place the point returns a non-finite screen position rather than
     * failing. Treating that as "close enough to centred" would silently stop following.
     */
    @Test
    fun anUnprojectableLocationIsTreatedAsAMove() {
        assertEquals(
            FollowCameraMove.JUMP,
            followCameraMove(
                offsetX = Double.NaN,
                offsetY = 0.0,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
    }

    /** Before the map has been laid out there is no centre to be away from. */
    @Test
    fun aViewportWithNoSizeHoldsRatherThanGuessing() {
        assertEquals(
            FollowCameraMove.HOLD,
            followCameraMove(
                offsetX = 500.0,
                offsetY = 500.0,
                viewportWidth = 0,
                viewportHeight = 0,
            ),
        )
    }

    /**
     * A camera request may leave the zoom alone, which is what a move that is only about where to
     * look needs. The recentre button is not one of those any more — it names the exploration zoom,
     * because being taken back in is the point of pressing it — but a request without one is still
     * a shape this type accepts, and an impossible zoom is still one it refuses.
     */
    @Test
    fun aCameraRequestMayCarryNoZoomButNeverAnImpossibleOne() {
        assertEquals(
            null,
            MapCameraRequest(requestId = 1L, point = TAIPEI, zoom = null).zoom,
        )
        assertEquals(
            16.0,
            MapCameraRequest(requestId = 1L, point = TAIPEI).zoom,
        )
        assertThrows(IllegalArgumentException::class.java) {
            MapCameraRequest(requestId = 1L, point = TAIPEI, zoom = 23.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapCameraRequest(requestId = 1L, point = TAIPEI, zoom = Double.NaN)
        }
    }

    private companion object {
        const val VIEWPORT_WIDTH = 1080
        const val VIEWPORT_HEIGHT = 2400
        val TAIPEI = GeoPoint(25.0330, 121.5654)
    }
}
