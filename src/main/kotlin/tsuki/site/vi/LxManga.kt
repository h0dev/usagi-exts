package tsuki.site.vi

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tsuki.MangaLoaderContext
import tsuki.MangaParserAuthProvider
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.AuthRequiredException
import tsuki.model.*
import tsuki.network.CloudFlareHelper
import tsuki.network.OkHttpWebClient
import tsuki.network.UserAgents
import tsuki.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * LXManga (https://lxmanga.space) — Vietnamese hentai manga reader.
 *
 * ## Cloudflare "Under Attack" mode
 *
 * Every request to `lxmanga.space` is gated by Cloudflare's bot
 * challenge. The cookie the challenge drops, `cf_clearance`, is
 * `HttpOnly` and there is no API equivalent of the challenge — you
 * can only get past it by running a real browser through the
 * challenge UI. The keiyoushi Mihon port does that with a full
 * `runWebView` (Mihon has it; Tsuki does not). The Tsuki port has
 * to live with a one-shot `evaluateJs(baseUrl, script)`, so we
 * route **every** HTTP request through the app's WebView: the
 * `fetch()` inside the script is same-origin and the WebView's
 * `CookieManager` (which has `cf_clearance` once the user has cleared
 * the challenge) forwards the cookie automatically.
 *
 * The Tsuki WebView is exposed to the user through two paths:
 *   1. [MangaParserAuthProvider.authUrl] — the app's "Đăng nhập"
 *      row opens the homepage in a `SourceAuthActivity` WebView and
 *      the user taps through the challenge there.
 *   2. The same `SourceAuthActivity` is reused by every other
 *      `evaluateJs` call below: the in-app WebView is what actually
 *      runs the script, and the cookies it picked up from the auth
 *      flow are the ones that satisfy every subsequent fetch.
 *
 * We **do not** fall back to [MangaLoaderContext.requestBrowserAction]
 * for the catalog. The default implementation opens a custom tab in
 * the system browser; cookies set there live in a separate cookie
 * store the app cannot read back, so the catalog would stay 403
 * even after the user thought they had "logged in". The user-visible
 * auth path is the in-app WebView only.
 *
 * ## Chapter reader
 *
 * The chapter page does not embed the image URLs in the HTML. It
 * ships an inline obfuscated script (~600 kB) that
 *   1. fetches `/get_token` to mint a fresh per-chapter
 *      `action_token` (Cloudflare Turnstile-gated),
 *   2. XHRs the image URLs into `window["_0x…"]`,
 *   3. hands them to lazysizes to render the `<img>` tags.
 *
 * We mirror the keiyoushi Mihon approach in a single async IIFE:
 * `fetch()` the chapter HTML, `document.write` it (which re-runs
 * every inline `<script>` in the stub document, including the
 * obfuscated one), then poll `window._0x…` until the array lands.
 * The `get_token` request is same-origin so it picks up
 * `cf_clearance` from the WebView's `CookieManager`.
 *
 * If the user has not yet passed the Turnstile challenge the
 * `/get_token` response is `{"is_bot": true, "require_verification":
 * true}` and the obfuscated script never sets `_0x…`. The script
 * times out after 30 s and we throw [AuthRequiredException] with a
 * Vietnamese hint so the app can re-prompt the user to clear the
 * challenge.
 */
@MangaSourceParser("LXMANGA", "LXManga", "vi", type = ContentType.HENTAI)
internal class LxManga(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.LXMANGA, 60), MangaParserAuthProvider {

	override val configKeyDomain = ConfigKey.Domain("lxmanga.space")

	/**
	 * OkHttp is only used for image fetches now. The catalog and
	 * chapter HTML go through the WebView, so the call rate is
	 * well below what the catalog used to need — 10 req/s is just
	 * the same safety margin Tsuki's other Vietnamese parsers use.
	 */
	override val webClient = OkHttpWebClient(
		context.httpClient.newBuilder()
			.callTimeout(45.seconds)
			.rateLimit(10, 1.seconds)
			.build(),
		source,
	)

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_WINDOWS)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	private val baseUrl: String get() = "https://$domain"

	// ============================== Auth ==============================

	/**
	 * Cloudflare sets `cf_clearance` (HttpOnly) once the user clears
	 * the "Verify you are human" challenge in the in-app WebView. We
	 * cannot read that cookie from `document.cookie` (it is HttpOnly)
	 * and we cannot copy it into the OkHttp jar — so the cheapest
	 * reliable signal that the user has authorized is a same-origin
	 * `fetch()` through the WebView that returns 200.
	 *
	 * The OkHttp jar is still checked first because some reader apps
	 * share a single `WebViewCookieJar` between WebView and OkHttp, in
	 * which case the catalog probe succeeds without ever round-tripping
	 * the WebView. A short TTL cache (10 s) sits in front of the
	 * WebView probe so the app's frequent `isAuthorized()` polls do
	 * not hammer it.
	 */
	override suspend fun isAuthorized(): Boolean {
		// Fast path: shared cookie jar already carries cf_clearance.
		val cookies = context.cookieJar.loadForRequest("https://$domain/".toHttpUrl())
		if (cookies.any { CloudFlareHelper.isCloudFlareCookie(it.name) }) {
			cachedAuthCheck = System.currentTimeMillis() to true
			return true
		}

		val now = System.currentTimeMillis()
		val cached = cachedAuthCheck
		if (cached != null && now - cached.first < AUTH_CACHE_TTL_MS) {
			return cached.second
		}

		val script = """
			(async () => {
				try {
					const resp = await fetch('${baseUrl}', { credentials: 'include', cache: 'no-store' });
					if (resp.status === 200 || resp.status === 301 || resp.status === 302) {
						return 'OK';
					}
					if (resp.status === 403 || resp.status === 503) {
						return 'CF';
					}
					return 'HTTP ' + resp.status;
				} catch (e) {
					return 'ERR: ' + (e && e.message ? e.message : String(e));
				}
			})()
		""".trimIndent()
		val raw = runCatching { context.evaluateJs(baseUrl, script) }.getOrNull()
		val result = raw?.trim() == "OK"
		cachedAuthCheck = now to result
		return result
	}

	override suspend fun getUsername(): String? = null

	/**
	 * `SourceAuthActivity` opens this in the in-app WebView. The
	 * user taps through the Cloudflare "Verify you are human"
	 * challenge once; after that the WebView's `CookieManager`
	 * carries `cf_clearance` and every subsequent `evaluateJs` fetch
	 * (catalog, details, chapter) goes through.
	 */
	override val authUrl: String get() = baseUrl

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
		availableTags = loadAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildListUrl(page, order, filter)
		val doc = fetchHtml(url)
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
		}
	}

	// ============================== Details ===============================

	override suspend fun getDetails(manga: Manga): Manga {
		val root = fetchHtml(manga.url.toAbsoluteUrl(domain))
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
		val raw = runCatching { context.evaluateJs(chapterUrl, PAGES_SCRIPT) }.getOrNull()
		val trimmed = raw?.trim().orEmpty()
		if (trimmed.isEmpty() || trimmed.startsWith("ERR:")) {
			val reason = trimmed.removePrefix("ERR:").trim()
			throw AuthRequiredException(
				source,
				IllegalStateException(
					if (reason.isEmpty())
						"LXManga: chưa vượt qua Cloudflare — mở trang nguồn trong webview rồi thử lại"
					else
						"LXManga: không đọc được ảnh ($reason)",
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
				IllegalStateException("LXManga: webview không tìm được ảnh — có thể captcha đã hết hạn, mở lại trang nguồn"),
			)
		}

		// The catalog probe succeeded in the WebView, so a previously
		// negative auth check was just stale cookies. Drop the cache
		// so the next call sees the fresh state.
		cachedAuthCheck = null

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

	private suspend fun loadAvailableTags(): Set<MangaTag> {
		val doc = fetchHtml("https://$domain/the-loai")
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
				append(tag.key)
				append("?page=")
				append(page)
				append("&sort=")
				append(sortQuery(order))
			}

			else -> {
				append("/danh-sach")
				append("?sort=")
				append(sortQuery(order))
				append("&page=")
				append(page)
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
	 * Fetch a static HTML page through the WebView. The WebView's
	 * `CookieManager` already has `cf_clearance` (set when the user
	 * confirmed the challenge through [authUrl]) and the same-origin
	 * `fetch()` forwards it; the OkHttp jar does not get involved.
	 *
	 * If the user has not solved Cloudflare yet, the fetch returns
	 * 403 "Just a moment…" — the script returns the literal
	 * `"CF"` status which we convert into an
	 * [AuthRequiredException] so the app re-prompts the user.
	 */
	private suspend fun fetchHtml(url: String): Document {
		val script = FETCH_HTML_SCRIPT.replace("__URL__", url)
		val raw = runCatching { context.evaluateJs(url, script) }.getOrNull()
		val trimmed = raw?.trim().orEmpty()
		if (trimmed == "CF" || trimmed.startsWith("CF") || trimmed.startsWith("ERR:") || trimmed.isEmpty()) {
			cachedAuthCheck = null
			throw AuthRequiredException(
				source,
				IllegalStateException(
					if (trimmed.startsWith("ERR:"))
						"LXManga: không tải được trang (${trimmed.removePrefix("ERR:").trim()})"
					else
						"LXManga: chưa vượt qua Cloudflare — mở trang nguồn trong webview rồi thử lại",
				),
			)
		}
		return Jsoup.parse(trimmed, url)
	}

	private fun sortQuery(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "-views"
		SortOrder.NEWEST -> "-created_at"
		SortOrder.ALPHABETICAL -> "name"
		SortOrder.ALPHABETICAL_DESC -> "-name"
		else -> "-updated_at"
	}

	@Volatile
	private var cachedAuthCheck: Pair<Long, Boolean>? = null

	companion object {
		/**
		 * How long the [isAuthorized] WebView probe stays cached.
		 * The app polls `isAuthorized` on every state transition
		 * (settings UI, retry after auth, etc.) and each probe is a
		 * round-trip through the WebView — without this, a flurry of
		 * settings-screen visits would re-issue the same `fetch()`
		 * ten times in a row.
		 */
		private const val AUTH_CACHE_TTL_MS: Long = 10_000L

		/**
		 * The script the WebView runs to fetch one of the static
		 * HTML pages (catalog, detail, tag listing). Returns the
		 * raw HTML body as a string so the Kotlin side can run
		 * Jsoup over it.
		 *
		 * `__URL__` is replaced with the target URL by the caller
		 * — the `evaluateJs(baseUrl, script)` call passes the same
		 * URL as the baseUrl, so a same-origin `fetch()` is what
		 * the WebView runs.
		 */
		private val FETCH_HTML_SCRIPT: String = """
			(async () => {
				try {
					const resp = await fetch('__URL__', { credentials: 'include', cache: 'no-store' });
					if (resp.status === 403 || resp.status === 503) {
						return 'CF';
					}
					if (!resp.ok) {
						return 'ERR: HTTP ' + resp.status;
					}
					return await resp.text();
				} catch (e) {
					return 'ERR: ' + (e && e.message ? e.message : String(e));
				}
			})()
		""".trimIndent()

		/**
		 * The script the WebView runs to fetch the chapter page and
		 * capture the obfuscated image-URL builder's output. Mirrors
		 * the keiyoushi `runWebView` approach in a single async IIFE.
		 *
		 * The chapter URL is fetched with `credentials: include` so
		 * `cf_clearance` from the WebView's `CookieManager` is
		 * forwarded. The fetched HTML is dropped into the stub
		 * document with `document.write` so every inline `<script>`
		 * (including the ~600 kB obfuscated image-URL builder)
		 * executes as if the user had loaded the chapter page
		 * directly. The builder's final act is to populate
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
					const resp = await fetch(location.href, { credentials: 'include', cache: 'no-store' });
					if (resp.status === 403 || resp.status === 503) return 'CF';
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
							if (!/^_0x[a-f0-9]+$/i.test(key)) continue;
							const val = window[key];
							if (!Array.isArray(val) || val.length === 0) continue;
							const urls = val.filter(function (u) {
								return typeof u === 'string' &&
									/\.(?:jpe?g|png|webp)(?:[?#]|$)/i.test(u);
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
