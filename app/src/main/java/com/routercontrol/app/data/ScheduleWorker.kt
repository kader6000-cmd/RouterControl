package com.routercontrol.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Background enforcement of schedules and timers, via WorkManager.
 * - Periodic check (every 15 min): for each enabled schedule, decide whether the
 *   rule should be ON (blocked) right now and apply it if it differs.
 * - One-time timer: after the user opens a blocked device for X minutes, this
 *   worker re-enables the rule (blocks the device again).
 * Both paths call ParentalSync, so a change is only treated as done when the
 * router confirms it (SUCC); otherwise the rule stays pendingSync.
 */
class ScheduleWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        val settings = store.loadSettings()
        val client = RouterClient()
        val mode = inputData.getString(KEY_MODE) ?: MODE_PERIODIC
        return try {
            when (mode) {
                MODE_TIMER -> {
                    val ruleName = inputData.getString(KEY_RULE_NAME) ?: return Result.success()
                    val rules = store.loadRules().toMutableList()
                    val idx = rules.indexOfFirst { it.name == ruleName }
                    if (idx < 0) return Result.success()
                    val rule = rules[idx]
                    val res = ParentalSync.applyRule(client, settings, rule, true)
                    rules[idx] = rule.copy(enabled = true, pendingSync = res is RouterResult.Failure)
                    store.saveRules(rules)
                    Result.success()
                }
                else -> {
                    val rules = store.loadRules().toMutableList()
                    val schedules = store.loadSchedules().filter { it.enabled }
                    if (schedules.isEmpty()) return Result.success()
                    val now = Calendar.getInstance()
                    val dayBit = 1 shl ((now.get(Calendar.DAY_OF_WEEK) - 1) % 7) // Sun=0
                    val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    var changed = false
                    for (s in schedules) {
                        if (s.daysMask and dayBit == 0) continue
                        val inWindow = if (s.startMinutes <= s.endMinutes) {
                            nowMin in s.startMinutes..s.endMinutes
                        } else {
                            nowMin >= s.startMinutes || nowMin <= s.endMinutes
                        }
                        val idx = rules.indexOfFirst { it.name == s.ruleName }
                        if (idx < 0) continue
                        val rule = rules[idx]
                        if (rule.enabled != inWindow) {
                            val res = ParentalSync.applyRule(client, settings, rule, inWindow)
                            rules[idx] = rule.copy(enabled = inWindow, pendingSync = res is RouterResult.Failure)
                            changed = true
                        }
                    }
                    if (changed) store.saveRules(rules)
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_RULE_NAME = "rule_name"
        const val MODE_PERIODIC = "periodic"
        const val MODE_TIMER = "timer"
        const val UNIQUE_PERIODIC = "router_schedule_check"

        fun enqueuePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<ScheduleWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_MODE to MODE_PERIODIC))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }

        fun enqueueTimer(context: Context, ruleName: String, delayMinutes: Long) {
            val req = OneTimeWorkRequestBuilder<ScheduleWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_MODE to MODE_TIMER, KEY_RULE_NAME to ruleName))
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
