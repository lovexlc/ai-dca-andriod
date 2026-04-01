package tech.freebacktrack.aidca

data class RegistrationSnapshot(
  val state: String,
  val title: String,
  val detail: String,
  val updatedAt: String,
  val deviceInstallationId: String,
  val tokenMasked: String,
  val projectId: String,
  val packageName: String,
  val deviceName: String,
  val senderId: String,
  val appId: String,
  val trigger: String,
  val registrationId: String,
  val pairingCode: String,
  val pairingCodeExpiresAt: String,
  val pairingStatus: String,
  val pairingDetail: String,
  val pairedClientSummary: String
) {
  companion object {
    fun idle(identity: AppIdentity): RegistrationSnapshot {
      return RegistrationSnapshot(
        state = "idle",
        title = "等待自动注册",
        detail = "",
        updatedAt = "",
        deviceInstallationId = identity.deviceInstallationId,
        tokenMasked = "",
        projectId = identity.projectId,
        packageName = identity.packageName,
        deviceName = identity.deviceName,
        senderId = identity.senderId,
        appId = identity.appId,
        trigger = "",
        registrationId = "",
        pairingCode = "",
        pairingCodeExpiresAt = "",
        pairingStatus = "",
        pairingDetail = "",
        pairedClientSummary = ""
      )
    }
  }
}

data class AppIdentity(
  val deviceInstallationId: String,
  val projectId: String,
  val packageName: String,
  val deviceName: String,
  val senderId: String,
  val appId: String
)
