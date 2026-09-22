package com.yugahashimoto.andcode.runtime.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R

/**
 * Keeps the app in the foreground while Codex is signing in or running a turn.
 *
 * Codex is a child process of the app, so Android applies the app's own network rules to it - and
 * an app with nothing in the foreground is cut off from the network on some devices (a write to a
 * DNS server fails with EPERM). The ChatGPT sign-in is the obvious casualty: the browser takes over
 * the screen, and Codex's token exchange with auth.openai.com then fails the moment the callback
 * arrives. OpenCode's runtime is spared this by [LocalRuntimeService]; a setup with Codex alone has
 * no such service, so this one exists to give it the same standing for exactly as long as it is
 * needed. It owns no work of its own: [CodexRuntime.needsForeground] starts and stops it.
 */
class CodexKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // The platform can refuse the promotion for a service started while the app was in the
        // background; giving up beats being killed for never calling startForeground.
        runCatching { startForeground(NOTIFICATION_ID, notification()) }
            .onFailure { error ->
                Log.w(TAG, "Could not enter the foreground", error)
                stopSelf()
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_codex), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.codex_keepalive_notification_title))
            .setContentText(getString(R.string.codex_keepalive_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CodexKeepAlive"
        private const val CHANNEL_ID = "andcode_codex_keepalive"
        private const val NOTIFICATION_ID = 4301

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CodexKeepAliveService::class.java)) }
                .onFailure { error -> Log.w(TAG, "Foreground start refused", error) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CodexKeepAliveService::class.java))
        }
    }
}
