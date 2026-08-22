package com.dataproxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.dataproxy.util.AppLog
import com.dataproxy.util.AntiKillPreferences
import com.dataproxy.util.LifecycleDiagnostics
import java.security.Security

class DataProxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Disable JVM-level positive + negative DNS caching. Most of our
        // resolves go through Network#getAllByName on the cellular Network,
        // but any JVM fallback (e.g. java.net.InetAddress.getByName from a
        // library, or a future code path) would otherwise cache a poisoned
        // answer for the whole process lifetime — only force-stopping the
        // app would clear it. Cellular carriers in censorship regions
        // occasionally serve hijacked DNS, so a stale poisoned entry would
        // make TLS look broken to every client.
        Security.setProperty("networkaddress.cache.ttl", "0")
        Security.setProperty("networkaddress.cache.negative.ttl", "0")
        AntiKillPreferences.migrateInvalidAutoConfirmations(this)
        LifecycleDiagnostics.installCrashHandler(this)
        AppLog.i("DataProxy", "app process started")
        LifecycleDiagnostics.reportProcessStart(this)

        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "DataProxy",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Ongoing proxy status"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val message = "trim-memory: ${LifecycleDiagnostics.trimMemoryLabel(level)}; " +
            LifecycleDiagnostics.restrictionSummary(this)
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            AppLog.i("Lifecycle", message)
        } else {
            AppLog.w("Lifecycle", message)
        }
        LifecycleDiagnostics.record(this, message)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        val message = "system reported low memory; ${LifecycleDiagnostics.restrictionSummary(this)}"
        AppLog.w("Lifecycle", message)
        LifecycleDiagnostics.record(this, message)
    }

    companion object {
        const val CHANNEL_ID = "dataproxy.status"
    }
}
