package com.monika.dashboard.ui.screens

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.monika.dashboard.data.DebugLog
import com.monika.dashboard.media.MediaSyncCoordinator
import com.monika.dashboard.media.PlaybackStateEnum
import com.monika.dashboard.ui.theme.Border
import com.monika.dashboard.ui.theme.TextMuted
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun StatusScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Tick for refreshing debug log and permission states
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            tick++
        }
    }

    val pm = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager }
    var batteryOptimized by remember {
        mutableStateOf(pm?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimized = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // --- Permission & service checks ---
        Text(text = "权限状态", style = MaterialTheme.typography.titleMedium)

        ServiceStatusRow("电池优化已忽略", batteryOptimized) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                DebugLog.log("设置", "电池优化直接请求失败: ${e.message}")
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (e2: Exception) {
                    DebugLog.log("设置", "电池优化设置页也无法打开: ${e2.message}")
                    Toast.makeText(context, "无法打开电池优化设置", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifPermGranted = remember(tick) {
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            ServiceStatusRow("通知权限", notifPermGranted) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (e: Exception) {
                    DebugLog.log("设置", "无法打开通知设置: ${e.message}")
                    Toast.makeText(context, "无法打开通知设置", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Divider(color = Border, thickness = 1.dp)

        // Media sync status
        Text(text = "媒体同步状态", style = MaterialTheme.typography.titleMedium)

        val mediaSnapshot = remember(tick) { MediaSyncCoordinator.lastSnapshot }
        val hasMedia = mediaSnapshot != null &&
            mediaSnapshot.playbackState != PlaybackStateEnum.STOPPED &&
            mediaSnapshot.playbackState != PlaybackStateEnum.UNKNOWN

        // Notification listener permission
        val notifGranted = remember(tick) {
            val enabledPackages = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return@remember false
            val component = android.content.ComponentName(
                context, com.monika.dashboard.media.MediaNotificationListenerService::class.java
            )
            enabledPackages.contains(component.flattenToString())
        }

        ServiceStatusRow("通知监听权限", notifGranted) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(context, "无法打开通知使用权设置", Toast.LENGTH_SHORT).show()
            }
        }

        // Bluetooth permission
        val bluetoothGranted = remember(tick) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        ServiceStatusRow("蓝牙权限", bluetoothGranted) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(context, "无法打开应用设置", Toast.LENGTH_SHORT).show()
            }
        }

        // Usage stats permission
        val app = context.applicationContext as? com.monika.dashboard.DashboardApp
        val usageGranted = remember(tick) { app?.foregroundAppDetector?.hasUsageStatsPermission() == true }

        ServiceStatusRow("使用情况访问权限", usageGranted) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(context, "无法打开使用情况访问设置", Toast.LENGTH_SHORT).show()
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = if (hasMedia) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (hasMedia) "当前播放" else "未检测到媒体播放",
                    style = MaterialTheme.typography.labelLarge
                )
                if (hasMedia && mediaSnapshot != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val displayApp = mediaSnapshot.appName ?: mediaSnapshot.packageName ?: "未知"
                    val displayTitle = mediaSnapshot.title ?: ""
                    val displayArtist = mediaSnapshot.artist ?: ""
                    if (displayApp.isNotBlank()) {
                        Text(text = "应用: $displayApp", style = MaterialTheme.typography.bodySmall)
                    }
                    if (displayTitle.isNotBlank()) {
                        Text(text = "标题: $displayTitle", style = MaterialTheme.typography.bodySmall)
                    }
                    if (displayArtist.isNotBlank()) {
                        Text(text = "艺术家: $displayArtist", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(text = "状态: ${mediaSnapshot.playbackState}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Divider(color = Border, thickness = 1.dp)

        // Debug log
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "调试日志", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { DebugLog.clear() }) {
                Text("清空", style = MaterialTheme.typography.bodySmall)
            }
        }

        val logLines = remember(tick) { DebugLog.lines }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 300.dp)
                .border(1.dp, Border, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp)
        ) {
            if (logLines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无日志",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            } else {
                val logScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .verticalScroll(logScrollState)
                ) {
                    logLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceStatusRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (ok) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (ok) "✓" else "✗",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ok) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ok) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (!ok) {
                TextButton(onClick = onFix) {
                    Text("去设置")
                }
            }
        }
    }
}

@Composable
private fun ServiceStatusRow(label: String, onAction: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$label（请确认已开启）",
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onAction) {
                Text("去设置")
            }
        }
    }
}
