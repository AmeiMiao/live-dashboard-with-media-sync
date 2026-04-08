package com.monika.dashboard.heart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.monika.dashboard.R
import com.monika.dashboard.data.DebugLog
import com.monika.dashboard.data.SettingsStore
import com.monika.dashboard.network.ReportClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

class HeartRateService : Service() {
    companion object {
        private const val TAG = "HeartRateService"
        private const val CHANNEL_ID = "heart_rate_channel"
        private const val NOTIFICATION_ID = 1002
        private const val PREFS_NAME = "heart_rate_prefs"
        private const val KEY_DEVICE_ADDRESS = "last_connected_device_address"
        
        @Volatile var isServiceRunning = false
            private set
        @Volatile var discoveredDevices: Map<String, BluetoothDevice> = emptyMap()
            private set
        @Volatile var isConnected = false
            private set
        @Volatile var connectedDeviceName: String? = null
            private set
        @Volatile var currentHeartRate: Int = 0
            private set
        @Volatile var lastHeartRateReportTime: Long = 0L
            private set
    }

    private lateinit var settings: SettingsStore
    private lateinit var heartRateManager: BluetoothHeartRateManager
    private val executor = Executors.newSingleThreadExecutor()
    private var lastReportTime: Long = 0
    private var lastHeartRate: Int = 0

    private fun getPrefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun saveDeviceAddress(address: String?) {
        getPrefs().edit { putString(KEY_DEVICE_ADDRESS, address) }
    }

    private fun getSavedDeviceAddress(): String? {
        return getPrefs().getString(KEY_DEVICE_ADDRESS, null)
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        settings = SettingsStore(applicationContext)
        heartRateManager = BluetoothHeartRateManager(applicationContext, object : BluetoothHeartRateManager.HeartRateCallback {
            override fun onHeartRateReceived(heartRate: Int) {
                handleHeartRate(heartRate)
            }

            override fun onConnectionStateChanged(connected: Boolean) {
                isConnected = connected
                if (connected) {
                    connectedDeviceName = heartRateManager.getConnectedDeviceName()
                    heartRateManager.getConnectedDeviceAddress()?.let { saveDeviceAddress(it) }
                } else {
                    connectedDeviceName = null
                    currentHeartRate = 0
                }
                DebugLog.log("心率", if (connected) "已连接" else "已断开")
            }

            override fun onScanResult(device: BluetoothDevice) {
                // Real-time scan result - no action needed
            }

            override fun onScanComplete(devices: Map<String, BluetoothDevice>) {
                discoveredDevices = devices
                DebugLog.log("心率", "扫描完成，找到 ${devices.size} 个设备")
                
            }

            override fun onError(message: String) {
                DebugLog.log("心率", "错误: $message")
                Log.e(TAG, "Error: $message")
            }
        })

        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, createNotification(false))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }

        reconnectSavedDevice()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_SCAN" -> startScan()
            "STOP_SCAN" -> stopScan()
            "DISCONNECT" -> disconnectCurrentDevice()
            "CLEAR_SAVED_DEVICE" -> clearSavedDevice()
            "RECONNECT_SAVED_DEVICE" -> reconnectSavedDevice()
            "CONNECT" -> {
                val address = intent.getStringExtra("device_address")
                if (address != null) {
                    val device = discoveredDevices.values.firstOrNull { it.address == address }
                        ?: heartRateManager.getRemoteDevice(address)
                    device?.let {
                        connectedDeviceName = try { it.name ?: it.address } catch (_: SecurityException) { it.address }
                        connectToDevice(it)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        isConnected = false
        discoveredDevices = emptyMap()
        heartRateManager.disconnect()
        super.onDestroy()
    }

    fun startScan() {
        // Check Bluetooth permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                DebugLog.log("心率", "蓝牙扫描权限未授予")
                return
            }
        }
        
        if (!heartRateManager.isBluetoothEnabled()) {
            DebugLog.log("心率", "蓝牙未开启")
            return
        }
        
        try {
            discoveredDevices = emptyMap()
            heartRateManager.startScan()
            DebugLog.log("心率", "开始扫描蓝牙设备")
        } catch (e: Exception) {
            DebugLog.log("心率", "扫描失败: ${e.message}")
        }
    }

    fun stopScan() {
        heartRateManager.stopScan()
    }

    fun reconnectSavedDevice() {
        val savedAddress = getSavedDeviceAddress()
        if (savedAddress != null && !isConnected && heartRateManager.isBluetoothEnabled()) {
            DebugLog.log("心率", "尝试直连已保存设备: $savedAddress")
            try {
                val device = heartRateManager.getRemoteDevice(savedAddress)
                if (device != null) {
                    connectToDevice(device)
                } else {
                    DebugLog.log("心率", "未找到已保存设备")
                }
            } catch (e: Exception) {
                DebugLog.log("心率", "直连已保存设备失败: ${e.message}")
            }
        }
    }

    fun disconnectCurrentDevice() {
        heartRateManager.disconnect()
        isConnected = false
        connectedDeviceName = null
        currentHeartRate = 0
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, createNotification(false))
        } catch (_: Exception) {}
        DebugLog.log("心率", "手动断开当前设备")
    }

    fun clearSavedDevice() {
        saveDeviceAddress(null)
        DebugLog.log("心率", "已清除保存设备")
    }

    fun connectToDevice(device: BluetoothDevice) {
        heartRateManager.connectToDevice(device)
        val deviceName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
        DebugLog.log("心率", "正在连接: $deviceName")
    }

    private fun handleHeartRate(heartRate: Int) {
        if (heartRate !in 1..250) return

        currentHeartRate = heartRate
        val now = System.currentTimeMillis()
        val interval = try { runBlocking { settings.heartRateReportInterval.first() } * 1000L } catch (_: Exception) { 30000L }
        if (now - lastReportTime < interval) {
            return
        }

        lastReportTime = now
        lastHeartRate = heartRate
        lastHeartRateReportTime = now
        executor.execute { reportHeartRate(heartRate) }
    }

    private fun reportHeartRate(heartRate: Int) {
        val url = try { runBlocking { settings.serverUrl.first() } } catch (_: Exception) { "" }
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

    private fun createNotification(connected: Boolean, heartRate: Int? = null): Notification {
        val contentText = when {
            connected && heartRate != null -> "心率: $heartRate bpm"
            connected -> "已连接，等待心率数据..."
            getSavedDeviceAddress() != null -> "后台保活中，等待自动重连..."
            else -> "未连接蓝牙设备"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("心率监测")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
