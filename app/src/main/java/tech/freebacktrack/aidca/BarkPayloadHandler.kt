package tech.freebacktrack.aidca

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * 单一来源的 Bark 风格 payload 处理器。FCM 与未来的 WebSocket 长连接通道都从这里走，
 * 保证两条通道呈现一致：解密、归档、铃声 channel 构建、点击意图、剪贴板、largeIcon、
 * 角标、分组等行为都在这里收敛。
 *
 * 调用方仅需要：
 *  - 决定 `source`（"fcm" | "ws" | ...），用于 debug log 区分；
 *  - 提供 `messageId`（FCM 用 [com.google.firebase.messaging.RemoteMessage.getMessageId]，
 *    WS 用服务端给的 id）；
 *  - 可选传入 `fallbackTitle` / `fallbackBody`（FCM 通知通道下发的 title/body 备选）。
 */
object BarkPayloadHandler {

  /**
   * 处理一个完整 payload，并尝试展示通知。返回展示用的最终 channelId（仅供调用方记录/调试）。
   */
  fun handle(
    context: Context,
    rawData: Map<String, String>,
    source: String,
    messageId: String,
    fallbackTitle: String? = null,
    fallbackBody: String? = null,
  ): String {
    val ctx = context.applicationContext
    val data: Map<String, String> = tryDecryptPayload(ctx, rawData)?.let { rawData + it } ?: rawData

    val title = data["title"]
      ?: fallbackTitle
      ?: ctx.getString(R.string.incoming_message_fallback_title)
    val subtitle = data["subtitle"].orEmpty()
    val body = data["body"]
      ?: data["message"]
      ?: fallbackBody
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

    val eventId = data["eventId"].orEmpty()
    val eventType = data["eventType"].orEmpty()
    val symbol = data["symbol"].orEmpty()
    val strategyName = data["strategyName"].orEmpty()
    val triggerCondition = data["triggerCondition"].orEmpty()
    val purchaseAmount = data["purchaseAmount"].orEmpty()
    val detailUrl = data["detailUrl"].orEmpty()

    val channelId = NotifyMessagingService.ensureChannel(ctx, level, soundName, callMode)

    val expandedBody = buildExpandedBody(body, subtitle, triggerCondition, purchaseAmount, detailUrl)
    val expandedBodyRich: CharSequence =
      if (bodyMd.isNotBlank()) MarkdownRenderer.render(bodyMd) else expandedBody

    DebugLogStore.append(
      ctx,
      source,
      "onMessageReceived id=${messageId.ifBlank { "-" }} title=${title.take(48)} " +
        "level=${level.ifBlank { "-" }} sound=${soundName.ifBlank { "-" }} call=$callMode " +
        "archive=$isArchive group=${group.ifBlank { "-" }} dataKeys=${rawData.keys.joinToString(",")}"
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

    val builder = android.app.Notification.Builder(ctx, channelId)
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
      val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.notify(notificationId(eventId, messageId), notification)
      if (isArchive) NotificationMessageStore.markDisplayed(ctx, eventId, messageId)
      if (callMode) {
        try {
          CallRingService.start(ctx, title, expandedBody)
          DebugLogStore.append(ctx, "call", "call=1 ring service started for ${messageId.ifBlank { "-" }}")
        } catch (e: Exception) {
          DebugLogStore.append(ctx, "call", "call=1 ring service failed: ${e.message ?: "未知错误"}")
        }
      }
      DebugLogStore.append(
        ctx,
        "notify",
        "Notification displayed for message ${messageId.ifBlank { "-" }} channel=$channelId source=$source"
      )
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

    return channelId
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
   * 返回解出的明文字段集（会叠加在原始 data 上）。
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
      DebugLogStore.append(ctx, "payload", "ciphertext decrypted, fields=${out.keys.joinToString(",")}")
      out
    } catch (error: Exception) {
      DebugLogStore.append(ctx, "payload", "ciphertext decrypt failed: ${error.message ?: "未知错误"}")
      null
    }
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
