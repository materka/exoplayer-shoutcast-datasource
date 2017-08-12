package se.materka.exoplayershoutcastdatasource

/**
 * Copyright 2016 Mattias Karlsson

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSourceException
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.upstream.TransferListener
import com.google.android.exoplayer2.util.Predicate
import okhttp3.*
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.*
import java.util.concurrent.atomic.AtomicReference

/**
 * An [ShoutcastDataSource] that delegates to Square's [Call.Factory].
 */

class ShoutcastDataSource
/**
 * @param callFactory An [Call.Factory] for use by the source.
 * *
 * @param userAgent The User-Agent string that should be used.
 * *
 * @param contentTypePredicate An optional [Predicate]. If a content type is rejected by the
 * *     predicate then a [InvalidContentTypeException] is thrown from
 * *     [.open].
 * *
 * @param transferListener An optional transferListener.
 * *
 * @param cacheControl An optional [CacheControl] which sets all requests' Cache-Control
 * *     header. For example, you could force the network response for all requests.
 */
(callFactory: Call.Factory, userAgent: String,
 private val contentTypePredicate: Predicate<String>?, private val transferListener: TransferListener<in ShoutcastDataSource>?,
 private val shoutcastMetadataListener: ShoutcastMetadataListener?,
 private val cacheControl: CacheControl?) : HttpDataSource, MetadataListener {

    private data class IcyHeader(
            var channels: String? = null,
            var bitrate: String? = null,
            var station: String? = null,
            var genre: String? = null,
            var url: String? = null
    )

    private val callFactory: Call.Factory
    private val userAgent: String
    private val requestProperties: HashMap<String, String>

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var responseByteStream: InputStream? = null
    private var opened: Boolean = false

    private var bytesToSkip: Long = 0
    private var bytesToRead: Long = 0
    private var bytesSkipped: Long = 0
    private var bytesRead: Long = 0

    private var icyHeader: IcyHeader = IcyHeader()

    /**
     * @param callFactory An [Call.Factory] for use by the source.
     * *
     * @param userAgent The User-Agent string that should be used.
     * *
     * @param contentTypePredicate An optional [Predicate]. If a content type is rejected by the
     * *     predicate then a InvalidContentTypeException} is thrown from [.open].
     */
    constructor(callFactory: Call.Factory, userAgent: String,
                contentTypePredicate: Predicate<String>) : this(callFactory, userAgent, contentTypePredicate, null, null)

    /**
     * @param callFactory An [Call.Factory] for use by the source.
     * *
     * @param userAgent The User-Agent string that should be used.
     * *
     * @param contentTypePredicate An optional [Predicate]. If a content type is rejected by the
     * *     predicate then a [InvalidContentTypeException] is thrown from
     * *     [.open].
     * *
     * @param transferListener An optional transferListener.
     */
    private constructor(callFactory: Call.Factory, userAgent: String,
                        contentTypePredicate: Predicate<String>, transferListener: TransferListener<in ShoutcastDataSource>?,
                        shoutcastMetadataListener: ShoutcastMetadataListener?) : this(callFactory, userAgent, contentTypePredicate, transferListener, shoutcastMetadataListener, null)

    init {
        this.callFactory = callFactory
        this.userAgent = userAgent
        this.requestProperties = HashMap<String, String>()
    }

    override fun getUri(): Uri? {
        return if (response == null) null else Uri.parse(response!!.request().url().toString())
    }

    override fun getResponseHeaders(): Map<String, List<String>>? {
        return if (response == null) null else response!!.headers().toMultimap()
    }

    override fun setRequestProperty(name: String, value: String) {
        synchronized(requestProperties) {
            requestProperties.put(name, value)
        }
    }

    override fun clearRequestProperty(name: String) {
        synchronized(requestProperties) {
            requestProperties.remove(name)
        }
    }

    override fun clearAllRequestProperties() {
        synchronized(requestProperties) {
            requestProperties.clear()
        }
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.bytesRead = 0
        this.bytesSkipped = 0
        setRequestProperty(ICY_METADATA, "1")
        val request = makeRequest(dataSpec)
        try {
            response = callFactory.newCall(request).execute()
            response?.let {
                responseByteStream = getInputStream(it)
            }
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException("Unable to connect to " + dataSpec.uri.toString(), e,
                    dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }

        val responseCode = response?.code() ?: 0

        // Check for a valid response code.
        if (!(response?.isSuccessful ?: false)) {
            val headers = request.headers().toMultimap()
            closeConnectionQuietly()
            val exception = HttpDataSource.InvalidResponseCodeException(
                    responseCode, headers, dataSpec)
            if (responseCode == 416) {
                exception.initCause(DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE))
            }
            throw exception
        }

        // Check for a valid content type.
        val mediaType = response!!.body()?.contentType()
        val contentType = mediaType?.toString()
        if (contentTypePredicate != null && !contentTypePredicate.evaluate(contentType)) {
            closeConnectionQuietly()
            throw HttpDataSource.InvalidContentTypeException(contentType, dataSpec)
        }

        // If we requested a range starting from a non-zero position and received a 200 rather than a
        // 206, then the server does not support partial requests. We'll need to manually skip to the
        // requested position.
        bytesToSkip = if (responseCode == 200 && dataSpec.position != 0L) dataSpec.position else 0

        // Determine the length of the data to be read, after skipping.
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesToRead = dataSpec.length
        } else {
            val contentLength = response!!.body()?.contentLength() ?: -1L
            bytesToRead = if (contentLength != -1L) contentLength - bytesToSkip else C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferListener?.onTransferStart(this, dataSpec)

        return bytesToRead
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        try {
            skipInternal()
            return readInternal(buffer, offset, readLength)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_READ)
        }

    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun close() {
        if (opened) {
            opened = false
            transferListener?.onTransferEnd(this)
            closeConnectionQuietly()
        }
    }

    /**
     * Establishes a connection.
     */
    private fun makeRequest(dataSpec: DataSpec): Request {
        val allowGzip = dataSpec.flags and DataSpec.FLAG_ALLOW_GZIP != 0

        val url = HttpUrl.parse(dataSpec.uri.toString())
        val builder = Request.Builder().url(url)
        if (cacheControl != null) {
            builder.cacheControl(cacheControl)
        }
        synchronized(requestProperties) {
            for ((key, value) in requestProperties) {
                builder.addHeader(key, value)
            }
        }
        builder.addHeader("User-Agent", userAgent)
        if (!allowGzip) {
            builder.addHeader("Accept-Encoding", "identity")
        }
        if (dataSpec.postBody != null) {
            builder.post(RequestBody.create(null, dataSpec.postBody))
        }
        return builder.build()
    }

    @Throws(IOException::class)
    private fun getInputStream(response: Response): InputStream? {
        val contentType = response.header("Content-Type")
        setIcyHeader(response.headers())
        var stream = response.body()?.byteStream()
        if (stream != null) {
            when (contentType) {
                MP3, AAC, AACP -> {
                    val interval = Integer.parseInt(response.header(ICY_METAINT))
                    stream = IcyInputStream(stream, interval, null, this)
                }
                OGG -> stream = OggInputStream(stream, this)
            }
        }
        return stream
    }

    /**
     * Skips any bytes that need skipping. Else does nothing.
     *
     *
     * This implementation is based roughly on `libcore.io.Streams.skipByReading()`.

     * @throws InterruptedIOException If the thread is interrupted during the operation.
     * *
     * @throws EOFException If the end of the input stream is reached before the bytes are skipped.
     */
    @Throws(IOException::class)
    private fun skipInternal() {
        if (bytesSkipped == bytesToSkip) {
            return
        }

        // Acquire the shared skip buffer.
        var skipBuffer: ByteArray? = skipBufferReference.getAndSet(null)
        if (skipBuffer == null) {
            skipBuffer = ByteArray(4096)
        }

        while (bytesSkipped != bytesToSkip) {
            val readLength = Math.min(bytesToSkip - bytesSkipped, skipBuffer.size.toLong()).toInt()
            val read = responseByteStream!!.read(skipBuffer, 0, readLength)
            if (Thread.interrupted()) {
                throw InterruptedIOException()
            }
            if (read == -1) {
                throw EOFException()
            }
            bytesSkipped += read.toLong()
            transferListener?.onBytesTransferred(this, read)
        }

        // Release the shared skip buffer.
        skipBufferReference.set(skipBuffer)
    }

    /**
     * Reads up to `length` bytes of data and stores them into `buffer`, starting at
     * index `offset`.
     *
     *
     * This method blocks until at least one byte of data can be read, the end of the opened range is
     * detected, or an exception is thrown.

     * @param buffer The buffer into which the read data should be stored.
     * *
     * @param offset The start offset into `buffer` at which data should be written.
     * *
     * @param readLength The maximum number of bytes to read.
     * *
     * @return The number of bytes read, or [C.RESULT_END_OF_INPUT] if the end of the opened
     * *     range is reached.
     * *
     * @throws IOException If an error occurs reading from the source.
     */
    @Throws(IOException::class)
    private fun readInternal(buffer: ByteArray, offset: Int, readLength: Int): Int {
        var readLength = readLength
        if (readLength == 0) {
            return 0
        }
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val bytesRemaining = bytesToRead - bytesRead
            if (bytesRemaining == 0L) {
                return C.RESULT_END_OF_INPUT
            }
            readLength = Math.min(readLength.toLong(), bytesRemaining).toInt()
        }

        val read = responseByteStream!!.read(buffer, offset, readLength)
        if (read == -1) {
            if (bytesToRead != C.LENGTH_UNSET.toLong()) {
                // End of stream reached having not read sufficient data.
                throw EOFException()
            }
            return C.RESULT_END_OF_INPUT
        }

        bytesRead += read.toLong()
        transferListener?.onBytesTransferred(this, read)
        return read
    }

    /**
     * Closes the current connection quietly, if there is one.
     */
    private fun closeConnectionQuietly() {
        response?.body()?.close()
        response = null
        responseByteStream = null
    }

    private fun setIcyHeader(headers: Headers) {
        icyHeader.station = headers.get("icy-name")
        icyHeader.url = headers.get("icy-url")
        icyHeader.genre = headers.get("icy-genre")
        icyHeader.channels = headers.get("icy-channels")
        icyHeader.bitrate = headers.get("icy-br")
    }

    override fun onMetadataReceived(artist: String?, song: String?, show: String?) {
        if (shoutcastMetadataListener != null) {
            val metadata = Metadata(artist, song, show, icyHeader.channels, icyHeader.bitrate, icyHeader.station, icyHeader.genre, icyHeader.url)
            shoutcastMetadataListener.onMetadataReceived(metadata)
        }
    }

    companion object {

        private val MP3 = "audio/mpeg"
        private val AAC = "audio/aac"
        private val AACP = "audio/aacp"
        private val OGG = "application/ogg"
        private val ICY_METADATA = "Icy-Metadata"
        private val ICY_METAINT = "icy-metaint"

        private val skipBufferReference = AtomicReference<ByteArray>()
    }
}