package se.materka.exoplayershoutcastdatasource.stream

/**
 * Copyright 2016 Mattias Karlsson

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.util.Log
import se.materka.TAG
import se.materka.exoplayershoutcastdatasource.MetadataListener
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.regex.Pattern

internal class IcyInputStream
/**
 * Creates a new input stream.
 * @param stream the underlying input stream
 * @param interval the interval of metadata frame is repeating (in bytes)
 *
 */
(stream: InputStream, private val interval: Int, private val listener: MetadataListener) : FilterInputStream(stream) {
    private var remaining: Int = interval

    @Throws(IOException::class)
    override fun read(): Int {
        val read = super.read()

        if (--remaining == 0) { // Time to read metadata
            readMetadata()
        }

        return read
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        val read = super.`in`.read(buffer, offset, if (remaining < len) remaining else len)

        if (remaining == read) {
            readMetadata()
        } else {
            remaining -= read
        }

        return read
    }

    /**
     * Tries to read all bytes into the target buffer.
     * @param b Target buffer
     * @param o Offset to start reading from
     * @param s Size to read
     * @return the number of really bytes read; if less than requested, then eof detected
     */
    @Throws(IOException::class)
    private fun readFully(b: ByteArray, o: Int, s: Int): Int {
        val buffer = b
        var offset = o
        var size = s
        var read: Int

        do {
            read = super.`in`.read(buffer, offset, size)
            if (read == -1)
                break
            offset += read
            size -= read
        } while (size > 0)
        return offset - o
    }

    @Throws(IOException::class)
    private fun readMetadata() {
        // Reset counter
        remaining = interval

        // Determine size of metadata
        var size = super.`in`.read()

        // either no metadata or eof:
        if (size < 1) return

        size *= 16

        val buffer = ByteArray(size)

        // Read whole chunk of metadata
        size = readFully(buffer, 0, size)

        // find the string end
        for (i in 0 until size) {
            if (buffer[i].toInt() == 0) {
                size = i
                break
            }
        }

        val s: String

        try {
            s = String(buffer, 0, size, charset("UTF-8"))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot convert bytes to String")
            return
        }
        parseMetadata(s)
    }


    /**
     * Parses the metadata
     * @param data the metadata string like: StreamTitle='...';StreamUrl='...';
     */
    private fun parseMetadata(data: String) {
        val match = Pattern.compile("StreamTitle='([^;]*)'").matcher(data.trim { it <= ' ' })
        if (match.find()) {
            // Assume metadata is separated by hyphen (And the order is Show - Artist - Song)
            val metadata = match.group(1).split(" - ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            when (metadata.size) {
                3 -> this.listener.onMetadataReceived(metadata[1], metadata[2], metadata[0])
                2 -> this.listener.onMetadataReceived(metadata[0], metadata[1], "")
                1 -> this.listener.onMetadataReceived("", "", metadata[0])
            }
        }
    }
}

