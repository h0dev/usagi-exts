package tsuki.site.vi.moetruyen.imgx

internal data class ReaderMediaEntry(
	val pageIndex: Int,
	val storageKey: String,
	val downloadUrl: String,
	val width: Int? = null,
	val height: Int? = null,
)

internal data class SealedChannel(
	val version: String,
	val publicKey: String,
	val iv: String,
	val ciphertext: String,
)

internal data class ImgxGrant(
	val version: Int? = null,
	val algorithm: String? = null,
	val imageId: String? = null,
	val issuedAt: Long? = null,
	val expiresAt: Long? = null,
	val nonce: String? = null,
	val keyNonce: String? = null,
	val signature: String? = null,
	val wrappedDecodeKey: String? = null,
	val wrappedContentKey: String? = null,
	val wrappedV4Key: String? = null,
	val decodeKey: String? = null,
	val channelKeys: ChannelKeys? = null,
)

internal data class ChannelKeys(val version: String, val iv: String, val ciphertext: String)
internal data class DecryptedChannelKeys(
	val decodeKey: String? = null,
	val wrappedDecodeKey: String? = null,
	val wrappedContentKey: String? = null,
	val wrappedV4Key: String? = null,
)

internal data class ImgxPageAccess(
	val pageIndex: Int,
	val storageKey: String,
	val downloadUrl: String,
	val width: Int? = null,
	val height: Int? = null,
	val grant: ImgxGrant? = null,
)

internal data class ReaderBootstrapConfig(
	val requestPath: String,
	val chapterId: Long,
	val bootstrapUrl: String,
	val initialIndexes: List<Int>,
)

internal data class ReaderCapability(val readerInstanceId: String, val chapterId: Long, val secret: String)
