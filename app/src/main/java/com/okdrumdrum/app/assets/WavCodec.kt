package com.okdrumdrum.app.assets

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM-16 mono WAV read/write — the §2 asset/record format. */
object WavCodec {

    fun read(bytes: ByteArray, expectedRate: Int): ShortArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        require(
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) { "not RIFF" }

        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataSize = 0
        var pos = 12 // RIFF(4) + size(4) + WAVE(4)

        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            when (id) {
                "fmt " -> {
                    format = bb.getShort(pos + 8).toInt()
                    channels = bb.getShort(pos + 10).toInt()
                    rate = bb.getInt(pos + 12)
                    bits = bb.getShort(pos + 22).toInt()
                }
                "data" -> {
                    dataStart = pos + 8
                    dataSize = minOf(size, bytes.size - dataStart)
                }
            }
            pos += 8 + size + (size and 1) // chunks are word-aligned
        }

        require(format == 1) { "not PCM ($format)" }
        require(channels == 1) { "not mono ($channels)" }
        require(bits == 16) { "not 16-bit ($bits)" }
        require(rate == expectedRate) { "rate $rate != expected $expectedRate" }
        require(dataStart >= 0) { "no data chunk" }

        bb.position(dataStart)
        val shorts = ShortArray(dataSize / 2)
        bb.asShortBuffer().get(shorts)
        return shorts
    }

    fun write(file: File, rate: Int, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataLen)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)             // PCM
        header.putShort(1)             // mono
        header.putInt(rate)
        header.putInt(rate * 2)        // byte rate
        header.putShort(2)             // block align
        header.putShort(16)            // bits
        header.put("data".toByteArray())
        header.putInt(dataLen)

        file.outputStream().use { out ->
            out.write(header.array())
            val data = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) data.putShort(s)
            out.write(data.array())
        }
    }
}
