package app.trailveil.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecordingSessionEntity::class,
        TrackSegmentEntity::class,
        TrackPointEntity::class,
        RecordingOperationReceiptEntity::class,
        LocationReceiptWindowEntity::class,
        LocationReceiptRetentionStateEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(RecordingStatusConverters::class)
internal abstract class TrailVeilDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        internal const val DATABASE_NAME = "trailveil.db"

        internal val invariantCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createDatabaseInvariantTriggers(db)
                createTrackPointInvariantTriggers(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Self-heal a database created by an early v2 development build that
                // predates the callbacks; CREATE TRIGGER IF NOT EXISTS is idempotent.
                createDatabaseInvariantTriggers(db)
                createTrackPointInvariantTriggers(db)
            }
        }

        fun open(context: Context): TrailVeilDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TrailVeilDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                .addCallback(invariantCallback)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
