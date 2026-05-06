package tech.freebacktrack.aidca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM 入口。具体 Bark payload 解析与通知呈现都委托给 [BarkPayloadHandler]，
 * 以便后续的 WebSocket 长连接通道能复用同一条路径。
 *
 * Bark 字段覆盖范围见 [BarkPayloadHandler.handle]。
 */
class NotifyMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    super.onNewToken(token)
    DebugLogStore.append(applicationContext, "fcm", "onNewToken: ${maskToken(token)}")
    RegistrationRepository.syncFromService(applicationContext, token, "token-refresh")
  }

  override fun onMessageReceived(message: RemoteMessage) {
    super.onMessageReceived(message)
    BarkPayloadHandler.handle(
      context = applicationContext,
      rawData = message.data,
      source = "fcm",
      messageId = message.messageId.orEmpty(),
      fallbackTitle = message.notification?.title,
      fallbackBody = message.notification?.body,
    )
  }

  companion object {
    private const val LEGACY_CHANNEL_ID = "ai_dca_messages"
    private val SUPPORTED_LEVELS = setOf("active", "passive", "timesensitive", "critical")

    /**
     * 默认 channel（用于邀请中的预创建）。
     */
    fun ensureNotificationChannel(context: Context) {
      ensureChannel(context, "", "", false)
    }

    /**
     * 根据 level + sound + call 组合创建一个独立的通知通道，返回通道 ID。
     */
    fun ensureChannel(context: Context, level: String, sound: String, call: Boolean): String {
      if (Build.VERSION.SDK_INT < 26) return LEGACY_CHANNEL_ID
      val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

      val normalizedLevel = level.lowercase().replace("_", "").replace("-", "").let {
        if (it in SUPPORTED_LEVELS) it else "active"
      }
      val soundKey = sound.lowercase().filter { it.isLetterOrDigit() }.take(20)

      val channelId = buildString {
        append("bark_")
        append(if (call) "call" else normalizedLevel)
        if (soundKey.isNotEmpty()) {
          append("_")
          append(soundKey)
        }
      }

      if (nm.getNotificationChannel(channelId) != null) return channelId

      val importance = when {
        call -> NotificationManager.IMPORTANCE_HIGH
        normalizedLevel == "critical" -> NotificationManager.IMPORTANCE_HIGH
        normalizedLevel == "timesensitive" -> NotificationManager.IMPORTANCE_HIGH
        normalizedLevel == "passive" -> NotificationManager.IMPORTANCE_LOW
        else -> NotificationManager.IMPORTANCE_DEFAULT
      }
      val baseName = when {
        call -> "持续响铃"
        normalizedLevel == "critical" -> "重要提醒"
        normalizedLevel == "timesensitive" -> "时效提醒"
        normalizedLevel == "passive" -> "静默提醒"
        else -> "默认提醒"
      }
      val displayName = if (soundKey.isNotEmpty()) "$baseName · $sound" else baseName

      val channel = NotificationChannel(channelId, displayName, importance).apply {
        description = "Bark 风格推送通道（level=${normalizedLevel}" +
          (if (soundKey.isNotEmpty()) ", sound=$sound" else "") +
          (if (call) ", call=1" else "") + "）"
        // 1) 当 sound=<name> 且 res/raw/<name>.* 存在时，无论 level/call 如何都用该 raw 资源做铃声。
        // 2) 否则 call=1 / critical 退回默认 alarm uri，保持 v1 行为。
        // 3) 普通 level 且没找到 raw 资源时不显式 setSound，使用 channel 默认。
        val rawResId = if (soundKey.isNotEmpty()) {
          context.resources.getIdentifier(soundKey, "raw", context.packageName)
        } else 0
        if (rawResId != 0) {
          val attrs = AudioAttributes.Builder()
            .setUsage(if (call || normalizedLevel == "critical") AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
          val soundUri = Uri.parse("android.resource://${context.packageName}/$rawResId")
          setSound(soundUri, attrs)
          if (call || normalizedLevel == "critical") enableVibration(true)
          DebugLogStore.append(context.applicationContext, "notify", "channel $channelId sound=raw/$soundKey")
        } else if (call || normalizedLevel == "critical") {
          val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
          val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
          setSound(soundUri, attrs)
          enableVibration(true)
          DebugLogStore.append(context.applicationContext, "notify", "channel $channelId sound=default-alarm (raw/${soundKey.ifBlank { "<none>" }} not found)")
        }
      }

      nm.createNotificationChannel(channel)
      DebugLogStore.append(context.applicationContext, "notify", "Created channel $channelId")
      return channelId
    }

    private fun maskToken(token: String): String {
      return if (token.length <= 14) token else "${token.take(8)}...${token.takeLast(6)}"
    }
  }
}
