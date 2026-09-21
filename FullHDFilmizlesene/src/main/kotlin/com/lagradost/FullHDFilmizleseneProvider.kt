package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.util.Base64

class FullHDFilmizleseneProvider : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullHDFilmizlesene"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private val DOMAINS = listOf(
            "https://www.fullhdfilmizlesene.now",
            "https://www.fullhdfilmizlesene.de",
            "https://www.fullhdfilmizlesene.com",
            "https://www.fullhdfilmizlesene.net",
            "https://www.fullhdfilmizlesene.org",
            "https://www.fullhdfilmizlesene.live",
        )
    }

    private suspend fun getBaseUrl(): String {
        val resolved = DomainResolver.resolve("fullhdfilmizlesene", DOMAINS)
        mainUrl = resolved
        return resolved
    }

    override val mainPage = mainPageOf(
        "yeni-filmler/" to "Yeni Filmler",
        "en-cok-izlenen-filmler/" to "En Çok İzlenen",
        "en-cok-begenilen-filmler/" to "En Çok Beğenilen",
        "turkce-dublaj-filmler/" to "Türkçe Dublaj",
        "turkce-altyazili-filmler/" to "Türkçe Altyazılı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBaseUrl()
        val url = if (page > 1) "$base/${request.data}$page" else "$base/${request.data}"
        val doc = app.get(url).document

        val home = doc.select("li.film, div.film").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = doc.selectFirst("link[rel=next]") != null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBaseUrl()
        val doc = app.get("$base/arama/$query").document
        return doc.select("li.film, div.film").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("h2.film-tt span.film-title") ?: return null
        val title = titleEl.text().trim()
        val href = this.selectFirst("a.tt")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }?.takeIf { !it.contains("svg+xml") }
        val year = this.selectFirst("span.film-yil")?.text()?.trim()?.toIntOrNull()
        val quality = when {
            this.selectFirst("span.uhd") != null -> SearchQuality.UHD
            this.selectFirst("span.hd") != null -> SearchQuality.HD
            else -> null
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = quality
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1[itemprop=name], h1.izle-title, h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("img.ic-afis, .izle-poster img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }?.takeIf { !it.contains("svg+xml") }
        val description = doc.selectFirst("div.izle-desc p, div.ozet-ic p, [itemprop=description]")?.text()?.trim()
        val year = doc.selectFirst("span.film-yil, .izle-meta .yil")?.text()?.trim()?.toIntOrNull()
        val rating = doc.selectFirst("span.imdb, .puan span")?.text()?.trim()?.toDoubleOrNull()?.let { (it * 10).toInt() }
        val duration = doc.selectFirst("span.sure")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        val tags = doc.select("span.ktt a, .tur a, a[href*='/tur/']").map { it.text().trim() }

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
        val body = doc.html()

        val vidid = Regex("""var\s+vidid\s*=\s*'(\d+)'""").find(body)?.groupValues?.get(1) ?: return false
        val scxMatch = Regex("""var\s+scx\s*=\s*(\{.*?\});""").find(body)?.groupValues?.get(1)

        if (scxMatch != null) {
            try {
                parseScxSources(scxMatch).forEach { (_, iframeUrl) ->
                    loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
                }
            } catch (_: Exception) { }
        }

        val dataQ = doc.selectFirst("script[data-q]")?.attr("data-q")
        if (dataQ != null) {
            try {
                val decrypted = decryptV2(dataQ)
                if (decrypted.startsWith("http")) loadExtractor(decrypted, mainUrl, subtitleCallback, callback)
            } catch (_: Exception) { }
        }

        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.startsWith("http")) loadExtractor(src, mainUrl, subtitleCallback, callback)
        }

        return true
    }

    private fun parseScxSources(scxJson: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val sourceRegex = Regex(""""(\w+)":\s*\{"tt":"([^"]+)","sx":\{"p":\[(.*?)\],"t":\[(.*?)\]""")
        sourceRegex.findAll(scxJson).forEach { match ->
            val sourceName = match.groupValues[1]
            val titleB64 = match.groupValues[2]
            val pLinks = match.groupValues[3]
            val tLinks = match.groupValues[4]
            val title = try { String(Base64.getDecoder().decode(titleB64)) } catch (_: Exception) { sourceName }
            val linkRegex = Regex(""""([^"]+)"""")

            linkRegex.findAll(tLinks).forEach { lm ->
                val decoded = decryptV2(lm.groupValues[1])
                if (decoded.startsWith("http")) results.add("$title (TR)" to decoded)
            }
            linkRegex.findAll(pLinks).forEach { lm ->
                val decoded = decryptV2(lm.groupValues[1])
                if (decoded.startsWith("http")) results.add("$title (ORJ)" to decoded)
            }
        }
        return results
    }

    private fun decryptV2(v: String): String {
        if (!v.startsWith("v2.")) return try { String(Base64.getDecoder().decode(v)) } catch (_: Exception) { "" }
        val reversed = v.substring(3).reversed().replace('-', '+').replace('_', '/')
        val padded = reversed + "===".substring(0, (4 - reversed.length % 4) % 4)
        val decoded = try { Base64.getDecoder().decode(padded) } catch (_: Exception) { return "" }
        val key = "fullhdfilmizlesene.now|fmx"
        val sb = StringBuilder()
        for (i in decoded.indices) {
            sb.append(((decoded[i].toInt() and 0xFF) xor key[(i * 7 + decoded.size) % key.length].code xor ((i * 13 + 31) and 255)).toChar())
        }
        return sb.toString()
    }
}
