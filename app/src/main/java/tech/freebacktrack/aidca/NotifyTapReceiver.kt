package tech.freebacktrack.aidca

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Receives notification taps so the app can ACK "opened" before routing to app or browser. */
class NotifyTapReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val appContext = context.applicationContext
    val eventId = intent?.getStringExtra(EXTRA_EVENT_ID).orEmpty()
    val messageId = intent?.getStringExtra(EXTRA_MESSAGE_ID).orEmpty()
    val source = intent?.getStringExtra(EXTRA_SOURCE).orEmpty().ifBlank { "local" }
    val url = intent?.getStringExtra(EXTRA_URL).orEmpty()

    if (eventId.isNotBlank() || messageId.isNotBlank()) {
      DeliveryAckReporter.report(appContext, source, "opened", eventId, messageId)
      DebugLogStore.append(appContext, "ack", "opened ack from tap messageId=${messageId.ifBlank { eventId }}")
    }

    val targetIntent = if (url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
      Intent(Intent.ACTION_VIEW, Uri.parse(url))
    } else {
      Intent(appContext, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_EVENT_ID, eventId)
        putExtra(MainActivity.EXTRA_MESSAGE_ID, messageId)
        putExtra(MainActivity.EXTRA_OPENED_ACK_REPORTED, true)
      }
    }.apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    try {
      appContext.startActivity(targetIntent)
    } catch (error: Exception) {
      DebugLogStore.append(appContext, "ack", "tap route failed: ${error.message ?: "-"}")
    }
  }

  companion object {
    const val ACTION_OPEN_NOTIFICATION = "tech.freebacktrack.aidca.action.OPEN_NOTIFICATION"
    const val EXTRA_EVENT_ID = "extra_event_id"
    const val EXTRA_MESSAGE_ID = "extra_message_id"
    const val EXTRA_URL = "extra_url"
    const val EXTRA_SOURCE = "extra_source"
  }
}
