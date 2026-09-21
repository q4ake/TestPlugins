# 🎬 Cloudstream Türkçe Extensions Repository

Cloudstream 3 için Türkçe film ve dizi eklentileri. Tüm siteler **otomatik domain çözümleme** destekler — site domain'i değişince eklenti kendisi çalışan adresi bulur.

## 📦 Eklentiler

| Eklenti | Tür | İçerik | Domain Sayısı |
|---------|-----|--------|---------------|
| **FullHDFilmizlesene** | Film | Yeni filmler, dublaj, altyazılı | 6 |
| **HDFilmCehennemi** | Film + Dizi | Vizyon, Netflix, kategoriler | 8 |
| **FilmMakinesi** | Film | IMDB 7+, türlere göre | 6 |
| **UltraFilmIzle** | Film | Aksiyon, korku, bilim kurgu | 6 |
| **DiziWatch** | Dizi + Anime | Yerli, yabancı, Kore, anime | 8 |
| **Dizilla** | Dizi | Yabancı diziler, türler | 8 |
| **SineFilTR** | Film + Dizi | Genel film ve dizi arşivi | 6 |

## 🔄 Otomatik Domain Çözümleme

Her provider birden fazla bilinen domain'i dener ve çalışanı otomatik kullanır. Site adresi değiştiğinde eklentiyi güncellemeye gerek yoktur — yeni domain listeye eklendiğinde otomatik çalışır.

```kotlin
// DomainResolver.kt — tüm providerlar tarafından kullanılır
DomainResolver.resolve("hdfilmcehennemi", listOf(
    "https://www.hdfilmcehennemi.nl",
    "https://www.hdfilmcehennemi.com",
    "https://www.hdfilmcehennemi.de",
    // ... yeni domain'ler buraya eklenir
))
```

## 📲 Kurulum

1. Cloudstream uygulamasını açın
2. **Settings → Extensions → Add Repository** 
3. URL ekleyin:
   ```
   https://KULLANICI_ADINIZ.github.io/cloudstream-repo/
   ```
4. Eklentileri yükleyin

## 🛠️ Geliştirme

```bash
# Tümünü derle
./gradlew make

# Tek eklenti derle
./gradlew HDFilmCehennemi:make
```

### Yeni Site Eklemek

1. Yeni klasör oluştur (ör: `YeniSite/`)
2. `build.gradle.kts` + `src/main/kotlin/com/lagradost/` yapısını kur
3. `Plugin.kt` ve `Provider.kt` yaz
4. `DomainResolver` ile birden fazla domain ekle
5. Push et — GitHub Actions otomatik derler

## 📁 Proje Yapısı

```
cloudstream-repo/
├── .github/workflows/build.yml
├── build.gradle.kts
├── settings.gradle.kts
├── FullHDFilmizlesene/
│   └── src/main/kotlin/com/lagradost/
│       ├── DomainResolver.kt          ← Paylaşılan domain çözümleyici
│       ├── FullHDFilmizlesenePlugin.kt
│       └── FullHDFilmizleseneProvider.kt
├── HDFilmCehennemi/
├── FilmMakinesi/
├── UltraFilmIzle/
├── DiziWatch/
├── Dizilla/
└── SineFilTR/
```

## Lisans
MIT
