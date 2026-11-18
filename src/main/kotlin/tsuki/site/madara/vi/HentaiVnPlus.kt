package tsuki.site.madara.vi

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("HENTAIVNPLUS", "HentaiVN.plus", "vi", ContentType.HENTAI)
internal class HentaiVnPlus(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.HENTAIVNPLUS, 24) {

    override val configKeyDomain = ConfigKey.Domain("hentaivnreal.com", "hentaivn.show")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST, SortOrder.UPDATED, SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = when {
            !filter.query.isNullOrBlank() -> urlBuilder().addPathSegments("tim-kiem")
                .addQueryParameter("q", filter.query)
                .addQueryParameter("page", (page + 1).toString())
                .build()
            else -> urlBuilder().addPathSegments("danh-sach")
                .addQueryParameter("page", (page + 1).toString())
                .build()
        }
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(".item-list .item").mapNotNull { item ->
            val link = item.selectFirst(".box-description a[href^=/truyen/]")
                ?: item.selectFirst(".box-cover a[href^=/truyen/]")
                ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
            val title = link.text().trim().ifEmpty { return@mapNotNull null }
            val image = item.selectFirst(".box-cover img")?.let { img ->
                img.attrAsAbsoluteUrlOrNull("src") ?: img.attrAsAbsoluteUrlOrNull("data-src")
            }
            Manga(
                id = generateUid(href), title = title, altTitles = emptySet(), url = href,
                publicUrl = href.toAbsoluteUrl(domain), rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT, coverUrl = image, tags = emptySet(),
                state = null, authors = emptySet(), source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val dateFormat = SimpleDateFormat("d/M/yyyy", Locale.ROOT)
        val stateText = doc.select(".page-info").text()
        val chapters = doc.select("table.listing tr").mapNotNull { row ->
            val a = row.selectFirst("a[href*=/truyen/][href*=/chap-]") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            val date = row.select("td").lastOrNull()?.text()?.trim()
            MangaChapter(
                id = generateUid(href), title = a.text().trim(), number = chapterNumber(a.text()),
                volume = 0, url = href, scanlator = null, uploadDate = dateFormat.parseSafe(date),
                branch = null, source = source,
            )
        }.sortedBy { it.number }
        return manga.copy(
            title = doc.selectFirst(".page-info h1")?.text()?.trim() ?: manga.title,
            altTitles = setOfNotNull(doc.selectFirst(".page-info h2")?.textOrNull()),
            authors = setOfNotNull(doc.selectFirst(".page-info a.info-link[href^=/tac-gia/]")?.textOrNull()),
            tags = doc.select(".page-info a.tag").mapToSet {
                MangaTag(it.attr("href").substringAfterLast('/'), it.text().trim(), source)
            },
            description = doc.selectFirst(".detail-preview")?.textOrNull(),
            state = if (stateText.contains("Đã hoàn thành", true)) MangaState.FINISHED else MangaState.ONGOING,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select(".chapter_content img, .chapter_content_div img").mapNotNull { img ->
            val url = img.attr("data-original").ifBlank { img.attr("src") }.trim()
            if (url.isBlank()) null else MangaPage(generateUid(url), url, null, source)
        }
    }

    private fun chapterNumber(title: String): Float =
        Regex("(?i)(?:chap(?:ter)?|chương)\\s*([0-9]+(?:\\.[0-9]+)?)")
            .find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
}
