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

import com.google.android.exoplayer2.util.ParsableByteArray
import com.google.android.exoplayer2.util.Util
import se.materka.exoplayershoutcastdatasource.MetadataListener
import java.io.IOException
import java.io.InputStream
import java.util.*

/**
 * Oggstein`s monster of bits and pieces from Exoplayer`s Ogg handling functionality
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
            if (peekPacket()) {
                this.idHeader.unpack(this.packetArray)
                this.commentHeader.unpack(this.packetArray, this.listener)
            }

        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        return super.read(target, offset, length)
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun peekPacket(): Boolean {
        var segmentIndex: Int
        var currentSegmentIndex = -1
        packetArray.reset()
        var packetComplete = false
        while (!packetComplete) {
            if (currentSegmentIndex < 0) {
                if (!this.pageHeader.unpack(this, this.headerArray)) {
                    return false
                }

                segmentIndex = 0
                if ((this.pageHeader.type and 1) == 1 && packetArray.limit() == 0) {
                    calculatePacketSize(segmentIndex)
                    segmentIndex += holder.segmentCount
                }

                currentSegmentIndex = segmentIndex
            }

            calculatePacketSize(currentSegmentIndex)
            segmentIndex = currentSegmentIndex + holder.segmentCount
            if (holder.size > 0) {
                this.peekFully(packetArray.data, packetArray.limit(), holder.size)
                packetArray.setLimit(packetArray.limit() + holder.size)
                packetComplete = this.pageHeader.laces[segmentIndex - 1] != 255
            }
            currentSegmentIndex = if (segmentIndex == this.pageHeader.pageSegmentCount) -1 else segmentIndex
        }

        return true
    }

    private fun calculatePacketSize(startSegmentIndex: Int) {
        this.holder.segmentCount = 0
        this.holder.size = 0

        var segmentLength: Int
        while (startSegmentIndex + holder.segmentCount < this.pageHeader.pageSegmentCount) {
            segmentLength = this.pageHeader.laces[startSegmentIndex + holder.segmentCount++]
            holder.size += segmentLength
            if (segmentLength != 255) {
                break
            }
        }
    }

    private inner class PageHeader(
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

        @Throws(IOException::class, InterruptedException::class)
        fun unpack(stream: PeekInputStream, scratch: ParsableByteArray): Boolean {
            scratch.reset()
            this.reset()
            if (stream.peekFully(scratch.data, 0, 27, true)) {
                if (scratch.readUnsignedInt().toInt() == Util.getIntegerCodeForString("OggS")) {
                    this.revision = scratch.readUnsignedByte()
                    if (this.revision != 0) {
                        return false
                    } else {
                        this.type = scratch.readUnsignedByte()
                        this.granulePosition = scratch.readLittleEndianLong()
                        this.streamSerialNumber = scratch.readLittleEndianUnsignedInt()
                        this.pageSequenceNumber = scratch.readLittleEndianUnsignedInt()
                        this.pageChecksum = scratch.readLittleEndianUnsignedInt()
                        this.pageSegmentCount = scratch.readUnsignedByte()
                        scratch.reset()
                        this.headerSize = 27 + this.pageSegmentCount
                        stream.peekFully(scratch.data, 0, this.pageSegmentCount)

                        for (i in 0 until this.pageSegmentCount) {
                            this.laces[i] = scratch.readUnsignedByte()
                            this.bodySize += this.laces[i]
                        }
                        return true
                    }
                }
            }
            return false
        }

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

    private inner class IdHeader(
            var version: Long = 0,
            var audioChannels: Int = 0,
            var audioSampleRate: Long = 0,
            var bitRateMaximum: Int = 0,
            var bitRateNominal: Int = 0,
            var bitRateMinimum: Int = 0,
            var blockSize0: Int = 0,
            var blockSize1: Int = 0) {

        fun unpack(scratch: ParsableByteArray) {
            if (verifyVorbisHeader(1, scratch)) {
                this.reset()
                this.version = scratch.readLittleEndianUnsignedInt()
                this.audioChannels = scratch.readUnsignedByte()
                this.audioSampleRate = scratch.readLittleEndianUnsignedInt()
                this.bitRateMaximum = scratch.readLittleEndianInt()
                this.bitRateNominal = scratch.readLittleEndianInt()
                this.bitRateMinimum = scratch.readLittleEndianInt()

                val blockSize = scratch.readUnsignedByte()
                this.blockSize0 = Math.pow(2.0, (blockSize and 15).toDouble()).toInt()
                this.blockSize1 = Math.pow(2.0, (blockSize shr 4).toDouble()).toInt()
            }
        }

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

    private inner class CommentHeader(
            var vendor: String = "",
            val comments: HashMap<String, String> = HashMap(),
            var length: Int = 0) {

        fun unpack(scratch: ParsableByteArray, listener: MetadataListener) {
            if (verifyVorbisHeader(3, scratch)) {
                this.reset()
                val vendorLength = scratch.readLittleEndianUnsignedInt().toInt()
                var length = 7 + 4
                this.vendor = scratch.readString(vendorLength)
                length += this.vendor.length
                val commentListLen = scratch.readLittleEndianUnsignedInt()
                length += 4

                var len: Int
                var comment: String
                var i = 0
                while (i.toLong() < commentListLen) {
                    len = scratch.readLittleEndianUnsignedInt().toInt()
                    length += 4
                    comment = scratch.readString(len)
                    parse(comment)
                    length += comment.length
                    ++i
                }
                this.length = length
                listener.onMetadataReceived(this.comments["ARTIST"], this.comments["TITLE"])
            }

        }

        private fun parse(comment: String) {
            if (comment.contains("=")) {
                val kv = comment.split("=".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (kv.size == 2) {
                    this.comments.put(kv[0], kv[1])
                } else if (kv.size == 1) {
                    this.comments.put(kv[0], "")
                }
            }
        }

        fun reset() {
            this.vendor = ""
            this.comments.clear()
            this.length = 0
        }
    }

    data class PacketInfoHolder(var size: Int = 0, var segmentCount: Int = 0)

    companion object {
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
    }
}


