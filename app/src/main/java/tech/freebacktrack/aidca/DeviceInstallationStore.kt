package tech.freebacktrack.aidca

import android.content.Context
import android.provider.Settings
import java.util.Locale
import java.util.UUID

object DeviceInstallationStore {
  private const val PREFERENCES_NAME = "ai_dca_notify_state"
  private const val DEVICE_INSTALLATION_ID_KEY = "device_installation_id"

  fun getOrCreate(context: Context): String {
    val appContext = context.applicationContext
    val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val existing = preferences.getString(DEVICE_INSTALLATION_ID_KEY, null)?.trim().orEmpty()

    if (existing.isNotBlank()) {
      // Preserve legacy installation IDs so existing registrations do not break on app upgrade.
      return existing
    }

    val generated = resolveStableDeviceId(appContext) ?: "android-${UUID.randomUUID()}"
    preferences.edit().putString(DEVICE_INSTALLATION_ID_KEY, generated).apply()
    return generated
  }

  /**
   * Always returns a freshly generated `android-<uuid>` id. Used by the manual reset flow
   * where the user explicitly wants a brand-new identifier instead of the ANDROID_ID动态推导。
   */
  fun generateRandomId(): String = "android-${UUID.randomUUID()}"

  /**
   * Overwrites the stored deviceInstallationId with the given value. Trims and falls back
   * to a freshly generated id when the input is blank, so callers can never end up with
   * an empty identifier.
   */
  fun replace(context: Context, newId: String): String {
    val appContext = context.applicationContext
    val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val sanitized = newId.trim().ifBlank { generateRandomId() }
    preferences.edit().putString(DEVICE_INSTALLATION_ID_KEY, sanitized).apply()
    return sanitized
  }

  private fun resolveStableDeviceId(context: Context): String? {
    // On Android 8+, ANDROID_ID stays stable across reinstall for the same device, user, and signing key.
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
      ?.trim()
      ?.lowercase(Locale.US)
      .orEmpty()

    if (androidId.isBlank() || androidId == "unknown") {
      return null
    }

    return "android-$androidId"
  }
}
