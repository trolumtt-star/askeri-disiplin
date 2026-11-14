package com.askeri.disiplin

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

sealed class VerifyResult {
    data class Match(val detail: String) : VerifyResult()
    data class NoMatch(val reason: String) : VerifyResult()
    data class Error(val message: String) : VerifyResult()
}

/**
 * Nesne doğrulama — iki katman:
 *  1) Gemini (ücretsiz model zinciri): referans fotoğrafla karşılaştırır,
 *     ekran/basılı resim fotoğrafı gibi hileleri reddeder.
 *  2) Çevrimdışı yedek bot (ML Kit Image Labeling, ~400 temel eşya etiketi):
 *     internet yoksa veya tüm Gemini kotaları dolduysa çalışır.
 */
class ObjectVerifier(private val ctx: Context) {

    companion object {
        private val TR = Locale.forLanguageTag("tr")

        /** Türkçe nesne adı → ML Kit İngilizce etiketleri */
        val BUILTIN_LABELS: Map<String, List<String>> = mapOf(
            "çaydanlık" to listOf("kettle", "teapot"),
            "demlik" to listOf("teapot", "kettle"),
            "diş fırçası" to listOf("toothbrush"),
            "kupa" to listOf("cup", "mug", "coffee cup"),
            "bardak" to listOf("cup", "glass", "mug"),
            "fincan" to listOf("cup", "coffee cup", "tableware"),
            "buzdolabı" to listOf("refrigerator"),
            "lavabo" to listOf("sink", "tap", "bathroom"),
            "musluk" to listOf("tap", "sink"),
            "ayna" to listOf("mirror"),
            "ayakkabı" to listOf("shoe", "sneakers", "footwear"),
            "seccade" to listOf("rug", "carpet", "textile"),
            "kitap" to listOf("book"),
            "kuran" to listOf("book"),
            "tava" to listOf("frying pan", "cookware", "pan"),
            "tencere" to listOf("cookware", "pot"),
            "ocak" to listOf("stove", "cooktop", "kitchen"),
            "saksı" to listOf("flowerpot", "houseplant", "plant"),
            "saat" to listOf("clock", "watch"),
            "havlu" to listOf("towel")
        )
    }

    suspend fun verify(photo: File): VerifyResult = withContext(Dispatchers.IO) {
        val p = Prefs(ctx)
        val target = p.targetObject.trim()
        var geminiNote = ""

        if (p.apiKey.isNotBlank()) {
            when (val r = GeminiClient(ctx).generate(buildParts(p, target, photo), wantJson = true)) {
                is GeminiResult.Success -> {
                    val json = GeminiClient.parseJson(r.text)
                    if (json != null) {
                        val match = json.optBoolean("eslesme", false)
                        val conf = json.optDouble("guven", 0.0)
                        val seen = json.optString("gorulen", "")
                        val why = json.optString("aciklama", "")
                        return@withContext if (match && conf >= 0.6) {
                            VerifyResult.Match("Doğrulandı (${r.model}): $seen")
                        } else {
                            VerifyResult.NoMatch(listOf(seen, why).filter { it.isNotBlank() }.joinToString(" — "))
                        }
                    }
                    geminiNote = "Gemini yanıtı okunamadı"
                }
                is GeminiResult.Failure -> geminiNote = r.message
            }
        }
        // Yedek: çevrimdışı temel eşya botu
        mlKit(photo, p, target, geminiNote)
    }

    private fun buildParts(p: Prefs, target: String, photo: File): List<GeminiPart> {
        val ref = p.referencePhotoPath?.let { File(it) }?.takeIf { it.exists() }
        val prompt = buildString {
            append("Sen bir alarm uygulamasının hile önleyici doğrulama botusun. ")
            append("Kullanıcı uyandığını kanıtlamak için şu nesnenin fotoğrafını çekmek zorunda: \"$target\".\n")
            if (ref != null) append("İLK görsel kullanıcının önceden çektiği REFERANS fotoğraftır. ")
            append("SON görsel şu an çekilen fotoğraftır.\n")
            append("Kurallar:\n")
            append("1) Son fotoğrafta \"$target\" açıkça ve gerçek olarak görünmeli.\n")
            append("2) Fotoğraf bir telefon/monitör ekranının veya basılı bir resmin fotoğrafıysa REDDET.\n")
            append("3) Çok karanlık, aşırı bulanık ya da nesne seçilemiyorsa REDDET.\n")
            if (ref != null) append("4) Referanstaki ile aynı nesne olmalı (renk/şekil benzer; açı farklı olabilir).\n")
            append("Sadece şu JSON'u döndür: ")
            append("{\"eslesme\": true veya false, \"guven\": 0.0-1.0 arası sayı, ")
            append("\"gorulen\": \"fotoğrafta gördüğün, kısa\", \"aciklama\": \"kısa Türkçe gerekçe\"}")
        }
        val parts = mutableListOf<GeminiPart>(GeminiPart.Text(prompt))
        if (ref != null) parts += GeminiPart.Image(ref, 768)
        parts += GeminiPart.Image(photo, 1024)
        return parts
    }

    private fun mlKit(photo: File, p: Prefs, target: String, geminiNote: String): VerifyResult {
        return try {
            val image = InputImage.fromFilePath(ctx, Uri.fromFile(photo))
            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build()
            )
            val labels = Tasks.await(labeler.process(image))
            labeler.close()

            val keywords = (p.targetLabels.split(",") + (BUILTIN_LABELS[target.lowercase(TR)] ?: emptyList()))
                .map { it.trim().lowercase(Locale.US) }
                .filter { it.isNotEmpty() }
                .distinct()
            if (keywords.isEmpty()) {
                return VerifyResult.Error("Çevrimdışı bot bu nesneyi tanımıyor; ayarlara İngilizce etiket yaz")
            }
            val seen = labels.sortedByDescending { it.confidence }
                .joinToString(", ") { "${it.text} %${(it.confidence * 100).toInt()}" }
            val hit = labels.firstOrNull { l ->
                val t = l.text.lowercase(Locale.US)
                keywords.any { k -> t == k || t.contains(k) }
            }
            val prefix = if (geminiNote.isNotBlank()) "[Çevrimdışı mod — $geminiNote] " else "[Çevrimdışı mod] "
            if (hit != null) VerifyResult.Match("${prefix}Tanındı: ${hit.text}")
            else VerifyResult.NoMatch("${prefix}Görülen: ${seen.ifBlank { "hiçbir şey" }}")
        } catch (e: Exception) {
            VerifyResult.Error("Tanıma hatası: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
