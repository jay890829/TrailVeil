package app.trailveil.recording

import java.util.concurrent.ConcurrentHashMap

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
 * **A set, where [AbandonedResumeClaims] is a single slot, because the two forget in opposite
 * directions.** Forgetting a resume claim costs one extra offer, which is harmless. Forgetting an
 * announcement resumes an exploration the user was told had ended, which is the whole defect. So
 * this one may not overwrite, and the bound on its size is the number of explorations one runtime
 * announces — one per user-initiated exploration that failed, which in practice is one.
 *
 * **`V02-014`: it remembers the REASON, not only the fact.** The terminal row may still be
 * unwritten when this is read - that is the case `V02-014` repairs - and the repair has to write
 * the reason recording actually stopped for. The history screen shows that reason to the user, and
 * the only path that existed wrote `device_restarted`, which for a full disk is simply false. The
 * runtime knows the true reason at the moment it announces, and nothing else does afterwards.
 */
internal class AnnouncedInterruptions {
    private val announced = ConcurrentHashMap<Long, String>()

    /**
     * Records that the user has been told [sessionId] was interrupted, and why.
     *
     * First announcement wins, for the same reason this is a map and not a slot: a later
     * announcement about the same exploration cannot make the first one un-said, so overwriting its
     * reason would only replace the truth with a guess.
     */
    fun announce(sessionId: Long, reason: String) {
        if (sessionId > NO_SESSION) announced.putIfAbsent(sessionId, reason)
    }

    /** True when this runtime already told the user [sessionId] was interrupted. */
    fun wasAnnounced(sessionId: Long): Boolean = announced.containsKey(sessionId)

    /** Why this runtime stopped recording [sessionId], or null if it never announced one. */
    fun reasonFor(sessionId: Long): String? = announced[sessionId]

    private companion object {
        const val NO_SESSION = 0L
    }
}
