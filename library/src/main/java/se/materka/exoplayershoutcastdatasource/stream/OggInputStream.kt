package se.materka.exoplayershoutcastdatasource.stream

/**
 * Copyright 2016 Mattias Karlsson
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.util.Log
import com.google.android.exoplayer2.util.ParsableByteArray
import com.google.android.exoplayer2.util.Util
import se.materka.exoplayershoutcastdatasource.MetadataListener
import java.io.IOException
import java.io.InputStream
import java.util.*

/**
 *
 */
internal class OggInputStream(stream: InputStream, private val listener: MetadataListener) : PeekInputStream(stream) {

    private val holder = PacketInfoHolder()
    private val idHeader = IdHeader()
    private val commentHeader = CommentHeader()
    private val pageHeader = PageHeader()

    private val packetArray = ParsableByteArray(ByteArray(255 * 255), 0)
    private val headerArray = ParsableByteArray(282)

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        try {
            if (peekPacket(this, this.packetArray, this.headerArray, this.pageHeader, this.holder)) {
                unpackIdHeader(this.packetArray, this.idHeader)
                unpackCommentHeader(this.packetArray, this.commentHeader, this.listener)
            }

        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        return super.read(target, offset, length)
    }

    private data class PageHeader(
            var revision: Int = 0,
            var type: Int = 0,
            var granulePosition: Long = 0,
            var streamSerialNumber: Long = 0,
            var pageSequenceNumber: Long = 0,
            var pageChecksum: Long = 0,
            var pageSegmentCount: Int = 0,
            var headerSize: Int = 0,
            var bodySize: Int = 0,
            val laces: IntArray = IntArray(255)) {

        fun reset() {
            this.revision = 0
            this.type = 0
            this.granulePosition = 0L
            this.streamSerialNumber = 0L
            this.pageSequenceNumber = 0L
            this.pageChecksum = 0L
            this.pageSegmentCount = 0
            this.headerSize = 0
            this.bodySize = 0
        }
    }

    private data class IdHeader(
            var version: Long = 0,
            var audioChannels: Int = 0,
            var audioSampleRate: Long = 0,
            var bitRateMaximum: Int = 0,
            var bitRateNominal: Int = 0,
            var bitRateMinimum: Int = 0,
            var blockSize0: Int = 0,
            var blockSize1: Int = 0) {

        fun reset() {
            this.audioChannels = 0
            this.audioSampleRate = 0
            this.bitRateMaximum = 0
            this.bitRateNominal = 0
            this.bitRateMinimum = 0
            this.blockSize0 = 0
            this.blockSize1 = 0
        }
    }

    private data class CommentHeader(
            var vendor: String = "",
            val comments: HashMap<String, String> = HashMap(),
            var length: Int = 0) {

        fun reset() {
            this.vendor = ""
            this.comments.clear()
            this.length = 0
        }
    }

    data class PacketInfoHolder(
            var size: Int = 0,
            var segmentCount: Int = 0)

    companion object {
        private val TAG = OggInputStream::class.java.name

        @Throws(IOException::class, InterruptedException::class)
        private fun peekPacket(stream: PeekInputStream, packetArray: ParsableByteArray, scratch: ParsableByteArray, header: PageHeader, holder: PacketInfoHolder): Boolean {
            var segmentIndex: Int
            var currentSegmentIndex = -1
            packetArray.reset()
            var packetComplete = false
            while (!packetComplete) {
                if (currentSegmentIndex < 0) {
                    if (!unpackPageHeader(stream, scratch, header)) {
                        return false
                    }

                    segmentIndex = 0
                    if ((header.type and 1) == 1 && packetArray.limit() == 0) {
                        calculatePacketSize(header, segmentIndex, holder)
                        segmentIndex += holder.segmentCount
                    }

                    currentSegmentIndex = segmentIndex
                }

                calculatePacketSize(header, currentSegmentIndex, holder)
                segmentIndex = currentSegmentIndex + holder.segmentCount
                if (holder.size > 0) {
                    stream.peekFully(packetArray.data, packetArray.limit(), holder.size)
                    packetArray.setLimit(packetArray.limit() + holder.size)
                    packetComplete = header.laces[segmentIndex - 1] != 255
                }
                currentSegmentIndex = if (segmentIndex == header.pageSegmentCount) -1 else segmentIndex
            }

            return true
        }

        private fun calculatePacketSize(header: PageHeader, startSegmentIndex: Int, holder: PacketInfoHolder) {
            holder.segmentCount = 0
            holder.size = 0

            var segmentLength: Int
            while (startSegmentIndex + holder.segmentCount < header.pageSegmentCount) {
                segmentLength = header.laces[startSegmentIndex + holder.segmentCount++]
                holder.size += segmentLength
                if (segmentLength != 255) {
                    break
                }
            }

        }

        @Throws(IOException::class, InterruptedException::class)
        private fun unpackPageHeader(stream: PeekInputStream, scratch: ParsableByteArray, header: PageHeader): Boolean {
            scratch.reset()
            header.reset()
            if (stream.peekFully(scratch.data, 0, 27, true)) {
                if (scratch.readUnsignedInt().toInt() == Util.getIntegerCodeForString("OggS")) {
                    header.revision = scratch.readUnsignedByte()
                    if (header.revision != 0) {
                        return false
                    } else {
                        header.type = scratch.readUnsignedByte()
                        header.granulePosition = scratch.readLittleEndianLong()
                        header.streamSerialNumber = scratch.readLittleEndianUnsignedInt()
                        header.pageSequenceNumber = scratch.readLittleEndianUnsignedInt()
                        header.pageChecksum = scratch.readLittleEndianUnsignedInt()
                        header.pageSegmentCount = scratch.readUnsignedByte()
                        scratch.reset()
                        header.headerSize = 27 + header.pageSegmentCount
                        stream.peekFully(scratch.data, 0, header.pageSegmentCount)

                        for (i in 0 until header.pageSegmentCount) {
                            header.laces[i] = scratch.readUnsignedByte()
                            header.bodySize += header.laces[i]
                        }
                        return true
                    }
                }
            }
            return false
        }

        private fun unpackIdHeader(scratch: ParsableByteArray, header: IdHeader) {
            if (verifyVorbisHeader(1, scratch)) {
                header.reset()
                header.version = scratch.readLittleEndianUnsignedInt()
                header.audioChannels = scratch.readUnsignedByte()
                header.audioSampleRate = scratch.readLittleEndianUnsignedInt()
                header.bitRateMaximum = scratch.readLittleEndianInt()
                header.bitRateNominal = scratch.readLittleEndianInt()
                header.bitRateMinimum = scratch.readLittleEndianInt()

                val blockSize = scratch.readUnsignedByte()
                header.blockSize0 = Math.pow(2.0, (blockSize and 15).toDouble()).toInt()
                header.blockSize1 = Math.pow(2.0, (blockSize shr 4).toDouble()).toInt()
            }
        }

        private fun unpackCommentHeader(scratch: ParsableByteArray, header: CommentHeader, listener: MetadataListener) {
            if (verifyVorbisHeader(3, scratch)) {
                header.reset()
                val vendorLength = scratch.readLittleEndianUnsignedInt().toInt()
                var length = 7 + 4
                header.vendor = scratch.readString(vendorLength)
                length += header.vendor.length
                val commentListLen = scratch.readLittleEndianUnsignedInt()
                length += 4

                var len: Int
                var comment: String
                var i = 0
                while (i.toLong() < commentListLen) {
                    len = scratch.readLittleEndianUnsignedInt().toInt()
                    length += 4
                    comment = scratch.readString(len)
                    unPackComment(comment, header.comments)
                    length += comment.length
                    ++i
                }
                header.length = length
                metadataReceived(header.comments["ARTIST"] ?: "", header.comments["TITLE"] ?: "", listener)
            }

        }

        private fun unPackComment(comment: String, commentContainer: HashMap<String, String>) {
            if (comment.contains("=")) {
                val kv = comment.split("=".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (kv.size == 2) {
                    commentContainer.put(kv[0], kv[1])
                } else if (kv.size == 1) {
                    commentContainer.put(kv[0], "")
                }
            }
        }

        /**
         * Verifies whether the next bytes in `header` are a vorbis header of the given
         * `headerType`.
         *
         * @param headerType the type of the header expected.
         * @param header the alleged header bytes.
         * @return True/False if correct header type and actual vorbis header
         */
        fun verifyVorbisHeader(headerType: Int, header: ParsableByteArray): Boolean {
            header.reset()
            return header.readUnsignedByte() == headerType && header.readString(6) == "vorbis"
        }

        /**
         * Invoke listeners for metadata
         * @param artist
         * @param song
         * @param listener
         */
        private fun metadataReceived(artist: String, song: String, listener: MetadataListener?) {
            Log.i(TAG, "Metadata received: ")
            Log.i(TAG, "Artist: " + artist)
            Log.i(TAG, "Song: " + song)
            listener?.onMetadataReceived(artist, song, "")
        }
    }
}


