package tech.freebacktrack.aidca

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

/**
 * 内置铃声库。Bark 的 `sound=<name>` 字段会被 Android 端查询 res/raw/<name>.* 资源；
 * 这里提供：
 *  - 枚举当前 apk 包内可用的 sound 名，以及三个系统默认入口（alarm/notification/ringtone）供试听。
 *  - 试听 API：[preview]，[stop]。
 *  - 复制 sound 名到剪贴板 API：[copySoundName]。
 *
 * res/raw 目前为空。用户可以自行在 `app/src/main/res/raw/` 里放入 `.mp3`/`.ogg`/`.wav`，
 * 并在 push 时带上 `sound=<文件名不带后缀>` 即可生效。
 */
object SoundLibrary {
  data class Entry(
    /** sound 发布名，如 `bell`。为空串时是“使用默认”入口。 */
    val name: String,
    /** 展示名。 */
    val displayName: String,
    /** 实际试听使用的 URI。为 null 表示不可试听。 */
    val previewUri: Uri?,
    /** 试听时使用的 AudioAttributes usage。 */
    val usage: Int = AudioAttributes.USAGE_NOTIFICATION,
  )

  private var current: MediaPlayer? = null

  /**
   * 查询当前 apk 带的 res/raw 资源 + 三个系统默认铃声。
   */
  fun list(context: Context): List<Entry> {
    val ctx = context.applicationContext
    val out = mutableListOf<Entry>()
    // res/raw 中的资源：反射 R.raw 下面的 int 字段。
    try {
      val rawClass = Class.forName("${ctx.packageName}.R\$raw")
      for (field in rawClass.fields) {
        try {
          val resId = field.getInt(null)
          val name = field.name
          val uri = Uri.parse("android.resource://${ctx.packageName}/$resId")
          out += Entry(name = name, displayName = name, previewUri = uri)
        } catch (_: Throwable) {
          // skip
        }
      }
    } catch (_: Throwable) {
      // R.raw 不存在（raw 目录为空时可能会发生）。忽略。
    }
    out.sortBy { it.displayName }
    // 系统默认铃声 fallback。
    out += Entry(
      name = "alarm",
      displayName = "默认闹钟·USAGE_ALARM",
      previewUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
      usage = AudioAttributes.USAGE_ALARM,
    )
    out += Entry(
      name = "notification",
      displayName = "默认提示·USAGE_NOTIFICATION",
      previewUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
      usage = AudioAttributes.USAGE_NOTIFICATION,
    )
    out += Entry(
      name = "ringtone",
      displayName = "默认铃声·USAGE_NOTIFICATION_RINGTONE",
      previewUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
      usage = AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
    )
    return out
  }

  /**
   * 试听一条铃声；如果有上一条正在播放，先 stop。
   */
  fun preview(context: Context, entry: Entry) {
    stop()
    val uri = entry.previewUri ?: return
    try {
      current = MediaPlayer().apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(entry.usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
        )
        setDataSource(context.applicationContext, uri)
        isLooping = false
        prepare()
        start()
        setOnCompletionListener {
          try { it.release() } catch (_: Throwable) {}
          if (current === it) current = null
        }
      }
    } catch (_: Throwable) {
      stop()
    }
  }

  fun stop() {
    val mp = current ?: return
    try { mp.stop() } catch (_: Throwable) {}
    try { mp.release() } catch (_: Throwable) {}
    current = null
  }

  fun copySoundName(context: Context, name: String) {
    if (name.isBlank()) return
    try {
      val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      cm.setPrimaryClip(ClipData.newPlainText("bark sound", "sound=$name"))
    } catch (_: Throwable) {}
  }
}
