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
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSourceException
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import okhttp3.*
import se.materka.TAG
import se.materka.exoplayershoutcastdatasource.stream.IcyInputStream
import se.materka.exoplayershoutcastdatasource.stream.OggInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference

/**
 * A DataSource which extracts shoutcast metadata from audio stream.
 */

/**
 * *
 * @param userAgent The User-Agent string that should be used.
 * *
 * @param metadataListener An optional [ShoutcastMetadataListener] which receives all metadata updates from stream
 *
 */
class ShoutcastDataSource(private val userAgent: String, private val metadataListener: ShoutcastMetadataListener?) : HttpDataSource, MetadataListener {

    companion object {
        val audioFormat = hashMapOf(
                Pair("audio/mpeg", "MP3"),
                Pair("audio/aac", "AAC"),
                Pair("audio/aacp", "AACP"),
                Pair("application/ogg", "OGG")
        )

        val ICY_METADATA = "Icy-Metadata"
        val ICY_METAINT = "icy-metaint"
    }

    private val requestProperties: HttpDataSource.RequestProperties = HttpDataSource.RequestProperties().apply { set(ICY_METADATA, "1") }
    private val skipBufferReference = AtomicReference<ByteArray>()
    private val callFactory: OkHttpClient = OkHttpClient.Builder().build()
    private var response: Response? = null
    private var dataSpec: DataSpec? = null
    private var responseByteStream: InputStream? = null

    private var bytesToSkip: Long = 0
    private var bytesToRead: Long = 0
    private var bytesSkipped: Long = 0
    private var bytesRead: Long = 0


    override fun getUri(): Uri? {
        return this.response?.let { Uri.parse(it.request().url().toString()) }
    }

    override fun getResponseHeaders(): Map<String, List<String>>? {
        return this.response?.let { it.headers()?.toMultimap() }
    }

    override fun setRequestProperty(name: String, value: String) {
        this.requestProperties.set(name, value)
    }

    override fun clearRequestProperty(name: String) {
        this.requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        this.requestProperties.clear()
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {

        this.dataSpec = dataSpec
        this.bytesRead = 0
        this.bytesSkipped = 0
        val request = makeRequest(dataSpec)
        try {
            this.response = callFactory.newCall(request).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException("Unable to connect to " + dataSpec.uri.toString(), e,
                    dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }

        val responseCode = response?.code() ?: -1

        // Check for a valid response code.
        if (response?.isSuccessful != true) {
            val headers = request.headers().toMultimap()
            close()
            val exception = HttpDataSource.InvalidResponseCodeException(
                    responseCode, headers, dataSpec)
            if (responseCode == 416) {
                exception.initCause(DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE))
            }
            throw exception
        }

        response?.body()?.byteStream()?.let { this.responseByteStream = filterStream(it) }

        // If we requested a range starting from a non-zero position and received a 200 rather than a
        // 206, then the server does not support partial requests. We'll need to manually skip to the
        // requested position.
        bytesToSkip = if (responseCode == 200 && dataSpec.position != 0L) dataSpec.position else 0

        // Determine the length of the data to be read, after skipping.
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesToRead = dataSpec.length
        } else {
            response?.body()?.contentLength()?.let { length ->
                bytesToRead = if (length != -1L) length - bytesToSkip else C.LENGTH_UNSET.toLong()
            }
        }

        return bytesToRead
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun close() {
        response?.body()?.close()
        response = null
        responseByteStream = null
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

    override fun onMetadataReceived(artist: String?, title: String?, show: String?) {
        if (this.metadataListener != null) {
            val headers = responseHeaders
            val channels = headers?.get("icy-channels")?.first()?.toLong()
            val format = audioFormat[headers?.get("Content-Type")?.first()]
            val station = headers?.get("icy-name")?.first()
            val url = headers?.get("icy-url")?.first()
            val genre = headers?.get("icy-genre")?.first()
            val bitrate = headers?.get("icy-br")?.first()?.toLong()

            val metadata = ShoutcastMetadata.Builder()
                    .putString(ShoutcastMetadata.METADATA_KEY_ARTIST, artist)
                    .putString(ShoutcastMetadata.METADATA_KEY_TITLE, title)
                    .putString(ShoutcastMetadata.METADATA_KEY_SHOW, show)
                    .putString(ShoutcastMetadata.METADATA_KEY_GENRE, genre)
                    .putString(ShoutcastMetadata.METADATA_KEY_STATION, station)
                    .putString(ShoutcastMetadata.METADATA_KEY_FORMAT, format)
                    .putString(ShoutcastMetadata.METADATA_KEY_URL, url)
                    .putLong(ShoutcastMetadata.METADATA_KEY_BITRATE, bitrate)
                    .putLong(ShoutcastMetadata.METADATA_KEY_CHANNELS, channels)
                    .build()

            Log.d(TAG, "ShoutcastMetadata received\n$metadata")
            this.metadataListener.onMetadataReceived(metadata)
        }
    }

    /**
     * Establishes a connection.
     */
    private fun makeRequest(dataSpec: DataSpec): Request {
        val position = dataSpec.position
        val length = dataSpec.length
        val allowGzip = dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)

        val url = HttpUrl.parse(dataSpec.uri.toString())
        val builder = Request.Builder().url(url!!)

        for ((key, value) in requestProperties.snapshot) {
            builder.header(key, value)
        }

        if (!(position == 0L && length == C.LENGTH_UNSET.toLong())) {
            var rangeRequest = "bytes=$position-"
            if (length != C.LENGTH_UNSET.toLong()) {
                rangeRequest += position + length - 1
            }
            builder.addHeader("Range", rangeRequest)
        }


        if (!allowGzip) {
            builder.addHeader("Accept-Encoding", "identity")
        }

        if (dataSpec.postBody != null) {
            builder.post(RequestBody.create(null, dataSpec.postBody))
        }

        builder.addHeader("User-Agent", userAgent)
        return builder.build()
    }

    /**
     * Skips any bytes that need skipping. Else does nothing.
     *
     *
     * This implementation is based roughly on `libcore.io.Streams.skipByReading()`.
     *
     * @throws InterruptedIOException If the thread is interrupted during the operation.
     * @throws EOFException If the end of the input stream is reached before the bytes are skipped.
     */
    @Throws(IOException::class)
    private fun skipInternal() {
        if (bytesSkipped == bytesToSkip) {
            return
        }

        // Acquire the shared skip buffer.
        val skipBuffer: ByteArray = skipBufferReference.getAndSet(null) ?: ByteArray(4096)

        while (bytesSkipped != bytesToSkip) {
            val readLength = Math.min(bytesToSkip - bytesSkipped, skipBuffer.size.toLong()).toInt()
            val read = responseByteStream?.read(skipBuffer, 0, readLength) ?: -1
            if (Thread.interrupted()) {
                throw InterruptedIOException()
            }
            if (read == -1) {
                throw EOFException()
            }
            bytesSkipped += read.toLong()
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
     *
     * @param buffer The buffer into which the read data should be stored.
     * @param offset The start offset into `buffer` at which data should be written.
     * @param length The maximum number of bytes to read.
     * @return The number of bytes read, or [C.RESULT_END_OF_INPUT] if the end of the opened
     * range is reached.
     * @throws IOException If an error occurs reading from the source.
     */
    @Throws(IOException::class)
    private fun readInternal(buffer: ByteArray, offset: Int, length: Int): Int {
        var readLength = length
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

        val read = responseByteStream?.read(buffer, offset, readLength) ?: -1
        if (read == -1) {
            if (bytesToRead != C.LENGTH_UNSET.toLong()) {
                // End of stream reached having not read sufficient data.
                throw EOFException()
            }
            return C.RESULT_END_OF_INPUT
        }

        bytesRead += read.toLong()
        return read
    }

    /**
     * Filter the supplied stream for metadata
     *
     * @param stream The unfiltered shoutcast stream. Supports MP3, AAC, AACP and OGG
     * @return InputStream which has been filtered for metadata
     */
    private fun filterStream(stream: InputStream): InputStream? {
        var filteredStream: InputStream = stream
        val headers = responseHeaders

        val interval = headers?.get(ICY_METAINT)?.first()?.toInt()
        headers?.get("Content-Type")?.first()?.let { contentType ->
            if (audioFormat.containsKey(contentType)) {
                filteredStream = when (audioFormat[contentType]) {
                    "MP3", "AAC", "AACP" -> {
                        IcyInputStream(stream, interval ?: 0, this)
                    }
                    "OGG" -> OggInputStream(stream, this)
                    else -> {
                        Log.e(TAG, "Unsupported format for extracting metadata")
                        stream
                    }
                }

            }
        }
        return filteredStream
    }
}