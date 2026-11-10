package com.metrolist.music.backup

import android.content.Context
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class AutomaticBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun musicDatabase(): MusicDatabase
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        backupLock.withLock {
            val context = applicationContext
            val backup = AutomaticBackup(context)
            val settings = backup.read()
            val folder = settings.folder ?: return@withLock Result.failure()
            if (inputData.getBoolean(AutomaticBackup.SCHEDULED, false) && !settings.enabled) {
                return@withLock Result.success()
            }
            val resolver = context.contentResolver
            try {
                if (resolver.persistedUriPermissions.none { it.uri == folder && it.isWritePermission }) {
                    throw SecurityException("Backup folder permission is missing")
                }
                val archive = File.createTempFile("meld-backup-", ".tmp", context.cacheDir)
                try {
                    val database = EntryPointAccessors.fromApplication(context, Dependencies::class.java).musicDatabase()
                    archive.outputStream().use { BackupArchive.write(context, database, it) }
                    ensureActive()
                    if (backup.read().folder != folder) return@withLock Result.success()
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT))
                    val fileName = "${context.getString(R.string.app_name)}_$stamp.backup"
                    val document = DocumentsContract.createDocument(
                        resolver, AutomaticBackup.folderDocument(folder), "application/octet-stream", fileName,
                    ) ?: throw IOException("Could not create backup document")
                    try {
                        val output = resolver.openOutputStream(document, "wt")
                            ?: throw IOException("Could not open backup document")
                        output.use { stream -> archive.inputStream().use { it.copyTo(stream) } }
                        ensureActive()
                    } catch (e: Exception) {
                        runCatching { DocumentsContract.deleteDocument(resolver, document) }
                        throw e
                    }
                } finally {
                    archive.delete()
                }
                backup.record(folder, success = true)
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                backup.record(folder, success = false)
                Result.failure()
            } catch (e: Exception) {
                backup.record(folder, success = false)
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
        }
    }

    private companion object {
        val backupLock = Mutex()
    }
}
