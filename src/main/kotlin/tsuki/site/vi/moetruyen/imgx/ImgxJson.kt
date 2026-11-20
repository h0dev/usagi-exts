package tsuki.site.vi.moetruyen.imgx

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
internal fun ByteArray.readIntBigEndian(offset: Int): Int =
	((this[offset].toInt() and 0xff) shl 24) or ((this[offset + 1].toInt() and 0xff) shl 16) or
		((this[offset + 2].toInt() and 0xff) shl 8) or (this[offset + 3].toInt() and 0xff)

private fun JSONObject.stringOrNull(name: String): String? = optString(name).takeIf { has(name) && it.isNotBlank() }
private fun JSONObject.longOrNull(name: String): Long? = optLong(name).takeIf { has(name) }
private fun JSONObject.intOrNull(name: String): Int? = optInt(name).takeIf { has(name) }

internal fun JSONObject.toSealedChannel(): SealedChannel = SealedChannel(
	getString("version"), getString("publicKey"), getString("iv"), getString("ciphertext"),
)

internal fun JSONObject.toGrant(): ImgxGrant {
	val channel = optJSONObject("channelKeys")?.let {
		ChannelKeys(it.getString("version"), it.getString("iv"), it.getString("ciphertext"))
	}
	return ImgxGrant(
		version = intOrNull("version"), algorithm = stringOrNull("algorithm"), imageId = stringOrNull("imageId"),
		issuedAt = longOrNull("issuedAt"), expiresAt = longOrNull("expiresAt"), nonce = stringOrNull("nonce"),
		keyNonce = stringOrNull("keyNonce"), signature = stringOrNull("signature"),
		wrappedDecodeKey = stringOrNull("wrappedDecodeKey"), wrappedContentKey = stringOrNull("wrappedContentKey"),
		wrappedV4Key = stringOrNull("wrappedV4Key"), decodeKey = stringOrNull("decodeKey"), channelKeys = channel,
	)
}

internal fun JSONObject.toPageAccess(): ImgxPageAccess = ImgxPageAccess(
	pageIndex = getInt("pageIndex"), storageKey = getString("storageKey"), downloadUrl = getString("downloadUrl"),
	width = intOrNull("width"), height = intOrNull("height"), grant = optJSONObject("grant")?.toGrant(),
)

internal fun JSONArray.toPages(): List<ImgxPageAccess> = (0 until length()).map { getJSONObject(it).toPageAccess() }
internal fun JSONArray.toCapabilities(): List<ReaderCapability> = (0 until length()).map {
	getJSONObject(it).run { ReaderCapability(getString("readerInstanceId"), getLong("chapterId"), getString("secret")) }
}

internal fun JSONObject.toDecryptedKeys(): DecryptedChannelKeys = DecryptedChannelKeys(
	decodeKey = stringOrNull("decodeKey"), wrappedDecodeKey = stringOrNull("wrappedDecodeKey"),
	wrappedContentKey = stringOrNull("wrappedContentKey"), wrappedV4Key = stringOrNull("wrappedV4Key"),
)
