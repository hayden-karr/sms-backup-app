package com.heideen.smsbackup.backup

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.heideen.smsbackup.model.MmsAttachment
import com.heideen.smsbackup.model.MmsMessage
import com.heideen.smsbackup.model.SmsMessage
import com.heideen.smsbackup.util.HashUtil
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

class BackupDatabase(private val file: File) : Closeable {

  companion object {
    private const val VERSION = 1

    private val SCHEMA =
        listOf(
            """CREATE TABLE IF NOT EXISTS backup_metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS sms_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                original_id INTEGER,
                thread_id INTEGER,
                address TEXT,
                body TEXT,
                timestamp INTEGER NOT NULL,
                type INTEGER,
                read_status INTEGER DEFAULT 1,
                status INTEGER DEFAULT -1,
                service_center TEXT,
                dedup_hash TEXT UNIQUE
            )""",
            """CREATE TABLE IF NOT EXISTS mms_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                original_id INTEGER,
                thread_id INTEGER,
                timestamp INTEGER NOT NULL,
                msg_box INTEGER,
                read_status INTEGER DEFAULT 1,
                subject TEXT,
                addresses TEXT,
                dedup_hash TEXT UNIQUE
            )""",
            """CREATE TABLE IF NOT EXISTS mms_attachments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                mms_id INTEGER NOT NULL,
                content_type TEXT,
                filename TEXT,
                charset INTEGER,
                data BLOB,
                FOREIGN KEY (mms_id) REFERENCES mms_messages(id)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_sms_dedup ON sms_messages(dedup_hash)",
            "CREATE INDEX IF NOT EXISTS idx_mms_dedup ON mms_messages(dedup_hash)")
  }

  private var _db: SQLiteDatabase? = null
  private val db: SQLiteDatabase
    get() = _db ?: error("Call open() before using BackupDatabase")

  // Opens the database connection. Returns this for chaining with use { }.
  fun open(): BackupDatabase {
    _db = SQLiteDatabase.openOrCreateDatabase(file, null)
    return this
  }

  override fun close() {
    _db?.close()
    _db = null
  }

  fun initialize() {
    SCHEMA.forEach { db.execSQL(it) }
    db.execSQL("INSERT OR IGNORE INTO backup_metadata VALUES ('version', '$VERSION')")
    db.execSQL(
        "INSERT OR REPLACE INTO backup_metadata VALUES ('created_at', '${System.currentTimeMillis()}')")
  }

  fun validate(): Boolean {
    if (!file.exists()) return false
    return try {
      db.rawQuery("SELECT value FROM backup_metadata WHERE key='version'", null).use { c ->
        c.moveToFirst()
      }
      true
    } catch (_: Exception) {
      false
    }
  }

  suspend fun insertSms(
      messages: List<SmsMessage>,
      onProgress: suspend (Float, String) -> Unit = { _, _ -> }
  ) {
    // Same thread-local session constraint as insertMmsWithChannel: pin to one thread so
    // that onProgress suspension points can't shift the coroutine to a different IO thread
    // mid-transaction and deadlock on the write lock.
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    try {
      withContext(dispatcher) {
        db.beginTransaction()
        try {
          val stmt =
              db.compileStatement(
                  "INSERT OR IGNORE INTO sms_messages " +
                      "(original_id, thread_id, address, body, timestamp, type, read_status, status, service_center, dedup_hash) " +
                      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
          for ((index, msg) in messages.withIndex()) {
            stmt.bindLong(1, msg.id)
            stmt.bindLong(2, msg.threadId)
            if (msg.address != null) stmt.bindString(3, msg.address) else stmt.bindNull(3)
            if (msg.body != null) stmt.bindString(4, msg.body) else stmt.bindNull(4)
            stmt.bindLong(5, msg.timestamp)
            stmt.bindLong(6, msg.type.toLong())
            stmt.bindLong(7, msg.read.toLong())
            stmt.bindLong(8, msg.status.toLong())
            if (msg.serviceCenter != null) stmt.bindString(9, msg.serviceCenter)
            else stmt.bindNull(9)
            stmt.bindString(10, HashUtil.smsHash(msg))
            stmt.executeInsert()
            stmt.clearBindings()
            if (index % 100 == 0 && messages.isNotEmpty()) {
              onProgress(
                  index.toFloat() / messages.size, "Writing SMS ${index + 1}/${messages.size}...")
            }
          }
          db.setTransactionSuccessful()
        } finally {
          db.endTransaction()
        }
      }
    } finally {
      dispatcher.close()
    }
  }

  suspend fun insertMmsWithChannel(channel: ReceiveChannel<MmsMessage>): Pair<Int, Int> {
    // Android's SQLiteDatabase.beginTransaction() uses thread-local sessions.
    // If the coroutine suspends on channel.receive() and resumes on a different IO thread,
    // subsequent SQL calls hit a different session and deadlock on the write lock.
    // A single-threaded dispatcher ensures all operations (including post-suspension resumals)
    // always run on the same thread that owns the transaction.
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    try {
      return withContext(dispatcher) {
        var inserted = 0
        var errors = 0
        db.beginTransaction()
        try {
          val mmsStmt =
              db.compileStatement(
                  "INSERT OR IGNORE INTO mms_messages " +
                      "(original_id, thread_id, timestamp, msg_box, read_status, subject, addresses, dedup_hash) " +
                      "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
          val attStmt =
              db.compileStatement(
                  "INSERT INTO mms_attachments (mms_id, content_type, filename, charset, data) VALUES (?, ?, ?, ?, ?)")
          for (msg in channel) {
            try {
              val rowId = insertMmsPrepared(mmsStmt, msg)
              if (rowId != -1L) {
                insertAttachmentsPrepared(attStmt, rowId, msg.attachments)
              }
              inserted++
            } catch (_: Exception) {
              errors++
            }
          }
          db.setTransactionSuccessful()
          inserted to errors
        } finally {
          db.endTransaction()
        }
      }
    } finally {
      dispatcher.close()
    }
  }

  private fun insertMmsPrepared(stmt: SQLiteStatement, msg: MmsMessage): Long {
    stmt.bindLong(1, msg.id)
    stmt.bindLong(2, msg.threadId)
    stmt.bindLong(3, msg.timestampMs)
    stmt.bindLong(4, msg.msgBox.toLong())
    stmt.bindLong(5, msg.read.toLong())
    if (msg.subject != null) stmt.bindString(6, msg.subject) else stmt.bindNull(6)
    stmt.bindString(7, msg.addresses)
    stmt.bindString(8, HashUtil.mmsHash(msg))
    return stmt.executeInsert().also { stmt.clearBindings() }
  }

  private fun insertAttachmentsPrepared(
      stmt: SQLiteStatement,
      mmsRowId: Long,
      attachments: List<MmsAttachment>
  ) {
    for (att in attachments) {
      stmt.bindLong(1, mmsRowId)
      if (att.contentType != null) stmt.bindString(2, att.contentType) else stmt.bindNull(2)
      if (att.filename != null) stmt.bindString(3, att.filename) else stmt.bindNull(3)
      stmt.bindLong(4, att.charset.toLong())
      if (att.data != null) stmt.bindBlob(5, att.data) else stmt.bindNull(5)
      stmt.executeInsert()
      stmt.clearBindings()
    }
  }

  fun readSms(): List<SmsMessage> {
    val result = mutableListOf<SmsMessage>()
    db.rawQuery(
            "SELECT original_id, thread_id, address, body, timestamp, type, read_status, status, service_center FROM sms_messages ORDER BY timestamp ASC",
            null)
        .use { c ->
          while (c.moveToNext()) {
            result.add(
                SmsMessage(
                    id = c.getLong(0),
                    threadId = c.getLong(1),
                    address = c.getString(2),
                    body = c.getString(3),
                    timestamp = c.getLong(4),
                    type = c.getInt(5),
                    read = c.getInt(6),
                    status = c.getInt(7),
                    serviceCenter = c.getString(8)))
          }
        }
    return result
  }

  suspend fun forEachMms(block: suspend (MmsMessage) -> Unit) {
    // Read all rows into memory first — keeps cursor open only during the non-suspending
    // iteration, avoiding thread-safety issues when block() suspends and resumes on a
    // different coroutine thread.
    data class Row(
        val rowId: Long,
        val originalId: Long,
        val threadId: Long,
        val timestampMs: Long,
        val msgBox: Int,
        val read: Int,
        val subject: String?,
        val addresses: String
    )
    val rows = mutableListOf<Row>()
    db.rawQuery(
            "SELECT id, original_id, thread_id, timestamp, msg_box, read_status, subject, addresses FROM mms_messages ORDER BY timestamp ASC",
            null)
        .use { c ->
          while (c.moveToNext()) {
            rows.add(
                Row(
                    rowId = c.getLong(0),
                    originalId = c.getLong(1),
                    threadId = c.getLong(2),
                    timestampMs = c.getLong(3),
                    msgBox = c.getInt(4),
                    read = c.getInt(5),
                    subject = c.getString(6),
                    addresses = c.getString(7) ?: ""))
          }
        }
    for (row in rows) {
      val attachments = readAttachments(row.rowId)
      block(
          MmsMessage(
              id = row.originalId,
              threadId = row.threadId,
              timestampMs = row.timestampMs,
              msgBox = row.msgBox,
              read = row.read,
              subject = row.subject,
              addresses = row.addresses,
              attachments = attachments))
    }
  }

  private fun readAttachments(mmsRowId: Long): List<MmsAttachment> {
    val result = mutableListOf<MmsAttachment>()
    db.rawQuery(
            "SELECT id, content_type, filename, charset, data FROM mms_attachments WHERE mms_id = ?",
            arrayOf(mmsRowId.toString()))
        .use { c ->
          while (c.moveToNext()) {
            result.add(
                MmsAttachment(
                    id = c.getLong(0),
                    mmsId = mmsRowId,
                    contentType = c.getString(1),
                    filename = c.getString(2),
                    charset = c.getInt(3),
                    data = c.getBlob(4)))
          }
        }
    return result
  }

  fun getSmsCount(): Int {
    db.rawQuery("SELECT COUNT(*) FROM sms_messages", null).use { c ->
      return if (c.moveToFirst()) c.getInt(0) else 0
    }
  }

  fun getMmsCount(): Int {
    db.rawQuery("SELECT COUNT(*) FROM mms_messages", null).use { c ->
      return if (c.moveToFirst()) c.getInt(0) else 0
    }
  }

  fun getSmsDedupHashes(): Set<String> {
    val result = mutableSetOf<String>()
    db.rawQuery("SELECT dedup_hash FROM sms_messages WHERE dedup_hash IS NOT NULL", null).use { c ->
      while (c.moveToNext()) result.add(c.getString(0))
    }
    return result
  }

  fun getMmsDedupHashes(): Set<String> {
    val result = mutableSetOf<String>()
    db.rawQuery("SELECT dedup_hash FROM mms_messages WHERE dedup_hash IS NOT NULL", null).use { c ->
      while (c.moveToNext()) result.add(c.getString(0))
    }
    return result
  }

  fun getMediaAttachmentCount(): Int {
    db.rawQuery(
            """SELECT COUNT(*) FROM mms_attachments
               WHERE data IS NOT NULL AND length(data) > 0
               AND content_type IS NOT NULL
               AND content_type != 'text/plain'
               AND content_type != 'application/smil'""",
            null)
        .use { c ->
          return if (c.moveToFirst()) c.getInt(0) else 0
        }
  }

  suspend fun forEachMediaAttachment(block: suspend (MmsAttachment) -> Unit) {
    // Load metadata only — blobs are streamed per-attachment via writeBlobToStream.
    data class Row(
        val id: Long,
        val mmsId: Long,
        val contentType: String?,
        val filename: String?,
        val charset: Int
    )
    val rows = mutableListOf<Row>()
    db.rawQuery(
            """SELECT id, mms_id, content_type, filename, charset
               FROM mms_attachments
               WHERE data IS NOT NULL AND length(data) > 0
               AND content_type IS NOT NULL
               AND content_type != 'text/plain'
               AND content_type != 'application/smil'
               ORDER BY mms_id, id""",
            null)
        .use { c ->
          while (c.moveToNext()) {
            rows.add(Row(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getInt(4)))
          }
        }
    for (row in rows) {
      block(
          MmsAttachment(
              id = row.id,
              mmsId = row.mmsId,
              contentType = row.contentType,
              filename = row.filename,
              charset = row.charset,
              data = null))
    }
  }

  // Streams a blob in 4 MB chunks using SQLite's SUBSTR so large attachments (e.g. videos)
  // never require a single allocation of the full blob size.
  fun writeBlobToStream(attachmentId: Long, out: OutputStream) {
    val chunkSize = 4 * 1024 * 1024
    var offset = 1L // SQLite SUBSTR is 1-indexed
    while (true) {
      val chunk =
          db.rawQuery(
                  "SELECT SUBSTR(data, ?, ?) FROM mms_attachments WHERE id = ?",
                  arrayOf(offset.toString(), chunkSize.toString(), attachmentId.toString()))
              .use { c -> if (c.moveToFirst()) c.getBlob(0) else null } ?: break
      if (chunk.isEmpty()) break
      out.write(chunk)
      if (chunk.size < chunkSize) break
      offset += chunkSize
    }
  }
}
