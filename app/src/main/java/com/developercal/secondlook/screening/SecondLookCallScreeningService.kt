package com.developercal.secondlook.screening

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import com.developercal.secondlook.analysis.CallAction
import com.developercal.secondlook.analysis.CallDecisionEngine
import com.developercal.secondlook.analysis.CallSettings
import com.developercal.secondlook.data.LocalDatabase
import com.developercal.secondlook.data.LocalSettings
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Conservative screening: only exact user-entered blocklist matches or explicitly enabled private callers are actioned. */
class SecondLookCallScreeningService : CallScreeningService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onScreenCall(details: Call.Details) {
        val done = AtomicBoolean(false)
        fun respond(action: CallAction) {
            if (!done.compareAndSet(false, true)) return
            val builder = CallResponse.Builder()
            when (action) {
                CallAction.ALLOW -> Unit
                CallAction.BLOCK -> builder.setDisallowCall(true).setRejectCall(true).setSkipCallLog(false).setSkipNotification(false)
                CallAction.SILENCE -> builder.setSilenceCall(true)
            }
            try { respondToCall(details, builder.build()) } catch (_: Exception) { }
        }

        // Android allows five seconds. Return a conservative allow after 3.5s if local storage stalls.
        main.postDelayed({ respond(CallAction.ALLOW) }, 3_500)
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) { respond(CallAction.ALLOW); return }
        worker.execute {
            val action = try {
                val settings = LocalSettings(this)
                val rawNumber = details.handle?.schemeSpecificPart
                val privateCaller = rawNumber.isNullOrBlank() || rawNumber.trim().lowercase() in setOf("private", "unknown", "withheld", "restricted")
                val db = LocalDatabase.get(this).dao()
                val blocklist = if (settings.blocklistEnabled) db.listBlocked().map { it.normalized }.toSet() else emptySet()
                CallDecisionEngine.decide(
                    number = rawNumber,
                    blocklist = blocklist,
                    privateCaller = privateCaller,
                    settings = CallSettings(settings.protectionEnabled, settings.blocklistEnabled, settings.privateNumbersEnabled)
                )
            } catch (_: Throwable) { CallAction.ALLOW }
            main.post { respond(action) }
        }
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
