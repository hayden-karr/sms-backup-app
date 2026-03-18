package com.heideen.smsbackup.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class ExportResult(val exported: Int, val skipped: Int)

class AttachmentExporter(private val context: Context) {

  suspend fun export(
      backupFile: File,
      outputTreeUri: Uri,
      onProgress: suspend (Float, String) -> Unit
  ): ExportResult {
    val docTree =
        DocumentFile.fromTreeUri(context, outputTreeUri)
            ?: throw IllegalStateException("Cannot access output folder")

    var exported = 0
    var skipped = 0
    var processed = 0

    BackupDatabase(backupFile).open().use { db ->
      val total = db.getMediaAttachmentCount()
      if (total == 0) return ExportResult(0, 0)

      db.forEachMediaAttachment { att ->
        processed++
        val ext = mimeToExt(att.contentType)
        val filename =
            att.filename?.takeIf { it.isNotBlank() } ?: "mms${att.mmsId}_att${att.id}.$ext"

        val mimeType = att.contentType ?: "application/octet-stream"
        val docFile = docTree.createFile(mimeType, filename)
        if (docFile == null) {
          skipped++
        } else {
          try {
            context.contentResolver.openOutputStream(docFile.uri)?.use { out ->
              db.writeBlobToStream(att.id, out)
            }
            exported++
          } catch (_: Exception) {
            docFile.delete()
            skipped++
          }
        }

        onProgress(processed.toFloat() / total, "Exporting $processed/$total attachments...")
      }
    }

    onProgress(1.0f, "Export complete")
    return ExportResult(exported, skipped)
  }

  private fun mimeToExt(mimeType: String?): String =
      when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "video/mp4" -> "mp4"
        "video/3gpp" -> "3gp"
        "video/webm" -> "webm"
        "audio/mpeg" -> "mp3"
        "audio/aac" -> "aac"
        "audio/amr" -> "amr"
        "audio/ogg" -> "ogg"
        else -> "bin"
      }
}
