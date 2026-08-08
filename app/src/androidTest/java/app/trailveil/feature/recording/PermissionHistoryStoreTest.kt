package app.trailveil.feature.recording

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionHistoryStoreTest {
    @Test
    fun aCorruptPreferencesFileEmitsTheConservativeFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile(
            "permission-history-corrupt-",
            ".preferences_pb",
            context.cacheDir,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // An unterminated protobuf varint makes the real Preferences serializer report an
            // unreadable/corrupt file rather than an empty preference set.
            file.writeBytes(byteArrayOf(0x80.toByte()))
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file },
            )

            val actual = withTimeout(5_000L) {
                dataStore.data.toPermissionHistory().first()
            }

            assertEquals(PermissionHistory.ConservativeFallback, actual)
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
