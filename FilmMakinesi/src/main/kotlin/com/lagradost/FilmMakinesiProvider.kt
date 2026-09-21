package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class FilmMakinesiProvider : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private val DOMAINS = listOf(
            "https://filmmakinesi.to",
            "https://filmmakinesi.de",
            "https://filmmakinesi.com",
            "https://filmmakinesi.net",
            "https://filmmakinesi.org",
            "https://filmmakinesi.film",
        )
    }

    private suspend fun getBaseUrl(): String {
        val resolved = DomainResolver.resolve("filmmakinesi", DOMAINS)
        mainUrl = resolved
        return resolved
    }

    override val mainPage = mainPageOf(
        "filmler/page/" to "Son Eklenen Filmler",
        "imdb-7-puan-uzeri/page/" to "IMDB 7+",
        "en-cok-izlenen-filmler/page/" to "En Çok İzlenen",
        "tur/aksiyon/page/" to "Aksiyon",
        "tur/bilim-kurgu/page/" to "Bilim Kurgu",
        "tur/korku/page/" to "Korku",
        "tur/komedi/page/" to "Komedi",
        "tur/animasyon/page/" to "Animasyon",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBaseUrl()
        val url = "$base/${request.data}$page"
        val doc = app.get(url).document

        val home = doc.select("article.movie-box, div.movie-box, .film-list .film-item, .movie-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBaseUrl()
        val doc = app.get("$base/?s=$query").document
        return doc.select("article.movie-box, div.movie-box, .film-list .film-item, .movie-item, .search-result .item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a[href*='/film/'], a[href*='-izle'], a:has(img)") ?: return null
        val title = aTag.attr("title").ifEmpty {
            this.selectFirst("h2, h3, .movie-title, .film-title, strong")?.text()?.trim() ?: return null
        }
        val href = aTag.attr("href")
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("data-lazy-src").ifEmpty { img.attr("src") } }
        }
        val year = this.selectFirst(".year, .movie-year, span:contains(20)")?.text()?.trim()
            ?.replace(Regex("[^0-9]"), "")?.take(4)?.toIntOrNull()
        val quality = this.selectFirst(".quality, .kalite")?.text()?.let {
            when {
                it.contains("4K", true) -> SearchQuality.UHD
                it.contains("1080", true) || it.contains("HD", true) -> SearchQuality.HD
                it.contains("720") -> SearchQuality.HDR
                else -> null
            }
        }

        return newMovieSearchResponse(title.replace(" izle", "").trim(), href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = quality
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .movie-title, .film-title")?.text()?.replace(" izle", "")?.trim() ?: ""
        val poster = doc.selectFirst(".movie-poster img, .film-poster img, article img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val description = doc.selectFirst(".movie-description, .film-aciklama, .ozet, [itemprop=description], .synopsis p")?.text()?.trim()
        val year = doc.selectFirst(".year, span:contains(20), .info span")?.text()?.replace(Regex("[^0-9]"), "")?.take(4)?.toIntOrNull()
        val rating = doc.selectFirst(".imdb, .rating, .puan")?.text()?.replace(Regex("[^0-9.]"), "")
            ?.toDoubleOrNull()?.let { (it * 10).toInt() }
        val duration = doc.selectFirst(".duration, .sure, span:contains(dk)")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        val tags = doc.select(".genre a, .tur a, .categories a, a[href*='/tur/']").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.duration = duration
            this.tags = tags.ifEmpty { null }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // iframe kaynakları
        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { iframe ->
            val src = iframe.attr("data-lazy-src").ifEmpty { iframe.attr("data-src").ifEmpty { iframe.attr("src") } }
            if (src.startsWith("http")) loadExtractor(src, mainUrl, subtitleCallback, callback)
        }

        // Alternatif player tabları
        doc.select(".player-tab, .tab-content, [data-embed], [data-src]").forEach { tab ->
            val embedUrl = tab.attr("data-embed").ifEmpty { tab.attr("data-src") }
            if (embedUrl.startsWith("http")) loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
        }

        return true
    }
}
