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
  val pairingDetail: String
) {
  companion object {
    fun idle(identity: AppIdentity): RegistrationSnapshot {
      return RegistrationSnapshot(
        state = "idle",
        title = "等待自动注册",
        detail = "应用会在启动后自动拉取 FCM token 并注册到 AI DCA 通知服务。",
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
        pairingDetail = "设备完成注册后会自动向 Worker 申请前端配对码。"
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
