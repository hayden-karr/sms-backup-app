package com.heideen.smsbackup.ui

import android.app.Application
import android.net.Uri
import android.provider.Telephony
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.heideen.smsbackup.backup.AttachmentExporter
import com.heideen.smsbackup.backup.BackupFileInfo
import com.heideen.smsbackup.backup.BackupManager
import com.heideen.smsbackup.backup.BackupResult
import com.heideen.smsbackup.backup.ExportResult
import com.heideen.smsbackup.backup.RestoreManager
import com.heideen.smsbackup.backup.RestoreMode
import com.heideen.smsbackup.backup.RestoreResult
import com.heideen.smsbackup.util.StoragePrefs
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class OperationState {
  object Idle : OperationState()

  data class InProgress(val progress: Float, val message: String) : OperationState()

  object Cancelling : OperationState()

  data class BackupDone(val result: BackupResult) : OperationState()

  data class RestoreDone(val result: RestoreResult) : OperationState()

  data class ExportDone(val result: ExportResult) : OperationState()

  data class Error(val message: String) : OperationState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val backupManager = BackupManager(application)
  private val restoreManager = RestoreManager(application)
  val prefs = StoragePrefs(application)

  private val _state = MutableStateFlow<OperationState>(OperationState.Idle)
  val state: StateFlow<OperationState> = _state.asStateFlow()

  private var currentJob: Job? = null

  private val _backupFiles = MutableStateFlow<List<BackupFileInfo>>(emptyList())
  val backupFiles: StateFlow<List<BackupFileInfo>> = _backupFiles.asStateFlow()

  private val _storageDescription = MutableStateFlow(prefs.describeLocation(application))
  val storageDescription: StateFlow<String> = _storageDescription.asStateFlow()

  fun updateStorageMode(mode: String, customUri: String? = null) {
    prefs.mode = mode
    if (customUri != null) prefs.customUri = customUri
    _storageDescription.value = prefs.describeLocation(getApplication())
    refreshBackupList()
  }

  fun isDefaultSmsApp(): Boolean {
    val pkg = Telephony.Sms.getDefaultSmsPackage(getApplication())
    return pkg == getApplication<Application>().packageName
  }

  fun refreshBackupList() {
    _backupFiles.value = backupManager.listBackups()
  }

  fun cancelCurrentOperation() {
    currentJob?.cancel()
    // Don't null currentJob here — the coroutine's finally block does that once truly done,
    // which prevents starting a new operation before the old one has fully stopped.
    _state.value = OperationState.Cancelling
  }

  private fun progressUpdate(progress: Float, message: String) {
    // Only post progress updates while actively running — not while cancelling.
    if (_state.value is OperationState.InProgress) {
      _state.value = OperationState.InProgress(progress, message)
    }
  }

  private fun finishOperation() {
    if (_state.value is OperationState.Cancelling) {
      _state.value = OperationState.Idle
    }
    currentJob = null
    refreshBackupList()
  }

  fun startBackup() {
    if (_state.value !is OperationState.Idle) return
    _state.value = OperationState.InProgress(0f, "Starting backup...")
    currentJob =
        viewModelScope.launch(Dispatchers.IO) {
          try {
            backupManager
                .backup { progress, message -> progressUpdate(progress, message) }
                .onSuccess { result -> _state.value = OperationState.BackupDone(result) }
                .onFailure { e ->
                  _state.value = OperationState.Error(e.message ?: "Backup failed")
                }
          } catch (_: CancellationException) {
            // finishOperation() below handles the Cancelling → Idle transition
          } finally {
            finishOperation()
          }
        }
  }

  fun startRestore(info: BackupFileInfo, mode: RestoreMode) {
    if (_state.value !is OperationState.Idle) return
    _state.value = OperationState.InProgress(0f, "Starting restore...")
    currentJob =
        viewModelScope.launch(Dispatchers.IO) {
          try {
            val (file, isTempFile) = resolveToFile(info)
            if (file == null) {
              _state.value = OperationState.Error("Cannot access backup file")
              return@launch
            }
            restoreManager
                .restore(file, mode) { progress, message -> progressUpdate(progress, message) }
                .onSuccess { result -> _state.value = OperationState.RestoreDone(result) }
                .onFailure { e ->
                  _state.value = OperationState.Error(e.message ?: "Restore failed")
                }
            if (isTempFile) file.delete()
          } catch (_: CancellationException) {
            // finishOperation() below handles the Cancelling → Idle transition
          } finally {
            finishOperation()
          }
        }
  }

  private fun resolveToFile(info: BackupFileInfo): Pair<File?, Boolean> {
    if (info.file != null) return Pair(info.file, false)
    val uri = info.safUri ?: return Pair(null, false)
    val tempFile = File(getApplication<Application>().cacheDir, info.name)
    return try {
      getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { input.copyTo(it) }
      }
      Pair(tempFile, true)
    } catch (_: Exception) {
      Pair(null, false)
    }
  }

  fun exportAttachments(info: BackupFileInfo, outputUri: Uri) {
    if (_state.value !is OperationState.Idle) return
    _state.value = OperationState.InProgress(0f, "Preparing export...")
    currentJob =
        viewModelScope.launch(Dispatchers.IO) {
          var file: File? = null
          var isTemp = false
          try {
            val resolved = resolveToFile(info)
            file = resolved.first
            isTemp = resolved.second
            if (file == null) {
              _state.value = OperationState.Error("Cannot access backup file")
              return@launch
            }
            val result =
                AttachmentExporter(getApplication()).export(file, outputUri) { progress, message ->
                  progressUpdate(progress, message)
                }
            _state.value = OperationState.ExportDone(result)
          } catch (_: CancellationException) {
            // finishOperation() below handles the Cancelling → Idle transition
          } catch (e: Exception) {
            _state.value = OperationState.Error(e.message ?: "Export failed")
          } finally {
            if (isTemp) file?.delete()
            finishOperation()
          }
        }
  }

  fun resetState() {
    _state.value = OperationState.Idle
  }

  fun deleteBackup(info: BackupFileInfo) {
    if (info.file != null) {
      info.file.delete()
      deleteSqliteArtifacts(info.file)
    } else if (info.safUri != null) {
      DocumentFile.fromSingleUri(getApplication(), info.safUri)?.delete()
    }
    refreshBackupList()
  }

  private fun deleteSqliteArtifacts(dbFile: File) {
    listOf("-journal", "-wal", "-shm").forEach { suffix ->
      File(dbFile.parent, "${dbFile.name}$suffix").delete()
    }
  }
}
