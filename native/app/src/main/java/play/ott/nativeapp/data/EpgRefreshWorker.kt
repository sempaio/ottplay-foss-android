package play.ott.nativeapp.data

import android.content.Context
import androidx.work.*
import play.ott.nativeapp.OttplayApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class EpgRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as OttplayApplication).repository
        return try {
            var failures = 0
            repository.sources().forEach { source ->
                try { repository.refreshEpg(source, repository.cached(source.id)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failures++ }
            }
            if (failures > 0 && runAttemptCount < 2) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure() }
    }
    companion object {
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<EpgRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("epg-refresh", ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
