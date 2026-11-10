package com.metrolist.music.backup

import android.content.Context
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.extensions.zipOutputStream
import com.metrolist.music.utils.ArtistNameAliases
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry

internal object BackupArchive {
    const val SETTINGS_FILENAME = "settings.preferences_pb"

    fun write(context: Context, database: MusicDatabase, output: OutputStream) {
        val db = database.openHelper.writableDatabase
        val snapshot = File.createTempFile("meld-db-", ".sqlite", context.cacheDir)
        try {
            // Hold the writer lock while copying a fully checkpointed database.
            // Restore reads the database itself, so pending WAL entries must be included in it.
            var copied = false
            repeat(3) {
                if (!copied) {
                    val checkpointed = db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                        cursor.moveToFirst() && cursor.getInt(0) == 0
                    }
                    if (checkpointed) {
                        db.beginTransactionNonExclusive()
                        try {
                            val path = checkNotNull(db.path)
                            if (File("$path-wal").length() <= 32L) {
                                File(path).copyTo(snapshot, overwrite = true)
                                copied = true
                            }
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }
                }
            }
            if (!copied) throw IOException("Could not snapshot the music database")

            output.buffered().zipOutputStream().use { zip ->
                val settings = File(context.filesDir, "datastore/$SETTINGS_FILENAME")
                if (settings.isFile) {
                    zip.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                    settings.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(ArtistNameAliases.BACKUP_FILENAME))
                zip.write(ArtistNameAliases.serialize().encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                snapshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        } finally {
            snapshot.delete()
        }
    }
}
