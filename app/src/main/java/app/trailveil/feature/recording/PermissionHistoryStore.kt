package app.trailveil.feature.recording

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.permissionHistoryDataStore by preferencesDataStore(
    name = "permission_history",
)
private val HasSeenIntroduction = booleanPreferencesKey("has_seen_introduction")
private val HasRequestedLocation = booleanPreferencesKey("has_requested_location")
private val HasRetriedLocation = booleanPreferencesKey("has_retried_location")
private val HasRequestedPreciseUpgrade = booleanPreferencesKey("has_requested_precise_upgrade")
private val HasRequestedNotifications = booleanPreferencesKey("has_requested_notifications")

internal data class PermissionHistory(
    val hasSeenIntroduction: Boolean = false,
    val hasRequestedLocation: Boolean = false,
    val hasRetriedLocation: Boolean = false,
    val hasRequestedPreciseUpgrade: Boolean = false,
    val hasRequestedNotifications: Boolean = false,
) {
    companion object {
        /**
         * A corrupt or unreadable marker store must never be interpreted as permission consent.
         *
         * For the four request markers that means `true`: "assume we already asked", so nothing is
         * auto-requested on the user's behalf. `hasSeenIntroduction` reads the other way round —
         * `true` means "do not show the disclosure" — so the safe value for it is `false`. A user
         * whose store cannot be read gets the first-run disclosure again, which costs them one
         * dialog; the alternative was never showing it to someone who had never seen it.
         */
        val ConservativeFallback = PermissionHistory(
            hasSeenIntroduction = false,
            hasRequestedLocation = true,
            hasRetriedLocation = true,
            hasRequestedPreciseUpgrade = true,
            hasRequestedNotifications = true,
        )
    }
}

internal class PermissionHistoryStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val dataStore = applicationContext.permissionHistoryDataStore

    val history: Flow<PermissionHistory> = dataStore.data.toPermissionHistory()

    suspend fun current(): PermissionHistory = history.first()

    suspend fun markIntroductionSeen() {
        dataStore.edit { it[HasSeenIntroduction] = true }
    }

    suspend fun markLocationRequested() {
        dataStore.edit { it[HasRequestedLocation] = true }
    }

    suspend fun markLocationRetried() {
        dataStore.edit {
            it[HasRequestedLocation] = true
            it[HasRetriedLocation] = true
        }
    }

    suspend fun markPreciseUpgradeRequested() {
        dataStore.edit {
            it[HasRequestedLocation] = true
            it[HasRequestedPreciseUpgrade] = true
        }
    }

    suspend fun markNotificationsRequested() {
        dataStore.edit { it[HasRequestedNotifications] = true }
    }

    /** Same-process instrumentation fixture seam; production callers only use monotonic markers. */
    internal suspend fun replaceForTesting(history: PermissionHistory) {
        dataStore.edit { preferences ->
            preferences.clear()
            preferences[HasSeenIntroduction] = history.hasSeenIntroduction
            preferences[HasRequestedLocation] = history.hasRequestedLocation
            preferences[HasRetriedLocation] = history.hasRetriedLocation
            preferences[HasRequestedPreciseUpgrade] = history.hasRequestedPreciseUpgrade
            preferences[HasRequestedNotifications] = history.hasRequestedNotifications
        }
    }

}

/**
 * Keeps the real DataStore read-failure boundary directly testable without constructing a second
 * DataStore singleton. Only I/O failures represent an unreadable marker store; programming and
 * cancellation failures must stay visible instead of being mistaken for conservative history.
 */
internal fun Flow<Preferences>.toPermissionHistory(): Flow<PermissionHistory> =
    map { preferences ->
        PermissionHistory(
            hasSeenIntroduction = preferences[HasSeenIntroduction] ?: false,
            hasRequestedLocation = preferences[HasRequestedLocation] ?: false,
            hasRetriedLocation = preferences[HasRetriedLocation] ?: false,
            hasRequestedPreciseUpgrade =
                preferences[HasRequestedPreciseUpgrade] ?: false,
            hasRequestedNotifications =
                preferences[HasRequestedNotifications] ?: false,
        )
    }.catch { failure ->
        if (failure is IOException) {
            emit(PermissionHistory.ConservativeFallback)
        } else {
            throw failure
        }
    }
