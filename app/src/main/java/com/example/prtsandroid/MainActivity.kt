package com.example.prtsandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.prtsandroid.ui.theme.PRTSAndroidTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "prts_prefs"
        private const val KEY_USER_ID = "user_id"
    }

    private val recordAudioPermission = Manifest.permission.RECORD_AUDIO

    private val requestRecordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 这里你可以根据 granted 做 UI 提示
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PRTSAndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // SharedPreferences
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

                    // 读本地已保存的 user_id（第一次可能是空字符串）
                    var userId by remember {
                        mutableStateOf(prefs.getString(KEY_USER_ID, "") ?: "")
                    }

                    MainScreen(
                        userId = userId,
                        onUserIdChange = { newId ->
                            userId = newId
                        },
                        onStartServiceClick = {
                            // 启动前先把 userId 保存一下
                            if (userId.isNotBlank()) {
                                prefs.edit().putString(KEY_USER_ID, userId.trim()).apply()
                                ensurePermissionsAndStartService()
                            } else {
                                // 简单提醒一下
                                android.widget.Toast
                                    .makeText(this, "先填一个 user_id 再启动吧～", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                        onStopServiceClick = {
                            stopVoiceService()
                        },
                        onOpenOverlaySettingsClick = {
                            openOverlaySettings()
                        }
                    )
                }
            }
        }
    }


    private fun ensurePermissionsAndStartService() {
        // 1. 录音权限
        if (ContextCompat.checkSelfPermission(this, recordAudioPermission)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestRecordAudioPermission.launch(recordAudioPermission)
            return
        }

        // 2. 悬浮窗权限（用于悬浮球）
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }

        // 3. 启动前台 Service
        startVoiceService()
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceForegroundService::class.java).apply {
            action = VoiceForegroundService.ACTION_START
        }
        // Android 8+ 要用 startForegroundService
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVoiceService() {
        val intent = Intent(this, VoiceForegroundService::class.java).apply {
            action = VoiceForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}

@Composable
fun MainScreen(
    userId: String,
    onUserIdChange: (String) -> Unit,
    onStartServiceClick: () -> Unit,
    onStopServiceClick: () -> Unit,
    onOpenOverlaySettingsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("陪伴监督 AI · 测试面板")

        Spacer(Modifier.height(16.dp))
        // 👉 用户 ID 输入框
        androidx.compose.material3.OutlinedTextField(
            value = userId,
            onValueChange = onUserIdChange,
            label = { Text("用户 ID") },
            placeholder = { Text("给自己取个 id，比如 1001") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Button(onClick = onStartServiceClick) {
            Text("启动前台语音服务（悬浮球）")
        }

        Spacer(Modifier.height(8.dp))

        Button(onClick = onStopServiceClick) {
            Text("停止前台语音服务")
        }

        Spacer(Modifier.height(16.dp))

        Button(onClick = onOpenOverlaySettingsClick) {
            Text("打开悬浮窗权限设置")
        }
    }
}
