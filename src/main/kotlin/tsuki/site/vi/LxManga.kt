package tsuki.site.vi

import okhttp3.Headers
import org.json.JSONArray
import tsuki.MangaLoaderContext
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
 * The site is gated by Cloudflare's "Under Attack" mode on the very
 * first request — every catalog hit returns the standard
 * "Just a moment…" challenge page until the browser proves it is
 * not a bot. The keiyoushi Mihon port handles this with
 * `runWebView` (a Mihon-only API that Tsuki does not expose).
 *
 * ## Login vs captcha
 *
 * This site does **not** have a user account system — there is
 * nothing to "log in" to. The only auth gate is the Cloudflare
 * Turnstile captcha, which is conceptually very different from a
 * username/password login. The previous design wired
 * [MangaParserAuthProvider] which made the host app's source
 * settings screen show a "Đăng nhập" (Sign in) row, and that
 * confused users into looking for a login form the site does not
 * have. The current design drops the interface so no such row is
 * ever rendered; the user is only ever prompted to "solve the
 * captcha" via [MangaLoaderContext.requestBrowserAction], which the
 * host app typically surfaces with a captcha-specific affordance.
 *
 * ## Captcha flow
 *
 * Every catalog / details / filter-options call goes through
 * [fetchDocument], which:
 *   1. calls OkHttp on the page URL,
 *   2. asks [CloudFlareHelper.checkResponseForProtection] whether
 *      the body is a "Just a moment…" challenge,
 *   3. if it is, hands the URL to
 *      [MangaLoaderContext.requestBrowserAction] (which throws
 *      `Nothing`) and lets the host app open a custom tab.
 *
 * The user solves the Turnstile captcha in that custom tab. When
 * the tab closes the host app's `requestBrowserAction`
 * implementation copies `cf_clearance` out of
 * `CookieManager.getInstance().getCookie(url)` into the OkHttp
 * jar. The very next [fetchDocument] call returns 200 and the
 * catalog renders. (The same prompt fires automatically any time
 * `cf_clearance` ever expires — no manual retry button needed.)
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
	PagedMangaParser(context, MangaParserSource.LXMANGA, 60) {

	override val configKeyDomain = ConfigKey.Domain("lxmanga.space")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_WINDOWS)

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
		.add(CommonHeaders.USER_AGENT, UserAgents.CHROME_WINDOWS)
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
	 * Fetch one of the static HTML pages through OkHttp. The site
	 * is Cloudflare-fronted, so on a fresh install the first hit
	 * returns the "Just a moment…" challenge page. We hand the
	 * URL to [MangaLoaderContext.requestBrowserAction] which the
	 * host app turns into a captcha prompt (open custom tab, etc.).
	 * After the user solves the captcha and the host app copies
	 * `cf_clearance` back to the OkHttp jar, the very next call
	 * goes through. [requestBrowserAction] is declared `Nothing` —
	 * it throws — so this function never returns when CF is
	 * detected.
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
