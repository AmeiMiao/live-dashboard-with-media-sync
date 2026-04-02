package com.monika.dashboard.heart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.monika.dashboard.R
import com.monika.dashboard.data.DebugLog
import com.monika.dashboard.data.SettingsStore
import com.monika.dashboard.network.ReportClient
import java.util.concurrent.Executors

class HeartRateService : Service() {
    companion object {
        private const val TAG = "HeartRateService"
        private const val CHANNEL_ID = "heart_rate_channel"
        private const val NOTIFICATION_ID = 1002
        private const val MIN_REPORT_INTERVAL_MS = 30000L // 30 seconds
    }

    inner class LocalBinder : Binder() {
        fun getService(): HeartRateService = this@HeartRateService
    }

    private val binder = LocalBinder()
    private lateinit var settings: SettingsStore
    private lateinit var heartRateManager: BluetoothHeartRateManager
    private val executor = Executors.newSingleThreadExecutor()
    private var lastReportTime: Long = 0
    private var lastHeartRate: Int = 0

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(applicationContext)
        heartRateManager = BluetoothHeartRateManager(applicationContext, object : BluetoothHeartRateManager.HeartRateCallback {
            override fun onHeartRateReceived(heartRate: Int) {
                handleHeartRate(heartRate)
            }

            override fun onConnectionStateChanged(connected: Boolean) {
                updateNotification(connected)
                DebugLog.log("心率", if (connected) "已连接" else "已断开")
            }

            override fun onScanResult(device: BluetoothDevice) {
                DebugLog.log("心率", "发现设备: ${device.name ?: device.address}")
            }
        })

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_SCAN" -> startScan()
            "STOP_SCAN" -> stopScan()
            "CONNECT" -> {
                val address = intent.getStringExtra("device_address")
                if (address != null) {
                    val device = try {
                        heartRateManager.bluetoothAdapter?.getRemoteDevice(address)
                    } catch (e: Exception) {
                        null
                    }
                    device?.let { heartRateManager.connectToDevice(it) }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        heartRateManager.disconnect()
        super.onDestroy()
    }

    fun startScan() {
        heartRateManager.startScan()
        DebugLog.log("心率", "开始扫描蓝牙设备")
    }

    fun stopScan() {
        heartRateManager.stopScan()
    }

    fun isConnected(): Boolean = heartRateManager.isConnected()

    fun getConnectedDeviceName(): String? = heartRateManager.getConnectedDeviceName()

    private fun handleHeartRate(heartRate: Int) {
        if (heartRate <= 0 || heartRate > 250) return

        val now = System.currentTimeMillis()
        if (now - lastReportTime < MIN_REPORT_INTERVAL_MS && heartRate == lastHeartRate) {
            return // Skip if same heart rate within interval
        }

        lastReportTime = now
        lastHeartRate = heartRate

        // Update notification with current heart rate
        updateNotification(true, heartRate)

        // Report to server
        executor.execute {
            reportHeartRate(heartRate)
        }
    }

    private fun reportHeartRate(heartRate: Int) {
        val url = try { settings.serverUrl.first() } catch (_: Exception) { "" }
        val token = settings.getToken()
        if (url.isEmpty() || token.isNullOrEmpty()) return

        var client: ReportClient? = null
        try {
            client = ReportClient(url, token)
            val result = client.reportApp(
                appId = "android",
                windowTitle = "",
                heartRate = heartRate
            )

            if (result.isSuccess) {
                DebugLog.log("心率", "上报成功: $heartRate bpm")
                Log.i(TAG, "Reported heart rate: $heartRate")
            } else {
                DebugLog.log("心率", "上报失败: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            DebugLog.log("心率", "异常: ${e.message}")
            Log.e(TAG, "Failed to report heart rate", e)
        } finally {
            runCatching { client?.shutdown() }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "心率监测",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live Dashboard 心率监测服务"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(connected: Boolean, heartRate: Int? = null): Notification {
        val contentText = when {
            connected && heartRate != null -> "心率: $heartRate bpm"
            connected -> "已连接，等待心率数据..."
            else -> "正在扫描蓝牙设备..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("心率监测")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(connected: Boolean, heartRate: Int? = null) {
        val notification = createNotification(connected, heartRate)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
