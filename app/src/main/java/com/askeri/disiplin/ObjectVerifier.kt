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

        /** Türkçe nesne adı → ML Kit etiketleri (kullanıcının İngilizce yazmasına gerek yok) */
        val BUILTIN_LABELS: Map<String, List<String>> = mapOf(
            "çaydanlık" to listOf("kettle", "teapot", "tableware", "kitchen", "pot", "cookware"),
            "demlik" to listOf("teapot", "kettle", "tableware", "pot"),
            "su ısıtıcı" to listOf("kettle", "kitchen", "appliance"),
            "kettle" to listOf("kettle", "kitchen", "appliance"),
            "diş fırçası" to listOf("toothbrush", "bathroom", "sink"),
            "diş macunu" to listOf("toothbrush", "bathroom", "cosmetics"),
            "kupa" to listOf("cup", "mug", "coffee", "tableware"),
            "bardak" to listOf("cup", "glass", "mug", "tableware", "drinkware"),
            "fincan" to listOf("cup", "coffee", "tableware"),
            "tabak" to listOf("plate", "tableware", "dishware"),
            "kaşık" to listOf("spoon", "cutlery", "tableware"),
            "çatal" to listOf("fork", "cutlery", "tableware"),
            "bıçak" to listOf("knife", "cutlery"),
            "buzdolabı" to listOf("refrigerator", "kitchen", "appliance"),
            "fırın" to listOf("oven", "kitchen", "appliance"),
            "ocak" to listOf("stove", "cooktop", "oven", "kitchen"),
            "mikrodalga" to listOf("microwave", "kitchen", "appliance"),
            "çamaşır makinesi" to listOf("washing machine", "appliance"),
            "tava" to listOf("frying pan", "cookware", "pan", "kitchen"),
            "tencere" to listOf("cookware", "pot", "kitchen"),
            "lavabo" to listOf("sink", "tap", "bathroom"),
            "musluk" to listOf("tap", "sink", "bathroom"),
            "klozet" to listOf("toilet", "bathroom"),
            "tuvalet" to listOf("toilet", "bathroom"),
            "duş" to listOf("shower", "bathroom", "tap"),
            "banyo" to listOf("bathroom", "sink", "tap"),
            "ayna" to listOf("mirror", "bathroom"),
            "havlu" to listOf("towel", "textile", "bathroom"),
            "sabun" to listOf("soap", "bathroom"),
            "ayakkabı" to listOf("shoe", "sneakers", "footwear"),
            "terlik" to listOf("shoe", "footwear", "sandal"),
            "seccade" to listOf("rug", "carpet", "textile", "flooring"),
            "halı" to listOf("rug", "carpet", "flooring"),
            "kitap" to listOf("book", "paper"),
            "kuran" to listOf("book", "paper"),
            "tespih" to listOf("bead", "jewelry", "necklace"),
            "saksı" to listOf("flowerpot", "houseplant", "plant", "flower"),
            "çiçek" to listOf("flower", "plant", "houseplant", "flowerpot"),
            "saat" to listOf("clock", "watch"),
            "kapı" to listOf("door", "handle"),
            "pencere" to listOf("window", "curtain"),
            "perde" to listOf("curtain", "window", "textile"),
            "sandalye" to listOf("chair", "furniture"),
            "masa" to listOf("table", "desk", "furniture"),
            "koltuk" to listOf("couch", "sofa", "furniture", "chair"),
            "kanepe" to listOf("couch", "sofa", "furniture"),
            "televizyon" to listOf("television", "monitor", "screen"),
            "tv" to listOf("television", "monitor", "screen"),
            "bilgisayar" to listOf("computer", "laptop", "monitor", "keyboard"),
            "laptop" to listOf("laptop", "computer", "keyboard"),
            "klavye" to listOf("keyboard", "computer"),
            "lamba" to listOf("lamp", "light", "lighting"),
            "şişe" to listOf("bottle", "drinkware"),
            "su şişesi" to listOf("bottle", "drinkware"),
            "dambıl" to listOf("dumbbell", "sports equipment", "weight"),
            "ekmek" to listOf("bread", "food", "baked goods"),
            "yumurta" to listOf("egg", "food"),
            "meyve" to listOf("fruit", "food"),
            "elma" to listOf("apple", "fruit", "food"),
            "muz" to listOf("banana", "fruit", "food"),
            "çanta" to listOf("bag", "handbag", "backpack"),
            "anahtar" to listOf("key", "metal"),
            "gözlük" to listOf("glasses", "sunglasses"),
            "kedi" to listOf("cat", "pet"),
            "köpek" to listOf("dog", "pet")
        )

        /** Her yerde çıkabilen, tek başına kanıt sayılmayan genel etiketler */
        private val GENERIC = setOf(
            "room", "interior design", "wall", "floor", "flooring", "ceiling", "light", "lighting",
            "font", "pattern", "material", "metal", "wood", "plastic", "textile", "paper",
            "darkness", "black", "white", "night", "sky", "shelf", "hand", "finger", "selfie"
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

    private fun labelsOf(file: File): List<Pair<String, Float>> {
        val image = InputImage.fromFilePath(ctx, Uri.fromFile(file))
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.45f).build()
        )
        try {
            return Tasks.await(labeler.process(image))
                .map { it.text.lowercase(Locale.US) to it.confidence }
                .sortedByDescending { it.second }
        } finally {
            labeler.close()
        }
    }

    /** Türkçe nesne adını sözlükte bul ("mutfaktaki çaydanlık" → "çaydanlık") */
    private fun keywordsFor(target: String, extra: String): List<String> {
        val t = target.lowercase(TR).trim()
        val fromDict = BUILTIN_LABELS[t]
            ?: BUILTIN_LABELS.entries.filter { t.contains(it.key) || it.key.contains(t) }.flatMap { it.value }
        return (extra.split(",").map { it.trim().lowercase(Locale.US) } + fromDict)
            .filter { it.isNotEmpty() }.distinct()
    }

    /**
     * Çevrimdışı bot — İngilizce yazmaya gerek yok:
     *  A) Türkçe sözlükten nesnenin etiketlerini bulur ve fotoğrafta arar.
     *  B) Referans fotoğraf varsa ikisinin etiketlerini karşılaştırır (her nesnede çalışır).
     */
    private fun mlKit(photo: File, p: Prefs, target: String, geminiNote: String): VerifyResult {
        return try {
            val shot = labelsOf(photo)
            val seen = shot.joinToString(", ") { "${it.first} %${(it.second * 100).toInt()}" }
            val prefix = if (geminiNote.isNotBlank()) "[Çevrimdışı mod — $geminiNote] " else "[Çevrimdışı mod] "

            // A) Sözlük eşleşmesi
            val keywords = keywordsFor(target, p.targetLabels)
            val hit = shot.firstOrNull { (label, _) ->
                keywords.any { k -> label == k || label.contains(k) || k.contains(label) } && label !in GENERIC
            }
            if (hit != null) return VerifyResult.Match("${prefix}Tanındı: ${hit.first}")

            // B) Referans fotoğrafla karşılaştırma
            val ref = p.referencePhotoPath?.let { File(it) }?.takeIf { it.exists() }
            if (ref != null) {
                val refLabels = labelsOf(ref).map { it.first }.toSet()
                val common = shot.map { it.first }.filter { it in refLabels }
                val specific = common.filter { it !in GENERIC }
                if (specific.isNotEmpty() || common.size >= 3) {
                    return VerifyResult.Match("${prefix}Referansla eşleşti: ${common.joinToString(", ")}")
                }
                return VerifyResult.NoMatch("${prefix}Referans fotoğrafa benzemiyor. Görülen: ${seen.ifBlank { "hiçbir şey" }}")
            }

            if (keywords.isEmpty()) {
                VerifyResult.NoMatch("${prefix}Bu nesneyi tanımıyorum. Ayarlardan bir REFERANS FOTOĞRAF çek, onunla karşılaştırırım.")
            } else {
                VerifyResult.NoMatch("${prefix}Görülen: ${seen.ifBlank { "hiçbir şey" }}")
            }
        } catch (e: Exception) {
            VerifyResult.Error("Tanıma hatası: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
