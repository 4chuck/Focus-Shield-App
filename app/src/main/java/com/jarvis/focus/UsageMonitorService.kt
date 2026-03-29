package com.jarvis.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.focus.data.DataRepository
import kotlinx.coroutines.*
import java.util.Calendar
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * UsageMonitorService is a production-ready foreground service that monitors
 * application usage and enforces time limits. It uses Kotlin Coroutines for 
 * efficient background processing and avoids blocking the main thread.
 */
class UsageMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var repository: DataRepository
    
    // Polling intervals defined for production efficiency
    private val POLLING_INTERVAL = TimeUnit.SECONDS.toMillis(1)
    private val RECONCILE_INTERVAL = TimeUnit.MINUTES.toMillis(5)
    private val MONITOR_INTERVAL = TimeUnit.SECONDS.toMillis(2)
    private val POPUP_COOLDOWN = TimeUnit.MINUTES.toMillis(1)

    private lateinit var usageStatsManager: UsageStatsManager
    
    private var monitoredApps = emptySet<String>()
    private val usageTimeMap = mutableMapOf<String, Long>()
    private val lastPopupAt = mutableMapOf<String, Long>()
    private var lastEventTime = System.currentTimeMillis()

    companion object {
        private const val TAG = "UsageMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "focus_shield_service"
        private const val ACTION_STOP_SERVICE = "com.jarvis.focus.STOP_SERVICE"

        // Thread-safe state for UI and other components
        val tempAllowedApps: MutableMap<String, Long> = Collections.synchronizedMap(mutableMapOf<String, Long>())
        val foregroundStart: MutableMap<String, Long> = Collections.synchronizedMap(mutableMapOf<String, Long>())
        
        @Volatile
        var isPopupShowing = false
    }

    override fun onCreate() {
        super.onCreate()
        repository = DataRepository.getInstance(this)
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        // Start foreground immediately to comply with Android 12+ strictness
        startServiceInForeground()
        
        refreshMonitoredApps()
        if (hasUsageAccess()) {
            seedTotalsFromSystemAggregates()
        }

        startMonitoringCycles()
    }

    private fun startServiceInForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMonitoringCycles() {
        // Main Events Polling
        serviceScope.launch {
            while (isActive) {
                if (hasUsageAccess()) {
                    pollUsageEvents()
                }
                delay(POLLING_INTERVAL)
            }
        }

        // Periodic Reconciliation
        serviceScope.launch {
            while (isActive) {
                delay(RECONCILE_INTERVAL)
                if (hasUsageAccess()) {
                    seedTotalsFromSystemAggregates()
                }
            }
        }

        // Monitoring and Enforcement
        serviceScope.launch {
            while (isActive) {
                enforceUsageLimits()
                delay(MONITOR_INTERVAL)
            }
        }
        
        // Refresh notification periodically to respect Strict Mode changes
        serviceScope.launch {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(1))
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, createNotification())
            }
        }
    }

    private fun pollUsageEvents() {
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(lastEventTime, now)
        val ev = UsageEvents.Event()
        
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            when (ev.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> handleForegroundEvent(ev.packageName, ev.timeStamp)
                UsageEvents.Event.MOVE_TO_BACKGROUND -> handleBackgroundEvent(ev.packageName, ev.timeStamp)
            }
        }
        lastEventTime = now
    }

    private fun handleForegroundEvent(pkg: String, timeStamp: Long) {
        if (!monitoredApps.contains(pkg)) return
        if (!foregroundStart.containsKey(pkg)) {
            foregroundStart[pkg] = timeStamp
        }
    }

    private fun handleBackgroundEvent(pkg: String, timeStamp: Long) {
        val start = foregroundStart.remove(pkg) ?: return
        val elapsed = timeStamp - start
        if (elapsed > 0) {
            updateAppUsage(pkg, elapsed)
        }
        // Reset popup cooldown so it triggers immediately if the user re-enters the app
        lastPopupAt.remove(pkg)
    }

    private fun updateAppUsage(pkg: String, elapsed: Long) {
        val currentTotal = usageTimeMap[pkg] ?: 0L
        val newTotal = currentTotal + elapsed
        usageTimeMap[pkg] = newTotal
        
        serviceScope.launch(Dispatchers.IO) {
            repository.saveUsage(pkg, newTotal)
        }
    }

    private fun enforceUsageLimits() {
        refreshMonitoredApps()
        val now = System.currentTimeMillis()
        
        // Cleanup expired entries
        tempAllowedApps.entries.removeIf { it.value <= now }
        
        if (isPopupShowing) return

        getForegroundPackage()?.let { currentPkg ->
            if (monitoredApps.contains(currentPkg)) {
                checkAppLimit(currentPkg, now)
            }
        }
    }

    private fun checkAppLimit(pkg: String, now: Long) {
        val limitMins = repository.getAppLimit(pkg)
        val usageMs = usageTimeMap[pkg] ?: 0L
        val usageMins = (usageMs / 60000).toInt()

        if (usageMins >= limitMins) {
            val expiry = tempAllowedApps[pkg] ?: 0L
            if (now >= expiry) {
                val lastShown = lastPopupAt[pkg] ?: 0L
                if (now - lastShown > POPUP_COOLDOWN) {
                    if (getForegroundPackage() == pkg) {
                        showBlockingPopup(pkg)
                        lastPopupAt[pkg] = now
                    }
                }
            }
        }
    }

    private fun getForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - 10000, now)
        val event = UsageEvents.Event()
        var lastPkg: String? = null
        
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPkg = event.packageName
            } else if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (event.packageName == lastPkg) lastPkg = null
            }
        }
        
        if (lastPkg == null) {
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 30000, now)
            lastPkg = stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        }
        
        return lastPkg
    }

    private fun showBlockingPopup(pkg: String) {
        isPopupShowing = true
        val intent = Intent(this, PopupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra("package_name", pkg)
            putExtra("mode", "popup")
        }
        startActivity(intent)
    }

    private fun seedTotalsFromSystemAggregates() {
        val now = System.currentTimeMillis()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val agg = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)
        monitoredApps.forEach { pkg ->
            val totalMs = agg[pkg]?.totalTimeInForeground ?: 0L
            usageTimeMap[pkg] = totalMs
            serviceScope.launch(Dispatchers.IO) {
                repository.saveUsage(pkg, totalMs)
            }
        }
    }

    private fun refreshMonitoredApps() {
        monitoredApps = repository.getMonitoredApps()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        return appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Focus Shield Protection", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus Shield Active")
            .setContentText("Monitoring your productivity...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Only show Deactivate button if NOT in Strict Mode
        if (!repository.isStrictMode()) {
            val stopIntent = Intent(this, UsageMonitorService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            val stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Deactivate Shield", stopPendingIntent)
        }

        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            if (repository.isStrictMode()) {
                // Ignore stop request if strict mode is active
                return START_STICKY
            }
            stopSelf()
            return START_NOT_STICKY
        }
        refreshMonitoredApps()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
