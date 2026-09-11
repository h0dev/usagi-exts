package tsuki.site.madara.vi

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.exception.ParseException
import tsuki.model.MangaChapter
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.site.madara.MadaraParser
import tsuki.util.*

/**
 * The site runs the stock Madara theme but keeps its manga under /truyen/
 * rather than /manga/, so the base url used for the tag list has to be
 * overridden. Chapters carry no release date, and images are hotlinked
 * straight from i.imgur.com.
 */
@MangaSourceParser("GANTZVN", "GantzVN", "vi")
internal class GantzVn(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.GANTZVN, "gantzvn.com") {

	override val listUrl = "truyen/"

	// The theme prints "OnGoing" without a space, which the base set already
	// covers; dates are absent from the chapter list entirely.
	override val datePattern = "dd/MM/yyyy"

	/**
	 * The reader markup writes the image urls with a leading space
	 * (`data-src=" https://i.imgur.com/..."`). The inherited implementation
	 * feeds that straight into toRelativeUrl, which cannot match a scheme
	 * behind whitespace and produces a broken url, so the value is trimmed
	 * here instead.
	 */
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val root = doc.body().selectFirst(selectBodyPage)
			?: throw ParseException("No image found", fullUrl)

		val urls = root.select(selectPage)
			.flatMap { it.select("img") }
			.mapNotNull { img ->
				val raw = img.attr("data-src").ifEmpty { img.attr("src") }
					.trim()
				raw.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
					?.toRelativeUrl(domain)
			}
			.distinct()

		if (urls.isEmpty()) throw ParseException("No image found", fullUrl)

		return urls.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
