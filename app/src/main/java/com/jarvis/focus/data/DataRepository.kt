package com.jarvis.focus.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * Production-ready Repository to manage all app data and preferences.
 */
class DataRepository(context: Context) {

    private val usagePrefs: SharedPreferences = context.getSharedPreferences(PREFS_USAGE, Context.MODE_PRIVATE)
    private val appPrefs: SharedPreferences = context.getSharedPreferences(PREFS_MONITORED, Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
    private val limitPrefs: SharedPreferences = context.getSharedPreferences(PREFS_LIMITS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_USAGE = "usage_prefs"
        private const val PREFS_MONITORED = "monitored_apps_prefs"
        private const val PREFS_SETTINGS = "settings_prefs"
        private const val PREFS_LIMITS = "app_limits_prefs"
        
        private const val KEY_SELECTED_PACKAGES = "selected_packages"
        private const val KEY_DAILY_GOAL = "daily_goal_minutes"
        private const val KEY_STRICT_MODE = "strict_mode_enabled"
        private const val KEY_BATTERY_ASKED = "battery_permission_asked"
        private const val DEFAULT_LIMIT_MINS = 30

        @Volatile
        private var INSTANCE: DataRepository? = null

        fun getInstance(context: Context): DataRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DataRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // --- Monitored Apps ---

    fun getMonitoredApps(): Set<String> {
        return appPrefs.getStringSet(KEY_SELECTED_PACKAGES, emptySet()) ?: emptySet()
    }

    val monitoredAppsFlow: Flow<Set<String>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == KEY_SELECTED_PACKAGES) {
                trySend(prefs.getStringSet(key, emptySet()) ?: emptySet())
            }
        }
        appPrefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getMonitoredApps())
        awaitClose { appPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    suspend fun setMonitoredApps(packages: Set<String>) = withContext(Dispatchers.IO) {
        appPrefs.edit { putStringSet(KEY_SELECTED_PACKAGES, packages) }
    }

    // --- Individual App Limits ---

    fun getAppLimit(pkg: String): Int = limitPrefs.getInt(pkg, DEFAULT_LIMIT_MINS)

    suspend fun setAppLimit(pkg: String, minutes: Int) = withContext(Dispatchers.IO) {
        limitPrefs.edit { putInt(pkg, minutes) }
    }

    fun getAllAppLimits(): Map<String, Int> {
        return limitPrefs.all.filterValues { it is Int }.mapValues { it.value as Int }
    }

    // --- Usage Data ---

    fun getUsage(pkg: String): Long = usagePrefs.getLong(pkg, 0L)

    suspend fun saveUsage(pkg: String, timeMs: Long) = withContext(Dispatchers.IO) {
        usagePrefs.edit { putLong(pkg, timeMs) }
    }

    fun getTotalUsage(monitoredOnly: Boolean = true): Long {
        val apps = if (monitoredOnly) getMonitoredApps() else usagePrefs.all.keys
        return apps.sumOf { usagePrefs.getLong(it, 0L) }
    }

    // --- Settings ---

    fun getDailyGoal(): Int = settingsPrefs.getInt(KEY_DAILY_GOAL, 120)

    suspend fun setDailyGoal(minutes: Int) = withContext(Dispatchers.IO) {
        settingsPrefs.edit { putInt(KEY_DAILY_GOAL, minutes) }
    }

    fun isStrictMode(): Boolean = settingsPrefs.getBoolean(KEY_STRICT_MODE, false)

    suspend fun setStrictMode(enabled: Boolean) = withContext(Dispatchers.IO) {
        settingsPrefs.edit { putBoolean(KEY_STRICT_MODE, enabled) }
    }

    // --- Permission Flags ---

    fun wasBatteryPermissionAsked(): Boolean = settingsPrefs.getBoolean(KEY_BATTERY_ASKED, false)

    suspend fun setBatteryPermissionAsked(asked: Boolean) = withContext(Dispatchers.IO) {
        settingsPrefs.edit { putBoolean(KEY_BATTERY_ASKED, asked) }
    }
}
