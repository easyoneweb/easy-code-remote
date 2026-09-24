package com.easycoderemote.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Starts the foreground data-sync service from an explicit user action
 * (connect wizard "Enable live updates" / Settings), per Android 14 FGS rules.
 * Returns false when the OS refused the start (background FGS restriction).
 */
object ServiceStarter {

    fun start(context: Context): Boolean {
        return try {
            val intent = Intent(context, LiveSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LiveSyncService::class.java))
    }
}