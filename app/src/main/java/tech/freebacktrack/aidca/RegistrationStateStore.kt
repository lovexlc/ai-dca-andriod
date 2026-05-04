package tech.freebacktrack.aidca

import android.content.Context
import org.json.JSONObject

object RegistrationStateStore {
  private const val PREFERENCES_NAME = "ai_dca_notify_state"
  private const val SNAPSHOT_KEY = "registration_snapshot"

  fun read(context: Context, fallbackIdentity: AppIdentity): RegistrationSnapshot {
    val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val rawValue = preferences.getString(SNAPSHOT_KEY, null) ?: return RegistrationSnapshot.idle(fallbackIdentity)

    return try {
      val payload = JSONObject(rawValue)
      RegistrationSnapshot(
        state = payload.optString("state"),
        title = payload.optString("title"),
        detail = payload.optString("detail"),
        updatedAt = payload.optString("updatedAt"),
        deviceInstallationId = payload.optString("deviceInstallationId", fallbackIdentity.deviceInstallationId),
        tokenMasked = payload.optString("tokenMasked"),
        projectId = payload.optString("projectId", fallbackIdentity.projectId),
        packageName = payload.optString("packageName", fallbackIdentity.packageName),
        deviceName = payload.optString("deviceName", fallbackIdentity.deviceName),
        senderId = payload.optString("senderId", fallbackIdentity.senderId),
        appId = payload.optString("appId", fallbackIdentity.appId),
        trigger = payload.optString("trigger"),
        registrationId = payload.optString("registrationId"),
        pairingCode = payload.optString("pairingCode"),
        pairingCodeExpiresAt = payload.optString("pairingCodeExpiresAt"),
        pairingStatus = payload.optString("pairingStatus"),
        pairingDetail = payload.optString("pairingDetail"),
        pairedClientSummary = payload.optString("pairedClientSummary"),
        pairedClientsJson = payload.optString("pairedClientsJson")
      )
    } catch (_error: Exception) {
      RegistrationSnapshot.idle(fallbackIdentity)
    }
  }

  fun write(context: Context, snapshot: RegistrationSnapshot) {
    val payload = JSONObject()
      .put("state", snapshot.state)
      .put("title", snapshot.title)
      .put("detail", snapshot.detail)
      .put("updatedAt", snapshot.updatedAt)
      .put("deviceInstallationId", snapshot.deviceInstallationId)
      .put("tokenMasked", snapshot.tokenMasked)
      .put("projectId", snapshot.projectId)
      .put("packageName", snapshot.packageName)
      .put("deviceName", snapshot.deviceName)
      .put("senderId", snapshot.senderId)
      .put("appId", snapshot.appId)
      .put("trigger", snapshot.trigger)
      .put("registrationId", snapshot.registrationId)
      .put("pairingCode", snapshot.pairingCode)
      .put("pairingCodeExpiresAt", snapshot.pairingCodeExpiresAt)
      .put("pairingStatus", snapshot.pairingStatus)
      .put("pairingDetail", snapshot.pairingDetail)
      .put("pairedClientSummary", snapshot.pairedClientSummary)
      .put("pairedClientsJson", snapshot.pairedClientsJson)

    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(SNAPSHOT_KEY, payload.toString())
      .apply()
  }
}
