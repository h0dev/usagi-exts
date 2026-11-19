package tsuki.site.vi

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Headers
import org.json.JSONObject
import tsuki.MangaLoaderContext
import tsuki.MangaParserAuthProvider
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.AuthRequiredException
import tsuki.exception.ParseException
import tsuki.model.ContentRating
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.network.CommonHeaders
import tsuki.network.OkHttpWebClient
import tsuki.util.WebViewHelper
import tsuki.util.generateUid
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSONNotNull
import tsuki.util.json.mapJSONToSet
import tsuki.util.parseJson
import tsuki.util.rateLimit
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * KiraKira (https://truyenkira.net) — Vietnamese manga/novel reader.
 *
 * The site is a SPA on top of a JSON API. The catalog (`/api/genres`, `/api/comics`,
 * `/api/search`) is public; the chapter content endpoint
 * (`/api/comics/<slug>/chapters/<id>`) requires a session cookie set during
 * Discord OAuth and additionally returns 401 for chapters that have to be bought
 * with the in-site currency.
 *
 * The keiyoushi port also added an `autoUnlockChapters` preference that probes
 * the image CDN to discover already-unlocked pages for a paid chapter. That
 * trick is fragile (it depends on the CDN returning 404 vs an opaque image
 * payload, which is not part of any contract) and not needed here: the app
 * surfaces `AuthRequiredException` through the standard "Đăng nhập" / "Mua
 * chương" UX. Paid chapters keep the lock prefix as a hint, while the API makes
 * the final decision so chapters already purchased by the current account work.
 */
@MangaSourceParser("KIRAKIRA", "KiraKira", "vi")
internal class KiraKira(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KIRAKIRA, 20), MangaParserAuthProvider {

	override val configKeyDomain = ConfigKey.Domain("truyenkira.net")

	/**
	 * Tsuki's rateLimit throws instead of waiting, so a single `getDetails`
	 * call (1 request) followed by a `getPages` call (1 request) only needs a
	 * modest window. We match the keiyoushi 3 req/s ceiling with 10 req/s to
	 * avoid surprising the user when they tap through a few chapters in a row.
	 */
	override val webClient = OkHttpWebClient(
		context.httpClient.newBuilder()
			.rateLimit(10, 1.seconds)
			.build(),
		source,
	)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add(CommonHeaders.ORIGIN, "https://$domain")
		.add(CommonHeaders.REFERER, "https://$domain/")
		.build()

	// ============================== Auth ==============================

	/**
	 * KiraKira is a token-based SPA. The current frontend stores the JWT as
	 * `auth_token` in localStorage (or sessionStorage when "remember me" is
	 * disabled) and sends it as `Authorization: Bearer …`; it does not use a
	 * login cookie (`withCredentials` is false).
	 */
	override suspend fun isAuthorized(): Boolean = currentToken() != null

	override suspend fun getUsername(): String? {
		val token = currentToken() ?: return null
		return runCatching {
			val payload = webClient.httpGet("$API_HOST/users/me".toHttpUrl(), apiHeaders(token)).parseJson()
			val data = payload.optJSONObject("data") ?: payload
			data.getStringOrNull("username")
				?: data.getStringOrNull("displayName")
				?: data.getStringOrNull("name")
		}.getOrNull()
	}

	/** The SPA login route. Email/password and the configured OAuth providers live here. */
	override val authUrl: String
		get() = "https://$domain/login"

	private suspend fun currentToken(): String? = when (readStorage(AUTH_REMEMBER_KEY, session = false)) {
		"0" -> readStorage(AUTH_TOKEN_KEY, session = true)
		"1" -> readStorage(AUTH_TOKEN_KEY, session = false)
		else -> readStorage(AUTH_TOKEN_KEY, session = false)
			?: readStorage(AUTH_TOKEN_KEY, session = true)
	}

	private suspend fun readStorage(key: String, session: Boolean): String? {
		val raw = runCatching {
			if (session) {
				context.evaluateJs("https://$domain/", "sessionStorage.getItem(${JSONObject.quote(key)})")
			} else {
				WebViewHelper(context).getLocalStorageValue(domain, key)
			}
		}.getOrNull() ?: return null
		return decodeJavascriptString(raw)
	}

	private fun decodeJavascriptString(raw: String): String? {
		val value = raw.trim().let { encoded ->
			if (encoded.startsWith('"') && encoded.endsWith('"')) {
				runCatching { JSONObject("{\"v\":$encoded}").getString("v") }.getOrNull()
			} else {
				encoded
			}
		}?.trim()
		return value?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
	}

	private fun apiHeaders(token: String? = null): Headers = Headers.Builder()
		.add("Accept", "application/json")
		.add(CommonHeaders.X_REQUESTED_WITH, "XMLHttpRequest")
		.add(CommonHeaders.ORIGIN, "https://$domain")
		.add(CommonHeaders.REFERER, "https://$domain/")
		.apply { token?.let { add(CommonHeaders.AUTHORIZATION, "Bearer $it") } }
		.build()

	// ============================== List ===============================

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,    // default: most recently updated
		SortOrder.POPULARITY, // trending feed
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = false, // /api/search ignores status/genre
			isMultipleTagsSupported = false,      // /api/genres/<id> only takes one genre
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,   // "updating"
			MangaState.FINISHED,  // "completed"
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
		val response = if (!query.isNullOrEmpty()) {
			fetchSearchPage(page, query)
		} else {
			fetchBrowsePage(page, order, filter)
		}

		return response.parseListManga()
	}

	private suspend fun fetchSearchPage(page: Int, query: String): JSONObject = fetchMangaBatch(page) { apiPage ->
		"$API_HOST/search".toHttpUrl().newBuilder()
			.addQueryParameter("q", query)
			.addQueryParameter("page", apiPage.toString())
			.build()
	}

	private suspend fun fetchBrowsePage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): JSONObject {
		// The mixed `/genres/all` feed is now overwhelmingly novels. KiraKira's
		// dedicated feeds understand `type=manga` and preserve normal 20-item
		// pagination, so use them whenever no genre filter is selected.
		if (filter.tags.isEmpty()) {
			val path = if (order == SortOrder.POPULARITY) "trending-comics" else "recent-update-comics"
			val base = "$API_HOST/$path".toHttpUrl().newBuilder()
				.addQueryParameter("type", "manga")
				.apply {
					filter.states.firstOrNull()?.let { state ->
						addQueryParameter("status", when (state) {
							MangaState.ONGOING -> "updating"
							MangaState.FINISHED -> "completed"
							else -> return@let
						})
					}
				}
				.build()
			return fetchMangaBatch(page) { apiPage ->
				base.newBuilder().addQueryParameter("page", apiPage.toString()).build()
			}
		}

		// The endpoint is `/api/genres/<id>` where `<id>` matches the chosen
		// genre (or "all"). The `type` query param is a redundant echo of the
		// same id; sending both is what the web client does and keeps the
		// server happy.
		val genreId = filter.tags.firstOrNull()?.key ?: "all"
		val url = "$API_HOST/genres/$genreId".toHttpUrl().newBuilder()
			.addQueryParameter("type", genreId)
			.addQueryParameter("page", page.toString())
			.apply {
				when (order) {
					SortOrder.POPULARITY -> addQueryParameter("sort", "views")
					SortOrder.NEWEST -> addQueryParameter("sort", "new")
					else -> Unit // default = most recently updated, no sort param
				}
				filter.states.firstOrNull()?.let { state ->
					addQueryParameter("status", when (state) {
						MangaState.ONGOING -> "updating"
						MangaState.FINISHED -> "completed"
						else -> return@let
					})
				}
			}
			.build()
		return fetchMangaBatch(page) { apiPage ->
			url.newBuilder().setQueryParameter("page", apiPage.toString()).build()
		}
	}

	/**
	 * Tsuki's paginator only sees the number of items returned after parsing,
	 * while KiraKira mixes manga and novels in the same API pages. A page made
	 * entirely of novels would otherwise look like end-of-list. Read ahead and
	 * merge API pages until there is at least one manga, the server says the
	 * catalog ended, or the bounded read-ahead is exhausted. The normal feeds
	 * already use `type=manga`; the bound mainly protects unusual search and
	 * genre results from making dozens of requests in one UI load.
	 */
	private suspend fun fetchMangaBatch(
		startPage: Int,
		urlForPage: (Int) -> HttpUrl,
	): JSONObject {
		val comics = org.json.JSONArray()
		var apiPage = startPage
		var totalPages = startPage
		do {
			val payload = webClient.httpGet(urlForPage(apiPage), apiHeaders()).parseJson()
			totalPages = payload.optInt("total_pages", apiPage)
			payload.optJSONArray("comics")?.let { pageItems ->
				for (index in 0 until pageItems.length()) {
					val item = pageItems.optJSONObject(index) ?: continue
					if (!item.optString("type").equals("novel", ignoreCase = true)) {
						comics.put(item)
					}
				}
			}
			apiPage++
		} while (
			comics.length() == 0 &&
			apiPage <= totalPages &&
			apiPage - startPage < MAX_EMPTY_PAGE_SCAN
		)

		return JSONObject()
			.put("comics", comics)
			.put("current_page", apiPage - 1)
			.put("total_pages", totalPages)
	}

	/**
	 * The browse and search endpoints return the same shape (`{comics: [...],
	 * current_page, total_pages, status}`); the catalog item is the same
	 * `ComicDto` either way.
	 */
	private fun JSONObject.parseListManga(): List<Manga> {
		val array = optJSONArray("comics") ?: return emptyList()
		return array.mapJSONNotNull { jo ->
			val slug = jo.optString("id").takeIf { it.isNotBlank() } ?: return@mapJSONNotNull null
			// `type` is either "manga" (image chapters) or "novel" (text).
			// The "novel" rows appear in the catalog but have no chapter
			// images, so we drop them to keep the library clean.
			val type = jo.optString("type")
			if (type.equals("novel", ignoreCase = true)) return@mapJSONNotNull null

			val cover = jo.getStringOrNull("thumbnail")
				?: jo.getStringOrNull("banner_image_url")

			Manga(
				id = generateUid(slug),
				title = jo.optString("title"),
				altTitles = emptySet(),
				url = "/comics/$slug",
				publicUrl = "https://$domain/comics/$slug",
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = cover,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	// ============================== Details ===============================

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfter("/comics/").substringBefore('?').trim('/')
		if (slug.isBlank()) throw ParseException("Invalid manga url", manga.url)

		val json = webClient.httpGet("$API_HOST/comics/$slug".toHttpUrl(), apiHeaders()).parseJson()

		val title = json.optString("title").ifBlank { manga.title }
		val description = json.getStringOrNull("description")
		val authors = json.getStringOrNull("authors")?.takeIf { it.isNotBlank() }
			?.let { setOf(it) }
			.orEmpty()
		val cover = json.getStringOrNull("thumbnail")
			?: json.getStringOrNull("banner_image_url")
			?: manga.coverUrl

		val state = when (json.optString("status").lowercase(Locale.ROOT)) {
			"updating", "ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			else -> null
		}

		val tags = json.optJSONArray("genres")?.mapJSONToSet { genreJo ->
			val id = genreJo.optString("id")
			val name = genreJo.optString("name")
			MangaTag(title = name, key = id, source = source)
		}.orEmpty()

		val chapters = json.optJSONArray("chapters")?.mapJSONNotNull { chJo ->
			val id = chJo.optLong("id", -1L).takeIf { it > 0 } ?: return@mapJSONNotNull null
			val name = chJo.optString("name").ifBlank { return@mapJSONNotNull null }
			val coinPrice = chJo.optInt("coinPrice", 0)
			val isLocked = coinPrice > 0
			val unlockAt = chJo.getStringOrNull("unlockAt")
			val date = unlockAt?.let { parseIsoDate(it) } ?: 0L

			val number = regexChapterNumber.find(name)?.value?.toFloatOrNull() ?: 0f
			val titleText = if (isLocked) "🔒 $name" else name
			// `coinPrice > 0` is only a display hint. It does not mean the current
			// account has not bought the chapter, so never bake a blocking marker
			// into the URL; the authenticated chapter endpoint is authoritative.
			val url = "/chapters/$slug/$id"

			MangaChapter(
				id = generateUid(id),
				title = titleText,
				number = number,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = date,
				branch = null,
				source = source,
			)
		}.orEmpty()

		return manga.copy(
			title = title,
			description = description,
			authors = authors,
			state = state,
			tags = tags,
			coverUrl = cover,
			chapters = chapters,
		)
	}

	// ============================== Pages ===============================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = "https://$domain${chapter.url}".toHttpUrl()

		// A legacy database entry may still carry `?locked=1`; query parameters
		// do not affect path parsing and no longer block a legitimately bought chapter.
		val pathSegments = url.pathSegments
		if (pathSegments.size < 3 || pathSegments[0] != "chapters") {
			throw ParseException("Cannot parse chapter url", chapter.url)
		}
		val slug = pathSegments[1]
		val id = pathSegments[2]
		val apiUrl = "$API_HOST/comics/$slug/chapters/$id".toHttpUrl()

		// `httpGet` is decorated with `ensureSuccess()`, which already turns
		// 401 into `AuthRequiredException(source)` and 404 into
		// `NotFoundException`. Letting those bubble up gives the app the
		// right UX (login prompt vs. parse error) without us needing to
		// inspect the status code manually.
		val token = currentToken()
		val response = try {
			webClient.httpGet(apiUrl, apiHeaders(token))
		} catch (e: AuthRequiredException) {
			throw AuthRequiredException(
				source,
				IllegalStateException(
					if (token == null) "Chương trả phí — cần đăng nhập và mở khóa trên web"
					else "Phiên đăng nhập đã hết hạn hoặc chương chưa được mở khóa",
					e,
				),
			)
		}

		response.use {
			val json = it.parseJson()
			val images = json.optJSONArray("images") ?: throw ParseException(
				"Empty chapter payload",
				apiUrl.toString(),
			)

			val pages = images.mapJSONNotNull { img ->
				val src = img.optString("src").ifBlank { return@mapJSONNotNull null }
				MangaPage(
					id = generateUid(src),
					url = src,
					preview = null,
					source = source,
				)
			}

			if (pages.isEmpty()) {
				throw ParseException("No image URLs in chapter payload", apiUrl.toString())
			}
			return pages
		}
	}

	// ============================== Tags ===============================

	/**
	 * The available genres come from a dedicated endpoint so we don't have
	 * to scrape the homepage. The response wraps the actual list under
	 * `data.genres` with the standard `{status, data: {genres: [...]}}`
	 * envelope every other API call uses.
	 */
	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val json = try {
			webClient.httpGet("$API_HOST/genres".toHttpUrl(), apiHeaders()).parseJson()
		} catch (_: Exception) {
			return emptySet()
		}
		val array = json.optJSONObject("data")?.optJSONArray("genres") ?: return emptySet()
		return array.mapJSONToSet { jo ->
			val id = jo.optString("id")
			val name = jo.optString("name")
			MangaTag(title = name, key = id, source = source)
		}
	}

	// ============================== Resolve link ===============================

	/**
	 * Mirror of keiyoushi's deeplink config: support both `/comics/<slug>`
	 * (from the catalog) and `/chapters/<slug>/<id>` (from the reader).
	 * We only need the slug; the chapter is discovered when the user opens
	 * the manga from the resolved result.
	 */
	override suspend fun resolveLink(link: HttpUrl): Manga? {
		if (link.host != domain) return null
		val segments = link.pathSegments
		if (segments.size < 2) return null
		val slug = when (segments.firstOrNull()) {
			"comics", "chapters" -> segments.getOrNull(1) ?: return null
			else -> return null
		}
		return Manga(
			id = generateUid(slug),
			title = slug,
			altTitles = emptySet(),
			url = "/comics/$slug",
			publicUrl = "https://$domain/comics/$slug",
			rating = RATING_UNKNOWN,
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	// ============================== Utils ===============================

	private fun parseIsoDate(text: String): Long = runCatching {
		// API format: 2026-09-23T12:22:26.278Z
		isoDateFormat.parse(text)?.time
	}.getOrNull() ?: 0L

	private companion object {
		const val API_HOST = "https://api.truyenkira.net"
		const val AUTH_TOKEN_KEY = "auth_token"
		const val AUTH_REMEMBER_KEY = "auth_remember"
		const val MAX_EMPTY_PAGE_SCAN = 8

		val regexChapterNumber = Regex("""\d+(?:\.\d+)?""")

		val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
	}
}
