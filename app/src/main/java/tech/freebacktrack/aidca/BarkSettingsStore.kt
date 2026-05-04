package tech.freebacktrack.aidca

import android.content.Context

/**
 * Bark 风格推送的本机偏好设置：
 * - defaultArchive：未在推送中显式指定 isArchive 时是否入库
 * - encryptionKey：用于解密 ciphertext 字段的 AES 密钥（用户在设置页录入）
 */
object BarkSettingsStore {
  private const val PREFS = "bark_settings"
  private const val KEY_DEFAULT_ARCHIVE = "default_archive"
  private const val KEY_ENCRYPTION_KEY = "encryption_key"

  fun defaultArchive(context: Context): Boolean {
    return context.applicationContext
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getBoolean(KEY_DEFAULT_ARCHIVE, true)
  }

  fun setDefaultArchive(context: Context, value: Boolean) {
    context.applicationContext
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit().putBoolean(KEY_DEFAULT_ARCHIVE, value).apply()
  }

  fun encryptionKey(context: Context): String {
    return context.applicationContext
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getString(KEY_ENCRYPTION_KEY, "").orEmpty()
  }

  fun setEncryptionKey(context: Context, value: String) {
    context.applicationContext
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit().putString(KEY_ENCRYPTION_KEY, value.trim()).apply()
  }
}
