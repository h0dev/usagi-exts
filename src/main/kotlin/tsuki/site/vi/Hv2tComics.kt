package tsuki.site.vi

import androidx.collection.arraySetOf
import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import tsuki.MangaLoaderContext
import tsuki.MangaParserAuthProvider
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.AuthRequiredException
import tsuki.exception.NotFoundException
import tsuki.model.*
import tsuki.network.OkHttpWebClient
import tsuki.util.generateUid
import tsuki.util.getCookies
import tsuki.util.insertCookies
import tsuki.util.parseJson
import tsuki.util.rateLimit
import java.util.*
import kotlin.time.Duration.Companion.seconds

/**
 * HV2T Comics — https://hv2tcomics.net
 *
 * The site is a Next.js SPA over a JSON API under `/api`. Everything — the
 * catalog (`/comics`, `/comics/<slug>`, `/tags`) and the chapter reader
 * (`/comics/<comicKey>/<chapterSlug>/view`) — sits behind a Cloudflare
 * Worker that demands an `hv2t_adult_gate` cookie; without it the API
 * answers `403 {"code":"ADULT_GATE_REQUIRED"}`.
 *
 * The gate cookie has the form `v1.<unix_ts>.<sig>` and the signature is
 * computed server-side, so the parser cannot forge it: the app's auth
 * WebView ([authUrl]) loads the sign-in page, the user confirms the gate
 * (and signs in through Discord if they want to read), and both cookies
 * land in the WebView's `CookieManager`. On Usagi the jar is
 * `AndroidCookieJar`, which reads straight through `CookieManager`, so the
 * very same cookies are already visible to [context]`​.cookieJar` and get
 * attached to the OkHttp requests below. No `evaluateJs` round-trip is
 * involved anywhere in this parser.
 *
 * ## Chapter reader
 *
 * `GET /api/comics/<comicKey>/<chapterSlug>/view` (requires a signed-in
 * session — free chapters included) returns
 *
 * ```json
 * {"success":true,
 *  "data":"<base64 ciphertext>",
 * "timestamp":1795059269930,
 * "key":"hv2t-niga-93eb5c856909b900"}
 * ```
 *
 * The payload is XOR-obfuscated with the ASCII bytes of `key` cycling over
 * the ciphertext and the low 8 bytes of `timestamp`, shifted one bit per
 * byte:
 *
 * ```
 * plain[i] = cipher[i] xor ((timestamp ushr (i and 7)) and 0xff) xor key[i % key.length]
 * ```
 *
 * That is exactly what the site's `/security.wasm` (`decrypt_buffer`) does
 * — the module first checks the 9-byte key prefix `hv2t-niga` and does
 * nothing otherwise — so no WASM runtime is needed on our side.
 * [decryptChapterPayload] mirrors it byte for byte (verified against the
 * real module on captured payloads, see the test harness).
 *
 * The decrypted JSON holds `images` (absolute CDN URLs, public — no cookie
 * or referer needed), `media_revision`, `media_expires_at`, `info`,
 * `comic` and `chapters_list`. When the CDN has not finished encoding a
 * freshly uploaded chapter, `media_revision` is missing/invalid and the
 * site retries with 250/750/1500 ms backoff; we do the same.
 *
 * ## Ids
 *
 * The catalog exposes two ids: the numeric detail id (`id`) and the public
 * code (`public_code`, e.g. 131427). The reader route and the chapter API
 * both key off the **public code** (or the slug); the numeric id answers
 * `404 {"message":"Chương không tồn tại"}`. Chapters therefore store
 * `<comicKey>/<chapterId>:<chapterSlug>` in [MangaChapter.url], where
 * `comicKey` is the public code.
 */
@MangaSourceParser("HV2TCOMICS", "HV2T Comics", "vi", type = ContentType.HENTAI)
internal class Hv2tComics(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.HV2TCOMICS, PAGE_SIZE), MangaParserAuthProvider {

	override val configKeyDomain = ConfigKey.Domain(
		"hv2tcomics.net",
		"hv2tcomics.com",
	)

	/** `println` tracing is tagged so it is easy to grep in `adb logcat`. */
	private val debugLog = true
	private val logTag = "[Hv2tComics]"
	private fun log(message: String) {
		if (debugLog) println("$logTag $message")
	}

	override val userAgentKey = ConfigKey.UserAgent(
		"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.46 Mobile Safari/537.36",
	)

	private val baseUrl: String get() = "https://$domain"

	override val webClient = OkHttpWebClient(
		context.httpClient.newBuilder()
			.rateLimit(30, 1.seconds)
			.callTimeout(20.seconds)
			.build(),
		source,
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,   // site: latest
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = loadTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	// region auth

	/**
	 * What the app's `SourceAuthActivity` opens. It is the first screen that
	 * both asks for the age gate and offers "Đăng nhập với Discord", so a
	 * single visit is enough to make every call below work.
	 */
	override val authUrl: String get() = "$baseUrl/auth/login"

	/**
	 * True as soon as the jar holds a non-blank `access_token`.
	 *
	 * Reading the jar is free (no request, no WebView), so this is safe to
	 * call from every `onPageFinished` of the auth activity — which is why
	 * the result is not cached: the activity fires it several times during
	 * the Discord OAuth redirects and a TTL would only add staleness.
	 */
	override suspend fun isAuthorized(): Boolean {
		val token = context.cookieJar.loadForRequest("$baseUrl/".toHttpUrl())
			.firstOrNull { it.name == ACCESS_TOKEN_COOKIE && it.value.isNotBlank() }
		log("isAuthorized: access_token=${token?.let { "present(${it.value.length})" } ?: "missing"}")
		return token != null
	}

	/** Shown in the source settings row, so the user can see who is signed in. */
	override suspend fun getUsername(): String? = runCatching {
		val payload = webClient.httpGet(apiUrl("users/me")).parseJson()
		payload.optJSONObject("data")?.optString("username")?.takeIf { it.isNotBlank() }
	}.getOrNull()

	/** Throws the "open the auth WebView" signal while the gate cookie is missing. */
	private fun requireAgeGate() {
		if (context.cookieJar.getCookies(domain).any { it.name == ADULT_GATE_COOKIE }) return
		throw AuthRequiredException(
			source,
			IllegalStateException("Cần xác nhận đủ 18 tuổi (và đăng nhập) trong webview của nguồn"),
		)
	}

	// endregion

	// region catalog

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		requireAgeGate()
		val url = apiUrl("comics").newBuilder().apply {
			addQueryParameter("page", page.toString())
			addQueryParameter("sort", if (order == SortOrder.POPULARITY) "popular" else "latest")
			filter.query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
			if (filter.tags.isNotEmpty()) {
				addQueryParameter("tags_inc", filter.tags.joinToString(",") { it.key })
			}
			if (filter.tagsExclude.isNotEmpty()) {
				addQueryParameter("tags_exc", filter.tagsExclude.joinToString(",") { it.key })
			}
		}.build()

		val payload = webClient.httpGet(url).parseJson()
		if (payload.optBoolean("success") != true) {
			return emptyList()
		}
		val items = payload.optJSONArray("data") ?: return emptyList()
		return (0 until items.length()).mapNotNull { index ->
			items.optJSONObject(index)?.toManga()
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		requireAgeGate()
		// The detail endpoint accepts the slug (and the public code); listing
		// ids do not match, so [Manga.url] holds the slug.
		val payload = webClient.httpGet(apiUrl("comics/${manga.url}")).parseJson()
		if (payload.optBoolean("success") != true) {
			return manga
		}
		val data = payload.optJSONObject("data") ?: return manga

		val updated = data.toManga() ?: return manga
		return manga.copy(
			title = updated.title,
			coverUrl = updated.coverUrl ?: manga.coverUrl,
			description = updated.description ?: manga.description,
			tags = updated.tags + manga.tags,
			state = updated.state ?: manga.state,
			authors = updated.authors.takeIf { it.isNotEmpty() } ?: manga.authors,
			chapters = data.chapterList(comicKeyOf(data)),
		)
	}

	override suspend fun resolveLink(link: HttpUrl): Manga? {
		requireAgeGate()
		val segments = link.pathSegments
		val key = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
		return runCatching {
			val payload = webClient.httpGet(apiUrl("comics/$key")).parseJson()
			if (payload.optBoolean("success") != true) return@runCatching null
			payload.optJSONObject("data")?.toManga()
		}.getOrNull()
	}

	private suspend fun loadTags(): Set<MangaTag> = runCatching {
		requireAgeGate()
		val payload = webClient.httpGet(apiUrl("tags")).parseJson()
		if (payload.optBoolean("success") != true) return@runCatching emptySet()
		val arr = payload.optJSONArray("data") ?: return@runCatching emptySet()
		(0 until arr.length()).mapNotNullTo(arraySetOf<MangaTag>()) { idx ->
			val obj = arr.optJSONObject(idx) ?: return@mapNotNullTo null
			val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
			val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
			MangaTag(title = name, key = slug, source = source)
		}
	}.getOrDefault(emptySet())

	// endregion

	// region pages

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		requireAgeGate()
		val ref = parseChapterUrl(chapter.url)
		val lockedHint = chapter.title?.startsWith(LOCK_PREFIX) == true
		val manifest = fetchManifest(ref, lockedHint)
		val images = manifest.optJSONArray("images") ?: JSONArray()
		log("getPages: ${ref.comicKey}/${ref.chapterSlug} -> ${images.length()} ảnh")
		if (images.length() == 0) {
			throw IllegalStateException("Chương không có ảnh nào (media_revision=${manifest.optString("media_revision")})")
		}
		return (0 until images.length()).mapNotNull { index ->
			val url = images.optString(index).takeIf { it.isNotBlank() } ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = if (url.startsWith("http")) url else "$baseUrl$url",
				preview = null,
				source = source,
			)
		}
	}

	/**
	 * Fetch and decrypt the chapter manifest, retrying the cases the site
	 * itself retries (fresh chapters whose CDN media is still being encoded,
	 * 5xx and network hiccups) with the same 250/750/1500 ms backoff.
	 *
	 * 401 becomes [AuthRequiredException] straight from `ensureSuccess`;
	 * 403 and 404 are turned into readable errors — a 403 is either a paid
	 * chapter the session has not unlocked or a rejected session, and both
	 * are fixed from the auth WebView.
	 */
	private suspend fun fetchManifest(ref: ChapterRef, lockedHint: Boolean): JSONObject {
		val path = "comics/${ref.comicKey}/${ref.chapterSlug}/view"
		val headers = Headers.Builder()
			.add("Accept", "application/json, text/plain, */*")
			.add("Referer", "$baseUrl/truyen/${ref.comicKey}/${ref.chapterSlug}")
			.build()

		var lastError: Throwable? = null
		for (pause in RETRY_PAUSES_MS) {
			if (pause > 0) delay(pause)
			try {
				val payload = webClient.httpGet(apiUrl(path), headers).parseJson()
				if (payload.optBoolean("success") != true) {
					val message = payload.optString("message")
					if (payload.optString("code") == "ADULT_GATE_REQUIRED") {
						throw AuthRequiredException(source, IllegalStateException(message))
					}
					lastError = IllegalStateException(message.ifBlank { "API trả về success=false" })
					continue
				}
				val manifest = decryptManifest(payload)
				if (manifest == null) {
					lastError = IllegalStateException("Manifest chưa sẵn sàng (media_revision)")
					continue
				}
				return manifest
			} catch (e: AuthRequiredException) {
				throw e
			} catch (e: NotFoundException) {
				throw IllegalStateException("Không tìm thấy chương ${ref.chapterSlug} (404)", e)
			} catch (e: HttpStatusException) {
				when (e.statusCode) {
					403 -> throw AuthRequiredException(
						source,
						IllegalStateException(
							if (lockedHint) "Chương trả phí — cần đăng nhập và mở khoá trên web"
							else "Truy cập bị từ chối (403) — đăng nhập lại trong webview",
						),
					)
					in RETRYABLE_STATUS -> lastError = e
					else -> throw IllegalStateException("HTTP ${e.statusCode} khi đọc chương", e)
				}
			} catch (e: Exception) {
				lastError = e
			}
		}
		throw IllegalStateException(
			"Không đọc được chương ${ref.chapterSlug}: ${lastError?.message}",
			lastError,
		)
	}

	/**
	 * Decrypt the manifest body. `data` is base64 ciphertext, `key` the
	 * `hv2t-niga…` ASCII keystream seed and `timestamp` the millisecond clock
	 * the server used. Returns `null` when the payload is not yet usable
	 * (cipher/key/timestamp missing, or no `images`), which the caller
	 * retries.
	 */
	private fun decryptManifest(payload: JSONObject): JSONObject? {
		val cipher = payload.optString("data")
		val key = payload.optString("key")
		val timestamp = payload.optLong("timestamp", 0L)
		if (cipher.isBlank() || key.isBlank() || timestamp == 0L) return null
		val plain = runCatching {
			decryptChapterPayload(context.decodeBase64(cipher), key, timestamp)
		}.getOrElse {
			log("decryptManifest: ${it.message}")
			return null
		}
		val json = runCatching { JSONObject(plain) }.getOrElse {
			log("decryptManifest: JSON lỗi (${plain.take(60)})")
			return null
		}
		return json.takeIf { it.optJSONArray("images") != null }
	}

	// endregion

	// region helpers

	private fun apiUrl(path: String): HttpUrl = "$baseUrl/api/$path".toHttpUrl()

	private fun comicKeyOf(data: JSONObject): String =
		data.optString("public_code").takeIf { it.isNotBlank() }
			?: data.optString("slug").takeIf { it.isNotBlank() }
			?: data.optLong("id", 0L).toString()

	private fun JSONObject.toManga(): Manga? {
		val id = optLong("id", 0L).takeIf { it > 0 } ?: return null
		val slug = optString("slug").takeIf { it.isNotBlank() } ?: return null
		val title = optString("title").ifBlank { slug }
		val cover = optString("cover_image").takeIf { it.isNotBlank() }
		val description = optString("description").takeIf { it.isNotBlank() }
		val state = when (optString("status")) {
			"ONGOING" -> MangaState.ONGOING
			"COMPLETED" -> MangaState.FINISHED
			else -> null
		}
		val author = optString("author").takeIf { it.isNotBlank() }
		val translator = optString("translator").takeIf { it.isNotBlank() }
		val tags = optJSONArray("tags").mapJSONToTags()
		@Suppress("UNUSED_VARIABLE") val keepId = id
		return Manga(
			id = generateUid(slug),
			title = title,
			altTitles = optString("other_names").split(',')
				.mapNotNull { it.trim().takeIf(String::isNotBlank) }
				.toSet(),
			url = slug,
			publicUrl = "$baseUrl/truyen/$slug",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = cover,
			tags = tags,
			state = state,
			authors = setOfNotNull(author, translator),
			description = description,
			chapters = emptyList(),
			source = source,
		)
	}

	private fun JSONArray?.mapJSONToTags(): Set<MangaTag> {
		if (this == null) return emptySet()
		return (0 until length()).mapNotNullTo(arraySetOf<MangaTag>()) { idx ->
			val obj = optJSONObject(idx) ?: return@mapNotNullTo null
			val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
			val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
			MangaTag(title = name, key = slug, source = source)
		}
	}

	/**
	 * Build the chapter list. `comicKey` is the public code, because that is
	 * what both the reader route and the chapter API accept — see the class
	 * kdoc.
	 */
	private fun JSONObject.chapterList(comicKey: String): List<MangaChapter> {
		val arr = optJSONArray("chapters") ?: return emptyList()
		if (comicKey.isBlank()) return emptyList()
		return (0 until arr.length()).mapNotNull { idx ->
			val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
			val chapterId = obj.optLong("id", 0L).takeIf { it > 0 } ?: return@mapNotNull null
			val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val number = obj.optDouble("chapter_number", -1.0).toFloat()
			val price = obj.optInt("price", 0)
			val publishedAt = obj.optString("published_at")
				.takeIf { it.isNotBlank() }
				?: obj.optString("created_at").takeIf { it.isNotBlank() }
			val uploadDate = publishedAt?.let { parseIsoMillis(it) } ?: 0L
			val baseTitle = when {
				number < 0f -> "Oneshot"
				else -> "Chương ${formatChapterNumber(number)}"
			}
			val extra = obj.optString("title").takeIf { it.isNotBlank() }
			val title = if (price > 0) "$LOCK_PREFIX$baseTitle" else baseTitle
			MangaChapter(
				id = chapterId,
				title = if (extra.isNullOrBlank()) title else "$title - $extra",
				number = if (number < 0f) 0f else number,
				volume = 0,
				url = "$comicKey/$chapterId:$slug",
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}.asReversed()
	}

	private fun parseChapterUrl(url: String): ChapterRef {
		val parts = url.split('/', limit = 2)
		require(parts.size == 2) { "Unexpected chapter url: $url" }
		val comicKey = parts[0].takeIf { it.isNotBlank() } ?: error("Bad comic key in $url")
		val chapter = parts[1].split(':', limit = 2)
		val chapterId = chapter[0].toLongOrNull() ?: error("Bad chapter id in $url")
		val chapterSlug = chapter.getOrNull(1)?.takeIf { it.isNotBlank() }
			?: error("Bad chapter slug in $url")
		return ChapterRef(
			comicKey = comicKey,
			chapterId = chapterId,
			chapterSlug = chapterSlug,
		)
	}

	private data class ChapterRef(
		val comicKey: String,
		val chapterId: Long,
		val chapterSlug: String,
	)

	// endregion

	companion object {
		private const val PAGE_SIZE = 20
		private const val ADULT_GATE_COOKIE = "hv2t_adult_gate"
		private const val ACCESS_TOKEN_COOKIE = "access_token"
		private const val LOCK_PREFIX = "🔒 "

		/** Prefix every `key` carries; the WASM module refuses anything else. */
		private const val KEY_PREFIX = "hv2t-niga"
		private const val KEY_MIN_LENGTH = 9

		/** Same backoff schedule the site uses before it gives up on a chapter. */
		private val RETRY_PAUSES_MS = longArrayOf(0L, 250L, 750L, 1500L)

		private val RETRYABLE_STATUS = setOf(429, 500, 502, 503, 504, 524)

		/**
		 * Pure mirror of `/security.wasm`'s `decrypt_buffer`:
		 *
		 * ```
		 * if (keyLen < 9 || key does not start with "hv2t-niga") return cipher unchanged
		 * for (i = 0; i < len; i++)
		 *     cipher[i] ^= (uint8)(timestamp >> (i & 7)) ^ key[i % keyLen]
		 * ```
		 *
		 * Exposed at module level so the test harness can feed it the exact
		 * ciphertext/key/timestamp triples the site returns.
		 */
		internal fun decryptChapterPayload(cipher: ByteArray, key: String, timestamp: Long): String {
			val keyBytes = key.toByteArray(Charsets.UTF_8)
			require(keyBytes.size >= KEY_MIN_LENGTH) { "Key quá ngắn: ${keyBytes.size}" }
			require(String(keyBytes, 0, KEY_PREFIX.length, Charsets.UTF_8) == KEY_PREFIX) {
				"Key không đúng định dạng hv2t-niga"
			}
			val out = ByteArray(cipher.size)
			for (i in cipher.indices) {
				val clock = ((timestamp ushr (i and 7)) and 0xFF).toInt()
				out[i] = (cipher[i].toInt() xor clock xor keyBytes[i % keyBytes.size].toInt()).toByte()
			}
			return String(out, Charsets.UTF_8)
		}

		/**
		 * Best-effort ISO-8601 parser. Falls back to `0L` on anything that
		 * does not match — the API always emits `…Z`.
		 */
		private fun parseIsoMillis(value: String): Long {
			return runCatching {
				val cleaned = value.removeSuffix("Z").substringBefore('+').substringBefore('.')
				val (date, time) = cleaned.split('T', limit = 2)
				val ymd = date.split('-')
				val hms = time.split(':')
				val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
				calendar.clear()
				calendar.set(
					ymd[0].toInt(),
					ymd[1].toInt() - 1,
					ymd[2].toInt(),
					hms[0].toInt(),
					hms[1].toInt(),
					hms.getOrNull(2)?.toInt() ?: 0,
				)
				calendar.timeInMillis
			}.getOrDefault(0L)
		}

		private fun formatChapterNumber(n: Float): String {
			if (n == n.toInt().toFloat()) return n.toInt().toString()
			val rounded = (n * 10).toInt() / 10f
			return rounded.toString().trimEnd('0').trimEnd('.')
		}
	}
}
