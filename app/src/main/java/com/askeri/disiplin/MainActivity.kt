package com.askeri.disiplin

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kurulum ekranı: alarm saati, hedef nesne + referans fotoğraf, yemek programı görseli,
 * antrenman saati, Gemini API anahtarı ve gerekli izinler.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var p: Prefs

    private lateinit var tvAlarmTime: TextView
    private lateinit var swAlarm: MaterialSwitch
    private lateinit var etTargetObject: EditText
    private lateinit var etTargetLabels: EditText
    private lateinit var ivReference: ImageView
    private lateinit var ivMealPlan: ImageView
    private lateinit var tvRecipePreview: TextView
    private lateinit var tvWorkoutTime: TextView
    private lateinit var swWorkout: MaterialSwitch
    private lateinit var etApiKey: EditText
    private lateinit var etModels: EditText
    private lateinit var tvModelStatus: TextView
    private lateinit var tvPermStatus: TextView

    private var pendingRefFile: File? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updatePermStatus() }

    private val takeRefPhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val f = pendingRefFile
            if (ok && f != null && f.exists()) {
                p.referencePhotoPath = f.absolutePath
                showImage(ivReference, f)
                toast("Referans fotoğraf kaydedildi")
            }
        }

    private val pickMealPlan =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                val dst = File(imagesDir(), "meal_plan.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { input.copyTo(it) }
                }
                p.mealPlanPath = dst.absolutePath
                p.recipeCacheDate = "" // yeni program → tarifi yeniden oku
                showImage(ivMealPlan, dst)
                toast("Yemek programı kaydedildi")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        p = Prefs(this)

        tvAlarmTime = findViewById(R.id.tvAlarmTime)
        swAlarm = findViewById(R.id.swAlarm)
        etTargetObject = findViewById(R.id.etTargetObject)
        etTargetLabels = findViewById(R.id.etTargetLabels)
        ivReference = findViewById(R.id.ivReference)
        ivMealPlan = findViewById(R.id.ivMealPlan)
        tvRecipePreview = findViewById(R.id.tvRecipePreview)
        tvWorkoutTime = findViewById(R.id.tvWorkoutTime)
        swWorkout = findViewById(R.id.swWorkout)
        etApiKey = findViewById(R.id.etApiKey)
        etModels = findViewById(R.id.etModels)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvPermStatus = findViewById(R.id.tvPermStatus)

        loadFields()

        findViewById<Button>(R.id.btnPickAlarm).setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                p.alarmHour = h; p.alarmMinute = m; refreshTimes()
            }, p.alarmHour, p.alarmMinute, true).show()
        }
        findViewById<Button>(R.id.btnPickWorkout).setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                p.workoutHour = h; p.workoutMinute = m; refreshTimes()
            }, p.workoutHour, p.workoutMinute, true).show()
        }

        findViewById<Button>(R.id.btnTestAlarm).setOnClickListener {
            saveFields()
            if (!hasCamera()) { askBasicPermissions(); return@setOnClickListener }
            AlarmScheduler.scheduleAlarm(this, System.currentTimeMillis() + 60_000)
            toast("TEST: Alarm 1 dakika sonra çalacak. Kapatmak için görevleri bitirmen gerekecek!")
        }

        findViewById<Button>(R.id.btnRefPhoto).setOnClickListener {
            if (!hasCamera()) { askBasicPermissions(); return@setOnClickListener }
            val f = File(imagesDir(), "reference.jpg")
            pendingRefFile = f
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            takeRefPhoto.launch(uri)
        }

        findViewById<Button>(R.id.btnMealPlan).setOnClickListener {
            pickMealPlan.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        findViewById<Button>(R.id.btnPreviewRecipe).setOnClickListener {
            saveFields()
            tvRecipePreview.text = "Yemek programı okunuyor..."
            lifecycleScope.launch {
                val r = RecipeProvider.getTodayRecipe(this@MainActivity, forceRefresh = true)
                tvRecipePreview.text = r.toDisplay()
            }
        }

        findViewById<Button>(R.id.btnTestWorkout).setOnClickListener {
            if (!WorkoutOverlayService.start(this)) openOverlaySettings()
        }

        findViewById<Button>(R.id.btnTestModels).setOnClickListener {
            saveFields()
            p.clearExhausted()
            tvModelStatus.text = "Modeller keşfediliyor..."
            lifecycleScope.launch {
                val list = withContext(Dispatchers.IO) { GeminiClient(this@MainActivity).candidateModels(forceRefresh = true) }
                val now = System.currentTimeMillis()
                tvModelStatus.text = "Deneme sırası (uzun hak → kısa hak):\n" + list.mapIndexed { i, m ->
                    val ex = p.exhaustedUntil(m)
                    "${i + 1}. $m" + if (ex > now) "  (kota dolu)" else ""
                }.joinToString("\n") + "\n\nSon kullanılan: ${p.lastModelUsed.ifBlank { "-" }}"
            }
        }

        findViewById<Button>(R.id.btnPermBasic).setOnClickListener { askBasicPermissions() }
        findViewById<Button>(R.id.btnPermOverlay).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.btnPermExact).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } else toast("Bu Android sürümünde gerekmez")
        }
        findViewById<Button>(R.id.btnPermBattery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnPermFullScreen).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            } else toast("Bu Android sürümünde otomatik verilir")
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAndSchedule() }
        findViewById<Button>(R.id.btnRoutine).setOnClickListener {
            startActivity(Intent(this, RoutineActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Alarm çalarken ana uygulamaya kaçış yok
        if (p.alarmRinging) {
            startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        updatePermStatus()
    }

    // --------------------------------------------------------------

    private fun loadFields() {
        swAlarm.isChecked = p.alarmEnabled
        swWorkout.isChecked = p.workoutEnabled
        etTargetObject.setText(p.targetObject)
        etTargetLabels.setText(p.targetLabels)
        etApiKey.setText(p.apiKey)
        etModels.setText(p.customModels)
        p.referencePhotoPath?.let { showImage(ivReference, File(it)) }
        p.mealPlanPath?.let { showImage(ivMealPlan, File(it)) }
        refreshTimes()
    }

    private fun saveFields() {
        p.targetObject = etTargetObject.text.toString().trim().ifBlank { "çaydanlık" }
        p.targetLabels = etTargetLabels.text.toString().trim()
        p.apiKey = etApiKey.text.toString().trim()
        val newModels = etModels.text.toString().trim()
        if (newModels != p.customModels) { p.customModels = newModels; p.modelCacheDate = "" }
    }

    private fun saveAndSchedule() {
        saveFields()
        p.alarmEnabled = swAlarm.isChecked
        p.workoutEnabled = swWorkout.isChecked
        val msg = StringBuilder()
        if (p.alarmEnabled) {
            val t = AlarmScheduler.scheduleAlarm(this)
            val diff = (t - System.currentTimeMillis()) / 60000
            msg.append("Alarm: ${fmt(t)} (${diff / 60} sa ${diff % 60} dk sonra)\n")
        } else {
            AlarmScheduler.cancelAlarm(this); msg.append("Alarm kapalı\n")
        }
        if (p.workoutEnabled) {
            AlarmScheduler.scheduleWorkout(this)
            msg.append(String.format("Antrenman kilidi: %02d:%02d", p.workoutHour, p.workoutMinute))
        } else {
            AlarmScheduler.cancelWorkout(this); msg.append("Antrenman kilidi kapalı")
        }
        toast(msg.toString())
        updatePermStatus()
    }

    private fun refreshTimes() {
        tvAlarmTime.text = String.format("%02d:%02d", p.alarmHour, p.alarmMinute)
        tvWorkoutTime.text = String.format("%02d:%02d", p.workoutHour, p.workoutMinute)
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun askBasicPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    private fun openOverlaySettings() {
        toast("'Diğer uygulamaların üzerinde göster' iznini aç")
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun updatePermStatus() {
        fun ok(b: Boolean) = if (b) "✔" else "✘"
        val notif = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val exact = Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val fullScreen = Build.VERSION.SDK_INT < 34 ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        tvPermStatus.text = """
            ${ok(hasCamera())} Kamera
            ${ok(notif)} Bildirim
            ${ok(Settings.canDrawOverlays(this))} Diğer uygulamaların üzerinde göster (alarmı öne getirme + antrenman kilidi)
            ${ok(exact)} Tam zamanlı alarm
            ${ok(fullScreen)} Tam ekran bildirim (kilit ekranında alarm)
            ${ok(battery)} Pil optimizasyonu kapalı
        """.trimIndent()
    }

    private fun imagesDir(): File = File(filesDir, "images").apply { mkdirs() }

    private fun showImage(iv: ImageView, f: File) {
        ImageUtil.decodeSampled(f, 800)?.let {
            iv.setImageBitmap(it)
            iv.visibility = android.view.View.VISIBLE
        }
    }

    private fun fmt(t: Long) = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(t))
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
