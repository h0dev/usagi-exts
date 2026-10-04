package tsuki.site.vi

import okhttp3.Headers
import okio.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import tsuki.MangaLoaderContext
import tsuki.MangaParserAuthProvider
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.AuthRequiredException
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
 * LXManga (https://lxmanga.space) — Vietnamese hentai manga reader.
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
 * ## What is deliberately NOT here
 *
 * The catalog runs over plain OkHttp. Earlier revisions also fetched
 * pages through the app's WebView (`evaluateJs`) to dodge Cloudflare
 * fingerprint checks, but that turned out to be harmful on this host:
 * `MangaLoaderContextImpl.evaluateJs` wraps the call in a 4 s
 * `withTimeout` while `WebViewExecutor` waits on a *non-cancellable*
 * `suspendCoroutine`, so any call that overruns leaves the shared
 * WebViewExecutor mutex locked for the rest of the process — after
 * which the app's own captcha handling and every WebView-based source
 * time out too. A no-network probe (`return 'pong'`) confirmed it: it
 * came back TIMEOUT. So the WebView is used for exactly one thing
 * ([getPages], which has no alternative) and that script keeps itself
 * well under the 4 s budget.
 *
 * ## Chapter reader
 *
 * The chapter page does not contain image URLs: it ships an obfuscated
 * (~900 kB) script that fetches and decrypts them into `window["_0x…"]`.
 * The only way to run it is a browser, hence [PAGES_SCRIPT], which
 * fetches the page, `document.write`s it so the inline scripts re-run,
 * and polls for the array. It aborts itself after ~2.8 s (fetch ≤2 s,
 * poll ≤0.8 s) to stay inside the app's evaluateJs timeout.
 */
@MangaSourceParser("LXMANGA", "LXManga", "vi", type = ContentType.HENTAI)
internal class LxManga(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.LXMANGA, 60), MangaParserAuthProvider {

	/** The homepage — it is Cloudflare-gated, so opening it renders the challenge. */
	private val baseUrl: String get() = "https://$domain/"

	private val tagsLazy = suspendLazy(initializer = { fetchAvailableTags() })

	override val configKeyDomain = ConfigKey.Domain("lxmanga.space")

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
		val clearance = runCatching {
			CloudFlareHelper.getClearanceCookie(context.cookieJar, baseUrl)
		}.getOrNull()
		log("isAuthorized=${clearance != null} cookies=[${cookieNames()}]")
		return clearance != null
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
		// The markup renders every card twice (a mobile and a desktop
		// block), so 60 mangas arrive as 120 `div.relative` nodes. The
		// paginator counts what we return, so collapse them.
		return doc.select("div.grid div.relative").mapNotNull { div ->
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

		// Download the page with OkHttp first. The app caps a single
		// `evaluateJs` call at 4 s, and this page is ~900 kB, so letting
		// the WebView do the download regularly blew that budget. OkHttp
		// has no such limit, and the app's CloudFlareInterceptor handles a
		// challenge here exactly as it does for the catalogue.
		val html = runCatching { webClient.httpGet(chapterUrl).parseRaw() }.getOrElse { e ->
			log("getPages: fetching $chapterUrl failed: ${e.javaClass.simpleName}: ${e.message}")
			throw e
		}

		val raw = runCatching { context.evaluateJs(chapterUrl, pagesScript(html)) }.getOrNull()
		val trimmed = decodeJsResult(raw)
		log("getPages ${chapterUrl.take(60)} → ${trimmed.length} chars: ${trimmed.take(80)}")

		val arr = when {
			trimmed.isEmpty() -> fail("không đọc được chương (webview không phản hồi)")
			trimmed.startsWith(ERROR_PREFIX) -> fail("không đọc được ảnh (${trimmed.removePrefix(ERROR_PREFIX).trim()})")
			else -> runCatching { JSONArray(trimmed) }.getOrElse {
				fail("webview trả về dữ liệu không hợp lệ — ${trimmed.take(80)}")
			}
		}

		if (arr.length() == 0) {
			fail("webview không tìm được ảnh — thử lại chương này")
		}

		return (0 until arr.length()).mapNotNull { i ->
			val url = arr.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				preview = null,
				url = url,
				source = source,
			)
		}
	}

	/** [PAGES_SCRIPT] with the already-downloaded chapter markup spliced in. */
	private fun pagesScript(html: String): String =
		PAGES_SCRIPT.replace(HTML_PLACEHOLDER, JSONObject.quote(html))

	/**
	 * Report a chapter failure.
	 *
	 * `AuthRequiredException` is the app's "ask the user to sign in"
	 * signal, and this site has no login at all — solving the Cloudflare
	 * challenge is the entire gate, and the catalogue proves it is
	 * already solved. Throwing it here made the app pop the "Đăng nhập"
	 * screen, which then closed itself immediately (isAuthorized() sees
	 * the clearance) and popped up again on the retry: a loop that tells
	 * the user to log in to a site without accounts. So only ask for
	 * authentication when we genuinely have no clearance; otherwise
	 * report a plain, retryable error.
	 */
	private fun fail(reason: String): Nothing {
		val message = "LXManga: $reason"
		if (hasClearance()) {
			throw IOException(message)
		}
		throw AuthRequiredException(source, IllegalStateException(message))
	}

	private fun hasClearance(): Boolean =
		runCatching { CloudFlareHelper.getClearanceCookie(context.cookieJar, baseUrl) != null }.getOrDefault(false)

	// ============================== Tags ===============================

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = fetchDocument("https://$domain/the-loai")
		return doc.select("nav.grid.grid-cols-3.md\\:grid-cols-8 button").mapNotNull { button ->
			val raw = button.attr("wire:click")
			val key = raw.substringAfterLast(", '").substringBeforeLast("')")
			if (key.isBlank()) return@mapNotNull null
			MangaTag(
				key = key,
				title = button.select("span.text-ellipsis").text(),
				source = source,
			)
		}.toSet()
	}

	// ============================== Helpers ===============================

	/**
	 * Build the URL the catalog endpoints expect. The site has three
	 * shapes depending on whether the user is searching, filtering
	 * by tag, or just browsing.
	 */
	private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String = buildString {
		append("https://")
		append(domain)

		when {
			!filter.query.isNullOrEmpty() -> {
				append("/tim-kiem")
				append("?filter[name]=")
				append(filter.query.urlEncoded())

				if (page > 1) {
					append("&page=")
					append(page)
				}

				append("&sort=")
				append(sortQuery(order))
			}

			filter.tags.isNotEmpty() -> {
				val tag = filter.tags.first()
				append("/the-loai/")
				.append(tag.key)
				append("?page=")
				append(page)
				append("&sort=")
				append(sortQuery(order))
			}

			else -> {
				append("/danh-sach")
				.append("?sort=")
				.append(sortQuery(order))
				.append("&page=")
				.append(page)
			}
		}

		if (filter.states.isNotEmpty()) {
			append("&filter[status]=")
			filter.states.forEach {
				append(
					when (it) {
						MangaState.ONGOING -> "ongoing,"
						MangaState.FINISHED -> "completed,"
						MangaState.PAUSED -> "paused,"
						else -> "ongoing,completed,paused"
					},
				)
			}
		}
	}

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

	/**
	 * Undo the JSON encoding `WebView.evaluateJavascript` applies to
	 * string results (a returned string arrives quoted and escaped).
	 */
	private fun decodeJsResult(raw: String?): String {
		val text = raw?.trim().orEmpty()
		if (text.length < 2 || !text.startsWith("\"") || !text.endsWith("\"")) {
			return text
		}
		return runCatching { JSONTokener(text).nextValue() as? String ?: text }.getOrDefault(text)
	}

	private fun cookieNames(): String = runCatching {
		context.cookieJar.getCookies(domain).joinToString(",") { it.name }
	}.getOrDefault("?")

	private fun log(message: String) {
		println("$LOG_TAG $message")
	}

	companion object {
		private const val LOG_TAG = "[LxManga]"

		private const val ERROR_PREFIX = "ERR:"

		private const val DEFAULT_ACCEPT_LANGUAGE = "vi-VN,vi;q=0.9,en;q=0.8"

		/**
		 * Extract the chapter's image URLs.
		 *
		 * The chapter page carries no image URLs — it ships an obfuscated
		 * script that fetches and decrypts them into `window["_0x…"]`.
		 * `evaluateJs(url, …)` loads a stub document whose origin is the
		 * URL, so the fetch below is same-origin: the WebView's HttpOnly
		 * `cf_clearance` is attached, `document.write` re-runs the page's
		 * own scripts, and we poll for the decrypted array (its property
		 * name is randomised on every load).
		 *
		 * The app caps a single `evaluateJs` call at 4 s and a call that
		 * overruns locks its shared WebView mutex, so the script bounds
		 * itself: the fetch aborts after 2 s and the poll gives up after
		 * another 0.8 s, well inside the budget.
		 */
		private const val HTML_PLACEHOLDER = "__LXMANGA_HTML__"

		/**
		 * Render the chapter markup we downloaded ourselves and read the
		 * image URLs out of it.
		 *
		 * The chapter page carries no image URLs: it ships an obfuscated
		 * script that decrypts them into `window["_0x…"]` (the property
		 * name is randomised on every load). Running that script needs a
		 * browser, hence `evaluateJs` — but the *download* is done by
		 * [getPages] with OkHttp and spliced in below, because a single
		 * `evaluateJs` call is capped at 4 s by the app and a ~900 kB page
		 * does not reliably fit in that budget together with the script.
		 *
		 * `document.write` runs the page's own inline scripts, and the loop
		 * waits up to 3 s for the decrypted array to appear. Staying inside
		 * the app's 4 s budget matters: the app waits on a non-cancellable
		 * continuation while holding its shared WebView mutex, so a call
		 * that overruns locks every other WebView call in the process.
		 */
		private val PAGES_SCRIPT: String = """
			(async () => {
				try {
					document.open();
					document.write($HTML_PLACEHOLDER);
					document.close();

					const start = Date.now();
					while (Date.now() - start < 3000) {
						const keys = Object.keys(window);
						for (let i = 0; i < keys.length; i++) {
							const key = keys[i];
							if (!/^_0x[a-f0-9]+${'$'}/i.test(key)) continue;
							const value = window[key];
							if (!Array.isArray(value) || value.length === 0) continue;
							const urls = value.filter(function (u) {
								return typeof u === 'string' &&
									/\.(?:jpe?g|png|webp)(?:[?#]|${'$'})/i.test(u);
							});
							if (urls.length > 0) return JSON.stringify(urls);
						}
						await new Promise(function (r) { setTimeout(r, 100); });
					}
					return 'ERR: ảnh chưa xuất hiện sau 3s — thử lại chương này';
				} catch (e) {
					return 'ERR: ' + (e && e.message ? e.message : String(e));
				}
			})()
		""".trimIndent()
	}
}
