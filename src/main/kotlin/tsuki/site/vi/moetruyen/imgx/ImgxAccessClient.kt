package tsuki.site.vi.moetruyen.imgx

import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import tsuki.network.WebClient
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class ImgxAccessClient(
	private val client: WebClient,
	private val baseUrl: String,
	private val chapterUrl: String,
	private val document: Document,
	private val xhrHeaders: Headers,
) {
	private val keyPair = ImgxCrypto.generateEcdhP256()
	private var sequence = 0L
	private var lastAccessAt = 0L

	private suspend fun paceAccess() {
		val wait = 340L - (System.currentTimeMillis() - lastAccessAt)
		if (wait > 0) delay(wait)
		lastAccessAt = System.currentTimeMillis()
	}

	suspend fun fetchPages(media: List<ReaderMediaEntry>, totalPages: Int = 0): List<ImgxPageAccess> {
		val config = parseBootstrapConfig(document)
		val indexes = if (media.isNotEmpty()) media.map { it.pageIndex } else (0 until totalPages).toList()
		require(config.bootstrapUrl.isNotBlank()) { "IMGX document capability missing" }

		val bootstrapProof = ImgxCrypto.base64UrlEncode(ImgxCrypto.randomBytes(32))
		paceAccess()
		val bootstrap = client.httpPost(
			"$baseUrl${config.bootstrapUrl}".toHttpUrl(),
			JSONObject().put("readerPublicKey", keyPair.publicKey).put("bootstrapProof", bootstrapProof)
				.put("initialPageIndexes", JSONArray(config.initialIndexes)), xhrHeaders,
		).use { JSONObject(it.body.string()) }
		require(bootstrap.optBoolean("ok") && bootstrap.has("sealedCapability")) {
			"IMGX bootstrap failed: ${bootstrap.optString("code", "unknown")}"
		}
		val capability = ImgxCrypto.openSealedCapability(
			keyPair, bootstrap.getJSONObject("sealedCapability").toSealedChannel(), bootstrapProof,
		)
		require(capability.readerInstanceId == bootstrap.getString("readerInstanceId")) { "IMGX reader instance mismatch" }

		val granted = mutableMapOf<Int, ImgxPageAccess>()
		bootstrap.optJSONObject("sealedInitialPages")?.let { sealed ->
			ImgxCrypto.openSealedPages(keyPair, sealed.toSealedChannel(), bootstrapProof).forEach { granted[it.pageIndex] = it }
		}
		indexes.filterNot(granted::containsKey).chunked(10).forEach { batch ->
			sequence++
			val issuedAt = bootstrap.getLong("serverTime")
			val material = buildProofMaterial(
				capability.readerInstanceId, config.chapterId, config.requestPath, batch, issuedAt, sequence,
				ImgxCrypto.publicKeyHash(keyPair.publicKey),
			)
			val mac = Mac.getInstance("HmacSHA256").apply {
				init(SecretKeySpec(ImgxCrypto.base64UrlDecode(capability.secret), "HmacSHA256"))
			}
			val signature = ImgxCrypto.base64UrlEncode(mac.doFinal(material))
			val proof = JSONObject().put("version", PROOF_VERSION)
				.put("readerInstanceId", capability.readerInstanceId).put("issuedAt", issuedAt)
				.put("sequence", sequence).put("proof", signature)
			paceAccess()
			val response = client.httpPost(
				"$baseUrl${config.requestPath}".toHttpUrl(),
				JSONObject().put("pageIndexes", JSONArray(batch)).put("pageAccessProof", proof)
					.put("readerPublicKey", keyPair.publicKey), xhrHeaders,
			).use { JSONObject(it.body.string()) }
			require(response.optBoolean("ok") && response.has("sealedPages")) {
				"IMGX page access failed: ${response.optString("code", "unknown")}"
			}
			ImgxCrypto.openSealedPages(
				keyPair, response.getJSONObject("sealedPages").toSealedChannel(), signature,
			).forEach { granted[it.pageIndex] = it }
		}
		return if (media.isNotEmpty()) media.mapNotNull { granted[it.pageIndex] } else indexes.mapNotNull(granted::get)
	}

	companion object {
		private const val PROOF_VERSION = "imgx-page-access-proof-v3"

		fun parseBootstrapConfig(document: Document): ReaderBootstrapConfig {
			val text = document.select("script").joinToString("\n") { it.data() } + "\n" + document.html()
			val requestPath = Regex("""requestPath:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
			val chapterId = Regex("""chapterId:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
			val bootstrapUrl = Regex("""bootstrapUrl:\s*"([^"]*)"""").find(text)?.groupValues?.get(1).orEmpty()
			val initial = Regex("""initialIndexes:\s*\[([^\]]*)]""").find(text)?.groupValues?.get(1)
				?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
			if (requestPath != null && chapterId != null) return ReaderBootstrapConfig(requestPath, chapterId, bootstrapUrl, initial)
			val root = document.selectFirst("[data-reader-lazy-pages]") ?: error("IMGX reader metadata missing")
			val accessUrl = root.attr("data-reader-imgx-access-url").ifBlank { error("IMGX access URL missing") }
			val id = root.attr("data-reader-view-track-token").substringBefore('.').toLongOrNull() ?: error("IMGX chapter id missing")
			val total = root.attr("data-reader-total-pages").toIntOrNull() ?: 0
			return ReaderBootstrapConfig(accessUrl, id, bootstrapUrl, if (total > 0) listOf(total - 1) else emptyList())
		}

		fun encryptedMedia(document: Document): List<ReaderMediaEntry> {
			val attr = document.selectFirst("[data-reader-lazy-pages]")?.attr("data-reader-imgx-media").orEmpty()
			val raw = if (attr.isNotBlank()) runCatching { URLDecoder.decode(attr, "UTF-8") }.getOrDefault(attr) else
				Regex("""media:\s*(\[[\s\S]*?])\s*,\s*connection:""").find(document.html())?.groupValues?.get(1).orEmpty()
			if (raw.isBlank()) return emptyList()
			val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
			return (0 until array.length()).mapNotNull { i ->
				val o = array.optJSONObject(i) ?: return@mapNotNull null
				ReaderMediaEntry(o.getInt("pageIndex"), o.getString("storageKey"), o.getString("downloadUrl"),
					o.optInt("width").takeIf { o.has("width") }, o.optInt("height").takeIf { o.has("height") })
			}.filter { it.storageKey.startsWith("chapters/") && !it.storageKey.endsWith("/0.js") && !it.downloadUrl.endsWith("/0.js") }
		}

		fun buildProofMaterial(readerInstanceId: String, chapterId: Long, requestPath: String, pageIndexes: List<Int>,
			issuedAt: Long, sequence: Long, publicKeyHash: String): ByteArray {
			val indexes = pageIndexes.joinToString(",", "[", "]")
			return """["$PROOF_VERSION","$readerInstanceId",$chapterId,"$requestPath","",$indexes,$issuedAt,$sequence,"$publicKeyHash"]"""
				.toByteArray(Charsets.UTF_8)
		}
	}
}
