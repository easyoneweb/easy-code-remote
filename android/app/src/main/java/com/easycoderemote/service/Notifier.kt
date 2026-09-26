package com.easycoderemote.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.easycoderemote.MainActivity
import com.easycoderemote.R
import com.easycoderemote.data.remote.LiveStream
import com.easycoderemote.ui.navigation.Routes
import com.easycoderemote.ui.navigation.Routes.EXTRA_ROUTE
import com.easycoderemote.ui.navigation.Routes.EXTRA_SESSION_ID
import com.easycoderemote.ui.navigation.Routes.ROUTE_APPROVAL

/** Local notifications on the three channels, only for app-background events. */
class Notifier(private val context: Context) {

    companion object {
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_COMPLETIONS = "completions"
        const val CHANNEL_ATTENTION = "attention"
        const val CHANNEL_LIVE = "live"

        const val NOTIF_LIVE = 1
        const val NOTIF_APPROVAL = 100
        const val NOTIF_COMPLETION = 200
        const val NOTIF_ATTENTION = 300
    }

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, context.getString(R.string.channel_approvals), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_approvals_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETIONS, context.getString(R.string.channel_completions), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_completions_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.channel_attention), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_attention_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, context.getString(R.string.channel_live), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_live_desc) },
        )
    }

    /** Human label of the live-sync stream state for the FGS notification. */
    fun foregroundStateLabel(state: LiveStream.StreamState): String = when (state) {
        LiveStream.StreamState.Connecting -> "Connecting to server…"
        LiveStream.StreamState.Connected -> "Live sync connected"
        LiveStream.StreamState.Reconnecting -> "Reconnecting…"
        LiveStream.StreamState.Stopped -> "Live sync stopped"
    }

    /** Updates the foreground notification text in place (FGS contract: the
     *  notification stays visible while the service runs; only its text/content
     *  intent may change). */
    @SuppressLint("MissingPermission")
    fun updateForeground(text: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(NOTIF_LIVE, foreground(text))
    }

    fun foreground(title: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(title)
            .setContentIntent(liveIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** Tapping the live notification opens the app root (no extras → sessions). */
    private fun liveIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun permissionAsked(sessionId: String, sessionTitle: String, text: String) {
        notifyChecked(
            CHANNEL_APPROVALS, NOTIF_APPROVAL + sessionId.hashCode(), sessionTitle,
            text, approvalIntent(sessionId),
        )
    }

    fun questionAsked(sessionId: String, sessionTitle: String, text: String) {
        notifyChecked(
            CHANNEL_APPROVALS, NOTIF_APPROVAL + sessionId.hashCode(), sessionTitle,
            text, approvalIntent(sessionId),
        )
    }

    fun sessionCompleted(sessionTitle: String, summary: String) {
        notifyChecked(CHANNEL_COMPLETIONS, NOTIF_COMPLETION, sessionTitle, summary, null)
    }

    fun sessionError(sessionTitle: String, message: String) {
        notifyChecked(CHANNEL_COMPLETIONS, NOTIF_COMPLETION + 1, "$sessionTitle — error", message, null)
    }

    fun engineDisconnected(reason: String) {
        notifyChecked(CHANNEL_ATTENTION, NOTIF_ATTENTION, "Engine disconnected", reason, null)
    }

    fun engineReconnected() {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ATTENTION)
    }

    @SuppressLint("MissingPermission")
    private fun notifyChecked(channel: String, id: Int, title: String, text: String, contentIntent: PendingIntent?) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        contentIntent?.let { builder.setContentIntent(it) }
        manager.notify(id, builder.build())
    }

    private fun approvalIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ROUTE, ROUTE_APPROVAL)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context, sessionId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}