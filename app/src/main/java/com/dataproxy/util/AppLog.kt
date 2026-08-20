package com.dataproxy.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AppLogLevel { DEBUG, INFO, WARN, ERROR }

data class AppLogEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: AppLogLevel,
    val tag: String,
    val message: String,
)

/** Thread-safe, bounded in-memory log store. */
internal class LogBuffer(
    private val maxEntries: Int = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private var nextId = 1L
    private val _entries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun append(level: AppLogLevel, tag: String, message: String) {
        val entry = AppLogEntry(
            id = nextId++,
            timestampMillis = clock(),
            level = level,
            tag = tag,
            message = message,
        )
        _entries.value = (_entries.value + entry).takeLast(maxEntries)
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }
}

/**
 * Mirrors important diagnostics to both Android logcat and an in-app console.
 * Traffic diagnostics in this console are intentionally memory-only so
 * destinations and failures are not left behind on disk after the process
 * exits. [LifecycleDiagnostics] separately persists a small set of
 * privacy-safe process/battery breadcrumbs needed to diagnose OS kills.
 */
object AppLog {
    private const val MAX_STACK_CHARS = 4_000
    private val buffer = LogBuffer()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    val entries: StateFlow<List<AppLogEntry>> = buffer.entries

    fun d(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.d(tag, message) else Log.d(tag, message, error)
        append(AppLogLevel.DEBUG, tag, message, error)
    }

    fun i(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.i(tag, message) else Log.i(tag, message, error)
        append(AppLogLevel.INFO, tag, message, error)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
        append(AppLogLevel.WARN, tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
        append(AppLogLevel.ERROR, tag, message, error)
    }

    fun clear() = buffer.clear()

    fun exportText(): String = entries.value.joinToString("\n") { entry ->
        "${formatTime(entry.timestampMillis)} ${entry.level.name.first()}/${entry.tag}: ${entry.message}"
    }

    fun formatTime(timestampMillis: Long): String =
        timeFormatter.format(Instant.ofEpochMilli(timestampMillis))

    private fun append(
        level: AppLogLevel,
        tag: String,
        message: String,
        error: Throwable?,
    ) {
        val fullMessage = if (error == null) {
            message
        } else {
            "$message\n${error.stackTraceToString().take(MAX_STACK_CHARS)}"
        }
        buffer.append(level, tag, fullMessage)
    }
}
