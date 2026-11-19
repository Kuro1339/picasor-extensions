package eu.kanade.tachiyomi.extension.es.picazor

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.runWebView
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Picazor : KeiSource() {

    override val name = "Picazor"
    override val baseUrl = "https://picazor.com/es"
    override val supportsLatest = true

    private val siteRoot = "https://picazor.com"
    private val pageSize = 20
    private val photoUrlRegex = Regex("""^https://picazor\.com/es/[^/#]+/\d+$""")
    private val sizePrefixRegex = Regex("""/\d+px_""")
    private val videoThumbSuffixRegex = Regex("""\.(mp4|webm|m3u8)\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)
    private val contentUrlRegex = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
    private val albumIdRegex = Regex("""/albums/(\d+)$""")
    private val spanishDateRegex = Regex("""(\d{1,2}) de (\w+) de (\d{4})""")
    private val mediaUrlRegex = Regex(
        """https?://[^\s"'<>\\]+\.(?:mp4|m3u8|webm|mpd)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val dateFormat = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("es", "ES"))

    private val reservedSegments = setOf(
        "es", "search", "tags", "categories", "creators", "hot", "top", "videos",
        "shorts", "forum", "userposts", "collections", "blog", "help", "donate",
        "welcome", "page", "images", "albums", "discuss", "login", "signup",
        "random", "dmca-policy", "report-abuse", "community-guidelines",
        "terms-of-service", "privacy-policy", "2257",
    )

    override fun getMangaUrl(manga: SManga): String = siteRoot + manga.url

    override fun getChapterUrl(chapter: SChapter): String = siteRoot + chapter.url

    private suspend fun fetchDocument(
        url: String,
        readySelector: String = "article[data-album-post], [data-home-feed=\"true\"] a[href*=\"/albums/\"], #main-content a[href]",
        scrollTargetCount: Int = 0,
    ): Document {
        val html = runWebView<String>(timeout = 45.seconds) {
            blockImages = true
            loadUrl(url)

            var ticks = 0
            var lastCardCount = 0
            var stagnantTicks = 0
            val quotedSelector = JSONObject.quote(readySelector)

            val checkAndScrollJs = """
                (() => {
                    const selector = $quotedSelector;
                    const el = document.querySelector(selector);
                    const hasText = document.body && document.body.innerText && document.body.innerText.trim().length > 80;
                    const cardCount = document.querySelectorAll('article[data-album-post]').length;
                    const target = $scrollTargetCount;

                    if (el && hasText && target <= 0) {
                        return JSON.stringify({ ready: true, count: cardCount, html: document.documentElement.outerHTML });
                    }

                    if (el && hasText && target > 0 && cardCount >= target) {
                        return JSON.stringify({ ready: true, count: cardCount, html: document.documentElement.outerHTML });
                    }

                    window.scrollTo(0, document.body.scrollHeight);
                    return JSON.stringify({ ready: false, count: cardCount, html: null });
                })()
            """.trimIndent()

            poll(interval = 600.milliseconds) {
                ticks++
                evaluateJs(checkAndScrollJs) { rawJson ->
                    val decoded = decodeJsonString(rawJson)
                    val obj = decoded?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val isReady = obj?.optBoolean("ready", false) == true
                    val count = obj?.optInt("count", 0) ?: 0
                    val readyHtml = obj?.optString("html").orEmpty()

                    if (count > lastCardCount) {
                        lastCardCount = count
                        stagnantTicks = 0
                    } else if (scrollTargetCount > 0) {
                        stagnantTicks++
                    }

                    val reachedEnd = scrollTargetCount > 0 && count > 0 && stagnantTicks >= 12
                    val timedOutByTicks = ticks >= 65

                    if (isReady && readyHtml.isNotBlank()) {
                        resolve(readyHtml)
                    } else if (reachedEnd || timedOutByTicks) {
                        evaluateJs("document.documentElement.outerHTML") { fallbackJson ->
                            resolve(decodeJsonString(fallbackJson).orEmpty())
                        }
                    }
                }
            }
        }
        return Jsoup.parse(html, url)
    }

    private fun decodeJsonString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            JSONTokener(raw).nextValue() as? String
        }.getOrNull()
    }

    // ------------------------------------------------------------------ Popular

    override suspend fun getPopularManga(page: Int): MangasPage {
        val targetCards = page * pageSize
        val doc = fetchDocument(url = "$baseUrl/hot", scrollTargetCount = targetCards)
        return parseCreators(doc, page)
    }

    // ------------------------------------------------------------------- Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val targetCards = page * pageSize
        val doc = fetchDocument(url = baseUrl, scrollTargetCount = targetCards)
        return parseCreators(doc, page)
    }

    // ------------------------------------------------------------------- Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val encodedQuery = query.trim().replace(" ", "%20")
        val targetCards = page * pageSize
        val doc = fetchDocument(url = "$baseUrl/search/$encodedQuery", scrollTargetCount = targetCards)
        return parseCreators(doc, page)
    }

    private fun parseCreators(doc: Document, page: Int): MangasPage {
        val container = doc.selectFirst("[data-home-feed=\"true\"]")
            ?: doc.selectFirst("#main-content")
            ?: doc.selectFirst("main")
            ?: doc

        val cards = container.select("article[data-album-post]")

        val rawEntries = if (cards.isNotEmpty()) {
            cards.mapNotNull { card ->
                val creatorAnchor = card.selectFirst("a[data-card-name=\"true\"][href]")
                    ?: card.selectFirst("a[href^=\"/es/\"]:not([href*=\"/albums/\"])")
                    ?: return@mapNotNull null

                val cleanUrl = absolutize(creatorAnchor.attr("href"))
                    .substringBefore('#')
                    .substringBefore('?')
                    .replace(albumIdRegex, "")

                val slug = extractCreatorSlug(cleanUrl) ?: return@mapNotNull null

                val previewImg = card.selectFirst("[data-home-preview=\"true\"] a[href*=\"/albums/\"] img")
                    ?: card.selectFirst("img")

                val title = creatorAnchor.attr("aria-label")
                    .substringBefore(",")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: previewImg?.attr("alt")
                        ?.substringBefore(" - ")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: creatorAnchor.text().trim().takeIf { it.isNotBlank() }
                    ?: slug

                val thumb = previewImg?.absSrc()?.takeIf { it.isNotBlank() }

                SManga.create().apply {
                    setUrlWithoutDomain(cleanUrl.substringAfter(siteRoot))
                    this.title = title
                    thumbnail_url = thumb
                }
            }
        } else {
            container.select("a[href]").mapNotNull { anchor ->
                val href = absolutize(anchor.attr("href")).substringBefore('#').substringBefore('?')
                if (!href.startsWith("$siteRoot/es/")) return@mapNotNull null
                val cleanUrl = href.replace(albumIdRegex, "")
                val slug = extractCreatorSlug(cleanUrl) ?: return@mapNotNull null

                val card = anchor.closest("article[data-album-post]")
                    ?: anchor.closest("article")
                    ?: anchor.parent()

                val title = anchor.selectFirst("img")?.attr("alt")?.substringBefore(" - ")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: anchor.text().trim().takeIf { it.isNotBlank() }
                    ?: slug

                val thumb = card?.selectFirst("a[href*=\"/albums/\"] img")?.absSrc()?.takeIf { it.isNotBlank() }
                    ?: card?.selectFirst("img")?.absSrc()

                SManga.create().apply {
                    setUrlWithoutDomain(cleanUrl.substringAfter(siteRoot))
                    this.title = title
                    thumbnail_url = thumb
                }
            }
        }.distinctBy { it.url }

        if (rawEntries.isEmpty()) {
            val sampleHref = container.select("a[href]").firstOrNull()?.attr("href")?.take(40).orEmpty()
            val debugItem = SManga.create().apply {
                url = "/es/marley-brinx"
                title = "L:${container.select("a[href]").size} I:${container.select("img").size} T:${doc.title().take(25)} H:$sampleHref"
            }
            return MangasPage(listOf(debugItem), false)
        }

        val startIndex = ((page - 1) * pageSize).coerceAtLeast(0)
        if (startIndex >= rawEntries.size) {
            return MangasPage(emptyList(), false)
        }

        val pageItems = rawEntries.drop(startIndex).take(pageSize)
        val hasNext = rawEntries.size > startIndex + pageItems.size || cards.size >= page * pageSize

        return MangasPage(pageItems, hasNext)
    }

    private fun extractCreatorSlug(cleanUrl: String): String? {
        val prefix = "$siteRoot/es/"
        if (!cleanUrl.startsWith(prefix)) return null
        val slug = cleanUrl.removePrefix(prefix).trim('/')
        if (slug.isBlank() || '/' in slug || slug in reservedSegments) return null
        return slug
    }

    // --------------------------------------------------------- Detalles y Capítulos

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = async {
            if (fetchDetails) {
                val doc = fetchDocument(siteRoot + manga.url, "h1, #main-content")
                parseMangaDetails(doc, manga)
            } else {
                manga
            }
        }
        val chaptersDeferred = async {
            if (fetchChapters) {
                fetchAllChapters(manga)
            } else {
                chapters
            }
        }
        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    private fun parseMangaDetails(doc: Document, manga: SManga): SManga {
        val scope = doc.selectFirst("#main-content") ?: doc.selectFirst("main") ?: doc

        val title = scope.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: manga.title
        val handle = scope.selectFirst("[data-file-auxnames] bdi")?.text()?.trim()
        val onlyfans = scope.selectFirst("a[href^=\"https://onlyfans.com\"]")?.absHref()

        return SManga.create().apply {
            url = manga.url
            this.title = title
            author = handle
            artist = handle
            thumbnail_url = scope.selectFirst("img[src*=\"/images/profiles/\"]")?.absSrc() ?: manga.thumbnail_url
            genre = scope.select("a[href*=\"/tags/\"]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString()
            status = SManga.ONGOING
            description = buildString {
                append("Creador: ").append(title)
                if (!handle.isNullOrBlank()) append("\n\nOnlyFans: ").append(handle)
                if (!onlyfans.isNullOrBlank()) append("\n").append(onlyfans)
            }
            initialized = true
        }
    }

    private suspend fun fetchAllChapters(manga: SManga): List<SChapter> {
        val result = mutableListOf<SChapter>()
        var nextUrl: String? = siteRoot + manga.url + "/albums"
        var pages = 0

        while (nextUrl != null && pages++ < 100) {
            val doc = fetchDocument(nextUrl, "a[href*=\"/albums/\"], #main-content")
            result += parseChapters(doc, manga)

            nextUrl = doc.selectFirst("a[rel=next]")?.absHref()
                ?: doc.select("a[href]")
                    .firstOrNull {
                        it.text().trim().startsWith("Sig", ignoreCase = true) && it.absHref().contains("/albums")
                    }
                    ?.absHref()
        }

        return result.distinctBy { it.url }
    }

    private fun parseChapters(doc: Document, manga: SManga): List<SChapter> {
        val scope = doc.selectFirst("#main-content") ?: doc.selectFirst("main") ?: return emptyList()

        return scope.select("a[href*=\"/albums/\"]")
            .mapNotNull { anchor ->
                val url = anchor.absHref().substringBefore('#').substringBefore('?')
                val albumId = albumIdRegex.find(url)?.groupValues?.get(1) ?: return@mapNotNull null

                SChapter.create().apply {
                    setUrlWithoutDomain(url.substringAfter(siteRoot))
                    name = "Álbum #$albumId"
                    chapter_number = albumId.toFloatOrNull() ?: -1f
                    date_upload = parseSpanishDate(scope.text())
                    scanlator = manga.title
                }
            }
            .distinctBy { it.url }
    }

    private fun parseSpanishDate(text: String): Long {
        val match = spanishDateRegex.find(text) ?: return 0L
        val (day, month, year) = match.destructured
        return runCatching { dateFormat.parse("$day de $month de $year")?.time ?: 0L }.getOrDefault(0L)
    }

    // ------------------------------------------------------------------- Páginas

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val albumUrl = siteRoot + chapter.url
        val doc = fetchDocument(
            albumUrl,
            "[data-file-viewer=\"true\"], [data-file-player=\"true\"], video source[src], video[src], a[href], img, #main-content",
        )
        val photos = photoAnchors(doc)

        val derivedImages = mutableListOf<String>()
        val derivedVideos = mutableListOf<String>()

        for (anchor in photos) {
            val rawSrc = anchor.selectFirst("img")?.absSrc()?.replace(sizePrefixRegex, "/").orEmpty()
            if (rawSrc.isBlank()) continue

            if (videoThumbSuffixRegex.containsMatchIn(rawSrc)) {
                val videoUrl = rawSrc.replace(videoThumbSuffixRegex, ".$1")
                derivedVideos += videoUrl
            } else {
                derivedImages += rawSrc
            }
        }

        val cleanImages = derivedImages.distinct().reversed()
        if (cleanImages.isNotEmpty()) {
            return cleanImages.mapIndexed { index, url -> Page(index, imageUrl = url) }
        }

        val allVideos = (derivedVideos + extractDirectVideos(doc)).distinct().reversed()
        if (allVideos.isNotEmpty()) {
            throw Exception(
                "Este capítulo contiene video (${allVideos.size}) y el lector solo muestra imágenes.\n" +
                    "Abre en navegador/WebView o copia el enlace:\n${allVideos.first()}",
            )
        }

        val fallbackUrls = photos.mapNotNull { anchor ->
            runCatching {
                val body = client.get(anchor.absHref(), Fetch.DOCUMENT).body.string()
                contentUrlRegex.find(body)?.groupValues?.get(1)
            }.getOrNull()
        }.distinct().reversed()

        return fallbackUrls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private fun extractDirectVideos(doc: Document): List<String> {
        val viewer = doc.selectFirst("[data-file-viewer=\"true\"]") ?: doc
        val player = viewer.selectFirst("[data-file-player=\"true\"]") ?: viewer

        val candidates = buildList {
            player.select("video[src]").forEach { el ->
                val src = el.attr("src").trim()
                if (src.isNotBlank()) add(absolutize(src))
            }
            player.select("video source[src], source[type*=\"video\"][src]").forEach { el ->
                val src = el.attr("src").trim()
                if (src.isNotBlank()) add(absolutize(src))
            }
            doc.select("script[type=\"application/ld+json\"]").forEach { ldJson ->
                val raw = ldJson.data()
                contentUrlRegex.findAll(raw).forEach { match ->
                    add(absolutize(match.groupValues[1].replace("\\/", "/")))
                }
                mediaUrlRegex.findAll(raw).forEach { match ->
                    add(absolutize(match.value.replace("\\/", "/")))
                }
            }
            mediaUrlRegex.findAll(player.outerHtml()).forEach { match ->
                add(absolutize(match.value.replace("\\/", "/")))
            }
        }

        return candidates
            .map { it.substringBefore('#') }
            .filter { it.isNotBlank() && !it.startsWith("blob:") }
            .distinct()
    }

    private fun photoAnchors(doc: Document): List<Element> {
        val scope = doc.selectFirst("#main-content") ?: doc.selectFirst("main") ?: return emptyList()
        return scope.select("a[href]")
            .filter { photoUrlRegex.matches(it.absHref().substringBefore('#').substringBefore('?')) }
            .filter { it.selectFirst("img") != null }
    }

    private fun Element.absHref(): String = absolutize(attr("href"))

    private fun Element.absSrc(): String = absolutize(attr("src"))

    private fun absolutize(value: String): String = when {
        value.isBlank() -> ""
        value.startsWith("http") -> value
        value.startsWith("//") -> "https:$value"
        value.startsWith("/") -> siteRoot + value
        else -> "$siteRoot/$value"
    }
}
