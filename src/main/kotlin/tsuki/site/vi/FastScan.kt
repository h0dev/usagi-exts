package tsuki.site.vi

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.model.*
import tsuki.network.CommonHeaders
import tsuki.network.OkHttpWebClient
import tsuki.util.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.seconds

@MangaSourceParser("FASTSCAN", "FastScan", "vi")
internal class FastScan(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.FASTSCAN, 42) {

	override val configKeyDomain = ConfigKey.Domain("fastscan.org")

	/**
	 * Tsuki throws TooManyRequestExceptions rather than waiting, so the window is
	 * kept generous.
	 */
	override val webClient = OkHttpWebClient(
		context.httpClient.newBuilder()
			.rateLimit(30, 1.seconds)
			.build(),
		source,
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders() = okhttp3.Headers.Builder()
		.add(CommonHeaders.REFERER, "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.POPULARITY,
		// POPULARITY_ASC (sort=5) makes the site answer 500 unless a category
		// is also set, so it is left out rather than failing for the default view.
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			// the advanced form takes a single genre id
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableGenres(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	// ============================== List ==============================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			val query = filter.query
			if (!query.isNullOrBlank()) {
				append("/tim-kiem?q=")
				append(query.urlEncoded())
			} else {
				append("/tim-kiem-nang-cao")

				append("?category=")
				append(filter.tags.firstOrNull()?.key.orEmpty())

				append("&notcategory=")

				append("&status=")
				append(filter.states.firstOrNull().toStatusPart())

				append("&minchapter=0")

				append("&sort=")
				append(
					when (order) {
						SortOrder.NEWEST -> "0"
						SortOrder.NEWEST_ASC -> "1"
						SortOrder.UPDATED -> "2"
						SortOrder.UPDATED_ASC -> "3"
						SortOrder.POPULARITY -> "4"
						else -> "0"
					},
				)
			}

			append("&page=")
			append(page)
		}

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private fun MangaState?.toStatusPart(): String = when (this) {
		MangaState.ONGOING -> "1"
		MangaState.FINISHED -> "2"
		else -> "0"
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("ul.list_grid.grid > li").mapNotNull { it.toManga() }
	}

	private fun Element.toManga(): Manga? {
		val link = selectFirst(".book_avatar a, .book_name a") ?: return null
		val title = selectFirst(".book_name a")?.textOrNull() ?: return null

		val href = link.attrAsRelativeUrl("href")

		return Manga(
			id = generateUid(href),
			title = title.trim(),
			altTitles = emptySet(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = selectFirst("img")?.let { img ->
				img.attr("data-src").ifEmpty { img.attr("src") }.toAbsoluteUrl(domain)
			},
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	// ============================== Details ==============================

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		val stateText = doc.selectFirst("li.status p.col-xs-9")?.text()?.lowercase()
		val state = when {
			stateText == null -> null
			"đang cập nhật" in stateText -> MangaState.ONGOING
			"hoàn thành" in stateText -> MangaState.FINISHED
			else -> null
		}

		val tags = doc.select(".book_other ul.list01 a")
			.mapNotNullToSet { a ->
				val title = a.textOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNullToSet null
				MangaTag(key = title.lowercase(), title = title, source = source)
			}

		val description = doc.select(".story-detail-info")
			.joinToString("\n\n") { container ->
				val blocks = container.select("p")
				if (blocks.isNotEmpty()) {
					blocks.joinToString("\n\n") { it.wholeText().trim() }
				} else {
					container.wholeText().trim()
				}
			}
			.nullIfEmpty()

		val chapters = doc.select(".list_chapter .works-chapter-item")
			.mapNotNull { element ->
				val link = element.selectFirst(".name-chap a[href]") ?: return@mapNotNull null
				val href = link.attrAsRelativeUrl("href")

				MangaChapter(
					id = generateUid(href),
					title = link.textOrNull()?.trim(),
					number = regexChapterNumber.find(link.text())?.value?.toFloatOrNull() ?: 0f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = parseChapterDate(element.selectFirst(".time-chap")?.textOrNull()),
					branch = null,
					source = source,
				)
			}
			.distinctBy { it.url }
			.reversed()

		return manga.copy(
			title = doc.selectFirst(".book_detail .book_other h1")?.text() ?: manga.title,
			authors = setOfNotNull(
				doc.selectFirst("p:contains(Tác giả) + p")?.text()?.trim()?.nullIfEmpty(),
			),
			description = description,
			tags = tags,
			state = state,
			chapters = chapters,
			coverUrl = doc.selectFirst(".book_info .book_avatar img")?.let { img ->
				img.attr("data-src").ifEmpty { img.attr("src") }.toAbsoluteUrl(domain)
			} ?: manga.coverUrl,
		)
	}

	// ============================== Related ==============================

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val url = seed.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		val list = doc.select("h3")
			.firstOrNull { it.text().contains("liên quan", ignoreCase = true) }
			?.nextElementSibling()
			?.selectFirst("ul.list_grid.grid")
			?: return emptyList()

		return list.select("li").mapNotNull { it.toManga() }.distinctBy { it.url }
	}

	// ============================== Pages ==============================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		val images = doc.select("#chapter_content img, .page-chapter img, img.lozad")
			.mapNotNull { element ->
				val raw = element.attr("data-src").ifEmpty { element.attr("src") }
				raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }
					?.toAbsoluteUrl(domain)
			}
			.distinct()

		if (images.isEmpty()) throw ParseException("Could not find image data", url)

		return images.map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	// ============================== Filters ==============================

	/**
	 * The advanced form renders genres as `.genre-item > span.icon-checkbox[data-id]`,
	 * and the ids happen to run 1..62 in document order. Reading the attribute keeps
	 * working if the site ever reorders the list.
	 */
	private suspend fun availableGenres(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/tim-kiem-nang-cao").parseHtml()
		return doc.select(".genre-item")
			.mapNotNullToSet { item ->
				val id = item.selectFirst("span[data-id]")?.attr("data-id")
					?.takeIf { it.isNotBlank() } ?: return@mapNotNullToSet null
				val title = item.textOrNull()?.trim()?.takeIf { it.isNotBlank() }
					?: return@mapNotNullToSet null

				MangaTag(key = id, title = title, source = source)
			}
			.distinctBy { it.key }
			.toSet()
	}

	// ============================== Utils ==============================

	private fun parseChapterDate(date: String?): Long {
		if (date.isNullOrEmpty()) return 0L
		return runCatching { chapterDateFormat.parse(date)?.time }.getOrNull() ?: 0L
	}

	private val chapterDateFormat by lazy {
		SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
		}
	}

	private companion object {
		val regexChapterNumber = Regex("""\d+(?:\.\d+)?""")
	}
}
