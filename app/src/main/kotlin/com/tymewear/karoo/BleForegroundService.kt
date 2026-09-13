package com.tymewear.karoo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * Plain foreground service that pins the app process in memory while a BLE
 * connection is active. Shared BLE sessions keep it running through Karoo's
 * short subscriber hand-offs at ride boundaries.
 *
 * Pattern modeled on `timklge/karoo-powerbar`: a separate Service (not the
 * `KarooExtension` itself), `IMPORTANCE_MIN` channel, `CATEGORY_SERVICE`.
 */
class BleForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("BleForegroundService starting")
        val hasPhysicalSession = synchronized(lifecycleLock) {
            physicalSessions.isNotEmpty()
        }
        if (!hasPhysicalSession) {
            // A final session can disappear while Android is still delivering an
            // earlier start request. Do not leave an orphan foreground service.
            Timber.d("Ignoring stale BLE foreground start with no physical session")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VitalBreathe Bluetooth active")
            .setContentText("Connecting to or receiving from VitalPro")
            .setSmallIcon(R.drawable.ic_breathing)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
            synchronized(lifecycleLock) {
                foregroundRunning = true
                breathRetryBackoff.reset()
            }
        } catch (e: Exception) {
            // A service started with startForegroundService must promote promptly.
            // If Karoo rejects promotion, stop instead of leaving a broken service.
            Timber.w(e, "startForeground rejected — stopping BLE foreground service")
            synchronized(lifecycleLock) {
                foregroundRunning = false
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Timber.d("BleForegroundService stopping")
        synchronized(lifecycleLock) {
            foregroundRunning = false
        }
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tymewear_ble"
        private const val NOTIFICATION_ID = 1001
        private val physicalSessions = ConcurrentHashMap.newKeySet<String>()
        private val lifecycleLock = Any()
        private var foregroundRunning = false
        private val breathRetryBackoff = ForegroundRetryBackoff()

        private fun ensureChannel(ctx: Context) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Tymewear BLE",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }

        fun start(ctx: Context) {
            synchronized(lifecycleLock) {
                startServiceInternal(ctx)
            }
        }

        /** Keep foreground promotion aligned with an actual shared BLE session. */
        fun physicalSessionStarted(ctx: Context, sessionKey: String) {
            synchronized(lifecycleLock) {
                val sessionWasAdded = physicalSessions.add(sessionKey)
                if (sessionWasAdded) {
                    breathRetryBackoff.reset()
                    Timber.d("BLE foreground physical session+$sessionKey count=${physicalSessions.size}")
                }
                if (foregroundSessionStartRequired(sessionWasAdded, foregroundRunning)) {
                    // A newly added session always refreshes Android's started-service
                    // request. This closes the race where an old session called
                    // stopService, the replacement arrived before onDestroy, and the
                    // old `foregroundRunning=true` value suppressed the replacement.
                    startServiceInternal(ctx)
                }
            }
        }

        /** Retry a previously rejected promotion at an eligible ride lifecycle event. */
        fun retryIfActive(ctx: Context) {
            synchronized(lifecycleLock) {
                if (physicalSessions.isNotEmpty() && !foregroundRunning) {
                    Timber.d("Retrying BLE foreground promotion for active physical session")
                    startServiceInternal(ctx)
                }
            }
        }

        /**
         * Breath packets are frequent, so rejected foreground starts use a
         * bounded monotonic backoff. Explicit ride, GATT, and session recovery
         * calls continue to bypass this gate.
         */
        fun retryIfActiveFromBreath(ctx: Context, nowElapsedMs: Long) {
            synchronized(lifecycleLock) {
                if (physicalSessions.isNotEmpty() &&
                    !foregroundRunning &&
                    breathRetryBackoff.tryAcquire(nowElapsedMs)
                ) {
                    Timber.d("Retrying BLE foreground promotion from live sensor traffic")
                    startServiceInternal(ctx)
                }
            }
        }

        fun physicalSessionStopped(ctx: Context, sessionKey: String) {
            synchronized(lifecycleLock) {
                if (physicalSessions.remove(sessionKey)) {
                    Timber.d("BLE foreground physical session-$sessionKey count=${physicalSessions.size}")
                }
                if (physicalSessions.isEmpty()) {
                    breathRetryBackoff.reset()
                    ctx.stopService(Intent(ctx, BleForegroundService::class.java))
                }
            }
        }

        private fun startServiceInternal(ctx: Context) {
            val intent = Intent(ctx, BleForegroundService::class.java)
            try {
                ctx.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 14+ may throw ForegroundServiceStartNotAllowedException if the
                // app isn't in an eligible foreground state. The connected-device
                // declaration satisfies the service-type prerequisite, not Android's
                // separate background-start eligibility rule. BLE can still recover,
                // and a later usable GATT/breath event will retry this promotion.
                Timber.w(e, "startForegroundService rejected — extension will run without FG pin")
            }
        }

        fun stop(ctx: Context) {
            synchronized(lifecycleLock) {
                if (physicalSessions.isEmpty()) {
                    ctx.stopService(Intent(ctx, BleForegroundService::class.java))
                } else {
                    Timber.d("Deferring BLE foreground stop; ${physicalSessions.size} physical session(s) active")
                }
            }
        }
    }
}

/** Pure decision kept testable without relying on Android service callback timing. */
internal fun foregroundSessionStartRequired(
    sessionWasAdded: Boolean,
    foregroundRunning: Boolean,
): Boolean = sessionWasAdded || !foregroundRunning

/** Pure elapsed-realtime retry policy for high-frequency breath callbacks. */
internal class ForegroundRetryBackoff(
    private val initialCooldownMs: Long = 1_000L,
    private val maximumCooldownMs: Long = 30_000L,
) {
    private var nextAllowedElapsedMs: Long? = null
    private var nextCooldownMs = initialCooldownMs

    init {
        require(initialCooldownMs > 0L)
        require(maximumCooldownMs >= initialCooldownMs)
    }

    fun tryAcquire(nowElapsedMs: Long): Boolean {
        val nextAllowed = nextAllowedElapsedMs
        if (nextAllowed != null && nowElapsedMs < nextAllowed) return false

        nextAllowedElapsedMs = if (Long.MAX_VALUE - nowElapsedMs < nextCooldownMs) {
            Long.MAX_VALUE
        } else {
            nowElapsedMs + nextCooldownMs
        }
        nextCooldownMs = if (nextCooldownMs >= maximumCooldownMs / 2L) {
            maximumCooldownMs
        } else {
            (nextCooldownMs * 2L).coerceAtMost(maximumCooldownMs)
        }
        return true
    }

    fun reset() {
        nextAllowedElapsedMs = null
        nextCooldownMs = initialCooldownMs
    }
}
