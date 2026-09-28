package tsuki.site.vi

import androidx.collection.arraySetOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import tsuki.MangaLoaderContext
import tsuki.MangaParserAuthProvider
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.AuthRequiredException
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
 * The site serves a Next.js SPA on top of a JSON API. The catalog
 * (`/api/comics`, `/api/comics/<slug>`, `/api/tags`, `/api/translators`) is
 * public but every request is gated by a Cloudflare Worker that demands an
 * `hv2t_adult_gate` cookie — without it the API returns
 * `{"code":"ADULT_GATE_REQUIRED"}` with HTTP 403.
 *
 * The cookie has the form `v1.<unix_ts>.<sig>` and the signature is computed
 * server-side, so the parser cannot forge it. The first time the user opens
 * the source the app's auth WebView ([authUrl]) loads the homepage; the user
 * clicks "Tôi đã đủ 18 tuổi" and the cookie lands in the WebView's
 * CookieManager. The parser then reads it through `context.evaluateJs` and
 * copies it into the OkHttp cookie jar so the rest of the catalog works
 * without leaving the app.
 *
 * Chapter pages are even more locked down: the SPA fetches a per-chapter
 * AES-GCM blob from `GET /api/comics/<mangaId>/<chapterId>/view`, then
 * decrypts it with a WASM module (`/security.wasm`) loaded at runtime. Both
 * the AES key and the IV timestamp are rotated per request. That endpoint
 * requires a signed-in session, so the auth WebView also doubles as a login
 * surface (the site supports Discord OAuth). Once the user is signed in, the
 * same `evaluateJs` trick wraps the whole flow: fetch the encrypted blob
 * using the WebView's session cookie, load the WASM with
 * `WebAssembly.instantiate`, decrypt in JavaScript, and return the JSON list
 * of image URLs.
 *
 * The keiyoushi reference parser does not need any of this because it gets a
 * real WebView with `runWebView` — Mihon/Tsuki only exposes the one-shot
 * `evaluateJs`, so we move both the cookie capture and the WASM decryption
 * inside the WebView's JavaScript context.
 *
 * An idiosyncrasy of the catalog: the listing endpoint assigns its own
 * `id` field that does **not** match the numeric id returned by the
 * detail endpoint (e.g. listing `id:2` resolves to detail `id:623`). The
 * detail endpoint accepts the slug but only one of the two numeric ids
 * matches, so the parser always keys off the slug for catalog lookups and
 * the detail response's numeric id is only used for the chapter API.
 */
@MangaSourceParser("HV2TCOMICS", "HV2T Comics", "vi", type = ContentType.HENTAI)
internal class Hv2tComics(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.HV2TCOMICS, PAGE_SIZE), MangaParserAuthProvider {

    override val configKeyDomain = ConfigKey.Domain(
        "hv2tcomics.net",
        "hv2tcomics.com",
    )

    /**
     * Enable `println` tracing for the auth flow. Tags every line
     * with `[Hv2tComics]` so it stands out in `adb logcat` (where
     * the app's `System.out` lands as the `System.out` tag). Turn
     * off if the log gets noisy in normal use.
     */
    private val debugLog = true
    private val logTag = "[Hv2tComics]"
    private fun log(message: String) {
        if (debugLog) println("$logTag $message")
    }

    /**
     * How long the answer to [isAuthorized] stays cached. The
     * SourceAuthActivity re-asks on every `onPageFinished` —
     * including the multiple intermediate hops of a Discord OAuth
     * flow — and each answer that falls through the cache triggers
     * a full [evaluateJs] round-trip (which on the shipped
     * `WebViewExecutor` replaces the WebView content and allocates
     * a fresh empty page). 30 s is long enough to cover a typical
     * Discord OAuth round-trip and short enough that the source
     * settings UI doesn't feel stale for long after the user logs
     * in.
     */
    private val authCacheTtlMs = 30_000L

    @Volatile
    private var cachedAuthCheck: Pair<Long, Boolean>? = null

    override val userAgentKey = ConfigKey.UserAgent(
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.46 Mobile Safari/537.36",
    )

    private val baseUrl: String get() = "https://$domain"

    /**
     * Keiyoushi uses 3 req/s; Tsuki's rate-limit throws instead of waiting,
     * so we mirror the wider DaoMeoDen window of 30 req/s — opening a manga
     * costs 2 requests (list, detail) and a typical page browse is well
     * within that.
     */
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
     * `authUrl` is what the app's SourceAuthActivity opens: the user lands
     * on the homepage, clicks the age-gate confirm button, and (if the
     * chapter they want to read is paid) signs in through Discord OAuth.
     * The cookies/state are then read back via `context.evaluateJs` in
     * [isAuthorized] / [readDecryptedChapter].
     */
    /**
     * `authUrl` is what the app's SourceAuthActivity opens. We point
     * it straight at the sign-in page so that after the user clears
     * the age-gate, they land on a screen with a clear
     * "Đăng nhập với Discord" button. The user can then either:
     *
     *  - tap the Discord button to log in (full access), or
     *  - hit Back / the up arrow to dismiss the activity, in which
     *    case [isAuthorized] still returns `false` (no session) so
     *    the app keeps the "Đăng nhập" row enabled and the user can
     *    browse the catalog (list / search / details) without ever
     *    creating an account. Free and VIP chapters will then 401 in
     *    [getPages] and the app's exception resolver opens this
     *    same activity again, so the user can log in later from
     *    there.
     */
    override val authUrl: String get() = "$baseUrl/auth/login"

    /**
     * Both halves have to be true before the app will let the user back
     * out of the auth WebView:
     *
     *  1. [ensureAgeGateCookie] — the Cloudflare Worker only forwards
     *     catalog requests when the `hv2t_adult_gate` cookie is present.
     *     It is set when the user clicks the age-gate "Tôi đã đủ 18
     *     tuổi" button on the homepage.
     *  2. [checkSessionCookie] — chapter pages need a real signed-in
     *     session (Discord OAuth); the catalog APIs work without it but
     *     `getPages` would 401. We ask the WebView to hit
     *     `/api/users/me` (the same call the SPA's AuthProvider makes);
     *     a 200 means the cookie jar carries a valid session.
     *
     * The whole result is **cached for 10 seconds**. The auth activity
     * calls this on every page-finished event — including the
     * several intermediate pages of the Discord OAuth flow
     * (login → 2FA → consent → callback). Each call goes through
     * [MangaLoaderContext.evaluateJs], which on this app's
     * `WebViewExecutor` runs `loadDataWithBaseURL(baseUrl, " ", ...)`
     * + `evaluateJavascript(...)` + `webView.reset()`. The
     * `loadDataWithBaseURL` REPLACES the page the user is looking at,
     * which interrupts an in-flight OAuth redirect; `reset()` then
     * loads yet another empty page. On a multi-step OAuth (5+ page
     * finishes in a few seconds) this is enough to freeze the UI
     * and balloon memory until the OS reclaims the process.
     *
     * Caching the boolean here cuts the whole `evaluateJs` chain to
     * one call per OAuth attempt; subsequent page-finished events
     * return the cached answer immediately and the WebView can keep
     * loading the next OAuth step uninterrupted. The 10 s window is
     * short enough that the user-visible state in source settings
     * refreshes quickly after they log in, but long enough that the
     * typical Discord OAuth completes without triggering a second
     * intrusive re-check.
     */
    override suspend fun isAuthorized(): Boolean {
        val cached = cachedAuthCheck
        if (cached != null && System.currentTimeMillis() - cached.first < authCacheTtlMs) {
            return cached.second
        }
        val result = ensureAgeGateCookie() && checkSessionCookie()
        cachedAuthCheck = System.currentTimeMillis() to result
        return result
    }

    override suspend fun getUsername(): String? = null

    private fun hasAgeGateCookie(): Boolean =
        context.cookieJar.getCookies(domain).any { it.name == ADULT_GATE_COOKIE }

    private suspend fun ensureAgeGateCookie(): Boolean {
        if (hasAgeGateCookie()) return true
        // The SourceAuthActivity has already loaded authUrl (the sign-in
        // page) by the time `isAuthorized()` is called, so the
        // WebView's cookie store has the gate cookie set in case the
        // user already went through the confirm step.
        // readDocumentCookie() returns null while the page is still on
        // `about:blank` (the very first call), which is also the point
        // where the user has not opened the source yet — treat that
        // as "not authorized" so the sign-in row stays enabled.
        val raw = readDocumentCookie() ?: return false
        val match = ADULT_GATE_REGEX.find(raw) ?: return false
        val value = match.groupValues[1]
        if (value.isBlank()) return false
        context.cookieJar.insertCookies(domain, "$ADULT_GATE_COOKIE=$value")
        // The age-gate just flipped, so the previously cached
        // `isAuthorized` (from a stale install or a previous sign-in
        // attempt) is no longer accurate. Drop it so the very next
        // call goes through checkSessionCookie and refreshes state.
        cachedAuthCheck = null
        return true
    }

    /**
     * Drops the cached [isAuthorized] answer. Production code does
     * not need this — the 30 s TTL in [authCacheTtlMs] handles
     * staleness — but tests can call it between scenarios so they
     * can drive the auth flow without having to sleep past the TTL.
     */
    fun clearAuthCache() {
        cachedAuthCheck = null
    }

    private suspend fun readDocumentCookie(): String? = runCatching {
        context.evaluateJs("$baseUrl/", "document.cookie")
    }.getOrNull()

    /**
     * Hits the SPA's own session probe (`/api/users/me`) from inside the
     * WebView. Routed through the *deprecated* `evaluateJs(script)`
     * overload (no base URL) — see [isAuthorized] for why this matters:
     * it lets the user keep interacting with the page for a few
     * hundred ms before `WebViewExecutor.reset()` blanks it.
     *
     * The script is wrapped in `try/catch` because once the user has
     * navigated into the Discord OAuth flow the WebView is on
     * `discord.com`, where the relative `fetch('/api/users/me')` would
     * either hit a CORS wall or a 404. We map anything that isn't a
     * clean `200` to "not logged in" so the activity keeps the user
     * in the flow until they actually complete it.
     */
    private suspend fun checkSessionCookie(): Boolean {
        val script = """
            (async () => {
                try {
                    if (location.hostname && !location.hostname.endsWith('hv2tcomics.net')) {
                        return 'wrong-host';
                    }
                    const resp = await fetch('/api/users/me', { credentials: 'include' });
                    return String(resp.status);
                } catch (e) {
                    return '0';
                }
            })()
        """.trimIndent()
        val raw = runCatching { context.evaluateJs(script) }.getOrNull() ?: return false
        return raw.trim() == "200"
    }

    // endregion

    // region catalog

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        ensureAgeGateCookie()
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
        ensureAgeGateCookie()
        // The catalog detail endpoint accepts the slug (and certain
        // numeric ids, but the listing ids do not match — see the class
        // kdoc). We stored the slug as the parser's `Manga.url`, so
        // pass it through unchanged.
        val payload = webClient.httpGet(apiUrl("comics/${manga.url}")).parseJson()
        if (payload.optBoolean("success") != true) {
            return manga
        }
        val data = payload.optJSONObject("data") ?: return manga

        val updated = data.toManga() ?: return manga.copy(chapters = data.chapterList(mangaId = data.optLong("id", 0L)))
        val chapters = data.chapterList(mangaId = updated.id)
        return manga.copy(
            title = updated.title,
            coverUrl = updated.coverUrl ?: manga.coverUrl,
            description = updated.description ?: manga.description,
            tags = updated.tags + manga.tags,
            state = updated.state ?: manga.state,
            authors = updated.authors.takeIf { it.isNotEmpty() } ?: manga.authors,
            chapters = chapters,
        )
    }

    override suspend fun resolveLink(link: HttpUrl): Manga? {
        ensureAgeGateCookie()
        val segments = link.pathSegments
        val slug = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val payload = webClient.httpGet(apiUrl("comics/$slug")).parseJson()
            if (payload.optBoolean("success") != true) return@runCatching null
            payload.optJSONObject("data")?.toManga()
        }.getOrNull()
    }

    private suspend fun loadTags(): Set<MangaTag> {
        ensureAgeGateCookie()
        return runCatching {
            val payload = webClient.httpGet(apiUrl("tags")).parseJson()
            if (payload.optBoolean("success") != true) return@runCatching emptySet()
            val arr = payload.optJSONArray("data") ?: return@runCatching emptySet()
            (0 until arr.length()).mapNotNullTo(arraySetOf<MangaTag>()) { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNullTo null
                val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
                MangaTag(
                    title = name,
                    key = slug,
                    source = source,
                )
            }
        }.getOrDefault(emptySet())
    }

    // endregion

    // region pages

    /**
     * The site serves chapter images as an AES-GCM blob fetched from
     * `GET /api/comics/<mangaId>/<chapterId>/view` (note: the JS calls
     * `axios.get('/comics/…/view')`, but the axios instance has
     * `baseURL: "/api"`, so the public URL is
     * `…/api/comics/2960/22839/view`). The response is then decrypted
     * with `/security.wasm` using a per-request key and timestamp. We
     * bundle the whole flow into a single `evaluateJs` call so the
     * WebView's session cookie and `WebAssembly.instantiate` are reused.
     *
     * The page itself is not loaded into the WebView — `evaluateJs` only
     * sets the origin and runs the script in a stub document. That's
     * enough: the chapter API and the WASM are same-origin, so the
     * WebView's cookies (including the user's login session) are
     * forwarded by `fetch`.
     */
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        ensureAgeGateCookie()
        if (chapter.title?.startsWith(LOCK_PREFIX) == true) {
            throw AuthRequiredException(
                source,
                IllegalStateException("Chương này trả phí cần đăng nhập tài khoản VIP bằng webview"),
            )
        }
        val parsed = parseChapterUrl(chapter.url)
        val result = readDecryptedChapter(parsed.mangaId, parsed.chapterId, chapter.url)
        // Reaching this line means the chapter API + WASM decrypt both
        // succeeded, which only happens with a valid session cookie in
        // the WebView. Promote the cached answer to `true` so the
        // source-settings row flips from "Đăng nhập" to its signed-in
        // state immediately, even if the 30 s [authCacheTtlMs] window
        // hasn't expired yet.
        cachedAuthCheck = System.currentTimeMillis() to true
        val images = result.optJSONArray("images") ?: return emptyList()
        return (0 until images.length()).mapNotNull { index ->
            val url = images.optString(index).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = if (url.startsWith("http")) url else "$baseUrl${url}",
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun readDecryptedChapter(
        mangaId: Long,
        chapterId: Long,
        pageUrl: String,
    ): JSONObject {
        // The script is loaded with the chapter page as baseUrl so its
        // origin and cookies line up with the catalog calls. The script
        // must return a JSON object — we wrap the work in an async IIFE
        // because `evaluateJs` only gives us back the last evaluated
        // value as a string.
        val script = buildDecryptScript(mangaId, chapterId)
        val raw = runCatching { context.evaluateJs(pageUrl, script) }.getOrNull()
            ?: throw AuthRequiredException(
                source,
                IllegalStateException("Không đọc được chương — mở webview để xác nhận 18+ và đăng nhập"),
            )
        val trimmed = raw.trim()
        if (trimmed.startsWith("ERR:")) {
            val message = trimmed.removePrefix("ERR:").trim()
            throw AuthRequiredException(
                source,
                IllegalStateException("Không đọc được chương: $message"),
            )
        }
        return runCatching { JSONObject(trimmed) }.getOrElse {
            throw AuthRequiredException(
                source,
                IllegalStateException("Phản hồi từ chương không hợp lệ: ${trimmed.take(80)}"),
            )
        }
    }

    // endregion

    // region helpers

    private fun apiUrl(path: String): HttpUrl =
        "$baseUrl/api/$path".toHttpUrl()

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
        // The numeric `id` is intentionally not exposed on the Manga —
        // it lives only in the chapter URL we build in [chapterList].
        @Suppress("UNUSED_VARIABLE") val keepId = id
    }

    private fun JSONArray?.mapJSONToTags(): Set<MangaTag> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNullTo(arraySetOf<MangaTag>()) { idx ->
            val obj = optJSONObject(idx) ?: return@mapNotNullTo null
            val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
            MangaTag(
                title = name,
                key = slug,
                source = source,
            )
        }
    }

    private fun JSONObject.chapterList(mangaId: Long): List<MangaChapter> {
        val arr = optJSONArray("chapters") ?: return emptyList()
        if (mangaId <= 0L) return emptyList()
        // The catalog endpoint returns chapters newest-first; reverse so
        // the reader sees them in natural ascending order.
        return (0 until arr.length()).mapNotNull { idx ->
            val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
            val chapterId = obj.optLong("id", 0L).takeIf { it > 0 } ?: return@mapNotNull null
            val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val number = obj.optDouble("chapter_number", -1.0).toFloat()
            val price = obj.optInt("price", 0)
            val publishedAt = obj.optString("published_at")
                .takeIf { it.isNotBlank() }
                ?: obj.optString("created_at")
                    .takeIf { it.isNotBlank() }
            val uploadDate = publishedAt?.let { parseIsoMillis(it) } ?: 0L
            val baseTitle = when {
                number < 0f -> "Oneshot"
                else -> "Chương ${formatChapterNumber(number)}"
            }
            val extra = obj.optString("title").takeIf { it.isNotBlank() }
            val title = if (price > 0) "$LOCK_PREFIX$baseTitle" else baseTitle
            val finalTitle = if (extra.isNullOrBlank()) title else "$title - $extra"
            MangaChapter(
                id = chapterId,
                title = finalTitle,
                number = if (number < 0f) 0f else number,
                volume = 0,
                url = "$mangaId/$chapterId:$slug",
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
        val mangaId = parts[0].toLongOrNull() ?: error("Bad manga id in $url")
        val chapter = parts[1].split(':', limit = 2)
        val chapterId = chapter[0].toLongOrNull() ?: error("Bad chapter id in $url")
        val chapterSlug = chapter.getOrNull(1).orEmpty()
        return ChapterRef(
            mangaId = mangaId,
            chapterId = chapterId,
            chapterSlug = chapterSlug,
        )
    }

    private data class ChapterRef(
        val mangaId: Long,
        val chapterId: Long,
        val chapterSlug: String,
    )

    // endregion

    companion object {
        private const val PAGE_SIZE = 20
        private const val ADULT_GATE_COOKIE = "hv2t_adult_gate"
        private val ADULT_GATE_REGEX = Regex("""hv2t_adult_gate=([^;\s]+)""")
        private const val LOCK_PREFIX = "🔒 "

        /**
         * JavaScript snippet run inside the WebView. Returns a JSON string
         * — either `{images:[...]}` on success, or `ERR:<message>` on
         * failure. The script is intentionally a single async IIFE so the
         * last expression (the resolved promise) is the value
         * `evaluateJs` returns.
         */
        private fun buildDecryptScript(mangaId: Long, chapterId: Long): String {
            val url = "/api/comics/$mangaId/$chapterId/view"
            return """
                (async () => {
                    try {
                        const resp = await fetch(${'$'}url, {
                            credentials: 'include',
                            headers: { 'Accept': 'application/json' },
                        });
                        if (!resp.ok) {
                            return 'ERR: HTTP ' + resp.status + ' ' + (await resp.text()).slice(0, 120);
                        }
                        const json = await resp.json();
                        if (!json || !json.success || !json.data) {
                            return 'ERR: ' + (json && json.message ? json.message : 'API error');
                        }
                        const blob = json.data;
                        const ct = Uint8Array.from(atob(blob.data), c => c.charCodeAt(0));
                        const key = new TextEncoder().encode(blob.key);
                        const ts = BigInt(blob.timestamp);
                        if (typeof WebAssembly === 'undefined') {
                            return 'ERR: WebAssembly không khả dụng trong webview';
                        }
                        const wasmResp = await fetch('/security.wasm', { credentials: 'same-origin' });
                        if (!wasmResp.ok) return 'ERR: WASM HTTP ' + wasmResp.status;
                        const wasmBytes = await wasmResp.arrayBuffer();
                        const wasm = await WebAssembly.instantiate(wasmBytes, {});
                        const m = wasm.instance.exports;
                        const ptr = m.get_buffer_ptr();
                        const buf = new Uint8Array(m.memory.buffer, ptr, ct.length + key.length);
                        buf.set(ct, 0);
                        buf.set(key, ct.length);
                        m.decrypt_buffer(ct.length, ts, ct.length, key.length);
                        const plain = new Uint8Array(m.memory.buffer, ptr, ct.length);
                        const payload = JSON.parse(new TextDecoder('utf-8').decode(plain));
                        if (!payload || !Array.isArray(payload.images)) {
                            return 'ERR: payload không có trường images';
                        }
                        return JSON.stringify({ images: payload.images });
                    } catch (e) {
                        return 'ERR: ' + (e && e.message ? e.message : String(e));
                    }
                })()
            """.trimIndent()
        }

        /**
         * Best-effort ISO-8601 parser. Falls back to `0L` on anything
         * that does not match — the API always emits `…Z` so this is
         * just a guard for odd input.
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
            // Round to one decimal, trim trailing zero
            val rounded = (n * 10).toInt() / 10f
            return rounded.toString().trimEnd('0').trimEnd('.')
        }
    }
}
