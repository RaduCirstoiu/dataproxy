package com.dataproxy.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Durable, privacy-safe lifecycle breadcrumbs.
 *
 * The normal in-app log is deliberately memory-only. That is good for proxy
 * privacy, but it also means the evidence disappears when Android kills the
 * process. This store persists only lifecycle/restriction state (never client
 * addresses, destinations, credentials, or traffic) and replays it on the
 * next process start.
 */
object LifecycleDiagnostics {
    private const val TAG = "Lifecycle"
    private const val PREFS = "dataproxy_lifecycle_diagnostics"
    private const val KEY_BREADCRUMBS = "breadcrumbs"
    private const val KEY_PROXY_EXPECTED = "proxy_expected_running"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private const val MAX_BREADCRUMBS = 32
    private const val SEPARATOR = '\u001e'

    private val eventTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    @Volatile
    private var crashHandlerInstalled = false

    /** Persist an uncaught stack before Android tears the process down. */
    @Synchronized
    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        val appContext = context.applicationContext
        val delegate = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                recordException(
                    appContext,
                    "uncaught exception on ${thread.name}",
                    error,
                )
            }
            delegate?.uncaughtException(thread, error)
        }
        crashHandlerInstalled = true
    }

    /** Report the prior process exit and replay breadcrumbs into the console. */
    fun reportProcessStart(context: Context) {
        val prefs = prefs(context)
        val proxyWasExpected = prefs.getBoolean(KEY_PROXY_EXPECTED, false)
        val previous = decode(prefs.getString(KEY_BREADCRUMBS, null))

        // Consume the old session before writing this process's first event.
        prefs.edit()
            .remove(KEY_BREADCRUMBS)
            .putBoolean(KEY_PROXY_EXPECTED, false)
            .commit()

        val exitReason = reportSystemExitReason(context)
        previous.takeLast(12).forEach { AppLog.i(TAG, "previous: $it") }
        if (proxyWasExpected) {
            AppLog.w(
                TAG,
                expectedProxyExitSummary(exitReason),
            )
        }
        AppLog.i(TAG, "current survival state: ${restrictionSummary(context)}")
        record(context, "process started; ${restrictionSummary(context)}")
    }

    /** Mark whether a future process death would be unexpected. */
    fun setProxyExpected(context: Context, expected: Boolean, event: String) {
        prefs(context).edit().putBoolean(KEY_PROXY_EXPECTED, expected).commit()
        record(context, event)
    }

    /** Persist one lifecycle-only event synchronously so sudden kills retain it. */
    @Synchronized
    fun record(context: Context, event: String) {
        val clean = event.replace('\n', ' ').replace(SEPARATOR, ' ')
        val line = "${eventTime.format(Instant.now())} $clean"
        val prefs = prefs(context)
        val next = (decode(prefs.getString(KEY_BREADCRUMBS, null)) + line)
            .takeLast(MAX_BREADCRUMBS)
        prefs.edit().putString(KEY_BREADCRUMBS, next.joinToString(SEPARATOR.toString())).commit()
    }

    /** Persist a bounded cause chain + stack. No traffic payload is included. */
    fun recordException(context: Context, label: String, error: Throwable) {
        val causes = generateSequence(error) { it.cause }
            .take(4)
            .joinToString(" <- ") { cause ->
                val message = cause.message
                    ?.replace('\n', ' ')
                    ?.replace(SEPARATOR, ' ')
                    ?.take(500)
                cause.javaClass.name + if (message.isNullOrBlank()) "" else ": $message"
            }
        val stack = error.stackTrace.take(24).joinToString(" | ") { frame ->
            "${frame.className}.${frame.methodName}:${frame.lineNumber}"
        }
        record(context, "$label; $causes; stack=$stack")
    }

    /** Human-readable state for logs after Settings, screen-off, or a kill. */
    fun restrictionSummary(context: Context): String {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName)
        val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.isBackgroundRestricted
        } else {
            false
        }
        val manual = listOf(
            "app-launch=${manualState(context, AntiKillStep.AutoStart)}",
            "background=${manualState(context, AntiKillStep.BackgroundActivity)}",
            "recents-lock=${manualState(context, AntiKillStep.LockInRecents)}",
        ).joinToString(",")
        return "device=${Build.MANUFACTURER}/${Build.MODEL}; " +
            "notifications=${status(notifications)}; " +
            "battery-exemption=${status(batteryExempt)}; " +
            "background-restricted=$backgroundRestricted; " +
            "power-save=${power.isPowerSaveMode}; idle=${power.isDeviceIdleMode}; " +
            "manual-confirmations[$manual]"
    }

    fun trimMemoryLabel(level: Int): String = when (level) {
        20 -> "UI hidden"
        5 -> "running moderate"
        10 -> "running low"
        15 -> "running critical"
        40 -> "background"
        60 -> "moderate"
        80 -> "complete"
        else -> "level $level"
    }

    private fun reportSystemExitReason(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val latest = runCatching {
            activity.getHistoricalProcessExitReasons(context.packageName, 0, 5).firstOrNull()
        }.getOrNull() ?: return null
        val prefs = prefs(context)
        if (latest.timestamp <= prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)) return latest.reason
        prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, latest.timestamp).commit()

        val message = "previous Android process exit: reason=${exitReason(latest.reason)}; " +
            "time=${eventTime.format(Instant.ofEpochMilli(latest.timestamp))}; " +
            "importance=${latest.importance}; status=${latest.status}" +
            latest.description?.takeIf { it.isNotBlank() }?.let { "; description=$it" }.orEmpty()
        if (latest.reason == ApplicationExitInfo.REASON_EXIT_SELF ||
            latest.reason == ApplicationExitInfo.REASON_USER_REQUESTED
        ) {
            AppLog.i(TAG, message)
        } else {
            AppLog.w(TAG, message)
        }
        return latest.reason
    }

    private fun expectedProxyExitSummary(reason: Int?): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
            "previous process crashed while the proxy was expected to be running"
        ApplicationExitInfo.REASON_USER_REQUESTED ->
            "previous process was force-stopped or removed from Recents while the proxy was running"
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            "Android killed the proxy process because the phone was low on memory"
        ApplicationExitInfo.REASON_FREEZER ->
            "Android killed the proxy process after it was frozen"
        else ->
            "previous process ended while the proxy was expected to be running; " +
                "see the Android exit reason and previous breadcrumbs above"
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-force-stop"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_FREEZER -> "frozen"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package-state-change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package-updated"
        else -> "code-$reason"
    }

    private fun manualState(context: Context, step: AntiKillStep): String =
        if (AntiKillPreferences.stepDone(context, step)) "confirmed" else "UNCONFIRMED"

    private fun status(granted: Boolean): String = if (granted) "granted" else "MISSING"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun decode(value: String?): List<String> =
        value?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
}
