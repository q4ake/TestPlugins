package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class AnimeCixProvider : MainAPI() {
    override var mainUrl = "https://animecix.tv"
    override var name = "AnimeCix"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)

    companion object {
        private val DOMAINS = listOf(
            "https://animecix.tv",
            "https://animecix.com",
            "https://animecix.net",
            "https://animecix.org",
            "https://animecix.de",
            "https://animecix.live",
        )
    }

    private suspend fun getBaseUrl(): String {
        val resolved = DomainResolver.resolve("animecix", DOMAINS)
        mainUrl = resolved
        return resolved
    }

    override val mainPage = mainPageOf(
        "anime-izle?page=" to "Son Eklenenler",
        "tur/aksiyon?page=" to "Aksiyon",
        "tur/macera?page=" to "Macera",
        "tur/komedi?page=" to "Komedi",
        "tur/dram?page=" to "Dram",
        "tur/fantastik?page=" to "Fantastik",
        "tur/bilim-kurgu?page=" to "Bilim Kurgu",
        "tur/korku?page=" to "Korku",
        "tur/romantizm?page=" to "Romantizm",
        "tur/shounen?page=" to "Shounen",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBaseUrl()
        val url = "$base/${request.data}$page"
        val doc = app.get(url).document

        val home = doc.select(".anime-card, .poster, article.item, .movie-box, .film-item, .content-card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBaseUrl()
        val doc = app.get("$base/arama?q=$query").document
        return doc.select(".anime-card, .poster, article.item, .movie-box, .film-item, .content-card, .search-result .item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a[href]") ?: this.takeIf { it.tagName() == "a" } ?: return null
        val title = aTag.attr("title").ifEmpty {
            this.selectFirst("h2, h3, h4, .title, strong, .anime-title, .poster-title, .name")?.text()?.trim() ?: aTag.text().trim()
        }.replace(Regex("\\s*izle\\s*", RegexOption.IGNORE_CASE), "").trim()
        if (title.isEmpty()) return null
        val href = aTag.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("data-lazy-src").ifEmpty { img.attr("src") } }
        }
        val year = this.selectFirst(".year, .yil, span:matches(^20\\d{2}$)")?.text()?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .anime-title, .detail-title")?.text()
            ?.replace(Regex("\\s*izle\\s*", RegexOption.IGNORE_CASE), "")?.trim() ?: ""
        val poster = doc.selectFirst(".anime-poster img, .detail-poster img, .poster img, article img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val description = doc.selectFirst(".description, .ozet, .synopsis, [itemprop=description], .anime-description")?.text()?.trim()
        val year = doc.selectFirst(".year, .yil")?.text()?.replace(Regex("[^0-9]"), "")?.take(4)?.toIntOrNull()
        val rating = doc.selectFirst(".imdb, .rating, .mal-score, .puan")?.text()?.replace(Regex("[^0-9.]"), "")
            ?.toDoubleOrNull()?.let { (it * 10).toInt() }
        val tags = doc.select(".genre a, .tur a, a[href*='/tur/'], .tags a").map { it.text().trim() }
        val type = doc.selectFirst(".type, .anime-type, .detail-type")?.text()?.lowercase()

        // Bölüm listesi
        val episodes = doc.select(".episode-list a, .episodes a, .bolumler a, a[href*='-bolum'], a[href*='/episode/'], a[href*='/ep/']").mapNotNull { ep ->
            val epTitle = ep.text().trim()
            val epHref = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val epNum = Regex("""(\d+)[\.\-]?\s*(?:b[oö]l[uü]m|episode|ep)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""/(\d+)/?$""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            Episode(epHref, epTitle, 1, epNum)
        }.distinctBy { it.data }

        val animeType = when {
            type?.contains("movie") == true || type?.contains("film") == true -> TvType.AnimeMovie
            type?.contains("ova") == true || type?.contains("special") == true -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(title, url, animeType) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.rating = rating
            this.tags = tags.ifEmpty { null }
            addEpisodes(DubStatus.Subbed, episodes)
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

        // Player sekmeleri / alternatif kaynaklar
        doc.select("[data-embed], [data-player], [data-src], .player-tab, .video-source").forEach { tab ->
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

        // Altyazı dosyaları
        doc.select("track[kind=subtitles], track[kind=captions]").forEach { track ->
            val subSrc = track.attr("src")
            val subLang = track.attr("label").ifEmpty { track.attr("srclang").ifEmpty { "Türkçe" } }
            if (subSrc.startsWith("http")) {
                subtitleCallback.invoke(SubtitleFile(subLang, subSrc))
            }
        }

        return true
    }
}
