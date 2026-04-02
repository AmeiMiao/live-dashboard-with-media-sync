package com.monika.dashboard.heart

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

class BluetoothHeartRateManager(
    private val context: Context,
    private val callback: HeartRateCallback
) {
    companion object {
        private const val TAG = "BleHeartRate"
        private const val SCAN_PERIOD = 10000L // 10 seconds scan timeout
        
        // Standard BLE Heart Rate Service UUIDs
        val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    interface HeartRateCallback {
        fun onHeartRateReceived(heartRate: Int)
        fun onConnectionStateChanged(connected: Boolean)
        fun onScanResult(device: BluetoothDevice)
        fun onError(message: String)
    }

    private val bluetoothManager: BluetoothManager? by lazy {
        try {
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get BluetoothManager", e)
            null
        }
    }
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }
    
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var connectedDevice: BluetoothDevice? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val device = result.device
                val name = device.name ?: "Unknown"
                Log.i(TAG, "Found device: $name (${device.address})")
                
                // Check if this device has heart rate service
                if (result.scanRecord?.serviceUuids?.any { it.uuid == HEART_RATE_SERVICE_UUID } == true) {
                    Log.i(TAG, "Device has heart rate service, connecting...")
                    stopScan()
                    connectToDevice(device)
                }
                
                callback.onScanResult(device)
            } catch (e: Exception) {
                Log.e(TAG, "Error in scan result", e)
                callback.onError("扫描结果处理失败: ${e.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            isScanning = false
            val errorMsg = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已在运行"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "应用注册失败"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "设备不支持BLE扫描"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "内部错误"
                else -> "扫描失败: $errorCode"
            }
            callback.onError(errorMsg)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server")
                    connectedDevice = gatt.device
                    callback.onConnectionStateChanged(true)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server")
                    connectedDevice = null
                    callback.onConnectionStateChanged(false)
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered")
                val heartRateService = gatt.getService(HEART_RATE_SERVICE_UUID)
                if (heartRateService != null) {
                    val heartRateCharacteristic = heartRateService.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
                    if (heartRateCharacteristic != null) {
                        enableHeartRateNotifications(gatt, heartRateCharacteristic)
                    } else {
                        Log.e(TAG, "Heart rate measurement characteristic not found")
                        callback.onError("心率测量特征未找到")
                    }
                } else {
                    Log.e(TAG, "Heart rate service not found")
                    callback.onError("心率服务未找到")
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
                callback.onError("服务发现失败: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                val heartRate = parseHeartRate(value)
                Log.i(TAG, "Heart rate: $heartRate")
                callback.onHeartRateReceived(heartRate)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Descriptor write successful")
            } else {
                Log.e(TAG, "Descriptor write failed with status: $status")
            }
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun startScan() {
        if (isScanning) {
            Log.w(TAG, "Scan already in progress")
            return
        }

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter is null")
            callback.onError("蓝牙适配器不可用")
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            callback.onError("蓝牙未开启")
            return
        }

        try {
            bluetoothLeScanner = bluetoothAdapter!!.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "BLE scanner is null")
                callback.onError("BLE扫描器不可用")
                return
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            
            bluetoothLeScanner?.startScan(null, settings, scanCallback)
            isScanning = true
            Log.i(TAG, "Started BLE scan")
            
            // Stop scan after timeout
            handler.postDelayed({ stopScan() }, SCAN_PERIOD)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting scan", e)
            callback.onError("蓝牙权限不足")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan", e)
            callback.onError("扫描启动失败: ${e.message}")
        }
    }

    fun stopScan() {
        if (!isScanning) return
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping scan", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping scan", e)
        }
        isScanning = false
        Log.i(TAG, "Stopped BLE scan")
    }

    fun connectToDevice(device: BluetoothDevice) {
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            Log.i(TAG, "Connecting to ${device.name ?: device.address}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception connecting to device", e)
            callback.onError("蓝牙连接权限不足")
        } catch (e: Exception) {
            Log.e(TAG, "Exception connecting to device", e)
            callback.onError("连接失败: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Exception disconnecting", e)
        }
        bluetoothGatt = null
        connectedDevice = null
    }

    private fun enableHeartRateNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                Log.i(TAG, "Enabled heart rate notifications")
            } else {
                Log.e(TAG, "CCC descriptor not found")
                callback.onError("CCC描述符未找到")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception enabling notifications", e)
            callback.onError("启用通知失败: ${e.message}")
        }
    }

    private fun parseHeartRate(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        
        val flag = data[0].toInt()
        val isHeartRate16Bit = (flag and 0x01) != 0
        
        return if (isHeartRate16Bit && data.size >= 3) {
            // 16-bit heart rate value
            ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        } else if (data.size >= 2) {
            // 8-bit heart rate value
            data[1].toInt() and 0xFF
        } else {
            0
        }
    }

    fun isConnected(): Boolean = connectedDevice != null
    fun getConnectedDeviceName(): String? = connectedDevice?.name
}
