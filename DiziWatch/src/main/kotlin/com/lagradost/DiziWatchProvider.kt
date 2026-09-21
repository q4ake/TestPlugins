package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class DiziWatchProvider : MainAPI() {
    override var mainUrl = "https://diziwatch.ac"
    override var name = "DiziWatch"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    companion object {
        private val DOMAINS = listOf(
            "https://diziwatch.ac",
            "https://diziwatch.net",
            "https://diziwatch2.com",
            "https://diziwatch3.com",
            "https://diziwatch.pro",
            "https://diziwatch.de",
            "https://diziwatch.org",
            "https://diziwatch.live",
        )
    }

    private suspend fun getBaseUrl(): String {
        val resolved = DomainResolver.resolve("diziwatch", DOMAINS)
        mainUrl = resolved
        return resolved
    }

    override val mainPage = mainPageOf(
        "dizi-izle/page/" to "Son Bölümler",
        "kore-dizileri/page/" to "Kore Dizileri",
        "anime-izle/page/" to "Anime",
        "yerli-dizi-izle/page/" to "Yerli Diziler",
        "yabanci-dizi-izle/page/" to "Yabancı Diziler",
        "netflix-dizileri/page/" to "Netflix",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBaseUrl()
        val url = "$base/${request.data}$page"
        val doc = app.get(url).document

        val home = doc.select(".poster-long, .poster, article.item, .movie-box, .dizi-box").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBaseUrl()
        val doc = app.get("$base/?s=$query").document
        return doc.select(".poster-long, .poster, article.item, .movie-box, .dizi-box, .search-result .item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a[href]") ?: this.takeIf { it.tagName() == "a" } ?: return null
        val title = aTag.attr("title").ifEmpty {
            this.selectFirst("h2, h3, .title, strong, .poster-title")?.text()?.trim() ?: aTag.text().trim()
        }.replace(Regex("\\s*(izle|altyazılı|türkçe dublaj)\\s*", RegexOption.IGNORE_CASE), "").trim()
        if (title.isEmpty()) return null
        val href = aTag.attr("href")
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("data-lazy-src").ifEmpty { img.attr("src") } }
        }
        val year = this.selectFirst(".year, .yil, span:matches(^20\\d{2}$)")?.text()?.trim()?.toIntOrNull()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .detail-title, .page-title")?.text()
            ?.replace(Regex("\\s*(izle|tüm bölümleri)\\s*", RegexOption.IGNORE_CASE), "")?.trim() ?: ""
        val poster = doc.selectFirst(".detail-poster img, .poster img, article img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val description = doc.selectFirst(".detail-description, .description, .ozet, [itemprop=description]")?.text()?.trim()
        val year = doc.selectFirst(".year, .yil, span:contains(20)")?.text()?.replace(Regex("[^0-9]"), "")?.take(4)?.toIntOrNull()
        val rating = doc.selectFirst(".imdb, .rating")?.text()?.replace(Regex("[^0-9.]"), "")
            ?.toDoubleOrNull()?.let { (it * 10).toInt() }
        val tags = doc.select(".genre a, .tur a, a[href*='/tur/']").map { it.text().trim() }

        // Bölüm listesi
        val episodes = doc.select(".episode-list a, .episodes a, .bolumler a, a[href*='-bolum-izle'], a[href*='-episode-']").mapNotNull { ep ->
            val epTitle = ep.text().trim()
            val epHref = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val epNum = Regex("""(\d+)[\.\-]?\s*(?:b[oö]l[uü]m|episode)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val seasonNum = Regex("""(\d+)[\.\-]?\s*sezon""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""season-?(\d+)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            newEpisode(epHref) {
                this.name = epTitle
                this.season = seasonNum
                this.episode = epNum
            }
        }.distinctBy { it.data }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags.ifEmpty { null }
            }
        } else {
            // Tek sayfa olabilir, direkt video araba
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags.ifEmpty { null }
            }
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

        doc.select("[data-embed], [data-player], .player-tab[data-src], .alternative-source[data-src]").forEach { tab ->
            val embedUrl = tab.attr("data-embed").ifEmpty { tab.attr("data-player").ifEmpty { tab.attr("data-src") } }
            if (embedUrl.startsWith("http")) loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
        }

        return true
    }
}
