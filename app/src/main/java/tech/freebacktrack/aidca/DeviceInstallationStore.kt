package tech.freebacktrack.aidca

import android.content.Context
import java.util.UUID

object DeviceInstallationStore {
  private const val PREFERENCES_NAME = "ai_dca_notify_state"
  private const val DEVICE_INSTALLATION_ID_KEY = "device_installation_id"

  fun getOrCreate(context: Context): String {
    val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val existing = preferences.getString(DEVICE_INSTALLATION_ID_KEY, null)?.trim().orEmpty()

    if (existing.isNotBlank()) {
      return existing
    }

    val generated = "android-${UUID.randomUUID()}"
    preferences.edit().putString(DEVICE_INSTALLATION_ID_KEY, generated).apply()
    return generated
  }
}
