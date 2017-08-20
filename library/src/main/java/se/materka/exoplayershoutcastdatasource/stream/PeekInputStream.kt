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

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.*

internal open class PeekInputStream private constructor(private val stream: InputStream, position: Long) : InputStream() {
    var position: Long = 0
        private set
    private var peekBuffer = ByteArray(8192)
    private var peekBufferPosition: Int = 0
    private var peekBufferLength: Int = 0

    constructor(stream: InputStream) : this(stream, 0)

    init {
        this.position = position
    }

    @Throws(IOException::class)
    override fun read(): Int {
        var bytesRead = this.readFromPeekBuffer()
        if (bytesRead == 0) {
            try {
                bytesRead = this.readFromStream()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }

        }
        this.commitBytesRead(bytesRead)
        return bytesRead
    }

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        var bytesRead = this.readFromPeekBuffer(target, offset, length)
        if (bytesRead == 0) {
            try {
                bytesRead = this.readFromStream(target, offset, length, 0, true)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }

        this.commitBytesRead(bytesRead)
        return bytesRead
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        var bytesRead: Int
        bytesRead = this.readFromPeekBuffer(target, offset, length)
        while (bytesRead < length && bytesRead != -1) {
            bytesRead = this.readFromStream(target, offset, length, bytesRead, allowEndOfInput)
        }

        this.commitBytesRead(bytesRead)
        return bytesRead != -1
    }

    @Throws(IOException::class, InterruptedException::class)
    fun readFully(target: ByteArray, offset: Int, length: Int) {
        this.readFully(target, offset, length, false)
    }

    @Throws(IOException::class, InterruptedException::class)
    fun skip(length: Int): Int {
        var bytesSkipped = this.skipFromPeekBuffer(length)
        if (bytesSkipped == 0) {
            bytesSkipped = this.readFromStream(SCRATCH_SPACE, 0, Math.min(length, SCRATCH_SPACE.size), 0, true)
        }

        this.commitBytesRead(bytesSkipped)
        return bytesSkipped
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        var bytesSkipped: Int
        bytesSkipped = this.skipFromPeekBuffer(length)
        while (bytesSkipped < length && bytesSkipped != -1) {
            bytesSkipped = this.readFromStream(SCRATCH_SPACE, -bytesSkipped, Math.min(length, bytesSkipped + SCRATCH_SPACE.size), bytesSkipped, allowEndOfInput)
        }

        this.commitBytesRead(bytesSkipped)
        return bytesSkipped != -1
    }

    @Throws(IOException::class, InterruptedException::class)
    fun skipFully(length: Int) {
        this.skipFully(length, false)
    }

    @Throws(IOException::class, InterruptedException::class)
    fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        if (!this.advancePeekPosition(length, allowEndOfInput)) {
            return false
        } else {
            System.arraycopy(this.peekBuffer, this.peekBufferPosition - length, target, offset, length)
            return true
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun peekFully(target: ByteArray, offset: Int, length: Int) {
        this.peekFully(target, offset, length, false)
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        this.ensureSpaceForPeek(length)
        var bytesPeeked = Math.min(this.peekBufferLength - this.peekBufferPosition, length)
        this.peekBufferLength += length - bytesPeeked

        do {
            if (bytesPeeked >= length) {
                this.peekBufferPosition += length
                return true
            }

            bytesPeeked = this.readFromStream(this.peekBuffer, this.peekBufferPosition, length, bytesPeeked, allowEndOfInput)
        } while (bytesPeeked != -1)

        return false
    }

    @Throws(IOException::class, InterruptedException::class)
    fun advancePeekPosition(length: Int) {
        this.advancePeekPosition(length, false)
    }

    fun resetPeekPosition() {
        this.peekBufferPosition = 0
    }

    val peekPosition: Long
        get() = this.position + this.peekBufferPosition.toLong()

    private fun ensureSpaceForPeek(length: Int) {
        val requiredLength = this.peekBufferPosition + length
        if (requiredLength > this.peekBuffer.size) {
            this.peekBuffer = Arrays.copyOf(this.peekBuffer, Math.max(this.peekBuffer.size * 2, requiredLength))
        }

    }

    private fun skipFromPeekBuffer(length: Int): Int {
        val bytesSkipped = Math.min(this.peekBufferLength, length)
        this.updatePeekBuffer(bytesSkipped)
        return bytesSkipped
    }

    private fun readFromPeekBuffer(): Int {
        if (this.peekBufferLength == 0) {
            return 0
        } else {
            val bytesRead = this.peekBuffer[0].toInt()
            this.updatePeekBuffer(1)
            return bytesRead
        }
    }

    private fun readFromPeekBuffer(target: ByteArray, offset: Int, length: Int): Int {
        if (this.peekBufferLength == 0) {
            return 0
        } else {
            val peekBytes = Math.min(this.peekBufferLength, length)
            System.arraycopy(this.peekBuffer, 0, target, offset, peekBytes)
            this.updatePeekBuffer(peekBytes)
            return peekBytes
        }
    }

    private fun updatePeekBuffer(bytesConsumed: Int) {
        this.peekBufferLength -= bytesConsumed
        this.peekBufferPosition = 0
        System.arraycopy(this.peekBuffer, bytesConsumed, this.peekBuffer, 0, this.peekBufferLength)
    }

    @Throws(InterruptedException::class, IOException::class)
    private fun readFromStream(): Int {
        return if (Thread.interrupted()) {
            throw InterruptedException()
        } else {
            this.stream.read()
        }
    }

    @Throws(InterruptedException::class, IOException::class)
    private fun readFromStream(target: ByteArray, offset: Int, length: Int, bytesAlreadyRead: Int, allowEndOfInput: Boolean): Int {
        if (Thread.interrupted()) {
            throw InterruptedException()
        } else {
            val bytesRead = this.stream.read(target, offset + bytesAlreadyRead, length - bytesAlreadyRead)
            return if (bytesRead == -1) {
                if (bytesAlreadyRead == 0 && allowEndOfInput) {
                    -1
                } else {
                    throw EOFException()
                }
            } else {
                bytesAlreadyRead + bytesRead
            }
        }
    }

    private fun commitBytesRead(bytesRead: Int) {
        if (bytesRead != -1) {
            this.position += bytesRead.toLong()
        }

    }

    companion object {
        private val SCRATCH_SPACE = ByteArray(4096)
    }
}

