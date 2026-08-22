package app.trailveil.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.http.HttpLogger
import org.maplibre.android.log.Logger
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class RecordingPlatformContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestDeclaresExactlyTheLocationCapabilitiesPlanNamesAndAPrivateService() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES).toLong(),
            ),
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        // Set-equality, so the name's "exactly" is what the assertion holds: any permission added
        // to or removed from the manifest fails here by name, not only the two the old denylist
        // happened to watch. ACCESS_BACKGROUND_LOCATION is P4-041's: declared so the
        // Allow-all-the-time grade exists, letting a sticky restart re-arm location from the
        // background (measured: refused at While-in-use, recovered at all-the-time on the reference
        // device). PLAN's privacy section names its single purpose; the app never prompts for it,
        // and recording still starts only from a visible activity. This test fired on the
        // declaration exactly as designed - the posture changed with a recorded PLAN entry rather
        // than silently.
        assertEquals(
            setOf(
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.FOREGROUND_SERVICE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                // Injected by the build toolchain for apps registering non-exported dynamic
                // receivers on targetSdk 34+; not declared in our manifest. Listed so the equality
                // stays strict - if the toolchain stops injecting it, this fires and says why.
                "app.trailveil.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ).filterTo(sortedSetOf()) { true },
            permissions.toSortedSet(),
        )

        val service = context.packageManager.getServiceInfo(
            ComponentName(context, RecordingForegroundService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertFalse(service.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        assertEquals(0, service.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
    }

    /**
     * `P5-002`: the app's location history must not leave the device through the backup transport.
     *
     * PLAN's privacy section requires `allowBackup=false` plus deny-listed extraction rules, and
     * both are in the manifest — but nothing held them there. `allowBackup` is one attribute whose
     * removal a reviewer reads as tidying and whose effect is that the whole track database is
     * eligible for cloud backup and device transfer. It is asserted here from the INSTALLED
     * application info rather than by parsing the manifest, so a merged library manifest that
     * re-enables it also fails.
     *
     * The rules resources are checked too, and by their CONTENT rather than by their presence: they
     * are the braces to that flag's belt, and an `include` smuggled into either one would opt data
     * back in without touching the manifest. `ApplicationInfo` exposes only resource ids for them,
     * so they are read through the resource parser here.
     *
     * Not caught by this: whether the PLATFORM honours the rules. That is the OS's contract, and
     * this test's job is that we asked for the right thing.
     */
    @Test
    fun theInstalledApplicationIsNotEligibleForBackupOrDeviceTransfer() {
        val application = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.ApplicationInfoFlags.of(0L),
        )

        assertEquals(
            "allowBackup is enabled, so the location database is eligible for cloud backup",
            0,
            application.flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )

        // Every domain the platform can carry, deny-listed in every section of both files. Stated
        // as a set so a domain ADDED by a future platform (and left un-excluded) fails by name.
        val domains = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
        listOf(
            R.xml.data_extraction_rules to setOf("cloud-backup", "device-transfer"),
            R.xml.backup_rules to setOf("full-backup-content"),
        ).forEach { (resource, expectedSections) ->
            val name = context.resources.getResourceEntryName(resource)
            val (sections, excludedBySection, includes) = readBackupRules(resource)
            assertEquals("$name declares the wrong sections", expectedSections, sections)
            assertEquals(
                "$name opts data back IN with an include element",
                emptyList<String>(),
                includes,
            )
            expectedSections.forEach { section ->
                assertEquals(
                    "$name section <$section> does not deny-list every backup domain",
                    domains,
                    excludedBySection[section].orEmpty(),
                )
            }
        }
    }

    /** Sections found, the domains each one excludes, and any `include` element anywhere. */
    private fun readBackupRules(
        resource: Int,
    ): Triple<Set<String>, Map<String, Set<String>>, List<String>> {
        val sections = mutableSetOf<String>()
        val excluded = mutableMapOf<String, MutableSet<String>>()
        val includes = mutableListOf<String>()
        var section: String? = null
        context.resources.getXml(resource).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                // Both rule files write `domain` unprefixed, so the attribute has no namespace.
                val domain = parser.getAttributeValue(null, "domain")
                when (parser.name) {
                    "data-extraction-rules" -> Unit
                    "exclude" -> {
                        val owner = section ?: "full-backup-content"
                        excluded.getOrPut(owner) { mutableSetOf() } += domain
                    }
                    "include" -> includes += "${section ?: "?"}:$domain"
                    else -> {
                        section = parser.name
                        sections += parser.name
                    }
                }
            }
        }
        return Triple(sections, excluded, includes)
    }

    @Test
    fun channelIsLowImportanceAndStopActionIsImmutableAndSessionScoped() {
        val notifier = RecordingForegroundNotifier(context)
        notifier.ensureChannel()
        val channel = requireNotNull(
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(RecordingForegroundNotifier.CHANNEL_ID),
        )
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)

        val first = notifier.notification(sessionId = 41L)
        val second = notifier.notification(sessionId = 42L)
        assertTrue(first.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, first.actions.size)
        assertTrue(first.actions.single().actionIntent.isImmutable)
        assertEquals(context.packageName, first.actions.single().actionIntent.creatorPackage)
        assertNotEquals(
            first.actions.single().actionIntent,
            second.actions.single().actionIntent,
        )
    }

    /**
     * An outcome has to reach a user who stopped from the shade, or who never noticed the recording
     * die at all, so it is a normal dismissible notification on its own channel rather than another
     * ongoing one.
     */
    @Test
    fun outcomeNotificationsAreDismissibleAndSeparatelyMutable() {
        val notifier = RecordingForegroundNotifier(context)
        notifier.ensureChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = requireNotNull(
            manager.getNotificationChannel(RecordingForegroundNotifier.OUTCOME_CHANNEL_ID),
        )
        assertNotEquals(RecordingForegroundNotifier.CHANNEL_ID, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(
            manager.getNotificationChannel(
                RecordingForegroundNotifier.RETIRED_COMPLETED_CHANNEL_ID,
            ),
        )

        listOf(
            notifier.outcomeNotification(
                R.string.recording_completed_title,
                R.string.recording_completed_text,
            ),
            notifier.outcomeNotification(
                R.string.recording_interrupted_title,
                R.string.recording_interrupted_text,
            ),
        ).forEach { outcome ->
            assertEquals(0, outcome.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(outcome.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertNull(outcome.actions)
        }
        // One session has one outcome, so the two share an id and the later one replaces the older.
        assertNotEquals(
            RecordingForegroundNotifier.NOTIFICATION_ID,
            RecordingForegroundNotifier.OUTCOME_NOTIFICATION_ID,
        )
    }

    /**
     * A tile URL is a position: the `P5-002` logcat capture caught MapLibre printing
     * `.../14/13698/7027.pbf`, a 2.2 km square containing the device. `TrailVeilApplication` closes
     * both paths, and this holds them, because a two-line privacy switch with nothing on it reads
     * exactly like tidy-up — which is the lesson `allowBackup` already taught this class.
     *
     * The process under test has run `Application.onCreate`, so these are the live values.
     */
    @Test
    fun theMapSdkCannotPrintATileRequestUrlBecauseATileUrlIsAPosition() {
        assertFalse(
            "HttpLogger.logRequestUrl is on, so a failed tile request prints its URL, and a tile " +
                "URL locates the device to a couple of kilometres",
            HttpLogger.logRequestUrl,
        )

        // MapLibre keeps its level in a private field with no getter. Read it rather than replacing
        // the global LoggerDefinition, which would leak into every later test in this process.
        val logLevel = Logger::class.java.getDeclaredField("logLevel")
            .apply { isAccessible = true }
            .getInt(null)
        assertTrue(
            "MapLibre's verbosity is $logLevel, which still emits the DEBUG line carrying the tile " +
                "URL; it must be at least WARN (${Logger.WARN})",
            logLevel >= Logger.WARN,
        )
        // Guard the guard: if the level were somehow above ERROR the assertion above would pass by
        // silencing everything, including failures worth seeing.
        assertTrue(
            "MapLibre logging is silenced past ERROR, so real map failures are invisible too",
            logLevel <= Logger.ERROR,
        )
    }
}
