package com.minar.birday.preferences.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AttributeSet
import android.view.View
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.sqlite.db.SimpleSQLiteQuery
import com.minar.birday.R
import com.minar.birday.activities.MainActivity
import com.minar.birday.persistence.EventDatabase
import java.io.File
import java.io.IOException
import java.time.LocalDate

class BirdayExporter(context: Context, attrs: AttributeSet?) : Preference(context, attrs),
    View.OnClickListener {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val v = holder.itemView
        v.setOnClickListener(this)
    }

    // Vibrate, request SAF target, then export and share
    override fun onClick(v: View) {
        val act = context as MainActivity
        act.vibrate()
        if (act.mainViewModel.allEventsUnfiltered.value.isNullOrEmpty()) {
            act.showSnackbar(context.getString(R.string.no_events))
            return
        }
        val fileName = "BirdayBackup_${LocalDate.now()}"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        act.saveBackup.launch(intent)
    }

    companion object {
        private const val AUTO_BACKUP_FILE_NAME = "BirdayBackup_auto"

        // Export to SAF file Uri, SAF folder tree Uri, or internal app folder
        fun exportEvents(
            context: Context,
            uri: Uri?,
            autoBackup: Boolean = false
        ): String {
            val database = EventDatabase.getBirdayDatabase(context)
            val eventDao = database.eventDao()
            // The settings go in the copy too, in a table older versions ignore
            runCatching { writeBackupSettings(context, database.openHelper.writableDatabase) }
            // Checkpoint WAL to flush DB to disk
            eventDao.checkpoint(SimpleSQLiteQuery("pragma wal_checkpoint(full)"))
            val dbFile = context.getDatabasePath("BirdayDB").absoluteFile
            if (!dbFile.exists() || dbFile.length() == 0L) {
                handleResult(autoBackup, false, context, false)
                return ""
            }
            try {
                if (uri != null && uri.toString().isNotBlank()) {
                    val targetFileUri = if (DocumentsContract.isTreeUri(uri)) {
                        resolveOrCreateFileInTree(context, uri, AUTO_BACKUP_FILE_NAME)
                            ?: throw IOException("Cannot create or access file in folder: $uri")
                    } else {
                        uri
                    }

                    // Truncate ("wt") so previous content is cleanly overwritten
                    (context.contentResolver.openOutputStream(targetFileUri, "wt")
                        ?: context.contentResolver.openOutputStream(targetFileUri))?.use { os ->
                        dbFile.inputStream().use { fis ->
                            fis.copyTo(os)
                            os.flush()
                        }
                    } ?: throw IOException("Cannot open output stream for uri: $targetFileUri")

                    if (!DocumentsContract.isTreeUri(uri)) {
                        try {
                            val takeFlags =
                                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                        } catch (_: Exception) {
                        }
                    }

                    handleResult(autoBackup, true, context, true)
                    return targetFileUri.toString()
                } else {
                    // Legacy behavior: write to app files dir
                    val appDirectory = File(context.getExternalFilesDir(null)!!.absolutePath)
                    val fileName =
                        if (autoBackup) AUTO_BACKUP_FILE_NAME else "BirdayBackup_${LocalDate.now()}"
                    val fileFullPath: String = appDirectory.path + File.separator + fileName
                    try {
                        dbFile.copyTo(File(fileFullPath), true)
                        handleResult(autoBackup, true, context, false)
                    } catch (e: Exception) {
                        handleResult(autoBackup, false, context, false)
                        e.printStackTrace()
                        return ""
                    }
                    return fileFullPath
                }
            } catch (e: Exception) {
                e.printStackTrace()
                handleResult(autoBackup, false, context, true)
                return ""
            }
        }

        private fun resolveOrCreateFileInTree(
            context: Context,
            treeUri: Uri,
            displayName: String
        ): Uri? {
            val resolver = context.contentResolver
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (idIdx >= 0 && nameIdx >= 0 && cursor.getString(nameIdx) == displayName) {
                        val existingDocId = cursor.getString(idIdx)
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDocId)
                    }
                }
            }

            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            return DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                "application/octet-stream",
                displayName
            )
        }

        // Show a toast or a snackbar depending on the result
        private fun handleResult(
            autoBackup: Boolean = false,
            success: Boolean = true,
            context: Context,
            onlyToast: Boolean = false
        ) {
            val resultString =
                if (success) context.getString(R.string.birday_export_success)
                else context.getString(R.string.birday_export_failure)
            (context as? MainActivity)?.runOnUiThread {
                if (!autoBackup && !onlyToast)
                    context.showSnackbar(resultString)
                else {
                    Toast.makeText(
                        context,
                        resultString,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
