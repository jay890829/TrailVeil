package app.trailveil.feature.recording

import android.content.Context
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

internal data class PermissionHistory(
    val hasSeenIntroduction: Boolean = false,
    val hasRequestedLocation: Boolean = false,
    val hasRetriedLocation: Boolean = false,
    val hasRequestedPreciseUpgrade: Boolean = false,
    val hasRequestedNotifications: Boolean = false,
) {
    companion object {
        /** A corrupt/unreadable marker store must never be interpreted as permission consent. */
        val ConservativeFallback = PermissionHistory(
            hasSeenIntroduction = true,
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

    val history: Flow<PermissionHistory> = dataStore.data
        .map { preferences ->
            PermissionHistory(
                hasSeenIntroduction = preferences[HasSeenIntroduction] ?: false,
                hasRequestedLocation = preferences[HasRequestedLocation] ?: false,
                hasRetriedLocation = preferences[HasRetriedLocation] ?: false,
                hasRequestedPreciseUpgrade =
                    preferences[HasRequestedPreciseUpgrade] ?: false,
                hasRequestedNotifications =
                    preferences[HasRequestedNotifications] ?: false,
            )
        }
        .catch { failure ->
            if (failure is IOException) {
                emit(PermissionHistory.ConservativeFallback)
            } else {
                throw failure
            }
        }

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

    private companion object {
        val HasSeenIntroduction = booleanPreferencesKey("has_seen_introduction")
        val HasRequestedLocation = booleanPreferencesKey("has_requested_location")
        val HasRetriedLocation = booleanPreferencesKey("has_retried_location")
        val HasRequestedPreciseUpgrade =
            booleanPreferencesKey("has_requested_precise_upgrade")
        val HasRequestedNotifications =
            booleanPreferencesKey("has_requested_notifications")
    }
}