package tsuki.site.vi

import kotlinx.coroutines.delay
import okhttp3.Headers
import okio.IOException
import org.json.JSONArray
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
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * LXManga (https://lxmanga.space) — Vietnamese hentai manga reader.
 *
 * The site is gated by Cloudflare's "Under Attack" mode on the very
 * first request — every catalog hit returns the standard
 * "Just a moment…" challenge page until the browser proves it is
 * not a bot. The keiyoushi Mihon port handles this with
 * `runWebView` (a Mihon-only API that Tsuki does not expose).
 *
 * ## The "Đăng nhập" row is the captcha entry point
 *
 * The site has no user accounts — the only gate is the Cloudflare
 * Turnstile challenge. But the host app only renders the source
 * settings' "Đăng nhập" row when the parser implements
 * [MangaParserAuthProvider] with a non-empty [authUrl], and that row
 * is the *only* way for the user to open the challenge on demand
 * (without first hitting a catalog request that 403s). So we do
 * implement the interface, and point [authUrl] at the homepage: the
 * app opens it in its in-app browser (`SourceAuthActivity`), the
 * "Just a moment…" page renders, the user taps the Turnstile
 * checkbox, and the browser writes the resulting cookies — including
 * the HttpOnly `cf_clearance` — into `android.webkit.CookieManager`.
 *
 * The app's cookie jar is [tsuki.util.CookieJar] backed by
 * `CookieManager` (`AndroidCookieJar`), so those cookies are visible
 * to the parser — a device log confirmed the jar really does hold
 * `cf_clearance`. [isAuthorized] answers "is that cookie there?",
 * which is the same signal the app's own `CloudFlareClient` uses, so
 * the row greys out and `SourceAuthActivity` closes itself with the
 * "Authorized" toast the moment the user is through.
 *
 * ## Two transports, because a clearance is not always portable
 *
 * A `cf_clearance` cookie is not just a password: Cloudflare can bind
 * it to the *client fingerprint* (TLS/JA4 + User-Agent) it was issued
 * to. On this site that is exactly what happens — the device log shows
 * the jar full of `cf_clearance` and the request still coming back as
 * a "Just a moment…" challenge. A Java/OkHttp client is not the
 * browser the clearance was minted for, so presenting the cookie is
 * not enough.
 *
 * [fetchDocument] therefore tries two transports, in order:
 *
 *  1. plain OkHttp — fast, and the host app's `CloudFlareInterceptor`
 *     hooks into it. Used whenever Cloudflare accepts it, which is
 *     also the case on sites that don't bind the clearance.
 *
 *  2. the app's WebView (`evaluateJs` + `fetch`) — a real Chromium
 *     network stack, i.e. a client Cloudflare trusts. The stub
 *     document `evaluateJs` creates has the page URL as its origin, so
 *     the fetch is same-origin and carries the HttpOnly clearance
 *     plus the fingerprint it was issued to. The parsed HTML is
 *     identical, so every selector below is shared.
 *
 * When even the WebView is challenged (no clearance yet, or an
 * expired one), [AuthRequiredException] makes the app surface the
 * "Đăng nhập" flow — which is the same WebView the challenge needs,
 * so solving it there unblocks transport 2 immediately.
 *
 * Note: `WebView.evaluateJavascript` hands string results back
 * JSON-encoded, and older builds do not resolve promises; both are
 * handled ([decodeJsResult], [FETCH_SCRIPT_SYNC]). The app also caps
 * a single `evaluateJs` call at 4 s, so both scripts must return fast.
 *
 * ## Chapter reader
 *
 * The chapter page does not embed image URLs in the HTML. It
 * ships an inline obfuscated script (~600 kB) that
 *   1. fetches `/get_token` to mint a fresh per-chapter
 *      `action_token` (Turnstile-gated on the server side),
 *   2. XHRs the image URLs into `window["_0x…"]`,
 *   3. hands them to lazysizes to render the `<img>` tags.
 *
 * The only way to make that flow happen on Tsuki is to load the
 * page in a real browser context. [getPages] runs [PAGES_SCRIPT]
 * inside `evaluateJs(chapterUrl, …)`: the script `fetch`es the
 * chapter HTML (same-origin, so the WebView's `cf_clearance`
 * cookie is forwarded), `document.write`s it so every inline
 * `<script>` re-runs including the obfuscated builder, and polls
 * `Object.keys(window)` for the first hex-prefixed property whose
 * value is a non-empty array of strings. Errors are returned as
 * `"ERR:<message>"` so the Kotlin side can distinguish them from
 * a valid JSON array.
 */
@MangaSourceParser("LXMANGA", "LXManga", "vi", type = ContentType.HENTAI)
internal class LxManga(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.LXMANGA, 60), MangaParserAuthProvider {

	/** The homepage — it is Cloudflare-gated, so opening it renders the challenge. */
	private val baseUrl: String get() = "https://$domain/"

	override val configKeyDomain = ConfigKey.Domain("lxmanga.space")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	/**
	 * Deliberately *not* overriding [userAgentKey]: it defaults to the
	 * app's own WebView user agent, which is exactly what every WebView
	 * in the app sends — the challenge screen, the "Đăng nhập" screen
	 * and the hidden WebView `evaluateJs` uses. A `cf_clearance` is only
	 * honoured for the identity it was issued to, so one consistent UA
	 * everywhere is worth more here than looking like a desktop
	 * browser; the markup is identical either way.
	 */

	// ============================== Auth ===============================
	// There is no account on this site; the "Đăng nhập" row exists so the
	// user can open the Cloudflare challenge whenever they want, and the
	// cf_clearance cookie the browser stores is exactly what the plain
	// OkHttp client needs to stop getting 403s.

	override val authUrl: String get() = baseUrl

	/**
	 * True as soon as the jar holds a `cf_clearance` for the domain.
	 *
	 * On Usagi the jar is `AndroidCookieJar`, which reads through
	 * `android.webkit.CookieManager`, so the HttpOnly clearance cookie
	 * written by the challenge WebView is visible here — that is why
	 * this check works even though `document.cookie` would not show it.
	 */
	override suspend fun isAuthorized(): Boolean {
		val clearance = runCatching {
			CloudFlareHelper.getClearanceCookie(context.cookieJar, baseUrl)
		}.getOrElse {
			log("isAuthorized: cookie jar threw ${it::class.simpleName}: ${it.message}")
			null
		}
		log("isAuthorized jar=${clearance != null} class=${jarName()} cookies=[${cookieNames()}]")
		if (clearance == null) {
			// The jar has no clearance. Two very different worlds can
			// produce that, and the log line below tells them apart:
			//   * "web=HTTP 200"  → the WebView session is fine, the
			//     cookie just never reaches the OkHttp jar;
			//   * "web=HTTP 403"  → the challenge itself is not cleared
			//     (the WebView is stuck on "Just a moment…").
			log("isAuthorized webviewProbe=${probeWebView()}")
		}
		return clearance != null
	}

	/** Diagnostic only: is the host reachable from inside the app's WebView session? */
	private suspend fun probeWebView(): String {
		val now = System.currentTimeMillis()
		lastProbe?.takeIf { now - it.first < 30_000L }?.let { return "${it.second} (cached)" }
		val result = runCatching { context.evaluateJs(baseUrl, PROBE_SCRIPT) }
			.getOrElse { "ERR: ${it::class.simpleName}" }
			.orEmpty()
		lastProbe = now to result
		return result
	}

	/** No account system on this site, so there is no name to show. */
	override suspend fun getUsername(): String? = null

	@Volatile
	private var lastProbe: Pair<Long, String>? = null

	/**
	 * Once Cloudflare has rejected an OkHttp request, every later one
	 * will be rejected identically (the clearance is bound to the
	 * browser that earned it, not to this client), so skip the wasted
	 * round trip — and the CF exception it raises inside the app — for
	 * a while.
	 */
	@Volatile
	private var okHttpBlockedUntil = 0L

	/**
	 * OkHttp is used for the catalog, details, filter listing and
	 * chapter image fetches. Image URLs are emitted by the
	 * obfuscated builder as raw s*.lxmanga.xyz paths with no token
	 * in the URL — the CDN validates Referer/Origin instead, which
	 * the browser supplies naturally for `<img src=...>` requests.
	 */
	override val webClient = OkHttpWebClient(
		context.httpClient.newBuilder()
			.callTimeout(45.seconds)
			.rateLimit(10, 1.seconds)
			.build(),
		source,
	)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add(CommonHeaders.REFERER, "https://$domain/")
		.add(CommonHeaders.ORIGIN, "https://$domain")
		.add(CommonHeaders.USER_AGENT, config[userAgentKey])
		.build()

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
		availableTags = fetchAvailableTags(),
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
					?: cover.attr("style").cssUrl()?.let { cssUrl ->
						if (cssUrl.contains("s3.lxmanga.top")) cssUrl.replace("s3.lxmanga.top", domain) else cssUrl
					}
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
		log("getPages $chapterUrl | cookies=[${cookieNames()}] clearance=${clearanceAge()}")
		val raw = runCatching { context.evaluateJs(chapterUrl, PAGES_SCRIPT) }.getOrNull()
		val trimmed = decodeJsResult(raw)
		log("getPages js returned ${trimmed.length} chars: ${trimmed.take(120)}")
		if (trimmed.isEmpty() || trimmed.startsWith("ERR:")) {
			val reason = trimmed.removePrefix("ERR:").trim()
			throw AuthRequiredException(
				source,
				IllegalStateException(
					if (reason.isEmpty())
						"LXManga: chưa vượt qua Cloudflare — mở 'Đăng nhập' trong cài đặt nguồn để giải captcha rồi thử lại"
					else
						"LXManga: không đọc được ảnh ($reason) — nếu là 403 thì giải lại captcha trong 'Đăng nhập'",
				),
			)
		}

		val arr = runCatching { JSONArray(trimmed) }.getOrElse {
			throw AuthRequiredException(
				source,
				IllegalStateException("LXManga: webview trả về payload không hợp lệ — ${trimmed.take(80)}"),
			)
		}

		if (arr.length() == 0) {
			throw AuthRequiredException(
				source,
				IllegalStateException("LXManga: webview không tìm được ảnh — captcha có thể đã hết hạn, giải lại trong 'Đăng nhập'"),
			)
		}

		return (0 until arr.length()).mapNotNull { i ->
			val url = arr.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

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
		.append(domain)

		when {
			!filter.query.isNullOrEmpty() -> {
				append("/tim-kiem")
				.append("?filter[name]=")
				.append(filter.query.urlEncoded())

				if (page > 1) {
					append("&page=")
					.append(page)
				}

				append("&sort=")
				.append(sortQuery(order))
			}

			filter.tags.isNotEmpty() -> {
				val tag = filter.tags.first()
				append("/the-loai/")
				.append(tag.key)
				.append("?page=")
				.append(page)
				.append("&sort=")
				.append(sortQuery(order))
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
	 * Fetch one of the static HTML pages.
	 *
	 * Two transports, tried in order:
	 *
	 *  1. **plain OkHttp** — fast, and it is what the host app's
	 *     `CloudFlareInterceptor` hooks into. When the jar holds a
	 *     `cf_clearance` that Cloudflare accepts, this is all we need.
	 *
	 *  2. **the app's WebView** (`evaluateJs` + `fetch`). Some
	 *     Cloudflare setups bind `cf_clearance` not only to the cookie
	 *     but to the *client fingerprint* (TLS/JA4 + UA) it was issued
	 *     to, so a clearance the WebView earned is rejected when a
	 *     Java/OkHttp client presents it — even though the cookie is
	 *     sitting right there in the jar. The WebView is a real
	 *     Chromium network stack, i.e. the client Cloudflare trusts, so
	 *     when OkHttp is challenged we simply ask the WebView for the
	 *     page instead. It returns the same HTML, so all the selectors
	 *     below are unchanged.
	 *
	 * If even the WebView is challenged (no clearance yet, or an
	 * expired one), [AuthRequiredException] makes the app surface the
	 * "Đăng nhập" flow, which opens the challenge in a WebView and
	 * keeps it open until [isAuthorized] sees the clearance — the same
	 * WebView that [fetchViaWebView] then uses.
	 */
	private suspend fun fetchDocument(url: String): org.jsoup.nodes.Document {
		if (System.currentTimeMillis() >= okHttpBlockedUntil) {
			val response = runCatching { webClient.httpGet(url) }.getOrElse { e ->
				if (!isCloudFlareRejection(e)) throw e
				log("okhttp rejected by Cloudflare ($url): ${e.javaClass.simpleName}")
				okHttpBlockedUntil = System.currentTimeMillis() + OKHTTP_RETRY_AFTER
				null
			}
			if (response != null) {
				val protection = CloudFlareHelper.checkResponseForProtection(response.copy())
				if (protection == CloudFlareHelper.PROTECTION_NOT_DETECTED) {
					log("ok $url via okhttp (HTTP ${response.code})")
					return response.parseHtml()
				}
				// No interceptor in this host build — same situation, same answer.
				response.close()
				log("plain response is a Cloudflare challenge ($url, protection=$protection)")
				okHttpBlockedUntil = System.currentTimeMillis() + OKHTTP_RETRY_AFTER
			}
		} else {
			log("okhttp recently rejected — going straight to the webview for $url")
		}
		return fetchViaWebView(url)
	}

	/** Fetch the same page from inside the app's WebView. */
	private suspend fun fetchViaWebView(url: String): org.jsoup.nodes.Document {
		log("webview fetch $url | cookies=[${cookieNames()}] clearance=${clearanceAge()}")
		var html = ""
		var challenged = false
		for (attempt in 1..WEBVIEW_ATTEMPTS) {
			if (attempt > 1) delay(WEBVIEW_RETRY_DELAY_MS)
			html = webViewFetch(url)
			log("webview attempt $attempt/$WEBVIEW_ATTEMPTS → ${html.length} chars: ${html.take(80).replace('\n', ' ')}")
			when {
				// A real challenge: waiting will not change the answer.
				html == CHALLENGE -> {
					challenged = true
					break
				}

				html.length >= MIN_HTML_LENGTH && !looksLikeChallenge(html) -> {
					return org.jsoup.Jsoup.parse(html, url)
				}
			}
		}
		log("webview fetch failed (challenged=$challenged, hadClearance=${hasClearance()})")
		// The jar holding a clearance means the auth screen would open and
		// immediately close again (isAuthorized() == true): that is the
		// "it keeps asking me to log in" ping-pong, not a fix. Report a
		// plain retryable error instead.
		return if (hasClearance()) {
			throw IOException(
				"LXManga: Cloudflare vẫn chặn request — đã thử qua webview ${WEBVIEW_ATTEMPTS} lần, thử lại sau vài giây",
			)
		} else {
			throw AuthRequiredException(
				source,
				IllegalStateException(NOT_CLEARED_MESSAGE),
			)
		}
	}

	private fun hasClearance(): Boolean =
		runCatching { CloudFlareHelper.getClearanceCookie(context.cookieJar, baseUrl) != null }.getOrDefault(false)

	/**
	 * Run `fetch` inside the WebView. The WebView is a real browser
	 * network stack, so the HttpOnly `cf_clearance` in `CookieManager`
	 * — and the matching client fingerprint — are used exactly as when
	 * the user solves the challenge by hand.
	 *
	 * The app serialises every `evaluateJs` and every invisible
	 * captcha resolve on one mutex, so this call can come back empty
	 * simply because the app is busy solving something else: it is
	 * retried by [fetchViaWebView].
	 *
	 * `WebView.evaluateJavascript` hands its result back JSON-encoded
	 * (a returned string arrives quoted and escaped), hence
	 * [decodeJsResult]. Promises are resolved by the platform, but if
	 * they are not on some WebView build the result is `{}` — in that
	 * case we retry with a synchronous XHR, which cannot be
	 * mishandled.
	 */
	private suspend fun webViewFetch(url: String): String {
		val async = runCatching { context.evaluateJs(url, FETCH_SCRIPT) }.getOrNull()
		if (async != null) {
			val decoded = decodeJsResult(async)
			if (decoded.isNotEmpty() && decoded != "{}") {
				return decoded
			}
			val sync = runCatching { context.evaluateJs(url, FETCH_SCRIPT_SYNC) }.getOrNull()
			if (sync != null) {
				return decodeJsResult(sync)
			}
		}
		return ""
	}

	/** Undo the JSON encoding `WebView.evaluateJavascript` applies to string results. */
	private fun decodeJsResult(raw: String?): String {
		val text = raw?.trim().orEmpty()
		if (text.length < 2 || !text.startsWith("\"") || !text.endsWith("\"")) {
			return text
		}
		return runCatching { org.json.JSONTokener(text).nextValue() as? String ?: text }.getOrDefault(text)
	}

	/** True when [e] (or anything it wraps) is the host app's Cloudflare rejection. */
	private fun isCloudFlareRejection(e: Throwable): Boolean {
		var cause: Throwable? = e
		while (cause != null) {
			if (cause.javaClass.simpleName.startsWith("CloudFlare")) return true
			cause = cause.cause
		}
		return false
	}

	private fun looksLikeChallenge(html: String): Boolean =
		html.contains("challenge-platform") ||
			html.contains("cf-chl") ||
			html.contains("challenges.cloudflare.com") ||
			html.contains("Just a moment")

	// ============================== Diagnostics ===============================

	private fun log(message: String) {
		println("$LOG_TAG $message")
	}

	private fun jarName(): String = context.cookieJar.javaClass.simpleName

	private fun cookieNames(): String = runCatching {
		context.cookieJar.getCookies(domain).joinToString(",") { it.name }
	}.getOrElse { "err:${it::class.simpleName}" }

	/**
	 * Age of the `cf_clearance` in the jar, parsed out of its value
	 * (`<hash>-<issuedAtUnix>-<version>-…`). A *stale* clearance means
	 * the challenge never actually passed, a *fresh* one means it
	 * passed and the plain-HTTP client is the one being rejected.
	 */
	private fun clearanceAge(): String {
		val cookie = runCatching { CloudFlareHelper.getClearanceCookie(context.cookieJar, baseUrl) }.getOrNull()
			?: return "none"
		val issuedAt = Regex("""-(\d{9,11})-""").find(cookie)?.groupValues?.get(1)?.toLongOrNull()
			?: return "unknown"
		val age = System.currentTimeMillis() / 1000 - issuedAt
		return if (age < 0) "from the future?" else "${age}s old"
	}

	/** Diagnostic only: can the WebView itself load [url] right now? */
	private suspend fun probeWebView(url: String): String {
		val now = System.currentTimeMillis()
		lastProbe?.takeIf { now - it.first < 30_000L }?.let { return "${it.second} (cached)" }
		val result = decodeJsResult(runCatching { context.evaluateJs(url, PROBE_SCRIPT) }.getOrNull()).ifEmpty { "empty" }
		lastProbe = now to result
		return result
	}

	private fun sortQuery(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "-views"
		SortOrder.NEWEST -> "-created_at"
		SortOrder.ALPHABETICAL -> "name"
		SortOrder.ALPHABETICAL_DESC -> "-name"
		else -> "-updated_at"
	}

	companion object {
		private const val LOG_TAG = "[LxManga]"

		/** Returned by the fetch scripts when the response is a Cloudflare challenge. */
		private const val CHALLENGE = "CF"

		private const val ERROR_PREFIX = "ERR:"

		/** Anything shorter than this is a challenge shell, not a page. */
		private const val MIN_HTML_LENGTH = 512

		/**
		 * Attempts at the WebView transport. The app runs every
		 * `evaluateJs` and every invisible captcha resolve on one
		 * mutex (held up to 20 s by `WebViewExecutor.tryResolveCaptcha`),
		 * so a single attempt can easily come back empty while the app
		 * is busy elsewhere.
		 */
		private const val WEBVIEW_ATTEMPTS = 3

		private const val WEBVIEW_RETRY_DELAY_MS = 2_500L

		/** How long to trust "OkHttp is rejected here" before probing it again. */
		private const val OKHTTP_RETRY_AFTER = 5 * 60_000L

		private const val NOT_CLEARED_MESSAGE =
			"LXManga: chưa vượt qua Cloudflare — bấm 'Đăng nhập' trong cài đặt nguồn để giải captcha rồi thử lại"

		/**
		 * Fetch the page *inside* the WebView. `evaluateJs(url, …)`
		 * loads a stub document whose origin is `url`, so this fetch is
		 * same-origin and goes through the WebView network stack: the
		 * HttpOnly `cf_clearance` (and the browser's TLS fingerprint
		 * that Cloudflare bound it to) are used exactly like a normal
		 * page load.
		 *
		 * The result is a JSON string; [decodeJsResult] unwraps it.
		 */
		private val FETCH_SCRIPT: String = """
			(async () => {
				try {
					const resp = await fetch(location.href, {
						credentials: 'include',
						cache: 'no-store',
						redirect: 'follow'
					});
					if (resp.status === 403 || resp.status === 503) return 'CF';
					if (!resp.ok) return 'ERR: HTTP ' + resp.status;
					return await resp.text();
				} catch (e) {
					return 'ERR: ' + (e && e.message ? e.message : String(e));
				}
			})()
		""".trimIndent()

		/**
		 * Same fetch, but synchronous. Only used when the async form
		 * comes back as `{}`, which means this WebView build did not
		 * resolve the promise for us.
		 */
		private val FETCH_SCRIPT_SYNC: String = """
			(function () {
				try {
					const xhr = new XMLHttpRequest();
					xhr.open('GET', location.href, false);
					xhr.send(null);
					if (xhr.status === 403 || xhr.status === 503) return 'CF';
					if (xhr.status < 200 || xhr.status >= 300) return 'ERR: HTTP ' + xhr.status;
					return xhr.responseText;
				} catch (e) {
					return 'ERR: ' + (e && e.message ? e.message : String(e));
				}
			})()
		""".trimIndent()

		/**
		 * Diagnostic probe: run a same-origin `fetch` inside the app's
		 * WebView. Because it goes through the WebView's own network
		 * stack, the HttpOnly `cf_clearance` from `CookieManager` is
		 * attached even though `document.cookie` cannot see it.
		 * "HTTP 200" therefore means "the WebView has a working
		 * session" — which is exactly the fact the OkHttp jar lookup
		 * cannot tell us when it fails.
		 */
		private val PROBE_SCRIPT: String = """
			(async () => {
				try {
					const resp = await fetch(location.href, { credentials: 'include', cache: 'no-store' });
					return 'HTTP ' + resp.status;
				} catch (e) {
					return 'ERR: ' + (e && e.message ? e.message : String(e));
				}
			})()
		""".trimIndent()

		/**
		 * Script run inside the WebView to extract the chapter's
		 * image URLs. Mirrors the keiyoushi `runWebView` approach
		 * in a single async IIFE because Tsuki only exposes
		 * one-shot `evaluateJs(baseUrl, script)`.
		 *
		 * The chapter URL is fetched with `credentials: include`
		 * so `cf_clearance` from the WebView's `CookieManager` is
		 * forwarded. The fetched HTML is dropped into the stub
		 * document with `document.write` so every inline
		 * `<script>` (including the ~600 kB obfuscated image-URL
		 * builder) executes as if the user had loaded the chapter
		 * page directly. The builder's final act is to populate
		 * `window["_0x…"]` (a property name randomised on every
		 * page load) with the array of image URLs.
		 *
		 * We poll `Object.keys(window)` for the first hex-prefixed
		 * property whose value is a non-empty array of strings.
		 * Errors are returned as `"ERR:<message>"` so the Kotlin
		 * side can distinguish them from a valid JSON array.
		 */
		private val PAGES_SCRIPT: String = """
			(async () => {
				try {
					const resp = await fetch(location.href, { credentials: 'include' });
					if (!resp.ok) return 'ERR: HTTP ' + resp.status;
					const html = await resp.text();
					document.open();
					document.write(html);
					document.close();

					const start = Date.now();
					while (Date.now() - start < 30000) {
						const keys = Object.keys(window);
						for (let i = 0; i < keys.length; i++) {
							const key = keys[i];
							if (!/^_0x[a-f0-9]+${'$'}/i.test(key)) continue;
							const val = window[key];
							if (!Array.isArray(val) || val.length === 0) continue;
							const urls = val.filter(function (u) {
								return typeof u === 'string' &&
									/\.(?:jpe?g|png|webp)(?:[?#]|${'$'})/i.test(u);
							});
							if (urls.length > 0) return JSON.stringify(urls);
						}
						await new Promise(function (r) { setTimeout(r, 250); });
					}
					return 'ERR: timeout — ảnh không xuất hiện sau 30s (có thể captcha đã hết hạn)';
				} catch (e) {
					return 'ERR: ' + (e && e.message ? e.message : String(e));
				}
			})()
		""".trimIndent()
	}
}
