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
import keiyoushi.utils.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Picazor : KeiSource() {

    override val name = "Picazor"
    override val baseUrl = "https://picazor.com/es"
    override val supportsLatest = true

    private val siteRoot = "https://picazor.com"
    private val photoUrlRegex = Regex("""^https://picazor\.com/es/[^/#]+/\d+$""")
    private val sizePrefixRegex = Regex("""/\d+px_""")
    private val contentUrlRegex = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
    private val albumIdRegex = Regex("""/albums/(\d+)$""")
    private val spanishDateRegex = Regex("""(\d{1,2}) de (\w+) de (\d{4})""")
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

    // ------------------------------------------------------------------ Popular

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page <= 1) "$baseUrl/hot" else "$baseUrl/hot/page/$page"
        val doc = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseCreators(doc, url)
    }

    // ------------------------------------------------------------------- Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page <= 1) baseUrl else "$baseUrl/page/$page"
        val doc = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseCreators(doc, url)
    }

    // ------------------------------------------------------------------- Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search/${query.trim()}"
        val doc = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseCreators(doc, url)
    }

    private fun parseCreators(doc: Document, pageUrl: String): MangasPage {
        val isFirstPage = !pageUrl.contains(Regex("""/page/\d+$"""))
        val container = doc.selectFirst("#main-content") ?: doc.selectFirst("main") ?: doc

        val rawEntries = container.select("a[href]")
            .mapNotNull { anchor ->
                val rawHref = anchor.attr("href").trim()
                val href = absolutize(rawHref).substringBefore('#').substringBefore('?')
                if (!href.startsWith("https://picazor.com/es/")) return@mapNotNull null
                val cleanUrl = href.replace(albumIdRegex, "")
                val slug = cleanUrl.removePrefix("https://picazor.com/es/").trim('/')
                if (slug.isBlank() || '/' in slug || slug in reservedSegments) return@mapNotNull null

                val card = anchor.closest("[class*=group/post]")
                    ?: anchor.closest("article")
                    ?: anchor.parent()

                val title = anchor.selectFirst("[data-album-post-name]")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: anchor.selectFirst("img")?.attr("alt")?.substringBefore(" - ")?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: anchor.text().trim()
                        .takeIf { it.isNotBlank() }
                    ?: slug

                val thumb = card?.selectFirst("a[href*=/albums/] img")?.absSrc()?.takeIf { it.isNotBlank() }
                    ?: card?.selectFirst("img")?.absSrc()?.takeIf { it.isNotBlank() }
                    ?: anchor.selectFirst("img")?.absSrc()

                SManga.create().apply {
                    setUrlWithoutDomain(cleanUrl.substringAfter(siteRoot))
                    this.title = title
                    thumbnail_url = thumb
                }
            }
            .distinctBy { it.url }

        if (rawEntries.isEmpty()) {
            val sampleHref = container.select("a[href]").firstOrNull()?.attr("href")?.take(40).orEmpty()
            val debugItem = SManga.create().apply {
                url = "/es/marley-brinx"
                title = "L:${container.select("a[href]").size} I:${container.select("img").size} T:${doc.title().take(25)} H:$sampleHref"
            }
            return MangasPage(listOf(debugItem), false)
        }

        val hasNext = isFirstPage || container.selectFirst("a[rel=next]") != null ||
            container.select("a[href]").any { it.absHref().contains(Regex("""/page/\d+$""")) }

        return MangasPage(rawEntries, hasNext)
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
                val doc = client.get(siteRoot + manga.url, Fetch.DOCUMENT).asJsoup()
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
            ?: throw Exception("Creador no encontrado")
        val handle = scope.selectFirst("[data-file-auxnames] bdi")?.text()?.trim()
        val onlyfans = scope.selectFirst("a[href^=https://onlyfans.com]")?.absHref()

        return SManga.create().apply {
            url = manga.url
            this.title = title
            author = handle
            artist = handle
            thumbnail_url = scope.selectFirst("img[src*=\"/images/profiles/\"]")?.absSrc()
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
            val doc = client.get(nextUrl, Fetch.DOCUMENT).asJsoup()
            result += parseChapters(doc, manga)

            nextUrl = doc.selectFirst("a[rel=next]")?.absHref()
                ?: doc.select("a[href]")
                    .firstOrNull {
                        it.text().trim().startsWith("Sig") && it.absHref().contains("/albums")
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
        val doc = client.get(albumUrl, Fetch.DOCUMENT).asJsoup()
        val photos = photoAnchors(doc)

        val derived = photos.mapNotNull { anchor ->
            anchor.selectFirst("img")?.absSrc()?.replace(sizePrefixRegex, "")
        }.filter { it.isNotBlank() }.distinct().reversed()

        val urls = if (derived.isNotEmpty()) {
            derived
        } else {
            photos.mapNotNull { anchor ->
                runCatching {
                    val body = client.get(anchor.absHref(), Fetch.DOCUMENT).body.string()
                    contentUrlRegex.find(body)?.groupValues?.get(1) ?: Jsoup.parse(body).baseUri().takeIf { false }
                }.getOrNull()
            }.distinct().reversed()
        }

        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
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
