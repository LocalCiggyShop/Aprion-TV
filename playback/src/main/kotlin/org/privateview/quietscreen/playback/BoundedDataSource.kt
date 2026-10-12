package org.privateview.quietscreen.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Bound downloaded manifests/captions, including decompressed/unknown-length
 * responses. Media segments retain streaming behaviour and are not buffered here. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BoundedDataSource(private val delegate: DataSource, private val limit: Long) : DataSource {
    private var consumed = 0L
    init { require(limit > 0) }
    override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
    override fun open(dataSpec: DataSpec): Long {
        consumed = 0
        val length = delegate.open(dataSpec)
        if (length > limit) { delegate.close(); throw IOException("Bounded response exceeded its limit") }
        return length
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val count = delegate.read(buffer, offset, minOf(length.toLong(), limit - consumed + 1).toInt())
        if (count != C.RESULT_END_OF_INPUT) {
            consumed += count
            if (consumed > limit) throw IOException("Bounded response exceeded its limit")
        }
        return count
    }
    override fun getUri(): Uri? = delegate.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
    override fun close() = delegate.close()
    class Factory(private val delegate: DataSource.Factory, private val limit: Long) : DataSource.Factory {
        override fun createDataSource(): DataSource = BoundedDataSource(delegate.createDataSource(), limit)
    }
}
