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
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InputStream

/**
 * An [DataSource] which extracts shoutcast metadata from audio stream. Uses an modified instance of
 * [OkHttpDataSource] as the underlying stream provider
 */

/**
 * *
 * @param userAgent The User-Agent string that should be used.
 * *
 * @param metadataListener An optional [ShoutcastMetadataListener] which receives all metadata updates from stream
 *
 */
class ShoutcastDataSource(private val userAgent: String, private val metadataListener: ShoutcastMetadataListener?) : HttpDataSource, MetadataListener {

    private val okhttpDataSource: OkHttpDataSource by lazy {
        OkHttpDataSource(OkHttpClient.Builder().build(),
                userAgent,
                null,
                null,
                null,
                HttpDataSource.RequestProperties().apply { set(ICY_METADATA, "1") })
    }

    private data class IcyHeader(
            var channels: String? = null,
            var bitrate: String? = null,
            var station: String? = null,
            var genre: String? = null,
            var url: String? = null
    )

    override fun getUri(): Uri? {
        return okhttpDataSource.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>>? {
        return okhttpDataSource.responseHeaders
    }

    override fun setRequestProperty(name: String, value: String) {
        return okhttpDataSource.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        okhttpDataSource.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        okhttpDataSource.clearAllRequestProperties()
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        val bytesToRead = okhttpDataSource.open(dataSpec)
        okhttpDataSource.responseByteStream = getInputStream(okhttpDataSource.responseByteStream)
        return bytesToRead
    }

    override fun close() {
        okhttpDataSource.close()
    }

    override fun read(buffer: ByteArray?, offset: Int, readLength: Int): Int {
        return okhttpDataSource.read(buffer, offset, readLength)
    }

    companion object {

        val MP3 = "audio/mpeg"
        val AAC = "audio/aac"
        val AACP = "audio/aacp"
        val OGG = "application/ogg"
        val ICY_METADATA = "Icy-Metadata"
        val ICY_METAINT = "icy-metaint"
        private val icyHeader = IcyHeader()

        private fun extractHeaderMetadata(headers: Map<String, List<String>>?): IcyHeader {
            icyHeader.station = headers?.get("icy-name")?.first()
            icyHeader.url = headers?.get("icy-url")?.first()
            icyHeader.genre = headers?.get("icy-genre")?.first()
            icyHeader.channels = headers?.get("icy-channels")?.first()
            icyHeader.bitrate = headers?.get("icy-br")?.first()
            return icyHeader
        }
    }

    @Throws(IOException::class)
    private fun getInputStream(rawStream: InputStream): InputStream? {
        var filterStream: InputStream? = null
        val response = okhttpDataSource.responseHeaders

        val contentType = response?.get("Content-Type")?.first()
        val interval = response?.get(ICY_METAINT)?.first()?.toInt()
        when (contentType) {
            MP3, AAC, AACP -> {
                filterStream = IcyInputStream(rawStream, interval ?: 0, null, this)
            }
            OGG -> filterStream = OggInputStream(rawStream, this)
        }
        return filterStream
    }

    override fun onMetadataReceived(artist: String?, song: String?, show: String?) {
        if (metadataListener != null) {
            val headerMetadata: IcyHeader = extractHeaderMetadata(okhttpDataSource.responseHeaders)
            val metadata = Metadata(artist, song, show, headerMetadata.channels, headerMetadata.bitrate, headerMetadata.station, headerMetadata.genre, headerMetadata.url)
            metadataListener.onMetadataReceived(metadata)
        }
    }
}