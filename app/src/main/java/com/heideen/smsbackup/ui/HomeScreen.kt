package com.heideen.smsbackup.ui

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.heideen.smsbackup.backup.BackupFileInfo
import com.heideen.smsbackup.util.StoragePrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel, onRequestPermissions: () -> Unit) {
  val context = LocalContext.current
  val state by viewModel.state.collectAsState()
  val backupFiles by viewModel.backupFiles.collectAsState()
  val storageDescription by viewModel.storageDescription.collectAsState()

  var showRestoreWizard by remember { mutableStateOf(false) }
  var showStorageDialog by remember { mutableStateOf(false) }
  var pendingExportFile by remember { mutableStateOf<BackupFileInfo?>(null) }

  val folderPickerLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
          context.contentResolver.takePersistableUriPermission(
              uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
          viewModel.updateStorageMode(StoragePrefs.MODE_CUSTOM, uri.toString())
        }
      }

  val exportFolderPickerLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && pendingExportFile != null) {
          context.contentResolver.takePersistableUriPermission(
              uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
          viewModel.exportAttachments(pendingExportFile!!, uri)
          pendingExportFile = null
        }
      }

  LaunchedEffect(Unit) { viewModel.refreshBackupList() }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("SMS Backup") },
            actions = {
              IconButton(onClick = { showStorageDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Storage settings")
              }
            })
      }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
              item { Spacer(Modifier.height(4.dp)) }

              item {
                StorageLocationCard(
                    location = storageDescription, onChangeLocation = { showStorageDialog = true })
              }

              item {
                when (val s = state) {
                  is OperationState.Idle -> {
                    ActionCards(
                        onBackup = {
                          onRequestPermissions()
                          viewModel.startBackup()
                        },
                        onRestore = {
                          onRequestPermissions()
                          showRestoreWizard = true
                        })
                  }
                  is OperationState.InProgress -> {
                    ProgressCard(
                        s.progress,
                        s.message,
                        cancelling = false,
                        onCancel = { viewModel.cancelCurrentOperation() })
                  }
                  is OperationState.Cancelling -> {
                    ProgressCard(0f, "Cancelling...", cancelling = true, onCancel = {})
                  }
                  is OperationState.BackupDone -> {
                    ResultCard(
                        title = "Backup complete",
                        body =
                            buildString {
                              append(
                                  "Saved ${s.result.smsCount} SMS and ${s.result.mmsCount} MMS\nFile: ${s.result.filename}")
                              if (s.result.errors > 0)
                                  append(
                                      "\n${s.result.errors} message(s) could not be read and were skipped")
                            },
                        onDismiss = { viewModel.resetState() })
                  }
                  is OperationState.RestoreDone -> {
                    ResultCard(
                        title = "Restore complete",
                        body =
                            buildString {
                              append(
                                  "Inserted: ${s.result.smsInserted} SMS, ${s.result.mmsInserted} MMS\nSkipped (already existed): ${s.result.smsSkipped} SMS, ${s.result.mmsSkipped} MMS")
                              val totalErrors = s.result.smsErrors + s.result.mmsErrors
                              if (totalErrors > 0)
                                  append("\n$totalErrors message(s) could not be restored")
                            },
                        onDismiss = { viewModel.resetState() })
                  }
                  is OperationState.ExportDone -> {
                    ResultCard(
                        title = "Export complete",
                        body =
                            "Exported ${s.result.exported} attachment(s) to chosen folder" +
                                if (s.result.skipped > 0)
                                    "\n${s.result.skipped} skipped (empty or unsupported)"
                                else "",
                        onDismiss = { viewModel.resetState() })
                  }
                  is OperationState.Error -> {
                    ErrorCard(s.message, onDismiss = { viewModel.resetState() })
                  }
                }
              }

              if (backupFiles.isNotEmpty()) {
                item {
                  Text(
                      "Saved backups",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.SemiBold,
                      modifier = Modifier.padding(top = 8.dp))
                }
                items(backupFiles) { info ->
                  BackupFileCard(
                      info = info,
                      onRestore = {
                        onRequestPermissions()
                        showRestoreWizard = true
                      },
                      onExportMedia = {
                        pendingExportFile = info
                        exportFolderPickerLauncher.launch(null)
                      },
                      onDelete = { viewModel.deleteBackup(info) })
                }
              }

              item { Spacer(Modifier.height(16.dp)) }
            }
      }

  if (showRestoreWizard) {
    RestoreWizard(
        viewModel = viewModel, backupFiles = backupFiles, onDismiss = { showRestoreWizard = false })
  }

  if (showStorageDialog) {
    StorageLocationDialog(
        currentMode = viewModel.prefs.mode,
        onSelectAppExternal = {
          viewModel.updateStorageMode(StoragePrefs.MODE_APP_EXTERNAL)
          showStorageDialog = false
        },
        onSelectCustom = {
          showStorageDialog = false
          folderPickerLauncher.launch(null)
        },
        onDismiss = { showStorageDialog = false })
  }
}

@Composable
private fun StorageLocationCard(location: String, onChangeLocation: () -> Unit) {
  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("Storage location", style = MaterialTheme.typography.labelMedium)
            Text(location, style = MaterialTheme.typography.bodySmall)
          }
          TextButton(onClick = onChangeLocation) { Text("Change") }
        }
  }
}

@Composable
private fun ActionCards(onBackup: () -> Unit, onRestore: () -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    ElevatedButton(onClick = onBackup, modifier = Modifier.weight(1f).height(64.dp)) {
      Text("Backup Now", fontWeight = FontWeight.SemiBold)
    }
    OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f).height(64.dp)) {
      Text("Restore", fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun ProgressCard(
    progress: Float,
    message: String,
    cancelling: Boolean,
    onCancel: () -> Unit
) {
  Card(modifier = Modifier.fillMaxWidth().alpha(if (cancelling) 0.6f else 1f)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(message, style = MaterialTheme.typography.bodyMedium)
      if (cancelling) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      } else {
        LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
      }
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (cancelling) "" else "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onCancel, enabled = !cancelling) { Text("Cancel") }
          }
    }
  }
}

@Composable
private fun ResultCard(title: String, body: String, onDismiss: () -> Unit) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      colors =
          CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
              title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(body, style = MaterialTheme.typography.bodySmall)
          TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
      }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
              "Error",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold)
          Text(message, style = MaterialTheme.typography.bodySmall)
          TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
      }
}

@Composable
private fun BackupFileCard(
    info: BackupFileInfo,
    onRestore: () -> Unit,
    onExportMedia: () -> Unit,
    onDelete: () -> Unit
) {
  val context = LocalContext.current
  val date = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(info.createdAt))
  val size = Formatter.formatShortFileSize(context, info.sizeBytes)
  var showDeleteConfirm by remember { mutableStateOf(false) }

  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(
                info.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium)
            Text("$date  |  $size", style = MaterialTheme.typography.bodySmall)
          }
          TextButton(onClick = onRestore) { Text("Restore") }
          TextButton(onClick = onExportMedia) { Text("Export media") }
          IconButton(onClick = { showDeleteConfirm = true }) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete backup",
                tint = MaterialTheme.colorScheme.error)
          }
        }
  }

  if (showDeleteConfirm) {
    AlertDialog(
        onDismissRequest = { showDeleteConfirm = false },
        title = { Text("Delete backup?") },
        text = { Text("Delete ${info.name}? This cannot be undone.") },
        confirmButton = {
          TextButton(
              onClick = {
                showDeleteConfirm = false
                onDelete()
              }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
              }
        },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } })
  }
}

@Composable
private fun StorageLocationDialog(
    currentMode: String,
    onSelectAppExternal: () -> Unit,
    onSelectCustom: () -> Unit,
    onDismiss: () -> Unit
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Storage location") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
              "App storage is accessible via USB at Android/data/com.heideen.smsbackup/files/.",
              style = MaterialTheme.typography.bodySmall)
          Spacer(Modifier.height(8.dp))
          StorageOption(
              "App storage (recommended)",
              currentMode == StoragePrefs.MODE_APP_EXTERNAL,
              onSelectAppExternal)
          StorageOption(
              "Choose custom folder...", currentMode == StoragePrefs.MODE_CUSTOM, onSelectCustom)
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun StorageOption(label: String, selected: Boolean, onClick: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    RadioButton(selected = selected, onClick = onClick)
    TextButton(onClick = onClick) { Text(label) }
  }
}
