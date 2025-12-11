package com.example.prtsandroid

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * 负责：
 * - 管理 AudioRecord
 * - 做简单的能量 VAD
 * - 通过 Listener 把“完整一句话的音频片段”回调出去
 *
 * 使用方式：
 * val vad = VadRecorder(listener = object : VadRecorder.Listener { ... })
 * vad.start()
 * vad.stop()
 */
class VadRecorder(
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val frameIntervalMs: Long = 160L,
    private val voiceThreshold: Double = 900.0,
    private val silenceThresholdMs: Long = 1500,
    private val gainFactor: Double = 1.8, // <-- 新增：音量增益系数，例如 1.5 倍
    private val minUtteranceMs: Long = 600L,
    private val listener: Listener
) {

    interface Listener {
        /** 开始检测到一句话（第一次从静音 -> 有声） */
        fun onUtteranceStarted()

        /**
         * 一句话结束，返回完整音频
         * @param data 16kHz 单声道 16bit PCM little-endian
         * @param durationMs 这句话的大致时长（毫秒）
         */
        fun onUtteranceFinished(data: ByteArray, durationMs: Long)

        /** 发生错误（AudioRecord 初始化失败等） */
        fun onError(message: String, throwable: Throwable? = null)
    }

    companion object {
        private const val TAG = "VadRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var bufferSizeInBytes: Int = 0

    private var vadThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    init {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            audioFormat
        )
        bufferSizeInBytes = (minBuffer * 2).coerceAtLeast(minBuffer)
        Log.d(TAG, "init, bufferSizeInBytes=$bufferSizeInBytes")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Synchronized
    fun start() {
        if (isRunning.get()) {
            Log.w(TAG, "start() called but already running")
            return
        }
        Log.d(TAG, "start() called")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSizeInBytes
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败, state=${record.state}")
            record.release()
            listener.onError("AudioRecord 初始化失败")
            return
        }

        audioRecord = record

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording() 失败", e)
            listener.onError("startRecording() 失败", e)
            record.release()
            audioRecord = null
            return
        }

        isRunning.set(true)

        vadThread = Thread { runVadLoop() }.apply {
            name = "VadRecorderThread"
            start()
        }

        Log.d(TAG, "VAD started")
    }

    @Synchronized
    fun stop() {
        if (!isRunning.get()) return
        Log.d(TAG, "stop() called, stopping VAD loop")
        isRunning.set(false)

        vadThread?.let {
            try {
                it.join(500)
            } catch (_: InterruptedException) {
            }
        }
        vadThread = null

        audioRecord?.let { record ->
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
        }
        audioRecord = null

        Log.d(TAG, "VAD stopped")
    }

    fun isRunning(): Boolean = isRunning.get()

    // ================= VAD 内部逻辑 =================

    private fun runVadLoop() {
        val record = audioRecord ?: return

        val buffer = ShortArray(bufferSizeInBytes / 2) // 16bit -> short

        var isUtteranceActive = false
        var utteranceStartTime = 0L
        var lastVoiceTime = 0L

        val currentUtterance = ByteArrayOutputStream()

        while (isRunning.get()) {
            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord.read 失败", e)
                listener.onError("AudioRecord.read 失败", e)
                break
            }

            if (read <= 0) {
                continue
            }

            val now = System.currentTimeMillis()
            val energy = calcRmsEnergy(buffer, read)
//            Log.v(TAG, "energy=$energy")
            if (energy > voiceThreshold) {
                // 有声音
                if (!isUtteranceActive) {
                    isUtteranceActive = true
                    utteranceStartTime = now
                    currentUtterance.reset()
                    lastVoiceTime = now
                    listener.onUtteranceStarted()
                    Log.d(TAG, "Utterance START, energy=$energy")
                } else {
                    lastVoiceTime = now
                }
                applyGainAndProtectClipping(buffer, read, gainFactor)
                // 写入当前帧
                writePcmToStream(currentUtterance, buffer, read)
            } else {
                // 静音
                if (isUtteranceActive) {
                    val silenceDuration = now - lastVoiceTime
                    val utteranceDuration = now - utteranceStartTime

                    if (silenceDuration > silenceThresholdMs &&
                        utteranceDuration > minUtteranceMs
                    ) {
                        // 结束一段话
                        isUtteranceActive = false
                        val data = currentUtterance.toByteArray()
                        Log.d(
                            TAG,
                            "Utterance END, len=${data.size} bytes, duration=${utteranceDuration}ms"
                        )
                        listener.onUtteranceFinished(data, utteranceDuration)
                        currentUtterance.reset()
                    } else {
                        // 还没到结束阈值，允许写入一点静音
                        writePcmToStream(currentUtterance, buffer, read)
                    }
                }
            }

            try {
                Thread.sleep(frameIntervalMs)
            } catch (_: InterruptedException) {
                break
            }
        }

        Log.d(TAG, "VAD loop finished")
    }

    // ================= 音频处理逻辑 =================

    /**
     * 对 PCM 16bit 短整型数组进行音量增益，并进行削波保护。
     * @param buffer 16-bit PCM short 数组
     * @param len 实际读取的长度
     * @param gainFactor 增益系数 (例如 1.5)
     */
    private fun applyGainAndProtectClipping(
        buffer: ShortArray,
        len: Int,
        gainFactor: Double
    ) {
        val max16Bit = Short.MAX_VALUE.toDouble() // 32767.0
        val min16Bit = Short.MIN_VALUE.toDouble() // -32768.0

        for (i in 0 until len) {
            var v = buffer[i].toDouble()
            v *= gainFactor

            // 削波保护：限制在 16bit 的最大/最小值范围内
            v = when {
                v > max16Bit -> max16Bit
                v < min16Bit -> min16Bit
                else -> v
            }

            buffer[i] = v.toInt().toShort()
        }
    }

    private fun calcRmsEnergy(data: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val v = data[i].toDouble()
            sum += v * v
        }
        return if (len > 0) sqrt(sum / len) else 0.0
    }

    private fun writePcmToStream(out: ByteArrayOutputStream, data: ShortArray, len: Int) {
        for (i in 0 until len) {
            val s = data[i].toInt()
            out.write(s and 0xFF)
            out.write((s shr 8) and 0xFF)
        }
    }
}
