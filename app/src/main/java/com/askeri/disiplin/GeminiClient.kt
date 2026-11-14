package com.askeri.disiplin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone

sealed class GeminiPart {
    data class Text(val text: String) : GeminiPart()
    data class Image(val file: File, val maxDim: Int = 1024) : GeminiPart()
}

sealed class GeminiResult {
    data class Success(val text: String, val model: String) : GeminiResult()
    data class Failure(val message: String) : GeminiResult()
}

/**
 * Gemini REST istemcisi — ÜCRETSİZ MODEL ZİNCİRİ
 *
 * 1) API anahtarınla erişilebilen tüm modeller /v1beta/models ile OTOMATİK keşfedilir.
 * 2) Günlük hakkı en uzun olandan kısaya sıralanır:
 *      Flash-Lite  →  Flash  →  Pro  →  Gemma (son çare)
 *    (aynı ailede: kararlı sürüm > preview, yeni sürüm > eski)
 * 3) Bir model 429 (kota) verirse:
 *      - günlük kota bittiyse → Pasifik gece yarısına kadar (Google'ın sıfırlama saati) atlanır
 *      - dakikalık limitse    → 65 sn atlanır
 *    ve hemen sıradaki model denenir.
 * 4) Ücretli olup ücretsiz kotası "0" olan modeller de 429 alır ve otomatik elenir.
 *
 * Google artık ücretsiz kota rakamlarını sabit yayınlamıyor; bu yüzden liste sabit kodlanmadı,
 * her gün yeniden keşfedilir. İstersen ayarlardan kendi sıranı da yazabilirsin.
 */
class GeminiClient(context: Context) {
    private val p = Prefs(context)

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val DAY = 24L * 60 * 60 * 1000
        private const val MAX_ATTEMPTS = 10

        /** Keşif başarısız olursa kullanılacak yedek sıra (olmayan model 404 alıp atlanır). */
        val DEFAULT_MODELS = listOf(
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash-lite",
            "gemini-3-flash-preview",
            "gemini-2.5-flash",
            "gemini-2.0-flash-lite",
            "gemini-2.0-flash",
            "gemini-2.5-pro",
            "gemma-3-27b-it",
            "gemma-3-12b-it"
        )

        /** Görsel anlamayan / metin üretmeyen modelleri ele */
        private val EXCLUDE = listOf(
            "embedding", "tts", "image-generation", "-image", "live", "audio", "native",
            "computer-use", "robotics", "aqa", "veo", "imagen", "lyria", "learnlm",
            "gemma-3n", "-1b"
        )

        fun modelScore(name: String): Double {
            val n = name.lowercase()
            val family = when {
                n.startsWith("gemma") -> 0.0
                n.contains("flash-lite") -> 3000.0
                n.contains("flash") -> 2000.0
                n.contains("pro") -> 1000.0
                else -> 500.0
            }
            val ver = Regex("""\d+(?:\.\d+)?""")
                .find(n.removePrefix("gemini-").removePrefix("gemma-"))
                ?.value?.toDoubleOrNull() ?: 0.0
            val stable = if (n.contains("preview") || n.contains("exp")) 0.0 else 50.0
            val alias = if (n.contains("latest")) -5.0 else 0.0
            return family + stable + ver * 10 + alias
        }

        /** Yanıttan JSON nesnesini güvenli şekilde çıkar (``` blokları vs. temizlenir) */
        fun parseJson(text: String): JSONObject? {
            val s = text.indexOf('{')
            val e = text.lastIndexOf('}')
            if (s < 0 || e <= s) return null
            return try { JSONObject(text.substring(s, e + 1)) } catch (_: Exception) { null }
        }

        fun nextPacificMidnight(): Long {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 1)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }

    /** Ağ çağrısı yapar — sadece arka plan iş parçacığında çağır. */
    fun candidateModels(forceRefresh: Boolean = false): List<String> {
        val custom = p.customModels.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (custom.isNotEmpty()) return custom
        if (!forceRefresh && p.modelCacheDate == todayKey() && p.modelCacheList.isNotBlank()) {
            return p.modelCacheList.split(",")
        }
        val discovered = try { discover() } catch (_: Exception) { emptyList() }
        if (discovered.isNotEmpty()) {
            p.modelCacheDate = todayKey()
            p.modelCacheList = discovered.joinToString(",")
            return discovered
        }
        return DEFAULT_MODELS
    }

    private fun discover(): List<String> {
        val key = p.apiKey.trim()
        if (key.isEmpty()) return emptyList()
        val (code, body) = http("GET", "$BASE/models?pageSize=1000", key, null)
        if (code != 200) return emptyList()
        val arr = JSONObject(body).optJSONArray("models") ?: return emptyList()
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
            var canGenerate = false
            for (j in 0 until methods.length()) if (methods.optString(j) == "generateContent") canGenerate = true
            if (!canGenerate) continue
            val name = m.optString("name").removePrefix("models/")
            val lower = name.lowercase()
            if (!(lower.startsWith("gemini") || lower.startsWith("gemma"))) continue
            if (EXCLUDE.any { lower.contains(it) }) continue
            names += name
        }
        return names.distinct().sortedByDescending { modelScore(it) }
    }

    /** Model zincirini sırayla dener; ilk başarılı yanıtı döndürür. Arka planda çağır. */
    fun generate(parts: List<GeminiPart>, wantJson: Boolean = true): GeminiResult {
        val key = p.apiKey.trim()
        if (key.isEmpty()) return GeminiResult.Failure("Gemini API anahtarı girilmemiş")

        val partsJson = JSONArray()
        for (part in parts) when (part) {
            is GeminiPart.Text -> partsJson.put(JSONObject().put("text", part.text))
            is GeminiPart.Image -> {
                val b64 = ImageUtil.toBase64Jpeg(part.file, part.maxDim)
                    ?: return GeminiResult.Failure("Görsel okunamadı: ${part.file.name}")
                partsJson.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                    )
                )
            }
        }

        val errors = mutableListOf<String>()
        var attempts = 0
        val now = System.currentTimeMillis()

        for (model in candidateModels()) {
            if (p.exhaustedUntil(model) > now) continue
            if (attempts++ >= MAX_ATTEMPTS) break

            // Gemma modelleri JSON modu desteklemez → serbest metinden JSON ayıklanır
            val json = wantJson && !model.lowercase().startsWith("gemma")
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", partsJson)))
                .put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                    if (json) put("responseMimeType", "application/json")
                })
                .toString()

            val (code, resp) = try {
                http("POST", "$BASE/models/$model:generateContent", key, body)
            } catch (e: Exception) {
                errors += "$model: bağlantı (${e.javaClass.simpleName})"
                continue
            }

            when {
                code == 200 -> {
                    val text = extractText(resp)
                    if (!text.isNullOrBlank()) {
                        p.lastModelUsed = model
                        return GeminiResult.Success(text, model)
                    }
                    errors += "$model: boş yanıt"
                }
                code == 429 -> {
                    val daily = resp.contains("PerDay", ignoreCase = true) || resp.contains("limit: 0")
                    p.markExhausted(model, if (daily) nextPacificMidnight() else now + 65_000)
                    errors += "$model: kota doldu"
                }
                resp.contains("API_KEY_INVALID") || code == 401 ->
                    return GeminiResult.Failure("API anahtarı geçersiz")
                code == 404 -> {
                    p.markExhausted(model, now + 3 * DAY)
                    errors += "$model: yok"
                }
                code == 400 || code == 403 -> {
                    p.markExhausted(model, now + DAY)
                    errors += "$model: desteklenmiyor ($code)"
                }
                else -> errors += "$model: HTTP $code"
            }
        }
        return GeminiResult.Failure(
            if (errors.isEmpty()) "Bugün tüm ücretsiz modellerin kotası dolmuş"
            else errors.takeLast(4).joinToString("; ")
        )
    }

    private fun extractText(resp: String): String? = try {
        val cands = JSONObject(resp).optJSONArray("candidates")
        if (cands == null || cands.length() == 0) null
        else {
            val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            if (parts == null) null else {
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val pt = parts.getJSONObject(i)
                    if (pt.optBoolean("thought")) continue
                    sb.append(pt.optString("text"))
                }
                sb.toString()
            }
        }
    } catch (_: Exception) { null }

    private fun http(method: String, url: String, key: String, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("x-goog-api-key", key)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }
}
