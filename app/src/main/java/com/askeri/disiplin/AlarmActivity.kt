package com.askeri.disiplin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Alarm çalarken açılan kilit ekranı. Sırayla:
 *   GÖREV 1: Belirlenen nesnenin fotoğrafı (CameraX → Gemini / çevrimdışı bot)
 *   GÖREV 2: 3 orta-zor zihinden matematik sorusu
 * İkisi bitmeden alarm susmaz. Geri tuşu ve ses tuşları devre dışı.
 */
class AlarmActivity : AppCompatActivity() {

    companion object {
        @Volatile var isVisible = false
        const val MATH_REQUIRED = 3
        const val BACKUP_MATH_REQUIRED = 10   // nesne doğrulama teknik olarak imkânsızsa ceza
        const val TECH_FAIL_LIMIT = 5
    }

    private lateinit var p: Prefs
    private lateinit var tvClock: TextView
    private lateinit var tvStage: TextView
    private lateinit var cameraSection: View
    private lateinit var previewView: PreviewView
    private lateinit var progressVerify: ProgressBar
    private lateinit var tvCameraStatus: TextView
    private lateinit var btnCapture: Button
    private lateinit var mathSection: LinearLayout
    private lateinit var tvMathProgress: TextView
    private lateinit var tvQuestion: TextView
    private lateinit var etAnswer: EditText
    private lateinit var btnSubmit: Button
    private lateinit var tvMathFeedback: TextView

    private var imageCapture: ImageCapture? = null
    private var cameraStarted = false
    private val engine = MathEngine()
    private var current: MathQuestion? = null
    private var techFails = 0
    private var busy = false

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            tvClock.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else enterBackupMode("Kamera izni verilmedi")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.activity_alarm)
        p = Prefs(this)

        if (!p.alarmRinging) { finish(); return }
        AlarmService.start(this) // çalmıyorsa garantiye al

        tvClock = findViewById(R.id.tvClock)
        tvStage = findViewById(R.id.tvStage)
        cameraSection = findViewById(R.id.cameraSection)
        previewView = findViewById(R.id.previewView)
        progressVerify = findViewById(R.id.progressVerify)
        tvCameraStatus = findViewById(R.id.tvCameraStatus)
        btnCapture = findViewById(R.id.btnCapture)
        mathSection = findViewById(R.id.mathSection)
        tvMathProgress = findViewById(R.id.tvMathProgress)
        tvQuestion = findViewById(R.id.tvQuestion)
        etAnswer = findViewById(R.id.etAnswer)
        btnSubmit = findViewById(R.id.btnSubmit)
        tvMathFeedback = findViewById(R.id.tvMathFeedback)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = flash("Geri tuşu yok asker. Görevi bitir.")
        })

        btnCapture.setOnClickListener { capture() }
        btnSubmit.setOnClickListener { submitAnswer() }
        etAnswer.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitAnswer(); true } else false
        }
        render()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun requiredMath() = if (p.backupMode) BACKUP_MATH_REQUIRED else MATH_REQUIRED

    private fun render() {
        if (p.alarmStage == 0) {
            cameraSection.visibility = View.VISIBLE
            btnCapture.visibility = View.VISIBLE
            tvCameraStatus.visibility = View.VISIBLE
            mathSection.visibility = View.GONE
            tvStage.text = "GÖREV 1/2 — ${p.targetObject.uppercase(Locale.forLanguageTag("tr"))} FOTOĞRAFI\n" +
                "Kalk, git ve gerçek nesnenin fotoğrafını çek."
            ensureCamera()
        } else {
            stopCamera()
            cameraSection.visibility = View.GONE
            btnCapture.visibility = View.GONE
            tvCameraStatus.visibility = View.GONE
            mathSection.visibility = View.VISIBLE
            tvStage.text = if (p.backupMode)
                "GÖREV 2/2 — CEZA MATEMATİĞİ (nesne doğrulanamadı)"
            else "GÖREV 2/2 — ZİHİN UYANDIRMA"
            if (current == null) nextQuestion() else updateMathProgress()
        }
    }

    // ------------------------ KAMERA ------------------------

    private fun ensureCamera() {
        if (cameraStarted) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
                cameraStarted = true
                tvCameraStatus.text = "Kamera hazır. Nesneyi kadraja al."
            } catch (e: Exception) {
                techFail("Kamera açılamadı: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        if (!cameraStarted) return
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) { }
        cameraStarted = false
        imageCapture = null
    }

    private fun capture() {
        if (busy) return
        val cap = imageCapture ?: run { flash("Kamera henüz hazır değil"); return }
        setBusy(true)
        val file = File(cacheDir, "alarm_shot.jpg")
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = verify(file)
                override fun onError(exc: ImageCaptureException) {
                    setBusy(false)
                    techFail("Çekim hatası: ${exc.message}")
                }
            }
        )
    }

    private fun verify(file: File) {
        tvCameraStatus.text = "🔍 Doğrulanıyor..."
        lifecycleScope.launch {
            val result = ObjectVerifier(this@AlarmActivity).verify(file)
            setBusy(false)
            when (result) {
                is VerifyResult.Match -> {
                    techFails = 0
                    flash("✔ ${result.detail}")
                    p.alarmStage = 1
                    p.mathSolved = 0
                    render()
                }
                is VerifyResult.NoMatch -> {
                    tvCameraStatus.text = "✘ REDDEDİLDİ: ${result.reason}\n" +
                        "Gerçek ${p.targetObject} karşısına geç ve tekrar çek."
                }
                is VerifyResult.Error -> techFail(result.message)
            }
        }
    }

    private fun techFail(msg: String) {
        techFails++
        tvCameraStatus.text = "⚠ Teknik sorun ($techFails/$TECH_FAIL_LIMIT): $msg"
        if (techFails >= TECH_FAIL_LIMIT) enterBackupMode(msg)
    }

    /** Doğrulama teknik olarak imkânsızsa kullanıcı sonsuza dek kilitli kalmasın: 10 soruluk ceza. */
    private fun enterBackupMode(reason: String) {
        p.backupMode = true
        p.alarmStage = 1
        p.mathSolved = 0
        flash("Nesne doğrulanamıyor ($reason) → CEZA: $BACKUP_MATH_REQUIRED soru")
        render()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progressVerify.visibility = if (b) View.VISIBLE else View.GONE
        btnCapture.isEnabled = !b
    }

    // ------------------------ MATEMATİK ------------------------

    private fun nextQuestion() {
        current = engine.next()
        tvQuestion.text = "${current!!.text} = ?"
        etAnswer.setText("")
        etAnswer.requestFocus()
        updateMathProgress()
    }

    private fun updateMathProgress() {
        tvMathProgress.text = "Doğru: ${p.mathSolved} / ${requiredMath()}"
    }

    private fun submitAnswer() {
        val q = current ?: return
        val value = etAnswer.text.toString().trim().toIntOrNull()
        if (value == null) { flash("Bir sayı yaz"); return }
        if (value == q.answer) {
            p.mathSolved = p.mathSolved + 1
            tvMathFeedback.text = "✔ Doğru!"
            if (p.mathSolved >= requiredMath()) finishAlarm() else nextQuestion()
        } else {
            tvMathFeedback.text = "✘ Yanlış! Yeni soru geldi. Uyan."
            nextQuestion()
        }
    }

    private fun finishAlarm() {
        p.alarmRinging = false
        p.alarmStage = 0
        p.mathSolved = 0
        p.backupMode = false
        AlarmService.stop(this)
        p.startRoutine()
        startActivity(
            Intent(this, RoutineActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // ------------------------ KAÇIŞ ENGELLERİ ------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        if (!::tvStage.isInitialized) return
        isVisible = true
        clockHandler.post(clockTick)
        if (p.alarmStage == 0) ensureCamera()
    }

    override fun onPause() {
        isVisible = false
        clockHandler.removeCallbacks(clockTick)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        cameraStarted = false // arka plana gidince CameraX bağı düşer; dönünce yeniden kur
    }

    private fun flash(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
