package play.ott.nativeapp.data

/** Encrypted database rows must remain smaller than Android's CursorWindow. */
internal object XtreamCacheCodec {
    const val CHUNK_BYTES = 128 * 1024
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    fun chunks(bytes: ByteArray): List<ByteArray> {
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Xtream cache too large; data is not truncated" }
        return (0 until bytes.size step CHUNK_BYTES).map {
            bytes.copyOfRange(it, minOf(it + CHUNK_BYTES, bytes.size))
        }
    }
}
