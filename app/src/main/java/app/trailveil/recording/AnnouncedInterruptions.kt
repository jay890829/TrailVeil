package app.trailveil.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which explorations this runtime has already told the user were interrupted.
 *
 * `P4-048`. The app announces 「探索已中斷 —— 錄製意外停止，已儲存的位置仍保留在歷史中」 and then, on
 * reopening, could still be recording that same exploration. Both halves were individually correct —
 * recording really had stopped, and `P4-038` decided that a stop inside one boot resumes — but a user
 * who acts on that notification is being misled. Once the interruption has been announced, the
 * exploration ends.
 *
 * **Held in memory, deliberately, and the reason is not convenience.** The case this exists for is
 * storage being unavailable: during the storage-full run even `SharedPreferences` failed with
 * `ENOSPC`, so a durable record would be least writable exactly when it is most needed. It does not
 * need to be durable, because of what distinguishes the two cases — in the announced case the
 * SERVICE stops while the PROCESS keeps running (measured: `dumpsys activity services` returned
 * `(nothing)` while `pidof` still answered), whereas an ordinary kill takes the process with it.
 * **If the process is still alive to resume, it is still alive to remember; if it is not, the case
 * is `P4-041`'s and resuming is correct.** The service shares this process — the manifest declares
 * no `android:process` — so what it records here is what the activity reads.
 *
 * **A collection, where [AbandonedResumeClaims] is a single slot, because the two forget in
 * opposite directions.** Forgetting a resume claim costs one extra offer, which is harmless.
 * Forgetting an announcement resumes an exploration the user was told had ended, which is the whole
 * defect. So this one may not overwrite, and the bound on its size is the number of explorations one
 * runtime announces — one per user-initiated exploration that failed, which in practice is one.
 *
 * **`V02-014`: it remembers the REASON, not only the fact.** The terminal row may still be
 * unwritten when this is read - that is the case `V02-014` repairs - and the repair has to write
 * the reason recording actually stopped for. The history screen shows that reason to the user, and
 * the only path that existed wrote `device_restarted`, which for a full disk is simply false. The
 * runtime knows the true reason at the moment it announces, and nothing else does afterwards.
 *
 * **`V02-014`: it is OBSERVABLE, and that is load-bearing rather than tidy.** The screen reads this
 * during composition, so a plain map would only be re-read when something else caused a
 * recomposition. Nothing reliably does: the two `MutableStateFlow` writes that follow an
 * announcement in `stopRuntime` are `clearStopping` and `clearLocation`, and in the case this whole
 * task exists for - storage already full when the first location arrives, so no location was ever
 * published - both are null-to-null and a `MutableStateFlow` conflates equal values. The screen
 * would keep saying "recording" until the user happened to leave and come back, which is most of the
 * defect surviving its own fix. Announcing therefore has to be a state change the screen is
 * subscribed to.
 */
internal class AnnouncedInterruptions {
    private val announced = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** Every announcement this runtime has made, session id to terminal reason. */
    val interruptions: StateFlow<Map<Long, String>> = announced.asStateFlow()

    /**
     * Records that the user has been told [sessionId] was interrupted, and why.
     *
     * First announcement wins, for the same reason this is a map and not a slot: a later
     * announcement about the same exploration cannot make the first one un-said, so overwriting its
     * reason would only replace the truth with a guess.
     */
    fun announce(sessionId: Long, reason: String) {
        if (sessionId <= NO_SESSION) return
        announced.update { current ->
            if (current.containsKey(sessionId)) current else current + (sessionId to reason)
        }
    }

    /** True when this runtime already told the user [sessionId] was interrupted. */
    fun wasAnnounced(sessionId: Long): Boolean = announced.value.containsKey(sessionId)

    /** Why this runtime stopped recording [sessionId], or null if it never announced one. */
    fun reasonFor(sessionId: Long): String? = announced.value[sessionId]

    private companion object {
        const val NO_SESSION = 0L
    }
}
