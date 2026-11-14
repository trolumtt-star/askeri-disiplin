package com.askeri.disiplin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * - ACTION_ALARM   : Sabah alarmı → AlarmService (maks. ses, kapatılamaz) başlar.
 * - ACTION_WORKOUT : Antrenman saati → rutin bittiyse tam ekran kilit, bitmediyse "bekliyor" işaretlenir.
 * - BOOT vb.       : Telefon yeniden açılınca alarmlar yeniden kurulur; çalan alarm yarıda kaldıysa tekrar çalar.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val p = Prefs(context)
        when (intent.action) {
            AlarmScheduler.ACTION_ALARM -> {
                p.alarmRinging = true
                p.alarmStage = 0
                p.mathSolved = 0
                p.backupMode = false
                if (p.alarmEnabled) AlarmScheduler.scheduleAlarm(context) // yarın için tekrar kur
                AlarmService.start(context)
            }

            AlarmScheduler.ACTION_WORKOUT -> {
                if (p.workoutEnabled) AlarmScheduler.scheduleWorkout(context)
                val today = todayKey()
                if (p.workoutDoneDate == today) return
                if (p.routineDoneDate == today) {
                    WorkoutOverlayService.start(context)
                } else {
                    // İbadet + yemek bitmeden kilit atılmaz; rutin bitince otomatik başlar
                    p.workoutPendingDate = today
                }
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                if (p.alarmEnabled) AlarmScheduler.scheduleAlarm(context)
                if (p.workoutEnabled) AlarmScheduler.scheduleWorkout(context)
                if (p.alarmRinging) AlarmService.start(context) // kaçış yok: telefonu kapatıp açmak işe yaramaz
                if (p.workoutSessionDate == todayKey() && p.workoutDoneDate != todayKey()) {
                    WorkoutOverlayService.start(context)
                }
            }
        }
    }
}
