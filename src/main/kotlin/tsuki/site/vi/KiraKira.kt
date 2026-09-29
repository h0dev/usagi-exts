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
import tsuki.util.generateUid
import tsuki.util.getCookies
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
 * chương" UX, which is what the user actually wants. Paid chapters are flagged
 * with a lock prefix and a `?locked=1` marker in the chapter URL so the parser
 * refuses to fetch them without the user explicitly buying them on the site.
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
	 * The site authenticates through Discord OAuth; the resulting session
	 * cookie is `HttpOnly` on `truyenkira.net` and is sent to the API on
	 * `api.truyenkira.net` via the standard `Domain` attribute. We can't
	 * know the exact cookie name from the client (it is generated server
	 * side), so the cheapest reliable signal is "is there *any* cookie for
	 * this domain at all?" — the actual session validity is verified by
	 * the API on each call and surfaces as a 401 in [getPages].
	 */
	override suspend fun isAuthorized(): Boolean =
		context.cookieJar.getCookies(domain).isNotEmpty()

	override suspend fun getUsername(): String? = null

	/**
	 * `SourceAuthActivity` opens this URL in a WebView so the user can sign
	 * in through Discord. After OAuth completes the SPA sets the session
	 * cookie, which is then picked up by the app's `CookieJar` and sent to
	 * the API on subsequent requests.
	 */
	override val authUrl: String
		get() = "https://$domain/auth/login"

	// ============================== List ===============================

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,    // default: most recently updated
		SortOrder.POPULARITY, // sort=views
		SortOrder.NEWEST,     // sort=new
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

	private suspend fun fetchSearchPage(page: Int, query: String): JSONObject {
		val url = "$API_HOST/search".toHttpUrl().newBuilder()
			.addQueryParameter("q", query)
			.addQueryParameter("page", page.toString())
			.build()
		return webClient.httpGet(url).parseJson()
	}

	private suspend fun fetchBrowsePage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): JSONObject {
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
		return webClient.httpGet(url).parseJson()
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

		val json = webClient.httpGet("$API_HOST/comics/$slug".toHttpUrl()).parseJson()

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
			val url = buildString {
				append("/chapters/$slug/$id")
				if (isLocked) append("?locked=1")
			}

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
		if (url.queryParameter("locked") == "1") {
			// The chapter is paywalled; the user has to purchase it on the
			// site. We refuse to probe because the only way to obtain pages
			// is a legitimate purchase and the API will reject any other
			// attempt.
			throw tsuki.exception.AuthRequiredException(
				source,
				Exception(LOCKED_CHAPTER_MESSAGE),
			)
		}

		// Chapter URLs come in two shapes from the database:
		//   /chapters/<slug>/<id>            (current)
		//   /chapters/<slug>/<id>?locked=1   (already locked)
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
		val response = webClient.httpGet(apiUrl)

		response.use {
			val json = it.parseJson()
			val coinPrice = json.optInt("coinPrice", 0)
			val isPurchased = json.optBoolean("isPurchased", false)
			if (coinPrice > 0 && !isPurchased) {
				throw tsuki.exception.AuthRequiredException(
					source,
					Exception(LOCKED_CHAPTER_MESSAGE),
				)
			}

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
			webClient.httpGet("$API_HOST/genres".toHttpUrl()).parseJson()
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
		const val LOCKED_CHAPTER_MESSAGE = "Chương này cần mua bằng xu trên trang web"

		val regexChapterNumber = Regex("""\d+(?:\.\d+)?""")

		val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
	}
}
