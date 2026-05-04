package tech.freebacktrack.aidca

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NotificationMessageRecord(
  val localId: String,
  val eventId: String,
  val messageId: String,
  val eventType: String,
  val receivedAt: String,
  val title: String,
  val body: String,
  val bodyMd: String,
  val triggerCondition: String,
  val purchaseAmount: String,
  val detailUrl: String,
  val symbol: String,
  val strategyName: String,
  val notificationStatus: String,
  val notificationError: String
)

object NotificationMessageStore {
  private const val PREFERENCES_NAME = "ai_dca_notify_state"
  private const val MESSAGES_KEY = "notification_messages"
  private const val MAX_MESSAGES = 30

  fun readAll(context: Context): List<NotificationMessageRecord> {
    val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val rawValue = preferences.getString(MESSAGES_KEY, null) ?: return emptyList()

    return try {
      val payload = JSONArray(rawValue)
      buildList {
        for (index in 0 until payload.length()) {
          val item = payload.optJSONObject(index) ?: continue
          add(
            NotificationMessageRecord(
              localId = item.optString("localId"),
              eventId = item.optString("eventId"),
              messageId = item.optString("messageId"),
              eventType = item.optString("eventType"),
              receivedAt = item.optString("receivedAt"),
              title = item.optString("title"),
              body = item.optString("body"),
              bodyMd = item.optString("bodyMd"),
              triggerCondition = item.optString("triggerCondition"),
              purchaseAmount = item.optString("purchaseAmount"),
              detailUrl = item.optString("detailUrl"),
              symbol = item.optString("symbol"),
              strategyName = item.optString("strategyName"),
              notificationStatus = item.optString("notificationStatus"),
              notificationError = item.optString("notificationError")
            )
          )
        }
      }
    } catch (_error: Exception) {
      emptyList()
    }
  }

  fun upsertReceived(
    context: Context,
    eventId: String,
    messageId: String,
    eventType: String,
    title: String,
    body: String,
    bodyMd: String,
    triggerCondition: String,
    purchaseAmount: String,
    detailUrl: String,
    symbol: String,
    strategyName: String
  ): NotificationMessageRecord {
    val incoming = NotificationMessageRecord(
      localId = buildLocalId(eventId, messageId),
      eventId = eventId,
      messageId = messageId,
      eventType = eventType,
      receivedAt = nowLabel(),
      title = title,
      body = body,
      bodyMd = bodyMd,
      triggerCondition = triggerCondition,
      purchaseAmount = purchaseAmount,
      detailUrl = detailUrl,
      symbol = symbol,
      strategyName = strategyName,
      notificationStatus = "received",
      notificationError = ""
    )

    val nextMessages = mutableListOf<NotificationMessageRecord>()
    var replaced = false

    for (record in readAll(context)) {
      if (isSameMessage(record, incoming)) {
        nextMessages.add(incoming)
        replaced = true
      } else {
        nextMessages.add(record)
      }
    }

    if (!replaced) {
      nextMessages.add(0, incoming)
    }

    writeAll(context, nextMessages)
    return incoming
  }

  fun markDisplayed(context: Context, eventId: String, messageId: String) {
    updateStatus(context, eventId, messageId, "displayed", "")
  }

  fun markDisplayError(context: Context, eventId: String, messageId: String, errorMessage: String) {
    updateStatus(context, eventId, messageId, "display-error", errorMessage)
  }

  fun removeByLocalId(context: Context, localId: String): Boolean {
    val target = localId.trim()
    if (target.isBlank()) return false
    val current = readAll(context)
    val next = current.filterNot { it.localId == target }
    if (next.size == current.size) return false
    writeAll(context, next)
    return true
  }

  fun clearAll(context: Context) {
    context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit()
      .remove(MESSAGES_KEY)
      .apply()
  }

  private fun updateStatus(
    context: Context,
    eventId: String,
    messageId: String,
    status: String,
    errorMessage: String
  ) {
    val targetEventId = eventId.trim()
    val targetMessageId = messageId.trim()
    val nextMessages = readAll(context).map { record ->
      val matchedByEvent = targetEventId.isNotBlank() && record.eventId == targetEventId
      val matchedByMessage = targetEventId.isBlank() && targetMessageId.isNotBlank() && record.messageId == targetMessageId

      if (!matchedByEvent && !matchedByMessage) {
        record
      } else {
        record.copy(
          notificationStatus = status,
          notificationError = errorMessage
        )
      }
    }

    writeAll(context, nextMessages)
  }

  private fun writeAll(context: Context, messages: List<NotificationMessageRecord>) {
    val payload = JSONArray()

    for (record in messages.take(MAX_MESSAGES)) {
      payload.put(
        JSONObject()
          .put("localId", record.localId)
          .put("eventId", record.eventId)
          .put("messageId", record.messageId)
          .put("eventType", record.eventType)
          .put("receivedAt", record.receivedAt)
          .put("title", record.title)
          .put("body", record.body)
          .put("bodyMd", record.bodyMd)
          .put("triggerCondition", record.triggerCondition)
          .put("purchaseAmount", record.purchaseAmount)
          .put("detailUrl", record.detailUrl)
          .put("symbol", record.symbol)
          .put("strategyName", record.strategyName)
          .put("notificationStatus", record.notificationStatus)
          .put("notificationError", record.notificationError)
      )
    }

    context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(MESSAGES_KEY, payload.toString())
      .apply()
  }

  private fun isSameMessage(left: NotificationMessageRecord, right: NotificationMessageRecord): Boolean {
    if (left.eventId.isNotBlank() && right.eventId.isNotBlank()) {
      return left.eventId == right.eventId
    }

    if (left.messageId.isNotBlank() && right.messageId.isNotBlank()) {
      return left.messageId == right.messageId
    }

    return left.localId == right.localId
  }

  private fun buildLocalId(eventId: String, messageId: String): String {
    return eventId.trim()
      .ifBlank { messageId.trim() }
      .ifBlank { "msg-${System.currentTimeMillis()}" }
  }

  private fun nowLabel(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
  }
}
