package com.dataproxy.util

import android.content.Context
import com.dataproxy.service.ProxyService

/**
 * Anti-Kill persistence, kept in the same [ProxyService.PREFS_NAME] file the
 * rest of the app uses.
 *
 * - [autoStartOnBoot] gates whether [com.dataproxy.service.BootReceiver]
 *   relaunches the proxy after a reboot.
 * - the per-[AntiKillStep] flags only cover the manual OEM steps — the user
 *   ticks "I've done this" because the system can't report those settings.
 *   Auto-detectable steps are read live and never persisted here.
 */
object AntiKillPreferences {
    private const val KEY_AUTOSTART = "autostart_on_boot"
    private const val KEY_MANUAL_CONFIRMATION_SCHEMA = "antikill_manual_confirmation_schema"
    private const val MANUAL_CONFIRMATION_SCHEMA = 2
    private fun stepKey(step: AntiKillStep) = "antikill_step_${step.name}"

    private fun prefs(context: Context) =
        context.getSharedPreferences(ProxyService.PREFS_NAME, Context.MODE_PRIVATE)

    fun autoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOSTART, false)

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

    fun stepDone(context: Context, step: AntiKillStep): Boolean =
        prefs(context).getBoolean(stepKey(step), false)

    fun setStepDone(context: Context, step: AntiKillStep, done: Boolean) {
        prefs(context).edit().putBoolean(stepKey(step), done).apply()
    }

    /**
     * v1 marked OEM steps complete as soon as their Settings page opened.
     * Those values cannot be trusted, so reset them once and require an
     * explicit "Mark done" confirmation under the corrected UI.
     */
    fun migrateInvalidAutoConfirmations(context: Context) {
        val prefs = prefs(context)
        if (prefs.getInt(KEY_MANUAL_CONFIRMATION_SCHEMA, 0) >= MANUAL_CONFIRMATION_SCHEMA) return
        val editor = prefs.edit()
        AntiKillStep.entries.filterNot { it.autoDetectable }.forEach {
            editor.remove(stepKey(it))
        }
        editor.putInt(KEY_MANUAL_CONFIRMATION_SCHEMA, MANUAL_CONFIRMATION_SCHEMA).commit()
    }
}
