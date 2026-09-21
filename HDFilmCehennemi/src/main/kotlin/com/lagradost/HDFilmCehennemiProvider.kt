package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class HDFilmCehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private val DOMAINS = listOf(
            "https://www.hdfilmcehennemi.nl",
            "https://www.hdfilmcehennemi.com",
            "https://www.hdfilmcehennemi.de",
            "https://www.hdfilmcehennemi.net",
            "https://www.hdfilmcehennemi.org",
            "https://www.hdfilmcehennemi.live",
            "https://www.hdfilmcehennemi.pw",
            "https://www.hdfilmcehennemi.cx",
        )
    }

    private suspend fun getBaseUrl(): String {
        val resolved = DomainResolver.resolve("hdfilmcehennemi", DOMAINS)
        mainUrl = resolved
        return resolved
    }

    override val mainPage = mainPageOf(
        "category/tavsiye-filmler-702/page/" to "Tavsiye Filmler",
        "category/vizyon-filmleri/page/" to "Vizyondakiler",
        "dizi-izle/page/" to "Diziler",
        "category/netflix/page/" to "Netflix",
        "category/aile/page/" to "Aile",
        "category/aksiyon/page/" to "Aksiyon",
        "category/bilim-kurgu/page/" to "Bilim Kurgu",
        "category/korku/page/" to "Korku",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBaseUrl()
        val url = "$base/${request.data}$page"
        val doc = app.get(url).document

        val home = doc.select("a.poster").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBaseUrl()
        val doc = app.get("$base/search/$query").document
        return doc.select("a.poster").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("strong.poster-title")?.text()?.trim() ?: return null
        val href = this.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val year = this.selectFirst(".poster-meta span:first-child")?.text()?.trim()?.toIntOrNull()
        val rating = this.selectFirst("span.imdb")?.text()?.trim()
            ?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()

        val isDizi = href.contains("/dizi-izle/") || href.contains("/sezon-")
        val type = if (isDizi) TvType.TvSeries else TvType.Movie

        return if (isDizi) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .detail-title h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst(".detail-poster img, .poster-wrapper img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val description = doc.selectFirst(".detail-description, .detail-text, .ozet, [itemprop=description]")?.text()?.trim()
        val year = doc.selectFirst(".detail-info span:contains(20), .poster-meta span:first-child")?.text()?.trim()?.toIntOrNull()
        val rating = doc.selectFirst(".imdb, .detail-info .imdb")?.text()?.replace(Regex("[^0-9.]"), "")
            ?.toDoubleOrNull()?.let { (it * 10).toInt() }
        val duration = doc.selectFirst(".detail-info span:contains(dk)")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        val tags = doc.select(".detail-info a[href*=category], .categories a, a[href*='/tur/']").map { it.text().trim() }

        // Dizi mi kontrol et
        val episodes = doc.select(".episodes a, .episode-list a, a[href*='-bolum-izle']").mapNotNull { ep ->
            val epTitle = ep.text().trim()
            val epHref = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val epNum = Regex("""(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val seasonNum = Regex("""(\d+)-sezon""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epHref) {
                this.name = epTitle
                this.season = seasonNum ?: 1
                this.episode = epNum
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.duration = duration
                this.tags = tags.ifEmpty { null }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.duration = duration
                this.tags = tags.ifEmpty { null }
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Sayfadaki tüm iframe'leri kontrol et
        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.startsWith("http")) loadExtractor(src, mainUrl, subtitleCallback, callback)
        }

        // data-token ile AJAX kaynak yükleme
        val token = doc.selectFirst("[data-token]")?.attr("data-token")
        if (token != null) {
            try {
                val ajaxResponse = app.post(
                    "$mainUrl/sources",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data
                    ),
                    data = mapOf("token" to token)
                ).text

                Regex("""(?:src|url|file)\s*[=:]\s*["'](https?://[^"'\s]+)""").findAll(ajaxResponse).forEach { match ->
                    loadExtractor(match.groupValues[1], mainUrl, subtitleCallback, callback)
                }
            } catch (_: Exception) { }
        }

        // Alternatif kaynaklar (part-btn'ler)
        doc.select("a[data-part], button[data-part], .part-btn-wrapper a").forEach { partBtn ->
            val partUrl = partBtn.attr("href").ifEmpty { partBtn.attr("data-src") }
            if (partUrl.startsWith("http")) {
                try {
                    val partDoc = app.get(partUrl, referer = data).document
                    partDoc.select("iframe[src]").forEach { iframe ->
                        loadExtractor(iframe.attr("src"), mainUrl, subtitleCallback, callback)
                    }
                } catch (_: Exception) { }
            }
        }

        return true
    }
}
