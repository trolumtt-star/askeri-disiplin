# Askeri Disiplin — Kurulum Rehberi

Kaçışsız alarm + sabah rutini (gusül, namaz, öğün) + antrenman kilidi. Kotlin, minSdk 26 (Android 8), targetSdk 35.

## 1. Android Studio'da açma

**Yol A (en kolay):** Android Studio → *Open* → bu `AskeriDisiplin` klasörünü seç. Gradle senkronize olsun.
Gradle wrapper sorulursa "Gradle 8.9" seçin ya da Studio'nun önerdiği sürümü kabul edin.

**Yol B:** *New Project → Empty Views Activity*, paket adı `com.askeri.disiplin`, dil Kotlin, Minimum SDK 26.
Sonra bu zip'teki `app/src/main` içeriğini ve `app/build.gradle.kts` bağımlılıklarını projeye kopyalayın.

## 2. Dosyalar

| Dosya | Görevi |
|---|---|
| `MainActivity.kt` | Kurulum ekranı: alarm/antrenman saati, nesne + referans foto, yemek programı görseli, API anahtarı, izinler |
| `AlarmScheduler.kt` | `setAlarmClock` ile tam zamanlı alarm, antrenman alarmı |
| `AlarmReceiver.kt` | Alarm tetiklenince servisi başlatır; telefon yeniden açılınca alarmları kurar |
| `AlarmService.kt` | Maksimum ses (kısılırsa 0.5 sn'de geri), titreşim, alarm ekranını sürekli öne getirme |
| `AlarmActivity.kt` | Görev 1: kamera ile nesne · Görev 2: 3 matematik sorusu. Geri/ses tuşları kapalı |
| `ObjectVerifier.kt` | Gemini ile doğrulama (+ ekran/fotoğraf hilesi reddi), yoksa çevrimdışı ML Kit botu |
| `GeminiClient.kt` | Ücretsiz model zinciri: otomatik keşif → Flash-Lite → Flash → Pro → Gemma |
| `MathEngine.kt` | Orta-zor soru üretici (47 × 3 − 15 tipi, 6 farklı kalıp) |
| `RoutineActivity.kt` | Kilitli zincir: Gusül (5 dk) → Namaz → Tarif adımları |
| `RecipeProvider.kt` | Yemek programı görselinden bugünün öğününü okuyup tarif üretir; yedek: tavuk bonfile sandviç |
| `WorkoutOverlayService.kt` | 15 dk karın seti tam ekran kilit, 2 dk basılı tutmalı acil çıkış |
| `ExerciseAnimView.kt` | Hareket animasyonları (kodla çizilen figür, dosya gerekmez) |

## 3. İlk çalıştırmada yapılacaklar (sırayla)

1. **İzinler** kartındaki tüm butonlara bas, hepsi ✔ olsun. *Overlay* izni olmazsa alarm ekranı öne gelemez, antrenman kilidi çalışmaz.
2. **Gemini anahtarı:** https://aistudio.google.com/apikey → *Create API key* → yapıştır → *Model sırasını göster*.
3. **Nesne:** adını yaz (ör. `çaydanlık`), mutfakta *Referans fotoğraf çek*.
4. **Yemek programı:** programının fotoğrafını/ekran görüntüsünü seç → *Bugünün tarifi* ile test et.
5. Saatleri seç → **KAYDET VE KUR** → **1 dk sonra test** ile dene.

## 4. Gemini model zinciri nasıl çalışır?

- Google ücretsiz kota rakamlarını artık sabit yayınlamıyor ve sık değiştiriyor. Bu yüzden liste koda gömülmedi:
  uygulama her gün anahtarınla erişebildiğin modelleri `/v1beta/models` ile **kendisi bulur**.
- Sıra: **Flash-Lite** (günlük hakkı en geniş) → **Flash** → **Pro** → **Gemma** (son çare).
- 429 (kota) gelince model Google'ın sıfırlama saatine (Pasifik gece yarısı ≈ TR 10:00/11:00) kadar atlanır, sıradaki denenir.
- Hepsi biterse veya internet yoksa **çevrimdışı bot** (ML Kit, ~400 temel eşya) devreye girer.
  Bunun için "İngilizce etiket" alanını doldur (çaydanlık → `kettle, teapot`; diş fırçası → `toothbrush`).

## 5. Bilinmesi gereken sınırlar (Android kuralları)

- **Telefonu kapatıp açmak** alarmı susturmaz (açılışta tekrar çalar). Ama *Ayarlar → Uygulamalar → Durmaya zorla* veya uygulamayı silmek Android'de engellenemez.
- **Overlay kilidi** tüm uygulamaların üstünde kalır, ancak bildirim paneli ve güç tuşu sistem tarafından korunur; tam kilit ancak "cihaz sahibi (kiosk)" modunda mümkündür.
- Kamera izni reddedilirse veya doğrulama 5 kez teknik hata verirse kullanıcı sonsuza dek kilitli kalmasın diye **10 soruluk ceza matematiği** devreye girer.
- Namaz adımında anında geçişi önlemek için 4 dk asgari süre var (`RoutineActivity.NAMAZ_MIN_SECONDS`), istersen değiştir.
- Xiaomi/Huawei/Oppo gibi cihazlarda ayrıca **Otomatik başlatma** ve **Kilit ekranında göster** izinlerini elle açın.
