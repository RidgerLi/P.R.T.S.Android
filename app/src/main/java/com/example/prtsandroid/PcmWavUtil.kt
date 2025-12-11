package com.example.prtsandroid

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PcmWavUtil {

    /**
     * @param pcmData       16kHz, mono, 16bit PCM (little-endian)
     * @param sampleRate    例 16000
     * @param channels      1 = mono
     * @param bitsPerSample 16
     */
    fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcmData.size + 36
        val totalAudioLen = pcmData.size

        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt sub-chunk
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                  // Subchunk1Size for PCM
        header.putShort(1)                 // AudioFormat = 1 (PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())

        // data sub-chunk
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(totalAudioLen)

        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }
}
