package com.monika.dashboard.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.monika.dashboard.data.DebugLog
import com.monika.dashboard.data.SettingsStore
import com.monika.dashboard.network.ReportClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

class ScreenStateReceiver(
    private val context: Context,
    private val settings: SettingsStore
) {
    companion object {
        private const val PREFS_NAME = "screen_state"
        private const val KEY_IDLE_LOCKED = "idle_locked"

        fun isIdleLocked(context: Context): Boolean {
            return try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_IDLE_LOCKED, false)
            } catch (_: Exception) {
                false
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    setIdleLocked(true)
                    DebugLog.log("屏幕", "息屏")
                    Log.i("ScreenState", "Screen OFF")
                    reportIdle()
                }
                Intent.ACTION_SCREEN_ON -> {
                    DebugLog.log("屏幕", "亮屏")
                    Log.i("ScreenState", "Screen ON")
                }
                Intent.ACTION_USER_PRESENT -> {
                    setIdleLocked(false)
                    DebugLog.log("屏幕", "已解锁")
                    Log.i("ScreenState", "User present")
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(receiver, filter)
        registered = true
        Log.i("ScreenState", "Receiver started")
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        Log.i("ScreenState", "Receiver stopped")
    }

    private fun setIdleLocked(locked: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_IDLE_LOCKED, locked)
                .apply()
        }
    }

    private fun reportIdle() {
        executor.execute {
            val url = try { runBlocking { settings.serverUrl.first() } } catch (_: Exception) { "" }
            val token = try { settings.getToken() } catch (_: Exception) { null }
            if (url.isEmpty() || token.isNullOrEmpty()) return@execute

            var client: ReportClient? = null
            try {
                client = ReportClient(url, token)
                client.reportApp(appId = "idle", windowTitle = "")
                DebugLog.log("屏幕", "上报 idle")
            } catch (e: Exception) {
                DebugLog.log("屏幕", "上报失败: ${e.message}")
            } finally {
                runCatching { client?.shutdown() }
            }
        }
    }
}
