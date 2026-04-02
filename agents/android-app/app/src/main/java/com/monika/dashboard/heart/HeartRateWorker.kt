package com.monika.dashboard.heart

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import com.monika.dashboard.data.DebugLog
import java.util.concurrent.TimeUnit

class HeartRateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "HeartRateWorker"
        private const val WORK_NAME = "heart_rate_keepalive"
        private const val INTERVAL_SECONDS = 30L

        fun schedule(context: Context) {
            enqueueNext(context)
            DebugLog.log("心率Worker", "已启动保活")
            Log.i(TAG, "Scheduled heart rate keepalive")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            DebugLog.log("心率Worker", "已取消")
            Log.i(TAG, "Cancelled heart rate keepalive")
        }

        private fun enqueueNext(context: Context) {
            val request = OneTimeWorkRequestBuilder<HeartRateWorker>()
                .setInitialDelay(INTERVAL_SECONDS, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        // Check if service is running
        if (!HeartRateService.isServiceRunning) {
            DebugLog.log("心率Worker", "服务未运行，正在启动...")
            Log.i(TAG, "Service not running, starting...")
            
            val intent = Intent(applicationContext, HeartRateService::class.java)
            intent.action = "START_SCAN"
            
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
                DebugLog.log("心率Worker", "服务已启动")
            } catch (e: Exception) {
                DebugLog.log("心率Worker", "启动服务失败: ${e.message}")
                Log.e(TAG, "Failed to start service", e)
            }
        } else {
            DebugLog.log("心率Worker", "服务运行中")
        }

        // Always reschedule
        enqueueNext(applicationContext)
        return Result.success()
    }
}
