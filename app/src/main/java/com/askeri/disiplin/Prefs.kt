package com.askeri.disiplin

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bugünün anahtarı: 2026-11-15 */
fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** Tüm kalıcı ayarlar ve durum. Alarm/rutin uygulama kapansa bile kaldığı yerden devam eder. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("askeri_disiplin", Context.MODE_PRIVATE)

    // ---------- ALARM ----------
    var alarmHour: Int
        get() = sp.getInt("alarm_hour", 5)
        set(v) = sp.edit().putInt("alarm_hour", v).apply()
    var alarmMinute: Int
        get() = sp.getInt("alarm_minute", 0)
        set(v) = sp.edit().putInt("alarm_minute", v).apply()
    var alarmEnabled: Boolean
        get() = sp.getBoolean("alarm_enabled", false)
        set(v) = sp.edit().putBoolean("alarm_enabled", v).apply()

    /** Alarm şu an çalıyor mu? (commit: anında diske yazılır) */
    var alarmRinging: Boolean
        get() = sp.getBoolean("alarm_ringing", false)
        set(v) { sp.edit().putBoolean("alarm_ringing", v).commit() }
    /** 0 = nesne fotoğrafı, 1 = matematik */
    var alarmStage: Int
        get() = sp.getInt("alarm_stage", 0)
        set(v) { sp.edit().putInt("alarm_stage", v).commit() }
    var mathSolved: Int
        get() = sp.getInt("math_solved", 0)
        set(v) { sp.edit().putInt("math_solved", v).commit() }
    /** Nesne doğrulama teknik olarak imkânsızsa: ceza matematiği modu */
    var backupMode: Boolean
        get() = sp.getBoolean("backup_mode", false)
        set(v) { sp.edit().putBoolean("backup_mode", v).commit() }

    // ---------- NESNE ----------
    var targetObject: String
        get() = sp.getString("target_object", "çaydanlık") ?: "çaydanlık"
        set(v) = sp.edit().putString("target_object", v).apply()
    /** Çevrimdışı ML Kit için İngilizce etiketler (virgüllü) */
    var targetLabels: String
        get() = sp.getString("target_labels", "") ?: ""
        set(v) = sp.edit().putString("target_labels", v).apply()
    var referencePhotoPath: String?
        get() = sp.getString("ref_photo", null)
        set(v) = sp.edit().putString("ref_photo", v).apply()

    // ---------- YEMEK PROGRAMI ----------
    var mealPlanPath: String?
        get() = sp.getString("meal_plan", null)
        set(v) = sp.edit().putString("meal_plan", v).apply()
    var recipeCacheDate: String
        get() = sp.getString("recipe_date", "") ?: ""
        set(v) = sp.edit().putString("recipe_date", v).apply()
    var recipeCacheJson: String
        get() = sp.getString("recipe_json", "") ?: ""
        set(v) = sp.edit().putString("recipe_json", v).apply()

    // ---------- ANTRENMAN ----------
    var workoutHour: Int
        get() = sp.getInt("workout_hour", 7)
        set(v) = sp.edit().putInt("workout_hour", v).apply()
    var workoutMinute: Int
        get() = sp.getInt("workout_minute", 0)
        set(v) = sp.edit().putInt("workout_minute", v).apply()
    var workoutEnabled: Boolean
        get() = sp.getBoolean("workout_enabled", false)
        set(v) = sp.edit().putBoolean("workout_enabled", v).apply()
    var workoutDoneDate: String
        get() = sp.getString("workout_done", "") ?: ""
        set(v) { sp.edit().putString("workout_done", v).commit() }
    var workoutEscapedDate: String
        get() = sp.getString("workout_escaped", "") ?: ""
        set(v) { sp.edit().putString("workout_escaped", v).commit() }
    var workoutPendingDate: String
        get() = sp.getString("workout_pending", "") ?: ""
        set(v) { sp.edit().putString("workout_pending", v).commit() }
    var workoutSessionDate: String
        get() = sp.getString("workout_session", "") ?: ""
        set(v) { sp.edit().putString("workout_session", v).commit() }
    var workoutStartedAt: Long
        get() = sp.getLong("workout_started", 0L)
        set(v) { sp.edit().putLong("workout_started", v).commit() }

    // ---------- RUTİN ----------
    var routineDate: String
        get() = sp.getString("routine_date", "") ?: ""
        set(v) { sp.edit().putString("routine_date", v).commit() }
    var routineStep: Int
        get() = sp.getInt("routine_step", 0)
        set(v) { sp.edit().putInt("routine_step", v).commit() }
    var recipeStepIndex: Int
        get() = sp.getInt("recipe_step", 0)
        set(v) { sp.edit().putInt("recipe_step", v).commit() }
    var stepStartedAt: Long
        get() = sp.getLong("step_started", 0L)
        set(v) { sp.edit().putLong("step_started", v).commit() }
    var routineDoneDate: String
        get() = sp.getString("routine_done", "") ?: ""
        set(v) { sp.edit().putString("routine_done", v).commit() }

    fun startRoutine() {
        routineDate = todayKey()
        routineStep = 0
        recipeStepIndex = 0
        stepStartedAt = System.currentTimeMillis()
    }

    // ---------- GEMINI ----------
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v).apply()
    /** Boşsa otomatik keşif. Doluysa: virgüllü model sırası */
    var customModels: String
        get() = sp.getString("custom_models", "") ?: ""
        set(v) = sp.edit().putString("custom_models", v).apply()
    var modelCacheDate: String
        get() = sp.getString("model_cache_date", "") ?: ""
        set(v) = sp.edit().putString("model_cache_date", v).apply()
    var modelCacheList: String
        get() = sp.getString("model_cache_list", "") ?: ""
        set(v) = sp.edit().putString("model_cache_list", v).apply()
    var lastModelUsed: String
        get() = sp.getString("last_model", "") ?: ""
        set(v) = sp.edit().putString("last_model", v).apply()

    fun exhaustedUntil(model: String): Long = sp.getLong("ex_$model", 0L)
    fun markExhausted(model: String, until: Long) = sp.edit().putLong("ex_$model", until).apply()
    fun clearExhausted() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("ex_") }.forEach { e.remove(it) }
        e.apply()
    }
}
