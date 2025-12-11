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
                    MainScreen(
                        onStartServiceClick = {
                            ensurePermissionsAndStartService()
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
