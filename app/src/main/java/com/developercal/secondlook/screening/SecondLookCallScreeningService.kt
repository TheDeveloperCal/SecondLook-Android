package com.developercal.secondlook.screening

import android.os.Build
import android.os.Handler
import android.annotation.SuppressLint
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.developercal.secondlook.analysis.CallAction
import com.developercal.secondlook.analysis.CallDecisionEngine
import com.developercal.secondlook.analysis.CallSettings
import com.developercal.secondlook.data.LocalDatabase
import com.developercal.secondlook.data.LocalSettings
import com.developercal.secondlook.data.IntelligenceCache
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Conservative screening: only exact user-entered blocklist matches or explicitly enabled private callers are actioned. */
class SecondLookCallScreeningService : CallScreeningService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val callbackIds = AtomicLong()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service_created")
    }

    @SuppressLint("NewApi")
    override fun onScreenCall(details: Call.Details) {
        val callbackId = callbackIds.incrementAndGet()
        val incoming = Build.VERSION.SDK_INT < 29 || details.callDirection == Call.Details.DIRECTION_INCOMING
        val rawNumber = details.handle?.schemeSpecificPart
        Log.i(TAG, "call=$callbackId callback_received direction=${if (incoming) "incoming" else "other"} handle_present=${!rawNumber.isNullOrBlank()}")
        val done = AtomicBoolean(false)
        fun respond(action: CallAction, source: String) {
            if (!done.compareAndSet(false, true)) return
            Log.i(TAG, "call=$callbackId decision=$action source=$source")
            val builder = CallResponse.Builder()
            when (action) {
                CallAction.ALLOW -> Unit
                CallAction.BLOCK -> builder.setDisallowCall(true).setRejectCall(true).setSkipCallLog(false).setSkipNotification(false)
                CallAction.SILENCE -> if (Build.VERSION.SDK_INT >= 29) builder.setSilenceCall(true)
            }
            try {
                respondToCall(details, builder.build())
                Log.i(TAG, "call=$callbackId response_submitted action=$action")
            } catch (error: Exception) {
                Log.e(TAG, "call=$callbackId response_failed", error)
            }
        }

        // Android allows five seconds. Return a conservative allow after 3.5s if local storage stalls.
        main.postDelayed({ respond(CallAction.ALLOW, "timeout") }, 3_500)
        if (!incoming) { respond(CallAction.ALLOW, "non_incoming"); return }
        try {
            worker.execute {
            val action = try {
                val settings = LocalSettings(this)
                val privateCaller = rawNumber.isNullOrBlank() || rawNumber.trim().lowercase() in setOf("private", "unknown", "withheld", "restricted")
                val db = LocalDatabase.get(this).dao()
                val blocklist = if (settings.blocklistEnabled) db.listBlocked().map { it.normalized }.toSet() else emptySet()
                val callSettings = CallSettings(settings.protectionEnabled, settings.blocklistEnabled, settings.privateNumbersEnabled)
                val report = rawNumber?.let { IntelligenceCache(db).lookup(it) }
                val reportCount = if (report?.snapshot != null && System.currentTimeMillis() - report.snapshot.retrievedAt <= java.util.concurrent.TimeUnit.DAYS.toMillis(7)) report.reportedNumber?.reportCount ?: 0 else 0
                val decision = CallDecisionEngine.decide(
                    number = rawNumber,
                    blocklist = blocklist,
                    privateCaller = privateCaller,
                    settings = callSettings,
                    unverifiedReportCount = reportCount
                )
                decision to decisionSource(rawNumber, privateCaller, blocklist, callSettings, decision, reportCount)
            } catch (error: Exception) {
                Log.e(TAG, "call=$callbackId policy_failed; allowing", error)
                CallAction.ALLOW to "policy_error"
            }
            main.post { respond(action.first, action.second) }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            Log.e(TAG, "call=$callbackId policy_worker_unavailable; allowing", error)
            respond(CallAction.ALLOW, "worker_unavailable")
        }
    }

    private fun decisionSource(
        number: String?,
        privateCaller: Boolean,
        blocklist: Set<String>,
        settings: CallSettings,
        action: CallAction,
        unverifiedReportCount: Int
    ): String = when {
        action == CallAction.BLOCK -> "exact_blocklist_match"
        action == CallAction.SILENCE -> "private_call_opt_in"
        !settings.protectionEnabled -> "protection_disabled"
        privateCaller -> "private_call_opt_out"
        CallDecisionEngine.normalize(number) == null -> "unidentified_number"
        !settings.blocklistEnabled -> "blocklist_disabled"
        unverifiedReportCount > 0 -> "unverified_reports_allow"
        else -> "caller_not_listed"
    }

    override fun onDestroy() {
        Log.i(TAG, "service_destroyed")
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SecondLookScreening"
    }
}
