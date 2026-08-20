package it.agoldoni.reminder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String? = null,
    val dateTimeMillis: Long,
    val advanceMinutes: Int = 0,
    val completed: Boolean = false
)
