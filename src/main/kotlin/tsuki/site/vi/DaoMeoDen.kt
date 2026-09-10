package tsuki.site.vi

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
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

@MangaSourceParser("DAOMEODEN", "Đảo Mèo Đen", "vi")
internal class DaoMeoDen(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DAOMEODEN, 24) {

	override val configKeyDomain = ConfigKey.Domain("daomeoden.net")

	override val webClient = OkHttpWebClient(
		context.httpClient.newBuilder()
			.rateLimit(3)
			.build(),
		source,
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add(CommonHeaders.REFERER, "https://$domain/")
		.build()

	private fun apiHeaders(referer: String) = Headers.Builder()
		.add(CommonHeaders.ORIGIN, "https://$domain")
		.add(CommonHeaders.REFERER, referer)
		.add(CommonHeaders.X_REQUESTED_WITH, "XMLHttpRequest")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			// The site keeps only the first value of each filter parameter
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableGenres(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
		),
	)

	// ============================== List ==============================

	/**
	 * The browse page holds the real listing behind a POST to bookList.php.
	 * The page itself only carries the query state in `var` declarations which
	 * have to be echoed back verbatim, so every request is a two step dance:
	 * GET the shell, POST the form, parse the returned HTML fragment.
	 */
	private suspend fun fetchListPage(query: Map<String, String>): List<Manga> {
		val listUrl = "https://$domain$LIST_PATH".toHttpUrl().newBuilder()
			.apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
			.build()
			.toString()

		val shell = webClient.httpGet(listUrl).parseHtml()
		val html = shell.html()

		val form = listOf(
			"token", "pageCurrent", "pageLast", "status", "ages", "category",
			"genre", "explicit", "magazine", "tags", "order", "pagiParam", "textSearch",
		).associateWith { key ->
			html.findScriptVariable(key) ?: DEFAULTS[key] ?: ""
		}

		val payload = webClient.httpPost(
			"https://$domain/apps/controllers/book/bookList.php".toHttpUrl(),
			form,
			apiHeaders(listUrl),
		).parseJson()

		val htmlBook = payload.optString("htmlBook").takeIf { it.isNotEmpty() }
			?: return emptyList()

		return Jsoup.parseBodyFragment(htmlBook, "https://$domain")
			.select("div.item-list")
			.map { element ->
			val link = element.selectFirstOrThrow("div.item-title a")
			val href = link.attrAsRelativeUrl("href")

			Manga(
				id = generateUid(href),
				title = link.text(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = element.selectFirst("div.item-cover img")
					?.attr("src")?.normalizeImageUrl(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = mutableMapOf("page" to page.toString())

		val orderPart = when (order) {
			SortOrder.UPDATED -> "updated_at"
			SortOrder.NEWEST -> "created_at"
			SortOrder.POPULARITY -> "viewsAll"
			else -> "updated_at"
		}
		query["order"] = orderPart

		// The site's filter accepts a single value per parameter; sending a
		// comma separated list makes it silently keep only the first entry.
		filter.states.firstOrNull()?.let { state ->
			query["status"] = when (state) {
				MangaState.FINISHED -> "1"
				MangaState.ONGOING -> "2"
				else -> "0"
			}
		}

		filter.tags.firstOrNull()?.let { tag ->
			query["genre"] = tag.key
		}

		if (!filter.query.isNullOrEmpty()) {
			query["textSearch"] = filter.query
		}

		return fetchListPage(query)
	}

	// ============================== Details ==============================

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		val tags = doc.select("div.info-tag.tag-genre span")
			.mapNotNullToSet { span ->
				val title = span.textOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNullToSet null
				MangaTag(key = title.lowercase().replace(' ', '-'), title = title, source = source)
			}

		val category = doc.selectFirst("div.info-tag.tag-category span")?.textOrNull()
		val extraTags = doc.select("div.info-tag.tag-tag span").mapNotNullToSet { span ->
			val title = span.textOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNullToSet null
			MangaTag(key = title.lowercase().replace(' ', '-'), title = title, source = source)
		}

		val stateText = doc.selectFirst("div.info-tag.tag-status span")?.text()?.lowercase()
		val state = when {
			stateText == null -> null
			stateText.contains("ongoing") -> MangaState.ONGOING
			stateText.contains("full") || stateText.contains("completed") ||
				stateText.contains("hoàn") -> MangaState.FINISHED
			else -> null
		}

		val chapters = doc.select("div#TabChapterChapter div.chapter")
			.mapNotNull { element ->
				val chapterUrl = regexOpenUrl.find(element.attr("onclick"))
					?.groupValues?.get(1)
					?: return@mapNotNull null

				val name = element.selectFirst("div.chapter-info div.name-sub")?.textOrNull()
					?: element.selectFirst("div.chapter-info div.name")?.textOrNull()
					?: return@mapNotNull null

				MangaChapter(
					id = generateUid(chapterUrl),
					title = name.trim(),
					number = regexChapterNumber.find(name)?.value?.toFloatOrNull() ?: 0f,
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = parseChapterDate(
						element.selectFirst("div.chapter-info div.time > div")?.text(),
					),
					branch = null,
					source = source,
				)
			}
			.distinctBy { it.url }

		return manga.copy(
			title = doc.selectFirst("div.info-name")?.text() ?: manga.title,
			description = doc.selectFirst("div.info-description div.content")?.textOrNull(),
			tags = tags + extraTags +
				setOfNotNull(category?.let { MangaTag(it.lowercase(), it, source) }),
			state = state,
			chapters = chapters.reversed(),
			coverUrl = doc.selectFirst("div.info-cover-img img")
				?.attr("src")?.normalizeImageUrl() ?: manga.coverUrl,
		)
	}

	// ============================== Pages ==============================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()
		val html = doc.html()

		val chapterId = html.findScriptVariable("chapterId")
			?: throw ParseException("chapterId not found", url)
		val token = html.findScriptVariable("_token")
			?: throw ParseException("Token not found", url)

		val payload = webClient.httpPost(
			"https://$domain/apps/controllers/book/bookChapterContent.php".toHttpUrl(),
			mapOf(
				"token" to token,
				"chapterId" to chapterId,
				"cookies" to CHAPTER_COOKIES,
			),
			apiHeaders(url),
		).parseJson()

		if (payload.optInt("status") != 200) {
			throw ParseException(payload.optString("mess", "Unknown error"), url)
		}

		val content = payload.optString("data").takeIf { it.isNotEmpty() }
			?: throw ParseException("Empty chapter content", url)

		val images = Jsoup.parseBodyFragment(content, "https://$domain").select("img")
			.mapNotNull { it.extractImageUrl() }
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

	private fun Element.extractImageUrl(): String? =
		sequenceOf("data-src", "src")
			.map { attr(it) }
			.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
			?.normalizeImageUrl()

	// ============================== Utils ==============================

	private suspend fun availableGenres(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain$LIST_PATH").parseHtml()
		return doc.select("#filterGenre .filter-item[data-slug]")
			.mapNotNullToSet { element ->
				val id = element.attr("data-slug").takeIf { it.isNotBlank() }
					?: return@mapNotNullToSet null
				val name = element.textOrNull()?.takeIf { it.isNotBlank() }
					?: return@mapNotNullToSet null

				MangaTag(key = id, title = name, source = source)
			}
			.distinctBy { it.key }
			.toSet()
	}

	private fun String.findScriptVariable(key: String): String? =
		Regex("""var\s+$key\s*=\s*'([^']*)'""").find(this)?.groupValues?.get(1)

	private fun String.normalizeImageUrl(): String = if (startsWith("//")) "https:$this" else this

	private fun parseChapterDate(date: String?): Long {
		if (date.isNullOrEmpty()) return 0L
		return runCatching { chapterDateFormat.parse(date)?.time }.getOrNull() ?: 0L
	}

	private val chapterDateFormat by lazy {
		SimpleDateFormat("dd.MM.yyyy - HH:mm", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
		}
	}

	private companion object {
		const val LIST_PATH = "/danh-sach-truyen-tranh.html"
		const val CHAPTER_COOKIES = "W10="

		val regexOpenUrl = Regex("""openUrl\('([^']+)'\)""")
		val regexChapterNumber = Regex("""\d+(?:\.\d+)?""")

		val DEFAULTS = mapOf(
			"status" to "0",
			"category" to "all",
			"genre" to "0",
			"explicit" to "0",
			"order" to "updated_at",
		)
	}
}
