package gustian.multiwallpaper

import gustian.multiwallpaper.data.ScheduleEntity
import java.text.SimpleDateFormat
import java.util.*

object ScheduleManager {
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun isScheduleActive(schedule: ScheduleEntity, currentTime: Calendar = Calendar.getInstance()): Boolean {
        if (!schedule.isEnabled) return false

        val currentStr = timeFormat.format(currentTime.time)
        val startStr = schedule.startTime
        val endStr = schedule.endTime

        return if (startStr <= endStr) {
            // Normal range (e.g., 08:00 to 17:00)
            currentStr in startStr..endStr
        } else {
            // Overnight range (e.g., 22:00 to 06:00)
            currentStr >= startStr || currentStr <= endStr
        }
    }

    fun getActiveSchedule(schedules: List<ScheduleEntity>, currentTime: Calendar = Calendar.getInstance()): ScheduleEntity? {
        // Find the first active schedule.
        // If multiple schedules overlap, the one appearing first in the sorted list (startTime) is picked.
        return schedules.firstOrNull { isScheduleActive(it, currentTime) }
    }
}
