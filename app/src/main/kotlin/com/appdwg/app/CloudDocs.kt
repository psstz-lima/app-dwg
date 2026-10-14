package com.appdwg.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Google Drive through Android's document picker (no sign-in in the app: Drive's own app gives
 * each user their own Drive). The picker opens in Drive when it is installed; files picked there
 * keep a lasting permission, so Salvar writes back to them and Recentes reopens them.
 */
object CloudDocs {
    const val DRIVE = "com.google.android.apps.docs.storage"
    private const val DRIVE_APP = "com.google.android.apps.docs"

    fun isDrive(uri: String?) = uri != null && Uri.parse(uri).authority?.startsWith(DRIVE) == true

    fun driveInstalled(context: Context) = runCatching { context.packageManager.getPackageInfo(DRIVE_APP, 0); true }.getOrDefault(false)

    /** Where the picker should start in Drive. */
    fun driveStart(context: Context): Uri {
        // Drive's root for the first Google account ("1"); the picker accepts a root there but not a
        // document, and a wrong guess just opens it where it was.
        return DocumentsContract.buildRootUri(DRIVE, "1")
    }

    /** Remembers the last Drive file used (kept for a later "recent folder" shortcut). */
    fun noteUsed(context: Context, uri: Uri) {
        if (isDrive(uri.toString())) context.getSharedPreferences("cloud", Context.MODE_PRIVATE).edit().putString("lastDrive", uri.toString()).apply()
    }
}

/** Open picker that can start at a given place (Drive); [start] is read when launched. */
class OpenDocumentAt(private val start: () -> Uri?) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply { start()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }
}

/** Picker for several files that can start at a given place (Drive). */
class OpenMultipleDocumentsAt(private val start: () -> Uri?) : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply { start()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }
}

/** Save picker that can start at a given place (Drive). */
class CreateDocumentAt(mime: String, private val start: () -> Uri?) : ActivityResultContracts.CreateDocument(mime) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).apply { start()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }
}
