package com.lagradost

import com.lagradost.cloudstream3.app
import android.util.Log

/**
 * Türk film/dizi sitelerinin domain'leri sürekli değişir.
 * Bu sınıf, bilinen alternatif domainleri dener ve çalışanı bulur.
 * Her provider başlatıldığında otomatik olarak doğru domain'i tespit eder.
 */
object DomainResolver {
    private const val TAG = "DomainResolver"
    private val cache = mutableMapOf<String, String>()

    /**
     * Verilen domain listesinden çalışan ilkini döndürür.
     * @param siteName Cache key olarak kullanılır
     * @param domains Denenecek domain listesi (https:// dahil)
     * @return Çalışan domain URL'si veya ilk domain (fallback)
     */
    suspend fun resolve(siteName: String, domains: List<String>): String {
        // Cache'de varsa direkt dön
        cache[siteName]?.let { return it }

        for (domain in domains) {
            try {
                val response = app.get(
                    domain,
                    timeout = 5,
                    allowRedirects = true,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                )
                if (response.code in 200..399) {
                    // Redirect varsa son URL'yi al
                    val finalUrl = response.url.let { url ->
                        val parsed = url.trimEnd('/')
                        if (parsed.contains("://")) {
                            val scheme = parsed.substringBefore("://")
                            val rest = parsed.substringAfter("://")
                            val host = rest.substringBefore("/")
                            "$scheme://$host"
                        } else domain.trimEnd('/')
                    }
                    cache[siteName] = finalUrl
                    Log.d(TAG, "$siteName resolved to: $finalUrl")
                    return finalUrl
                }
            } catch (e: Exception) {
                Log.d(TAG, "$siteName failed for $domain: ${e.message}")
                continue
            }
        }

        // Hiçbiri çalışmazsa ilkini döndür
        val fallback = domains.first().trimEnd('/')
        cache[siteName] = fallback
        return fallback
    }

    /** Cache'i temizle (domain değişikliğinde yeniden denetim için) */
    fun clearCache() {
        cache.clear()
    }

    fun clearCache(siteName: String) {
        cache.remove(siteName)
    }
}
