package com.monika.dashboard.ui.screens

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monika.dashboard.data.DebugLog
import com.monika.dashboard.data.SettingsStore
import com.monika.dashboard.heart.HeartRateService
import com.monika.dashboard.ui.theme.Border
import com.monika.dashboard.ui.theme.Secondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HealthScreen(settings: SettingsStore) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var bluetoothGranted by remember { mutableStateOf(false) }

    // Poll Bluetooth permission and heart rate every 1 second
    LaunchedEffect(Unit) {
        while (true) {
            bluetoothGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            delay(1000)
            tick++
        }
    }

    val isConnected = HeartRateService.isConnected
    val connectedDeviceName = HeartRateService.connectedDeviceName
    val discoveredDevices = HeartRateService.discoveredDevices
    val heartRate = HeartRateService.currentHeartRate
    val savedDeviceAddress = remember(tick) {
        try {
            val prefs = context.getSharedPreferences("heart_rate_prefs", android.content.Context.MODE_PRIVATE)
            prefs.getString("last_connected_device_address", null)
        } catch (_: Exception) {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "心率监测",
            style = MaterialTheme.typography.headlineMedium
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Border, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "💓 实时心率",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (heartRate > 0) "$heartRate bpm" else "-- bpm",
                    style = MaterialTheme.typography.displayMedium,
                    color = Secondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isConnected) "已连接: ${connectedDeviceName ?: "未知设备"}" else "未连接蓝牙设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) Secondary else MaterialTheme.colorScheme.error
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Border, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "蓝牙连接",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = when {
                            isConnected -> "已连接"
                            discoveredDevices.isNotEmpty() -> "已发现 ${discoveredDevices.size} 个设备"
                            else -> "未连接"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isConnected) Secondary else MaterialTheme.colorScheme.error
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!bluetoothGranted) {
                                Toast.makeText(context, "请先在状态页开启蓝牙权限", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            val intent = Intent(context, HeartRateService::class.java)
                            intent.action = "START_SCAN"
                            context.startForegroundService(intent)
                            Toast.makeText(context, "开始扫描蓝牙设备", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isConnected
                    ) {
                        Text("扫描")
                    }
                    if (isConnected) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(context, HeartRateService::class.java)
                                intent.action = "DISCONNECT"
                                context.startService(intent)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("断开")
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Border, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "已保存设备", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = savedDeviceAddress ?: "暂无已保存设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (savedDeviceAddress != null && !isConnected) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(context, HeartRateService::class.java)
                                intent.action = "CONNECT"
                                intent.putExtra("device_address", savedDeviceAddress)
                                context.startForegroundService(intent)
                            }
                        ) { Text("重连") }
                    }
                    if (isConnected) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(context, HeartRateService::class.java)
                                intent.action = "DISCONNECT"
                                context.startService(intent)
                            }
                        ) { Text("断开连接") }
                    }
                    if (savedDeviceAddress != null) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(context, HeartRateService::class.java)
                                intent.action = "CLEAR_SAVED_DEVICE"
                                context.startService(intent)
                                Toast.makeText(context, "已清除保存设备", Toast.LENGTH_SHORT).show()
                            }
                        ) { Text("清除保存设备") }
                    }
                }
            }
        }

        if (discoveredDevices.isNotEmpty()) {
            Text(
                text = "扫描发现的设备 (${discoveredDevices.size})",
                style = MaterialTheme.typography.titleMedium
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp)
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                items(discoveredDevices.entries.toList()) { (name, device) ->
                    DeviceItem(name = name, device = device, onConnect = {
                        val intent = Intent(context, HeartRateService::class.java)
                        intent.action = "CONNECT"
                        intent.putExtra("device_address", device.address)
                        context.startForegroundService(intent)
                    })
                }
            }
        }

        // Heart rate report interval setting
        val heartRateIntervalFlow by settings.heartRateReportInterval.collectAsState(initial = 30)
        var heartRateInterval by remember(heartRateIntervalFlow) { mutableIntStateOf(heartRateIntervalFlow) }
        val scope = rememberCoroutineScope()

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Border, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "上报间隔",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${heartRateInterval}秒",
                        style = MaterialTheme.typography.titleMedium,
                        color = Secondary
                    )
                }
                Slider(
                    value = heartRateInterval.toFloat(),
                    onValueChange = { heartRateInterval = it.toInt() },
                    valueRange = 10f..300f,
                    steps = 28,
                    onValueChangeFinished = {
                        scope.launch {
                            settings.setHeartRateReportInterval(heartRateInterval)
                        }
                    }
                )
                Text(
                    text = "心率变化时，间隔${heartRateInterval}秒上报一次到服务器",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(text = "调试日志", style = MaterialTheme.typography.titleMedium)
        val logs = remember(tick) { DebugLog.lines.toList() }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                                .border(1.dp, Border, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun DeviceItem(
    name: String,
    device: BluetoothDevice,
    onConnect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConnect() }
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "📱", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
