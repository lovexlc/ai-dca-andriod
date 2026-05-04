package tech.freebacktrack.aidca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class NotifyMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    super.onNewToken(token)
    DebugLogStore.append(applicationContext, "fcm", "onNewToken: ${maskToken(token)}")
    RegistrationRepository.syncFromService(applicationContext, token, "token-refresh")
  }

  override fun onMessageReceived(message: RemoteMessage) {
    super.onMessageReceived(message)
    ensureNotificationChannel(applicationContext)

    val title = message.data["title"]
      ?: message.notification?.title
      ?: getString(R.string.incoming_message_fallback_title)
    val body = message.data["body"]
      ?: message.data["message"]
      ?: message.notification?.body
      ?: "收到一条新的基金通知。"
    val bodyMd = message.data["body_md"].orEmpty()
    val messageId = message.messageId.orEmpty()
    val eventId = message.data["eventId"].orEmpty()
    val eventType = message.data["eventType"].orEmpty()
    val symbol = message.data["symbol"].orEmpty()
    val strategyName = message.data["strategyName"].orEmpty()
    val triggerCondition = message.data["triggerCondition"].orEmpty()
    val purchaseAmount = message.data["purchaseAmount"].orEmpty()
    val detailUrl = message.data["detailUrl"].orEmpty()
    val expandedBody = buildExpandedBody(body, triggerCondition, purchaseAmount, detailUrl)
    val expandedBodyRich: CharSequence =
      if (bodyMd.isNotBlank()) MarkdownRenderer.render(bodyMd) else expandedBody
    DebugLogStore.append(
      applicationContext,
      "fcm",
      "onMessageReceived id=${messageId.ifBlank { "-" }} title=${title.take(48)} notification=${message.notification != null} dataKeys=${message.data.keys.joinToString(",")}"
    )
    NotificationMessageStore.upsertReceived(
      context = applicationContext,
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
    DeliveryReceiptStore.writeReceived(applicationContext, title, expandedBody, messageId)
    val launchIntent = Intent(this, MainActivity::class.java).apply {
      putExtra(MainActivity.EXTRA_EVENT_ID, eventId)
      putExtra(MainActivity.EXTRA_MESSAGE_ID, messageId)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
      this,
      1,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = android.app.Notification.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title)
      .setContentText(buildCompactBody(body, triggerCondition, purchaseAmount))
      .setStyle(
        android.app.Notification.BigTextStyle()
          .setBigContentTitle(title)
          .bigText(expandedBodyRich)
      )
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)
      .build()

    try {
      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.notify(notificationId(eventId, messageId), notification)
      NotificationMessageStore.markDisplayed(applicationContext, eventId, messageId)
      DebugLogStore.append(applicationContext, "notify", "Notification displayed for message ${messageId.ifBlank { "-" }}")
    } catch (error: Exception) {
      NotificationMessageStore.markDisplayError(
        applicationContext,
        eventId,
        messageId,
        error.message ?: "未知错误"
      )
      DebugLogStore.append(
        applicationContext,
        "notify",
        "Notification display failed for message ${messageId.ifBlank { "-" }}: ${error.message ?: "未知错误"}"
      )
      DeliveryReceiptStore.writeDisplayError(
        applicationContext,
        title,
        body,
        messageId,
        error.message ?: "未知错误"
      )
    }
  }

  companion object {
    private const val CHANNEL_ID = "ai_dca_messages"

    fun ensureNotificationChannel(context: Context) {
      if (Build.VERSION.SDK_INT < 26) {
        return
      }

      val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

      if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
        return
      }

      val channel = NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_DEFAULT
      ).apply {
        description = context.getString(R.string.notification_channel_description)
      }

      notificationManager.createNotificationChannel(channel)
      DebugLogStore.append(context.applicationContext, "notify", "Created notification channel $CHANNEL_ID")
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
      triggerCondition: String,
      purchaseAmount: String,
      detailUrl: String
    ): String {
      return buildString {
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
