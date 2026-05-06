package tech.freebacktrack.aidca

import android.content.Context
import org.json.JSONArray

/**
 * 用户可配置的服务器域名列表（bark 风格）。
 * - 默认只有 tools.freebacktrack.tech；用户可以添加任意自建/备用域名。
 * - 仅影响服务器 tab 里 push URL 拼接 + 测试连通；推送本身仍由后端 worker 决定。
 */
object ServerRegistry {
  private const val PREFS = "ai_dca_server_registry"
  private const val KEY_HOSTS = "hosts_v1"
  private const val KEY_SELECTED = "selected_index"
  const val DEFAULT_HOST = "tools.freebacktrack.tech"

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun list(context: Context): List<String> {
    val raw = prefs(context).getString(KEY_HOSTS, null)
    if (raw.isNullOrBlank()) return listOf(DEFAULT_HOST)
    return try {
      val arr = JSONArray(raw)
      val out = mutableListOf<String>()
      for (i in 0 until arr.length()) {
        val h = arr.optString(i).trim()
        if (h.isNotEmpty()) out += h
      }
      if (out.isEmpty()) listOf(DEFAULT_HOST) else out
    } catch (_: Exception) {
      listOf(DEFAULT_HOST)
    }
  }

  fun selectedIndex(context: Context): Int {
    val idx = prefs(context).getInt(KEY_SELECTED, 0)
    val size = list(context).size
    return if (idx in 0 until size) idx else 0
  }

  fun currentHost(context: Context): String =
    list(context).getOrNull(selectedIndex(context)) ?: DEFAULT_HOST

  /** Returns "https://<host>/api/notify" base for quick / register / ws endpoints. */
  fun currentApiBase(context: Context): String =
    "https://" + currentHost(context) + "/api/notify"

  fun select(context: Context, index: Int) {
    val size = list(context).size
    val safe = if (index in 0 until size) index else 0
    prefs(context).edit().putInt(KEY_SELECTED, safe).apply()
  }

  /** Returns the sanitized host that was added/selected, or null if input was invalid. */
  fun addAndSelect(context: Context, host: String): String? {
    val sanitized = sanitize(host) ?: return null
    val current = list(context).toMutableList()
    if (sanitized !in current) {
      current += sanitized
      writeList(context, current)
    }
    select(context, current.indexOf(sanitized))
    return sanitized
  }

  /** Returns true if host was removed; false if last one (cannot delete) or out of range. */
  fun removeAt(context: Context, index: Int): Boolean {
    val current = list(context).toMutableList()
    if (current.size <= 1) return false
    if (index !in current.indices) return false
    current.removeAt(index)
    writeList(context, current)
    val selected = selectedIndex(context)
    if (selected >= current.size) select(context, 0)
    return true
  }

  private fun writeList(context: Context, hosts: List<String>) {
    val arr = JSONArray()
    for (h in hosts) arr.put(h)
    prefs(context).edit().putString(KEY_HOSTS, arr.toString()).apply()
  }

  /** Strip scheme / trailing slash, validate as a plausible host (allowing optional :port). */
  fun sanitize(input: String): String? {
    val s = input.trim()
      .removePrefix("https://")
      .removePrefix("http://")
      .removeSuffix("/")
      .trim()
    if (s.isEmpty()) return null
    if (!s.matches(Regex("^[A-Za-z0-9.\\-]+(:[0-9]+)?$"))) return null
    if ("." !in s) return null
    return s
  }
}
