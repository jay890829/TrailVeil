package app.trailveil.map

import java.util.concurrent.atomic.AtomicLong

/**
 * Composition-owned CAS ticket for every programmed map flight.
 *
 * This host ticket is independent of a provider binding. It therefore survives a null-runtime to
 * ready-runtime binding replacement and keeps follow effects stopped until the original SDK
 * callback releases the exact claim. Ticket ids are never reused during the controller's lifetime,
 * so a stale callback cannot clear a newer flight even after the old one has completed.
 */
internal class MapCameraFlightController {
    private val ticketSequence = AtomicLong(IDLE_CAMERA_FLIGHT)
    private val activeTicket = AtomicLong(IDLE_CAMERA_FLIGHT)

    val isActive: Boolean
        get() = activeTicket.get() != IDLE_CAMERA_FLIGHT

    fun claim(): Long {
        var ticket: Long
        do {
            ticket = ticketSequence.incrementAndGet()
        } while (ticket == IDLE_CAMERA_FLIGHT)
        activeTicket.set(ticket)
        return ticket
    }

    fun release(ticket: Long): Boolean =
        activeTicket.compareAndSet(ticket, IDLE_CAMERA_FLIGHT)
}
