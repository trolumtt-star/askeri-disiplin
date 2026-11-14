package com.askeri.disiplin

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * ODAK VE ANTRENMAN KİLİDİ
 * 15 dk karın seti (12 hareket × [50 sn çalış + 25 sn dinlen]) boyunca tam ekran overlay.
 * Overlay her uygulamanın üstünde durur; ana ekrana/başka uygulamaya geçilse bile kapanmaz.
 * Tek çıkış: "ACİL ÇIKIŞ" butonuna 2 dakika kesintisiz basılı tutmak (parmağını kaldırırsan sıfırlanır).
 */
class WorkoutOverlayService : Service() {

    data class Exercise(val name: String, val desc: String, val move: ExerciseAnimView.Move)

    companion object {
        private const val CHANNEL = "workout_channel"
        private const val NOTIF_ID = 77
        const val WORK_SEC = 50
        const val REST_SEC = 25
        const val EMERGENCY_HOLD_MS = 120_000L

        @Volatile var running = false

        val PLAN = listOf(
            Exercise("MEKİK (CRUNCH)", "Sırt yerde, dizler bükük. Boynu değil karnı sıkarak omuzları kaldır.", ExerciseAnimView.Move.CRUNCH),
            Exercise("BACAK KALDIRMA", "Bel yere bastırılı. Bacaklar düz, 90°'ye kaldır, yavaşça indir.", ExerciseAnimView.Move.LEG_RAISE),
            Exercise("PLANK", "Dirsekler omuz altında. Baştan topuğa düz çizgi, kalça düşmesin.", ExerciseAnimView.Move.PLANK),
            Exercise("BİSİKLET MEKİK", "Dirseği karşı dize götür, diğer bacağı uzat. Kontrollü tempo.", ExerciseAnimView.Move.BICYCLE),
            Exercise("DAĞCI (MOUNTAIN CLIMBER)", "Şınav pozisyonunda dizleri sırayla göğse çek. Hızlı!", ExerciseAnimView.Move.CLIMBER),
            Exercise("RUS TWISTİ", "Oturarak geriye yaslan, gövdeyi sağa-sola döndür.", ExerciseAnimView.Move.TWIST),
            Exercise("FLUTTER KICK", "Sırtüstü, bacaklar yerden 15 cm. Makas gibi küçük vuruşlar.", ExerciseAnimView.Move.FLUTTER),
            Exercise("YAN PLANK — SOL", "Sol kol üzerinde, kalça yukarıda, gövde düz.", ExerciseAnimView.Move.SIDE_PLANK),
            Exercise("YAN PLANK — SAĞ", "Sağ kol üzerinde, kalça yukarıda, gövde düz.", ExerciseAnimView.Move.SIDE_PLANK),
            Exercise("ÖLÜ BÖCEK (DEAD BUG)", "Bel yerde. Karşı kol ve bacağı yavaşça uzat, geri getir.", ExerciseAnimView.Move.DEAD_BUG),
            Exercise("TERS MEKİK", "Dizleri göğse çekip kalçayı yerden hafifçe kaldır.", ExerciseAnimView.Move.LEG_RAISE),
            Exercise("PLANK — FİNAL", "Son 50 saniye. Bırakmak yok asker!", ExerciseAnimView.Move.PLANK)
        )
        val TOTAL_SEC = PLAN.size * (WORK_SEC + REST_SEC) // 900 sn = 15 dk

        /** false dönerse overlay izni yok */
        fun start(c: Context): Boolean {
            if (!Settings.canDrawOverlays(c)) return false
            ContextCompat.startForegroundService(c, Intent(c, WorkoutOverlayService::class.java))
            return true
        }
    }

    private lateinit var p: Prefs
    private lateinit var wm: WindowManager
    private var root: View? = null
    private lateinit var tvPhase: TextView
    private lateinit var tvName: TextView
    private lateinit var tvDesc: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvNext: TextView
    private lateinit var tvEmergency: TextView
    private lateinit var progress: ProgressBar
    private lateinit var anim: ExerciseAnimView
    private lateinit var btnEmergency: Button

    private val handler = Handler(Looper.getMainLooper())
    private var holdStart = 0L
    private var lastPhaseKey = ""
    private var finished = false
    private var tone: ToneGenerator? = null

    private val tick = object : Runnable {
        override fun run() {
            update()
            if (!finished) handler.postDelayed(this, 250)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        p = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (_: Exception) { null }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (root == null) {
            val today = todayKey()
            if (p.workoutSessionDate != today || p.workoutStartedAt == 0L) {
                p.workoutSessionDate = today
                p.workoutStartedAt = System.currentTimeMillis()
            }
            showOverlay()
            handler.post(tick)
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showOverlay() {
        val ctx = ContextThemeWrapper(this, R.style.Theme_AskeriDisiplin)
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_workout, null)
        tvPhase = v.findViewById(R.id.tvPhase)
        tvName = v.findViewById(R.id.tvExerciseName)
        tvDesc = v.findViewById(R.id.tvExerciseDesc)
        tvTimer = v.findViewById(R.id.tvWorkoutTimer)
        tvNext = v.findViewById(R.id.tvNext)
        tvEmergency = v.findViewById(R.id.tvEmergency)
        progress = v.findViewById(R.id.progressWorkout)
        anim = v.findViewById(R.id.exerciseAnim)
        btnEmergency = v.findViewById(R.id.btnEmergency)
        progress.max = TOTAL_SEC

        btnEmergency.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> holdStart = System.currentTimeMillis()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (holdStart != 0L) tvEmergency.text = "Bıraktın → sayaç sıfırlandı. 2 dk kesintisiz basılı tutmalısın."
                    holdStart = 0L
                }
            }
            true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        wm.addView(v, params)
        root = v
    }

    private fun update() {
        val now = System.currentTimeMillis()
        val elapsed = ((now - p.workoutStartedAt) / 1000).toInt().coerceAtLeast(0)

        if (elapsed >= TOTAL_SEC) { complete(); return }

        val block = WORK_SEC + REST_SEC
        val idx = elapsed / block
        val inBlock = elapsed % block
        val ex = PLAN[idx]
        val next = PLAN.getOrNull(idx + 1)

        if (inBlock < WORK_SEC) {
            tvPhase.text = "ÇALIŞ  •  ${idx + 1}/${PLAN.size}"
            tvName.text = ex.name
            tvDesc.text = ex.desc
            tvTimer.text = "${WORK_SEC - inBlock}"
            anim.move = ex.move
            anim.resting = false
            tvNext.text = next?.let { "Sıradaki: ${it.name}" } ?: "Son hareket!"
            beepOnChange("W$idx")
        } else {
            tvPhase.text = "DİNLEN"
            tvName.text = next?.name ?: "BİTİŞ"
            tvDesc.text = next?.let { "Hazırlan: ${it.desc}" } ?: "Son saniyeler..."
            tvTimer.text = "${block - inBlock}"
            next?.let { anim.move = it.move }
            anim.resting = true
            tvNext.text = "Nefesini düzenle"
            beepOnChange("R$idx")
        }
        progress.progress = elapsed

        if (holdStart > 0) {
            val held = now - holdStart
            val left = ((EMERGENCY_HOLD_MS - held) / 1000).coerceAtLeast(0)
            tvEmergency.text = "ACİL ÇIKIŞ: Bırakma! $left sn kaldı"
            if (held >= EMERGENCY_HOLD_MS) emergencyExit()
        }
    }

    private fun beepOnChange(key: String) {
        if (key == lastPhaseKey) return
        if (lastPhaseKey.isNotEmpty()) {
            try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400) } catch (_: Exception) { }
        }
        lastPhaseKey = key
    }

    private fun complete() {
        if (finished) return
        finished = true
        p.workoutDoneDate = todayKey()
        p.workoutPendingDate = ""
        p.workoutStartedAt = 0L
        tvPhase.text = "TAMAMLANDI 🎖"
        tvName.text = "15 DAKİKA KARIN SETİ BİTTİ"
        tvDesc.text = "Disiplin, motivasyonun bittiği yerde başlar."
        tvTimer.text = "✔"
        try { tone?.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 800) } catch (_: Exception) { }
        handler.postDelayed({ stopSelf() }, 3500)
    }

    private fun emergencyExit() {
        finished = true
        p.workoutEscapedDate = todayKey()
        p.workoutDoneDate = todayKey() // bugün tekrar kilitlenmesin
        p.workoutPendingDate = ""
        p.workoutStartedAt = 0L
        stopSelf()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Antrenman Kilidi", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Antrenman kilidi aktif")
            .setContentText("15 dk karın seti bitene kadar telefon kilitli.")
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        root?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        root = null
        tone?.release()
        super.onDestroy()
    }
}
