package com.askeri.disiplin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Kilitli görev zinciri: GUSÜL → SABAH NAMAZI → ÖĞÜN (adım adım tarif).
 * Her adım bir öncekinin onayı olmadan açılmaz; süreler uygulama kapansa da korunur.
 */
class RoutineActivity : AppCompatActivity() {

    companion object {
        const val GUSUL_SECONDS = 5 * 60          // Görev 1: 5 dk geri sayım
        const val NAMAZ_MIN_SECONDS = 4 * 60      // Görev 2: anlık geçişi önleyen asgari süre (değiştirilebilir)
        const val RECIPE_STEP_MIN_SECONDS = 5     // Tarif adımlarını hızlı tıklayıp geçmeyi önler

        const val STEP_GUSUL = 0
        const val STEP_NAMAZ = 1
        const val STEP_MEAL = 2
        const val STEP_DONE = 3
    }

    private lateinit var p: Prefs
    private lateinit var tvChain: TextView
    private lateinit var tvStepTitle: TextView
    private lateinit var tvStepDesc: TextView
    private lateinit var tvTimer: TextView
    private lateinit var ivMealPlan: ImageView
    private lateinit var tvRecipeTitle: TextView
    private lateinit var tvRecipeStep: TextView
    private lateinit var progressRecipe: ProgressBar
    private lateinit var progressLoading: ProgressBar
    private lateinit var btnConfirm: Button

    private var recipe: Recipe? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateTimer()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        setContentView(R.layout.activity_routine)
        p = Prefs(this)

        tvChain = findViewById(R.id.tvChain)
        tvStepTitle = findViewById(R.id.tvStepTitle)
        tvStepDesc = findViewById(R.id.tvStepDesc)
        tvTimer = findViewById(R.id.tvTimer)
        ivMealPlan = findViewById(R.id.ivMealPlan)
        tvRecipeTitle = findViewById(R.id.tvRecipeTitle)
        tvRecipeStep = findViewById(R.id.tvRecipeStep)
        progressRecipe = findViewById(R.id.progressRecipe)
        progressLoading = findViewById(R.id.progressLoading)
        btnConfirm = findViewById(R.id.btnConfirm)

        if (p.routineDate != todayKey()) p.startRoutine()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (p.routineStep == STEP_DONE) finish()
                else Toast.makeText(this@RoutineActivity, "Görev zinciri bitmeden çıkış yok.", Toast.LENGTH_SHORT).show()
            }
        })

        btnConfirm.setOnClickListener { onConfirm() }

        // Gusül sırasında tarif arka planda hazırlanır (Gemini 5 dk içinde yanıtlar)
        lifecycleScope.launch {
            recipe = RecipeProvider.getTodayRecipe(this@RoutineActivity)
            if (p.routineStep == STEP_MEAL) render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun elapsedSec(): Int = ((System.currentTimeMillis() - p.stepStartedAt) / 1000).toInt()

    private fun requiredSec(): Int = when (p.routineStep) {
        STEP_GUSUL -> GUSUL_SECONDS
        STEP_NAMAZ -> NAMAZ_MIN_SECONDS
        STEP_MEAL -> RECIPE_STEP_MIN_SECONDS
        else -> 0
    }

    private fun updateTimer() {
        if (p.routineStep == STEP_DONE) { tvTimer.visibility = View.GONE; return }
        tvTimer.visibility = View.VISIBLE
        val remain = requiredSec() - elapsedSec()
        if (remain > 0) {
            tvTimer.text = String.format("%02d:%02d", remain / 60, remain % 60)
            btnConfirm.isEnabled = false
        } else {
            tvTimer.text = "✔ ONAYLAYABİLİRSİN"
            btnConfirm.isEnabled = p.routineStep != STEP_MEAL || recipe != null
        }
    }

    private fun renderChain() {
        val s = p.routineStep
        fun mark(i: Int, name: String) = when {
            s > i -> "✔ $name"
            s == i -> "▶ $name"
            else -> "🔒 $name"
        }
        tvChain.text = "${mark(0, "GUSÜL")}   ${mark(1, "NAMAZ")}   ${mark(2, "ÖĞÜN")}"
    }

    private fun render() {
        renderChain()
        ivMealPlan.visibility = View.GONE
        tvRecipeTitle.visibility = View.GONE
        tvRecipeStep.visibility = View.GONE
        progressRecipe.visibility = View.GONE
        progressLoading.visibility = View.GONE

        when (p.routineStep) {
            STEP_GUSUL -> {
                tvStepTitle.text = "GÖREV 1 — KALK, DUŞA GİR, GUSÜL ABDESTİ AL"
                tvStepDesc.text = "Niyet et ve gusülün üç farzını eksiksiz yerine getir:\n\n" +
                    "1. Ağzı bolca suyla çalkala (mazmaza)\n" +
                    "2. Burna su çekip temizle (istinşak)\n" +
                    "3. Kuru yer kalmayacak şekilde tüm bedeni yıka\n\n" +
                    "Onay butonu 5 dakika sonra açılır."
                btnConfirm.text = "GUSÜL ABDESTİMİ ALDIM"
            }
            STEP_NAMAZ -> {
                tvStepTitle.text = "GÖREV 2 — SABAH NAMAZINI KIL"
                tvStepDesc.text = "2 rekât sünnet + 2 rekât farz.\n\n" +
                    "Seccadeni ser, telefonu bırak, huşu ile kıl.\n" +
                    "Onay butonu ${NAMAZ_MIN_SECONDS / 60} dakika sonra açılır."
                btnConfirm.text = "SABAH NAMAZIMI KILDIM"
            }
            STEP_MEAL -> renderMeal()
            STEP_DONE -> {
                tvStepTitle.text = "GÖREV ZİNCİRİ TAMAMLANDI 🎖"
                tvStepDesc.text = workoutMessage()
                btnConfirm.text = "KAPAT"
                btnConfirm.isEnabled = true
            }
        }
        updateTimer()
    }

    private fun renderMeal() {
        tvStepTitle.text = "GÖREV 3 — MUTFAĞA GEÇ, ÖĞÜNÜ HAZIRLA"
        p.mealPlanPath?.let { File(it) }?.takeIf { it.exists() }?.let { f ->
            ImageUtil.decodeSampled(f, 900)?.let {
                ivMealPlan.setImageBitmap(it)
                ivMealPlan.visibility = View.VISIBLE
            }
        }
        val r = recipe
        if (r == null) {
            tvStepDesc.text = "Yemek programın okunuyor, tarif hazırlanıyor..."
            progressLoading.visibility = View.VISIBLE
            btnConfirm.text = "BEKLE..."
            return
        }
        val total = r.steps.size + 1 // 0. adım: malzemeler
        val idx = p.recipeStepIndex.coerceIn(0, total - 1)
        tvRecipeTitle.text = "🍽 ${r.title}"
        tvRecipeTitle.visibility = View.VISIBLE
        tvStepDesc.text = r.source
        progressRecipe.max = total
        progressRecipe.progress = idx + 1
        progressRecipe.visibility = View.VISIBLE
        tvRecipeStep.visibility = View.VISIBLE
        tvRecipeStep.text = if (idx == 0) {
            "ADIM 1/$total — MALZEMELERİ TEZGÂHA ÇIKAR\n\n" + r.ingredients.joinToString("\n") { "• $it" }
        } else {
            "ADIM ${idx + 1}/$total\n\n${r.steps[idx - 1]}"
        }
        btnConfirm.text = if (idx == total - 1) "SON ADIM TAMAM — AFİYET OLSUN" else "BU ADIMI TAMAMLADIM ➜"
    }

    private fun onConfirm() {
        when (p.routineStep) {
            STEP_GUSUL -> advance(STEP_NAMAZ)
            STEP_NAMAZ -> advance(STEP_MEAL)
            STEP_MEAL -> {
                val r = recipe ?: return
                val next = p.recipeStepIndex + 1
                if (next >= r.steps.size + 1) {
                    advance(STEP_DONE)
                } else {
                    p.recipeStepIndex = next
                    p.stepStartedAt = System.currentTimeMillis()
                    render()
                }
            }
            STEP_DONE -> finish()
        }
    }

    private fun advance(step: Int) {
        p.routineStep = step
        p.recipeStepIndex = 0
        p.stepStartedAt = System.currentTimeMillis()
        if (step == STEP_DONE) onRoutineDone()
        render()
    }

    private fun onRoutineDone() {
        p.routineDoneDate = todayKey()
        val today = todayKey()
        if (!p.workoutEnabled || p.workoutDoneDate == today) return
        val due = p.workoutPendingDate == today || AlarmScheduler.isWorkoutTimeReached(p)
        if (due) {
            if (!WorkoutOverlayService.start(this)) {
                Toast.makeText(this, "Antrenman kilidi için 'Diğer uygulamaların üzerinde göster' izni gerekli", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun workoutMessage(): String {
        val today = todayKey()
        return when {
            !p.workoutEnabled -> "İbadet ve öğün tamam. Antrenman kilidi kapalı."
            p.workoutDoneDate == today -> "Bugünün antrenmanı da tamam. Rahat!"
            WorkoutOverlayService.running -> "Antrenman kilidi devrede. 15 dk karın seti başladı."
            else -> String.format(
                "İbadet ve öğün tamam.\nAntrenman kilidi %02d:%02d'de tam ekran devreye girecek.",
                p.workoutHour, p.workoutMinute
            )
        }
    }
}
