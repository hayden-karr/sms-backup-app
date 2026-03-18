package com.heideen.smsbackup.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.heideen.smsbackup.model.MmsMessage
import com.heideen.smsbackup.model.SmsMessage
import com.heideen.smsbackup.util.StoragePrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class BackupResult(
    val filename: String,
    val smsCount: Int,
    val mmsCount: Int,
    val errors: Int = 0
)

data class BackupFileInfo(
    val name: String,
    val file: File?,
    val safUri: Uri?,
    val sizeBytes: Long,
    val createdAt: Long
)

class BackupManager(private val context: Context) {

  private val repo = SmsRepository(context)
  private val prefs = StoragePrefs(context)

  suspend fun backup(onProgress: suspend (Float, String) -> Unit): Result<BackupResult> {
    return try {
      val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
      val filename = "sms_backup_$timestamp.db"

      onProgress(0.05f, "Reading SMS messages...")
      val smsList = repo.readAllSms()

      when (prefs.mode) {
        StoragePrefs.MODE_CUSTOM -> backupToSaf(filename, smsList, onProgress)
        else -> backupToFile(filename, smsList, onProgress)
      }
    } catch (e: CancellationException) {
      throw e // let ViewModel handle cleanly — don't wrap as an error
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  private suspend fun backupToFile(
      filename: String,
      smsList: List<SmsMessage>,
      onProgress: suspend (Float, String) -> Unit
  ): Result<BackupResult> {
    val dir =
        prefs.getBackupDirectory(context)
            ?: return Result.failure(IllegalStateException("Storage location not available"))

    if (!dir.exists() && !dir.mkdirs()) {
      return Result.failure(IllegalStateException("Could not create backup directory"))
    }

    val file = File(dir, filename)
    var succeeded = false
    // Single connection for the entire backup — re-opening after a large SMS write triggers
    // WAL/journal reconciliation that blocks beginTransaction() for the MMS phase.
    return try {
      BackupDatabase(file).open().use { db ->
        db.initialize()

        db.insertSms(smsList) { localProgress, msg ->
          onProgress(0.5f + localProgress * 0.18f, msg)
        }

        val mmsTotal = repo.getMmsCount()
        onProgress(0.7f, "Writing $mmsTotal MMS to backup...")
        val (mmsInserted, mmsErrors) = backupMmsWithChannel(db, onProgress, mmsTotal)

        listOf("-journal", "-wal", "-shm").forEach { suffix ->
          File(dir, "$filename$suffix").delete()
        }
        onProgress(1.0f, "Backup complete")
        succeeded = true
        Result.success(BackupResult(filename, smsList.size, mmsInserted, mmsErrors))
      }
    } catch (e: CancellationException) {
      throw e // finally will clean up, then this propagates to backup() which rethrows
    } catch (e: Exception) {
      Result.failure(e)
    } finally {
      if (!succeeded) {
        file.delete()
        listOf("-journal", "-wal", "-shm").forEach { File(dir, "$filename$it").delete() }
      }
    }
  }

  private suspend fun backupToSaf(
      filename: String,
      smsList: List<SmsMessage>,
      onProgress: suspend (Float, String) -> Unit
  ): Result<BackupResult> {
    val treeUri =
        prefs.getCustomUri()
            ?: return Result.failure(
                IllegalStateException("Custom storage location not set. Re-select it in settings."))

    val docTree =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: return Result.failure(
                IllegalStateException(
                    "Cannot access custom storage location. Try re-selecting it in settings."))

    val tempFile = File(context.cacheDir, filename)
    return try {
      BackupDatabase(tempFile).open().use { db ->
        db.initialize()

        onProgress(0.5f, "Writing ${smsList.size} SMS to backup...")
        db.insertSms(smsList)

        val mmsTotal = repo.getMmsCount()
        onProgress(0.7f, "Writing $mmsTotal MMS to backup...")
        val (mmsInserted, mmsErrors) = backupMmsWithChannel(db, onProgress, mmsTotal)

        onProgress(0.95f, "Copying to selected storage location...")
        val docFile =
            docTree.createFile("application/octet-stream", filename)
                ?: return Result.failure(
                    IllegalStateException("Cannot create file in selected storage location"))

        context.contentResolver.openOutputStream(docFile.uri)?.use { out ->
          tempFile.inputStream().use { it.copyTo(out) }
        }

        onProgress(1.0f, "Backup complete")
        Result.success(BackupResult(filename, smsList.size, mmsInserted, mmsErrors))
      }
    } finally {
      tempFile.delete()
    }
  }

  private suspend fun backupMmsWithChannel(
      db: BackupDatabase,
      onProgress: suspend (Float, String) -> Unit,
      mmsTotal: Int
  ): Pair<Int, Int> = coroutineScope {
    val channel = Channel<MmsMessage>(capacity = 20)

    val count = AtomicInteger(0)
    val producer = launch {
      var cause: Exception? = null
      try {
        repo.forEachMms { msg ->
          channel.send(msg)
          val c = count.incrementAndGet()
          if (c % 25 == 0 || c == mmsTotal) {
            val progress = 0.7f + (c.toFloat() / mmsTotal.coerceAtLeast(1)) * 0.23f
            onProgress(progress, "Writing MMS $c/$mmsTotal...")
          }
        }
      } catch (e: CancellationException) {
        cause = e
        throw e
      } catch (e: Exception) {
        cause = e
      } finally {
        channel.close(cause)
      }
    }

    val (inserted, errors) = db.insertMmsWithChannel(channel)
    producer.join()
    inserted to errors
  }

  fun listBackups(): List<BackupFileInfo> {
    return when (prefs.mode) {
      StoragePrefs.MODE_CUSTOM -> listBackupsSaf()
      else -> listBackupsLocal()
    }
  }

  private fun listBackupsLocal(): List<BackupFileInfo> {
    val dir = prefs.getBackupDirectory(context) ?: return emptyList()
    return dir.listFiles { f -> f.name.startsWith("sms_backup_") && f.name.endsWith(".db") }
        ?.map { f ->
          BackupFileInfo(
              name = f.name,
              file = f,
              safUri = null,
              sizeBytes = f.length(),
              createdAt = f.lastModified())
        }
        ?.sortedByDescending { it.createdAt } ?: emptyList()
  }

  private fun listBackupsSaf(): List<BackupFileInfo> {
    val treeUri = prefs.getCustomUri() ?: return emptyList()
    val docTree = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
    return docTree
        .listFiles()
        .filter { it.name?.startsWith("sms_backup_") == true && it.name?.endsWith(".db") == true }
        .map { doc ->
          BackupFileInfo(
              name = doc.name ?: "backup.db",
              file = null,
              safUri = doc.uri,
              sizeBytes = doc.length(),
              createdAt = doc.lastModified())
        }
        .sortedByDescending { it.createdAt }
  }
}
