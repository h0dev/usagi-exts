package tsuki.site.vi

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("TRUYENGG", "FoxTruyen", "vi")
internal class TruyenGG(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.TRUYENGG, 42) {

	override val configKeyDomain = ConfigKey.Domain("truyenggvn.com", "foxtruyen.co.uk", "foxtruyen2.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
			ContentType.OTHER,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = when {
			!filter.query.isNullOrEmpty() -> {
				buildString {
					append("https://")
					append(domain)
					append("/tim-kiem.html")
					append("?q=")
					append((filter.query.urlEncoded()))
				}
			}

			else -> {
				buildString {
					append("https://")
					append(domain)
					append("/tim-kiem-nang-cao/trang-")
					append(page.toString())
					append(".html?country=")

					if (filter.types.isNotEmpty()) {
						filter.types.oneOrThrowIfMany()?.let {
							append(
								when (it) {
									ContentType.MANHUA -> '1'
									ContentType.OTHER -> '4' // Việt Nam
									ContentType.MANHWA -> '2'
									ContentType.MANGA -> '3'
									ContentType.COMICS -> '5'
									else -> '0' // all
								},
							)
						}
					} else append('0')

					append("&status=")
					if (filter.states.isNotEmpty()) {
						filter.states.oneOrThrowIfMany()?.let {
							append(
								when (it) {
									MangaState.ONGOING -> '0'
									MangaState.FINISHED -> '1'
									else -> "-1"
								},
							)
						}
					} else {
						append("-1")
					}

					append("&category=")
					filter.tags.joinTo(this, separator = ",") { it.key }

					append("&notcategory=")
					filter.tagsExclude.joinTo(this, separator = ",") { it.key }

					append("&minchapter=0")

					append("&sort=")
					append(
						when (order) {
							SortOrder.NEWEST -> "0"
							SortOrder.NEWEST_ASC -> "1"
							SortOrder.UPDATED -> "2"
							SortOrder.UPDATED_ASC -> "3"
							SortOrder.POPULARITY -> "4"
							SortOrder.POPULARITY_ASC -> "5"
							else -> "2"
						},
					)
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select(".list_grid_out > ul.list_grid > li").mapNotNull { item ->
			val link = item.selectFirst(".book_name a") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				title = link.text(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = item.selectFirst(".book_avatar img")?.let { img ->
					img.attrAsAbsoluteUrlOrNull("data-src")
						?: img.attrAsAbsoluteUrlOrNull("src")
				},
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
		val author = doc.select("p:contains(Tác Giả) + p").joinToString { it.text() }.nullIfEmpty()

		return manga.copy(
			altTitles = setOfNotNull(doc.selectFirst("h2.book-othername")?.textOrNull()),
			authors = setOfNotNull(doc.selectFirst(".book-counts__value .org")?.textOrNull() ?: author),
			tags = doc.select(".book-genres a").mapToSet {
				MangaTag(
					key = it.attr("href").substringAfterLast('-').substringBeforeLast('.'),
					title = it.text().toTitleCase(sourceLocale),
					source = source,
				)
			},
			description = doc.select("div.story-detail-info").text(),
			state = when {
				doc.select(".book-counts__value").any { it.text().contains("Hoàn Thành", true) } -> MangaState.FINISHED
				doc.select(".book-counts__value").any { it.text().contains("Đang Cập Nhật", true) } -> MangaState.ONGOING
				else -> null
			},
			chapters = doc.select(".works-chapter-list .works-chapter-item").mapChapters(reversed = true) { i, div ->
				val a = div.selectFirstOrThrow(".name-chap a")
				val href = a.attrAsRelativeUrl("href")
				val name = a.text()
				val dateText = div.selectFirst(".time-chap")?.text()?.trim()
				MangaChapter(
					id = generateUid(href),
					title = name,
					number = i + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(dateText),
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select("#chapter_content .page-chapter img, .chapter_content .page-chapter img").map { img ->
			val url = img.attr("data-original").ifBlank { img.attr("data-cdn") }.ifBlank { img.requireSrc() }
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/tim-kiem-nang-cao.html").parseHtml()
		return doc.select(".advsearch-form div.genre-item").mapToSet {
			MangaTag(
				key = it.selectFirstOrThrow("span").attr("data-id"),
				title = it.text().toTitleCase(sourceLocale),
				source = source,
			)
		}
	}
}

