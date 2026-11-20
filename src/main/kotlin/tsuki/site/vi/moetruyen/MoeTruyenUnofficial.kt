package tsuki.site.vi.moetruyen

import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.model.*
import tsuki.network.UserAgents
import tsuki.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("BFANGTEAM", "Moè Truyện (Unofficial)", "vi")
internal class MoeTruyenUnofficial(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.BFANGTEAM, 24) {

	override val configKeyDomain = ConfigKey.Domain("moetruyen.net")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.remove(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = availableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().addPathSegment("manga").apply {
			addQueryParameter("page", page.toString())
			filter.query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
			filter.states.oneOrThrowIfMany()?.let { state ->
				val value = when (state) {
					MangaState.ONGOING -> "Còn tiếp"
					MangaState.FINISHED -> "Hoàn thành"
					MangaState.PAUSED -> "Tạm dừng"
					else -> null
				}
				value?.let { addQueryParameter("status", it) }
			}
			filter.tags.forEach { addQueryParameter("include", it.key) }
			filter.tagsExclude.forEach { addQueryParameter("exclude", it.key) }
			addQueryParameter(
				"sort",
				when (order) {
					SortOrder.ALPHABETICAL -> "title_asc"
					SortOrder.POPULARITY -> "views_desc"
					else -> "updated_desc"
				},
			)
		}
		return parseMangaList(webClient.httpGet(url.build()).parseHtml())
	}

	private fun parseMangaList(doc: Document): List<Manga> =
		doc.select("article.manga-card").mapNotNull { card ->
			val link = card.selectFirst("a[href^=/manga/]") ?: return@mapNotNull null
			val relativeUrl = link.attrAsRelativeUrl("href")
			val mangaId = relativeUrl.substringAfter("/manga/").substringBefore('-').toLongOrNull()
			val title = card.selectFirst("h3")?.attrOrNull("title")
				?: card.selectFirst("h3")?.textOrNull()
				?: return@mapNotNull null
			Manga(
				id = mangaId?.let(::generateUid) ?: generateUid(relativeUrl),
				url = relativeUrl,
				publicUrl = relativeUrl.toAbsoluteUrl(domain),
				title = title,
				altTitles = emptySet(),
				coverUrl = card.selectFirst("img")?.src(),
				largeCoverUrl = null,
				authors = emptySet(),
				tags = card.select(".manga-hover-popup__genre").mapToSet {
					MangaTag(it.text(), it.text(), source)
				},
				state = parseState(card.selectFirst(".manga-badge")?.text()),
				description = card.selectFirst(".manga-hover-popup__description")?.textOrNull(),
				contentRating = null,
				source = source,
				rating = RATING_UNKNOWN,
			)
		}

	override suspend fun getDetails(manga: Manga): Manga {
		val relativeUrl = resolveMangaUrl(manga)
		val firstDoc = webClient.httpGet(relativeUrl.toAbsoluteUrl(domain)).parseHtml()
		val chapterDocs = mutableListOf(firstDoc)
		val lastPage = firstDoc.select("a[href*=chapterPage]")
			.mapNotNull { CHAPTER_PAGE_REGEX.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
			.maxOrNull() ?: 1
		for (page in 2..lastPage) {
			// urlBuilder(String) expects a host, not a complete URL. Passing the
			// absolute manga URL made OkHttp reject it as an "unexpected host".
			val pageUrl = "${relativeUrl.toAbsoluteUrl(domain)}?chapterPage=$page"
			chapterDocs += webClient.httpGet(pageUrl).parseHtml()
		}

		val chapters = chapterDocs.flatMap { it.select("a.chapter-link") }
			.distinctBy { it.attr("data-chapter-id").ifBlank { it.attr("href") } }
			.mapChapters(reversed = true) { index, a -> parseChapter(a, index) }

		return manga.copy(
			url = relativeUrl,
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			title = firstDoc.selectFirst("h1.manga-detail-title")?.textOrNull() ?: manga.title,
			altTitles = firstDoc.select(".manga-detail-meta-line--names .manga-detail-meta-value")
				.mapToSet { it.text() }.ifEmpty { manga.altTitles },
			coverUrl = firstDoc.selectFirst(".detail-cover img")?.src() ?: manga.coverUrl,
			authors = firstDoc.select(".manga-detail-meta-line--author a").mapToSet { it.text() },
			tags = firstDoc.select(".manga-detail-genre-chips a").mapToSet {
				val key = it.attr("href").substringAfter("include=", it.text())
				MangaTag(it.text(), key, source)
			},
			state = parseState(firstDoc.selectFirst(".manga-status-pill")?.text()),
			description = firstDoc.selectFirst("[data-description-content]")?.html(),
			chapters = chapters,
		)
	}

	private fun parseChapter(a: Element, index: Int): MangaChapter {
		val relativeUrl = a.attrAsRelativeUrl("href")
		val number = a.attr("data-chapter-number").toFloatOrNull() ?: (index + 1f)
		val dateText = a.selectFirst(".chapter-time")?.attrOrNull("title")
			?.substringAfter("Cập nhật ")
		return MangaChapter(
			id = generateUid(a.attr("data-chapter-id").ifBlank { relativeUrl }),
			title = a.selectFirst(".chapter-title")?.textOrNull(),
			number = number,
			volume = 0,
			url = relativeUrl,
			scanlator = a.selectFirst(".chapter-subrow .chapter-sub-text:not(.chapter-sub-metric)")?.textOrNull(),
			uploadDate = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).parseSafe(dateText),
			branch = null,
			source = source,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = normalizeSitePath(chapter.url).toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		val script = doc.select("script").firstOrNull { it.data().contains("worker.postMessage") }?.data()
			?: throw ParseException("Không tìm thấy cấu hình ảnh của chương", chapter.url)
		val mediaJson = MEDIA_REGEX.find(script)?.groupValues?.get(1)
			?: throw ParseException("Không đọc được danh sách ảnh của chương", chapter.url)
		val media = JSONArray(mediaJson)

		// primaryUrl is not the manga image. It is a noisy PNG carrier whose RGB
		// channels contain an encrypted IMGX payload. The keys are issued only to
		// the official reader worker through a per-document capability. Returning
		// that URL as MangaPage displays the carrier noise, so hand protected
		// chapters to the site's reader instead.
		if ((0 until media.length()).any { media.optJSONObject(it)?.has("primaryUrl") == true }) {
			context.requestBrowserAction(this, chapterUrl)
		}

		// Compatibility path for chapters that still expose ordinary image URLs.
		val pages = (0 until media.length()).mapNotNull { i ->
			val item = media.optJSONObject(i) ?: return@mapNotNull null
			val url = item.optString("downloadUrl").takeIf {
				it.startsWith("http") && !it.substringBefore('?').endsWith(".js")
			} ?: return@mapNotNull null
			Triple(item.optInt("pageNumber", i + 1), item.optInt("pageIndex", i), url)
		}.sortedWith(compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second })

		if (pages.isEmpty()) throw ParseException("Chương yêu cầu trình đọc web IMGX", chapter.url)
		return pages.map { (_, _, url) -> MangaPage(generateUid(url), url, null, source) }
	}

	private suspend fun resolveMangaUrl(manga: Manga): String {
		val normalized = normalizeSitePath(manga.url)
		if (normalized.startsWith("/manga/") &&
			normalized.removePrefix("/manga/").substringBefore('/').toLongOrNull() == null
		) return normalized
		val oldId = normalized.removePrefix("/manga/").substringBefore('/').toLongOrNull()
			?: return normalized
		val url = urlBuilder().addPathSegment("search")
			.addQueryParameter("q", oldId.toString())
			.addQueryParameter("limit", "20").build()
		val headers = getRequestHeaders().newBuilder().set("Accept", "application/json").build()
		val items = webClient.httpGet(url, headers).parseJson().getJSONArray("items")
		for (i in 0 until items.length()) {
			val item = items.getJSONObject(i)
			if (item.optLong("id") == oldId) return "/manga/${item.getString("slug")}"
		}
		throw ParseException("Không tìm được slug mới cho ${manga.title}", manga.url)
	}

	private fun normalizeSitePath(value: String): String {
		val raw = value.trim()
		val absolute = ABSOLUTE_URL_REGEX.matchEntire(raw)
		val path = if (absolute != null) {
			val host = absolute.groupValues[1]
			if (!host.equals(domain, ignoreCase = true)) {
				throw ParseException("URL không thuộc $domain", value)
			}
			absolute.groupValues[2]
		} else {
			raw
		}
		val clean = path.substringBefore('#').substringBefore('?').trim()
			.replace(Regex("\\s+"), "-")
			.replace(Regex("-{2,}"), "-")
		return when {
			clean.startsWith('/') -> clean
			clean.startsWith("manga/") -> "/$clean"
			else -> clean
		}
	}

	private suspend fun availableTags(): Set<MangaTag> =
		webClient.httpGet("https://$domain/manga").parseHtml()
			.select("button[data-genre]").mapToSet {
				MangaTag(
					title = it.selectFirst(".filter-name")?.text() ?: it.text(),
					key = it.attr("data-genre"),
					source = source,
				)
			}

	private fun parseState(value: String?): MangaState? = when (value?.trim()?.lowercase(Locale.ROOT)) {
		"còn tiếp" -> MangaState.ONGOING
		"hoàn thành" -> MangaState.FINISHED
		"tạm dừng" -> MangaState.PAUSED
		else -> null
	}

	private companion object {
		val ABSOLUTE_URL_REGEX = Regex("https?://([^/]+)(/.*)?", RegexOption.IGNORE_CASE)
		val CHAPTER_PAGE_REGEX = Regex("[?&]chapterPage=(\\d+)")
		val MEDIA_REGEX = Regex("media:\\s*(\\[.*?]),\\s*connection:", setOf(RegexOption.DOT_MATCHES_ALL))
	}
}
