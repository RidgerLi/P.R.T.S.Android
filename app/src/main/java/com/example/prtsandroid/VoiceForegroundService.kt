package com.example.prtsandroid

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext


class VoiceForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "voice_service_channel"
        const val TAG = "voice_service"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.prtsandroid.action.START"
        const val ACTION_STOP = "com.example.prtsandroid.action.STOP"
        const val ACTION_TOGGLE_LISTENING = "com.example.prtsandroid.action.TOGGLE_LISTENING"

        private const val PREFS_NAME = "prts_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val DEFAULT_BASE_URL = "http://124.222.58.189:8000/"
        private const val VOICE_CHAT_PATH = "ai/audio_chat"  // 不要带前导 /


        // 音频参数，要和 VadRecorder 保持一致
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val KEY_USER_ID = "user_id"
    }
    private enum class AssistantState {
        IDLE,           // 停止监听
        LISTENING,      // 正在监听（VAD 录音）
        THINKING,       // AI 思考 / 播放 TTS
        ERROR           // 出错（HTTP / 网络 / 服务器等）
    }

    private var currentState: AssistantState = AssistantState.IDLE
        set(value) {
            field = value
            updateUiForState(value)
        }



    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isListening = false

    private var vadRecorder: VadRecorder? = null

    private var idleTimeoutJob: Job? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(120, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                showFloatingBubble()
            }
            ACTION_STOP -> {
                hideFloatingBubble()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TOGGLE_LISTENING -> {
                toggleListening()
            }
        }
        return START_STICKY
    }

    // ---------- 前台通知 ----------

    private fun startAsForeground() {
        val notification = buildNotification("语音助手已启动，点击悬浮球开始对话")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("陪伴监督 AI")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_mic) // 用你自己的图标
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音前台服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ---------- 悬浮球 ----------

    private fun showFloatingBubble() {
        if (floatingView != null) return

        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.view_floating_bubble, null)

        val layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT

            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.END
            x = 50
            y = 200
        }

        // 拖动 + 点击
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        floatingView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager?.updateViewLayout(v, layoutParams)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 认为是点击
                        v.performClick()
                        toggleListening()
                    }
                    true
                }

                else -> false
            }
        }

        windowManager?.addView(floatingView, layoutParams)
        currentState = AssistantState.IDLE
    }

    private fun hideFloatingBubble() {
        floatingView?.let { view ->
            windowManager?.removeView(view)
        }
        floatingView = null
    }
    private fun pauseListeningWithoutChangeFlag() {
        vadRecorder?.stop()
        vadRecorder = null
    }

    private fun toggleListening() {
        isListening = !isListening
        Log.d(TAG, "toggleListening: isListening=$isListening")

        if (isListening) {
            startListeningInternal()
            currentState = AssistantState.LISTENING
        } else {
            stopListeningInternal()
            currentState = AssistantState.IDLE
        }
    }

    private fun startListeningInternal() {
        if (vadRecorder?.isRunning() == true) return
        cancelIdleTimeoutTimer()

        updateNotification("正在聆听中…")

        vadRecorder = VadRecorder(
            sampleRate = SAMPLE_RATE,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            listener = object : VadRecorder.Listener {
                override fun onUtteranceStarted() {
                    // 一句话开始时，你可以做一些 UI/日志
                    android.util.Log.d("VoiceService", "Utterance started")
                    cancelIdleTimeoutTimer()
                }

                override fun onUtteranceFinished(data: ByteArray, durationMs: Long) {
                    Log.d(
                        "VoiceService",
                        "Utterance finished: ${data.size} bytes, ${durationMs}ms"
                    )

                    // 一句话结束 → 暂停录音 & 进入“思考”状态
                    serviceScope.launch {
                        try {
                            withContext(Dispatchers.Main) {
                                currentState = AssistantState.THINKING
                                pauseListeningWithoutChangeFlag()  // 停掉 VAD，防止录到 AI 声音
                            }
                            handleUtterance(data)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            withContext(Dispatchers.Main) {
                                // 出错时，如果用户本来是开启状态，回到 LISTENING；否则 IDLE
                                currentState = if (isListening) AssistantState.LISTENING else AssistantState.IDLE
                                if (isListening) {
                                    startListeningInternal()
                                }
                            }
                        }
                    }
                }


                override fun onError(message: String, throwable: Throwable?) {
                    android.util.Log.e("VoiceService", "Vad error: $message", throwable)
                    updateNotification("录音出错：$message")
                }
            }
        )

        try {
            vadRecorder?.start()
        } catch (se: SecurityException) {
            android.util.Log.e("VoiceService", "start VadRecorder SecurityException", se)
            updateNotification("没有录音权限")
        }
    }

    private fun stopListeningInternal() {
        updateNotification("语音助手已暂停，点击悬浮球再次开始")
        vadRecorder?.stop()
        vadRecorder = null

        cancelIdleTimeoutTimer()
        currentState = AssistantState.IDLE
    }

    private fun stopListeningWithError(message: String, t: Throwable? = null) {
        Log.e(TAG, "stopListeningWithError(): $message", t)

        // 关闭 VAD / 录音
        vadRecorder?.stop()
        vadRecorder = null
        isListening = false
        cancelIdleTimeoutTimer()

        // 切换到错误状态（图标变红）
        currentState = AssistantState.ERROR
    }

    // ================== 一句语音的处理：PCM -> WAV -> 后端 -> MP3 播放 ==================

    private suspend fun handleUtterance(pcmData: ByteArray) {
        Log.d(TAG, "sendUtteranceToBackend(): 准备发送音频, size=${pcmData.size} bytes")

        val wavBytes = PcmWavUtil.pcmToWav(
            pcmData = pcmData,
            sampleRate = SAMPLE_RATE,
            channels = CHANNELS,
            bitsPerSample = BITS_PER_SAMPLE
        )
        Log.d(TAG, "sendUtteranceToBackend(): wav size=${wavBytes.size} bytes")

        val mp3Bytes = sendWavToBackend(wavBytes) ?: return

        // 这里只负责播放，播放完会在回调里自动恢复监听
        withContext(Dispatchers.Main) {
            playReplyAudio(mp3Bytes)
        }
    }


    private suspend fun sendWavToBackend(wavBytes: ByteArray): ByteArray? {
        return try {
            val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val userId = sp.getString(KEY_USER_ID, "1") ?: "1"
            val userIdInt = userId.toIntOrNull() ?: 1
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    "audio.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("user_id", userIdInt.toString())
                .build()

            val baseUrl = (sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL).trim()

            val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val fullUrl = normalizedBaseUrl + VOICE_CHAT_PATH
            val request = Request.Builder()
                .url(fullUrl)
                .post(body)
                .build()

            val resp = httpClient.newCall(request).execute()

            resp.use { r ->
                if (!r.isSuccessful) {
                    Log.e("VoiceService", "HTTP error ${r.code}, body=${r.body?.string()}")
                    // ★ HTTP 失败时停录音 + 提示
                    stopListeningWithError("服务器返回错误（HTTP ${r.code}）")
                    return null
                }

                val bytes = r.body?.bytes()
                if (bytes == null) {
                    Log.e("VoiceService", "响应体为空")
                    stopListeningWithError("服务器返回空音频")
                    return null
                }

                Log.d("VoiceService", "收到 MP3, size=${bytes.size} bytes")
                bytes
            }
        } catch (e: Exception) {
            // ★ 这里就能抓到 UnknownServiceException / 超时 等网络异常
            Log.e("VoiceService", "请求异常", e)
            stopListeningWithError("网络或服务器异常")
            null
        }
    }
    private fun playReplyAudio(mp3Bytes: ByteArray) {
        try {
            val tempFile = File(cacheDir, "reply_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tempFile).use { fos ->
                fos.write(mp3Bytes)
                fos.flush()
            }

            val mp = MediaPlayer()
            mp.setDataSource(tempFile.absolutePath)

            mp.setOnCompletionListener {
                it.release()
                tempFile.delete()

                // 播放结束 → 如果用户仍然“开启助手”，自动恢复监听
                if (isListening) {
                    startListeningInternal()
                    currentState = AssistantState.LISTENING
                    startIdleTimeoutTimer()
                } else {
                    currentState = AssistantState.IDLE
                }
            }

            mp.setOnErrorListener { player, _, _ ->
                player.release()
                tempFile.delete()

                if (isListening) {
                    startListeningInternal()
                    currentState = AssistantState.LISTENING
                } else {
                    currentState = AssistantState.IDLE
                }
                true
            }

            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            e.printStackTrace()
            if (isListening) {
                startListeningInternal()
                currentState = AssistantState.LISTENING
            } else {
                currentState = AssistantState.IDLE
            }
        }
    }

    private fun updateUiForState(state: AssistantState) {
        val bubble = floatingView?.findViewById<View>(R.id.bubbleIcon) ?: return

        val color = when (state) {
            AssistantState.IDLE      -> 0xFFAAAAAA.toInt()   // 灰：停止监听
            AssistantState.LISTENING -> 0xFF4CAF50.toInt()   // 绿：正在监听
            AssistantState.THINKING  -> 0xFF2196F3.toInt()   // 蓝：AI 思考 / 播放
            AssistantState.ERROR     -> 0xFFF44336.toInt()   // 🔴 红：错误
        }

        bubble.setBackgroundColor(color)

        val text = when (state) {
            AssistantState.IDLE      -> "语音助手已暂停，点击悬浮球再次开始"
            AssistantState.LISTENING -> "正在聆听中…"
            AssistantState.THINKING  -> "AI 正在思考 / 播放回复…"
            AssistantState.ERROR     -> "出错了，点击悬浮球重新开始"
        }
        updateNotification(text)
    }



    /**
     * 调用 FastAPI 后端，返回 AI 的文本回复
     */
    private fun callBackend(userText: String): String {
        val json = JSONObject().apply {
            put("session_id", "android_demo")
            put("message", userText)
        }
        val body = json.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("http://198.18.0.1/ai/chat")   // 访问局域网
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Unexpected code $resp")
            val respBody = resp.body?.string().orEmpty()
            val obj = JSONObject(respBody)
            // 假设你的后端返回 {"reply": "..."}，按实际情况改
            return obj.getString("reply")
        }
    }

    // 在 AI 播放完、重新开始监听后调用：
    // 如果 200s 内用户没再说话，就自动关闭监听
    private fun startIdleTimeoutTimer(timeoutMs: Long = 30_000L) {
        idleTimeoutJob?.cancel()

        idleTimeoutJob = serviceScope.launch {
            delay(timeoutMs)

            // 到时间再检查一下当前确实还在“监听状态”
            if (isListening && vadRecorder != null /* 说明麦克风还开着 */) {
                Log.w(TAG, "Idle timeout: no new utterance in $timeoutMs ms, auto stop listening")

                withContext(Dispatchers.Main) {
                    stopListeningInternal()   // 你已经有这个函数，会停掉 VAD 并更新通知
                    isListening = false
                    // 如果用了状态机的话：
                     currentState = AssistantState.IDLE
                }
            }
        }
    }

    private fun cancelIdleTimeoutTimer() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
    }

    // ---------- 生命周期收尾 ----------

    override fun onDestroy() {
        hideFloatingBubble()
        serviceScope.cancel()
        cancelIdleTimeoutTimer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
