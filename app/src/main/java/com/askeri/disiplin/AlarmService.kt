package com.askeri.disiplin

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Kaçışsız alarm servisi:
 *  - STREAM_ALARM sesi maksimuma çekilir, kısılırsa 0.5 sn içinde tekrar maksimuma döner.
 *  - Erteleme yok, bildirimde "kapat" butonu yok.
 *  - Kullanıcı başka uygulamaya kaçarsa AlarmActivity tekrar öne getirilir.
 *  - Sadece AlarmActivity, iki görev bitince stop() çağırır.
 */
class AlarmService : Service() {

    companion object {
        private const val CHANNEL = "alarm_channel"
        private const val NOTIF_ID = 42

        fun start(c: Context) {
            ContextCompat.startForegroundService(c, Intent(c, AlarmService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, AlarmService::class.java))
        }
    }

    private lateinit var audio: AudioManager
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ringing = false
    private var lastBringToFront = 0L

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = forceMaxVolume()
    }

    private val enforcer = object : Runnable {
        override fun run() {
            forceMaxVolume()
            ensurePlaying()
            bringAlarmToFront()
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        createChannel()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).alarmRinging) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ringing) startRinging()
        return START_STICKY
    }

    private fun startInForeground() {
        val n = buildNotification()
        try {
            val type = if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            else if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else 0
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (e: Exception) {
            // Tam zamanlı alarm izni yoksa systemExempted reddedilir → mediaPlayback dene
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun startRinging() {
        ringing = true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AskeriDisiplin:alarm").apply { acquire() }

        forceMaxVolume()
        startPlayer()
        startVibration()

        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        handler.post(enforcer)
        bringAlarmToFront(force = true)
    }

    private fun startPlayer() {
        val uris = listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        )
        for (uri in uris) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    setVolume(1f, 1f)
                    prepare()
                    start()
                }
                return
            } catch (_: Exception) {
                player?.release(); player = null
            }
        }
    }

    private fun ensurePlaying() {
        val pl = player
        if (pl == null) startPlayer()
        else try { if (!pl.isPlaying) pl.start() } catch (_: Exception) { player = null }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 900, 400), 0)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        try { vibrator?.vibrate(effect, attrs) } catch (_: Exception) { }
    }

    private fun forceMaxVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < max) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        } catch (_: SecurityException) { /* Rahatsız Etmeyin kısıtı */ }
    }

    /** Kullanıcı ana ekrana / başka uygulamaya kaçarsa alarm ekranını geri getirir. */
    private fun bringAlarmToFront(force: Boolean = false) {
        if (AlarmActivity.isVisible) return
        val now = System.currentTimeMillis()
        if (!force && now - lastBringToFront < 2500) return
        lastBringToFront = now
        try {
            startActivity(
                Intent(this, AlarmActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            )
        } catch (_: Exception) { /* "Diğer uygulamaların üzerinde göster" izni yoksa engellenebilir */ }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Kaçışsız Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val fsi = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("KALK ASKER!")
            .setContentText("Alarm yalnızca iki görev bitince susar. Erteleme yok.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fsi, true)
            .setContentIntent(fsi)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { contentResolver.unregisterContentObserver(volumeObserver) } catch (_: Exception) { }
        try { player?.stop() } catch (_: Exception) { }
        player?.release(); player = null
        vibrator?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        ringing = false
        super.onDestroy()
        // Görevler bitmeden sistem servisi öldürdüyse yeniden başlat
        if (Prefs(this).alarmRinging) {
            try { start(applicationContext) } catch (_: Exception) { }
        }
    }
}
