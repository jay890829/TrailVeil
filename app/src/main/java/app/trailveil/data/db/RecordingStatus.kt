package app.trailveil.data.db

import androidx.room.TypeConverter

enum class RecordingStatus {
    STARTING,
    ACTIVE,
    COMPLETED,
    INTERRUPTED,
    FAILED_TO_START,
}

internal const val ACTIVE_SESSION_SLOT = 1
internal const val OPEN_SEGMENT_SLOT = 1

internal object RecordingStatusConverters {
    @TypeConverter
    fun fromStorage(value: String): RecordingStatus = RecordingStatus.valueOf(value)

    @TypeConverter
    fun toStorage(value: RecordingStatus): String = value.name
}