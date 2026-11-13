package com.kotlistine.transferingfilebyair.core.transfer.lan

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Files as Android hands them out: `content://` URIs read through the content resolver. Incoming files land in the
 * app's cache (as `file:` URIs, which the received-file store copies to Downloads like a Nearby file).
 */
internal class ContentLanFiles(context: Context) : LanFiles {
    private val resolver = context.applicationContext.contentResolver
    private val incoming = DiskLanFiles(File(context.cacheDir, "lan-incoming"))

    override fun open(uri: String): OpenedFile? = runCatching {
        val parsed = Uri.parse(uri)
        // The receiver checks it got exactly this many bytes, so a file of unknown size can't go.
        val size = sizeOf(parsed)?.takeIf { it >= 0 } ?: return null
        val stream = resolver.openInputStream(parsed) ?: return null
        OpenedFile(stream.buffered(), size)
    }.getOrNull()

    override fun createIncoming(transferId: Long): IncomingFile = incoming.createIncoming(transferId)

    override fun scratch(name: String): File = incoming.scratch(name)

    private fun sizeOf(uri: Uri): Long? {
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length >= 0) return descriptor.length
        }
        return resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }
}
