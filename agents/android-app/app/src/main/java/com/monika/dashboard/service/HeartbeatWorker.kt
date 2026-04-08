package com.monika.dashboard.service

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.usage.UsageStatsManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.*
import com.monika.dashboard.data.DebugLog
import com.monika.dashboard.data.SettingsStore
import com.monika.dashboard.device.ScreenStateReceiver
import com.monika.dashboard.network.ReportClient
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based heartbeat that survives Xiaomi/HyperOS process freezer.
 * Uses self-rescheduling OneTimeWorkRequest to bypass the 15-min periodic minimum.
 * AlarmManager under the hood wakes the app even when frozen by cgroup freezer.
 */
class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "Heartbeat"
        private const val WORK_NAME = "heartbeat_report"
        private const val KEY_INTERVAL_SEC = "interval_sec"
        const val MIN_INTERVAL_SECONDS = 10
        const val MAX_INTERVAL_SECONDS = 50
        const val DEFAULT_INTERVAL_SECONDS = 30

        fun schedule(context: Context, intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS) {
            val safe = intervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
            enqueueNext(context, safe)
            DebugLog.log("心跳Worker", "已启动，间隔 ${safe} 秒")
            Log.i(TAG, "Scheduled heartbeat every ${safe}s")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            DebugLog.log("心跳Worker", "已取消")
            Log.i(TAG, "Cancelled heartbeat")
        }

        private fun enqueueNext(context: Context, intervalSec: Int) {
            val request = OneTimeWorkRequestBuilder<HeartbeatWorker>()
                .setInitialDelay(intervalSec.toLong(), TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(KEY_INTERVAL_SEC to intervalSec))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext)
        val intervalSec = inputData.getInt(KEY_INTERVAL_SEC, DEFAULT_INTERVAL_SECONDS)

        val enabled = settings.monitoringEnabled.first()
        if (!enabled) {
            DebugLog.log("心跳Worker", "监听未开启，跳过")
            return Result.success()
        }

        val url = settings.serverUrl.first()
        val token = settings.getToken()
        if (url.isEmpty() || token.isNullOrEmpty()) {
            DebugLog.log("心跳Worker", "URL或Token未配置，跳过")
            enqueueNext(applicationContext, intervalSec)
            return Result.success()
        }

        var client: ReportClient? = null
        try {
            client = ReportClient(url, token)

            val appId = resolveHeartbeatAppId()
            val battery = getBatteryInfo()

            val result = client.reportApp(
                appId = appId,
                windowTitle = "",
                batteryPercent = battery?.first,
                batteryCharging = battery?.second
            )

            if (result.isSuccess) {
                DebugLog.log("心跳Worker", "上报成功: $appId")
                Log.i(TAG, "Heartbeat sent: $appId")
            } else {
                DebugLog.log("心跳Worker", "上报失败: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            DebugLog.log("心跳Worker", "异常: ${e.message}")
            Log.e(TAG, "Heartbeat error", e)
        } finally {
            runCatching { client?.shutdown() }
        }

        // Always reschedule next heartbeat
        enqueueNext(applicationContext, intervalSec)
        return Result.success()
    }

    private fun resolveHeartbeatAppId(): String {
        if (ScreenStateReceiver.isIdleLocked(applicationContext)) {
            return "idle"
        }

        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isInteractive) {
            return "idle"
        }

        val keyguardManager = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager != null && keyguardManager.isKeyguardLocked) {
            return "idle"
        }

        if (!hasUsageStatsPermission()) {
            return "android"
        }

        val foregroundPackage = getForegroundPackage() ?: return "android"
        return if (foregroundPackage == applicationContext.packageName) {
            "android"
        } else {
            foregroundPackage
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                applicationContext.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                applicationContext.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getForegroundPackage(): String? {
        val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 60_000,
            now
        )

        if (stats.isNullOrEmpty()) return null

        var latestPackage: String? = null
        var latestTime = 0L
        for (stat in stats) {
            if (stat.lastTimeUsed > latestTime) {
                latestTime = stat.lastTimeUsed
                latestPackage = stat.packageName
            }
        }
        return latestPackage
    }

    private fun getBatteryInfo(): Pair<Int, Boolean>? {
        return try {
            val intent = applicationContext.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (level >= 0 && scale > 0) {
                    val percent = (level * 100) / scale
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    Pair(percent, charging)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
