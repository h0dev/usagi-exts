package tsuki.site.vi.moetruyen

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.HttpStatusException
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
import tsuki.network.UserAgents
import tsuki.site.vi.moetruyen.imgx.ImgxAccessClient
import tsuki.site.vi.moetruyen.imgx.MemoryCookieJar
import tsuki.site.vi.moetruyen.imgx.ImgxCrypto
import tsuki.site.vi.moetruyen.imgx.ImgxGrant
import tsuki.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("BFANGTEAM", "Moè Truyện (Unofficial)", "vi")
internal class MoeTruyenUnofficial(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.BFANGTEAM, 24) {

	override val configKeyDomain = ConfigKey.Domain("moetruyen.net")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.remove(userAgentKey)
	}

	/**
	 * The IMGX handshake needs the document, the bootstrap and the page-access
	 * calls to share one cookie jar, and that jar must not contain leftovers from
	 * the app's WebView session: the reader-instance token is validated against
	 * the `imgx_document` cookie of the response that produced it. With the shared
	 * jar the server rejects the pairing and answers 5xx, which the app shows as
	 * "Lỗi server (500)".
	 */
	private val imgxCookieJar = MemoryCookieJar()
	private val imgxClient by lazy {
		val logging = Interceptor { chain ->
			val sent = chain.request()
			if (sent.method == "GET") {
				println("[MoeTruyen] doc headers ${sent.headers.names().sorted()}")
			}
			val response = chain.proceed(sent)
			if (response.isSuccessful) {
				response
			} else {
				val body = response.body
				val bytes = body.bytes()
				println(
					"[MoeTruyen] ${chain.request().url.encodedPath.takeLast(40)} -> ${response.code} " +
						String(bytes, Charsets.UTF_8).replace('\n', ' ').take(240),
				)
				response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
			}
		}
		// A bare client on purpose: the app's client injects its own cookies and
		// challenge headers, which makes the reader-instance token disagree with
		// the document cookie and the server answers 500.
		val client = OkHttpClient.Builder()
			.cookieJar(imgxCookieJar)
			.cache(null)
			.followRedirects(true)
			.callTimeout(java.time.Duration.ofSeconds(45))
			.addInterceptor(logging)
			.build()
		OkHttpWebClient(client, source)
	}

	private fun documentHeaders(): Headers = getRequestHeaders().newBuilder()
		.set(CommonHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.set(CommonHeaders.ACCEPT_ENCODING, "identity")
		.set(CommonHeaders.SEC_FETCH_DEST, "document")
		.set(CommonHeaders.SEC_FETCH_MODE, "navigate")
		.set(CommonHeaders.SEC_FETCH_SITE, "same-origin")
		.set(CommonHeaders.SEC_FETCH_USER, "?1")
		.set(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
		.build()

	/**
	 * The IMGX endpoints answer 500 ("Đã xảy ra lỗi hệ thống") to some
	 * header combinations, and which ones are rejected differs per client and
	 * per chapter. Try the exact set the official worker sends first, then the
	 * browser-shaped set, then a Chrome-identity variant.
	 */
	private fun requestHeaderVariants(chapterUrl: String): List<Headers> {
		val base = getRequestHeaders().newBuilder()
			.set(CommonHeaders.ORIGIN, "https://$domain")
			.set(CommonHeaders.REFERER, chapterUrl)
			.build()
		val official = base.newBuilder()
			.set(CommonHeaders.ACCEPT, "application/json")
			.set(CommonHeaders.CONTENT_TYPE, "application/json")
			.set(CommonHeaders.ACCEPT_ENCODING, "identity")
			.build()
		val browser = base.newBuilder()
			.set(CommonHeaders.ACCEPT, "*/*")
			.set(CommonHeaders.ACCEPT_ENCODING, "identity")
			.set(CommonHeaders.SEC_FETCH_DEST, "empty")
			.set(CommonHeaders.SEC_FETCH_MODE, "cors")
			.set(CommonHeaders.SEC_FETCH_SITE, "same-origin")
			.set("Priority", "u=1, i")
			.build()
		val chromeUserAgent = UserAgents.CHROME_MOBILE
		val chrome = browser.newBuilder()
			.set(CommonHeaders.USER_AGENT, chromeUserAgent)
			.set(CommonHeaders.ACCEPT_LANGUAGE, "vi-VN,vi;q=0.9,en;q=0.8")
			.set(CommonHeaders.SEC_CH_UA, "\"Chromium\";v=\"144\", \"Not=A?Brand\";v=\"24\"")
			.set(CommonHeaders.SEC_CH_UA_MOBILE, "?1")
			.set(CommonHeaders.SEC_CH_UA_PLATFORM, "\"Android\"")
			.build()
		return listOf(official, browser, chrome)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = availableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().addPathSegment("manga").apply {
			addQueryParameter("page", page.toString())
			filter.query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
			filter.states.oneOrThrowIfMany()?.let { state ->
				val value = when (state) {
					MangaState.ONGOING -> "Còn tiếp"
					MangaState.FINISHED -> "Hoàn thành"
					MangaState.PAUSED -> "Tạm dừng"
					else -> null
				}
				value?.let { addQueryParameter("status", it) }
			}
			filter.tags.forEach { addQueryParameter("include", it.key) }
			filter.tagsExclude.forEach { addQueryParameter("exclude", it.key) }
			addQueryParameter(
				"sort",
				when (order) {
					SortOrder.ALPHABETICAL -> "title_asc"
					SortOrder.POPULARITY -> "views_desc"
					else -> "updated_desc"
				},
			)
		}
		return parseMangaList(webClient.httpGet(url.build()).parseHtml())
	}

	private fun parseMangaList(doc: Document): List<Manga> =
		doc.select("article.manga-card").mapNotNull { card ->
			val link = card.selectFirst("a[href^=/manga/]") ?: return@mapNotNull null
			val relativeUrl = link.attrAsRelativeUrl("href")
			val mangaId = relativeUrl.substringAfter("/manga/").substringBefore('-').toLongOrNull()
			val title = card.selectFirst("h3")?.attrOrNull("title")
				?: card.selectFirst("h3")?.textOrNull()
				?: return@mapNotNull null
			Manga(
				id = mangaId?.let(::generateUid) ?: generateUid(relativeUrl),
				url = relativeUrl,
				publicUrl = relativeUrl.toAbsoluteUrl(domain),
				title = title,
				altTitles = emptySet(),
				coverUrl = card.selectFirst("img")?.src(),
				largeCoverUrl = null,
				authors = emptySet(),
				tags = card.select(".manga-hover-popup__genre").mapToSet {
					MangaTag(it.text(), it.text(), source)
				},
				state = parseState(card.selectFirst(".manga-badge")?.text()),
				description = card.selectFirst(".manga-hover-popup__description")?.textOrNull(),
				contentRating = null,
				source = source,
				rating = RATING_UNKNOWN,
			)
		}

	override suspend fun getDetails(manga: Manga): Manga {
		val relativeUrl = resolveMangaUrl(manga)
		val firstDoc = webClient.httpGet(relativeUrl.toAbsoluteUrl(domain)).parseHtml()
		val chapterDocs = mutableListOf(firstDoc)
		val lastPage = firstDoc.select("a[href*=chapterPage]")
			.mapNotNull { CHAPTER_PAGE_REGEX.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
			.maxOrNull() ?: 1
		for (page in 2..lastPage) {
			// urlBuilder(String) expects a host, not a complete URL. Passing the
			// absolute manga URL made OkHttp reject it as an "unexpected host".
			val pageUrl = "${relativeUrl.toAbsoluteUrl(domain)}?chapterPage=$page"
			// The site rate-limits burst document requests and may answer 500.
			delay(350)
			chapterDocs += webClient.httpGet(pageUrl).parseHtml()
		}

		val chapters = chapterDocs.flatMap { it.select("a.chapter-link") }
			.distinctBy { it.attr("data-chapter-id").ifBlank { it.attr("href") } }
			.mapChapters(reversed = true) { index, a -> parseChapter(a, index) }

		return manga.copy(
			url = relativeUrl,
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			title = firstDoc.selectFirst("h1.manga-detail-title")?.textOrNull() ?: manga.title,
			altTitles = firstDoc.select(".manga-detail-meta-line--names .manga-detail-meta-value")
				.mapToSet { it.text() }.ifEmpty { manga.altTitles },
			coverUrl = firstDoc.selectFirst(".detail-cover img")?.src() ?: manga.coverUrl,
			authors = firstDoc.select(".manga-detail-meta-line--author a").mapToSet { it.text() },
			tags = firstDoc.select(".manga-detail-genre-chips a").mapToSet {
				val key = it.attr("href").substringAfter("include=", it.text())
				MangaTag(it.text(), key, source)
			},
			state = parseState(firstDoc.selectFirst(".manga-status-pill")?.text()),
			description = firstDoc.selectFirst("[data-description-content]")?.html(),
			chapters = chapters,
		)
	}

	private fun parseChapter(a: Element, index: Int): MangaChapter {
		val relativeUrl = a.attrAsRelativeUrl("href")
		val number = a.attr("data-chapter-number").toFloatOrNull() ?: (index + 1f)
		val dateText = a.selectFirst(".chapter-time")?.attrOrNull("title")
			?.substringAfter("Cập nhật ")
		return MangaChapter(
			id = generateUid(a.attr("data-chapter-id").ifBlank { relativeUrl }),
			title = a.selectFirst(".chapter-title")?.textOrNull(),
			number = number,
			volume = 0,
			url = relativeUrl,
			scanlator = a.selectFirst(".chapter-subrow .chapter-sub-text:not(.chapter-sub-metric)")?.textOrNull(),
			uploadDate = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).parseSafe(dateText),
			branch = null,
			source = source,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = imgxMutex.withLock {
		val chapterUrl = normalizeSitePath(chapter.url).toAbsoluteUrl(domain)
		pageCache[chapterUrl]?.let { return@withLock it }
		var lastError: Throwable? = null
		val variants = requestHeaderVariants(chapterUrl)
		repeat(2) { round ->
			try {
				imgxCookieJar.clear()
				val html = imgxClient.httpGet(chapterUrl, documentHeaders()).parseRaw()
				val document = Jsoup.parse(html, chapterUrl)
				println(
					"[MoeTruyen] round $round ${chapterUrl.substringAfter("$domain")} doc=${html.length}B " +
						"cookies=" + imgxCookieJar.loadForRequest(chapterUrl.toHttpUrl()).joinToString(",") { it.name } +
						" token=" + html.contains("reader-instance"),
				)
				val media = ImgxAccessClient.encryptedMedia(document)
				val totalPages = document.selectFirst("[data-reader-lazy-pages]")
					?.attr("data-reader-total-pages")?.toIntOrNull() ?: 0
				for (variant in variants.indices) {
					val pages = try {
						ImgxAccessClient(
							imgxClient, "https://$domain", chapterUrl, document, variants, variant,
						).fetchPages(media, totalPages)
					} catch (e: HttpStatusException) {
						lastError = e
						println("[MoeTruyen] variant $variant HTTP ${e.statusCode}")
						continue
					}
					val usable = pages
						.filter { it.storageKey.startsWith("chapters/") && !it.downloadUrl.substringBefore('?').endsWith("/0.js") }
						.sortedBy { it.pageIndex }
					if (usable.isEmpty()) continue
					println("[MoeTruyen] variant $variant ok, ${usable.size} pages")
					synchronized(imgxGrants) {
						usable.forEach { page ->
							val grant = page.grant ?: return@synchronized
							imgxGrants[page.downloadUrl] = grant to page.storageKey
						}
					}
					return@withLock usable.map { page ->
						MangaPage(generateUid(page.downloadUrl), page.downloadUrl, null, source)
					}.also { pageCache[chapterUrl] = it }
				}
				delay(700L * (round + 1))
			} catch (e: HttpStatusException) {
				lastError = e
				println("[MoeTruyen] round $round document HTTP ${e.statusCode}")
				if (e.statusCode !in setOf(403, 429, 500, 502, 503, 504)) throw e
				delay(700L * (round + 1))
			}
		}
		// Native handshake is refused by the server for this chapter: fall back to
		// plain page URLs when the reader exposes them, otherwise hand the chapter
		// to the official web reader instead of failing the reader screen.
		val fallbackDocument = runCatching {
			Jsoup.parse(imgxClient.httpGet(chapterUrl, documentHeaders()).parseRaw(), chapterUrl)
		}.getOrNull()
		val plain = fallbackDocument?.select("img.page-media")
			?.mapNotNull { img -> img.attr("data-src").ifBlank { img.attr("src") } }
			.orEmpty()
			.filter { it.startsWith("http") && !it.substringBefore('?').endsWith(".js") }
			.distinct()
		if (plain.isNotEmpty()) {
			println("[MoeTruyen] IMGX refused, using ${plain.size} plain pages")
			return@withLock plain.map { MangaPage(generateUid(it), it, null, source) }
				.also { pageCache[chapterUrl] = it }
		}
		println("[MoeTruyen] IMGX refused, opening web reader: ${lastError?.message}")
		context.requestBrowserAction(this, chapterUrl)
		throw ParseException("Chương này cần đọc bằng trình đọc web của Moè", chapter.url)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val grantEntry = synchronized(imgxGrants) { imgxGrants[request.url.toString()] }
			?: return super.intercept(chain)
		val response = chain.proceed(request.newBuilder().header(CommonHeaders.ACCEPT_ENCODING, "identity").build())
		if (!response.isSuccessful) {
			println("[MoeTruyen] image HTTP ${response.code} ${request.url.toString().takeLast(48)}")
		}
		val encrypted = response.body.use { it.bytes() }
		if (encrypted.size <= 13 || !encrypted.copyOfRange(0, 4).contentEquals(
			byteArrayOf(0x49.toByte(), 0x4D.toByte(), 0x47.toByte(), 0x58.toByte()),
		)) {
			return response.newBuilder().body(encrypted.toResponseBody(response.body.contentType())).build()
		}
		val webp = ImgxCrypto.decodeProtectedPage(encrypted, grantEntry.first, grantEntry.second)
		return response.newBuilder()
			.removeHeader(CommonHeaders.CONTENT_ENCODING)
			.header(CommonHeaders.CONTENT_TYPE, "image/webp")
			.header(CommonHeaders.CONTENT_LENGTH, webp.size.toString())
			.header(CommonHeaders.CACHE_CONTROL, "no-cache, no-store")
			.body(webp.toResponseBody("image/webp".toMediaType()))
			.build()
	}

	private suspend fun resolveMangaUrl(manga: Manga): String {
		val normalized = normalizeSitePath(manga.url)
		if (normalized.startsWith("/manga/") &&
			normalized.removePrefix("/manga/").substringBefore('/').toLongOrNull() == null
		) return normalized
		val oldId = normalized.removePrefix("/manga/").substringBefore('/').toLongOrNull()
			?: return normalized
		val url = urlBuilder().addPathSegment("search")
			.addQueryParameter("q", oldId.toString())
			.addQueryParameter("limit", "20").build()
		val headers = getRequestHeaders().newBuilder().set("Accept", "application/json").build()
		val items = webClient.httpGet(url, headers).parseJson().getJSONArray("items")
		for (i in 0 until items.length()) {
			val item = items.getJSONObject(i)
			if (item.optLong("id") == oldId) return "/manga/${item.getString("slug")}"
		}
		throw ParseException("Không tìm được slug mới cho ${manga.title}", manga.url)
	}

	private fun normalizeSitePath(value: String): String {
		val raw = value.trim()
		val absolute = ABSOLUTE_URL_REGEX.matchEntire(raw)
		val path = if (absolute != null) {
			val host = absolute.groupValues[1]
			if (!host.equals(domain, ignoreCase = true)) {
				throw ParseException("URL không thuộc $domain", value)
			}
			absolute.groupValues[2]
		} else {
			raw
		}
		val clean = path.substringBefore('#').substringBefore('?').trim()
			.replace(Regex("\\s+"), "-")
			.replace(Regex("-{2,}"), "-")
		return when {
			clean.startsWith('/') -> clean
			clean.startsWith("manga/") -> "/$clean"
			else -> clean
		}
	}

	private suspend fun availableTags(): Set<MangaTag> =
		webClient.httpGet("https://$domain/manga").parseHtml()
			.select("button[data-genre]").mapToSet {
				MangaTag(
					title = it.selectFirst(".filter-name")?.text() ?: it.text(),
					key = it.attr("data-genre"),
					source = source,
				)
			}

	private fun parseState(value: String?): MangaState? = when (value?.trim()?.lowercase(Locale.ROOT)) {
		"còn tiếp" -> MangaState.ONGOING
		"hoàn thành" -> MangaState.FINISHED
		"tạm dừng" -> MangaState.PAUSED
		else -> null
	}

	private companion object {
		// Usagi creates more than one parser instance. The IMGX document cookie
		// permits only one active reader-instance, so synchronization must be
		// shared across instances rather than stored on a parser object.
		val imgxMutex = Mutex()
		val imgxGrants = object : LinkedHashMap<String, Pair<ImgxGrant, String>>(100, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ImgxGrant, String>>?): Boolean = size > 100
		}
		val pageCache = object : LinkedHashMap<String, List<MangaPage>>(12, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MangaPage>>?): Boolean = size > 12
		}
		val ABSOLUTE_URL_REGEX = Regex("https?://([^/]+)(/.*)?", RegexOption.IGNORE_CASE)
		val CHAPTER_PAGE_REGEX = Regex("[?&]chapterPage=(\\d+)")
	}
}
