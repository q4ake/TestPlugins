package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class SineFilTRProvider : MainAPI() {
    override var mainUrl = "https://sinefil.tv"
    override var name = "SineFilTR"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private val DOMAINS = listOf(
            "https://sinefil.tv",
            "https://sinefil.com.tr",
            "https://sinefil.club",
            "https://sinefil.org",
            "https://sinefil.net",
            "https://sinefil.live",
        )
    }

    private suspend fun getBaseUrl(): String {
        val resolved = DomainResolver.resolve("sinefil", DOMAINS)
        mainUrl = resolved
        return resolved
    }

    override val mainPage = mainPageOf(
        "filmler/page/" to "Filmler",
        "diziler/page/" to "Diziler",
        "tur/aksiyon/page/" to "Aksiyon",
        "tur/bilim-kurgu/page/" to "Bilim Kurgu",
        "tur/dram/page/" to "Dram",
        "tur/komedi/page/" to "Komedi",
        "tur/korku/page/" to "Korku",
        "tur/gerilim/page/" to "Gerilim",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBaseUrl()
        val url = "$base/${request.data}$page"
        val doc = app.get(url).document
        val home = doc.select(".poster, .movie-box, article.item, .film-item, .content-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBaseUrl()
        val doc = app.get("$base/?s=$query").document
        return doc.select(".poster, .movie-box, article.item, .film-item, .content-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a[href]") ?: this.takeIf { it.tagName() == "a" } ?: return null
        val title = aTag.attr("title").ifEmpty {
            this.selectFirst("h2, h3, .title, strong")?.text()?.trim() ?: ""
        }.replace(Regex("\\s*izle\\s*", RegexOption.IGNORE_CASE), "").trim()
        if (title.isEmpty()) return null
        val href = aTag.attr("href")
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val year = this.selectFirst(".year, .yil")?.text()?.trim()?.toIntOrNull()
        val isDizi = href.contains("/dizi/") || href.contains("-sezon-") || href.contains("-bolum")

        return if (isDizi) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.replace(Regex("\\s*izle\\s*", RegexOption.IGNORE_CASE), "")?.trim() ?: ""
        val poster = doc.selectFirst(".detail-poster img, .poster img, article img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val description = doc.selectFirst(".description, .ozet, [itemprop=description]")?.text()?.trim()
        val year = doc.selectFirst(".year, .yil")?.text()?.replace(Regex("[^0-9]"), "")?.take(4)?.toIntOrNull()
        val rating = doc.selectFirst(".imdb, .rating")?.text()?.replace(Regex("[^0-9.]"), "")
            ?.toDoubleOrNull()?.let { (it * 10).toInt() }
        val tags = doc.select(".genre a, .tur a, a[href*='/tur/']").map { it.text().trim() }

        val episodes = doc.select(".episode-list a, .episodes a, a[href*='-bolum-izle'], a[href*='-bolum/']").mapNotNull { ep ->
            val epTitle = ep.text().trim()
            val epHref = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val epNum = Regex("""(\d+)[\.\-]?\s*b[oö]l[uü]m""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val seasonNum = Regex("""(\d+)[\.\-]?\s*sezon""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            Episode(epHref, epTitle, seasonNum, epNum)
        }.distinctBy { it.data }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = description; this.year = year
                this.rating = rating; this.tags = tags.ifEmpty { null }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.plot = description; this.year = year
                this.rating = rating; this.tags = tags.ifEmpty { null }
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.startsWith("http")) loadExtractor(src, mainUrl, subtitleCallback, callback)
        }
        doc.select("[data-embed], [data-player]").forEach { tab ->
            val embedUrl = tab.attr("data-embed").ifEmpty { tab.attr("data-player") }
            if (embedUrl.startsWith("http")) loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}
