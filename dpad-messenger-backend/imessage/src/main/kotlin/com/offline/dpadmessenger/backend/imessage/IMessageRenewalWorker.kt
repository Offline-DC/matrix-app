package com.offline.dpadmessenger.backend.imessage

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic re-registration. Mirror of the renewal half of the gmessages token
 * refresh, but for IDS (IMESSAGE_NATIVE_BACKEND_PLAN.md §2.2/§4/§8).
 *
 * Renewal = re-run steps 3–4 (validation data + IDS register) on a schedule.
 * In **Mac mode** this needs Apple connectivity (the relay / Apple endpoints),
 * NOT the physical Mac. If this misses its window (Doze, killed worker) the
 * device silently de-registers — so it carries the same WorkManager robustness
 * the launcher's other chains use, and [IMessageRepository.status] gives the
 * field a visible "stuck renewal" surface.
 */
class IMessageRenewalWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "running iMessage renewal")
        return when (val r = IMessageRepository.renew(applicationContext)) {
            is RegistrationResult.Success -> {
                Log.i(TAG, "renewal ok (registered ${r.account.lastRegisteredMs})")
                Result.success()
            }
            is RegistrationResult.Failure -> {
                Log.w(TAG, "renewal failed: ${r.message} — will retry")
                // Transient (relay/Apple unreachable) → let WorkManager back off.
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "IMsgRenewal"
        private const val WORK_NAME = "imessage_renewal"

        /** Schedule periodic renewal (every ~12h, network-gated). Idempotent —
         *  KEEP policy means calling it repeatedly won't stack work. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<IMessageRenewalWorker>(
                12, TimeUnit.HOURS,
                1, TimeUnit.HOURS, // flex window
            ).setConstraints(constraints).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
