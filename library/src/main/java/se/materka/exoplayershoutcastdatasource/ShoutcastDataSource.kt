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
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import okhttp3.OkHttpClient
import se.materka.TAG
import se.materka.exoplayershoutcastdatasource.stream.IcyInputStream
import se.materka.exoplayershoutcastdatasource.stream.OggInputStream
import java.io.InputStream

/**
 * An DataSource which extracts shoutcast metadata from audio stream. Uses an modified instance of
 * OkHttpDataSource as the underlying stream provider
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

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun close() {
        okhttpDataSource.close()
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun read(buffer: ByteArray?, offset: Int, readLength: Int): Int {
        return okhttpDataSource.read(buffer, offset, readLength)
    }

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

    private fun getInputStream(rawStream: InputStream): InputStream? {
        var filterStream: InputStream = rawStream
        val response = okhttpDataSource.responseHeaders

        val interval = response?.get(ICY_METAINT)?.first()?.toInt()
        response?.get("Content-Type")?.first()?.let { contentType ->
            if (audioFormat.containsKey(contentType)) {
                filterStream = when (audioFormat[contentType]) {
                    "MP3", "AAC", "AACP" -> {
                        IcyInputStream(rawStream, interval ?: 0, this)
                    }
                    "OGG" -> OggInputStream(rawStream, this)
                    else -> {
                        Log.e(TAG, "Unsupported format for extracting metadata")
                        rawStream
                    }
                }

            }
        }
        return filterStream
    }

    override fun onMetadataReceived(artist: String?, title: String?, show: String?) {
        if (metadataListener != null) {
            val channels = okhttpDataSource.responseHeaders?.get("icy-channels")?.first()?.toLong()
            val format = audioFormat[okhttpDataSource.responseHeaders?.get("Content-Type")?.first()]
            val station = okhttpDataSource.responseHeaders?.get("icy-name")?.first()
            val url = okhttpDataSource.responseHeaders?.get("icy-url")?.first()
            val genre = okhttpDataSource.responseHeaders?.get("icy-genre")?.first()
            val bitrate = okhttpDataSource.responseHeaders?.get("icy-br")?.first()?.toLong()

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
            metadataListener.onMetadataReceived(metadata)
        }
    }
}