package tsuki.site.liliana.vi

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.*
import tsuki.network.CommonHeaders
import tsuki.site.liliana.LilianaParser
import tsuki.util.*
import java.util.EnumSet

/**
 * The site moved from manga.io.vn to manga.pro.vn and reshuffled its urls:
 *
 * | before                | now                    |
 * |-----------------------|------------------------|
 * | /all-manga/N/?sort=   | /danh-sach-truyen/N    |
 * | /ranking/week/N       | /xem-nhieu/N (all-time)|
 * | /filter/N             | gone, genres are pages |
 *
 * The old cdn (ntcdnqq.wibu.asia) no longer resolves; the images now come from
 * doctruyen5s.luce.moe straight from the chapter ajax response, so the proxy
 * fallback that used to live here was dropped.
 */
@MangaSourceParser("DOCTRUYEN5S", "DocTruyen5s", "vi")
internal class DocTruyen5s(context: MangaLoaderContext) :
	LilianaParser(context, MangaParserSource.DOCTRUYEN5S, "manga.pro.vn", 42) {

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add(CommonHeaders.REFERER, "no-referrer")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			// picking a genre navigates to its own page, so only one applies
			isMultipleTagsSupported = false,
			// the status param is accepted but ignored by the site
			isSearchWithFiltersSupported = false,
		)

	// ============================== List ==============================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			val query = filter.query
			if (!query.isNullOrBlank()) {
				append("/search/")
				append(page)
				append("/?keyword=")
				append(query.urlEncoded())
				return@buildString
			}

			val tag = filter.tags.firstOrNull()
			if (tag != null) {
				append("/the-loai/")
				append(tag.key)
				append('/')
				append(page)
				return@buildString
			}

			// /xem-nhieu is the popularity board, /truyen-moi the recency one
			val sort = when (order) {
				SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "new"
				SortOrder.ALPHABETICAL -> "az"
				SortOrder.ALPHABETICAL_DESC -> "za"
				SortOrder.POPULARITY -> null
				SortOrder.POPULARITY_TODAY -> "views_day"
				SortOrder.POPULARITY_WEEK -> "views_week"
				SortOrder.POPULARITY_MONTH -> "views_month"
				else -> "last_update"
			}

			append(if (order == SortOrder.POPULARITY) "/xem-nhieu/" else "/danh-sach-truyen/")
			append(page)

			sort?.let {
				append("?sort=")
				append(it)
			}
		}

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	/**
	 * Page 1 puts the entries in a thumbnail grid and repeats them as rows right
	 * below, later pages only use the rows. Both shapes are collected and the
	 * duplicates dropped.
	 */
	private fun parseMangaList(doc: Document): List<Manga> {
		val fromGrid = doc.select("div.grid.gtc-f141a > div")
			.mapNotNull { it.toManga(".text-center a") }
		if (fromGrid.isNotEmpty()) return fromGrid

		return doc.select("article")
			.mapNotNull { it.toManga("h3.post-title a") }
			.distinctBy { it.url }
	}

	private fun Element.toManga(titleSelector: String): Manga? {
		val link = selectFirst("a[href*=/manga/]") ?: return null
		val href = link.attrAsRelativeUrl("href")

		val title = selectFirst(titleSelector)?.textOrNull()
			?: selectFirst("a[href*=/manga/]")?.attr("title")?.nullIfEmpty()
			?: link.textOrNull()
			?: return null

		return Manga(
			id = generateUid(href),
			title = title.trim(),
			altTitles = emptySet(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = selectFirst("img")?.src(),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	// ============================== Filters ==============================

	/**
	 * The new domain dropped the status query param (it is accepted but ignored)
	 * and /filter is gone, so only the genre list survives. Genres live in the
	 * header menu and link to /the-loai/<slug> pages.
	 */
	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getAvailableTags(),
	)

	protected override suspend fun getAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		return doc.select("a[href*=/the-loai/]")
			.mapNotNullToSet { a ->
				val key = a.attr("href").substringAfterLast('/').takeIf { it.isNotBlank() }
					?: return@mapNotNullToSet null
				val title = a.textOrNull()?.takeIf { it.isNotBlank() }
					?: return@mapNotNullToSet null

				MangaTag(key = key, title = title, source = source)
			}
			.distinctBy { it.key }
			.toSet()
	}

	// ============================== Pages ==============================

	// Pages come from the chapter ajax endpoint already pointing at the live cdn,
	// so the inherited implementation is enough - no cdn juggling needed.
}
