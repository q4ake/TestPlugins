package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class UltraFilmIzleProvider : MainAPI() {
    override var mainUrl = "https://ultrafilmizle.org"
    override var name = "UltraFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private val DOMAINS = listOf(
            "https://ultrafilmizle.org",
            "https://ultrafilmizle.com",
            "https://ultrafilmizle.net",
            "https://ultrafilmizle.de",
            "https://ultrafilmizle.live",
            "https://ultrafilmizle.online",
        )
    }

    private suspend fun getBaseUrl(): String {
        val resolved = DomainResolver.resolve("ultrafilmizle", DOMAINS)
        mainUrl = resolved
        return resolved
    }

    override val mainPage = mainPageOf(
        "page/" to "Son Eklenenler",
        "tur/aksiyon/page/" to "Aksiyon",
        "tur/bilim-kurgu/page/" to "Bilim Kurgu",
        "tur/macera/page/" to "Macera",
        "tur/animasyon/page/" to "Animasyon",
        "tur/komedi/page/" to "Komedi",
        "tur/korku/page/" to "Korku",
        "tur/romantik/page/" to "Romantik",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBaseUrl()
        val url = "$base/${request.data}$page"
        val doc = app.get(url).document

        val home = doc.select(".movie-box, .film-box, .poster-box, article.item, .movies .movie").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBaseUrl()
        val doc = app.get("$base/?s=$query").document
        return doc.select(".movie-box, .film-box, .poster-box, article.item, .movies .movie, .search-result .item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a[href]") ?: return null
        val title = aTag.attr("title").ifEmpty {
            this.selectFirst("h2, h3, h4, .title, .movie-title")?.text()?.trim() ?: aTag.text().trim()
        }.replace(" izle", "").trim()
        if (title.isEmpty()) return null
        val href = aTag.attr("href")
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("data-lazy-src").ifEmpty { img.attr("src") } }
        }
        val year = this.selectFirst(".year, .yil, span:matches(^20\\d{2}$)")?.text()?.trim()?.toIntOrNull()
        val quality = this.selectFirst(".quality, .kalite")?.text()?.let {
            when {
                it.contains("4K", true) -> SearchQuality.UHD
                it.contains("1080", true) || it.contains("HD", true) -> SearchQuality.HD
                else -> null
            }
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = quality
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .movie-title, .film-name")?.text()?.replace(" izle", "")?.trim() ?: ""
        val poster = doc.selectFirst(".movie-poster img, .film-poster img, .poster img, .detail img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val description = doc.selectFirst(".movie-story, .film-aciklama, .ozet, [itemprop=description], .description, .storyline")?.text()?.trim()
        val year = doc.selectFirst(".year, .yil, .info:contains(20)")?.text()?.replace(Regex("[^0-9]"), "")?.take(4)?.toIntOrNull()
        val rating = doc.selectFirst(".imdb, .rating, .puan, [class*=imdb]")?.text()?.replace(Regex("[^0-9.]"), "")
            ?.toDoubleOrNull()?.let { (it * 10).toInt() }
        val duration = doc.selectFirst(".duration, .sure, span:contains(dk), span:contains(min)")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        val tags = doc.select(".genre a, .tur a, a[href*='/tur/'], a[href*='/genre/']").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.rating = rating
            this.duration = duration
            this.tags = tags.ifEmpty { null }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { iframe ->
            val src = iframe.attr("data-lazy-src").ifEmpty { iframe.attr("data-src").ifEmpty { iframe.attr("src") } }
            if (src.startsWith("http")) loadExtractor(src, mainUrl, subtitleCallback, callback)
        }

        // Alternatif player sekmeleri
        doc.select("[data-embed], [data-player], .player-tab[data-src]").forEach { tab ->
            val embedUrl = tab.attr("data-embed").ifEmpty { tab.attr("data-player").ifEmpty { tab.attr("data-src") } }
            if (embedUrl.startsWith("http")) loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
        }

        // Video source etiketleri
        doc.select("video source[src]").forEach { source ->
            val src = source.attr("src")
            if (src.startsWith("http")) {
                callback.invoke(
                    ExtractorLink(name, name, src, mainUrl, Qualities.Unknown.value, src.contains(".m3u8"))
                )
            }
        }

        return true
    }
}
