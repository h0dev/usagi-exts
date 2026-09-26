package tsuki.site.vi

import androidx.collection.arraySetOf
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import tsuki.MangaLoaderContext
import tsuki.MangaParserAuthProvider
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.AuthRequiredException
import tsuki.model.*
import tsuki.network.CommonHeaders
import tsuki.network.OkHttpWebClient
import tsuki.util.*
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException

private const val PAGE_SIZE = 30
private const val CHAPTER_SEPARATOR = "/chuong-"
private const val AUTH_MESSAGE = "Phiên làm việc đã hết hạn, vui lòng đăng nhập lại"
private const val BEARER_PREFIX = "Bearer "
private const val DEBUG_LOG = true
private const val LOG_TAG = "[GocTruyenTranhVui]"
private const val AUTHORIZATION_KEY = "Authorization"
private const val USER_INFO_KEY = "user_info"
private const val GOOGLE_CLIENT_ID = "957735517445-b33dahmc67lr938l52j82v82oetd3o94.apps.googleusercontent.com"

private val COMIC_ID_REGEX = Regex("""id:\s*"([^"]+)"""")
private val COMIC_NAME_REGEX = Regex("""nameEn:\s*`([^`]+)`""")

/**
 * Góc Truyện Tranh Vui — https://goctruyentranhvui41.com
 *
 * The site is a Vue app on top of a JSON API (`/api/v2/...`). Browsing works anonymously,
 * but the chapter list (`/api/comic/<id>/chapter`) and the page list
 * (`/api/chapter/loadAll`) require an account: the site keeps the whole `Authorization`
 * header value ("Bearer ...") in the local storage and sends it with every request.
 *
 * So the parser reads that value from the WebView storage (see [MangaParserAuthProvider])
 * and reuses it for its own requests; when the value is missing, [AuthRequiredException]
 * is thrown so the app offers the WebView login.
 */
@MangaSourceParser("GOCTRUYENTRANHVUI", "Góc Truyện Tranh Vui", "vi")
internal class GocTruyenTranhVui(context: MangaLoaderContext):
	PagedMangaParser(context, MangaParserSource.GOCTRUYENTRANHVUI, PAGE_SIZE), MangaParserAuthProvider {

	/**
	 * Tsuki's rateLimit throws TooManyRequestExceptions instead of waiting, so the window
	 * has to be generous: opening a manga costs 3 requests (details page, chapter api,
	 * pages api) and that is reached right away when the user starts reading.
	 * Keiyoushi asks for 3 requests per *second*; Mihon waits, Tsuki does not.
	 */
	override val webClient = OkHttpWebClient(
		context.httpClient.newBuilder()
			.callTimeout(20.seconds)
			.rateLimit(30, 1.seconds)
			.build(),
		source,
	)

	override val configKeyDomain = ConfigKey.Domain(
		"goctruyentranhvui41.com",
		"goctruyentranhvui30.com",
	)

	override val userAgentKey = ConfigKey.UserAgent(
		"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.46 Mobile Safari/537.36",
	)

	private val baseUrl: String get() = "https://$domain"

	/**
	 * Diagnostics for the report "the sign in row stays grey": the app swallows parser
	 * exceptions and logs nothing by itself, so every step that depends on the token is
	 * printed to logcat (tag `System.out`) while [DEBUG_LOG] is on.
	 */
	private fun log(message: String) {
		if (DEBUG_LOG) {
			// stdout lands in logcat as "System.out"
			println("$LOG_TAG $message")
		}
	}

	private fun String?.describeToken(): String = when {
		this == null -> "none"
		isExpiredJwt() -> "expired(${length} chars)"
		else -> "${length} chars"
	}

	private var cachedToken: String? = null
	private var cachedUsername: String? = null
	private var cachedCategories: Set<MangaTag>? = null

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = true,
		isSearchWithFiltersSupported = true,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = loadCategories(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	// region authorization

	/**
	 * The site's own sign in button only posts to `/api/login/google` and then navigates
	 * to the url that call returns — but that page is a heavy Vue app, so in an embedded
	 * WebView the tap usually lands before the handler is bound and nothing happens.
	 * Going straight to the provider does the same thing without the middleman: google
	 * sends the user back to `$baseUrl/login?code=...`, which is the page that really
	 * writes the token into the local storage.
	 *
	 * The url is built locally (no request on the sign in path): it is what
	 * `POST /api/login/google` has been answering, client id included.
	 */
	override val authUrl: String
		get() = defaultLoginUrl()

	private fun defaultLoginUrl(): String = buildString {
		append("https://accounts.google.com/o/oauth2/v2/auth")
		append("?scope=profile")
		append("&access_type=offline")
		append("&include_granted_scopes=true")
		append("&response_type=code")
		append("&state=GA")
		append("&client_id=").append(GOOGLE_CLIENT_ID)
		append("&redirect_uri=").append("$baseUrl/login")
	}

	override suspend fun isAuthorized(): Boolean {
		// currentToken() only touches the WebView again while there is no token, so a signed
		// in user does not pay for a round trip on every check
		val token = currentToken()
		log("isAuthorized: domain=$domain token=${token.describeToken()}")
		if (token == null) {
			return false
		}
		// an expired token must not keep the "sign in" row of the app disabled forever
		return !token.isExpiredJwt()
	}

	override suspend fun getUsername(): String {
		cachedUsername?.let { return it }
		val name = localStorage(USER_INFO_KEY)?.let { raw ->
			runCatching { JSONObject(raw).optString("name") }.getOrNull()
		}
		if (name.isNullOrBlank()) {
			throw AuthRequiredException(source, IllegalStateException(AUTH_MESSAGE))
		}
		cachedUsername = name
		return name
	}

	/**
	 * The site stores the ready-to-use header value in the local storage, so it is passed
	 * to the API as is — there is no need to build a "Bearer" prefix ourselves.
	 *
	 * The value is re-read from the WebView only while it is missing: the site rewrites it
	 * on sign in/out and the user signs in *after* the first check, so a cached "no token"
	 * would hide the account for the rest of the session.
	 */
	private suspend fun readToken(): String? = localStorage(AUTHORIZATION_KEY)
		.also { cachedToken = it }

	private suspend fun currentToken(forceRefresh: Boolean = false): String? =
		if (forceRefresh) readToken() else cachedToken ?: readToken()

	/**
	 * The site hands out "Bearer <jwt>", so the expiration date can be checked locally.
	 * Anything else (or a token we cannot read) is taken as valid.
	 */
	private fun String.isExpiredJwt(): Boolean {
		val payload = substringAfter(BEARER_PREFIX, this)
			.trim()
			.split('.')
			.getOrNull(1)
			?.decodeBase64()
			?.utf8()
			?.let { runCatching { JSONObject(it) }.getOrNull() }
			?: return false
		val expiresAt = payload.optLong("exp", 0L)
		return expiresAt > 0L && expiresAt * 1_000L <= System.currentTimeMillis()
	}

	private suspend fun localStorage(key: String): String? = runCatching {
		WebViewHelper(context)
			.getLocalStorageValue(domain, key)
			?.trim()
			?.removeSurrounding("\"")
			?.trim()
			?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
	}.getOrNull()

	private suspend fun apiHeaders(): Headers = Headers.Builder()
		.add(CommonHeaders.REFERER, "$baseUrl/")
		.add(CommonHeaders.X_REQUESTED_WITH, "XMLHttpRequest")
		.apply { currentToken()?.let { add(CommonHeaders.AUTHORIZATION, it) } }
		.build()

	// endregion

	// region catalog

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = apiUrl("search").newBuilder().apply {
			addQueryParameter("p", (page - 1).coerceAtLeast(0).toString())
			filter.query
				?.takeIf { it.isNotBlank() }
				?.let { addQueryParameter("searchValue", it) }
			addQueryParameter("orders[]", order.toApiSort())
			filter.tags.forEach { addQueryParameter("categories[]", it.key) }
			filter.states.forEach { state ->
				state.toApiStatus()?.let { addQueryParameter("status[]", it) }
			}
		}.build()

		val data = webClient.httpGet(url, apiHeaders())
			.parseJson()
			.optJSONObject("result")
			?.optJSONArray("data")
			?: return emptyList()

		val items = (0 until data.length()).mapNotNull { index ->
			data.optJSONObject(index)?.toManga()
		}
		log("list: page=$page order=$order query=${filter.query} tags=${filter.tags.map { it.key }} -> ${items.size} items")
		return items
	}

	private fun JSONObject.toManga(): Manga? {
		val comicId = optString("id").takeIf { it.isNotBlank() } ?: return null
		val slug = optString("nameEn").takeIf { it.isNotBlank() } ?: return null

		val codes = optJSONArray("categoryCode")
		val names = optJSONArray("category")
		val tags = (0 until (codes?.length() ?: 0)).mapNotNullTo(mutableSetOf()) { index ->
			tagOf(codes?.optString(index).orEmpty(), names?.optString(index).orEmpty())
		}

		return Manga(
			id = generateUid(comicId),
			title = optString("name"),
			altTitles = optString("otherName")
				.split(',')
				.mapNotNull { it.trim().takeIf(String::isNotBlank) }
				.toSet(),
			url = "$comicId:$slug",
			publicUrl = "$baseUrl/truyen/$slug",
			rating = optDouble("evaluationScore", 0.0).toFloat().let {
				if (it > 0f) (it / 5f).coerceIn(0f, 1f) else RATING_UNKNOWN
			},
			contentRating = null,
			coverUrl = optString("photo").let { if (it.startsWith("http")) it else "$baseUrl$it" },
			tags = tags,
			state = optString("statusCode").toMangaState(),
			authors = setOfNotNull(optString("author").takeIf { it.isNotBlank() }),
			description = optString("description").takeIf { it.isNotBlank() },
			source = source,
		)
	}

	// endregion

	override suspend fun getDetails(manga: Manga): Manga {
		val listingId = mangaComicId(manga)
		val listingSlug = mangaSlug(manga)

		// The listing api already carries the title, cover, description, tags and status,
		// while the details page is ~140 KB of html with ads on top of it. Only read it
		// when something is actually missing (a manga opened by link, for example), so
		// opening a manga from the list costs a single request.
		if (listingId != null && listingSlug != null && manga.coverUrl != null && manga.description != null) {
			log("details: $listingId:$listingSlug from the listing, page skipped")
			return manga.copy(chapters = loadChapters(listingId, listingSlug))
		}

		val pageUrl = mangaPageUrl(manga)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		val script = doc.select("script")
			.firstOrNull { it.data().contains("const comic =") }
			?.data()

		val slug = script
			?.let { COMIC_NAME_REGEX.find(it)?.groupValues?.get(1) }
			?: mangaSlug(manga)
			?: throw IllegalStateException("Không tìm thấy tên truyện trong $pageUrl")

		val comicId = script
			?.let { COMIC_ID_REGEX.find(it)?.groupValues?.get(1) }
			?: doc.selectFirst("#comic-id-comment")?.attr("value")?.takeIf { it.isNotBlank() }
			?: mangaComicId(manga)
			?: comicIdFromPage(slug)
			?: throw IllegalStateException("Không tìm thấy mã truyện trong $pageUrl")

		return manga.copy(
			title = doc.selectFirst(".v-card-title")?.textOrNull() ?: manga.title,
			coverUrl = doc.selectFirst("img.image")?.absUrl("src") ?: manga.coverUrl,
			tags = manga.tags + doc.select(".group-content > .v-chip-link").mapNotNull { tagByName(it.text()) },
			state = doc.selectFirst(".mb-1:contains(Trạng thái:) span")?.textOrNull()?.toMangaState() ?: manga.state,
			authors = setOfNotNull(doc.selectFirst(".mb-1:contains(Tác giả:) span")?.textOrNull()),
			description = doc.selectFirst(".v-card-text")?.textOrNull() ?: manga.description,
			chapters = loadChapters(comicId, slug),
		)
	}

	private suspend fun loadChapters(comicId: String, slug: String): List<MangaChapter> {
		if (currentToken() == null) {
			throw AuthRequiredException(source, IllegalStateException(AUTH_MESSAGE))
		}

		var error: String? = null

		suspend fun requestChapters(): List<MangaChapter>? = try {
			val json = webClient
				.httpGet("$baseUrl/api/comic/$comicId/chapter?limit=-1", apiHeaders())
				.parseJson()
			val result = json.optJSONObject("result")
			if (result == null) {
				error = json.errorMessage()
				null
			} else {
				// the api returns chapters from the newest to the oldest one
				result.optJSONArray("chapters")?.mapChapters(reversed = true) { _, item ->
					val number = item.optString("numberChapter")
					val title = item.optString("name")
					MangaChapter(
						id = generateUid("/truyen/$slug/chuong-$number"),
						title = title.takeUnless { it.isBlank() || it == "N/A" } ?: "Chương $number",
						number = number.toFloatOrNull() ?: -1f,
						volume = 0,
						url = "/truyen/$slug/chuong-$number#$comicId",
						scanlator = null,
						uploadDate = item.optLong("updateTime", 0L),
						branch = null,
						source = source,
					)
				} ?: emptyList()
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			error = e.message
			null
		}

		var chapters = requestChapters()
		log("chapters: id=$comicId slug=$slug token=${currentToken().describeToken()} -> ${chapters?.size ?: "failed($error)"}")
		if (chapters == null) {
			// the session may have expired — the user could have signed in again in the WebView
			currentToken(forceRefresh = true)
			chapters = requestChapters()
			log("chapters: retry with token=${currentToken().describeToken()} -> ${chapters?.size ?: "failed($error)"}")
		}

		return chapters
			?: throw AuthRequiredException(source, IllegalStateException(error ?: AUTH_MESSAGE))
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val ref = chapterRef(chapter)
		if (currentToken() == null) {
			throw AuthRequiredException(source, IllegalStateException(AUTH_MESSAGE))
		}

		suspend fun requestPages(): List<MangaPage>? = try {
			val payload = buildString {
				append("comicId=").append(ref.comicId)
				append("&chapterNumber=").append(ref.number)
				append("&nameEn=").append(ref.slug)
			}
			val result = webClient
				.httpPost("$baseUrl/api/chapter/loadAll".toHttpUrl(), payload, apiHeaders())
				.parseJson()
				.optJSONObject("result")
			val data = result?.optJSONArray("data")
			data?.let {
				(0 until it.length()).mapNotNull { index ->
					val raw = it.optString(index).takeIf(String::isNotBlank) ?: return@mapNotNull null
					val url = if (raw.startsWith("/")) "$baseUrl$raw" else raw
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				}
			}
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			null
		}

		var pages = requestPages()
		log("pages: $ref token=${currentToken().describeToken()} -> ${pages?.size ?: "failed"}")
		if (pages == null) {
			// the stored token may have expired — pick the fresh one up and warm up the cookies
			currentToken(forceRefresh = true)
			runCatching { webClient.httpGet("$baseUrl/truyen/${ref.slug}") }.getOrNull()?.close()
			pages = requestPages()
			log("pages: retry with token=${currentToken().describeToken()} -> ${pages?.size ?: "failed"}")
		}

		return pages
			?: throw AuthRequiredException(source, IllegalStateException(AUTH_MESSAGE))
	}

	// region helpers

	private fun apiUrl(path: String) = "$baseUrl/api/v2/$path".toHttpUrl()

	private suspend fun loadCategories(): Set<MangaTag> {
		cachedCategories?.let { return it }
		val fetched = runCatching {
			val array = webClient.httpGet(apiUrl("category"), apiHeaders()).parseJsonArray()
			(0 until array.length()).mapNotNullTo(arraySetOf()) { index ->
				val item = array.optJSONObject(index) ?: return@mapNotNullTo null
				val code = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
				val name = item.optString("name").takeIf { it.isNotBlank() } ?: code
				MangaTag(title = name, key = code, source = source)
			}
		}.getOrNull()

		val categories: Set<MangaTag> = fetched?.takeIf { it.isNotEmpty() } ?: FALLBACK_TAGS
		log("categories: ${categories.size} (fetched=${fetched != null})")
		cachedCategories = categories
		return categories
	}

	private fun JSONObject.errorMessage(): String = optJSONArray("messages")
		?.let { messages -> (0 until messages.length()).map { messages.optString(it) }.firstOrNull { it.isNotBlank() } }
		?: AUTH_MESSAGE

	private fun tagOf(code: String, name: String): MangaTag? {
		if (code.isBlank()) {
			return tagByName(name)
		}
		val title = FALLBACK_TAGS.firstOrNull { it.key == code }?.title ?: name.takeIf { it.isNotBlank() } ?: code
		return MangaTag(key = code, title = title, source = source)
	}

	private fun tagByName(name: String): MangaTag? {
		val title = name.trim().takeIf { it.isNotEmpty() } ?: return null
		val known = FALLBACK_TAGS.firstOrNull { it.title.equals(title, ignoreCase = true) }
		return known?.copy(source = source) ?: MangaTag(key = title, title = title, source = source)
	}

	private fun SortOrder.toApiSort(): String = when (this) {
		SortOrder.POPULARITY -> "viewCount"
		SortOrder.RATING -> "evaluationScore"
		SortOrder.NEWEST -> "createdAt"
		else -> "recentDate"
	}

	private fun MangaState.toApiStatus(): String? = when (this) {
		MangaState.ONGOING -> "PRG"
		MangaState.FINISHED -> "END"
		else -> null
	}

	private fun String.toMangaState(): MangaState? = when (this) {
		"PRG", "Đang thực hiện" -> MangaState.ONGOING
		"END", "Hoàn thành" -> MangaState.FINISHED
		else -> null
	}

	/**
	 * The manga may come either from the list ("<comicId>:<slug>") or from a link
	 * (a relative url like "/truyen/<slug>")
	 */
	private fun mangaSlug(manga: Manga): String? = manga.url
		.substringAfter(':')
		.substringAfter("/truyen/")
		.substringBefore('/')
		.takeIf { it.isNotBlank() }

	private fun mangaComicId(manga: Manga): String? = manga.url
		.substringBefore(':')
		.takeIf { it.isNotBlank() && !it.contains('/') }

	private fun mangaPageUrl(manga: Manga): String = "$baseUrl/truyen/${mangaSlug(manga) ?: manga.url}"

	private suspend fun comicIdFromPage(slug: String): String? = runCatching {
		webClient.httpGet("$baseUrl/truyen/$slug").parseHtml()
			.selectFirst("#comic-id-comment")
			?.attr("value")
			?.takeIf { it.isNotBlank() }
	}.getOrNull()

	/**
	 * Supports both the current chapter url ("/truyen/<slug>/chuong-<number>#<comicId>")
	 * and the legacy one ("<comicId>:<number>/<slug>") that may still be stored in the database
	 */
	private suspend fun chapterRef(chapter: MangaChapter): ChapterRef {
		val url = chapter.url
		if (url.contains(CHAPTER_SEPARATOR)) {
			val slug = url.substringAfter("/truyen/").substringBefore(CHAPTER_SEPARATOR)
			val number = url.substringAfter(CHAPTER_SEPARATOR).substringBefore('#').substringBefore('/')
			if (slug.isNotBlank() && number.isNotBlank()) {
				val comicId = url.substringAfter('#', "")
					.takeIf { it.isNotBlank() }
					?: comicIdFromPage(slug)
				if (comicId != null) {
					return ChapterRef(comicId, slug, number)
				}
			}
		}

		val comicId = url.substringBefore(':')
		val rest = url.substringAfter(':', "")
		val number = rest.substringBefore('/')
		val slug = rest.substringAfter('/', "")
		if (comicId.isNotBlank() && number.isNotBlank() && slug.isNotBlank()) {
			return ChapterRef(comicId, slug, number)
		}

		throw IllegalStateException("Không đọc được thông tin chương: $url")
	}

	// endregion

	private data class ChapterRef(
		val comicId: String,
		val slug: String,
		val number: String,
	) {
		override fun toString(): String = "comic=$comicId slug=$slug number=$number"
	}

	private companion object {

		/**
		 * Fallback for the `/api/v2/category` endpoint, kept in sync with the site
		 */
		private val FALLBACK_TAGS = arraySetOf(
			MangaTag("Anime", "ANI", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Drama", "DRA", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Josei", "JOS", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Manhwa", "MAW", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("One Shot", "OSH", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Shounen", "SHO", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Webtoons", "WEB", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Shoujo", "SHJ", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Harem", "HAR", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Ecchi", "ECC", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Mature", "MAT", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Slice of life", "SOL", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Isekai", "ISE", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Manga", "MAG", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Manhua", "MAU", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Hành Động", "ACT", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Phiêu Lưu", "ADV", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Hài Hước", "COM", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Võ Thuật", "MAA", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Huyền Bí", "MYS", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Lãng Mạn", "ROM", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Thể Thao", "SPO", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Học Đường", "SCL", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Lịch Sử", "HIS", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Kinh Dị", "HOR", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Siêu Nhiên", "SUN", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Bi Kịch", "TRA", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Trùng Sinh", "RED", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Game", "GAM", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Viễn Tưởng", "FTS", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Khoa Học", "SCF", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Truyện Màu", "COI", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Người Lớn", "ADU", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("BoyLove", "BBL", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Hầm Ngục", "DUN", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Săn Bắn", "HUNT", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Ngôn Từ Nhạy Cảm", "NTNC", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Doujinshi", "DOU", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Bạo Lực", "BLM", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Ngôn Tình", "NTT", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Nữ Cường", "NCT", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Gender Bender", "GDB", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Murim", "MRR", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Leo Tháp", "LTT", MangaParserSource.GOCTRUYENTRANHVUI),
			MangaTag("Nấu Ăn", "COO", MangaParserSource.GOCTRUYENTRANHVUI),
		)
	}
}
