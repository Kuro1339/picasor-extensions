package eu.kanade.tachiyomi.extension.es.picazor

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

@Source
class Picazor(
    override val lang: String,
    override val id: Long,
) : KeiSource() {

    override val name = "Picazor"
    override val baseUrl = "https://picazor.com"
    override val supportsLatest = true

    override val isNsfw = true

    private val localePrefix = "$baseUrl/es"
    private val creatorUrlRegex = Regex("""^https://picazor\.com/es/[^/#]+$""")
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

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headers.newBuilder()
            .set("Referer", "$localePrefix/")
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    // ------------------------------------------------------------------ Popular

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/es/creators/top/$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseCreators(response.asJsoup(), response.request.url.toString())

    // ------------------------------------------------------------------- Latest

    override fun latestUpdatesRequest(page: Int): Request = GET("$localePrefix/page/$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseCreators(response.asJsoup(), response.request.url.toString())

    // ------------------------------------------------------------------- Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$localePrefix/search/${query.trim()}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseCreators(response.asJsoup(), response.request.url.toString())

    private fun parseCreators(doc: Document, pageUrl: String): MangasPage {
        val isFirstPage = !pageUrl.contains(Regex("""/(page|top)/\d+$"""))

        val entries = doc.select("#main-content a[href]")
            .filter { it.closest(".breadcrumbs") == null }
            .mapNotNull { anchor ->
                val img = anchor.selectFirst("img") ?: return@mapNotNull null
                val raw = anchor.absHref().substringBefore('#').substringBefore('?')
                val creatorUrl = raw.replace(albumIdRegex, "")
                if (!creatorUrlRegex.matches(creatorUrl)) return@mapNotNull null
                if (creatorUrl.substringAfterLast('/') in reservedSegments) return@mapNotNull null
                val title = img.attr("alt")
                    .substringBefore(" - ")
                    .ifBlank { anchor.text() }
                    .trim()
                if (title.isBlank()) return@mapNotNull null

                SManga.create().apply {
                    setUrlWithoutDomain(creatorUrl.substringAfter(baseUrl))
                    this.title = title
                    thumbnail_url = img.absSrc()
                }
            }
            .distinctBy { it.url }

        val hasNext = if (entries.isEmpty()) {
            false
        } else {
            isFirstPage || doc.selectFirst("#main-content a[rel=next]") != null ||
                doc.select("#main-content a[href]").any { it.absHref().contains(Regex("""/(page|top)/\d+$""")) }
        }

        return MangasPage(entries, hasNext)
    }

    // ------------------------------------------------------------------ Detalles

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val scope = doc.selectFirst("#main-content") ?: doc

        val title = scope.selectFirst("h1")?.text()?.trim()
            ?: throw Exception("Creador no encontrado")
        val handle = scope.selectFirst("[data-file-auxnames] bdi")?.text()?.trim()
        val onlyfans = scope.selectFirst("a[href^=https://onlyfans.com]")?.absHref()
        return SManga.create().apply {
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

    // ----------------------------------------------------------------- Capítulos

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url + "/albums", headers)

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.defer {
        val chapters = mutableListOf<SChapter>()
        var nextUrl: String? = baseUrl + manga.url + "/albums"
        var pages = 0

        while (nextUrl != null && pages++ < 100) {
            val doc = client.newCall(GET(nextUrl, headers)).execute().use { it.asJsoup() }
            chapters += parseChapters(doc, manga)

            nextUrl = doc.selectFirst("#main-content a[rel=next]")?.absHref()
                ?: doc.select("#main-content a[href]")
                    .firstOrNull {
                        it.text().trim().startsWith("Sig") && it.absHref().contains("/albums")
                    }
                    ?.absHref()
        }

        Observable.just(chapters.distinctBy { it.url })
    }

    override fun chapterListParse(response: Response): List<SChapter> = parseChapters(response.asJsoup(), null)

    private fun parseChapters(doc: Document, manga: SManga?): List<SChapter> {
        val scope = doc.selectFirst("#main-content") ?: return emptyList()

        return scope.select("a[href*=\"/albums/\"]")
            .mapNotNull { anchor ->
                val url = anchor.absHref().substringBefore('#').substringBefore('?')
                val albumId = albumIdRegex.find(url)?.groupValues?.get(1) ?: return@mapNotNull null

                SChapter.create().apply {
                    setUrlWithoutDomain(url.substringAfter(baseUrl))
                    name = "Álbum #$albumId"
                    chapter_number = albumId.toFloatOrNull() ?: -1f
                    thumbnail_url = anchor.selectFirst("img")?.absSrc()
                    date_upload = parseSpanishDate(scope.text())
                    scanlator = manga?.title
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

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.defer {
        val albumUrl = baseUrl + chapter.url
        val doc = client.newCall(GET(albumUrl, headers)).execute().use { it.asJsoup() }
        val photos = photoAnchors(doc)

        val derived = photos.mapNotNull { anchor ->
            anchor.selectFirst("img")?.absSrc()?.replace(sizePrefixRegex, "")
        }.filter { it.isNotBlank() }.distinct().reversed()

        val urls = if (derived.isNotEmpty()) {
            derived
        } else {
            photos.mapNotNull { anchor ->
                runCatching {
                    client.newCall(GET(anchor.absHref(), headers)).execute().use { r ->
                        contentUrlRegex.find(r.body.string())?.groupValues?.get(1)
                    }
                }.getOrNull()
            }.distinct().reversed()
        }

        Observable.just(urls.mapIndexed { index, url -> Page(index, chapter.url, url) })
    }

    override fun pageListParse(response: Response): List<Page> {
        val urls = photoAnchors(response.asJsoup())
            .mapNotNull { it.selectFirst("img")?.absSrc()?.replace(sizePrefixRegex, "") }
            .filter { it.isNotBlank() }
            .distinct()
            .reversed()

        return urls.mapIndexed { index, url -> Page(index, response.request.url.toString(), url) }
    }

    private fun photoAnchors(doc: Document): List<Element> {
        val scope = doc.selectFirst("#main-content") ?: return emptyList()
        return scope.select("a[href]")
            .filter { photoUrlRegex.matches(it.absHref().substringBefore('#').substringBefore('?')) }
            .filter { it.selectFirst("img") != null }
    }

    override fun getFilterList(): FilterList = FilterList()

    private fun Element.absHref(): String = absolutize(attr("href"))

    private fun Element.absSrc(): String = absolutize(attr("src"))

    private fun absolutize(value: String): String = when {
        value.isBlank() -> ""
        value.startsWith("http") -> value
        value.startsWith("//") -> "https:$value"
        value.startsWith("/") -> baseUrl + value
        else -> "$baseUrl/$value"
    }
}
