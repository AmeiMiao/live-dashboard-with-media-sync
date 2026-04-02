package com.monika.dashboard.ui.screens

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay

@Composable
fun HealthScreen(settings: SettingsStore) {
    val context = LocalContext.current

    var heartRate by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }

    // Poll heart rate from server
    LaunchedEffect(Unit) {
        while (true) {
            val url = try { settings.serverUrl.first() } catch (_: Exception) { "" }
            val token = settings.getToken()
            if (url.isNotEmpty() && !token.isNullOrEmpty()) {
                try {
                    val client = com.monika.dashboard.network.ReportClient(url, token)
                    try {
                        val res = okhttp3.Request.Builder()
                            .url("${url}/api/current")
                            .addHeader("Authorization", "Bearer $token")
                            .get()
                            .build()
                        val response = okhttp3.OkHttpClient().newCall(res).execute()
                        val body = response.body?.string()
                        response.close()
                        if (body != null) {
                            val json = org.json.JSONObject(body)
                            val devices = json.optJSONArray("devices")
                            if (devices != null && devices.length() > 0) {
                                val device = devices.getJSONObject(0)
                                val extra = device.optJSONObject("extra")
                                if (extra != null) {
                                    heartRate = extra.optInt("heart_rate", 0)
                                }
                            }
                        }
                    } finally {
                        client.shutdown()
                    }
                } catch (_: Exception) {}
            }
            delay(3000)
            tick++
        }
    }

    val isConnected = HeartRateService.isConnected
    val connectedDeviceName = HeartRateService.connectedDeviceName
    val discoveredDevices = HeartRateService.discoveredDevices

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "心率监测",
            style = MaterialTheme.typography.headlineMedium
        )

        // Heart rate card
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

        // Bluetooth connection status
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
                        text = if (isConnected) "已连接" else if (discoveredDevices.isNotEmpty()) "已发现 ${discoveredDevices.size} 个设备" else "未连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isConnected) Secondary else MaterialTheme.colorScheme.error
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(context, HeartRateService::class.java)
                            intent.action = "START_SCAN"
                            context.startService(intent)
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
                                intent.action = "STOP_SCAN"
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

        // Discovered devices list
        if (discoveredDevices.isNotEmpty()) {
            Text(
                text = "发现的设备 (${discoveredDevices.size})",
                style = MaterialTheme.typography.titleMedium
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                items(discoveredDevices.entries.toList()) { (name, device) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val intent = Intent(context, HeartRateService::class.java)
                                intent.action = "CONNECT"
                                intent.putExtra("device_address", device.address)
                                context.startService(intent)
                            }
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📱",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Debug log
        Text(
            text = "调试日志",
            style = MaterialTheme.typography.titleMedium
        )

        val logs = remember(tick) { DebugLog.lines.toList() }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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
