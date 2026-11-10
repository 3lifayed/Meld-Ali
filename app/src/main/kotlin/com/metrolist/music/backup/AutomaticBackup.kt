package com.metrolist.music.backup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.TimeUnit

internal class AutomaticBackup(private val context: Context) {
    enum class Frequency(val hours: Long) {
        DAILY(24),
        EVERY_THREE_DAYS(72),
        WEEKLY(168),
    }

    data class Settings(
        val folder: Uri?,
        val folderName: String?,
        val frequency: Frequency,
        val enabled: Boolean,
        val lastSuccess: Long,
        val failed: Boolean,
    )

    private val prefs = context.getSharedPreferences("automatic_backup", Context.MODE_PRIVATE)
    private val workManager get() = WorkManager.getInstance(context)

    fun read() = Settings(
        folder = prefs.getString("folder", null)?.let(Uri::parse),
        folderName = prefs.getString("folder_name", null),
        frequency = Frequency.entries.firstOrNull { it.name == prefs.getString("frequency", null) }
            ?: Frequency.DAILY,
        enabled = prefs.getBoolean("enabled", false),
        lastSuccess = prefs.getLong("last_success", 0),
        failed = prefs.getBoolean("failed", false),
    )

    fun observe() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun selectFolder(folder: Uri) {
        val previous = read()
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(folder, flags)
        val name = try {
            val document = folderDocument(folder)
            resolver.query(
                document,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS),
                null, null, null,
            ).use { cursor ->
                check(cursor != null && cursor.moveToFirst())
                check(cursor.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
                cursor.getString(0)
            }
        } catch (e: Exception) {
            if (folder != previous.folder) resolver.releasePersistableUriPermission(folder, flags)
            throw e
        }
        val editor = prefs.edit().putString("folder", folder.toString()).putString("folder_name", name)
        if (folder != previous.folder) editor.remove("last_success").remove("failed")
        synchronized(prefs) { editor.apply() }
        if (folder != previous.folder) {
            if (previous.enabled) schedule()
            previous.folder?.let { oldFolder ->
                runCatching { resolver.releasePersistableUriPermission(oldFolder, flags) }
            }
        }
    }

    fun setFrequency(frequency: Frequency) {
        if (read().frequency == frequency) return
        prefs.edit().putString("frequency", frequency.name).apply()
        if (read().enabled) schedule()
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled && read().folder == null) return
        prefs.edit().putBoolean("enabled", enabled).apply()
        if (enabled) schedule() else workManager.cancelUniqueWork(PERIODIC)
    }

    fun runNow() {
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AutomaticBackupWorker>().build())
    }

    fun record(folder: Uri, success: Boolean) = synchronized(prefs) {
        if (read().folder != folder) return@synchronized
        val editor = prefs.edit().putBoolean("failed", !success)
        if (success) editor.putLong("last_success", System.currentTimeMillis())
        editor.apply()
    }

    private fun schedule() {
        val settings = read()
        val hours = settings.frequency.hours
        val network = when (settings.folder?.authority) {
            "com.android.externalstorage.documents", "com.android.providers.downloads.documents" ->
                NetworkType.NOT_REQUIRED
            else -> NetworkType.UNMETERED
        }
        val work = PeriodicWorkRequestBuilder<AutomaticBackupWorker>(hours, TimeUnit.HOURS)
            .setInitialDelay(hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setInputData(workDataOf(SCHEDULED to true))
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, work)
    }

    companion object {
        const val SCHEDULED = "scheduled"
        private const val PERIODIC = "meld-automatic-backup"
        private const val NOW = "meld-backup-now"

        fun folderDocument(folder: Uri): Uri =
            DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
    }
}
