package tsuki.site.vi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.IOException
import org.json.JSONArray
import org.json.JSONObject
import tsuki.MangaLoaderContext
import tsuki.MangaParserAuthProvider
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.TooManyRequestExceptions
import tsuki.model.*
import tsuki.network.CloudFlareHelper
import tsuki.network.CommonHeaders
import tsuki.network.OkHttpWebClient
import tsuki.util.*
import tsuki.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * LXManga (https://lxmanga.top) — Vietnamese hentai manga reader.
 *
 * The site sits behind Cloudflare "Under Attack" mode, so every request
 * needs a `cf_clearance` cookie before it is answered.
 *
 * ## How this parser gets one
 *
 * The host app already has the whole Cloudflare story built in, and
 * this parser simply rides on it — the same way [CuuTruyenParser] and
 * every other Cloudflare-fronted source here does:
 *
 *  * `CloudFlareInterceptor` (installed on the HTTP client every parser
 *    inherits) turns a "Just a moment…" response into a
 *    `CloudFlareProtectedException`;
 *  * the app's exception resolver opens `CloudFlareActivity`, the user
 *    clears the challenge, the fresh `cf_clearance` lands in the app's
 *    cookie jar — which is `AndroidCookieJar`, i.e.
 *    `android.webkit.CookieManager` — and the original request is
 *    retried with it attached.
 *
 * Because that jar is shared, the parser never copies cookies around:
 * [isAuthorized] just asks it whether a clearance exists.
 *
 * ## The "Đăng nhập" row is the captcha entry point
 *
 * The site has no accounts. The host app renders the source settings
 * "Đăng nhập" row only when the parser implements
 * [MangaParserAuthProvider] with a non-empty [authUrl], and that row is
 * the only way to open the challenge on demand, so we point it at the
 * homepage (Cloudflare-gated, so it renders the challenge immediately).
 *
 * ## Chapter reader
 *
 * The current reader exposes an encrypted Livewire manifest. [getPages]
 * extracts its component and manifest key, obtains the per-chapter action
 * token from `/get_token`, fires `lxViewerImagesUnlock`, and decrypts the
 * `lx:srcs` dispatch natively. The token travels with each page URL as a
 * fragment and [intercept] converts it into the CDN's `Token` header.
 * When the server asks for Turnstile verification, the app opens the
 * chapter in its browser and retries after that shared session is verified.
 */
@MangaSourceParser("LXMANGA", "LXManga", "vi", type = ContentType.HENTAI)
internal class LxManga(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.LXMANGA, 60), MangaParserAuthProvider {

	/** The homepage — it is Cloudflare-gated, so opening it renders the challenge. */
	private val baseUrl: String get() = "https://$domain/"

	private val tagsLazy = suspendLazy(initializer = { fetchAvailableTags() })

	override val configKeyDomain = ConfigKey.Domain("lxmanga.top")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// `userAgentKey` is intentionally left at its default: the app's own
	// WebView user agent. That is the identity the challenge screens
	// present, and a `cf_clearance` is only honoured for the identity it
	// was issued to. The markup is identical for a mobile user agent
	// (verified against the live site), so nothing is lost.

	// ============================== Auth ===============================

	override val authUrl: String get() = baseUrl

	/**
	 * True as soon as the jar holds a `cf_clearance` for the domain.
	 *
	 * On Usagi the jar is `AndroidCookieJar`, which reads through
	 * `android.webkit.CookieManager`, so the HttpOnly clearance cookie
	 * written by the challenge WebView is visible here — that is why
	 * this check works even though `document.cookie` would not show it.
	 * The same signal drives the row's enabled state and lets
	 * `SourceAuthActivity` close itself once the user is through.
	 */
	override suspend fun isAuthorized(): Boolean {
		// The reader gate is a server-side flag on the Laravel session, not a
		// `cf_clearance`. A chapter that already had its Turnstile solved keeps
		// working as long as `lxmanga_session` is present, so that is the signal
		// the settings row should use.
		val session = runCatching {
			context.cookieJar.getCookies(domain).any { it.name == SESSION_COOKIE }
		}.getOrDefault(false)
		log("isAuthorized=$session cookies=[${cookieNames()}]")
		return session
	}

	/** No account system on this site, so there is no name to show. */
	override suspend fun getUsername(): String? = null

	// ============================== Client ===============================

	/**
	 * OkHttp is used for everything except the chapter image list. Image
	 * URLs are emitted by the obfuscated builder as raw `s*.lxmanga.xyz`
	 * paths with no token in the URL — the CDN validates Referer/Origin
	 * instead, which [getRequestHeaders] supplies.
	 */
	override val webClient = OkHttpWebClient(
		context.httpClient.newBuilder()
			.callTimeout(45.seconds)
			.rateLimit(10, 1.seconds)
			.build(),
		source,
	)

	/**
	 * Headers for every request we make — catalogue, details, and the
	 * images the app loads with them.
	 *
	 * The interesting part is the browser identity. Cloudflare decides
	 * whether a `cf_clearance` is valid for *this* client, and the
	 * client it recorded when the challenge was solved was the app's
	 * WebView. So we present exactly that identity: the WebView's user
	 * agent (see [userAgentKey]) **plus** the client hints and
	 * `Accept-Language` that go with it. A request that claims to be
	 * Chrome on Android but sends no `Sec-Ch-Ua` at all is a mismatch
	 * Cloudflare can see, and this parser used to send a desktop
	 * Windows UA — the opposite of what the challenge screens use.
	 *
	 * `Sec-Fetch-*` deliberately stays out: its values depend on the
	 * request type (a document navigation and an image differ), and a
	 * wrong one would be worse than none.
	 */
	override fun getRequestHeaders(): Headers {
		val userAgent = config[userAgentKey]
		return Headers.Builder()
			.add(CommonHeaders.REFERER, "https://$domain/")
			.add(CommonHeaders.ORIGIN, "https://$domain")
			.add(CommonHeaders.USER_AGENT, userAgent)
			.add(CommonHeaders.ACCEPT_LANGUAGE, acceptLanguage())
			.add(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
			.apply { clientHints(userAgent)?.let { addAll(it) } }
			.build()
	}

	/** e.g. `vi-VN,vi;q=0.9,en-US;q=0.8` — what the WebView would send. */
	private fun acceptLanguage(): String = runCatching {
		val tags = context.getPreferredLocales().take(3)
			.map { it.toLanguageTag().ifEmpty { it.language } }
			.distinct()
		if (tags.isEmpty()) return@runCatching DEFAULT_ACCEPT_LANGUAGE
		tags.mapIndexed { index, tag ->
			when (index) {
				0 -> tag
				1 -> "$tag;q=0.9"
				else -> "$tag;q=0.8"
			}
		}.joinToString(",")
	}.getOrDefault(DEFAULT_ACCEPT_LANGUAGE)

	/** Client hints matching [userAgent], as the WebView sends them. */
	private fun clientHints(userAgent: String): Headers? {
		val version = Regex("""Chrome/(\d+)""").find(userAgent)?.groupValues?.get(1) ?: return null
		return Headers.Builder()
			.add(CommonHeaders.SEC_CH_UA, "\"Chromium\";v=\"$version\", \"Not=A?Brand\";v=\"24\"")
			.add(CommonHeaders.SEC_CH_UA_MOBILE, if (userAgent.contains("Mobile")) "?1" else "?0")
			.add(
				CommonHeaders.SEC_CH_UA_PLATFORM,
				if (userAgent.contains("Android")) "\"Android\"" else "\"Linux\"",
			)
			.build()
	}

	// ============================== List ===============================

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		// Cached: the app asks for the filter options every time the sheet
		// opens, and /the-loai is a heavyweight page.
		availableTags = tagsLazy.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildListUrl(page, order, filter)
		val doc = fetchDocument(url)
		// Each `manga-vertical` is one catalogue card. Avoid selecting its
		// nested `.relative` blocks, which used to duplicate every result.
		return doc.select("div.manga-vertical").mapNotNull { div ->
			val href = div.selectFirst("a[href^=/truyen/]")?.attrAsRelativeUrl("href")
				?: return@mapNotNull null
			val coverUrl = div.selectFirst("div.cover")?.let { cover ->
				cover.attrOrNull("data-bg")
					?: cover.attr("style").cssUrl()
			}?.orEmpty()

			Manga(
				id = generateUid(href),
				title = div.select("div.p-2 a.text-ellipsis").text(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = coverUrl,
				tags = setOf(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}.distinctBy { it.url }
	}

	// ============================== Details ===============================

	override suspend fun getDetails(manga: Manga): Manga {
		val root = fetchDocument(manga.url.toAbsoluteUrl(domain))
		val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("GMT+7")
		}
		val author = root.selectFirst("div.mt-2:contains(Tác giả) span a")?.textOrNull()
		val altTitles = root.selectFirst("div.grow div:contains(Tên khác)")
			?.select("span a")?.mapToSet { it.text() }
			?: emptySet()
		val scanlator = root.selectFirst("div.mt-2:has(span:first-child:contains(Thực hiện:)) span:last-child")
			?.textOrNull()

		return manga.copy(
			altTitles = altTitles,
			state = when (root.selectFirst("div.mt-2:contains(Tình trạng) span.text-blue-500")?.text()) {
				"Đang tiến hành" -> MangaState.ONGOING
				"Đã hoàn thành" -> MangaState.FINISHED
				else -> null
			},
			tags = root.selectFirst("div.mt-2:contains(Thể loại)")?.select("a.bg-gray-500")
				?.mapToSet { a ->
					MangaTag(
						key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
						title = a.text(),
						source = source,
					)
				} ?: emptySet(),
			authors = setOfNotNull(author),
			description = root.selectFirst("meta[name=description]")?.attrOrNull("content"),
			chapters = root.select("div.justify-between ul.overflow-y-auto.overflow-x-hidden a")
				.mapChapters(reversed = true) { i, a ->
					val href = a.attrAsRelativeUrl("href")
					val name = a.selectFirst("span.text-ellipsis")?.text().orEmpty()
					val dateText = a.parent()?.selectFirst("span.timeago")
						?.attr("datetime")
						?.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
						.orEmpty()

					MangaChapter(
						id = generateUid(href),
						title = name,
						number = (i + 1).toFloat(),
						volume = 0,
						url = href,
						scanlator = scanlator,
						uploadDate = chapterDateFormat.parseSafe(dateText),
						branch = null,
						source = source,
					)
				},
		)
	}

	// ============================== Pages ===============================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		pageCache[chapterUrl]?.let {
			log("getPages: cache hit ${chapterUrl.takeLast(40)} (${it.size} pages)")
			return it
		}

		val pages = try {
			// The site answers HTTP 429 to bursts and states its own retry delay in
			// `Retry-After`; Tsuki surfaces that as TooManyRequestExceptions. Waiting
			// it out is the difference between "works" and "open the browser once per
			// chapter", because opening a chapter costs 3 requests back to back.
			withRetryOn429 {
				val html = webClient.httpGet(chapterUrl, chapterDocumentHeaders()).parseRaw()
				val manifestKey = extractManifestKey(html)
					?: throw IOException("LXManga: không giải mã được khóa viewer")
				val component = findViewerComponent(html)
					?: throw IOException("LXManga: không tìm thấy component ảnh")
				val csrf = CSRF_REGEX.find(html)?.groupValues?.get(1).orEmpty()
				val kid = component.getJSONObject("serverMemo")
					.optJSONObject("data")?.optString("manifestKid").orEmpty()
				val actionToken = getActionToken(chapterUrl, csrf, kid, manifestKey)
				if (actionToken == null) {
					log("getPages: verification required for ${chapterUrl.takeLast(50)}")
					throw VerificationRequiredException()
				}
				val urls = fetchImageManifest(chapterUrl, csrf, component, actionToken, manifestKey)
				if (urls.isEmpty()) throw IOException("LXManga: manifest ảnh trống")
				val encodedToken = context.encodeBase64(actionToken.toByteArray())
				urls.map { url ->
					rememberToken(url, actionToken)
					val pageUrl = url.toHttpUrl().newBuilder()
						.fragment("$TOKEN_MARKER$encodedToken")
						.build()
						.toString()
					MangaPage(generateUid(url), pageUrl, null, source)
				}
			}
		} catch (_: VerificationRequiredException) {
			context.requestBrowserAction(this, chapterUrl)
		}

		if (pageCache.size >= PAGE_CACHE_LIMIT) {
			pageCache.remove(pageCache.keys.firstOrNull())
		}
		pageCache[chapterUrl] = pages
		log("getPages: native ok ${chapterUrl.takeLast(40)} (${pages.size} pages)")
		return pages
	}

	/** Wait out the site's own `Retry-After` instead of failing the chapter. */
	/** Keeps a URL -> token map as a fallback when the page fragment is dropped. */
	private fun rememberToken(url: String, token: String) {
		if (imageTokens.size > TOKEN_MAP_LIMIT) {
			imageTokens.clear()
		}
		imageTokens[url] = token
	}

	private suspend fun <T> withRetryOn429(block: suspend () -> T): T {
		var attempt = 0
		while (true) {
			try {
				return block()
			} catch (e: TooManyRequestExceptions) {
				attempt++
				val wait = e.retryAt?.let { at ->
					java.time.Duration.between(java.time.Instant.now(), at).toMillis()
				}?.let { if (it in 1L..15_000L) it + 250L else 1_500L } ?: 1_500L
				log("getPages: HTTP 429 on ${e.url.takeLast(40)}, waiting ${wait}ms (try $attempt)")
				if (attempt >= MAX_429_RETRIES) throw e
				delay(wait)
			}
		}
	}

	/** Browser-navigation headers for the chapter document itself. */
	private fun chapterDocumentHeaders(): Headers = getRequestHeaders().newBuilder()
		.set(CommonHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.set("Sec-Fetch-Dest", "document")
		.set("Sec-Fetch-Mode", "navigate")
		.set("Sec-Fetch-Site", "same-origin")
		.set("Sec-Fetch-User", "?1")
		.set("Cache-Control", "max-age=0")
		.build()

	private fun findViewerComponent(html: String): JSONObject? = WIRE_DATA_REGEX.findAll(html)
		.mapNotNull { match ->
			runCatching { JSONObject(org.jsoup.parser.Parser.unescapeEntities(match.groupValues[1], false)) }.getOrNull()
		}
		.firstOrNull { it.optJSONObject("fingerprint")?.optString("name") == "chapter-viewer-images" }

	private fun extractManifestKey(html: String): String? {
		var candidates = listOf(html)
		repeat(4) {
			val next = mutableListOf<String>()
			for (text in candidates) {
				MF_KEY_REGEX.find(text)?.let { return it.groupValues[1].replace("\\/", "/") }
				ARRAY_START_REGEX.findAll(text).forEach { match ->
					val end = text.indexOf(']', match.range.last + 1)
					if (end < 0) return@forEach
					val raw = text.substring(match.range.last + 1, end)
					if (raw.any { it !in "0123456789abcdefABCDEF\"', \t\r\n" }) return@forEach
					val hex = HEX_REGEX.findAll(raw).joinToString("") { it.value }
					decodeHexLayer(hex)?.let(next::add)
				}
			}
			if (next.isEmpty()) return null
			candidates = next
		}
		return null
	}

	private fun decodeHexLayer(hex: String): String? = runCatching {
		if (hex.length < 66 || hex.length % 2 != 0) return null
		val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
		String(ByteArray(bytes.size - 32) { i -> (bytes[i + 32].toInt() xor bytes[i % 32].toInt()).toByte() })
	}.getOrNull()

	private suspend fun getActionToken(chapterUrl: String, csrf: String, kid: String, key: String): String? {
		val headers = Headers.Builder()
			.add("Accept", "application/json")
			.add("Content-Type", "application/json")
			.add("X-CSRF-TOKEN", csrf)
			.add(CommonHeaders.REFERER, chapterUrl)
			.add(CommonHeaders.ORIGIN, "https://$domain")
			.build()
		val url = "https://$domain/get_token".toHttpUrl().newBuilder()
			.apply { if (kid.isNotEmpty()) addQueryParameter("kid", kid) }
			.build()
		val response = webClient.httpGet(url, headers)
		val body = response.body.string()
		val code = response.code
		response.close()
		log("get_token kid=${kid.take(8)} → HTTP $code ${body.take(120)}")
		if (code !in 200..299) return null
		val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
		if (json.optBoolean("is_bot", true)) return null
		return json.optString("action_token").takeIf { it.isNotEmpty() }
			?: json.optString("e").takeIf { it.isNotEmpty() }?.let { encrypted ->
				xorBase64(encrypted, key)?.let { JSONObject(it).optString("at") }?.takeIf { it.isNotEmpty() }
			}
	}

	private suspend fun fetchImageManifest(
		chapterUrl: String,
		csrf: String,
		component: JSONObject,
		actionToken: String,
		key: String,
	): List<String> {
		val payload = JSONObject()
			.put("fingerprint", component.getJSONObject("fingerprint"))
			.put("serverMemo", component.getJSONObject("serverMemo"))
			.put("updates", JSONArray().put(JSONObject()
				.put("type", "fireEvent")
				.put("payload", JSONObject()
					.put("id", randomId())
					.put("event", "lxViewerImagesUnlock")
					.put("params", JSONArray().put(actionToken)))))
		val headers = Headers.Builder()
			.add("Accept", "text/html, application/xhtml+xml")
			.add("Content-Type", "application/json")
			.add("X-Livewire", "true")
			.add("X-CSRF-TOKEN", csrf)
			.add(CommonHeaders.REFERER, chapterUrl)
			.add(CommonHeaders.ORIGIN, "https://$domain")
			.build()
		val json = webClient.httpPost("https://$domain/livewire/message/chapter-viewer-images".toHttpUrl(), payload, headers).parseJson()
		val dispatches = json.optJSONObject("effects")?.optJSONArray("dispatches") ?: return emptyList()
		for (i in 0 until dispatches.length()) {
			val item = dispatches.optJSONObject(i) ?: continue
			if (item.optString("event") != "lx:srcs") continue
			val encrypted = item.optJSONObject("data")?.optString("data").orEmpty()
			val manifest = xorBase64(encrypted, key)?.let { JSONObject(it) } ?: continue
			val urls = manifest.optJSONArray("u") ?: continue
			val flags = manifest.optJSONArray("f")
			return (0 until urls.length()).mapNotNull { index ->
				urls.optString(index).takeIf { it.isNotEmpty() && ((flags?.optInt(index, 0) ?: 0) and 1 == 0) }
			}
		}
		return emptyList()
	}

	private fun xorBase64(payload: String, key: String): String? = runCatching {
		val bytes = context.decodeBase64(payload)
		val keyBytes = key.toByteArray()
		String(ByteArray(bytes.size) { i -> (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte() })
	}.getOrNull()

	private fun randomId(): String = (1..7).map { ID_CHARS.random() }.joinToString("")

	private class VerificationRequiredException : Exception()

	override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
		val request = chain.request()
		val cleanUrl = request.url.newBuilder().fragment(null).build()
		val marker = request.url.fragment?.takeIf { it.startsWith(TOKEN_MARKER) }

		val token = marker?.let {
			runCatching { String(context.decodeBase64(it.removePrefix(TOKEN_MARKER))) }.getOrNull()
		} ?: imageTokens[cleanUrl.toString()] ?: imageTokens[request.url.toString()]

		if (token == null) {
			if (request.url.host.endsWith(CDN_SUFFIX)) {
				log("intercept: no token for ${cleanUrl.toString().takeLast(48)}")
			}
			return chain.proceed(request)
		}
		if (marker == null) {
			log("intercept: token restored from map for ${cleanUrl.host}")
		}
		val updated = request.newBuilder()
			.url(cleanUrl)
			.header("Token", token.ifEmpty { "x" })
			.header(CommonHeaders.REFERER, "https://$domain/")
			.header(CommonHeaders.ORIGIN, "https://$domain")
			.build()
		return chain.proceed(updated)
	}

	// ============================== Tags ===============================

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = fetchDocument("https://$domain/tim-kiem")
		return doc.select("label").mapNotNull { label ->
			val key = GENRE_SLUG_REGEX.matchEntire(label.attr("@click"))
				?.groupValues?.get(1)
				?: return@mapNotNull null
			val title = label.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
			MangaTag(key = key, title = title, source = source)
		}.toSet()
	}

	// ============================== Helpers ===============================

	/** Build the current unified catalogue/search URL. */
	private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String =
		"https://$domain/tim-kiem".toHttpUrl().newBuilder()
			.addQueryParameter("sort", sortQuery(order))
			.addQueryParameter("page", page.toString())
			.apply {
				filter.query?.takeIf { it.isNotBlank() }
					?.let { addQueryParameter("filter[name]", it) }
				if (filter.tags.isNotEmpty()) {
					addQueryParameter("filter[accept_genres]", filter.tags.joinToString(",") { it.key })
				}
				val states = filter.states.mapNotNull {
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "paused"
						else -> null
					}
				}
				addQueryParameter(
					"filter[status]",
					states.takeIf { it.isNotEmpty() }?.joinToString(",")
						?: "ongoing,completed,paused",
				)
			}
			.build()
			.toString()

	/**
	 * Fetch one of the static HTML pages over plain OkHttp — the
	 * [CuuTruyenParser] pattern.
	 *
	 * On Usagi the inherited `CloudFlareInterceptor` throws
	 * `CloudFlareProtectedException` as soon as it sees a "Just a
	 * moment…" body, and the app opens its captcha screen, stores the
	 * fresh clearance in the shared jar and retries. The explicit check
	 * below covers hosts whose HTTP client has no such interceptor: it
	 * hands the URL to [MangaLoaderContext.requestBrowserAction], which
	 * throws so the app can show a browser for the user to solve it in.
	 */
	private suspend fun fetchDocument(url: String): org.jsoup.nodes.Document {
		val response = webClient.httpGet(url)
		val protection = CloudFlareHelper.checkResponseForProtection(response.copy())
		if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
			response.close()
			log("plain response is a Cloudflare challenge ($url, protection=$protection)")
			context.requestBrowserAction(this, url)
		}
		return response.parseHtml()
	}

	private fun sortQuery(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "-views"
		SortOrder.NEWEST -> "-created_at"
		SortOrder.ALPHABETICAL -> "name"
		SortOrder.ALPHABETICAL_DESC -> "-name"
		else -> "-updated_at"
	}

	private fun cookieNames(): String = runCatching {
		context.cookieJar.getCookies(domain).joinToString(",") { it.name }
	}.getOrDefault("?")

	private fun log(message: String) {
		println("$LOG_TAG $message")
	}

	companion object {
		private const val LOG_TAG = "[LxManga]"
		private const val DEFAULT_ACCEPT_LANGUAGE = "vi-VN,vi;q=0.9,en;q=0.8"
		private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
		private const val TOKEN_MARKER = "LXMANGA_TOKEN="
		private const val PAGE_CACHE_LIMIT = 50
		private const val SESSION_COOKIE = "lxmanga_session"
		private const val MAX_429_RETRIES = 5
		private const val TOKEN_MAP_LIMIT = 400
		private const val CDN_SUFFIX = "lxmanga.xyz"

		private val imageTokens = java.util.concurrent.ConcurrentHashMap<String, String>()

		private val pageCache = LinkedHashMap<String, List<MangaPage>>()

		private val GENRE_SLUG_REGEX = Regex("""toggleGenre\('([^']+)'\)""")
		private val CSRF_REGEX = Regex("""var\s+csrf_token\s*=\s*'([^']+)'""")
		private val WIRE_DATA_REGEX = Regex("""wire:initial-data="((?:[^"\\]|\\.)*)"""")
		private val MF_KEY_REGEX = Regex("""__lxMfkey\s*=\s*"([^"]+)"""")
		private val ARRAY_START_REGEX = Regex("""var\s+_\w+\s*=\s*\[""")
		private val HEX_REGEX = Regex("""[0-9a-fA-F]+""")
	}
}
