package com.askeri.disiplin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    const val ACTION_ALARM = "com.askeri.disiplin.ACTION_ALARM"
    const val ACTION_WORKOUT = "com.askeri.disiplin.ACTION_WORKOUT"
    private const val RC_ALARM = 1001
    private const val RC_WORKOUT = 1002

    fun nextTrigger(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun broadcast(ctx: Context, action: String, rc: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, rc,
            Intent(ctx, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun canExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /** atMillis verilirse (test) o anda, yoksa ayarlı saatte çalar. */
    fun scheduleAlarm(ctx: Context, atMillis: Long? = null): Long {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val trigger = atMillis ?: nextTrigger(p.alarmHour, p.alarmMinute)
        val op = broadcast(ctx, ACTION_ALARM, RC_ALARM)
        if (canExact(am)) {
            // setAlarmClock: Doze'u deler, durum çubuğunda alarm ikonu çıkar
            val show = PendingIntent.getActivity(
                ctx, 2001, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), op)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, op)
        }
        return trigger
    }

    fun cancelAlarm(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(broadcast(ctx, ACTION_ALARM, RC_ALARM))
    }

    fun scheduleWorkout(ctx: Context): Long {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val trigger = nextTrigger(p.workoutHour, p.workoutMinute)
        val op = broadcast(ctx, ACTION_WORKOUT, RC_WORKOUT)
        if (canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, op)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, op)
        return trigger
    }

    fun cancelWorkout(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(broadcast(ctx, ACTION_WORKOUT, RC_WORKOUT))
    }

    /** Bugünün antrenman saati geçti mi? */
    fun isWorkoutTimeReached(p: Prefs): Boolean {
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return now >= p.workoutHour * 60 + p.workoutMinute
    }
}
