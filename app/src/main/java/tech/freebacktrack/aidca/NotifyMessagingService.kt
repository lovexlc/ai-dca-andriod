package tech.freebacktrack.aidca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * 收到 FCM 推送后的处理。Bark 兼容：
 *  - title / subtitle / body / body_md
 *  - url（点击跳转 URL）
 *  - group（通知分组 key）
 *  - icon（远程图标 URL，作 largeIcon）
 *  - sound（映射到独立 channel）
 *  - level：passive | active | timeSensitive | critical
 *  - call=1（在 critical/alarm 属性 channel 上推送以使系统震动/响铃）
 *  - isArchive：0/1，是否写入本机历史
 *  - copy / autoCopy：拷贝到剪贴板
 *  - badge：设置数字角标
 *  - ciphertext + iv：AES-256-CBC 端到端加密，本机解密后霆叠在明文字段上
 *
 * 以及现有的业务字段：eventId / eventType / symbol / strategyName /
 *  triggerCondition / purchaseAmount / detailUrl。
 */
class NotifyMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    super.onNewToken(token)
    DebugLogStore.append(applicationContext, "fcm", "onNewToken: ${maskToken(token)}")
    RegistrationRepository.syncFromService(applicationContext, token, "token-refresh")
  }

  override fun onMessageReceived(message: RemoteMessage) {
    super.onMessageReceived(message)
    val ctx = applicationContext
    val raw = message.data
    val data: Map<String, String> = tryDecryptPayload(ctx, raw)?.let { raw + it } ?: raw

    val title = data["title"]
      ?: message.notification?.title
      ?: getString(R.string.incoming_message_fallback_title)
    val subtitle = data["subtitle"].orEmpty()
    val body = data["body"]
      ?: data["message"]
      ?: message.notification?.body
      ?: "收到一条新的基金通知。"
    val bodyMd = data["body_md"].orEmpty()
    val urlAction = data["url"].orEmpty()
    val group = data["group"].orEmpty()
    val iconUrl = data["icon"].orEmpty()
    val soundName = data["sound"].orEmpty()
    val level = data["level"].orEmpty()
    val callMode = data["call"] == "1"
    val copyText = data["copy"].orEmpty()
    val autoCopy = data["autoCopy"] == "1" || data["automaticallyCopy"] == "1"
    val badge = data["badge"]?.toIntOrNull() ?: 0
    val isArchive = when (data["isArchive"]) {
      null -> BarkSettingsStore.defaultArchive(ctx)
      "0" -> false
      "1" -> true
      else -> BarkSettingsStore.defaultArchive(ctx)
    }

    val messageId = message.messageId.orEmpty()
    val eventId = data["eventId"].orEmpty()
    val eventType = data["eventType"].orEmpty()
    val symbol = data["symbol"].orEmpty()
    val strategyName = data["strategyName"].orEmpty()
    val triggerCondition = data["triggerCondition"].orEmpty()
    val purchaseAmount = data["purchaseAmount"].orEmpty()
    val detailUrl = data["detailUrl"].orEmpty()

    val channelId = ensureChannel(ctx, level, soundName, callMode)

    val expandedBody = buildExpandedBody(body, subtitle, triggerCondition, purchaseAmount, detailUrl)
    val expandedBodyRich: CharSequence =
      if (bodyMd.isNotBlank()) MarkdownRenderer.render(bodyMd) else expandedBody

    DebugLogStore.append(
      ctx,
      "fcm",
      "onMessageReceived id=${messageId.ifBlank { "-" }} title=${title.take(48)} " +
        "level=${level.ifBlank { "-" }} sound=${soundName.ifBlank { "-" }} call=$callMode " +
        "archive=$isArchive group=${group.ifBlank { "-" }} dataKeys=${raw.keys.joinToString(",")}"
    )

    if (isArchive) {
      NotificationMessageStore.upsertReceived(
        context = ctx,
        eventId = eventId,
        messageId = messageId,
        eventType = eventType,
        title = title,
        body = body,
        bodyMd = bodyMd,
        triggerCondition = triggerCondition,
        purchaseAmount = purchaseAmount,
        detailUrl = detailUrl,
        symbol = symbol,
        strategyName = strategyName
      )
    }
    DeliveryReceiptStore.writeReceived(ctx, title, expandedBody, messageId)

    if (autoCopy && copyText.isNotBlank()) {
      copyToClipboard(ctx, copyText)
    }

    val pendingIntent = buildTapIntent(ctx, urlAction, eventId, messageId)
    val largeIcon = if (iconUrl.isNotBlank()) tryDownloadBitmap(iconUrl) else null

    val builder = android.app.Notification.Builder(this, channelId)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title)
      .setContentText(
        if (subtitle.isNotBlank()) subtitle
        else buildCompactBody(body, triggerCondition, purchaseAmount)
      )
      .setStyle(
        android.app.Notification.BigTextStyle()
          .setBigContentTitle(title)
          .also { if (subtitle.isNotBlank()) it.setSummaryText(subtitle) }
          .bigText(expandedBodyRich)
      )
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)
    if (group.isNotBlank()) builder.setGroup(group)
    if (largeIcon != null) builder.setLargeIcon(largeIcon)
    if (badge > 0) builder.setNumber(badge)

    val notification = builder.build()

    try {
      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.notify(notificationId(eventId, messageId), notification)
      if (isArchive) NotificationMessageStore.markDisplayed(ctx, eventId, messageId)
      DebugLogStore.append(ctx, "notify", "Notification displayed for message ${messageId.ifBlank { "-" }} channel=$channelId")
    } catch (error: Exception) {
      if (isArchive) {
        NotificationMessageStore.markDisplayError(
          ctx,
          eventId,
          messageId,
          error.message ?: "未知错误"
        )
      }
      DebugLogStore.append(
        ctx,
        "notify",
        "Notification display failed for message ${messageId.ifBlank { "-" }}: ${error.message ?: "未知错误"}"
      )
      DeliveryReceiptStore.writeDisplayError(
        ctx,
        title,
        body,
        messageId,
        error.message ?: "未知错误"
      )
    }
  }

  private fun buildTapIntent(ctx: Context, urlAction: String, eventId: String, messageId: String): PendingIntent {
    val safeUrl = urlAction.trim()
    if (safeUrl.isNotBlank() && (safeUrl.startsWith("http://") || safeUrl.startsWith("https://"))) {
      val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      return PendingIntent.getActivity(
        ctx,
        safeUrl.hashCode(),
        viewIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
    }
    val launchIntent = Intent(ctx, MainActivity::class.java).apply {
      putExtra(MainActivity.EXTRA_EVENT_ID, eventId)
      putExtra(MainActivity.EXTRA_MESSAGE_ID, messageId)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
      ctx,
      1,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun copyToClipboard(ctx: Context, text: String) {
    try {
      val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText("bark", text))
    } catch (_: Exception) {
      // 忽略剪贴板异常（后台限制等）
    }
  }

  private fun tryDownloadBitmap(urlString: String): Bitmap? {
    return try {
      val conn = (URL(urlString.trim()).openConnection() as HttpURLConnection).apply {
        connectTimeout = 4000
        readTimeout = 4000
        useCaches = true
        setRequestProperty("User-Agent", "jijin-notify-android")
      }
      conn.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
      null
    }
  }

  /**
   * 如果 data 字段里有 ciphertext 且本机配了密钥，则尝试 AES-256-CBC 解密。
   * 返回解出的明文字段集（会霆叠在原始 data 上）。
   */
  private fun tryDecryptPayload(ctx: Context, data: Map<String, String>): Map<String, String>? {
    val ciphertext = data["ciphertext"]?.takeIf { it.isNotBlank() } ?: return null
    val key = BarkSettingsStore.encryptionKey(ctx).takeIf { it.isNotBlank() } ?: return null
    val ivStr = data["iv"].orEmpty()
    return try {
      val sha = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
      val targetLen = when (key.length) { 16 -> 16; 24 -> 24; else -> 32 }
      val keyBytes = sha.copyOf(targetLen)
      val ivBytes = if (ivStr.isNotBlank()) {
        val raw = ivStr.toByteArray(Charsets.UTF_8)
        if (raw.size >= 16) raw.copyOf(16) else raw + ByteArray(16 - raw.size)
      } else ByteArray(16)
      val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
      val decoded = Base64.decode(ciphertext, Base64.DEFAULT)
      val plain = String(cipher.doFinal(decoded), Charsets.UTF_8)
      val json = JSONObject(plain)
      val out = HashMap<String, String>()
      val it = json.keys()
      while (it.hasNext()) {
        val k = it.next()
        out[k] = json.optString(k, "")
      }
      DebugLogStore.append(ctx, "fcm", "ciphertext decrypted, fields=${out.keys.joinToString(",")}")
      out
    } catch (error: Exception) {
      DebugLogStore.append(ctx, "fcm", "ciphertext decrypt failed: ${error.message ?: "未知错误"}")
      null
    }
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
     * 根据 level + sound + call 名下创建一个独立的通知通道，返回通道 ID。
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
        if (call || normalizedLevel == "critical") {
          val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
          val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
          setSound(soundUri, attrs)
          enableVibration(true)
        }
      }

      nm.createNotificationChannel(channel)
      DebugLogStore.append(context.applicationContext, "notify", "Created channel $channelId")
      return channelId
    }

    private fun maskToken(token: String): String {
      return if (token.length <= 14) token else "${token.take(8)}...${token.takeLast(6)}"
    }

    private fun buildCompactBody(body: String, triggerCondition: String, purchaseAmount: String): String {
      return when {
        triggerCondition.isNotBlank() && purchaseAmount.isNotBlank() -> "$triggerCondition · 建议买入 $purchaseAmount"
        triggerCondition.isNotBlank() -> triggerCondition
        purchaseAmount.isNotBlank() -> "建议买入 $purchaseAmount"
        else -> body
      }
    }

    private fun buildExpandedBody(
      body: String,
      subtitle: String,
      triggerCondition: String,
      purchaseAmount: String,
      detailUrl: String
    ): String {
      return buildString {
        if (subtitle.isNotBlank()) {
          append(subtitle)
          append("\n\n")
        }
        append(body)
        if (triggerCondition.isNotBlank()) {
          append("\n\n触发条件: ")
          append(triggerCondition)
        }
        if (purchaseAmount.isNotBlank()) {
          append("\n购买金额: ")
          append(purchaseAmount)
        }
        if (detailUrl.isNotBlank()) {
          append("\n\n更详细的策略说明请到网站查看。")
        }
      }
    }

    private fun notificationId(eventId: String, messageId: String): Int {
      val stableId = eventId.ifBlank { messageId }.ifBlank { System.currentTimeMillis().toString() }
      return stableId.hashCode()
    }
  }
}
