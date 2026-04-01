package tech.freebacktrack.aidca

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class HeartbeatWorker(
  appContext: Context,
  workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    return try {
      val snapshot = RegistrationRepository.runHeartbeat(applicationContext, "heartbeat")
      DebugLogStore.append(
        applicationContext,
        "heartbeat",
        "Heartbeat finished with state=${snapshot.state}"
      )
      Result.success()
    } catch (error: Exception) {
      DebugLogStore.append(
        applicationContext,
        "heartbeat",
        "Heartbeat failed: ${error.message ?: "未知错误"}"
      )
      Result.retry()
    }
  }
}
