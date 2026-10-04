package tsuki.site.vi

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * The site is a plain server-rendered PHP page on top of a Cloudflare-fronted
 * origin. The catalog (path `/danh-sach`, search `/tim-kiem`, tag
 * pages under `/the-loai/<slug>`) is publicly crawlable, but every
 * request to `lxmanga.space` is gated by Cloudflare's "Under Attack"
 * mode: without a valid `cf_clearance` cookie the response is the
 * standard "Just a moment…" challenge page. Because `cf_clearance`
 * is `HttpOnly` the parser cannot read it from
 * `WebView.document.cookie` to copy it into the OkHttp jar, so the
 * first catalog hit on a fresh install throws via
 * [CloudFlareHelper.checkResponseForProtection] and the app's
 * [MangaLoaderContext.requestBrowserAction] opens a custom tab on
 * the homepage. After the user clears the challenge, both the
 * OkHttp jar and the app's WebView `CookieManager` end up with
 * `cf_clearance` and the rest of the parser works without further
 * interaction.
 *
 * The chapter reader does not embed the image URLs in the HTML.
 * Instead the page ships an inline obfuscated script (~600 kB) that
 *   1. fetches `/get_token` to mint a fresh per-chapter
 *      `action_token` (Cloudflare Turnstile-gated),
 *   2. XHRs the image URLs into `window["_0x…"]`,
 *   3. hands them to lazysizes to render the `<img>` tags.
 *
 * On Mihon, keiyoushi's `runWebView`+`onPageStarted` hook loads that
 * whole flow in a real WebView and scrapes `window._0x…`. Tsuki's
 * `MangaLoaderContext` only exposes a one-shot
 * `evaluateJs(baseUrl, script)`, so we do the same work in a single
 * async IIFE: `fetch()` the chapter HTML, `document.write` it
 * (which re-runs every inline `<script>` in the stub document,
 * including the obfuscated one), then poll `window._0x…` until the
 * array lands. The `get_token` request is same-origin so it picks
 * up the `cf_clearance` cookie the user set when they confirmed
 * Cloudflare in the auth WebView.
 *
 * If the user has not yet passed the Turnstile challenge the
 * `/get_token` response is `{"is_bot": true, "require_verification":
 * true}` and the obfuscated script never sets `_0x…` —
 * `evaluateJs` times out and we throw [AuthRequiredException] so the
 * app shows the source's "Đăng nhập" row and the user can retry
 * once Cloudflare has been satisfied.
 *
 * [MangaParserAuthProvider.authUrl] still points at the homepage as
 * a safety net: some apps surface a "Đăng nhập" row whose only
 * action is to open the WebView, so the user can also satisfy the
 * challenge from there. Once `cf_clearance` is in the OkHttp jar
 * (via either path) the catalog stops calling
 * [requestBrowserAction].
 */
@MangaSourceParser("LXMANGA", "LXManga", "vi", type = ContentType.HENTAI)
internal class LxManga(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.LXMANGA, 60), MangaParserAuthProvider {

	override val configKeyDomain = ConfigKey.Domain("lxmanga.space")

	/**
	 * Keiyoushi uses 3 req/s for the catalog. The image-loading
	 * WebView path doesn't talk to the catalog, so a single chapter
	 * open costs at most 1 catalog request (the chapter HTML inside
	 * `evaluateJs`), then the rest of the work is browser-internal.
	 * 10 req/s gives the same headroom Tsuki's other Vietnamese
	 * parsers use.
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
	 * The site is gated by Cloudflare's "Under Attack" mode. Once
	 * the user has cleared the challenge we look for *any* cookie
	 * Cloudflare emits (`cf_*`, `_cf*`, `__cf*` — covered by
	 * [CloudFlareHelper.isCloudFlareCookie]); the parser does not
	 * care which exact variant cf_clearance comes back as, just that
	 * the catalog stops returning 403. The chapter reader will
	 * still fail loudly if the cookie is stale or has expired —
	 * that surfaces as [AuthRequiredException] from [getPages] and
	 * the app re-prompts the user to clear the challenge again.
	 */
	override suspend fun isAuthorized(): Boolean {
		val cookies = context.cookieJar.loadForRequest("https://$domain/".toHttpUrl())
		return cookies.any { CloudFlareHelper.isCloudFlareCookie(it.name) }
	}

	override suspend fun getUsername(): String? = null

	/**
	 * `SourceAuthActivity` opens this in the app's WebView so the
	 * user can pass the Cloudflare "Verify you are human" /
	 * Turnstile challenge. After that `cf_clearance` is in both the
	 * WebView's `CookieManager` (needed for [PAGES_SCRIPT]'s
	 * same-origin `fetch`) and the OkHttp jar (needed for the
	 * catalog).
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
		val doc = fetchDocument(url)
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

	// ============================== Interceptor ===============================

	/**
	 * Cloudflare's image CDN at `s*.lxmanga.xyz` checks the
	 * `Referer` / `Origin` against the live site. The OkHttp call to
	 * the image URL has neither header by default, so the CDN
	 * rejects the request as cross-origin even though the browser
	 * would have sent them for an `<img>` request. Re-attach them
	 * on every non-cover request too, for the cases where the
	 * origin itself cares.
	 */
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val url = request.url.toString()

		val needsHeaders = !url.contains("covers")
		val headers = if (needsHeaders) {
			request.headers.newBuilder()
				.add(CommonHeaders.REFERER, "$baseUrl/")
				.add(CommonHeaders.ORIGIN, baseUrl)
				.add(CommonHeaders.USER_AGENT, UserAgents.CHROME_WINDOWS)
				.build()
		} else {
			request.headers.newBuilder()
				.add(CommonHeaders.USER_AGENT, UserAgents.CHROME_WINDOWS)
				.build()
		}

		val newRequest = request.newBuilder()
			.headers(headers)
			.build()

		return chain.proceed(newRequest)
	}

	// ============================== Tags ===============================

	private suspend fun loadAvailableTags(): Set<MangaTag> {
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
	 * Fetch and parse one of the static HTML endpoints. The whole
	 * site is Cloudflare-fronted, so any request can come back as
	 * the "Just a moment…" challenge page; if it does, hand the URL
	 * to [MangaLoaderContext.requestBrowserAction] which opens a
	 * custom tab for the user to clear the challenge. After the
	 * user comes back the OkHttp jar carries `cf_clearance` and the
	 * next call goes through.
	 *
	 * [requestBrowserAction] is declared `Nothing` — it throws
	 * (typically an [AuthRequiredException] the app surfaces to the
	 * user) — so this function never returns when CF is detected.
	 */
	private suspend fun fetchDocument(url: String): org.jsoup.nodes.Document {
		val response = webClient.httpGet(url)
		val protection = CloudFlareHelper.checkResponseForProtection(response.copy())
		if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
			response.close()
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

	companion object {
		/**
		 * The script run inside the chapter WebView. It mirrors what
		 * the keiyoushi `runWebView` does, but compressed into a
		 * single async IIFE because Tsuki only exposes one-shot
		 * `evaluateJs(baseUrl, script)`.
		 *
		 * The chapter URL itself is fetched with
		 * `credentials: include` so the WebView's `cf_clearance`
		 * cookie is forwarded. The fetched HTML is then dropped into
		 * the stub document with `document.write` so every inline
		 * `<script>` (including the ~600 kB obfuscated image-URL
		 * builder) executes as if the user had loaded the chapter
		 * page directly.
		 *
		 * The obfuscated script's final act is to populate
		 * `window["_0x…"]` (a property name is randomised on every
		 * page load) with the array of image URLs. We poll
		 * `Object.keys(window)` for the first hex-prefixed property
		 * whose value is a non-empty array of strings.
		 *
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
