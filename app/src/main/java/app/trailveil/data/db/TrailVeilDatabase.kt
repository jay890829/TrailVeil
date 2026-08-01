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
    ],
    version = 3,
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
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Self-heal a database created by an early v2 development build that
                // predates the callbacks; CREATE TRIGGER IF NOT EXISTS is idempotent.
                createDatabaseInvariantTriggers(db)
            }
        }

        fun open(context: Context): TrailVeilDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TrailVeilDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(invariantCallback)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}