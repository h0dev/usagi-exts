package tsuki.site.vi

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
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

@MangaSourceParser("CUUTRUYENMOE", "Cứu Truyện (Kuro Neko)", "vi")
internal class CuuTruyenMoe(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CUUTRUYENMOE, 60) {

	override val configKeyDomain = ConfigKey.Domain("cuutruyen.moe")

	override val webClient = OkHttpWebClient(
		context.httpClient.newBuilder()
			.rateLimit(10, 60.seconds)
			.build(),
		source,
	)

	private val gatePasswordKey = ConfigKey.PreferredImageServer(
		presetValues = mapOf(
			"5" to "Mặc định (5)",
			"1" to "1",
			"123456" to "123456",
		),
		defaultValue = "5",
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(gatePasswordKey)
	}

	override fun getRequestHeaders() = okhttp3.Headers.Builder()
		.add(CommonHeaders.REFERER, "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	// ============================== List ==============================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/tim-kiem")

			append("?sort=")
			append(
				when (order) {
					SortOrder.UPDATED -> "-updated_at"
					SortOrder.NEWEST -> "-created_at"
					SortOrder.NEWEST_ASC -> "created_at"
					SortOrder.POPULARITY -> "-views"
					SortOrder.ALPHABETICAL -> "name"
					SortOrder.ALPHABETICAL_DESC -> "-name"
					else -> "-updated_at"
				},
			)

			// The site's own detail pages link status=1 for finished and status=2 for ongoing.
			val statusFilter = when {
				filter.states.isEmpty() -> "2,1"

				MangaState.ONGOING in filter.states && MangaState.FINISHED in filter.states -> "2,1"
				MangaState.ONGOING in filter.states -> "2"
				else -> "1"
			}
			append("&filter[status]=")
			append(statusFilter)

			if (filter.tags.isNotEmpty()) {
				append("&filter[accept_genres]=")
				append(filter.tags.joinTo(this, ",") { it.key })
			}

			if (!filter.query.isNullOrEmpty()) {
				// The site returns HTTP 500 for the `keyword` param; `filter[name]` works.
				append("&filter[name]=")
				append(filter.query.urlEncoded())
			}

			append("&page=")
			append(page)
		}

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.manga-vertical").map { element ->
			val linkElement = element.selectFirstOrThrow("div.p-2 a")
			val href = linkElement.attrAsRelativeUrl("href")

			Manga(
				id = generateUid(href),
				title = linkElement.text(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = element.selectFirst("div.cover")?.extractBackgroundImage(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override val sortOrders: Set<SortOrder> = availableSortOrders

	// ============================== Details ==============================

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		val tags = doc.select("div.mt-2 a[href*=/the-loai/]")
			.mapNotNullToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast('/'),
					title = a.textOrNull()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null,
					source = source,
				)
			}

		val stateText = doc.selectFirst("a[href*='filter[status]'] span, a[href*='filter%5Bstatus%5D'] span")?.text()
		val state = when {
			stateText?.contains("Đã hoàn thành") == true -> MangaState.FINISHED
			stateText?.contains("Đang tiến hành") == true -> MangaState.ONGOING
			else -> MangaState.ONGOING
		}

		val chapters = doc.select("ul.overflow-y-auto a[href*=/truyen/]")
			.mapNotNull { a ->
				val name = a.selectFirst("div.grow span.text-ellipsis, div.grow span.truncate")?.text()?.trim()
				if (name.isNullOrEmpty()) return@mapNotNull null

				MangaChapter(
					id = generateUid(a.attrAsRelativeUrl("href")),
					title = name,
					number = regexChapterNumber.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f,
					volume = 0,
					url = a.attrAsRelativeUrl("href"),
					scanlator = null,
					uploadDate = parseChapterDate(
						a.selectFirst("span.timeago[datetime]")?.attr("datetime"),
					),
					branch = null,
					source = source,
				)
			}
			.distinctBy { it.url }
			.reversed()

		val description = doc.selectFirst("div.mg-plot")
			?.select("p")
			?.drop(1)
			?.joinToString("\n") { it.text() }
			?.trim()
			?.takeIf { it.isNotBlank() }

		return manga.copy(
			title = doc.selectFirst("span.grow.text-lg")?.text() ?: manga.title,
			authors = setOfNotNull(doc.selectFirst("a[href*=/tac-gia/]")?.textOrNull()),
			description = description,
			tags = tags,
			state = state,
			chapters = chapters,
			coverUrl = doc.selectFirst("div.cover-frame div.cover, div.cover-frame")
				?.extractBackgroundImage() ?: manga.coverUrl,
		)
	}

	// ============================== Pages ==============================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		val urls = doc.select("div.text-center > img.max-w-full")
			.mapNotNull { it.extractImageUrl() }

		if (urls.isEmpty()) throw ParseException("Could not find image data", url)

		return urls.map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun Element.extractImageUrl(): String? = sequenceOf("src", "data-src", "data-original", "data-lazy-src")
		.map { attr(it) }
		.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }

	// ============================== Gate bypass ==============================

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()

		// Only the site itself is behind the Livewire gate; CDN/static hosts are not.
		if (request.url.host != domain) {
			return chain.proceed(request)
		}

		val newRequest = request.newBuilder()
			.addHeader(CommonHeaders.REFERER, "https://$domain/")
			.build()

		var response = chain.proceed(newRequest)

		val bypassTried = request.header("X-Bypass-Tried") != null
		if (request.method == "GET" && response.isSuccessful && !bypassTried) {
			val contentType = response.body.contentType()
			if (contentType?.subtype == "html") {
				val html = response.peekBody(1024 * 1024).string()
				if (html.contains("enter-secret")) {
					val success = runCatching { bypassGatekeeper(chain, html, request.url, newRequest) }
						.getOrDefault(false)
					if (success) {
						response.close()
						response = chain.proceed(
							newRequest.newBuilder().addHeader("X-Bypass-Tried", "true").build(),
						)
					}
				}
			}
		}

		return response
	}

	private fun bypassGatekeeper(
		chain: Interceptor.Chain,
		html: String,
		originalUrl: okhttp3.HttpUrl,
		baseRequest: Request,
	): Boolean {
		val doc = Jsoup.parse(html, originalUrl.toString())
		val element = doc.selectFirst("[wire:id][wire:initial-data]") ?: return false
		val initialData = JSONObject(element.attr("wire:initial-data"))
		val fingerprint = initialData.getJSONObject("fingerprint")
		val serverMemo = initialData.getJSONObject("serverMemo")

		val password = config[gatePasswordKey].ifBlank { "5" }
		val payload = """
			{"fingerprint":$fingerprint,"serverMemo":$serverMemo,"updates":[{"type":"syncInput","payload":{"id":"s1","name":"password","value":"$password"}},{"type":"callMethod","payload":{"id":"c1","method":"submit","params":[]}}]}
		""".trimIndent()

		val csrfToken = regexLivewireToken.find(html)?.groupValues?.get(1)
			?: doc.selectFirst("meta[name=csrf-token]")?.attr("content")
			?: ""

		val postRequest = Request.Builder()
			.headers(baseRequest.headers)
			.post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
			.url(
				originalUrl.newBuilder()
					.encodedPath("/livewire/message/${fingerprint.getString("name")}")
					.query(null)
					.build(),
			)
			.addHeader("X-CSRF-TOKEN", csrfToken)
			.addHeader("X-Livewire", "true")
			.addHeader("Accept", "text/html, application/xhtml+xml")
			.addHeader(CommonHeaders.REFERER, originalUrl.toString())
			.build()

		chain.proceed(postRequest).use { response ->
			if (!response.isSuccessful) return false
			val body = response.body.string()
			return body.contains("Passed") ||
				response.headers("Set-Cookie").any { it.contains("session") }
		}
	}

	// ============================== Utils ==============================

	private suspend fun availableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/tim-kiem").parseHtml()
		return doc.select("label[\\@click]")
			.mapNotNullToSet { label ->
				val id = label.attr("\\@click").findGroupValue(regexGenreId) ?: return@mapNotNullToSet null
				val name = label.textOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNullToSet null

				MangaTag(
					key = id,
					title = name.toTitleCase(sourceLocale),
					source = source,
				)
			}
			.distinctBy { it.key }
			.toSet()
	}

	private fun Element.extractBackgroundImage(): String? =
		regexBackgroundImage.find(attr("style"))?.groupValues?.get(1)

	private fun parseChapterDate(date: String?): Long {
		if (date.isNullOrEmpty()) return 0L
		return runCatching { dateFormat.parse(date)?.time }.getOrNull() ?: 0L
	}

	private val dateFormat by lazy {
		SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
		}
	}

	private companion object {
		val regexGenreId = Regex("""toggleGenre\('(\d+)'\)""")
		val regexBackgroundImage = Regex("""background-image:\s*url\(['"]?(.*?)['"]?\)""")
		val regexLivewireToken = Regex("""livewire_token\s*=\s*'([^']+)'""")
		val regexChapterNumber = Regex("""(?i)(?:chap|chapter|ch\.?)\s*([\d.]+)""")
	}
}
