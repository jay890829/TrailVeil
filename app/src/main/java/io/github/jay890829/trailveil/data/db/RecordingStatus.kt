package io.github.jay890829.trailveil.data.db

import androidx.room.TypeConverter

enum class RecordingStatus {
    ACTIVE,
    COMPLETED,
    INTERRUPTED,
    FAILED_TO_START,
}

internal const val ACTIVE_SESSION_SLOT = 1

internal object RecordingStatusConverters {
    @TypeConverter
    fun fromStorage(value: String): RecordingStatus = RecordingStatus.valueOf(value)

    @TypeConverter
    fun toStorage(value: RecordingStatus): String = value.name
}