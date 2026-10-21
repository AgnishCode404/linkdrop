package com.agnish.linkdrop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.agnish.linkdrop.ui.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the connection to a desktop alive while the screen is off or LinkDrop is in the
 * background: a foreground service (with its notification) from the first connection attempt
 * until the connection ends. Without it, Android cuts an idle app's network and lets Wi-Fi and the
 * CPU sleep, and the desktop drops the connection after missing its pings.
 *
 * While running it holds a Wi-Fi lock, and a CPU wake lock only while files are being transferred.
 * It stops itself when the connection ends, for any reason.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var connection: PhoneConnection
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var watching: Job? = null
    private var lastStartId = 0

    /** The newest notification content; shown at most once a second (see [watch]). */
    private val latest = MutableStateFlow<NotificationContent?>(null)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        connection = (application as LinkDropApplication).connection
        createChannel(this)
        // LOW_LATENCY is the Wi-Fi lock mode Android still supports (HIGH_PERF is deprecated); it
        // takes effect while the screen is on. With the screen off, the foreground service itself
        // keeps network access, and the wake lock below keeps transfers going.
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "LinkDrop:connection")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinkDrop:transfer")
            ?.apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_DISCONNECT) {
            // From the notification's button. The watcher then sees "disconnected" and stops.
            connection.disconnect()
            if (watching == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (watching == null) {
            // Android requires startForeground soon after startForegroundService, even if the
            // connection has already failed (the watcher then stops at once).
            val content = ConnectionNotification.content(connection.state.value, emptyList())
                ?: NotificationContent("LinkDrop", null, null, "Disconnect")
            try {
                startForeground(NOTIFICATION_ID, build(content), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (e: Exception) {
                // E.g. ForegroundServiceStartNotAllowedException: the app is no longer in front.
                Log.w(TAG, "Could not run in the foreground; the connection lasts while LinkDrop is open", e)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            watching = watch()
        }
        // Not restarted by Android after being killed: the connection is gone with the process.
        return START_NOT_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watch(): Job = scope.launch {
        launch {
            // Show the newest content, at most once a second (progress changes many times a second).
            latest.filterNotNull().collect { content ->
                // Without the permission (Android 13+, declined) updates are not shown anyway.
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(content))
                }
                delay(NOTIFICATION_INTERVAL_MILLIS)
            }
        }
        connection.state
            .flatMapLatest { state ->
                val transfers = (state as? PhoneConnection.State.Connected)?.session?.transfers ?: flowOf(emptyList())
                transfers.map { state to it }
            }
            .collect { (state, transfers) ->
                if (!ConnectionNotification.shouldRun(state)) {
                    stop()
                    return@collect
                }
                holdWakeLock(transfers.any { !it.finished })
                latest.value = ConnectionNotification.content(state, transfers)
            }
    }

    /** The CPU wake lock, only while files are being transferred (or wait for an answer). */
    private fun holdWakeLock(transferring: Boolean) {
        val lock = wakeLock ?: return
        if (transferring && !lock.isHeld) {
            lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        } else if (!transferring && lock.isHeld) {
            lock.release()
        }
    }

    private fun stop() {
        watching?.cancel()
        watching = null
        wakeLock?.takeIf { it.isHeld }?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Only if no newer start came in meanwhile (a new connection attempt keeps it running).
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun build(content: NotificationContent): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Palette.ACCENT.toInt())
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notification), content.actionLabel, disconnect)
                    .build(),
            )
            .apply {
                content.progress?.let { progress ->
                    val fraction = progress.fraction
                    setProgress(PROGRESS_MAX, ((fraction ?: 0f) * PROGRESS_MAX).toInt(), fraction == null)
                }
            }
            .build()
    }

    companion object {
        private const val TAG = "ConnectionService"
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.agnish.linkdrop.action.DISCONNECT"
        private const val NOTIFICATION_INTERVAL_MILLIS = 1000L
        private const val PROGRESS_MAX = 1000

        /** A safety limit: the lock is renewed by the next progress update anyway. */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 60L * 60 * 1000

        /** Starts the service; call while LinkDrop is in front (Android refuses it from the background). */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ConnectionService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: the connection then lasts while LinkDrop is open.
                Log.w(TAG, "Could not start the connection service", e)
            }
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_connection),
                NotificationManager.IMPORTANCE_LOW, // no sound, no pop-up
            ).apply {
                description = context.getString(R.string.notification_channel_connection_description)
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
