package com.askeri.disiplin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recipe(
    val title: String,
    val ingredients: List<String>,
    val steps: List<String>,
    val source: String
) {
    fun toJson(): String = JSONObject()
        .put("ogun", title)
        .put("malzemeler", JSONArray(ingredients))
        .put("adimlar", JSONArray(steps))
        .put("kaynak", source)
        .toString()

    fun toDisplay(): String = buildString {
        append("🍽 $title\n($source)\n\nMALZEMELER\n")
        ingredients.forEach { append("• $it\n") }
        append("\nADIMLAR\n")
        steps.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
    }

    companion object {
        fun fromJson(json: JSONObject, source: String): Recipe? {
            val steps = json.optJSONArray("adimlar") ?: return null
            if (steps.length() == 0) return null
            val ing = json.optJSONArray("malzemeler") ?: JSONArray()
            return Recipe(
                title = json.optString("ogun", "Bugünün öğünü"),
                ingredients = (0 until ing.length()).map { ing.optString(it) }.filter { it.isNotBlank() },
                steps = (0 until steps.length()).map { steps.optString(it) }.filter { it.isNotBlank() },
                source = json.optString("kaynak", source)
            )
        }
    }
}

/**
 * Yemek programı GÖRSELİNİ (fotoğraf/ekran görüntüsü) Gemini ile okur,
 * bugünün gününü bulur ve o öğünün adım adım tarifini üretir.
 * Görsel yoksa / internet yoksa → yerleşik tavuk parmak bonfile sandviç tarifi.
 * Tarif güne göre önbelleğe alınır (her sabah 1 kez Gemini çağrısı).
 */
object RecipeProvider {

    val DEFAULT = Recipe(
        title = "Tavuk Parmak Bonfile Sandviç (Pratik)",
        ingredients = listOf(
            "150–200 g tavuk parmak bonfile",
            "1 adet tam buğday sandviç ekmeği veya lavaş",
            "1 tatlı kaşığı zeytinyağı",
            "Yarım limon suyu",
            "Tuz, karabiber, kekik, pul biber, toz kırmızı biber, sarımsak tozu",
            "1 yemek kaşığı yoğurt + 1 çay kaşığı hardal (sos)",
            "2–3 yaprak marul, 3–4 dilim domates, 1 turşu / salatalık"
        ),
        steps = listOf(
            "Tavayı orta-yüksek ateşte ısıtmaya koy. Bonfileleri kağıt havluyla kurula.",
            "Bonfileleri bir kapta zeytinyağı, limon, tuz, karabiber, kekik, pul biber ve sarımsak tozuyla harmanla.",
            "Bonfileleri tavaya tek sıra diz. Her yüzünü 3–4 dk pişir; içi tamamen beyazlaşıp suyu berrak akmalı.",
            "Pişen tavuğu tahtaya al, 1 dk dinlendir. Bu sürede marulu yıka, domates ve turşuyu dilimle.",
            "Sosu hazırla: yoğurt + hardal + bir tutam tuz, karıştır.",
            "Ekmeği aynı tavada her yüzü 30 sn ısıt.",
            "Ekmeğe sosu sür; marul, domates, tavuk ve turşuyu diz.",
            "Sandviçi kapat, ikiye kes. Tezgâhı 2 dakikada topla — asker dağınık bırakmaz. Afiyet olsun."
        ),
        source = "Yerleşik tarif"
    )

    private val TR = Locale.forLanguageTag("tr")

    suspend fun getTodayRecipe(ctx: Context, forceRefresh: Boolean = false): Recipe =
        withContext(Dispatchers.IO) {
            val p = Prefs(ctx)
            val today = todayKey()

            if (!forceRefresh && p.recipeCacheDate == today && p.recipeCacheJson.isNotBlank()) {
                GeminiClient.parseJson(p.recipeCacheJson)?.let { Recipe.fromJson(it, "Önbellek") }
                    ?.let { return@withContext it }
            }

            val plan = p.mealPlanPath?.let { File(it) }?.takeIf { it.exists() }
            if (plan == null || p.apiKey.isBlank()) return@withContext DEFAULT

            val dayName = SimpleDateFormat("EEEE", TR).format(Date())
            val dateStr = SimpleDateFormat("d MMMM yyyy", TR).format(Date())
            val prompt = """
                Bu görsel kullanıcının yemek programıdır (haftalık veya günlük tablo olabilir).
                Bugün: $dayName, $dateStr.
                1) Programda BUGÜNE ait sabah/kahvaltı öğününü bul (gün adı yoksa programdaki ilk öğünü al).
                2) Öğün tavuk / parmak bonfile sandviç ise onun pratik hazırlama tarifini ver.
                   Farklı bir öğünse programda yazan öğünün pratik tarifini ver.
                3) Programdaki gramaj ve miktarları aynen kullan.
                4) Adımlar 5–10 arası olsun, her adım tek bir iş içersin, kısa ve emir kipinde.
                Sadece şu JSON'u döndür:
                {"ogun": "öğünün adı", "gun": "$dayName", "malzemeler": ["..."], "adimlar": ["..."]}
            """.trimIndent()

            val result = GeminiClient(ctx).generate(
                listOf(GeminiPart.Text(prompt), GeminiPart.Image(plan, 1600)),
                wantJson = true
            )
            val recipe = when (result) {
                is GeminiResult.Success -> GeminiClient.parseJson(result.text)
                    ?.let { Recipe.fromJson(it, "Yemek programından · ${result.model}") }
                is GeminiResult.Failure -> null
            }
            if (recipe != null) {
                p.recipeCacheDate = today
                p.recipeCacheJson = recipe.toJson()
                recipe
            } else {
                DEFAULT.copy(source = "Yerleşik tarif (program okunamadı: " +
                    ((result as? GeminiResult.Failure)?.message ?: "biçim hatası") + ")")
            }
        }
}
