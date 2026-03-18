package com.heideen.smsbackup.util

import android.content.Context
import android.net.Uri
import java.io.File

class StoragePrefs(context: Context) {

  private val prefs = context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE)

  var mode: String
    get() = prefs.getString("mode", MODE_APP_EXTERNAL) ?: MODE_APP_EXTERNAL
    set(value) = prefs.edit().putString("mode", value).apply()

  var customUri: String?
    get() = prefs.getString("custom_uri", null)
    set(value) = prefs.edit().putString("custom_uri", value).apply()

  fun getBackupDirectory(context: Context): File? {
    return when (mode) {
      MODE_CUSTOM -> null
      else -> context.getExternalFilesDir(null)
    }
  }

  fun getCustomUri(): Uri? = customUri?.let { Uri.parse(it) }

  fun describeLocation(context: Context): String {
    return when (mode) {
      MODE_CUSTOM ->
          customUri?.let {
            Uri.parse(it).lastPathSegment?.substringAfterLast(':') ?: "Custom folder"
          } ?: "Custom folder (not set)"
      else -> "App storage (Android/data/com.heideen.smsbackup/files)"
    }
  }

  companion object {
    const val MODE_APP_EXTERNAL = "app_external"
    const val MODE_CUSTOM = "custom"
  }
}
