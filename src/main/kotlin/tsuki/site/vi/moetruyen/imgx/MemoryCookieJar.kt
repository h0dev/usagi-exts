package tsuki.site.vi.moetruyen.imgx

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * A private cookie jar for the IMGX handshake.
 *
 * The reader-instance token in the chapter HTML is bound to the document cookie
 * set by the very same response. When the app-wide jar still holds cookies from
 * a WebView session or a previous reader instance, the server pairs the token
 * with the wrong document and the handshake fails with HTTP 5xx. Keeping the
 * document, bootstrap and page-access calls on one isolated jar makes the three
 * calls always agree with each other.
 */
internal class MemoryCookieJar : CookieJar {
	private val cookies = ConcurrentHashMap<String, MutableList<Cookie>>()

	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		this.cookies[url.host] = cookies.toMutableList()
	}

	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val now = System.currentTimeMillis()
		return cookies.entries
			.filter { (host, _) -> url.host == host || url.host.endsWith(".$host") }
			.flatMap { it.value }
			.filter { it.expiresAt > now && it.matches(url) }
	}

	fun clear() = cookies.clear()
}
