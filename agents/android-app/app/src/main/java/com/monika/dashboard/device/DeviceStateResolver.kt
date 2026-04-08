package com.monika.dashboard.device

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

object DeviceStateResolver {
    fun resolveCurrentAppId(context: Context): String {
        if (ScreenStateReceiver.isIdleLocked(context)) {
            return "idle"
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isInteractive) {
            return "idle"
        }

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager != null && keyguardManager.isKeyguardLocked) {
            return "idle"
        }

        if (!hasUsageStatsPermission(context)) {
            return "android"
        }

        val foregroundPackage = getForegroundPackage(context) ?: return "android"
        return if (foregroundPackage == context.packageName) {
            "android"
        } else {
            foregroundPackage
        }
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getForegroundPackage(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
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
}
