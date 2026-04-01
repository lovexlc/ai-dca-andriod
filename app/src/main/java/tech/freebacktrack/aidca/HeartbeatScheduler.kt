package tech.freebacktrack.aidca

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object HeartbeatScheduler {
  private const val HEARTBEAT_WORK_NAME = "ai-dca-heartbeat"

  fun ensureScheduled(context: Context) {
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build()

    val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
      .setConstraints(constraints)
      .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
      HEARTBEAT_WORK_NAME,
      ExistingPeriodicWorkPolicy.UPDATE,
      request
    )

    DebugLogStore.append(context, "heartbeat", "Periodic heartbeat scheduled")
  }
}
