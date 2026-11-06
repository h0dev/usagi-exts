package tsuki.site.vi

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.model.*
import tsuki.network.CommonHeaders
import tsuki.util.*
import tsuki.util.suspendlazy.getOrNull
import tsuki.util.suspendlazy.suspendLazy
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("DAMCONUONG", "Dâm Cô Nương", "vi", type = ContentType.HENTAI)
internal class DamCoNuong(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DAMCONUONG, 30) {

	override val configKeyDomain = ConfigKey.Domain("damconuong.cool", "damconuong.icu", "damconuong.sbs")

	private val availableTags = suspendLazy(initializer = ::fetchTags)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add(CommonHeaders.REFERER, "https://$domain")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/tim-kiem")

			append("?sort=")
			append(
				when (order) {
					SortOrder.UPDATED -> "-updated_at"
					SortOrder.NEWEST -> "-created_at"
					SortOrder.NEWEST_ASC -> "created_at"
					SortOrder.POPULARITY -> "-views"
					SortOrder.POPULARITY_TODAY -> "-views_day"
					SortOrder.POPULARITY_WEEK -> "-views_week"
					SortOrder.RATING -> "-average_rating"
					SortOrder.ALPHABETICAL -> "name"
					SortOrder.ALPHABETICAL_DESC -> "-name"
					else -> "-updated_at"
				},
			)

			if (filter.states.isNotEmpty()) {
				append("&filter[status]=")
				filter.states.forEach {
					append(
						when (it) {
							MangaState.ONGOING -> "2,"
							MangaState.FINISHED -> "1,"
							else -> "2,1"
						},
					)
				}
			}

			if (filter.tags.isNotEmpty()) {
				append("&filter[accept_genres]=")
				append(filter.tags.joinTo(this, ",") { it.key })
			}

			if (!filter.query.isNullOrEmpty()) {
				append("&filter[name]=")
				append(filter.query.urlEncoded())
			}

			if (filter.tagsExclude.isNotEmpty()) {
				append("&filter[reject_genres]=")
				append(filter.tagsExclude.joinTo(this, ",") { it.key })
			}

			if (!filter.author.isNullOrEmpty()) {
				append("&filter[author]=")
				append(filter.author.urlEncoded())
			}

			append("&page=$page")
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.manga-vertical").mapNotNull { element ->
			val mainA = element.selectFirst("a[href*=/truyen/]") ?: return@mapNotNull null
			val href = mainA.attrAsRelativeUrl("href")
			val title = element.selectFirst("div.p-3 h3 a")?.text()?.takeIf { it.isNotBlank() }
				?: mainA.selectFirst("div.cover-frame img, img")?.attr("alt")?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			val coverUrl = mainA.selectFirst("div.cover-frame img, img")?.let { img ->
				img.attr("data-src").takeIf { it.isNotBlank() }
					?: img.attr("src").takeIf { it.isNotBlank() }
			}

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		val altTitles = doc.select(".md-rail dt:contains(Tên khác) + dd span, .md-rail dt:contains(Tên khác) + dd a, div.mt-2:contains(Tên khác:) span")
			.mapNotNullToSet { it.textOrNull() }
		val allTags = availableTags.getOrNull().orEmpty()
		val tags = doc.select(".md-rail-genres a.md-chip, .md-rail dt:contains(Thể loại) + dd a.md-chip, div.mt-2:contains(Thể loại:) a")
			.mapNotNullToSet { a ->
				val title = a.text().trim().toTitleCase(sourceLocale)
				allTags.find { x -> x.title.equals(title, ignoreCase = true) }
					?: MangaTag(key = a.attrAsRelativeUrlOrNull("href") ?: title, title = title, source = source)
			}

		val state = when {
			doc.selectFirst(".md-badge-done") != null -> MangaState.FINISHED
			doc.selectFirst(".md-badge-ongoing") != null -> MangaState.ONGOING
			doc.selectFirst(".md-badge")?.text()?.contains("hoàn thành", ignoreCase = true) == true -> MangaState.FINISHED
			doc.selectFirst(".md-badge")?.text()?.contains("đang tiến hành", ignoreCase = true) == true -> MangaState.ONGOING
			doc.selectFirst("div.mt-2:contains(Tình trạng:) span")?.text()?.contains("Đang tiến hành", ignoreCase = true) == true -> MangaState.ONGOING
			else -> MangaState.FINISHED
		}

		val chapterLinks = doc.select("ul#md-chapter-list li a.md-ch, a.md-ch, ul#chapterList a.block, div#md-chapters a.md-ch")
		if (chapterLinks.isEmpty()) {
			throw ParseException("Chapters list not found!", url)
		}

		val scanlator = doc.selectFirst(".md-uploader strong")?.text()?.trim()
			?: doc.selectFirst("img[src*=avatar.png]")?.attr("alt")?.takeIf { it.isNotBlank() }

		val chapters = chapterLinks.mapChapters(reversed = true) { index, a ->
			val title = a.selectFirst(".md-ch-title")?.textOrNull()
				?: a.selectFirst("span.text-ellipsis")?.textOrNull()
				?: a.text().trim().takeIf { it.isNotBlank() }
			val href = a.attrAsRelativeUrl("href")
			val uploadDate = a.selectFirst(".md-ch-meta span")?.text()
				?: a.selectFirst("span.ml-2.whitespace-nowrap")?.text()

			MangaChapter(
				id = generateUid(href),
				title = title,
				number = index + 1f,
				volume = 0,
				url = href,
				scanlator = scanlator,
				uploadDate = parseChapterDate(uploadDate),
				branch = null,
				source = source,
			)
		}

		val description = doc.selectFirst(".md-synopsis p[x-ref=text], .md-synopsis p, div.synopsis")?.text()?.trim()
		val authors = doc.select(".md-rail dt:contains(Tác giả) + dd a, .md-rail dt:contains(Tác giả) + dd span, div.mt-2:contains(Tác giả:) span, div.mt-2:contains(Tác giả:) a")
			.mapNotNullToSet { it.textOrNull() }

		return manga.copy(
			altTitles = altTitles,
			tags = tags,
			state = state,
			chapters = chapters,
			description = description ?: manga.description,
			authors = authors.ifEmpty { manga.authors },
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val segments = chapter.url.trim('/').split('/')
		val mangaSlug: String
		val chapterSlug: String
		if (segments.size >= 3 && segments[0] == "truyen") {
			mangaSlug = segments[1]
			chapterSlug = segments[2]
		} else if (segments.size >= 2) {
			mangaSlug = segments[0]
			chapterSlug = segments[1]
		} else {
			throw ParseException("Invalid chapter url: ${chapter.url}", chapter.url)
		}

		val path = "$mangaSlug/$chapterSlug"
		try {
			ensureDecoderLoaded()
			val token = makePagesToken(mangaSlug, chapterSlug)
			val cUrl = "https://$domain/_c/mangas/$mangaSlug/chapters/$chapterSlug/pages?_=$token"
			val headers = Headers.Builder()
				.add(CommonHeaders.REFERER, chapter.url.toAbsoluteUrl(domain))
				.add("X-Requested-With", "XMLHttpRequest")
				.add("Accept", "application/json")
				.build()
			val json = webClient.httpGet(cUrl, headers).parseJson()
			val encrypted = json.optString("e")
			if (encrypted.isNotBlank()) {
				val payload = decryptPages(encrypted, token, path)
				if (payload.pages.isNotEmpty()) {
					return payload.pages.mapIndexedNotNull { index, src ->
						if (src.isBlank()) return@mapIndexedNotNull null
						val key = payload.scrambleKeys?.getOrNull(index)?.takeIf { it.isNotEmpty() }
						val imageUrl = if (key != null) "$src#$key" else src
						MangaPage(
							id = generateUid(imageUrl),
							url = imageUrl,
							preview = null,
							source = source,
						)
					}
				}
			}
		} catch (_: Exception) {
			// fallback to HTML parsing below
		}

		// Fallback for older static/unencrypted pages
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val imgs = doc.select("div#chapter-content img, .chapter-content img")
		if (imgs.isNotEmpty()) {
			return imgs.mapNotNull { img ->
				val src = img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src").takeIf { it.isNotBlank() }
				if (src != null && !src.endsWith(".gif", ignoreCase = true)) {
					MangaPage(id = generateUid(src), url = src, preview = null, source = source)
				} else null
			}
		}

		throw ParseException("Pages not found!", chapter.url)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val fragment = request.url.fragment
		if (fragment == null || !fragment.startsWith("x1.") || fragment.length != 46) {
			return chain.proceed(request)
		}

		val cleanUrl = request.url.newBuilder().fragment(null).build()
		val response = chain.proceed(request.newBuilder().url(cleanUrl).build())
		if (!response.isSuccessful) return response

		return context.redrawImageResponse(response) { bitmap ->
			descramble(bitmap, fragment)
		}
	}

	private fun parseChapterDate(date: String?): Long {
		if (date == null) return 0L
		val now = System.currentTimeMillis()

		if (date.contains("vừa xong", ignoreCase = true) || date.contains("vừa đăng", ignoreCase = true)) {
			return now
		}
		if (date.contains("hôm nay", ignoreCase = true)) {
			return now
		}
		if (date.contains("hôm qua", ignoreCase = true)) {
			return now - 24 * 60 * 60 * 1000L
		}

		val number = Regex("""\d+""").find(date)?.value?.toLongOrNull()
		if (number != null) {
			return when {
				date.contains("giây") -> now - number * 1000L
				date.contains("phút") -> now - number * 60 * 1000L
				date.contains("giờ") -> now - number * 60 * 60 * 1000L
				date.contains("ngày") -> now - number * 24 * 60 * 60 * 1000L
				date.contains("tuần") -> now - number * 7 * 24 * 60 * 60 * 1000L
				date.contains("tháng") -> now - number * 30 * 24 * 60 * 60 * 1000L
				date.contains("năm") -> now - number * 365 * 24 * 60 * 60 * 1000L
				else -> SimpleDateFormat("dd/MM/yyyy", Locale.US).parse(date)?.time ?: 0L
			}
		}

		return SimpleDateFormat("dd/MM/yyyy", Locale.US).parse(date)?.time ?: 0L
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/tim-kiem").parseHtml()
		val regex = Regex("""toggleGenre\('([0-9]+)'\)""")
		val tags = HashSet<MangaTag>()
		val buttons = doc.select("button[@click*=toggleGenre], button[x-on:click*=toggleGenre], button")
		for (button in buttons) {
			val click = button.attr("@click").ifEmpty { button.attr("x-on:click") }
			val id = regex.find(click)?.groupValues?.get(1) ?: continue
			val title = button.selectFirst("span.truncate")?.textOrNull()
				?: button.textOrNull()
				?: continue
			val trimmed = title.trim().toTitleCase(sourceLocale)
			if (trimmed.isNotEmpty()) {
				tags.add(MangaTag(key = id, title = trimmed, source = source))
			}
		}
		return tags
	}

	// =========================== Pages Decoder & Crypto ===========================

	private val defaultSecret = "YVdGuT8RjDWkeQjt7s7mv53smMpLrcKBuGMs8erg8Bs"
	private val defaultAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

	@Volatile
	private var currentTokenAlphabet: String = defaultAlphabet

	@Volatile
	private var tokKey: ByteArray? = null

	@Volatile
	private var encKey: ByteArray? = null

	private suspend fun ensureDecoderLoaded() {
		if (tokKey != null && encKey != null) return
		try {
			loadDecoderFromSite()
		} catch (_: Exception) {
			setupKeys(defaultSecret, defaultAlphabet)
		}
	}

	private fun setupKeys(secret: String, alphabet: String) {
		currentTokenAlphabet = alphabet
		val secretBytes = decodeBase64Url(secret, alphabet) ?: error("bad decoder secret")
		tokKey = hmacSha256("tok".toByteArray(StandardCharsets.UTF_8), secretBytes)
		encKey = hmacSha256("enc".toByteArray(StandardCharsets.UTF_8), secretBytes)
	}

	private suspend fun loadDecoderFromSite() {
		// Attempt to load from bookmark.js or home
		val bookmarkUrl = "https://$domain/js/bookmark.js"
		val js = try {
			webClient.httpGet(bookmarkUrl).parseRaw()
		} catch (_: Exception) {
			""
		}

		if (js.isNotEmpty() && js.contains("export default")) {
			// Find tokenAlphabet and secret candidates from script
			val secretMatch = Regex("""^[A-Za-z0-9_-]{43,44}$""")
			val alphaMatch = Regex("""^[A-Za-z0-9+/_-]{64}$""")
			val stringTokens = Regex("""['"]([A-Za-z0-9+/=_-]{43,65})['"]""").findAll(js).map { it.groupValues[1] }
			var foundSecret: String? = null
			var foundAlpha: String? = null
			for (t in stringTokens) {
				if (foundSecret == null && secretMatch.matches(t)) foundSecret = t
				if (foundAlpha == null && t.length == 64 && alphaMatch.matches(t)) foundAlpha = t
			}
			if (foundSecret != null && foundAlpha != null) {
				setupKeys(foundSecret, foundAlpha)
				return
			}
		}

		// Fallback to verified default
		setupKeys(defaultSecret, defaultAlphabet)
	}

	private fun makePagesToken(mangaSlug: String, chapterSlug: String): String {
		val tok = checkNotNull(tokKey) { "Keys not initialized" }
		val path = "$mangaSlug/$chapterSlug"
		val mac = hmacSha256(path.toByteArray(StandardCharsets.UTF_8), tok).copyOf(16)
		val bytes = ByteArray(17)
		bytes[0] = 1
		mac.copyInto(bytes, 1)
		return encodeBase64Url(bytes, currentTokenAlphabet)
	}

	private data class PagesPayload(
		val pages: List<String>,
		val scrambleKeys: List<String?>?,
	)

	private fun decryptPages(encrypted: String, token: String, path: String): PagesPayload {
		val enc = checkNotNull(encKey) { "Keys not initialized" }
		val raw = decodeBase64Url(encrypted, currentTokenAlphabet) ?: error("bad payload")
		if (raw.size < 12 + 16) error("bad payload size")
		val iv = raw.copyOfRange(0, 12)
		val cipherBytes = raw.copyOfRange(12, raw.size)

		val aesKey = hmacSha256(token.toByteArray(StandardCharsets.UTF_8), enc)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
			init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
			updateAAD(path.toByteArray(StandardCharsets.UTF_8))
		}
		val plain = cipher.doFinal(cipherBytes)
		val json = JSONObject(String(plain, StandardCharsets.UTF_8))
		val pagesArr = json.optJSONArray("p")
		val pages = ArrayList<String>()
		if (pagesArr != null) {
			for (i in 0 until pagesArr.length()) {
				pages.add(pagesArr.getString(i))
			}
		}
		val sArr = json.optJSONArray("s")
		val scrambleKeys = if (sArr != null) {
			ArrayList<String?>().apply {
				for (i in 0 until sArr.length()) {
					add(if (sArr.isNull(i)) null else sArr.optString(i).takeIf { it.isNotBlank() })
				}
			}
		} else null

		return PagesPayload(pages, scrambleKeys)
	}

	private fun descramble(bitmap: Bitmap, key: String): Bitmap {
		val seed = decodeBase64Url(key.removePrefix("x1."), currentTokenAlphabet) ?: return bitmap
		if (seed.size != 32) return bitmap

		val width = bitmap.width
		val height = bitmap.height
		if (width <= 0 || height <= 0) return bitmap

		val layout = buildLayout(seed, height) ?: return bitmap
		val out = context.createBitmap(width, height)
		for ((dstY, srcY, tileH) in layout) {
			out.drawBitmap(
				bitmap,
				Rect(0, srcY, width, srcY + tileH),
				Rect(0, dstY, width, dstY + tileH),
			)
		}
		return out
	}

	private fun buildLayout(seed: ByteArray, height: Int): List<Triple<Int, Int, Int>>? {
		val rng = HmacPrng(seed)
		val rows = 8 + rng.nextInt(13)
		val tileH = 16 * (height / (16 * rows))
		if (tileH < 16) return null

		val perm = IntArray(rows) { it }
		for (i in rows - 1 downTo 1) {
			val j = rng.nextInt(i + 1)
			val tmp = perm[i]
			perm[i] = perm[j]
			perm[j] = tmp
		}

		val total = rows * tileH
		if (total > height) return null

		val layout = ArrayList<Triple<Int, Int, Int>>(rows + 1)
		for (i in 0 until rows) {
			layout.add(Triple(perm[i] * tileH, i * tileH, tileH))
		}
		if (height > total) {
			layout.add(Triple(total, total, height - total))
		}
		return layout
	}

	private class HmacPrng(seed: ByteArray) {
		private val mac = Mac.getInstance("HmacSHA256").apply {
			init(SecretKeySpec(seed, "HmacSHA256"))
		}
		private var counter = 0
		private var buf = ByteArray(0)
		private var pos = 0

		private fun nextUint32(): Int {
			if (pos >= buf.size) {
				val msg = ByteArray(4)
				msg[0] = (counter shr 24).toByte()
				msg[1] = (counter shr 16).toByte()
				msg[2] = (counter shr 8).toByte()
				msg[3] = counter.toByte()
				counter++
				buf = mac.doFinal(msg)
				pos = 0
			}
			val v = ((buf[pos].toInt() and 0xff) shl 24) or
				((buf[pos + 1].toInt() and 0xff) shl 16) or
				((buf[pos + 2].toInt() and 0xff) shl 8) or
				(buf[pos + 3].toInt() and 0xff)
			pos += 4
			return v
		}

		fun nextInt(n: Int): Int {
			val limit = 0x100000000L / n * n
			var r = nextUint32().toLong() and 0xffffffffL
			while (r >= limit) {
				r = nextUint32().toLong() and 0xffffffffL
			}
			return (r % n).toInt()
		}
	}

	private fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(key, "HmacSHA256"))
		return mac.doFinal(data)
	}

	private fun encodeBase64Url(input: ByteArray, b64: String): String {
		val out = StringBuilder()
		var i = 0
		while (i < input.size) {
			val b0 = input[i].toInt() and 0xff
			val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 0xff else 0
			val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 0xff else 0
			val n = (b0 shl 16) or (b1 shl 8) or b2
			val remain = input.size - i
			val chars = minOf(4, (remain * 8 + 5) / 6)
			for (c in 0 until chars) {
				out.append(b64[(n shr (18 - 6 * c)) and 63])
			}
			i += 3
		}
		return out.toString()
	}

	private fun decodeBase64Url(input: String, b64: String): ByteArray? {
		val out = ArrayList<Byte>()
		var bits = 0
		var value = 0
		for (ch in input) {
			val d = b64.indexOf(ch)
			if (d < 0) return null
			value = (value shl 6) or d
			bits += 6
			if (bits >= 8) {
				bits -= 8
				out.add(((value shr bits) and 0xff).toByte())
			}
		}
		return out.toByteArray()
	}
}
