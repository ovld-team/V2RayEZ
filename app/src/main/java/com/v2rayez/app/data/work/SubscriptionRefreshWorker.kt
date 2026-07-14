package com.v2rayez.app.data.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2rayez.app.domain.repository.ServerRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Aggregate result of one refresh sweep — pure/testable apart from Worker/Context/WorkManager. */
data class SubscriptionRefreshSummary(
    val attempted: Int,
    val succeeded: Int,
    val failed: Int
) {
    val allFailed: Boolean get() = attempted > 0 && failed == attempted
}

/**
 * Refreshes every enabled subscription. Deliberately framework-free (no `Context`/`WorkManager`)
 * so [SubscriptionRefreshWorkerTest]-style unit tests can exercise it with a fake
 * [ServerRepository] without Robolectric — this project's unit tests run on plain JVM.
 */
object SubscriptionRefresher {
    suspend fun refreshEnabled(repo: ServerRepository): SubscriptionRefreshSummary {
        val subs = repo.subscriptions().first().filter { it.enabled }
        var succeeded = 0
        var failed = 0
        for (sub in subs) {
            val ok = runCatching { repo.refreshSubscription(sub.id) }.getOrNull()?.success == true
            if (ok) succeeded++ else failed++
        }
        return SubscriptionRefreshSummary(subs.size, succeeded, failed)
    }
}

/**
 * Periodic background subscription refresh (replaces the old "auto-refresh" overclaim, which
 * only ever ran when [com.v2rayez.app.ui.viewmodel.ServersViewModel] happened to be created).
 * Thin `CoroutineWorker` shell — all real logic lives in [SubscriptionRefresher] above.
 */
@HiltWorker
class SubscriptionRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val serverRepository: ServerRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val summary = runCatching { SubscriptionRefresher.refreshEnabled(serverRepository) }
            .onFailure { Log.w(TAG, "Subscription refresh sweep threw", it) }
            .getOrNull()
        return when {
            summary == null -> Result.retry()
            summary.allFailed -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val TAG = "SubscriptionRefreshWorker"
        const val UNIQUE_WORK_NAME = "subscription-refresh-periodic"
        private const val REPEAT_INTERVAL_HOURS = 6L

        /**
         * Enqueue (or leave untouched, via [ExistingPeriodicWorkPolicy.KEEP]) the periodic
         * refresh. Safe to call on every app start — WorkManager no-ops when the unique work
         * already exists with the same schedule.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
                REPEAT_INTERVAL_HOURS,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Cancel the periodic refresh (currently unused by product UI — kept for tests/future settings toggle). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
