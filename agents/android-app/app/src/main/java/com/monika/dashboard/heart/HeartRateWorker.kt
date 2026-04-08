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
            enqueueNext(context, ExistingWorkPolicy.KEEP)
            DebugLog.log("心率Worker", "已启动保活")
            Log.i(TAG, "Scheduled heart rate keepalive")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            DebugLog.log("心率Worker", "已取消")
            Log.i(TAG, "Cancelled heart rate keepalive")
        }

        private fun enqueueNext(
            context: Context,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE
        ) {
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
                policy,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        DebugLog.log("心率Worker", "执行中...")
        Log.i(TAG, "Running...")
        
        val intent = Intent(applicationContext, HeartRateService::class.java)
        intent.action = "RECONNECT_SAVED_DEVICE"
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            DebugLog.log("心率Worker", "服务保活命令已发送（仅重连已保存设备）")
            Log.i(TAG, "Service keepalive command sent")
        } catch (e: Exception) {
            DebugLog.log("心率Worker", "启动失败: ${e.message}")
            Log.e(TAG, "Failed to start service", e)
        }

        // Always reschedule
        enqueueNext(applicationContext)
        return Result.success()
    }
}
